@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.session

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
import us.tractat.kuilt.session.partition.DefaultJoinerReconnectController
import us.tractat.kuilt.test.FaultySeam
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Evidence pass for #2556 — **the arc [MeshRoomRecoveryTest] stops one step short of.**
 *
 * `MeshRoomRecoveryTest`'s recover-within-window case advances `timeout + interval * 9` against a
 * 2 s reconnect window, so it observes the recovery and then ends. It never crosses the window's
 * own deadline, and everything this file is about happens *after* that crossing: the host's
 * [us.tractat.kuilt.session.partition.JoinerReconnectController] timer is still armed for a member
 * that already came back, because nothing on the recovery path closes it —
 * [SeamRoom]'s `markRecovered` tells the controller nothing, and no production code calls
 * [us.tractat.kuilt.session.partition.JoinerReconnectController.expire].
 *
 * When that stale timer fires, `runReconnectEventLoop`'s `WindowExpired` arm runs
 * `propagateFarewell(peerId, expired = true)` **before** any liveness check. The host's own
 * eviction backstop (`evictOnExpiredWindowIfPartitioned`) then correctly declines — the member is
 * [Liveness.Connected] again — but the `Farewell` has already been enqueued on every *other*
 * member's admit lane, and `handleFarewell` removes it with no liveness check and no re-admit path.
 *
 * The subject of the fan-out is excluded from it ([SeamRoom]'s `fanOutToOtherMembers` filters
 * `it != subject`), so the recovered member is never told. In a room of three that is a permanent
 * three-way split: the host holds the member `Connected`, the survivor has evicted it, and the
 * member believes it is still seated.
 *
 * ### Why this is a mesh and why it must run past the window
 *
 * Same harness shape as [MeshRoomRecoveryTest] — a host plus two joiners on one [InMemoryLoom],
 * every peer holding a live heartbeat edge to every other. The drop is a [FaultySeam.partition]:
 * frames dropped both ways with `peers`/`state` untouched, which is what makes the detector report
 * [us.tractat.kuilt.liveness.PartitionEvent.Reason.Timeout] rather than `TransportClosed` — the
 * "silent Wi-Fi loss" lane that routes to `markPartitioned` (and so arms the controller) rather
 * than to the joiner resume machine.
 *
 * ### Both tests are [Ignore]d, and that is the point
 *
 * They assert the **correct** behaviour and both fail on `main`. This is an evidence branch, not a
 * fix, so they are marked [Ignore] rather than left red — nothing here changes production source,
 * and a green branch must not be mistaken for a repaired one. **The fix PR un-ignores them; it does
 * not weaken them.** The observed failures, from
 * `./gradlew :kuilt-session:jvmTest --tests "*MeshRoomRecoveredWindowExpiryTest*"` on
 * `origin/main` @ `f0813cd0`, are quoted on each test.
 */
class MeshRoomRecoveredWindowExpiryTest {

    /** Fast detection, 2 s reconnect window — [MeshRoomRecoveryTest]'s configuration verbatim. */
    private val config = HeartbeatConfig(
        interval = 100.milliseconds,
        timeout = 300.milliseconds,
        reconnectWindow = 2.seconds,
    )

    /**
     * A three-peer mesh. Unlike [MeshRoomRecoveryTest]'s `Mesh` this keeps the dropped peer's own
     * [Room], because the third roster is half the finding: the member that everyone else is
     * arguing about has an opinion too, and it is never consulted.
     */
    private class Mesh(
        val host: Room,
        val droppedLink: FaultySeam,
        val dropped: Room,
        val survivor: Room,
    )

    private suspend fun TestScope.mesh(): Mesh {
        val loom = InMemoryLoom()
        val clock: () -> Instant = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }
        val factory = SeamRoomFactory(loom, backgroundScope, clock, config)

        val host = factory.host(Pattern("Host"))
        val droppedLink = FaultySeam(loom.join(InMemoryTag("Dropped")), backgroundScope)
        val dropped = factory.adopt(droppedLink, SessionRole.Joiner)
        val survivor = factory.join(InMemoryTag("Survivor"))

        // Two, not three: [Room.roster] excludes the reading peer itself.
        host.roster.first { it.size == 2 }
        dropped.roster.first { it.size == 2 }
        survivor.roster.first { it.size == 2 }

        return Mesh(host, droppedLink, dropped, survivor)
    }

    /**
     * Renders one room's roster as `id=liveness` pairs in a stable order — **identities and state,
     * never a size.** A count says a roster changed; only the identities say which seat moved and
     * to what, and the whole question here is whether three rooms disagree about one named peer.
     */
    private fun Room.rosterLine(label: String): String =
        "$label(self=${selfId.value}) " +
            roster.value
                .sortedBy { it.id.value }
                .joinToString(prefix = "[", postfix = "]") { "${it.id.value}=${it.liveness.render()}" }

    private fun Liveness.render(): String = when (this) {
        is Liveness.Connected -> "Connected"
        is Liveness.Partitioned -> "Partitioned(until=${windowExpiresAt.toEpochMilliseconds()})"
        else -> this::class.simpleName ?: "?"
    }

    private fun Room.holds(peer: PeerId): Boolean = roster.value.any { it.id == peer }

    /**
     * Who this room believes is in the room, **including itself** — the only cross-room-comparable
     * form of [Room.roster], which excludes the reading peer by contract.
     */
    private fun Room.membershipView(): List<String> =
        (roster.value.map { it.id.value } + selfId.value).sorted()

    /**
     * **The #2556 reproducer.** A member drops silently, recovers well inside its reconnect window,
     * and the room then runs past the window's original deadline. Every peer must still agree that
     * the recovered member is seated.
     *
     * Asserts the *correct* behaviour, so it fails on `main`:
     *
     * ```
     * #2556 ids: host=peer-1 dropped=peer-2 survivor=peer-3
     * #2556 t=1200 step=recovered
     *   host(self=peer-1)     [peer-2=Connected, peer-3=Connected]
     *   survivor(self=peer-3) [peer-1=Connected, peer-2=Connected]
     * #2556 t=3600 step=past-window
     *   host(self=peer-1)     [peer-2=Connected, peer-3=Connected]
     *   dropped(self=peer-2)  [peer-1=Connected, peer-3=Partitioned(until=4600)]
     *   survivor(self=peer-3) [peer-1=Connected]
     *
     * AssertionError: 2 assertion(s) failed:
     *   - the survivor must still seat the recovered member … expected:<true> but was:<false>
     *   - host and survivor must agree … expected:<[peer-1, peer-2, peer-3]> but was:<[peer-1, peer-3]>
     * ```
     *
     * Note the third line of the final roster: after the survivor evicts it, `handleFarewell`'s
     * `stopDetector` means the survivor stops answering the recovered member's pings, so the member
     * declares the *survivor* partitioned at t=2600. The split does not heal — it spreads.
     */
    @Ignore
    @Test
    fun `a member that recovered inside its window is still seated everywhere after the window elapses`() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val mesh = mesh()
            val droppedId = mesh.dropped.selfId
            val survivorId = mesh.survivor.selfId
            val hostId = mesh.host.selfId

            // Identities, not counts: every membership event each room raises, tagged with the room
            // that raised it, so the transcript names who evicted whom and on whose say-so.
            val transcript = mutableListOf<String>()
            fun watch(label: String, room: Room) {
                backgroundScope.launch {
                    room.events.collect { transcript += "$label: $it" }
                }
            }
            watch("host", mesh.host)
            watch("dropped", mesh.dropped)
            watch("survivor", mesh.survivor)
            testScheduler.runCurrent()

            println("#2556 ids: host=${hostId.value} dropped=${droppedId.value} survivor=${survivorId.value}")
            println("#2556 t=${testScheduler.currentTime} step=admitted")
            println("  ${mesh.host.rosterLine("host")}")
            println("  ${mesh.dropped.rosterLine("dropped")}")
            println("  ${mesh.survivor.rosterLine("survivor")}")

            // 1. Silent drop, detected but well short of the window.
            mesh.droppedLink.partition()
            testScheduler.advanceTimeBy(config.timeout + config.interval * 3)
            testScheduler.runCurrent()
            println("#2556 t=${testScheduler.currentTime} step=partitioned")
            println("  ${mesh.host.rosterLine("host")}")
            println("  ${mesh.dropped.rosterLine("dropped")}")
            println("  ${mesh.survivor.rosterLine("survivor")}")

            // 2. Heal, and let ping/pong resume — still comfortably inside the 2 s window.
            mesh.droppedLink.heal()
            testScheduler.advanceTimeBy(config.interval * 6)
            testScheduler.runCurrent()
            println("#2556 t=${testScheduler.currentTime} step=recovered")
            println("  ${mesh.host.rosterLine("host")}")
            println("  ${mesh.dropped.rosterLine("dropped")}")
            println("  ${mesh.survivor.rosterLine("survivor")}")

            // 3. Cross the original deadline. This is the step MeshRoomRecoveryTest never takes.
            testScheduler.advanceTimeBy(config.reconnectWindow + config.interval * 4)
            testScheduler.runCurrent()
            println("#2556 t=${testScheduler.currentTime} step=past-window")
            println("  ${mesh.host.rosterLine("host")}")
            println("  ${mesh.dropped.rosterLine("dropped")}")
            println("  ${mesh.survivor.rosterLine("survivor")}")
            transcript.forEach { println("#2556 event $it") }

            assertAll(
                {
                    assertEquals(
                        true,
                        mesh.host.holds(droppedId),
                        "the host must still seat the recovered member — ${mesh.host.rosterLine("host")}",
                    )
                },
                {
                    assertEquals(
                        true,
                        mesh.survivor.holds(droppedId),
                        "the survivor must still seat the recovered member; a stale WindowExpired must not " +
                            "farewell a healthy peer — ${mesh.survivor.rosterLine("survivor")}",
                    )
                },
                {
                    assertEquals(
                        true,
                        mesh.dropped.holds(survivorId),
                        "the recovered member must still seat the survivor — ${mesh.dropped.rosterLine("dropped")}",
                    )
                },
                {
                    // [Room.roster] excludes the reading peer, so membership is only comparable
                    // across rooms once each adds itself back in.
                    assertEquals(
                        mesh.host.membershipView(),
                        mesh.survivor.membershipView(),
                        "host and survivor must agree on who is in the room after a recovery that " +
                            "outlived its window",
                    )
                },
                {
                    assertEquals(
                        mesh.host.membershipView(),
                        mesh.dropped.membershipView(),
                        "the recovered member must agree with the host about who is in the room",
                    )
                },
            )

            mesh.host.leave()
            mesh.survivor.leave()
            mesh.dropped.leave()
        }

    /**
     * **The discriminator.** The test above proves the survivor evicts a recovered member; it does
     * not on its own prove *which* timer did it, because with one shared [HeartbeatConfig] the
     * survivor's own detector window and the host's controller window expire at the same virtual
     * instant, and both spell their eviction [LeaveReason.PartitionExpired].
     *
     * So separate them, the way
     * [MeshRoomRecoveryTest]'s `host evicts a still-partitioned member on window expiry even when
     * PeerLost never fires` separates them: a **long** (10 s) detector window against a **short**
     * (1 s) injected reconnect-controller window. Inside the advance the survivor's own
     * [us.tractat.kuilt.liveness.PartitionEvent.PeerLost] is structurally unreachable — and it is a
     * joiner, so it has no controller and no `evictOnExpiredWindowIfPartitioned` backstop either.
     * A joiner has exactly one remaining way to reach `Left(PartitionExpired)`: `handleFarewell` on
     * a host-sent `Farewell(expired = true)`.
     *
     * The host's own eviction is not that producer either — `handlePeerLost` does not fan a
     * `Farewell` out at all, and `evictOnExpiredWindowIfPartitioned` (which does not fan one out
     * either) declines here because the member is [Liveness.Connected]. On the host the **only**
     * producer of `Farewell(expired = true)` is `runReconnectEventLoop`'s `WindowExpired` arm.
     *
     * This test is therefore the positive control for the mechanism *and* the refutation of rival
     * explanations 1 and 3 in #2556: it asserts the survivor still holds the member one tick
     * *before* the controller's deadline and observes the eviction land on the far side of it.
     *
     * Observed on `main`:
     *
     * ```
     * #2556-B detectorWindowMs=10000 controllerWindowMs=1000
     * #2556-B t=400 step=partitioned
     *   host(self=peer-1)     [peer-2=Partitioned(until=1300), peer-3=Connected]
     *   survivor(self=peer-3) [peer-1=Connected, peer-2=Partitioned(until=1300)]
     * #2556-B t=800 step=recovered
     *   host(self=peer-1)     [peer-2=Connected, peer-3=Connected]
     *   survivor(self=peer-3) [peer-1=Connected, peer-2=Connected]
     * #2556-B t=2000 step=past-controller-deadline survivorDroppedAt=1300
     *   host(self=peer-1)     [peer-2=Connected, peer-3=Connected]
     *   survivor(self=peer-3) [peer-1=Connected]
     * #2556-B survivor-left t=1300 Left(peerId=PeerId(value=peer-2), reason=PartitionExpired)
     * ```
     *
     * `survivorDroppedAt=1300` is the injected controller's deadline exactly (detection at 300 plus
     * a 1 s window); the survivor's own detector could not have reached `PeerLost` before 10300.
     *
     * The `Partitioned(until=1300)` in the *first* snapshot is a second, independent receipt for
     * rival 3. `markPartitioned`'s own estimate here is `300 + 10000 = 10300`; 1300 can only have
     * come from `refineWindow` applying the controller's `WindowOpened`. The `Reason.Timeout` blip
     * demonstrably armed the controller.
     */
    @Ignore
    @Test
    fun `the eviction lands on the controller's deadline, not the survivor's own detector window`() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            // Ten seconds: nothing in this test advances far enough for the survivor's own detector
            // to mature to PeerLost, recovered or not.
            val longDetectorWindow = HeartbeatConfig(
                interval = 100.milliseconds,
                timeout = 300.milliseconds,
                reconnectWindow = 10.seconds,
            )
            val shortWindowMs = 1_000L

            val loom = InMemoryLoom()
            val clock: () -> Instant = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }
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
            val dropped = factory.adopt(droppedLink, SessionRole.Joiner)
            val survivor = factory.join(InMemoryTag("Survivor"))
            host.roster.first { it.size == 2 }
            dropped.roster.first { it.size == 2 }
            survivor.roster.first { it.size == 2 }
            val droppedId = dropped.selfId

            val survivorLefts = mutableListOf<String>()
            backgroundScope.launch {
                survivor.events.collect { event ->
                    if (event is MembershipEvent.Left) {
                        survivorLefts += "t=${testScheduler.currentTime} $event"
                    }
                }
            }
            testScheduler.runCurrent()
            println("#2556-B ids: host=${host.selfId.value} dropped=${droppedId.value} survivor=${survivor.selfId.value}")
            println("#2556-B detectorWindowMs=${longDetectorWindow.reconnectWindow.inWholeMilliseconds} controllerWindowMs=$shortWindowMs")

            // Drop, detect (t≈300) — the controller's window now expires at t≈1300, the survivor's
            // own detector window not until t≈10300.
            droppedLink.partition()
            testScheduler.advanceTimeBy(longDetectorWindow.timeout + longDetectorWindow.interval)
            testScheduler.runCurrent()
            println("#2556-B t=${testScheduler.currentTime} step=partitioned")
            println("  ${host.rosterLine("host")}")
            println("  ${survivor.rosterLine("survivor")}")

            // Heal well inside the controller's 1 s window.
            droppedLink.heal()
            testScheduler.advanceTimeBy(longDetectorWindow.interval * 4)
            testScheduler.runCurrent()
            println("#2556-B t=${testScheduler.currentTime} step=recovered")
            println("  ${host.rosterLine("host")}")
            println("  ${survivor.rosterLine("survivor")}")
            val heldAfterRecovery = survivor.holds(droppedId)
            val livenessAfterRecovery = host.roster.value.firstOrNull { it.id == droppedId }?.liveness

            // Walk to the controller's deadline in bounded steps, recording the exact virtual
            // instant the survivor's seat for the recovered member disappears. A count would say
            // "it went"; the instant says *which timer took it*.
            var droppedAt: Long? = null
            repeat(STEPS_PAST_DEADLINE) {
                testScheduler.advanceTimeBy(longDetectorWindow.interval)
                testScheduler.runCurrent()
                if (droppedAt == null && !survivor.holds(droppedId)) {
                    droppedAt = testScheduler.currentTime
                }
            }
            println("#2556-B t=${testScheduler.currentTime} step=past-controller-deadline survivorDroppedAt=$droppedAt")
            println("  ${host.rosterLine("host")}")
            println("  ${survivor.rosterLine("survivor")}")
            survivorLefts.forEach { println("#2556-B survivor-left $it") }

            assertAll(
                {
                    assertEquals(
                        true,
                        heldAfterRecovery,
                        "sanity: the survivor must still seat the member once it has recovered",
                    )
                },
                {
                    assertEquals(
                        Liveness.Connected,
                        livenessAfterRecovery,
                        "sanity: the host must read the recovered member Connected before the window elapses",
                    )
                },
                {
                    assertEquals(
                        true,
                        survivor.holds(droppedId),
                        "the survivor must still seat the recovered member after the host's reconnect " +
                            "window elapsed; its own detector window (${longDetectorWindow.reconnectWindow}) " +
                            "cannot have evicted it by t=${testScheduler.currentTime} — " +
                            "${survivor.rosterLine("survivor")}, lefts=$survivorLefts",
                    )
                },
            )

            host.leave()
            survivor.leave()
            dropped.leave()
        }

    private companion object {
        /**
         * Enough 100 ms ticks to carry virtual time past the injected 1 s controller deadline with
         * margin, and nowhere near the 10 s detector window that is the rival evictor.
         */
        const val STEPS_PAST_DEADLINE = 12
    }
}
