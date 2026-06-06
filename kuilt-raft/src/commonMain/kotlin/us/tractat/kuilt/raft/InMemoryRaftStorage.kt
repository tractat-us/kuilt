package us.tractat.kuilt.raft

/**
 * An in-memory [RaftStorage] implementation.
 *
 * State is held entirely in process memory — term, vote, and log are all lost
 * on process exit. This makes it suitable for:
 * - **Tests** — fast, zero-setup, deterministic.
 * - **Ephemeral players** — nodes that rejoin the cluster fresh on restart
 *   rather than recovering from durable state (they simply catch up via
 *   log replication).
 *
 * **Production servers** should inject a persistent [RaftStorage] backed by
 * SQLite, IndexedDB, or a similar crash-safe store to guarantee Raft's
 * durability properties across restarts.
 */
public class InMemoryRaftStorage : RaftStorage {
    private var currentTerm: Long = 0L
    private var currentVotedFor: NodeId? = null
    private val log = mutableListOf<LogEntry>()

    override suspend fun term(): Long = currentTerm
    override suspend fun saveTerm(term: Long) { currentTerm = term }
    override suspend fun votedFor(): NodeId? = currentVotedFor
    override suspend fun saveVotedFor(nodeId: NodeId?) { currentVotedFor = nodeId }
    override suspend fun saveTermAndVotedFor(term: Long, votedFor: NodeId?) {
        currentTerm = term
        currentVotedFor = votedFor
    }
    override suspend fun appendEntries(entries: List<LogEntry>) { log.addAll(entries) }
    override suspend fun entries(fromIndex: Long): List<LogEntry> = log.filter { it.index >= fromIndex }
    override suspend fun truncateFrom(index: Long) { log.removeAll { it.index >= index } }
}
