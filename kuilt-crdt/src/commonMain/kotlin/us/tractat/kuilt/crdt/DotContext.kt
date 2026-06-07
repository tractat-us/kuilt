package us.tractat.kuilt.crdt

import kotlinx.serialization.Serializable

/**
 * The causal history of a replica: every [Dot] it has ever witnessed, live or
 * already removed. It is the memory that outlives a delete — that is what lets a
 * merge tell "I saw that dot and dropped it on purpose" from "I just haven't
 * seen it yet".
 *
 * Stored compactly: the contiguous prefix per replica is a version vector
 * ([vv]: `replica → highest contiguous seq`), and any non-contiguous dots wait
 * in a [cloud] until the gaps before them fill, at which point they compact into
 * the vector. The cloud is required because kuilt fabrics reorder and duplicate
 * frames, so dots can arrive out of order.
 *
 * It is itself a [Quilted]: [piece] is the join (union of both histories).
 * Equality is canonical — compaction pushes the cloud as far into the vector as
 * possible and drops already-covered dots — so two contexts with the same causal
 * history are structurally equal, which the conformance laws rely on.
 */
@Serializable
public class DotContext private constructor(
    private val vv: Map<ReplicaId, Long>,
    private val cloud: Set<Dot>,
) : Quilted<DotContext> {

    /** True if [dot] has been witnessed — covered by the vector or held in the cloud. */
    public fun contains(dot: Dot): Boolean =
        dot.seq <= (vv[dot.replica] ?: 0L) || dot in cloud

    /**
     * Returns the next [Dot] for [replica] to mint for a new local operation.
     *
     * **Precondition — one DotContext per logical replica.** The returned dot
     * is globally unique *within the causal history of this DotContext*. Two
     * independently-constructed `DotContext` instances for the same replica will
     * both compute `seq = 1` for their first dot — producing a collision. Every
     * causal CRDT that depends on dot uniqueness ([ORSet], [MVRegister], [ORMap])
     * will produce wrong results if two events share the same `(replica, seq)`.
     *
     * In practice: create exactly **one** `DotContext` per logical replica and
     * thread it through all of that replica's operations for its lifetime.
     */
    public fun nextDot(replica: ReplicaId): Dot =
        Dot(replica, (vv[replica] ?: 0L) + 1L)

    /** This history with [dot] witnessed (idempotent; compacts the cloud). */
    public fun add(dot: Dot): DotContext = compact(vv, cloud + dot)

    /** The join: the union of two causal histories. */
    override fun piece(other: DotContext): DotContext {
        val mergedVv = HashMap<ReplicaId, Long>(vv)
        for ((replica, seq) in other.vv) {
            val current = mergedVv[replica]
            if (current == null || seq > current) mergedVv[replica] = seq
        }
        return compact(mergedVv, cloud + other.cloud)
    }

    override fun equals(other: Any?): Boolean =
        other is DotContext && vv == other.vv && cloud == other.cloud

    override fun hashCode(): Int = 31 * vv.hashCode() + cloud.hashCode()

    override fun toString(): String = "DotContext(vv=$vv, cloud=$cloud)"

    public companion object {
        /** The empty history — nothing witnessed. */
        public val EMPTY: DotContext = DotContext(emptyMap(), emptySet())

        /** A history witnessing exactly [dots]. */
        public fun of(vararg dots: Dot): DotContext =
            dots.fold(EMPTY) { ctx, dot -> ctx.add(dot) }

        /**
         * Normalize `(vv, cloud)`: drop cloud dots already covered by the vector,
         * and repeatedly extend the vector by any cloud dot that sits exactly at
         * the next contiguous seq, until no more compaction is possible.
         */
        private fun compact(vv: Map<ReplicaId, Long>, cloud: Set<Dot>): DotContext {
            val newVv = HashMap(vv)
            val remaining = cloud.toMutableSet()
            var changed = true
            while (changed) {
                changed = false
                val iterator = remaining.iterator()
                while (iterator.hasNext()) {
                    val dot = iterator.next()
                    val current = newVv[dot.replica] ?: 0L
                    when {
                        dot.seq <= current -> iterator.remove()
                        dot.seq == current + 1L -> {
                            newVv[dot.replica] = dot.seq
                            iterator.remove()
                            changed = true
                        }
                        // else: a gap remains before this dot — keep it in the cloud
                    }
                }
            }
            return DotContext(newVv, remaining)
        }
    }
}
