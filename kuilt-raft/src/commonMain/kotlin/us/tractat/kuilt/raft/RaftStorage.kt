package us.tractat.kuilt.raft

public interface RaftStorage {
    public suspend fun term(): Long
    public suspend fun saveTerm(term: Long)
    public suspend fun votedFor(): NodeId?
    public suspend fun saveVotedFor(nodeId: NodeId?)
    public suspend fun appendEntries(entries: List<LogEntry>)
    public suspend fun entries(fromIndex: Long = 0L): List<LogEntry>
    public suspend fun truncateFrom(index: Long)
}
