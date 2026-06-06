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
     * Implementations MUST write both values atomically — a crash between
     * two separate writes allows a node to vote twice in the same term
     * (Raft §5.1 / §5.2 election safety).
     *
     * Persistent implementations (SQLite, IndexedDB) MUST implement this as
     * a single transaction: `UPDATE raft_meta SET term=?, voted_for=?`.
     * Avoid NSUserDefaults on iOS without explicit `synchronize()` — it is
     * not crash-safe by default.
     *
     * Note: [appendEntries] after [truncateFrom] is a liveness concern on
     * crash (extra round-trip) but not a safety concern — no composite
     * method is required for that pair.
     */
    public suspend fun saveTermAndVotedFor(term: Long, votedFor: NodeId?)

    public suspend fun appendEntries(entries: List<LogEntry>)
    public suspend fun entries(fromIndex: Long = 0L): List<LogEntry>
    public suspend fun truncateFrom(index: Long)
}
