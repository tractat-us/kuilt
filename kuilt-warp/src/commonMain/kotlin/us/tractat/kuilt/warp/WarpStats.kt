package us.tractat.kuilt.warp

import kotlinx.serialization.Serializable
import us.tractat.kuilt.crdt.HyperLogLog
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.Quilted
import us.tractat.kuilt.crdt.piece

/**
 * A CRDT-mergeable statistics map: per-source cardinality sketches gathered as
 * [HyperLogLog] instances, so they converge when gossiped on the same anti-entropy
 * as the rest of the warp state.
 *
 * **Lattice laws.** [piece] is a join-semilattice operation: for every source key the
 * register arrays are merged element-wise via [HyperLogLog.piece] (itself element-wise
 * max). The key set is add-only (no removal). Together these make [piece] idempotent,
 * commutative, and associative — convergence is unconditional.
 *
 * **Delta-state gossip.** [observe] returns a [Patch] whose delta contains only the
 * single changed source's sparse HLL fragment; it is a valid lattice fragment and can
 * be forwarded directly to Quilter without carrying the full state.
 *
 * **Read API for the E-3 cost model.** [estimatedCardinality] returns the HLL estimate
 * for a given source (0 when the source has never been observed). The estimate is
 * within ~0.81% relative standard error at the default HLL precision of 14.
 *
 * **No networking in this type.** [WarpStats] is a pure CRDT value — no `Seam`,
 * no coroutines. Gossip transport is the [us.tractat.kuilt.quilter.Quilter]'s job;
 * this class only defines *what* gets gossiped.
 *
 * @sample us.tractat.kuilt.warp.sampleWarpStats
 * @see HyperLogLog
 */
@Serializable
public class WarpStats private constructor(
    private val sketches: Map<OpId, HyperLogLog>,
) : Quilted<WarpStats> {

    /**
     * Record that [element] was observed for [source]. Returns a [Patch] whose delta
     * is a sparse [WarpStats] carrying only the one changed source's HLL fragment.
     * The receiver is unchanged; apply with [piece]:
     * ```
     * val next = stats.piece(stats.observe(source, element))
     * ```
     */
    public fun observe(source: OpId, element: String): Patch<WarpStats> {
        val current = sketches[source] ?: HyperLogLog.empty()
        val hllPatch = current.add(element)
        return Patch(WarpStats(mapOf(source to hllPatch.delta)))
    }

    /**
     * Estimated number of distinct elements observed for [source].
     * Returns `0` when [source] has never been observed (no HLL exists yet).
     */
    public fun estimatedCardinality(source: OpId): Long =
        sketches[source]?.estimate() ?: 0L

    /**
     * The join: for each source key, merge the [HyperLogLog] sketches via
     * element-wise max ([HyperLogLog.piece]). Keys present in only one side are
     * taken as-is (the other side's implicit empty sketch contributes nothing).
     */
    override fun piece(other: WarpStats): WarpStats {
        if (other.sketches.isEmpty()) return this
        if (sketches.isEmpty()) return other
        val merged = HashMap<OpId, HyperLogLog>(sketches)
        for ((key, hll) in other.sketches) {
            merged[key] = merged[key]?.piece(hll) ?: hll
        }
        return WarpStats(merged)
    }

    override fun equals(other: Any?): Boolean =
        other is WarpStats && sketches == other.sketches

    override fun hashCode(): Int = sketches.hashCode()

    override fun toString(): String =
        "WarpStats(${sketches.entries.joinToString { (k, v) -> "${k.value}~${v.estimate()}" }})"

    public companion object {
        /** An empty stats map with no observed sources. */
        public fun empty(): WarpStats = WarpStats(emptyMap())
    }
}
