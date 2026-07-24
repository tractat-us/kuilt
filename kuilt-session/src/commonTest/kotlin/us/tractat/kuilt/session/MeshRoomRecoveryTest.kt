@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.session

import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.session.partition.DefaultJoinerReconnectController
import us.tractat.kuilt.test.FaultySeam
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The **recovery** and **window-expiry** arcs of the presence state machine, at **mesh scale**
 * (host + two members) — the two transitions the existing 2-peer
 * [us.tractat.kuilt.conformance.RoomConformanceSuite] tests and the [StarTopologyPresenceFanoutTest]
 * only partially cover, and the missing complement to the `Partitioned` / `WindowOpened` mesh
 * coverage. Part of the #1618 presence-on-drop hardening.
 *
 * ### Why a real mesh (not a star)
 *
 * Every room here runs one fast [config] — so **every** member has a live heartbeat edge to every
 * other member, a true mesh. That is exactly what makes the **survivor-untouched** assertions
 * load-bearing: when one member silently drops, the survivor's own edge to the host is never
 * faulted, so a correct implementation must leave the survivor [Liveness.Connected] and fire **no**
 * `Partitioned`/`Left` for it — even as it (legitimately) observes the *dropped* peer vanish. A bug
 * that over-reacts to one member's loss by disturbing another member's seat would surface here and
 * nowhere in the 2-peer suites.
 *
 * ### The drop model
 *
 * One member's seam is wrapped in a [FaultySeam] and [partitioned][FaultySeam.partition] — frames
 * dropped both ways, `peers`/`state` untouched: a silent Wi-Fi loss, the case the reconnect window
 * exists for.
 */
class MeshRoomRecoveryTest {

    /**
     * Fast detection with a **generous** reconnect window (2 s) so the recovery arc — detect, heal,
     * recover — completes comfortably inside the window without racing eviction.
     */
    private val config = HeartbeatConfig(
        interval = 100.milliseconds,
        timeout = 300.milliseconds,
        reconnectWindow = 2.seconds,
    )

    /**
     * A three-peer mesh: a host, a [droppedLink] member faulted mid-test, and a [survivor] whose
     * edge to the host is never touched.
     */
    private class Mesh(
        val host: Room,
        val droppedLink: FaultySeam,
        val droppedId: PeerId,
        val survivor: Room,
        val survivorId: PeerId,
    )

    private suspend fun kotlinx.coroutines.test.TestScope.mesh(): Mesh {
        val loom = InMemoryLoom()
        val clock: () -> Instant = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }
        val factory = SeamRoomFactory(loom, backgroundScope, clock, config)

        val host = factory.host(Pattern("Host"))
        val droppedLink = FaultySeam(loom.join(InMemoryTag("Dropped")), backgroundScope)
        val droppedRoom = factory.adopt(droppedLink, SessionRole.Joiner)
        val survivor = factory.join(InMemoryTag("Survivor"))

        host.roster.first { it.size == 2 }
        droppedRoom.roster.first { it.size == 2 }
        survivor.roster.first { it.size == 2 }

        return Mesh(host, droppedLink, droppedRoom.selfId, survivor, survivor.selfId)
    }

    /**
     * A member that silently drops and then heals **before** its reconnect window expires must fire
     * [MembershipEvent.Recovered] on the host and return to [Liveness.Connected] — while the survivor
     * stays untouched throughout.
     */
    @Test
    fun `a dropped member that heals before the window recovers on the host`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val mesh = mesh()

            val partitionedForDropped = mutableListOf<MembershipEvent.Partitioned>()
            val recoveredForDropped = mutableListOf<MembershipEvent.Recovered>()
            val disturbedSurvivor = mutableListOf<MembershipEvent>()
            backgroundScope.launch {
                mesh.host.events.collect { event ->
                    when (event) {
                        is MembershipEvent.Partitioned ->
                            if (event.peerId == mesh.droppedId) partitionedForDropped += event
                            else if (event.peerId == mesh.survivorId) disturbedSurvivor += event
                        is MembershipEvent.Recovered ->
                            if (event.peerId == mesh.droppedId) recoveredForDropped += event
                        is MembershipEvent.Left ->
                            if (event.peerId == mesh.survivorId) disturbedSurvivor += event
                        else -> Unit
                    }
                }
            }
            testScheduler.runCurrent()

            // Detect the drop (past timeout, short of the window).
            mesh.droppedLink.partition()
            testScheduler.advanceTimeBy(config.timeout + config.interval * 3)
            testScheduler.runCurrent()
            assertEquals(
                1,
                partitionedForDropped.size,
                "sanity: the host must first see the dropped member Partitioned — observed $partitionedForDropped",
            )

            // Heal well inside the 2 s window, then let ping/pong resume.
            mesh.droppedLink.heal()
            testScheduler.advanceTimeBy(config.interval * 6)
            testScheduler.runCurrent()

            assertAll(
                {
                    assertEquals(
                        1,
                        recoveredForDropped.size,
                        "the healed member must fire exactly one Recovered — observed $recoveredForDropped",
                    )
                },
                {
                    assertEquals(
                        Liveness.Connected,
                        mesh.host.roster.value.first { it.id == mesh.droppedId }.liveness,
                        "the recovered member's roster entry must read Connected again",
                    )
                },
                {
                    assertEquals(
                        Liveness.Connected,
                        mesh.host.roster.value.first { it.id == mesh.survivorId }.liveness,
                        "the survivor must stay Connected throughout",
                    )
                },
                {
                    assertTrue(
                        disturbedSurvivor.isEmpty(),
                        "the survivor's seat must never be disturbed by another member's drop — observed $disturbedSurvivor",
                    )
                },
            )

            mesh.host.leave()
            mesh.survivor.leave()
        }

    /**
     * A member whose reconnect window **expires** must be evicted from the host with
     * [LeaveReason.PartitionExpired] — while the survivor stays admitted and Connected.
     */
    @Test
    fun `a dropped member whose window expires is evicted while the survivor stays`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val mesh = mesh()

            val leftForDropped = mutableListOf<MembershipEvent.Left>()
            val leftForSurvivor = mutableListOf<MembershipEvent.Left>()
            backgroundScope.launch {
                mesh.host.events.filterIsInstance<MembershipEvent.Left>().collect { event ->
                    when (event.peerId) {
                        mesh.droppedId -> leftForDropped += event
                        mesh.survivorId -> leftForSurvivor += event
                        else -> Unit
                    }
                }
            }
            testScheduler.runCurrent()

            mesh.droppedLink.partition()
            // Past the full reconnect window so the held seat expires.
            testScheduler.advanceTimeBy(config.timeout + config.reconnectWindow + config.interval * 4)
            testScheduler.runCurrent()

            assertAll(
                {
                    assertEquals(
                        1,
                        leftForDropped.size,
                        "the expired member must be evicted from the host exactly once — observed $leftForDropped",
                    )
                },
                {
                    assertEquals(
                        LeaveReason.PartitionExpired,
                        leftForDropped.firstOrNull()?.reason,
                        "an expired seat is not a clean leave — the reason must be PartitionExpired",
                    )
                },
                {
                    assertEquals(
                        emptyList(),
                        mesh.host.roster.value.filter { it.id == mesh.droppedId },
                        "the expired member must be gone from the host's roster",
                    )
                },
                {
                    assertTrue(
                        leftForSurvivor.isEmpty(),
                        "the survivor must never be evicted by another member's expiry — observed $leftForSurvivor",
                    )
                },
                {
                    assertEquals(
                        Liveness.Connected,
                        mesh.host.roster.value.first { it.id == mesh.survivorId }.liveness,
                        "the survivor must remain admitted and Connected",
                    )
                },
            )

            mesh.host.leave()
            mesh.survivor.leave()
        }

    /**
     * #1618 Track C — the **host-eviction single-point-of-failure**.
     *
     * On real hardware (a 2-phone airplane drop) the host fired `Partitioned` but the detector's
     * [us.tractat.kuilt.liveness.PartitionEvent.PeerLost] — the *only* evictor — never matured, so
     * the host stuck in `Partitioned` forever and never emitted [MembershipEvent.Left].
     *
     * The reconnect window ([JoinerReconnectController]) is an **independent** timer. This test
     * decouples the two — a **long** detector window (10 s, so `PeerLost` cannot fire inside the
     * advance) against a **short** injected reconnect window (400 ms) — to prove the host still
     * resolves the seat on [JoinerReconnectEvent.WindowExpired] with the member **still Partitioned**,
     * even though `PeerLost` is absent. The survivor's seat must stay untouched, and a later
     * (real-timing) `PeerLost` must not double-evict.
     */
    @Test
    fun `host evicts a still-partitioned member on window expiry even when PeerLost never fires`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            // Detector window far longer than anything this test advances: PeerLost, the pre-fix
            // sole evictor, is effectively absent.
            val longDetectorWindow = HeartbeatConfig(
                interval = 100.milliseconds,
                timeout = 300.milliseconds,
                reconnectWindow = 10.seconds,
            )
            val shortWindowMs = 400L

            val loom = InMemoryLoom()
            val clock: () -> Instant = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }
            // Inject a reconnect controller whose window is SHORT and independent of the detector's.
            val controllerFactory: JoinerReconnectControllerFactory = { roomId, scope, c ->
                DefaultJoinerReconnectController(
                    roomId = roomId,
                    reconnectWindowMs = shortWindowMs,
                    clock = { c().toEpochMilliseconds() },
                    scope = scope,
                )
            }
            val factory = SeamRoomFactory(
                loom,
                backgroundScope,
                clock,
                longDetectorWindow,
                reconnectControllerFactory = controllerFactory,
            )

            val host = factory.host(Pattern("Host"))
            val droppedLink = FaultySeam(loom.join(InMemoryTag("Dropped")), backgroundScope)
            val droppedRoom = factory.adopt(droppedLink, SessionRole.Joiner)
            val survivor = factory.join(InMemoryTag("Survivor"))
            host.roster.first { it.size == 2 }
            droppedRoom.roster.first { it.size == 2 }
            survivor.roster.first { it.size == 2 }
            val droppedId = droppedRoom.selfId
            val survivorId = survivor.selfId

            val leftForDropped = mutableListOf<MembershipEvent.Left>()
            val leftForSurvivor = mutableListOf<MembershipEvent.Left>()
            backgroundScope.launch {
                host.events.filterIsInstance<MembershipEvent.Left>().collect { event ->
                    when (event.peerId) {
                        droppedId -> leftForDropped += event
                        survivorId -> leftForSurvivor += event
                        else -> Unit
                    }
                }
            }
            testScheduler.runCurrent()

            droppedLink.partition()
            // Past detection (timeout) and the SHORT reconnect window, but far short of the 10 s
            // detector window — so only the WindowExpired backstop can evict.
            testScheduler.advanceTimeBy(
                longDetectorWindow.timeout + shortWindowMs.milliseconds + longDetectorWindow.interval * 4,
            )
            testScheduler.runCurrent()

            assertAll(
                {
                    assertEquals(
                        1,
                        leftForDropped.size,
                        "a still-Partitioned member must be evicted on WindowExpired even without PeerLost — observed $leftForDropped",
                    )
                },
                {
                    assertEquals(
                        LeaveReason.PartitionExpired,
                        leftForDropped.firstOrNull()?.reason,
                        "an expired seat is not a clean leave — the reason must be PartitionExpired",
                    )
                },
                {
                    assertEquals(
                        emptyList(),
                        host.roster.value.filter { it.id == droppedId },
                        "the evicted member must be gone from the host's roster",
                    )
                },
                {
                    assertTrue(
                        leftForSurvivor.isEmpty(),
                        "the survivor must never be evicted by another member's expiry — observed $leftForSurvivor",
                    )
                },
            )

            // Idempotence: advance past the long detector window so a late PeerLost would also fire.
            // removeFromRoster already guards duplicate Left, so exactly one eviction survives.
            testScheduler.advanceTimeBy(longDetectorWindow.reconnectWindow)
            testScheduler.runCurrent()
            assertEquals(
                1,
                leftForDropped.size,
                "a double-evict (WindowExpired then a late PeerLost) must stay idempotent — observed $leftForDropped",
            )

            host.leave()
            survivor.leave()
        }
}
