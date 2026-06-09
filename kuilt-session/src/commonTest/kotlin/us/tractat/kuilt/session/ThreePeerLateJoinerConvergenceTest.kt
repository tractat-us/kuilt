@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    kotlinx.serialization.ExperimentalSerializationApi::class,
)

package us.tractat.kuilt.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.replicator.ReplicatorMessage
import us.tractat.kuilt.crdt.replicator.SeamReplicator
import us.tractat.kuilt.crdt.replicator.SeamReplicatorConfig
import kotlin.test.Test
import kotlin.test.assertEquals

private fun assertAll(vararg assertions: () -> Unit) = assertions.forEach { it() }

/**
 * Integration: a [GCounter] [SeamReplicator] over [Room.channel] converges across
 * **three** members (1 host + 2 joiners), exercising two catch-up paths:
 *
 * 1. **Simultaneous join**: All three replicators are registered before any deltas
 *    are applied. On admit, [SeamReplicator] fires [ReplicatorMessage.FullState] to each
 *    new peer; subsequent deltas reach every peer directly.
 *
 * 2. **Late joiner with GC'd deltas (gap-fill → FullState fallback)**: Phase 1 two-peer
 *    convergence GC's the initial deltas. Joiner2 arrives after the GC. New deltas in
 *    Phase 2 trigger gap-detection; the Resend request can't be fulfilled from the
 *    now-empty delta buffer, so the sender falls back to [ReplicatorMessage.FullState].
 *    All three peers converge to the accumulated total.
 *
 * Both paths exercise the end-to-end [Room.channel] + [SeamReplicator] stack over
 * [InMemoryLoom] with virtual-time scheduling via [UnconfinedTestDispatcher].
 */
class ThreePeerLateJoinerConvergenceTest {

    private val replicatorConfig = SeamReplicatorConfig(expectVirtualTime = true)
    private val messageSer = ReplicatorMessage.serializer(GCounter.serializer())

    private fun gcounterReplicator(room: Room, scope: CoroutineScope): SeamReplicator<GCounter> =
        SeamReplicator(
            replica = ReplicaId(room.selfId.value),
            seam = room.channel("crdt-test"),
            initial = GCounter.ZERO,
            messageSerializer = messageSer,
            scope = scope,
            config = replicatorConfig,
        )

    /**
     * Three simultaneous peers converge GCounter to sum of all increments.
     *
     * All three rooms and replicators are set up before any deltas are applied.
     * Exercises the 3-peer delta propagation path end-to-end over `Room.channel`:
     * each admit fires [ReplicatorMessage.FullState] to the new peer; subsequent deltas
     * propagate to all three.
     */
    @Test
    fun `three simultaneous peers converge GCounter to sum of all increments`() =
        runTest(UnconfinedTestDispatcher()) {
            val loom = InMemoryLoom()

            val hostRoom = SeamRoomFactory(loom, backgroundScope).host(Pattern("Host"))
            val joiner1Room = SeamRoomFactory(loom, backgroundScope).join(InMemoryTag("Joiner1"))
            val joiner2Room = SeamRoomFactory(loom, backgroundScope).join(InMemoryTag("Joiner2"))

            hostRoom.roster.first { it.size == 2 }
            joiner1Room.roster.first { it.isNotEmpty() }
            joiner2Room.roster.first { it.isNotEmpty() }

            val repHost = gcounterReplicator(hostRoom, backgroundScope)
            val repJoiner1 = gcounterReplicator(joiner1Room, backgroundScope)
            val repJoiner2 = gcounterReplicator(joiner2Room, backgroundScope)

            repHost.apply(repHost.state.value.inc(repHost.replica, 2L))
            repJoiner1.apply(repJoiner1.state.value.inc(repJoiner1.replica, 3L))
            repJoiner2.apply(repJoiner2.state.value.inc(repJoiner2.replica, 7L))

            testScheduler.advanceUntilIdle()

            val expected = 12L
            assertAll(
                { assertEquals(expected, repHost.state.value.value, "host must converge to $expected") },
                { assertEquals(expected, repJoiner1.state.value.value, "joiner1 must converge to $expected") },
                { assertEquals(expected, repJoiner2.state.value.value, "joiner2 must converge to $expected") },
            )

            joiner2Room.leave()
            joiner1Room.leave()
            hostRoom.leave()
        }

    /**
     * Late joiner catches up via FullState fallback when Phase-1 deltas have been GC'd.
     *
     * Phase 1: host (+3) and joiner1 (+5) converge to 8 (2-peer). The initial deltas
     * are acked and GC'd from both senders' pending-delta buffers.
     *
     * Phase 2: joiner2 joins after GC. Host and joiner1 each apply one more delta.
     * Joiner2 receives the new deltas (seq=2) but is missing seq=1 for each sender.
     * Gap detection fires [ReplicatorMessage.Resend]; the senders can't fulfil it
     * (seq=1 is GC'd), so they fall back to [ReplicatorMessage.FullState]. Joiner2
     * absorbs the FullState snapshots and converges to the total accumulated value.
     */
    @Test
    fun `late joiner converges via gap-fill resend when it missed initial deltas`() =
        runTest(UnconfinedTestDispatcher()) {
            val loom = InMemoryLoom()

            // ── Phase 1: two-peer convergence ─────────────────────────────────
            val hostRoom = SeamRoomFactory(loom, backgroundScope).host(Pattern("Host"))
            val joiner1Room = SeamRoomFactory(loom, backgroundScope).join(InMemoryTag("Joiner1"))

            hostRoom.roster.first { it.size == 1 }
            joiner1Room.roster.first { it.isNotEmpty() }

            val repHost = gcounterReplicator(hostRoom, backgroundScope)
            val repJoiner1 = gcounterReplicator(joiner1Room, backgroundScope)

            repHost.apply(repHost.state.value.inc(repHost.replica, 3L))
            repJoiner1.apply(repJoiner1.state.value.inc(repJoiner1.replica, 5L))

            testScheduler.advanceUntilIdle()

            assertEquals(8L, repHost.state.value.value, "host must converge to 8 before late join")
            assertEquals(8L, repJoiner1.state.value.value, "joiner1 must converge to 8 before late join")

            // ── Phase 2: late joiner + new deltas trigger gap-fill ────────────
            val joiner2Room = SeamRoomFactory(loom, backgroundScope).join(InMemoryTag("Joiner2"))
            val repJoiner2 = gcounterReplicator(joiner2Room, backgroundScope)

            hostRoom.roster.first { it.size == 2 }
            joiner2Room.roster.first { it.isNotEmpty() }
            testScheduler.advanceUntilIdle()

            // New deltas from all three peers — joiner2 misses seq=1 from host and joiner1.
            // Resend requests can't be fulfilled (seq=1 GC'd) → FullState fallback.
            repHost.apply(repHost.state.value.inc(repHost.replica, 1L))   // host seq=2
            repJoiner1.apply(repJoiner1.state.value.inc(repJoiner1.replica, 2L)) // joiner1 seq=2
            repJoiner2.apply(repJoiner2.state.value.inc(repJoiner2.replica, 7L)) // joiner2 seq=1

            testScheduler.advanceUntilIdle()

            // Total: host(3+1) + joiner1(5+2) + joiner2(7) = 18
            val expected = 18L
            assertAll(
                { assertEquals(expected, repHost.state.value.value, "host must converge to $expected") },
                { assertEquals(expected, repJoiner1.state.value.value, "joiner1 must converge to $expected") },
                {
                    assertEquals(
                        expected,
                        repJoiner2.state.value.value,
                        "late joiner must converge to $expected via gap-fill FullState fallback",
                    )
                },
            )

            joiner2Room.leave()
            joiner1Room.leave()
            hostRoom.leave()
        }
}
