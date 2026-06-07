package us.tractat.kuilt.crdt

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * Identity of one replica (peer) in a CRDT. Used to namespace [Dot]s so every
 * operation gets a globally-unique name with no coordination and no clock.
 *
 * **Precondition — uniqueness across all replicas.** A `ReplicaId` identifies
 * **one logical replica for its entire lifetime**. Two replicas (peers) MUST
 * have distinct `ReplicaId`s. Reusing the same value for two peers — even
 * across sessions — corrupts every causal CRDT: their dots collide, making
 * the merge treat two unrelated events as the same event, producing wrong
 * results with no runtime error.
 *
 * Choose a stable identifier (a UUID, device serial, or similar) at replica
 * creation and keep it for the peer's lifetime. Never derive a `ReplicaId`
 * from a counter that can wrap or restart.
 */
@Serializable
@JvmInline
public value class ReplicaId(public val value: String)
