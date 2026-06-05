package us.tractat.kuilt.raft

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

public interface RaftNode {
    public val role: StateFlow<RaftRole>
    public val leader: StateFlow<NodeId?>
    public val commitIndex: StateFlow<Long>
    public val committed: Flow<LogEntry>

    /**
     * Suspends until a quorum commits the entry. Returns the committed [LogEntry].
     * @throws NotLeaderException if this node is not currently the leader (including learners).
     * @throws LeadershipLostException if leadership is lost while waiting.
     */
    public suspend fun propose(command: ByteArray): LogEntry

    public suspend fun close()
}
