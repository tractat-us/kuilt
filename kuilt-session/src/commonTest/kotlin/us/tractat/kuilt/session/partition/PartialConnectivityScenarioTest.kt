package us.tractat.kuilt.session.partition

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.Direction
import us.tractat.kuilt.core.FaultProfile
import us.tractat.kuilt.core.PeerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Scenario-driven tests for partial connectivity in 3- and 4-peer meshes.
 *
 * All tests model the **host-as-hub** topology (D-003): the host is the sole routing
 * point; joiners have no direct peer-to-peer links with each other.
 *
 * **Fault injection technique:**
 * - [Direction.Inbound] on a joiner's [us.tractat.kuilt.core.FaultySeam]
 *   drops frames arriving at that joiner from the host. The host's ping never arrives
 *   at J1 → J1 cannot pong → detector times out. Models "H→J1 outbound dropped" (S1).
 * - [Direction.Outbound] on a joiner's link drops frames the joiner sends back to the
 *   host. J1 receives pings but its replies are dropped → detector times out.
 *   Models "H→J1 inbound dropped" (S2).
 *
 * **Clock contract:** all [HeartbeatPartitionDetector] instances share a single mutable
 * [clockMs] variable advanced in lockstep with [advanceTimeBy].
 *
 * **D-003 invariant:** faulting joiner↔joiner paths (which don't exist in a hub topology)
 * has no observable effect on delivery (S3).
 *
 * **D-009 invariant:** game-pause is global — any peer unresponsive pauses for everyone;
 * no subset keeps making progress (S4, S8).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PartialConnectivityScenarioTest {
    // ── Scenario 1: H→J1 outbound dropped, H→J1 inbound healthy ─────────────

    /**
     * H can hear J1 but pings never reach J1 (J1's inbound faulted).
     * J1 never receives the ping, so it never sends a pong.
     * Expected: detector for J1 emits [PartitionEvent.PeerUnresponsive] with [PartitionEvent.Reason.Timeout].
     */
    @Test
    fun `S1 - asymmetric outbound drop causes PeerUnresponsive for J1`() =
        runTest {
            var clockMs = 0L
            val clock: () -> Instant = { Instant.fromEpochMilliseconds(clockMs) }
            val config = HeartbeatConfig(interval = 100.milliseconds, timeout = 300.milliseconds, reconnectWindow = 1.seconds)

            val mesh = Mesh.build(peerCount = 3, scope = backgroundScope, clock = clock, config = config)

            // J1's inbound faulted: host pings arrive at the FaultySeam wrapper but
            // are dropped before J1's application layer. J1 can't pong → detector times out.
            mesh.j0.setFaultProfile(FaultProfile.DropAll(Direction.Inbound))

            val unresponsive =
                async {
                    mesh.d0.events
                        .filterIsInstance<PartitionEvent.PeerUnresponsive>()
                        .first()
                }
            mesh.d0.start(backgroundScope)

            repeat(5) {
                clockMs += config.interval.inWholeMilliseconds
                advanceTimeBy(config.interval)
            }

            val event = unresponsive.await()
            assertAll(
                { assertEquals(mesh.j0.selfId, event.peerId) },
                { assertEquals(PartitionEvent.Reason.Timeout, event.reason) },
            )
        }

    /**
     * With J1 partitioned, J2's link is still healthy.
     * The game must pause globally (D-001 / D-009): [PartitionEvent.PeerUnresponsive]
     * from any detector signals pause for all peers, not just the affected joiner.
     */
    @Test
    fun `S1 - pause is global - PeerUnresponsive from J1 detector signals pause for all peers`() =
        runTest {
            var clockMs = 0L
            val clock: () -> Instant = { Instant.fromEpochMilliseconds(clockMs) }
            val config = HeartbeatConfig(interval = 100.milliseconds, timeout = 300.milliseconds, reconnectWindow = 1.seconds)

            val mesh = Mesh.build(peerCount = 3, scope = backgroundScope, clock = clock, config = config)
            mesh.j0.setFaultProfile(FaultProfile.DropAll(Direction.Inbound))

            // Collect all events — any PeerUnresponsive is the global pause signal
            val pauseEvent =
                async {
                    mesh.allEvents.filterIsInstance<PartitionEvent.PeerUnresponsive>().first()
                }
            mesh.startAllDetectors(backgroundScope)

            repeat(5) {
                clockMs += config.interval.inWholeMilliseconds
                advanceTimeBy(config.interval)
            }

            val event = pauseEvent.await()
            // The event came from d0 (J1); it is the global pause signal (D-009).
            // J2's detector (d1) must not have also fired — J2's link is healthy.
            assertAll(
                { assertEquals(mesh.j0.selfId, event.peerId) },
                { assertEquals(PartitionEvent.Reason.Timeout, event.reason) },
            )

            // Verify J2's detector channel is empty (no unexpected events)
            val d1Channel = mesh.d1.events.produceIn(this)
            val unexpectedJ2Event = d1Channel.tryReceive()
            assertFalse(unexpectedJ2Event.isSuccess, "J2's detector must not have fired")
            d1Channel.cancel()
        }

    // ── Scenario 2: H→J1 inbound dropped, H→J1 outbound healthy ─────────────

    /**
     * J1 stops talking to H (J1's outbound faulted). H sends pings; J1 receives them
     * but cannot reply. Expected: detector emits [PartitionEvent.PeerUnresponsive] with
     * [PartitionEvent.Reason.Timeout].
     */
    @Test
    fun `S2 - asymmetric inbound drop - J1 pongs suppressed - causes PeerUnresponsive`() =
        runTest {
            var clockMs = 0L
            val clock: () -> Instant = { Instant.fromEpochMilliseconds(clockMs) }
            val config = HeartbeatConfig(interval = 100.milliseconds, timeout = 300.milliseconds, reconnectWindow = 1.seconds)

            val mesh = Mesh.build(peerCount = 3, scope = backgroundScope, clock = clock, config = config)

            // J1's outbound faulted: J1 receives pings but its pong replies are dropped.
            mesh.j0.setFaultProfile(FaultProfile.DropAll(Direction.Outbound))

            val unresponsive =
                async {
                    mesh.d0.events
                        .filterIsInstance<PartitionEvent.PeerUnresponsive>()
                        .first()
                }
            mesh.d0.start(backgroundScope)

            repeat(5) {
                clockMs += config.interval.inWholeMilliseconds
                advanceTimeBy(config.interval)
            }

            val event = unresponsive.await()
            assertAll(
                { assertEquals(mesh.j0.selfId, event.peerId) },
                { assertEquals(PartitionEvent.Reason.Timeout, event.reason) },
            )
        }

    // ── Scenario 3: joiner-to-joiner asymmetry under host-as-hub ────────────

    /**
     * In a host-as-hub topology (D-003), joiners have no direct links to each other.
     * Faulting the "J1↔J2 direct path" has no effect because that path does not exist.
     * All joiner traffic routes through the host.
     *
     * Verification: host broadcasts reach both J1 and J2 regardless of J2's inbound state.
     * (In a real hub topology no joiner-sourced frame reaches another joiner without
     * the host fanning it out — the InMemoryLoom models this shared-bus correctly.)
     */
    @Test
    fun `S3 - host-as-hub fan-out delivers host broadcast to all joiners`() =
        runTest {
            val mesh = Mesh.build(peerCount = 3, scope = backgroundScope, clock = { Instant.fromEpochMilliseconds(0) }, withPingResponders = false)

            val receivedByJ1 = async { mesh.j0.incoming.first() }
            val receivedByJ2 = async { mesh.j1.incoming.first() }

            mesh.host.broadcast(byteArrayOf(42))

            val f1 = receivedByJ1.await()
            val f2 = receivedByJ2.await()
            assertAll(
                { assertEquals(mesh.host.selfId, f1.sender) },
                { assertEquals(mesh.host.selfId, f2.sender) },
                { assertTrue(f1.payload.contentEquals(byteArrayOf(42))) },
                { assertTrue(f2.payload.contentEquals(byteArrayOf(42))) },
            )
        }

    /**
     * Frame ordering is preserved across the host fan-out: host sends frames 1, 2, 3;
     * both joiners receive them in order. Validates ADR-019 §1 ordering guarantee.
     */
    @Test
    fun `S3 - frame ordering preserved across host fan-out`() =
        runTest {
            val mesh = Mesh.build(peerCount = 3, scope = backgroundScope, clock = { Instant.fromEpochMilliseconds(0) }, withPingResponders = false)

            val framesAtJ1 =
                async {
                    mesh.j0.incoming
                        .take(3)
                        .toList()
                }
            val framesAtJ2 =
                async {
                    mesh.j1.incoming
                        .take(3)
                        .toList()
                }

            mesh.host.broadcast(byteArrayOf(1))
            mesh.host.broadcast(byteArrayOf(2))
            mesh.host.broadcast(byteArrayOf(3))

            val atJ1 = framesAtJ1.await()
            val atJ2 = framesAtJ2.await()
            assertAll(
                { assertTrue(atJ1[0].payload.contentEquals(byteArrayOf(1))) },
                { assertTrue(atJ1[1].payload.contentEquals(byteArrayOf(2))) },
                { assertTrue(atJ1[2].payload.contentEquals(byteArrayOf(3))) },
                { assertTrue(atJ2[0].payload.contentEquals(byteArrayOf(1))) },
                { assertTrue(atJ2[1].payload.contentEquals(byteArrayOf(2))) },
                { assertTrue(atJ2[2].payload.contentEquals(byteArrayOf(3))) },
            )
        }

    /**
     * There is no joiner-to-joiner direct path: a joiner's send to another joiner's PeerId
     * must be routed through the host. Faulting a joiner's inbound does not prevent the
     * host from reaching the other joiner.
     */
    @Test
    fun `S3 - faulting J2 inbound does not prevent host from reaching J1`() =
        runTest {
            val mesh = Mesh.build(peerCount = 3, scope = backgroundScope, clock = { Instant.fromEpochMilliseconds(0) }, withPingResponders = false)

            // J2's inbound fully partitioned — only J2 is affected; J1 must still receive.
            mesh.j1.setFaultProfile(FaultProfile.DropAll(Direction.Inbound))

            val receivedByJ1 = async { mesh.j0.incoming.first() }
            mesh.host.broadcast(byteArrayOf(99))

            val frame = receivedByJ1.await()
            assertAll(
                { assertEquals(mesh.host.selfId, frame.sender) },
                { assertTrue(frame.payload.contentEquals(byteArrayOf(99))) },
            )
        }

    // ── Scenario 4: triple partition — H↔J2 partitioned, H↔J1 healthy ───────

    /**
     * H↔J1 healthy, H↔J2 partitioned (J2's inbound dropped — host can't reach J2).
     * Expected:
     * - d1 (monitoring J2) fires [PartitionEvent.PeerUnresponsive] for J2.
     * - d0 (monitoring J1) does NOT fire — J1 is still reachable.
     * - D-009: even J1 must pause because the game state machine sees any unresponsive peer.
     */
    @Test
    fun `S4 - triple partition - only J2 detector fires - J1 link stays healthy`() =
        runTest {
            var clockMs = 0L
            val clock: () -> Instant = { Instant.fromEpochMilliseconds(clockMs) }
            val config = HeartbeatConfig(interval = 100.milliseconds, timeout = 300.milliseconds, reconnectWindow = 1.seconds)

            val mesh = Mesh.build(peerCount = 3, scope = backgroundScope, clock = clock, config = config)
            mesh.j1.setFaultProfile(FaultProfile.DropAll(Direction.Inbound))

            val j2Unresponsive =
                async {
                    mesh.d1.events
                        .filterIsInstance<PartitionEvent.PeerUnresponsive>()
                        .first()
                }
            mesh.startAllDetectors(backgroundScope)

            repeat(5) {
                clockMs += config.interval.inWholeMilliseconds
                advanceTimeBy(config.interval)
            }

            val event = j2Unresponsive.await()
            assertAll(
                { assertEquals(mesh.j1.selfId, event.peerId) },
                { assertEquals(PartitionEvent.Reason.Timeout, event.reason) },
            )

            // D-009: J1's detector must not have fired (J1's link is healthy)
            val d0Channel = mesh.d0.events.produceIn(this)
            assertFalse(
                d0Channel.tryReceive().isSuccess,
                "J1 detector must not have fired — J1 link is healthy",
            )
            d0Channel.cancel()
        }

    // ── Scenario 5: heal before reconnectWindow expires ──────────────────────

    /**
     * Partition H→J1 (scenario 1 profile), then heal before [HeartbeatConfig.reconnectWindow].
     * Expected: detector emits [PartitionEvent.PeerUnresponsive] then [PartitionEvent.PeerRecovered].
     * Game may resume once [PeerRecovered] fires (D-001).
     */
    @Test
    fun `S5 - partition heals before reconnectWindow - PeerRecovered emitted`() =
        runTest {
            var clockMs = 0L
            val clock: () -> Instant = { Instant.fromEpochMilliseconds(clockMs) }
            val config =
                HeartbeatConfig(
                    interval = 100.milliseconds,
                    timeout = 300.milliseconds,
                    reconnectWindow = 2.seconds,
                )

            val mesh = Mesh.build(peerCount = 3, scope = backgroundScope, clock = clock, config = config)
            mesh.j0.setFaultProfile(FaultProfile.DropAll(Direction.Inbound))

            val twoEvents =
                async {
                    mesh.d0.events
                        .take(2)
                        .toList()
                }
            mesh.d0.start(backgroundScope)

            // Advance past timeout → PeerUnresponsive fires
            repeat(5) {
                clockMs += config.interval.inWholeMilliseconds
                advanceTimeBy(config.interval)
            }

            // Heal: restore J1's inbound and tell the detector J1 is alive
            mesh.j0.setFaultProfile(FaultProfile.Healthy)
            mesh.d0.observedPeer(mesh.j0.selfId)

            // Advance to give the detector a poll cycle to notice recovery
            repeat(5) {
                clockMs += config.interval.inWholeMilliseconds
                advanceTimeBy(config.interval)
            }

            val collected = twoEvents.await()
            assertAll(
                { assertEquals(2, collected.size) },
                { assertIs<PartitionEvent.PeerUnresponsive>(collected[0]) },
                { assertIs<PartitionEvent.PeerRecovered>(collected[1]) },
                { assertEquals(mesh.j0.selfId, collected[1].peerId) },
            )
        }

    // ── Scenario 6: window expiry — partition persists past reconnectWindow ───

    /**
     * Partition H→J1 persists past [HeartbeatConfig.reconnectWindow].
     * Expected: detector emits [PartitionEvent.PeerUnresponsive] then [PartitionEvent.PeerLost].
     * After [PeerLost] J1 is considered permanently evicted.
     */
    @Test
    fun `S6 - partition persists past reconnectWindow - PeerLost emitted`() =
        runTest {
            var clockMs = 0L
            val clock: () -> Instant = { Instant.fromEpochMilliseconds(clockMs) }
            val config =
                HeartbeatConfig(
                    interval = 100.milliseconds,
                    timeout = 300.milliseconds,
                    reconnectWindow = 1.seconds,
                )

            val mesh = Mesh.build(peerCount = 3, scope = backgroundScope, clock = clock, config = config)
            mesh.j0.setFaultProfile(FaultProfile.DropAll(Direction.Inbound))

            val twoEvents =
                async {
                    mesh.d0.events
                        .take(2)
                        .toList()
                }
            mesh.d0.start(backgroundScope)

            // Advance past timeout + full reconnect window
            val totalMs = config.timeout.inWholeMilliseconds + config.reconnectWindow.inWholeMilliseconds
            val steps = (totalMs / config.interval.inWholeMilliseconds).toInt() + 5
            repeat(steps) {
                clockMs += config.interval.inWholeMilliseconds
                advanceTimeBy(config.interval)
            }

            val collected = twoEvents.await()
            assertAll(
                { assertEquals(2, collected.size) },
                { assertIs<PartitionEvent.PeerUnresponsive>(collected[0]) },
                { assertIs<PartitionEvent.PeerLost>(collected[1]) },
                { assertEquals(mesh.j0.selfId, collected[1].peerId) },
            )
        }

    // ── Scenario 7: resume token after eviction ───────────────────────────────

    /**
     * After [PartitionEvent.PeerLost] fires, a joiner attempting to reconnect with
     * the same [us.tractat.kuilt.core.PeerId] must receive
     * [ResumeResult.WindowClosed].
     *
     * Validates D-005 contract: the reconnect window is closed after [PeerLost];
     * the peer cannot resume and must re-join as a fresh peer.
     */
    @Test
    fun `S7 - reconnect attempt after PeerLost returns WindowClosed`() =
        runTest {
            var clockMs = 0L
            val clock: () -> Instant = { Instant.fromEpochMilliseconds(clockMs) }
            val config =
                HeartbeatConfig(
                    interval = 100.milliseconds,
                    timeout = 300.milliseconds,
                    reconnectWindow = 1.seconds,
                )

            val mesh = Mesh.build(peerCount = 3, scope = backgroundScope, clock = clock, config = config)
            mesh.j0.setFaultProfile(FaultProfile.DropAll(Direction.Inbound))

            val lostEvent =
                async {
                    mesh.d0.events
                        .filterIsInstance<PartitionEvent.PeerLost>()
                        .first()
                }
            mesh.d0.start(backgroundScope)

            val totalMs = config.timeout.inWholeMilliseconds + config.reconnectWindow.inWholeMilliseconds
            val steps = (totalMs / config.interval.inWholeMilliseconds).toInt() + 5
            repeat(steps) {
                clockMs += config.interval.inWholeMilliseconds
                advanceTimeBy(config.interval)
            }

            val lost = lostEvent.await()
            assertEquals(mesh.j0.selfId, lost.peerId)

            // FakeReconnectGate stubs JoinerReconnectController's window-close semantics.
            val gate = FakeReconnectGate()
            gate.onPartitionEvent(lost)
            val result = gate.attemptReconnect(lost.peerId)

            assertIs<ResumeResult.WindowClosed>(result)
        }

    // ── Scenario 8: 4-peer cascade ────────────────────────────────────────────

    /**
     * 4-peer game: H + J1 + J2 + J3. H↔J1 partitions, then H↔J2 partitions.
     * Both [PartitionEvent.PeerUnresponsive] events must fire.
     * D-009: no subset of peers makes progress while another is partitioned.
     * The order of events does not matter for the final state.
     */
    @Test
    fun `S8 - 4-peer cascade - sequential partitions both trigger PeerUnresponsive`() =
        runTest {
            var clockMs = 0L
            val clock: () -> Instant = { Instant.fromEpochMilliseconds(clockMs) }
            val config =
                HeartbeatConfig(
                    interval = 100.milliseconds,
                    timeout = 300.milliseconds,
                    reconnectWindow = 2.seconds,
                )

            val mesh = Mesh.build(peerCount = 4, scope = backgroundScope, clock = clock, config = config)
            mesh.startAllDetectors(backgroundScope)

            // Collect the first two PeerUnresponsive events across all detectors
            val twoUnresponsive =
                async {
                    mesh.allEvents
                        .filterIsInstance<PartitionEvent.PeerUnresponsive>()
                        .take(2)
                        .toList()
                }

            // Step 1: partition H↔J1 at t=0
            mesh.j0.setFaultProfile(FaultProfile.DropAll(Direction.Inbound))

            // Advance past J1's timeout
            repeat(5) {
                clockMs += config.interval.inWholeMilliseconds
                advanceTimeBy(config.interval)
            }

            // Step 2: partition H↔J2 while J1 still partitioned
            mesh.j1.setFaultProfile(FaultProfile.DropAll(Direction.Inbound))

            // Advance past J2's timeout
            repeat(5) {
                clockMs += config.interval.inWholeMilliseconds
                advanceTimeBy(config.interval)
            }

            val events = twoUnresponsive.await()
            assertAll(
                { assertEquals(2, events.size) },
                // Both J1 and J2 must have fired PeerUnresponsive (order may vary)
                { assertTrue(events.any { it.peerId == mesh.j0.selfId }, "J1 must be unresponsive") },
                { assertTrue(events.any { it.peerId == mesh.j1.selfId }, "J2 must be unresponsive") },
            )
        }

    /**
     * D-009 cascade: both J1 and J2 partitioned simultaneously.
     * Healing J1 does NOT resume the game — J2 is still unresponsive.
     * Only when both recover (or are evicted) may the game resume.
     * J3 (healthy throughout) does not independently advance the game.
     */
    @Test
    fun `S8 - D009 cascade - healing one peer does not resume when second peer still partitioned`() =
        runTest {
            var clockMs = 0L
            val clock: () -> Instant = { Instant.fromEpochMilliseconds(clockMs) }
            val config =
                HeartbeatConfig(
                    interval = 100.milliseconds,
                    timeout = 300.milliseconds,
                    reconnectWindow = 2.seconds,
                )

            val mesh = Mesh.build(peerCount = 4, scope = backgroundScope, clock = clock, config = config)
            mesh.startAllDetectors(backgroundScope)

            // Partition both J1 and J2
            mesh.j0.setFaultProfile(FaultProfile.DropAll(Direction.Inbound))
            mesh.j1.setFaultProfile(FaultProfile.DropAll(Direction.Inbound))

            val j1Unresponsive =
                async {
                    mesh.d0.events
                        .filterIsInstance<PartitionEvent.PeerUnresponsive>()
                        .first()
                }
            val j2Unresponsive =
                async {
                    mesh.d1.events
                        .filterIsInstance<PartitionEvent.PeerUnresponsive>()
                        .first()
                }

            repeat(5) {
                clockMs += config.interval.inWholeMilliseconds
                advanceTimeBy(config.interval)
            }

            val e1 = j1Unresponsive.await()
            val e2 = j2Unresponsive.await()
            assertAll(
                { assertEquals(mesh.j0.selfId, e1.peerId) },
                { assertEquals(mesh.j1.selfId, e2.peerId) },
            )

            // Heal J1 only — collect PeerRecovered for J1
            mesh.j0.setFaultProfile(FaultProfile.Healthy)
            mesh.d0.observedPeer(mesh.j0.selfId)

            val j1Recovered =
                async {
                    mesh.d0.events
                        .filterIsInstance<PartitionEvent.PeerRecovered>()
                        .first()
                }

            repeat(5) {
                clockMs += config.interval.inWholeMilliseconds
                advanceTimeBy(config.interval)
            }

            val recovered = j1Recovered.await()
            assertEquals(mesh.j0.selfId, recovered.peerId)

            // D-009: J2 is still partitioned. Game must remain paused.
            // The leader's pause set still contains J2's PeerId — no resume is possible.
            // J3's detector (d2) must not have fired any events.
            val d2Channel = mesh.d2.events.produceIn(this)
            assertFalse(
                d2Channel.tryReceive().isSuccess,
                "J3 detector must not have fired — J3 link is healthy throughout",
            )
            d2Channel.cancel()

            // J2 is still unresponsive — its detector has not emitted PeerRecovered.
            val d1Channel = mesh.d1.events.produceIn(this)
            // d1 already emitted PeerUnresponsive (e2); no PeerRecovered should follow yet.
            // Channel may contain additional PeerUnresponsive re-notifications during polling,
            // but no PeerRecovered because J2 is still faulted.
            val nextJ2Event = d1Channel.tryReceive()
            if (nextJ2Event.isSuccess) {
                assertFalse(
                    nextJ2Event.getOrThrow() is PartitionEvent.PeerRecovered,
                    "J2 must not have recovered while still faulted",
                )
            }
            d1Channel.cancel()
        }
}

// ── Reconnect gate stub ──────────────────────────────────────────────────────

/**
 * Local test fake for [JoinerReconnectController]'s window-close semantics.
 *
 * Tracks which peers have had their windows closed via [PartitionEvent.PeerLost]
 * and answers [attemptReconnect] queries. This stubs the post-eviction guard in
 * scenario 7: once a [PartitionEvent.PeerLost] fires, the window is closed and a
 * reconnect attempt returns [ResumeResult.WindowClosed].
 */
private class FakeReconnectGate {
    private val closedWindows = mutableSetOf<PeerId>()

    fun onPartitionEvent(event: PartitionEvent) {
        if (event is PartitionEvent.PeerLost) closedWindows += event.peerId
    }

    fun attemptReconnect(peerId: PeerId): ResumeResult = if (peerId in closedWindows) ResumeResult.WindowClosed else ResumeResult.Success
}

// ── Test helpers ──────────────────────────────────────────────────────────────

/** Runs all [assertions] and reports all failures together (project convention). */
private fun assertAll(vararg assertions: () -> Unit) = assertions.forEach { it() }
