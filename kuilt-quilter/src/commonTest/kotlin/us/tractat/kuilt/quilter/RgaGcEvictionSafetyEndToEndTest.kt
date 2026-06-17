/**
 * End-to-end eviction-safety proof for RGA GC over the **live replicator stack** (#270).
 *
 * The model probes (`RgaCompactEvictionSafeBarrierTest`, `SeamReplicatorStableCutTest`) pin the
 * #275 refusal at the predicate / matrix-clock level. This test runs the *whole* stack — real
 * [SeamReplicator]s + [RgaGcCoordinator]s over [ControllableLoom] — and proves the one property
 * that matters: a concurrent `Insert(J, after=I)` whose tombstoned predecessor `I` is a GC
 * candidate is **never orphaned**, even across a held partition and the eviction of `J`'s author.
 *
 * ## The ControllableLoom limitation (and how the test works around it)
 *
 * The ideal #275 split is "the compactor learns `(c, seq(J))` exists via *gossip* but has not
 * delivered J's *delta*". [ControllableLoom.holdDelivery] holds a peer's **whole inbound FIFO**
 * keyed by *recipient* — holding the compactor partitions it from *every* sender, so it cannot
 * receive a live peer's gossip-ahead either, and a peer that has not delivered a dot cannot learn
 * the dot exists. The gossip-but-not-delta split is therefore **not expressible end-to-end** on
 * this control surface; it is covered at the predicate/matrix level by the model probes
 * (`RgaCompactEvictionSafeBarrierTest` §6.2, `SeamReplicatorStableCutTest`).
 *
 * What the FIFO *can* express end-to-end is the **delivered-successor** refusal (condition 4 of
 * the real predicate) under a genuine partition + author eviction: A inserts I; C mints
 * `J = Insert(J, after=I)` and A/B **deliver** it; A then tombstones I, so on A the tombstoned I
 * has a surviving local successor J and `Rga.compact` refuses GC — driven entirely by live
 * delivery, no model-side matrix. C is then **held off and evicted** (`seam.close()`); J lives on
 * A and B and is never orphaned. Releasing C and converging leaves every replica agreeing with
 * J present. This proves the live stack does not over-GC a tombstone whose concurrent successor
 * the system already carries, and that evicting the successor's author does not lose it.
 */
@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.quilter

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.serializer
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.RgaId
import us.tractat.kuilt.crdt.RgaOp
import us.tractat.kuilt.test.ControllableLoom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val E2E_MSG_SER = ReplicatorMessage.serializer(Rga.wireSerializer(serializer<String>()))

class RgaGcEvictionSafetyEndToEndTest {

    private fun rep(seam: Seam, scope: CoroutineScope): SeamReplicator<Rga<String>> {
        val replicator = SeamReplicator(
            replica = ReplicaId(seam.selfId.value),
            seam = seam,
            initial = Rga.empty(),
            messageSerializer = E2E_MSG_SER,
            scope = scope,
            config = SeamReplicatorConfig(expectVirtualTime = true),
        )
        RgaGcCoordinator(
            state = replicator.state,
            cutFrontier = replicator.cutFrontier,
            delivered = replicator.deliveredLocal,
            applyCompaction = { patch -> replicator.apply(patch) },
            scope = scope,
        )
        return replicator
    }

    private fun SeamReplicator<Rga<String>>.applyOp(op: RgaOp<String>) =
        apply(Patch(Rga.empty<String>().apply(op)))

    @Test
    fun concurrentInsertNeverOrphanedAcrossPartitionAndAuthorEviction() = runTest(UnconfinedTestDispatcher()) {
        val loom = ControllableLoom()
        val seamA = loom.host(Pattern("a"))
        val seamB = loom.join(InMemoryTag("b"))
        val seamC = loom.join(InMemoryTag("c"))
        val peerC = PeerId("c")

        val repA = rep(seamA, backgroundScope)
        val repB = rep(seamB, backgroundScope)
        val repC = rep(seamC, backgroundScope)
        testScheduler.advanceUntilIdle()

        // A inserts I; all three deliver it.
        val (afterI, opI) = repA.state.value.insertAfter(repA.replica, RgaId.HEAD, "I")
        repA.applyOp(opI)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("I"), repC.state.value.toList(), "C delivered I")

        // C mints J = Insert(J, after=I) and broadcasts it — A and B deliver J BEFORE A removes I,
        // so the concurrent successor of I is in flight and known to the compactor. (Delivering it
        // is the only way ControllableLoom's FIFO can make A *know* (c, seq(J)) exists.)
        val (_, opJ) = repC.state.value.insertAfter(repC.replica, opI.id, "J")
        repC.applyOp(opJ)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("I", "J"), repA.state.value.toList(), "A delivered J after I")

        // Partition C: it will not receive A's Remove(I) (or the later eviction's effects).
        loom.holdDelivery(peerC)

        // A tombstones I. On A the tombstoned I now has a surviving local successor J, so the real
        // Rga.compact refuses to GC it (condition 4) — driven entirely by live delivery.
        val remI = afterI.removeAt(0)!!.second
        repA.applyOp(remI)
        testScheduler.advanceUntilIdle()

        assertTrue(repA.state.value.tombstones.contains(opI.id), "A must NOT GC I — J references it")
        assertEquals(listOf("J"), repA.state.value.toList(), "J visible on A through the retained I")
        assertEquals(listOf("J"), repB.state.value.toList(), "J visible on B too")

        // Evict C (close its seam) while it is still partitioned. J lives on A and B — not lost.
        seamC.close()
        testScheduler.advanceUntilIdle()
        assertTrue(repA.state.value.tombstones.contains(opI.id), "after C's eviction, A still retains I")
        assertEquals(listOf("J"), repA.state.value.toList(), "J survives C's departure — never orphaned")

        // Release C's held queue and converge. Every replica agrees, with J present throughout.
        loom.releaseDelivery(peerC)
        testScheduler.advanceUntilIdle()
        assertEquals(repA.state.value.toList(), repB.state.value.toList(), "A and B converge")
        assertEquals(listOf("J"), repA.state.value.toList(), "final converged list still contains J")
    }

    @Test
    fun stableTombstoneWithNoConcurrentSuccessorIsCollectedEndToEnd() = runTest(UnconfinedTestDispatcher()) {
        // Non-vacuity: with no concurrent successor in flight, the same live stack DOES GC a stable
        // tombstone — the refusal above is specific, not a blanket "never GC".
        val loom = ControllableLoom()
        val seamA = loom.host(Pattern("a"))
        val seamB = loom.join(InMemoryTag("b"))

        val repA = rep(seamA, backgroundScope)
        rep(seamB, backgroundScope)
        testScheduler.advanceUntilIdle()

        val (afterI, opI) = repA.state.value.insertAfter(repA.replica, RgaId.HEAD, "I")
        repA.applyOp(opI)
        testScheduler.advanceUntilIdle()
        repA.applyOp(afterI.removeAt(0)!!.second)
        testScheduler.advanceUntilIdle()

        assertTrue(repA.state.value.tombstones.isEmpty(), "stable tombstone with no successor IS collected end-to-end")
        assertEquals(emptyList(), repA.state.value.toList())
    }
}
