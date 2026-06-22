package us.tractat.kuilt.crdt

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Round-trip invariant: every cache field of a live [Rga] must equal the corresponding
 * value computed by [Rga.fromOps] (the deserialization fallback), for every op path
 * including compaction that purges the highest-seq insert for a replica.
 *
 * This is the pin for the @Transient cache-coherence contract: if cache != fromOps,
 * a serialize→deserialize round-trip changes behaviour.
 */
class RgaCacheRoundTripTest {

    private val a = ReplicaId("a")
    private val b = ReplicaId("b")

    /**
     * Assert that all cache fields of [live] equal the fields that [Rga.fromOps] would
     * compute from the same op-log — i.e., the @Transient cache is fully reconstructable.
     */
    private fun assertCacheEqualsFromOps(live: Rga<String>, label: String) {
        val rehydrated = Rga.fromOps(live.ops, live.lamport)
        assertEquals(rehydrated.insertsById, live.insertsById, "$label: insertsById mismatch")
        assertEquals(rehydrated.maxSeqByReplica, live.maxSeqByReplica, "$label: maxSeqByReplica mismatch")
        assertEquals(rehydrated.tombstones, live.tombstones, "$label: tombstones mismatch")
        assertEquals(rehydrated.compactedIds, live.compactedIds, "$label: compactedIds mismatch")
        assertEquals(rehydrated.compactPositions, live.compactPositions, "$label: compactPositions mismatch")
    }

    /**
     * After a compaction that purges a replica's HIGHEST-seq insert, [maxSeqByReplica]
     * for that replica must equal what [Rga.fromOps] computes from the purged op-log —
     * NOT the old high-water mark from before the purge.
     *
     * Setup:
     *  - replica `a` inserts "a1" (seq=1) then "a2" (seq=2).
     *  - both are removed (tombstoned).
     *  - compact() purges both — the highest-seq (seq=2) insert for `a` is gone.
     *  - the live cache's maxSeqByReplica[a] must equal fromOps(purged-ops).maxSeqByReplica[a].
     *
     * If the cache incorrectly preserves the old high-water (seq=2) while fromOps returns
     * null/absent (no surviving inserts for `a`), nextSeqFor would agree with the stale
     * live object but diverge from fromOps — a serialize→deserialize round-trip changes
     * behaviour.
     */
    @Test
    fun maxSeqByReplicaMatchesFromOpsAfterCompactionThatPurgesHighestSeqInsert() {
        var rga = Rga.empty<String>()
        val (r1, op1) = rga.insertAfter(a, RgaId.HEAD, "a1")
        val (r2, op2) = r1.insertAfter(a, op1.id, "a2") // a's highest-seq insert
        val (r3, _) = r2.removeAt(0)!!   // remove "a2" (visible[0] after op2 — highest id wins HEAD)
        val (r4, _) = r3.removeAt(0)!!   // remove "a1"
        rga = r4

        // Both inserts are tombstoned; compact with a stable cut that covers both.
        val maxSeq = maxOf(op1.id.seq, op2.id.seq)
        val stable = VersionVector.of(mapOf(a to maxSeq))
        val compactResult = rga.compact(stable, stable, stable)
        checkNotNull(compactResult) { "compact() should purge both tombstoned inserts" }
        val (compacted, _) = compactResult

        assertCacheEqualsFromOps(compacted, "after compact purging highest-seq insert")
    }

    /**
     * Same invariant holds when one of a replica's inserts survives compaction (not
     * the top-seq one). The cache must equal fromOps on the surviving, lower-seq value.
     */
    @Test
    fun maxSeqByReplicaMatchesFromOpsAfterPartialCompaction() {
        var rga = Rga.empty<String>()
        val (r1, op1) = rga.insertAfter(a, RgaId.HEAD, "a1") // seq=1 — will survive
        val (r2, op2) = r1.insertAfter(a, op1.id, "a2")       // seq=2 — will be GC'd
        val (r3, _) = r2.removeAt(1)!! // remove "a2" (index 1: a1 then a2 in order)
        rga = r3

        // Stable cut covers only seq=2 for `a` (a2 is tombstoned and stable; a1 is live — not a candidate).
        val stable = VersionVector.of(mapOf(a to op2.id.seq))
        val compactResult = rga.compact(stable, stable, stable)
        checkNotNull(compactResult) { "compact() should purge the tombstoned a2" }
        val (compacted, _) = compactResult

        assertCacheEqualsFromOps(compacted, "after compact purging a2 while a1 survives")
    }

    /**
     * Invariant holds across a full op sequence: inserts, removes, applies, merges,
     * and a compaction. Covers all mutation paths.
     */
    @Test
    fun allCacheFieldsMatchFromOpsAfterMixedOpSequenceWithCompaction() {
        // replica a: two inserts
        val (ra1, opA1) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "a1")
        val (ra2, opA2) = ra1.insertAfter(a, opA1.id, "a2")

        // replica b: one insert
        val (rb1, opB1) = Rga.empty<String>().insertAfter(b, RgaId.HEAD, "b1")

        // merge a and b's views
        val merged = ra2.piece(rb1)

        // remove a2 (tombstone it)
        val (withRemove, _) = merged.removeAt(merged.toList().indexOf("a2"))!!

        // compact: a2 is tombstoned; stable covers a up to seq=2, b up to seq=1
        val stable = VersionVector.of(mapOf(a to opA2.id.seq, b to opB1.id.seq))
        val compactResult = withRemove.compact(stable, stable, stable)
        checkNotNull(compactResult) { "compact() should find a2 as a purgeable tombstone" }
        val (compacted, _) = compactResult

        assertCacheEqualsFromOps(compacted, "after mixed ops + compact")
    }

    /**
     * Cache coherence also holds for applyCompact (the remote-receive path), not just
     * the self-initiated compact() path.
     */
    @Test
    fun allCacheFieldsMatchFromOpsAfterApplyCompact() {
        val (r1, op1) = Rga.empty<String>().insertAfter(a, RgaId.HEAD, "a1")
        val (r2, _) = r1.removeAt(0)!!

        val stable = VersionVector.of(mapOf(a to op1.id.seq))
        val (_, compactOp) = r2.compact(stable, stable, stable)!!

        // Apply the compact op to a sibling replica that also has the tombstone.
        val sibling = r2.apply(compactOp)

        assertCacheEqualsFromOps(sibling, "after applyCompact on sibling")
    }
}
