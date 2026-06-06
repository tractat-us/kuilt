package us.tractat.kuilt.raft

/**
 * Durable state that a Raft node must persist to survive restarts.
 *
 * Raft's safety guarantees depend on two categories of durable state:
 * **vote metadata** (current term and who the node voted for in that term)
 * and the **log** (the ordered sequence of committed and uncommitted entries).
 *
 * All writes must be synchronised to stable storage before the corresponding
 * RPC reply is sent. In-memory implementations (e.g. [InMemoryRaftStorage])
 * are safe for ephemeral use (tests, transient players) but lose state on
 * process exit.
 */
public interface RaftStorage {
    /**
     * Returns the latest term this node has observed.
     *
     * Starts at `0` for a brand-new node. Increases monotonically; it is
     * never safe to decrease it.
     */
    public suspend fun term(): Long

    /**
     * Persists [term] as the latest observed term.
     *
     * Prefer [saveTermAndVotedFor] when advancing the term and clearing the
     * vote in the same operation — it is safer on crash-prone storage.
     */
    public suspend fun saveTerm(term: Long)

    /**
     * Returns the [NodeId] this node voted for in the current term, or `null`
     * if it has not yet voted.
     */
    public suspend fun votedFor(): NodeId?

    /**
     * Persists [nodeId] as the node voted for in the current term.
     *
     * Prefer [saveTermAndVotedFor] when both values change together.
     */
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

    /**
     * Appends [entries] to the end of the persistent log.
     *
     * Entries are assumed to be contiguous with whatever is already stored.
     * The engine never calls this with a gap.
     */
    public suspend fun appendEntries(entries: List<LogEntry>)

    /**
     * Returns all log entries with `index >= fromIndex`.
     *
     * Passing `fromIndex = 0` (the default) returns the full log.
     */
    public suspend fun entries(fromIndex: Long = 0L): List<LogEntry>

    /**
     * Removes all log entries with `index >= [index]`.
     *
     * Called during log conflict resolution when a follower's log diverges
     * from the leader's. After truncation, [appendEntries] is called to
     * write the correct entries from the leader.
     */
    public suspend fun truncateFrom(index: Long)
}
