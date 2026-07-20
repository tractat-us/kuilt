@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.session

import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.test.FaultySeam
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.fabric.InMemoryRoomFabric
import kotlin.coroutines.ContinuationInterceptor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * A liveness detector must only be started for a peer this member can actually **reach** (#1576).
 *
 * [SeamRoom] used to start a [us.tractat.kuilt.liveness.HeartbeatPartitionDetector] for every
 * admitted member, taken from the *room* roster. On a star/hub fabric a joiner has no route to a
 * co-joiner (`docs/fabric-peer-routing.md`), so its pings could never be answered — and because
 * the detector's **timeout** branch is not gated on the peer being in `link.peers`, that silence
 * matured into `PeerUnresponsive(Timeout)` → `PeerLost` → `Left(PartitionExpired)`. A perfectly
 * healthy member was evicted from every joiner's roster.
 *
 * The fix gates detector startup on a real liveness edge: the peer must be in the seam's own
 * `peers` set. These tests pin both halves — no spurious eviction where there is no route, and no
 * loss of partition detection where there is one.
 */
class LivenessRouteGateTest {

    /** Fast enough that partition → window expiry fits comfortably in ~2 s of virtual time. */
    private val fastConfig = HeartbeatConfig(
        interval = 100.milliseconds,
        timeout = 300.milliseconds,
        reconnectWindow = 1.seconds,
    )

    /**
     * Deliberately longer than any test's advancement budget, so a room running this config can
     * never be the source of an observation about a peer within the window under test.
     */
    private val slowConfig = HeartbeatConfig(
        interval = 10.seconds,
        timeout = 60.seconds,
        reconnectWindow = 60.seconds,
    )

    /** Past `fastConfig`'s timeout *and* its whole reconnect window, with margin. */
    private val expiryBudget = 2.seconds

    // ── The bug: no route ⇒ no detector ⇒ no spurious eviction ────────────────

    /**
     * The headline. On a real hub (`MuxServerLoom` + `MuxClientLoom`, the star fabric in
     * `:kuilt-core`) two joiners share a roster but have no route to each other. Neither is
     * unhealthy — the host sees both — so neither may be evicted from the other's roster.
     *
     * Before the fix this failed with a `Left(PartitionExpired)` for a peer that never went away.
     */
    @Test
    fun `a joiner does not evict an unroutable co-joiner`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val star = star()

            val evictions = mutableListOf<MembershipEvent.Left>()
            backgroundScope.launch {
                star.joinerA.events
                    .filterIsInstance<MembershipEvent.Left>()
                    .collect { if (it.peerId == star.joinerBId) evictions += it }
            }
            testScheduler.runCurrent()

            testScheduler.advanceTimeBy(expiryBudget)
            testScheduler.runCurrent()

            assertAll(
                {
                    assertEquals(
                        emptyList(),
                        evictions,
                        "a co-joiner this member cannot address must never be evicted — it is " +
                            "healthy, and the host (which can reach it) says so",
                    )
                },
                {
                    assertTrue(
                        star.joinerBId in star.joinerA.roster.value.map { it.id },
                        "the healthy co-joiner must still be in the roster",
                    )
                },
                {
                    assertEquals(
                        2,
                        star.host.roster.value.size,
                        "sanity: the host, which has an edge to both joiners, still sees both",
                    )
                },
            )
        }

    /**
     * The mechanism, asserted directly: no detector is registered for a peer outside the seam's
     * `peers` set, while the host — which every spoke *can* reach — keeps one.
     */
    @Test
    fun `a joiner starts a detector for the host but not for an unroutable co-joiner`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val star = star()
            val a = star.joinerA as SeamRoom

            assertAll(
                {
                    assertFalse(
                        a.hasDetector(star.joinerBId),
                        "no liveness edge to the co-joiner ⇒ no detector for it",
                    )
                },
                {
                    assertTrue(
                        a.hasDetector(star.hostId),
                        "the host is directly reachable, so its detector must still run",
                    )
                },
                {
                    assertEquals(
                        setOf(a.selfId, star.hostId),
                        star.joinerASeamPeers(),
                        "sanity: a spoke's seam knows only itself and the host",
                    )
                },
            )
        }

    // ── The control: a real edge still detects, and still evicts ──────────────

    /**
     * The fix must not disable partition detection where it works. On a full mesh
     * ([InMemoryLoom]) every member has a direct edge to every other, so a bystander's **own**
     * detector must still mature a genuinely-silent peer into `Left(PartitionExpired)`.
     *
     * The host and the dropped joiner run [slowConfig], so the host's authoritative fan-out
     * cannot fire inside the budget — the eviction observed here can only have come from the
     * bystander's own detector.
     */
    @Test
    fun `a mesh member still evicts a genuinely silent peer it can reach`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val clock: () -> Instant = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }
            val slowFactory = SeamRoomFactory(loom, backgroundScope, clock, slowConfig)
            val fastFactory = SeamRoomFactory(loom, backgroundScope, clock, fastConfig)

            val hostRoom = slowFactory.host(Pattern("Mesh"))
            val droppedLink = FaultySeam(loom.join(InMemoryTag("Dropped")), backgroundScope)
            val droppedRoom = slowFactory.adopt(droppedLink, SessionRole.Joiner)
            val bystander = fastFactory.join(InMemoryTag("Bystander"))

            hostRoom.roster.first { it.size == 2 }
            droppedRoom.roster.first { it.size == 2 }
            bystander.roster.first { it.size == 2 }
            val droppedId = droppedRoom.selfId

            assertTrue(
                (bystander as SeamRoom).hasDetector(droppedId),
                "a mesh peer is directly reachable, so a detector must be started for it",
            )

            val evictions = mutableListOf<MembershipEvent.Left>()
            backgroundScope.launch {
                bystander.events
                    .filterIsInstance<MembershipEvent.Left>()
                    .collect { if (it.peerId == droppedId) evictions += it }
            }
            testScheduler.runCurrent()

            droppedLink.partition()
            testScheduler.advanceTimeBy(expiryBudget)
            testScheduler.runCurrent()

            assertAll(
                {
                    assertEquals(
                        listOf(LeaveReason.PartitionExpired),
                        evictions.map { it.reason },
                        "a reachable peer that really goes silent must still be evicted by this " +
                            "member's own detector — observed $evictions",
                    )
                },
                {
                    assertFalse(
                        droppedId in bystander.roster.value.map { it.id },
                        "…and must be gone from the roster",
                    )
                },
            )
        }

    // ── Harness ───────────────────────────────────────────────────────────────

    /** A three-member star over the real hub fabric: one host and two mutually-unroutable joiners. */
    private class Star(
        val host: Room,
        val hostId: PeerId,
        val joinerA: Room,
        val joinerBId: PeerId,
        val joinerASeamPeers: () -> Set<PeerId>,
    )

    private suspend fun TestScope.star(): Star {
        val dispatcher = requireNotNull(coroutineContext[ContinuationInterceptor]) {
            "no dispatcher (ContinuationInterceptor) in coroutine context"
        }
        val clock: () -> Instant = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }
        val fabric = InMemoryRoomFabric(backgroundScope, dispatcher, random = Random(0L))

        val hostFactory = SeamRoomFactory(fabric.serverLoom, backgroundScope, clock, fastConfig)
        val hostRoom = hostFactory.host(Pattern(ROOM))

        val aLoom = fabric.clientLoom(PeerId("joiner-a"), Random(1L))
        val bLoom = fabric.clientLoom(PeerId("joiner-b"), Random(2L))
        // `adopt` (rather than `join`) so the test keeps the joiner's own seam handle and can
        // assert what its `peers` set actually contains.
        val aSeam = aLoom.join(InMemoryTag(ROOM))
        val bSeam = bLoom.join(InMemoryTag(ROOM))
        val aRoom = SeamRoomFactory(aLoom, backgroundScope, clock, fastConfig)
            .adopt(aSeam, SessionRole.Joiner)
        val bRoom = SeamRoomFactory(bLoom, backgroundScope, clock, fastConfig)
            .adopt(bSeam, SessionRole.Joiner)

        hostRoom.roster.first { it.size == 2 }
        aRoom.roster.first { it.size == 2 }
        bRoom.roster.first { it.size == 2 }

        return Star(
            host = hostRoom,
            hostId = hostRoom.selfId,
            joinerA = aRoom,
            joinerBId = bRoom.selfId,
            joinerASeamPeers = { aSeam.peers.value },
        )
    }

    private companion object {
        const val ROOM = "table"
    }
}
