package us.tractat.kuilt.raft

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import us.tractat.kuilt.raft.internal.RaftEngine

public interface RaftNode {
    public val role: StateFlow<RaftRole>
    public val leader: StateFlow<NodeId?>
    public val commitIndex: StateFlow<Long>
    public val committed: Flow<LogEntry>

    /**
     * Hot [Flow] of [RaftTraceEvent]s — one event per engine state transition.
     *
     * Useful for testing (assert exact transition sequences), debugging (replay
     * chaos-test failures), and TLC trace validation. Replay=0; late collectors
     * miss historical events.
     *
     * The event vocabulary follows etcd TLA+ action names for compatibility with
     * the Vanlightly standard-raft TLC trace specification.
     */
    public val trace: Flow<RaftTraceEvent>

    /**
     * Suspends until a quorum commits the entry. Returns the committed [LogEntry].
     * @throws NotLeaderException if this node is not currently the leader (including learners).
     * @throws LeadershipLostException if leadership is lost while waiting.
     */
    public suspend fun propose(command: ByteArray): LogEntry

    public suspend fun close()
}

public fun CoroutineScope.raftNode(
    clusterConfig: ClusterConfig,
    transport: RaftTransport,
    storage: RaftStorage,
    raftConfig: RaftConfig = RaftConfig(),
): RaftNode = RaftEngine(clusterConfig, transport, storage, raftConfig, this)
