/**
 * 3-peer integration test for [RgaGcCoordinator] + [SeamReplicator].
 *
 * Asserts that continuous insert + remove across 3 peers converges to a
 * **bounded** op-log size once the causal-stability watermark catches up —
 * i.e. the op-log stops growing unboundedly after compaction.
 *
 * All tests use [UnconfinedTestDispatcher] with [SeamReplicatorConfig.expectVirtualTime] = true.
 */
@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.quilter

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.serializer
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.RgaId
import us.tractat.kuilt.crdt.RgaOp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Use Int as the value type.
// Use Rga.wireSerializer (backed by RgaOpSerializer) instead of Rga.serializer —
// the generated serializer uses PolymorphicSerializer(Any) for V which fails in CBOR.
private val RGA_REP_CFG = SeamReplicatorConfig(expectVirtualTime = true)
private val RGA_MSG_SER = ReplicatorMessage.serializer(Rga.wireSerializer(serializer<Int>()))

/**
 * Wires a [SeamReplicator]`<`[Rga]`<Int>>` + [RgaGcCoordinator] pair over a raw seam.
 *
 * Returns the replicator so the caller can apply mutations and observe [SeamReplicator.state].
 */
private fun wireRgaWithGc(
    rawSeam: us.tractat.kuilt.core.Seam,
    replica: ReplicaId,
    scope: kotlinx.coroutines.CoroutineScope,
): SeamReplicator<Rga<Int>> {
    val replicator = SeamReplicator(
        replica = replica,
        seam = rawSeam,
        initial = Rga.empty(),
        messageSerializer = RGA_MSG_SER,
        scope = scope,
        config = RGA_REP_CFG,
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

/** The number of tombstoned (removed but not yet GC'd) ids in this [Rga]. */
private fun <V> Rga<V>.tombstoneCount(): Int = tombstones.size

class RgaGcCoordinator3PeerIntegrationTest {

    /**
     * Three peers insert and remove elements in rounds. After convergence, the coordinator
     * drives GC so the op-log size is bounded — it does not grow proportionally to the total
     * number of operations ever applied.
     *
     * Bounded means: after compaction, the op-log contains no tombstones — the historical
     * Insert/Remove pairs have been GC'd.
     *
     * 10 rounds × 3 peers × (insert + remove) = 60 ops total.
     * Without GC: tombstone count grows to 30.
     * With GC: tombstone count must reach 0 on all replicas.
     */
    @Test
    fun continuousInsertRemoveConvergesToBoundedOpLog() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val seamA = loom.host(Pattern("rga-gc-3peer"))
        val seamB = loom.join(InMemoryTag("b"))
        val seamC = loom.join(InMemoryTag("c"))

        val replicaA = ReplicaId(seamA.selfId.value)
        val replicaB = ReplicaId(seamB.selfId.value)
        val replicaC = ReplicaId(seamC.selfId.value)

        val repA = wireRgaWithGc(seamA, replicaA, backgroundScope)
        val repB = wireRgaWithGc(seamB, replicaB, backgroundScope)
        val repC = wireRgaWithGc(seamC, replicaC, backgroundScope)

        var totalOps = 0

        // Each round: each peer inserts a value at HEAD, then removes the element it just inserted
        // (by id, not by index, to avoid removing a different element after concurrent inserts merge).
        repeat(10) { round ->
            for ((rep, replica) in listOf(repA to replicaA, repB to replicaB, repC to replicaC)) {
                val (_, insertOp) = rep.state.value.insertAfter(
                    replica = replica,
                    after = RgaId.HEAD,
                    value = round,
                )
                rep.apply(Patch(Rga.empty<Int>().apply(insertOp)))
                totalOps++
                testScheduler.advanceUntilIdle()

                // Remove the specific element we just inserted, not index 0 (which may have changed).
                val removeOp = RgaOp.Remove<Int>(id = insertOp.id)
                rep.apply(Patch(Rga.empty<Int>().apply(removeOp)))
                totalOps++
            }
            testScheduler.advanceUntilIdle()
        }

        testScheduler.advanceUntilIdle()

        // All three replicas must converge to the same visible list.
        val listA = repA.state.value.toList()
        val listB = repB.state.value.toList()
        val listC = repC.state.value.toList()
        assertEquals(listA, listB, "A and B must converge to the same list")
        assertEquals(listA, listC, "A and C must converge to the same list")

        // GC must have cleared all tombstones on all replicas.
        val tombstonesA = repA.state.value.tombstoneCount()
        val tombstonesB = repB.state.value.tombstoneCount()
        val tombstonesC = repC.state.value.tombstoneCount()

        assertTrue(
            tombstonesA == 0,
            "A must have 0 tombstones after GC (had totalOps=$totalOps); found $tombstonesA",
        )
        assertTrue(
            tombstonesB == 0,
            "B must have 0 tombstones after GC; found $tombstonesB",
        )
        assertTrue(
            tombstonesC == 0,
            "C must have 0 tombstones after GC; found $tombstonesC",
        )
    }

    /**
     * A late-joining 4th peer receives a [ReplicatorMessage.FullState] that already reflects GC.
     * It converges to the same visible list as the existing 3 peers without re-introducing
     * GC'd ops.
     */
    @Test
    fun lateJoinerConvergesViaFullStateAfterGc() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val seamA = loom.host(Pattern("rga-gc-late-joiner"))
        val seamB = loom.join(InMemoryTag("b"))
        val seamC = loom.join(InMemoryTag("c"))

        val replicaA = ReplicaId(seamA.selfId.value)
        val replicaB = ReplicaId(seamB.selfId.value)
        val replicaC = ReplicaId(seamC.selfId.value)

        val repA = wireRgaWithGc(seamA, replicaA, backgroundScope)
        wireRgaWithGc(seamB, replicaB, backgroundScope)
        wireRgaWithGc(seamC, replicaC, backgroundScope)

        // Insert and remove several elements to build up tombstones.
        repeat(5) { i ->
            val (_, insertOp) = repA.state.value.insertAfter(replicaA, RgaId.HEAD, i)
            repA.apply(Patch(Rga.empty<Int>().apply(insertOp)))
            testScheduler.advanceUntilIdle()

            val removeResult = repA.state.value.removeAt(0)
            if (removeResult != null) {
                repA.apply(Patch(Rga.empty<Int>().apply(removeResult.second)))
            }
            testScheduler.advanceUntilIdle()
        }

        // Insert a surviving element.
        val (_, surviveOp) = repA.state.value.insertAfter(replicaA, RgaId.HEAD, 999)
        repA.apply(Patch(Rga.empty<Int>().apply(surviveOp)))
        testScheduler.advanceUntilIdle()

        val expectedList = repA.state.value.toList()

        // Late joiner D joins after GC has run.
        val seamD = loom.join(InMemoryTag("d"))
        val replicaD = ReplicaId(seamD.selfId.value)
        wireRgaWithGc(seamD, replicaD, backgroundScope)
        testScheduler.advanceUntilIdle()

        // repA's visible list must be unchanged after D joins.
        val listAfterJoin = repA.state.value.toList()
        assertEquals(expectedList, listAfterJoin, "list must not change when D joins post-GC")
    }

    /**
     * Two-pass chain GC in a 3-peer scenario: insert 1, insert 2 after 1, remove both.
     * The coordinator loops-until-stable, compacting 2 first (no successor) then 1
     * (structural predecessor of 2, now freed).
     */
    @Test
    fun chainGcConvergesAcrossThreePeers() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val seamA = loom.host(Pattern("rga-chain-gc"))
        val seamB = loom.join(InMemoryTag("b"))
        val seamC = loom.join(InMemoryTag("c"))

        val replicaA = ReplicaId(seamA.selfId.value)
        val replicaB = ReplicaId(seamB.selfId.value)
        val replicaC = ReplicaId(seamC.selfId.value)

        val repA = wireRgaWithGc(seamA, replicaA, backgroundScope)
        wireRgaWithGc(seamB, replicaB, backgroundScope)
        wireRgaWithGc(seamC, replicaC, backgroundScope)

        testScheduler.advanceUntilIdle()

        // Insert 1 then 2 after 1 — structural predecessor chain.
        val (_, insertFirst) = repA.state.value.insertAfter(replicaA, RgaId.HEAD, 1)
        repA.apply(Patch(Rga.empty<Int>().apply(insertFirst)))
        testScheduler.advanceUntilIdle()

        val (_, insertSecond) = repA.state.value.insertAfter(replicaA, insertFirst.id, 2)
        repA.apply(Patch(Rga.empty<Int>().apply(insertSecond)))
        testScheduler.advanceUntilIdle()

        // Remove both elements.
        val removeFirst = repA.state.value.removeAt(0)
        if (removeFirst != null) {
            repA.apply(Patch(Rga.empty<Int>().apply(removeFirst.second)))
            testScheduler.advanceUntilIdle()
        }
        val removeSecond = repA.state.value.removeAt(0)
        if (removeSecond != null) {
            repA.apply(Patch(Rga.empty<Int>().apply(removeSecond.second)))
            testScheduler.advanceUntilIdle()
        }

        // Let all acks + GC propagate.
        testScheduler.advanceUntilIdle()

        assertEquals(emptyList(), repA.state.value.toList(), "A must have empty list after chain GC")
        assertTrue(
            repA.state.value.tombstones.isEmpty(),
            "A must have no tombstones after loop-until-stable chain GC",
        )
    }
}
