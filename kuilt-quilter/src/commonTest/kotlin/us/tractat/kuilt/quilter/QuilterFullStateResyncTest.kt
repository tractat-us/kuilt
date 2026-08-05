@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.quilter

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.crdt.GSet
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A [QuiltMessage.FullState] heal must resynchronise the receive watermark, not just the
 * state (#1266). Before the fix, `onFullState` merged the snapshot but left
 * `expectedReceiveSeq` at its initial value, so a late joiner whose gap range was already
 * GC'd at the sender buffered every subsequent delta forever: it never acked via the delta
 * path, the sender's `pendingDeltas` never GC'd again, and every delta cost a
 * Resend → FullState round-trip — a permanent protocol livelock (state stayed convergent,
 * which is why convergence-only tests missed it).
 */
class QuilterFullStateResyncTest {

    private val gsetSer = QuiltMessage.serializer(
        GSet.serializer(kotlinx.serialization.serializer<String>()),
    )

    private fun replicatorFor(seam: Seam, scope: CoroutineScope) = Quilter(
        replica = ReplicaId(seam.selfId.value),
        seam = seam,
        initial = GSet.empty<String>(),
        messageSerializer = gsetSer,
        scope = scope,
        config = QuilterConfig(expectVirtualTime = true),
    )

    /**
     * Scenario from #1266:
     * 1. A mints deltas 1..3; B acks them all → A's watermark = 3, `pendingDeltas` GC'd.
     * 2. C joins late: A sends a FullState snapshot that already covers 1..3.
     * 3. A mints delta 4.
     *
     * Post-fix, the FullState fast-forwards C's receive cursor past the GC'd history, so
     * delta 4 applies directly and C acks it: A's watermark reaches 4 and delta 4 is GC'd.
     * Pre-fix, C buffers delta 4 forever (expected seq stays 1), never acks, and A's
     * watermark/pending buffer are pinned permanently.
     */
    @Test
    fun fullStateHealResyncsReceiveCursorSoLateJoinerCanAck() = runTest(
        UnconfinedTestDispatcher(),
        timeout = TEST_WEDGE_BACKSTOP,
    ) {
        val loom = InMemoryLoom()
        val seamA = loom.host(Pattern("fullstate-resync"))
        val seamB = loom.join(InMemoryTag("b"))

        val repA = replicatorFor(seamA, backgroundScope)
        val repB = replicatorFor(seamB, backgroundScope)

        // A mints deltas 1..3; B acks all of them → watermark 3, history GC'd at A.
        repA.apply(Patch(GSet.of("pre-1")))
        repA.apply(Patch(GSet.of("pre-2")))
        repA.apply(Patch(GSet.of("pre-3")))
        testScheduler.advanceUntilIdle()
        assertEquals(3L, repA.universalAckFlow.value, "precondition: B acked deltas 1..3")
        assertTrue(repA.pendingDeltasForTest.isEmpty(), "precondition: deltas 1..3 GC'd at A")

        // C joins late — its only route to the 1..3 history is the FullState snapshot.
        val seamC = loom.join(InMemoryTag("c"))
        val repC = replicatorFor(seamC, backgroundScope)
        testScheduler.advanceUntilIdle()

        // A mints delta 4. C must be able to apply and ack it via the delta path.
        repA.apply(Patch(GSet.of("post-4")))
        testScheduler.advanceUntilIdle()

        val expected = setOf("pre-1", "pre-2", "pre-3", "post-4")
        assertAll(
            { assertEquals(expected, repB.state.value.elements, "B converges") },
            { assertEquals(expected, repC.state.value.elements, "C converges") },
            {
                assertEquals(
                    4L,
                    repA.universalAckFlow.value,
                    "C must ack delta 4 — the FullState heal has to fast-forward its receive cursor",
                )
            },
            {
                assertTrue(
                    repA.pendingDeltasForTest.isEmpty(),
                    "delta 4 must be GC'd at A, not pinned forever by a livelocked late joiner",
                )
            },
        )
    }
}
