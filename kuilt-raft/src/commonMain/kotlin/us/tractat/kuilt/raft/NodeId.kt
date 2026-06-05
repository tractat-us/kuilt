package us.tractat.kuilt.raft

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

@Serializable
@JvmInline
public value class NodeId(public val value: String)
