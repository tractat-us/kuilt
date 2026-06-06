package us.tractat.kuilt.crdt

import kotlinx.serialization.Serializable

/**
 * A clock-free unique name for a single operation: the issuing [replica] paired
 * with that replica's own monotonically-increasing [seq] (1, 2, 3…).
 *
 * Uniqueness needs no coordination: the [replica] namespaces the dot, and a
 * replica only ever bumps its own counter, so no two operations anywhere can
 * mint the same dot. This replaces wall-clock timestamps for causality.
 *
 * @property seq the per-replica sequence number; always `>= 1`.
 */
@Serializable
public data class Dot(public val replica: ReplicaId, public val seq: Long) {
    init {
        require(seq >= 1L) { "Dot seq must be >= 1, was $seq" }
    }
}
