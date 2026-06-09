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
 * - [WindowPolicy.byCount] — keep the last *n* **visible** elements; drop the leading prefix.
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
     * The returned ids are dropped on this pass via the **history-windowing path**, which is
     * **un-gated** — unlike tombstone GC it does not pass through the causal-stability barrier.
     * Windowing forgets position, so dropping a live id is safe: reroot-to-HEAD (#254) keeps the
     * retained window reachable, and a concurrent `Insert(J, after=window-dropped-I)` resurfaces
     * at the window boundary instead of being orphaned. The barrier-gated tombstone-GC path stays
     * separate and position-preserving; the two never share a gate.
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

        /**
         * History-windowing policy: keep the last [n] **visible** elements, targeting the
         * leading prefix — every id (visible *or* tombstoned) that sorts before the start of
         * the retained window of `n` visible elements.
         *
         * This is the convergent replacement for `MutableSharedFlow(replay = n, DROP_OLDEST)`: old
         * visible history is forgotten once it falls out of the window. The leading prefix —
         * **live and tombstoned ids alike** — is dropped via the un-gated windowing path;
         * reroot-to-HEAD (#254) keeps the retained window reachable after its predecessor is purged.
         *
         * **Per-peer divergence is allowed.** Two replicas with different [n] both converge:
         * after `Compact` set-union the more-aggressive (smaller-[n]) window dominates.
         *
         * @param n the number of trailing visible elements to retain. `n <= 0` targets the
         *   entire prefix; a window larger than the visible count selects nothing.
         */
        public fun byCount(n: Int): WindowPolicy = WindowPolicy { sequence, tombstones ->
            prefixBeyondWindow(sequence, tombstones, n)
        }

        /**
         * The leading prefix of [sequence] that falls outside a window retaining the last [n]
         * **visible** ids. Walks the sequence from the end counting visible (non-tombstoned)
         * ids; once [n] visible ids have been seen, every remaining (earlier) id — visible or
         * tombstoned — is in the truncation set.
         */
        private fun prefixBeyondWindow(
            sequence: List<RgaId>,
            tombstones: Set<RgaId>,
            n: Int,
        ): Set<RgaId> {
            val keep = maxOf(n, 0)
            var visibleSeen = 0
            var cut = sequence.size // index where the retained window begins
            for (i in sequence.indices.reversed()) {
                if (visibleSeen == keep) break
                if (sequence[i] !in tombstones) visibleSeen++
                cut = i
            }
            return sequence.subList(0, cut).toSet()
        }
    }
}
