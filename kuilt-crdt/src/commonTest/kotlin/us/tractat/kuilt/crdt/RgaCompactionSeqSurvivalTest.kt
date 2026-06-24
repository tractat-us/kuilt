package us.tractat.kuilt.crdt

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #639: a self-compaction that purges a replica's highest-seq insert must not let the
 * per-author seq high-water regress when the state is reconstructed from the op-log alone
 * (the deserialize path, no cache). Otherwise the next insert reuses a seq it already minted,
 * which can corrupt the causal-stability frontier downstream.
 */
class RgaCompactionSeqSurvivalTest {

    private val a = ReplicaId("a")

    /** Insert one element (seq 1), remove it, then compact it away. */
    private fun compactAwayOnlyInsert(): Rga<String> {
        val (a0, opI) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "I")
        val (a1, _) = a0.removeAt(0)!!
        val stable = VersionVector.of(mapOf(a to opI.id.seq))
        val (compacted, _) = a1.compact(stableCut = stable, frontierMax = stable, delivered = stable)!!
        return compacted
    }

    @Test
    fun recomputedMaxSeqFoldsInCompactedIds() {
        val compacted = compactAwayOnlyInsert()
        // The live cached value is correct across compaction...
        assertEquals(1L, compacted.maxSeqByReplica[a], "cached high-water is preserved across compaction")
        // ...and the cacheless recompute (the deserialize path) must agree — the compacted
        // insert's seq still lives in the retained Compact op's positions.
        val rebuilt = Rga.fromOps(compacted.ops, compacted.lamport)
        assertEquals(1L, rebuilt.maxSeqByReplica[a], "recomputed high-water must fold in compacted ids")
    }

    @Test
    fun nextSeqAfterCompactionAndReloadDoesNotReuse() {
        val compacted = compactAwayOnlyInsert()
        // fromOps is exactly what RgaSerializer.deserialize calls — reconstructing cacheless.
        val reloaded = Rga.fromOps(compacted.ops, compacted.lamport)
        val (_, opNext) = reloaded.insertAfter(a, RgaId.HEAD, "J")
        assertEquals(2L, opNext.id.seq, "next seq after compaction + reload must not reuse the compacted seq 1")
    }
}
