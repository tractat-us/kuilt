package us.tractat.kuilt.raft

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * A stable, unique identifier for a Raft cluster member.
 *
 * Node IDs must be stable across restarts — the same physical node must
 * present the same [value] every time it joins the cluster. A string that
 * identifies the node's network endpoint (e.g. `"node-1"`, `"192.168.1.10:7000"`)
 * is a natural choice.
 *
 * [NodeId] is an inline value class backed by a [String], so it has no
 * allocation overhead in hot paths.
 */
@Serializable
@JvmInline
public value class NodeId(public val value: String)
