package us.tractat.kuilt.raft

public interface RaftStorage {
    public suspend fun term(): Long
    public suspend fun saveTerm(term: Long)
    public suspend fun votedFor(): NodeId?
    public suspend fun saveVotedFor(nodeId: NodeId?)

    /**
     * Atomically persist [term] and [votedFor] together.
     *
     * Raft §5.1/§5.2 requires these two fields to be written atomically: a crash
     * between a separate [saveTerm] and [saveVotedFor] would leave the node with a
     * stale `votedFor`, allowing it to vote twice in the same term. Persistent
     * implementations (e.g. SQLite) must implement this as a single statement:
     * `UPDATE SET term=?, voted_for=?`.
     *
     * The default falls back to two sequential calls — safe for [InMemoryRaftStorage]
     * where there is no crash risk, but persistent implementations must override.
     */
    public suspend fun saveTermAndVotedFor(term: Long, votedFor: NodeId?) {
        saveTerm(term)
        saveVotedFor(votedFor)
    }

    public suspend fun appendEntries(entries: List<LogEntry>)
    public suspend fun entries(fromIndex: Long = 0L): List<LogEntry>
    public suspend fun truncateFrom(index: Long)
}
