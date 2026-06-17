package us.tractat.kuilt.raft

import kotlinx.serialization.Serializable

/**
 * One entry in the replicated Raft log.
 *
 * @param index 1-based, monotonically increasing position in the log. The log
 *   is contiguous — there are no gaps between consecutive entries.
 * @param term The leader's term when this entry was appended. Term numbers
 *   increase monotonically across the cluster's lifetime.
 * @param command Opaque application bytes. The Raft layer treats this as an
 *   uninterpreted blob; the application gives [command] meaning after the
 *   entry appears on [RaftNode.committed]. An application may legitimately
 *   propose an empty [command] — emptiness alone does **not** mark an entry
 *   internal (see [isNoOp]).
 * @param isNoOp `true` only for the internal §5.4.2 election no-op a new leader
 *   appends so prior-term entries can advance `commitIndex`. No-ops are real log
 *   entries (replicated and persisted) but are **not** application data, so they
 *   are withheld from [RaftNode.committed]. Application-proposed entries always
 *   have `isNoOp == false`, even when their [command] is empty.
 * @param config Non-null only for internal membership-change entries (§6 joint
 *   consensus). Like [isNoOp], config entries are replicated and persisted but
 *   withheld from [RaftNode.committed] — they carry cluster membership, not
 *   application data. A `null` value means this is a normal application entry.
 * @param dedupKey The Raft §8 client-serial dedup identity stamped by the
 *   proposer. Non-null for stamped application entries; `null` for internal
 *   no-op/config entries and for legacy entries decoded before this field
 *   existed.
 */
@Serializable
public data class LogEntry(
    val index: Long,
    val term: Long,
    val command: ByteArray,
    val isNoOp: Boolean = false,
    val config: ConfigPayload? = null,
    val dedupKey: DedupKey? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LogEntry) return false
        return index == other.index && term == other.term &&
            command.contentEquals(other.command) && isNoOp == other.isNoOp &&
            config == other.config && dedupKey == other.dedupKey
    }
    override fun hashCode(): Int {
        var r = index.hashCode(); r = 31 * r + term.hashCode()
        r = 31 * r + command.contentHashCode(); r = 31 * r + isNoOp.hashCode()
        r = 31 * r + config.hashCode(); r = 31 * r + dedupKey.hashCode(); return r
    }
}

/**
 * The membership payload carried by a config log entry (§6 joint consensus).
 *
 * A non-null [old] means this is a **joint** configuration C_{old,new}: commit and
 * election require majorities of both [old].voters and [new].voters independently.
 * A null [old] means this is a **simple** configuration C_new: cluster has fully
 * transitioned to [new].
 *
 * Config entries ride the normal replicated log and are adopted on append
 * (not on commit) — the cardinal §6 safety rule. They are serializable so they
 * survive CBOR encoding in [RaftMessage] and persistence in [RaftStorage].
 */
@Serializable
public data class ConfigPayload(
    val old: ClusterConfig?,
    val new: ClusterConfig,
)
