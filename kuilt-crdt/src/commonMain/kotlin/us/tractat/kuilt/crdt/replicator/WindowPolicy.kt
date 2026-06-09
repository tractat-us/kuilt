package us.tractat.kuilt.crdt.replicator

import us.tractat.kuilt.crdt.RgaId

/**
 * Policy that decides which element ids to truncate from an [us.tractat.kuilt.crdt.Rga] sequence.
 *
 * [RgaGcCoordinator] calls [idsToTruncate] on every compaction pass (i.e. each time the
 * causal-stability watermark advances). The returned ids are added to the GC set and broadcast
 * as part of the [us.tractat.kuilt.crdt.RgaOp.Compact] delta.
 *
 * **Convergence.** Different replicas may run different [WindowPolicy] configurations.
 * After set-union merge the more-aggressive window dominates: each replica compacts the union
 * of what either dropped. This "most-aggressive-window-wins" behaviour is expected and convergent.
 *
 * ## Built-in factories
 *
 * - [WindowPolicy.never] — GC-only, no windowing (the default). Always returns an empty set.
 * - `WindowPolicy.byCount(n)` — keep the last *n* visible elements (sub-issue #254, not yet built).
 *
 * @see RgaGcCoordinator
 */
public fun interface WindowPolicy {
    /**
     * Returns the set of element ids to drop from the sequence on this compaction pass.
     *
     * [sequence] is the full ordered sequence of all [RgaId]s (both visible and tombstoned).
     * [tombstones] is the subset that have been removed but not yet compacted.
     *
     * Returning an id that is not a tombstone is safe but has no effect: [RgaGcCoordinator]
     * only compacts tombstoned ids (live elements cannot be garbage-collected).
     */
    public fun idsToTruncate(sequence: List<RgaId>, tombstones: Set<RgaId>): Set<RgaId>

    public companion object {
        /**
         * GC-only policy: no windowing. Returns an empty set on every pass, leaving
         * compaction decisions entirely to causal-stability watermark advancement.
         *
         * This is the default for [RgaGcCoordinator].
         */
        public fun never(): WindowPolicy = WindowPolicy { _, _ -> emptySet() }
    }
}
