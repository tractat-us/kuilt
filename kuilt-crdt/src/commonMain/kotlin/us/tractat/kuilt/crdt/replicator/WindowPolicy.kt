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
     * The returned ids are *candidates* — what the policy *would like* dropped. [RgaGcCoordinator]
     * gates every one through the causal-stability barrier before any is removed.
     *
     * **Live-element windowing is not yet wired (see [byCount]).** Forgetting old *visible* history
     * (the convergent `DROP_OLDEST`) requires dropping live elements, which orphans the retained
     * window unless the materializer re-roots inserts whose predecessor was compacted. That reroot
     * primitive is a pending design decision; until it lands, a returned live id has no effect and
     * only tombstoned candidates are compacted.
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
         * This is intended as the convergent replacement for
         * `MutableSharedFlow(replay = n, DROP_OLDEST)`: old visible history is forgotten once it
         * falls out of the window. **This selection is correct, but the drop is not yet enforced**
         * for live elements — dropping a live prefix orphans the retained window unless the RGA
         * materializer re-roots inserts whose predecessor was compacted. That reroot primitive is a
         * pending design decision (#254 re-plan); until it lands [byCount] selects the prefix but
         * [RgaGcCoordinator] only compacts the tombstoned subset of it.
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
