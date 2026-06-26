package us.tractat.kuilt.warp

import us.tractat.kuilt.crdt.Quilted
import us.tractat.kuilt.crdt.ReplicaId

/**
 * A federated-averaging (FedAvg) CRDT: each peer trains locally on [sampleCount] examples
 * and contributes a weight vector [localWeights], and the merged read is the count-weighted
 * mean across all peers.
 *
 * ## Lattice design — per-peer epoch-versioned slots
 *
 * FedAvg holds one **slot** per contributing [ReplicaId]. Each slot carries:
 * - an `epoch` (monotone `Long`): a peer increments its epoch when it starts a new round.
 *   A re-contribution at the same epoch is byte-identical, so the join is idempotent.
 *   A higher-epoch slot supersedes a lower-epoch one (LWW by epoch).
 * - a `sampleCount` (positive `Long`): the number of examples the peer trained on.
 * - a `weightedSum` (`List<Double>`): `sampleCount × localWeights`, pre-multiplied so
 *   merging across peers is pure addition (no division at merge time).
 *
 * The [piece] join is:
 * - Union the per-peer maps.
 * - On a key (peer) collision, keep the slot with the higher epoch.
 * - Equal epoch → identical data (idempotent re-delivery) → keep either (both are equal).
 *
 * This satisfies the three lattice laws:
 * - **Idempotent**: `a.piece(a) == a` — same slot, same epoch, nothing changes.
 * - **Commutative**: `a.piece(b) == b.piece(a)` — union and max-epoch are both commutative.
 * - **Associative**: max-epoch over three slots groups the same way regardless of order.
 *
 * ## Reading the result
 *
 * Call [weights] to get `Σ(n_k · w_k) / Σ(n_k)` per coordinate.
 * [weights] throws [IllegalStateException] when no peer has contributed (total count is zero) —
 * dividing by zero would produce wrong data silently; fail loud instead.
 *
 * ## Single-round usage (F1)
 *
 * For a single training round, use the default `epoch = 1L` in [contribution]. Re-delivering
 * the same frame is absorbed idempotently. Multiple rounds are supported by passing a
 * monotonically increasing `epoch` to [contribution].
 *
 * @sample us.tractat.kuilt.warp.sampleFedAvg
 */
public class FedAvg private constructor(
    private val slots: Map<ReplicaId, Slot>,
) : Quilted<FedAvg> {

    private data class Slot(
        val epoch: Long,
        val sampleCount: Long,
        val weightedSum: List<Double>,
    )

    /**
     * The count-weighted mean of all peer contributions, per coordinate.
     *
     * Computed as `Σ_k(n_k · w_k[i]) / Σ_k(n_k)` for each coordinate `i`.
     *
     * @throws IllegalStateException if no peer has contributed (total sample count is zero).
     */
    public val weights: List<Double>
        get() {
            val totalCount = totalSampleCount()
            check(totalCount > 0L) {
                "FedAvg has no contributions — cannot compute weights (total sample count is zero)"
            }
            return coordinateAverages(totalCount)
        }

    /** The join: per-peer union with LWW by epoch on collisions. */
    override fun piece(other: FedAvg): FedAvg {
        if (other.slots.isEmpty()) return this
        if (slots.isEmpty()) return other
        val merged = HashMap<ReplicaId, Slot>(slots)
        for ((peer, incoming) in other.slots) {
            val existing = merged[peer]
            if (existing == null || incoming.epoch > existing.epoch) {
                merged[peer] = incoming
            }
        }
        return FedAvg(merged)
    }

    override fun equals(other: Any?): Boolean =
        other is FedAvg && slots == other.slots

    override fun hashCode(): Int = slots.hashCode()

    override fun toString(): String = "FedAvg(peers=${slots.keys}, totalCount=${totalSampleCount()})"

    private fun totalSampleCount(): Long = slots.values.sumOf { it.sampleCount }

    private fun coordinateAverages(totalCount: Long): List<Double> {
        val dimension = slots.values.maxOf { it.weightedSum.size }
        return List(dimension) { i -> coordinateSum(i) / totalCount }
    }

    private fun coordinateSum(index: Int): Double =
        slots.values.sumOf { slot -> slot.weightedSum.getOrElse(index) { 0.0 } }

    public companion object {

        /** The empty FedAvg with no contributions. [weights] throws on this value. */
        public val ZERO: FedAvg = FedAvg(emptyMap())

        /**
         * Build a single peer's contribution to a FedAvg round.
         *
         * Stores `(sampleCount × localWeights[i])` per coordinate internally, so the
         * merge across peers is pure addition with no division until [weights] is read.
         *
         * @param peer the contributing replica — must be globally unique.
         * @param sampleCount the number of training examples (must be positive).
         * @param localWeights the locally-trained weight vector (same dimension across all peers).
         * @param epoch monotone round counter — increment to supersede a prior contribution.
         *              Defaults to `1L` (single-round FedAvg).
         * @throws IllegalArgumentException if [sampleCount] is not positive.
         */
        public fun contribution(
            peer: ReplicaId,
            sampleCount: Long,
            localWeights: List<Double>,
            epoch: Long = 1L,
        ): FedAvg {
            require(sampleCount > 0L) {
                "sampleCount must be positive, was $sampleCount"
            }
            val weightedSum = localWeights.map { w -> w * sampleCount }
            return FedAvg(mapOf(peer to Slot(epoch, sampleCount, weightedSum)))
        }
    }
}
