package us.tractat.kuilt.cluster

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import us.tractat.kuilt.raft.ClusterConfig
import us.tractat.kuilt.raft.Committed
import us.tractat.kuilt.raft.LogEntry
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftNode
import us.tractat.kuilt.raft.RaftRole
import us.tractat.kuilt.raft.RaftTraceEvent
import us.tractat.kuilt.raft.Snapshot

/**
 * A [RaftNode] whose [changeMembership] runs the next scripted step, or applies the target when the
 * script is empty. Records every target it is handed.
 */
internal class ScriptedNode(initial: ClusterConfig, role: RaftRole = RaftRole.Leader) : RaftNode {
    override val role: MutableStateFlow<RaftRole> = MutableStateFlow(role)
    override val leader: StateFlow<NodeId?> = MutableStateFlow(null)
    private val _membership = MutableStateFlow(initial)
    override val membership: StateFlow<ClusterConfig> = _membership.asStateFlow()
    override val commitIndex: StateFlow<Long> = MutableStateFlow(0L)
    override val committed: Flow<Committed> = emptyFlow()
    override fun committedFrom(fromIndex: Long): Flow<Committed> = emptyFlow()
    override val snapshots: MutableStateFlow<Snapshot?> = MutableStateFlow(null)
    override val compactionFloor: StateFlow<Long> = MutableStateFlow(0L)
    override val trace: Flow<RaftTraceEvent> = emptyFlow()

    val calls: MutableList<ClusterConfig> = mutableListOf()
    val script: ArrayDeque<(ClusterConfig) -> ClusterConfig> = ArrayDeque()
    var refusalsThrown: Int = 0
        private set

    /** Another writer's change committing while the reconciler is between its read and its call. */
    fun commitUnderneath(config: ClusterConfig) {
        _membership.value = config
    }

    /** Throw [refusal] from a scripted step, counting it so a test can assert its rig fired. */
    fun refuse(refusal: Exception): Nothing {
        refusalsThrown++
        throw refusal
    }

    override suspend fun changeMembership(target: ClusterConfig): ClusterConfig {
        calls += target
        val step = script.removeFirstOrNull() ?: return target.also { _membership.value = it }
        return step(target)
    }

    override suspend fun propose(command: ByteArray): LogEntry = error("unused")
    override suspend fun propose(command: ByteArray, requestId: Long): LogEntry = error("unused")
    override suspend fun close(): Unit = Unit
}
