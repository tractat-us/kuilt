package us.tractat.kuilt.crdt

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * Identity of one replica (peer) in a CRDT. Used to namespace [Dot]s so every
 * operation gets a globally-unique name with no coordination and no clock.
 */
@Serializable
@JvmInline
public value class ReplicaId(public val value: String)
