package us.tractat.kuilt.raft

/**
 * Identifies the log position a snapshot covers: everything with `index <= lastIncludedIndex`.
 *
 * [config] is the effective cluster configuration as of [lastIncludedIndex] — the membership a node
 * must adopt when it installs this snapshot, since the config log entries that produced it were
 * discarded by compaction. `null` means the covered prefix carried no config change (the cluster was
 * still under its bootstrap configuration); a non-null [ConfigPayload] may be simple (`old == null`)
 * or joint (`old != null`), so a snapshot taken mid-transition resumes the joint phase on install.
 */
public data class SnapshotMeta(
    val lastIncludedIndex: Long,
    val lastIncludedTerm: Long,
    val config: ConfigPayload? = null,
)

/** A persisted snapshot: its [meta] plus the opaque application [state] bytes. */
public class StoredSnapshot(public val meta: SnapshotMeta, public val state: ByteArray)

/**
 * The node §5.2 Election Safety established as the leader of [term] — a fact *about that term*, which
 * is why the two travel together and are meaningless apart.
 *
 * A bare [leaderId] would have to be explicitly invalidated at every site that moves the current term,
 * and would go silently wrong the day one of them was missed. Carrying [term] makes staleness
 * self-evident to the reader instead: a record whose term is not the current one simply is not this
 * term's leader.
 */
public data class LeaderForTerm(val term: Long, val leaderId: NodeId)

/**
 * Durable state that a Raft node must persist to survive restarts.
 *
 * Raft's safety guarantees depend on two categories of durable state:
 * **vote metadata** (current term and who the node voted for in that term)
 * and the **log** (the ordered sequence of committed and uncommitted entries).
 * A third, [leaderForTerm], is not a §5.2 safety requirement but a *sender-authority*
 * one: without it a restarted node cannot tell this term's leader from any other voter.
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
     * Returns the leader this node established for some term, or `null` if it has never established
     * one. See [saveLeaderForTerm].
     *
     * The returned [LeaderForTerm.term] is **not** necessarily the current term — the engine compares
     * it to `currentTerm` at every read and treats a mismatch as "no leader established for this
     * term". An implementation must return the record it was last given, unchanged; deciding whether
     * it is still relevant is not the storage's job.
     */
    public suspend fun leaderForTerm(): LeaderForTerm?

    /**
     * Persists [leaderId] as the node §5.2 established as leader of [term], replacing any previously
     * stored record.
     *
     * Written once per term — on this node's first leader-contact of that term, or on winning it — so
     * the cadence is the same as [saveTermAndVotedFor]'s, not a per-heartbeat cost.
     *
     * Why it survives a restart: §3.10 leadership transfer authenticates an incoming `TimeoutNow`
     * against the leader established for the current term, and that check is only as good as the
     * node's memory of it. A node that comes back holding no leader for a term it durably restored
     * cannot tell the real leader's `TimeoutNow` from any other voter's, and must either accept both
     * or break the honest transfer.
     *
     * Implementations MUST write [term] and [leaderId] as **one** record: a crash between two
     * separate writes would leave an identity paired with a term it was never established for, which
     * is worse than holding no record at all — the engine's staleness check compares the stored term
     * to its own and would admit the mismatched identity as authoritative. A single row
     * (`UPDATE raft_meta SET leader_term=?, leader_id=?`) satisfies this; two independent keys do not.
     *
     * It need not be atomic with [saveTerm] / [saveTermAndVotedFor]: the two are written at different
     * moments by construction, and a self-describing record is exactly what removes the need.
     */
    public suspend fun saveLeaderForTerm(term: Long, leaderId: NodeId)

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

    /**
     * Persists [state] as the snapshot covering all entries with `index <= meta.lastIncludedIndex`.
     *
     * Crash-safety: this MUST be durable before [discardLogPrefix] runs. A crash between the two
     * leaves the snapshot plus the full log — redundant but safe and recoverable. Overwrites any
     * previously stored snapshot (an older snapshot is strictly dominated).
     */
    public suspend fun saveSnapshot(meta: SnapshotMeta, state: ByteArray)

    /** Returns the stored snapshot, or `null` if none has been saved. */
    public suspend fun loadSnapshot(): StoredSnapshot?

    /** Removes all log entries with `index <= throughIndex`. Idempotent; tolerates a floor below the first retained entry. */
    public suspend fun discardLogPrefix(throughIndex: Long)
}
