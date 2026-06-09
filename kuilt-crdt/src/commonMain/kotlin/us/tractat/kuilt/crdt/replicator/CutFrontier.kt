package us.tractat.kuilt.crdt.replicator

import us.tractat.kuilt.crdt.VersionVector

/**
 * An atomically-published snapshot of the two causal-stability quantities a
 * [us.tractat.kuilt.crdt.Rga] GC compactor needs (ADR-003 addendum v3, #262).
 *
 * - [stableCut] `S` — elementwise **min** over live peers ∪ self of their delivered
 *   version vectors, maintained **monotonically** (a fresh joiner, which is
 *   FullState-synced, must not lower it). Drives condition 2 of the GC predicate:
 *   `S.contains(I.dot)` — the tombstoned op is causally stable.
 * - [frontierMax] `F` — `max(F_live, retainedFrontier)`: the highest dot any peer,
 *   **live or evicted-but-retained**, has told us exists. Drives condition 3:
 *   `delivered.dominates(F)` — self has delivered everything known to exist.
 *
 * The two are published **together**, in a single [SeamReplicator.cutFrontier]
 * emission, so a compactor can never observe an intermediate state in which `F` has
 * floored below a known-to-exist dot — wiring invariant **W1** of ADR §4.6. A
 * consumer must read the whole [CutFrontier], not re-derive the halves from separate
 * sources.
 */
public data class CutFrontier(
    public val stableCut: VersionVector,
    public val frontierMax: VersionVector,
) {
    public companion object {
        /** The initial cut — nothing stable, nothing known to exist. */
        public val EMPTY: CutFrontier = CutFrontier(VersionVector.EMPTY, VersionVector.EMPTY)
    }
}
