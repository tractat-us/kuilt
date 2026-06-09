package us.tractat.kuilt.crdt.replicator

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.RgaId
import us.tractat.kuilt.crdt.RgaOp
import us.tractat.kuilt.crdt.VersionVector

private val logger = KotlinLogging.logger("us.tractat.kuilt.crdt.RgaGcCoordinator")

/**
 * Coordinator that drives tombstone GC (and optional history windowing) for an [Rga] CRDT
 * replicated via [SeamReplicator], gated by the **eviction-safe causal-stability barrier**
 * (ADR-003 addendum v3, #262).
 *
 * ## Role
 *
 * The [Rga] op-log grows without bound unless tombstones are periodically garbage-collected,
 * but a tombstoned `Insert(I, …)` cannot be purged while a concurrent `Insert(J, after=I)`
 * minted by a *different* author may still exist undelivered — purging `I` would orphan `J`
 * everywhere (#262). The sound condition is **causal stability**, not a scalar watermark.
 *
 * [SeamReplicator] publishes the two version-vector quantities the barrier needs:
 * - [SeamReplicator.cutFrontier] — an atomically-published [CutFrontier] carrying both the
 *   **stable cut** `S` (`min` over live peers — every peer has delivered everything at-or-below
 *   it) and the **frontier** `F = max(F_live, retainedFrontier)` (the highest dot any peer,
 *   live or evicted-but-retained, has told us exists). Published together (wiring invariant W1)
 *   so a compactor never observes a half-update where `F` has fallen below a known-to-exist dot.
 * - [SeamReplicator.deliveredLocal] — this replica's own contiguous **delivered** version vector.
 *
 * This coordinator observes [cutFrontier] and, on each emission, hands `S`, `F`, and the fresh
 * [delivered] value to [Rga.compact]`(stableCut, frontierMax, delivered)`. That method refuses GC
 * unless the frontier is complete (`delivered.dominates(F)` — this replica has delivered every
 * known-to-exist dot, so any concurrent successor of a tombstone is already visible), the
 * tombstone is causally stable (`S.contains(I.dot)`), and it has no surviving local successor.
 *
 * [cutFrontier] is republished whenever [deliveredLocal] **or** the matrix clock changes, and
 * `_deliveredLocal` is updated *before* `cutFrontier` is published (see `recomputeCut`), so
 * reading [delivered]`.value` on each emission is consistent with the cut that triggered it.
 *
 * ## Loop-until-stable
 *
 * On each cut emission the coordinator calls [Rga.compact] in a loop until it returns `null`.
 * This handles the two-pass chain case: removing a tombstone may unblock a structural
 * predecessor, making it eligible on the next pass. [SeamReplicator.apply] updates [state]
 * synchronously via `StateFlow.update`, so `state.value` reflects each [applyCompaction] before
 * the next loop iteration — `loop-until-null` is therefore safe.
 *
 * Each resulting [RgaOp.Compact] is bridged into a [Patch]`<`[Rga]`<V>>` via
 * `Rga.empty<V>().apply(compactOp)` — a minimal single-op delta that any peer merges via
 * [Rga.piece], triggering the same purge on the remote op-log.
 *
 * ## Window policy
 *
 * [windowPolicy] may return additional ids to truncate from the visible prefix (history
 * windowing). The default [WindowPolicy.never] does nothing beyond causal-stability GC.
 *
 * @param state live [Rga] state (updated by [SeamReplicator] on every incoming delta).
 * @param cutFrontier the atomically-published causal-stability cut + frontier from
 *   [SeamReplicator.cutFrontier].
 * @param delivered this replica's contiguous delivered VV from [SeamReplicator.deliveredLocal].
 * @param applyCompaction called with each compaction [Patch]; the caller wires this to
 *   [SeamReplicator.apply] so the delta propagates to all peers.
 * @param windowPolicy optional history-windowing policy (default [WindowPolicy.never]).
 * @param scope the [CoroutineScope] for background coroutines.
 *
 * @see Rga.compact
 * @see SeamReplicator.cutFrontier
 * @see SeamReplicator.deliveredLocal
 * @see WindowPolicy
 */
public class RgaGcCoordinator<V>(
    private val state: StateFlow<Rga<V>>,
    private val cutFrontier: StateFlow<CutFrontier>,
    private val delivered: StateFlow<VersionVector>,
    private val applyCompaction: (Patch<Rga<V>>) -> Unit,
    private val windowPolicy: WindowPolicy = WindowPolicy.never(),
    private val scope: CoroutineScope,
) {
    init {
        // Re-evaluate GC on EITHER trigger: a [cutFrontier] change (the cut/frontier advanced)
        // OR a [state] change (a new tombstone appeared). A local Remove mints no author dot, so
        // it does not move [delivered] / [cutFrontier] — without observing [state] too, a tombstone
        // applied while the cut already covers it would never be re-considered (the [StateFlow]
        // dedups the unchanged cut). Both triggers read the current cut + delivered fresh.
        merge(cutFrontier, state)
            .onEach { evaluate() }
            .launchIn(scope)
    }

    /**
     * Read the current cut and fresh [delivered] value (consistent with the cut — the replicator
     * updates `deliveredLocal` before publishing `cutFrontier`), log the GC-decision inputs, and
     * run compaction to stable.
     */
    private fun evaluate() {
        val cut = cutFrontier.value
        val deliveredNow = delivered.value
        val frontierComplete = deliveredNow.dominates(cut.frontierMax)
        logger.debug {
            "[rga-gc] cut: stableCut=${cut.stableCut} frontierMax=${cut.frontierMax} " +
                "delivered=$deliveredNow frontierComplete=$frontierComplete"
        }
        compactUntilStable(cut, deliveredNow)
    }

    /**
     * Runs [Rga.compact] + optional [windowPolicy] in a loop until no further compaction
     * is possible for this [cut]. Handles the two-pass chain-GC case: removing a tombstone
     * may unblock a structural predecessor, making it eligible on the next pass.
     *
     * [SeamReplicator.apply] updates [StateFlow] synchronously via `StateFlow.update`, so
     * `state.value` reflects each compaction before the next loop iteration. This makes the
     * loop-until-null safe: each iteration reads fresh state and terminates when nothing
     * further can be GC'd.
     */
    private fun compactUntilStable(cut: CutFrontier, delivered: VersionVector) {
        if (!delivered.dominates(cut.frontierMax)) {
            logger.debug {
                "[rga-gc] GC refused — frontier-incomplete: known-but-undelivered dots remain " +
                    "(delivered=$delivered does not dominate frontierMax=${cut.frontierMax})"
            }
            return
        }
        while (true) {
            val current = state.value
            val windowIds = windowPolicy.idsToTruncate(current.sequence, current.tombstones)
            val (_, compactOp) = compactWithWindow(current, cut, delivered, windowIds) ?: run {
                logger.debug { "[rga-gc] nothing to compact at cut=$cut (stable + window yield nothing)" }
                return
            }
            logger.debug {
                "[rga-gc] compacting ${compactOp.ids.size} tombstone(s) at " +
                    "stableCut=${cut.stableCut} frontierMax=${cut.frontierMax}"
            }
            applyCompaction(Patch(Rga.empty<V>().apply(compactOp)))
        }
    }

    /**
     * Compacts tombstone-GC candidates **and** [windowIds] (which may include *live* elements
     * targeted by the history window) in a single barrier-gated pass, returning the unified
     * [RgaOp.Compact] or `null` if nothing qualifies.
     *
     * The candidate set is `tombstones ∪ windowIds`. Every candidate — tombstone or live — is held
     * to the identical causal-stability barrier inside [Rga.compactCandidates]: a live window-id is
     * dropped **only** when causally stable, frontier-complete, and free of any surviving local
     * successor. This is what makes history-windowing of live elements safe against the #275 hazard
     * (a concurrent undelivered `Insert(J, after=I)` blocks dropping `I` via the no-successor /
     * frontier conditions, exactly as for tombstone GC). A live candidate not yet barrier-safe is
     * deferred to a later pass; convergence still holds because the broadcast `Compact` set-unions.
     */
    private fun compactWithWindow(
        rga: Rga<V>,
        cut: CutFrontier,
        delivered: VersionVector,
        windowIds: Set<RgaId>,
    ): Pair<Rga<V>, RgaOp.Compact>? =
        rga.compactCandidates(
            candidates = rga.tombstones + windowIds,
            stableCut = cut.stableCut,
            frontierMax = cut.frontierMax,
            delivered = delivered,
        )
}
