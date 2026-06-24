package us.tractat.kuilt.quilter

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import us.tractat.kuilt.core.ScopedCloseable
import us.tractat.kuilt.crdt.MovableTree
import us.tractat.kuilt.crdt.MoveTreeCompact
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.VersionVector

private val logger = KotlinLogging.logger("us.tractat.kuilt.crdt.MovableTreeGcCoordinator")

/**
 * Coordinator that drives move-log GC for a [MovableTree] CRDT replicated via [Quilter],
 * gated by the **eviction-safe causal-stability barrier** (ADR-003 addendum v3, #262).
 *
 * ## Role
 *
 * The [MovableTree] move-log grows without bound unless causally-stable, superseded ops
 * are periodically garbage-collected. A `MoveOp` on node `n` is eligible for GC once:
 * 1. Causally stable — `op.seq ≤ stableCut[op.replica]`.
 * 2. Superseded — a later stable op on `n` is the winning placement; this op is not.
 * 3. Not a creation op (`value != null`) still referenced by any live op.
 * 4. Frontier-complete — `delivered.dominates(frontierMax)`.
 *
 * This coordinator observes [Quilter.cutFrontier] (for cut and frontier) and
 * [Quilter.deliveredLocal] (this replica's contiguous delivered VV), then calls
 * [MovableTree.compact] in a loop until nothing further qualifies. Each resulting
 * [MoveTreeCompact] is broadcast as a [Patch] via [applyCompaction] so peers trim
 * their own logs.
 *
 * ## Loop-until-stable
 *
 * Compaction runs in a loop until [MovableTree.compact] returns `null`. A single pass
 * may unlock a second (e.g., removing a superseded move reveals another). [Quilter.apply]
 * updates [state] synchronously, so `state.value` reflects each application before the next
 * iteration.
 *
 * ## Triggering
 *
 * Re-evaluates on EITHER trigger: a [cutFrontier] change (cut advanced or frontier changed)
 * OR a [state] change (a new move op appeared that may now be compactable). Without the
 * [state] trigger, an op applied while the cut already covers it would never be re-evaluated
 * (the [StateFlow] deduplicates unchanged cuts).
 *
 * @param state live [MovableTree] state (updated by [Quilter] on every incoming delta).
 * @param cutFrontier the atomically-published causal-stability cut + frontier from
 *   [Quilter.cutFrontier].
 * @param delivered this replica's contiguous delivered VV from [Quilter.deliveredLocal].
 * @param applyCompaction called with each compaction [Patch]; wire this to [Quilter.apply]
 *   so the delta propagates to all peers.
 * @param scope the [CoroutineScope] for background coroutines.
 *
 * @see MovableTree.compact
 * @see Quilter.cutFrontier
 * @see Quilter.deliveredLocal
 */
public class MovableTreeGcCoordinator<V>(
    private val state: StateFlow<MovableTree<V>>,
    private val cutFrontier: StateFlow<CutFrontier>,
    private val delivered: StateFlow<VersionVector>,
    private val applyCompaction: (Patch<MovableTree<V>>) -> Unit,
    scope: CoroutineScope,
) : ScopedCloseable(scope) {

    private val gcJob: Job

    /** Exposed internally so tests can verify [close] cancels the GC loop. */
    internal val gcJobForTest: Job get() = gcJob

    init {
        gcJob = merge(cutFrontier, state)
            .onEach { evaluate() }
            .launchIn(this.scope)
    }

    private fun evaluate() {
        val cut = cutFrontier.value
        val deliveredNow = delivered.value
        val frontierComplete = deliveredNow.dominates(cut.frontierMax)
        logger.debug {
            "[movabletree-gc] stableCut=${cut.stableCut} frontierMax=${cut.frontierMax} " +
                "delivered=$deliveredNow frontierComplete=$frontierComplete"
        }
        compactUntilStable(cut, deliveredNow)
    }

    private fun compactUntilStable(cut: CutFrontier, deliveredNow: VersionVector) {
        while (true) {
            val current = state.value
            val (_, compactOp) = current.compact(
                stableCut = cut.stableCut,
                frontierMax = cut.frontierMax,
                delivered = deliveredNow,
            ) ?: run {
                logger.debug { "[movabletree-gc] nothing to compact at cut=$cut" }
                return
            }
            logger.debug {
                "[movabletree-gc] compacting ${compactOp.droppedDots.size} op(s) at " +
                    "stableCut=${cut.stableCut} frontierMax=${cut.frontierMax}"
            }
            applyCompaction(compactDelta(compactOp))
        }
    }

    private fun compactDelta(compactOp: MoveTreeCompact): Patch<MovableTree<V>> =
        Patch(MovableTree.empty<V>().applyCompact(compactOp))
}
