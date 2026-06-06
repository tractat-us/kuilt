package us.tractat.kuilt.raft

public interface RaftStorage {
    public suspend fun term(): Long
    public suspend fun saveTerm(term: Long)
    public suspend fun votedFor(): NodeId?
    public suspend fun saveVotedFor(nodeId: NodeId?)

    /**
     * Atomically persists [term] and [votedFor] in a single durable write.
     *
     * Called at every term-advance site (become-candidate, step-down).
     * Implementations MUST write both values in a single atomic operation —
     * a crash between two separate writes allows a node to vote twice in the
     * same term (Raft §5.1/§5.2).
     *
     * Persistent implementations (SQLite, IndexedDB) implement this as one
     * statement: `UPDATE SET term=?, voted_for=?`. Avoid NSUserDefaults on
     * iOS — it is not crash-safe without explicit `synchronize()`.
     *
     * Note: [appendEntries] after [truncateFrom] is a liveness concern
     * (not safety) on crash; implementors may wish to wrap those two in a
     * transaction as well, but it is not required.
     */
    public suspend fun saveTermAndVotedFor(term: Long, votedFor: NodeId?)

    public suspend fun appendEntries(entries: List<LogEntry>)
    public suspend fun entries(fromIndex: Long = 0L): List<LogEntry>
    public suspend fun truncateFrom(index: Long)
}
