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
 *   entry appears on [RaftNode.committed].
 */
@Serializable
public data class LogEntry(
    val index: Long,
    val term: Long,
    val command: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LogEntry) return false
        return index == other.index && term == other.term && command.contentEquals(other.command)
    }
    override fun hashCode(): Int {
        var r = index.hashCode(); r = 31 * r + term.hashCode(); r = 31 * r + command.contentHashCode(); return r
    }
}
