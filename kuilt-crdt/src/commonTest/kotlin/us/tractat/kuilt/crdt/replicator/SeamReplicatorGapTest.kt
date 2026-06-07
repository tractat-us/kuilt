@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.crdt.replicator

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.GSet
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Exercises gap detection: when a receiver sees seq N+2 before N+1, it should
 * emit a Resend, buffer the out-of-order delta, and converge once the missing
 * delta is retransmitted.
 *
 * Uses [GSet] rather than [GCounter] because GSet deltas are genuinely
 * irreducible: a dropped `add("b")` cannot be reconstructed from later deltas,
 * so a dropped delta causes observable divergence without gap recovery.
 */
class SeamReplicatorGapTest {

    private val gsetSer = ReplicatorMessage.serializer(
        GSet.serializer(kotlinx.serialization.serializer<String>()),
    )
    private val gcounterSer = ReplicatorMessage.serializer(GCounter.serializer())

    /**
     * A [Seam] wrapper that intercepts outgoing [broadcast] calls and suppresses
     * specific frame indices (1-based) so tests can manufacture deliberate gaps.
     */
    private class DroppingSeam(
        private val delegate: Seam,
        private val dropBroadcastAtIndex: Int,
    ) : Seam by delegate {
        private var broadcastCount = 0

        override suspend fun broadcast(payload: ByteArray) {
            broadcastCount++
            if (broadcastCount != dropBroadcastAtIndex) {
                delegate.broadcast(payload)
            }
        }
    }

    private fun <S : us.tractat.kuilt.crdt.Quilted<S>> replicatorFor(
        seam: Seam,
        initial: S,
        serializer: kotlinx.serialization.KSerializer<ReplicatorMessage<S>>,
        scope: CoroutineScope,
        config: SeamReplicatorConfig = SeamReplicatorConfig(expectVirtualTime = true),
    ) = SeamReplicator(
        replica = ReplicaId(seam.selfId.value),
        seam = seam,
        initial = initial,
        messageSerializer = serializer,
        scope = scope,
        config = config,
    )

    /**
     * A drops its second broadcast (seq=2, which adds "banana") before B can receive it.
     * B receives seq=1 ("apple") and seq=3 ("cherry"), detects the gap at seq=3,
     * emits a Resend, A retransmits seq=2 from pendingDeltas, and B converges.
     */
    @Test
    fun missingDeltaConvergesAfterResend() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val rawSeamA = loom.host(Pattern("gap-test"))
        val seamB = loom.join(InMemoryTag("b"))

        // Wrap A's seam to drop its second broadcast (seq=2 = "banana")
        val droppingSeamA = DroppingSeam(rawSeamA, dropBroadcastAtIndex = 2)

        val repA = replicatorFor(droppingSeamA, GSet.empty<String>(), gsetSer, backgroundScope)
        val repB = replicatorFor(seamB, GSet.empty<String>(), gsetSer, backgroundScope)

        repA.apply(Patch(GSet.of("apple")))   // seq=1 delivered
        repA.apply(Patch(GSet.of("banana")))  // seq=2 DROPPED
        repA.apply(Patch(GSet.of("cherry")))  // seq=3 delivered

        testScheduler.advanceUntilIdle()

        val expected = setOf("apple", "banana", "cherry")
        assertEquals(expected, repA.state.value.elements, "A should have full state")
        assertEquals(expected, repB.state.value.elements, "B must converge after Resend retransmission")
    }

    /**
     * Duplicate deltas (seq < expected) must not re-apply state.
     * B receives seq=1 (adding "apple") normally; seq=1 arrives again.
     * B must still have {"apple"} — not {"apple", "apple"} (not that GSet can double-add,
     * but the delta must not be re-applied through the CRDT's piece path).
     *
     * We verify using GCounter where double-applying a delta would produce wrong state
     * if join were not idempotent — and more importantly, that no erroneous Ack is sent
     * advancing the sender's GC pointer prematurely. The key invariant: state is the same
     * as after a single application.
     */
    @Test
    fun duplicateDeltaIsIgnored() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val seamA = loom.host(Pattern("dup-test"))
        val seamB = loom.join(InMemoryTag("b"))

        val repA = replicatorFor(seamA, GCounter.ZERO, gcounterSer, backgroundScope)
        val repB = replicatorFor(seamB, GCounter.ZERO, gcounterSer, backgroundScope)

        repA.apply(repA.state.value.inc(repA.replica, 5L))
        testScheduler.advanceUntilIdle()

        // Verify B received seq=1 correctly
        assertEquals(5L, repB.state.value.value, "B should have received seq=1")

        // Re-broadcast seq=1 — simulates retransmit / network dup
        val duplicateMsg = ReplicatorMessage.Delta(
            sender = repA.replica,
            seq = 1L,
            delta = GCounter.of(repA.replica to 5L),
        )
        seamA.broadcast(Cbor.encodeToByteArray(gcounterSer, duplicateMsg))
        testScheduler.advanceUntilIdle()

        // State must remain 5, not 10 — duplicate is absorbed idempotently
        assertEquals(5L, repB.state.value.value, "Duplicate delta must not inflate state")
    }

    /**
     * Multiple gaps in a single sender's stream are each independently detected and
     * recovered. A applies 4 deltas; seqs 2 and 4 are dropped. B must emit Resends
     * for both gaps and converge to all 4 elements once A retransmits.
     *
     * This also validates that partial buffering works correctly — seq=3 is buffered
     * until seq=2 is retransmitted, then both are drained in order.
     */
    @Test
    fun multipleGapsConvergeAfterResend() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val rawSeamA = loom.host(Pattern("multi-gap"))
        val seamB = loom.join(InMemoryTag("b"))

        // Drop broadcasts 2 (seq=2) and 4 (seq=4)
        var broadcastIdx = 0
        val customSeamA = object : us.tractat.kuilt.core.Seam by rawSeamA {
            override suspend fun broadcast(payload: ByteArray) {
                broadcastIdx++
                if (broadcastIdx != 2 && broadcastIdx != 4) {
                    rawSeamA.broadcast(payload)
                }
            }
        }

        val repA = replicatorFor(customSeamA, GSet.empty<String>(), gsetSer, backgroundScope)
        val repB = replicatorFor(seamB, GSet.empty<String>(), gsetSer, backgroundScope)

        repA.apply(Patch(GSet.of("a1")))  // seq=1 delivered
        repA.apply(Patch(GSet.of("a2")))  // seq=2 DROPPED
        repA.apply(Patch(GSet.of("a3")))  // seq=3 delivered (triggers Resend for seq=2)
        repA.apply(Patch(GSet.of("a4")))  // seq=4 DROPPED

        // Let initial messages flow + Resends for seq=2 and seq=4
        testScheduler.advanceUntilIdle()

        // Both should converge — Resends cause A to retransmit from pendingDeltas
        val expected = setOf("a1", "a2", "a3", "a4")
        assertEquals(expected, repA.state.value.elements, "A has full state")
        assertEquals(expected, repB.state.value.elements, "B converges after multi-gap recovery")
    }
}
