package us.tractat.kuilt.raft

import kotlinx.serialization.Serializable

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
