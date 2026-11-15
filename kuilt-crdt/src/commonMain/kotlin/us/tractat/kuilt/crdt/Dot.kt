package us.tractat.kuilt.crdt

import kotlinx.serialization.Serializable
import us.tractat.kuilt.math.plusOrNull

/**
 * A clock-free unique name for a single operation: the issuing [replica] paired
 * with that replica's own monotonically-increasing [seq] (1, 2, 3…).
 *
 * Uniqueness needs no coordination: the [replica] namespaces the dot, and a
 * replica only ever bumps its own counter, so no two operations anywhere can
 * mint the same dot. This replaces wall-clock timestamps for causality.
 *
 * Implements [Comparable] (by [replica], then by [seq]) so that serializers can
 * emit dot collections in a canonical, delivery-order-independent order.
 *
 * @property seq the per-replica sequence number; always `>= 1`.
 */
@Serializable
public data class Dot(public val replica: ReplicaId, public val seq: Long) : Comparable<Dot> {
    init {
        require(seq >= 1L) { "Dot seq must be >= 1, was $seq" }
    }

    override fun compareTo(other: Dot): Int {
        val byReplica = replica.compareTo(other.replica)
        return if (byReplica != 0) byReplica else seq.compareTo(other.seq)
    }
}

/**
 * The clock value after this one, for a per-replica sequence or a Lamport clock that must never
 * wrap (#3417). A wrapped value is [Long.MIN_VALUE]: it breaks dot uniqueness, and as a Lamport
 * time it sorts before every real id. So the mint refuses instead, as [GCounter.inc] does.
 *
 * A local replica reaches the ceiling only after 2⁶³ operations; the real path is a decoded
 * version vector, op-log or peer clock that already holds [Long.MAX_VALUE].
 *
 * @throws ArithmeticException if this is [Long.MAX_VALUE]; [clock] names it in the message.
 */
internal inline fun Long.nextTick(clock: () -> String): Long =
    plusOrNull(1L) ?: throw ArithmeticException("${clock()} is at Long.MAX_VALUE and cannot advance")
