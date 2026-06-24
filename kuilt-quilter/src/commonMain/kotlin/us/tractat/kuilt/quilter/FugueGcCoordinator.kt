package us.tractat.kuilt.quilter

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import us.tractat.kuilt.core.ScopedCloseable
import us.tractat.kuilt.crdt.Fugue
import us.tractat.kuilt.crdt.FugueOp
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.VersionVector

private val logger = KotlinLogging.logger("us.tractat.kuilt.crdt.FugueGcCoordinator")

/**
 * Coordinator that drives tombstone GC for a [Fugue] CRDT replicated via [Quilter],
 * gated by the **eviction-safe causal-stability barrier** (ADR-003 addendum v3, #262).
 *
 * Mirrors [RgaGcCoordinator] exactly, substituting [Fugue] for [Rga] and
 * [FugueOp.Compact] for [RgaOp.Compact].
 *
 * ## Role
 *
 * The [Fugue] op-log grows without bound unless tombstones are periodically
 * garbage-collected, but a tombstoned `Insert(I, …)` cannot be purged while a
 * concurrent `Insert(J, parent=I)` minted by a different author may still exist
 * undelivered — purging `I` would leave `J`'s subtree unreachable. The sound
 * condition is **causal stability**, not a scalar watermark.
 *
 * [Quilter] publishes the two version-vector quantities the barrier needs:
 * - [Quilter.cutFrontier] — an atomically-published [CutFrontier] carrying the
 *   **stable cut** `S` and the **frontier max** `F`.
 * - [Quilter.deliveredLocal] — this replica's own contiguous delivered VV.
 *
 * @param state live [Fugue] state (updated by [Quilter] on every incoming delta).
 * @param cutFrontier the atomically-published causal-stability cut + frontier from
 *   [Quilter.cutFrontier].
 * @param delivered this replica's contiguous delivered VV from [Quilter.deliveredLocal].
 * @param applyCompaction called with each compaction [Patch]; the caller wires this to
 *   [Quilter.apply] so the delta propagates to all peers.
 * @param scope the [CoroutineScope] for background coroutines.
 *
 * @see Fugue.compact
 * @see Quilter.cutFrontier
 * @see Quilter.deliveredLocal
 */
public class FugueGcCoordinator<V>(
    private val state: StateFlow<Fugue<V>>,
    private val cutFrontier: StateFlow<CutFrontier>,
    private val delivered: StateFlow<VersionVector>,
    private val applyCompaction: (Patch<Fugue<V>>) -> Unit,
    scope: CoroutineScope,
) : ScopedCloseable(scope) {

    private val gcJob: Job

    /** Exposed internally so tests can verify [close] cancels the GC loop. */
    internal val gcJobForTest: Job get() = gcJob

    init {
        // Re-evaluate GC on EITHER trigger: a [cutFrontier] change (the cut/frontier advanced)
        // OR a [state] change (a new tombstone appeared). A local Remove mints no author dot, so
        // it does not move [delivered] / [cutFrontier] — without observing [state] too, a tombstone
        // applied while the cut already covers it would never be re-considered.
        gcJob = merge(cutFrontier, state)
            .onEach { evaluate() }
            .launchIn(this.scope)
    }

    private fun evaluate() {
        val cut = cutFrontier.value
        val deliveredNow = delivered.value
        val frontierComplete = deliveredNow.dominates(cut.frontierMax)
        logger.debug {
            "[fugue-gc] cut: stableCut=${cut.stableCut} frontierMax=${cut.frontierMax} " +
                "delivered=$deliveredNow frontierComplete=$frontierComplete"
        }
        compactUntilStable(cut, deliveredNow)
    }

    /**
     * Runs [Fugue.compact] in a loop until it returns null.
     * Handles the two-pass chain-GC case: removing a tombstone may unblock a structural
     * predecessor, making it eligible on the next pass.
     */
    private fun compactUntilStable(cut: CutFrontier, deliveredNow: VersionVector) {
        while (true) {
            val current = state.value
            val (_, compactOp) = current.compact(
                stableCut = cut.stableCut,
                frontierMax = cut.frontierMax,
                delivered = deliveredNow,
            ) ?: run {
                logger.debug { "[fugue-gc] nothing to compact at cut=$cut" }
                return
            }
            logger.debug {
                "[fugue-gc] compacting ${compactOp.positions.size} id(s) at " +
                    "stableCut=${cut.stableCut} frontierMax=${cut.frontierMax}"
            }
            applyCompaction(Patch(Fugue.empty<V>().apply(compactOp)))
        }
    }
}
