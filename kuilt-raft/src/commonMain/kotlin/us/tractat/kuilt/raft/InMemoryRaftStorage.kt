package us.tractat.kuilt.raft

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
