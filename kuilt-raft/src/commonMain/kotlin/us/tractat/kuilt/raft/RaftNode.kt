/**
 * # kuilt-raft — distributed Raft consensus for Kotlin Multiplatform
 *
 * `kuilt-raft` implements the [Raft consensus algorithm](https://raft.github.io)
 * on top of any [RaftTransport], which is itself typically backed by a kuilt-core
 * [us.tractat.kuilt.core.Seam]. It provides strongly-consistent, replicated state
 * across a cluster of nodes without requiring a centralised coordinator.
 *
 * ## Quick start
 *
 * 1. **Build a [ClusterConfig]** — list the voter [NodeId]s (and optional learners).
 * 2. **Provide a [RaftTransport]** — use [SeamRaftTransport] to wrap a kuilt `Seam`,
 *    or implement the interface directly for other transports.
 * 3. **Provide a [RaftStorage]** — use [InMemoryRaftStorage] for tests; inject a
 *    SQLite/IndexedDB-backed implementation for production (durable across restarts).
 * 4. **Call [CoroutineScope.raftNode]** — the node starts running inside the given
 *    scope. Its lifetime is tied to the scope's cancellation.
 * 5. **Observe [RaftNode.role]** to know when this node becomes [RaftRole.Leader].
 * 6. **Propose** by calling [RaftNode.propose] on the leader — it suspends until
 *    a quorum commits the entry and returns the committed [LogEntry].
 * 7. **Apply** by collecting [RaftNode.committed] on every node — entries appear
 *    here in index order once committed by a quorum.
 *
 * ## Example
 *
 * ```kotlin
 * val cluster = ClusterConfig.ofVoters(listOf(NodeId("a"), NodeId("b"), NodeId("c")))
 *
 * // On node "a": wrap a kuilt Seam as the transport
 * val seam: Seam = loom.host(Pattern("raft-cluster"))
 * val transport = SeamRaftTransport(seam)
 * val storage = InMemoryRaftStorage()   // use persistent storage in production
 *
 * val node: RaftNode = scope.raftNode(cluster, transport, storage)
 *
 * // Apply committed entries (runs on every node in the cluster)
 * scope.launch {
 *     node.committed.collect { entry ->
 *         applyToStateMachine(entry.command)
 *     }
 * }
 *
 * // Propose on the leader
 * scope.launch {
 *     node.awaitLeadership()   // suspend until this node is the leader
 *     try {
 *         val committed = node.propose("set x=1".encodeToByteArray())
 *         println("committed at index ${committed.index}")
 *     } catch (e: NotLeaderException) {
 *         // stepped down between the check and the propose — redirect to node.leader
 *     } catch (e: LeadershipLostException) {
 *         // leadership lost mid-flight — outcome unknown, retry with idempotent key
 *     }
 * }
 * ```
 *
 * ## Safety properties
 *
 * - **Election Safety** — at most one leader is elected per term.
 * - **Log Matching** — if two nodes have an entry at the same index and term,
 *   all preceding entries are identical.
 * - **Leader Completeness** — a leader's log contains every entry committed in
 *   any earlier term.
 * - **State Machine Safety** — if a node has applied an entry at index `i`, no
 *   other node applies a different entry at that index.
 *
 * ## Learners
 *
 * Nodes listed in [ClusterConfig.learners] are non-voting replicas. They receive
 * all log entries from the leader but never vote and never lead. [RaftNode.propose]
 * always throws [NotLeaderException] on a learner node.
 *
 * @see RaftNode for the runtime interface
 * @see CoroutineScope.raftNode for the construction entry point
 * @see docs/superpowers/specs/2026-06-05-raft-design.md for the design spec
 */
package us.tractat.kuilt.raft

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import us.tractat.kuilt.raft.internal.RaftEngine

/**
 * The runtime interface for a single Raft cluster member.
 *
 * Obtain an instance via [CoroutineScope.raftNode]. The node starts running
 * immediately and remains active until [close] is called or the owning
 * [CoroutineScope] is cancelled.
 */
public interface RaftNode {
    /**
     * The current [RaftRole] of this node — [RaftRole.Leader], [RaftRole.Follower],
     * [RaftRole.Candidate], or [RaftRole.Learner].
     *
     * Collect this flow to react to leadership changes. The initial value is
     * [RaftRole.Follower] for voters, [RaftRole.Learner] for learners.
     */
    public val role: StateFlow<RaftRole>

    /**
     * The [NodeId] of the node this node currently believes to be the leader,
     * or `null` if no leader is known (e.g. during an election).
     *
     * Useful for redirecting [propose] calls when this node is not the leader.
     */
    public val leader: StateFlow<NodeId?>

    /**
     * The index of the highest log entry known to be committed by a quorum.
     *
     * Advances monotonically. An entry at index `i` is safe to apply to a
     * state machine once `commitIndex >= i`.
     */
    public val commitIndex: StateFlow<Long>

    /**
     * A hot [Flow] of **application** log entries committed by a quorum, in index order.
     *
     * Every node in the cluster — leader and followers alike — emits the same
     * sequence of entries here. Collect this flow to drive a state machine.
     *
     * The internal §5.4.2 election no-op ([LogEntry.isNoOp]) is withheld: it advances
     * [commitIndex] but never appears here, so consumers see only application commands
     * and need not filter. Application-proposed entries always surface, including those
     * with an empty [LogEntry.command].
     *
     * Replay=0; late collectors miss entries emitted before they subscribed.
     */
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
     * Proposes [command] for replication and suspends until a quorum commits it.
     *
     * Returns the committed [LogEntry] (which carries the assigned [LogEntry.index]
     * and [LogEntry.term]). Only the leader can propose; all other roles throw
     * immediately.
     *
     * @throws NotLeaderException if this node is not currently the leader (including learners).
     * @throws LeadershipLostException if leadership is lost while waiting for commitment.
     */
    public suspend fun propose(command: ByteArray): LogEntry

    /**
     * Suspends until this node is observed in the [RaftRole.Leader] role.
     *
     * Convenience over polling `role.value`. Returns immediately if already leader.
     * Honours structured concurrency: if the surrounding scope is cancelled while
     * waiting, this throws [kotlinx.coroutines.CancellationException]. A [RaftRole.Learner]
     * never becomes leader, so calling this on a learner node suspends forever (until cancelled).
     *
     * Example:
     * ```
     * node.awaitLeadership()
     * // this node is now the leader — safe to propose
     * ```
     */
    public suspend fun awaitLeadership() {
        role.first { it is RaftRole.Leader }
    }

    /**
     * Shuts down this node, cancelling all internal coroutines.
     *
     * Idempotent. Equivalent to cancelling the owning [CoroutineScope].
     */
    public suspend fun close()
}

/**
 * Creates and starts a [RaftNode] whose lifetime is tied to this [CoroutineScope].
 *
 * This is the single construction entry point for `kuilt-raft`. Using a scope
 * extension means the node participates in structured concurrency — it is
 * cancelled automatically when the scope completes or is cancelled, and any
 * exception in the node propagates to the scope's supervisor.
 *
 * @param clusterConfig The cluster membership (voters + optional learners).
 * @param transport The messaging layer connecting this node to its peers.
 * @param storage Durable state for this node's term, vote, and log.
 * @param raftConfig Timing parameters. Defaults are suitable for LAN; adjust
 *   for high-latency or test environments.
 * @return A running [RaftNode] ready to receive proposals and emit committed entries.
 */
public fun CoroutineScope.raftNode(
    clusterConfig: ClusterConfig,
    transport: RaftTransport,
    storage: RaftStorage,
    raftConfig: RaftConfig = RaftConfig(),
): RaftNode = RaftEngine(clusterConfig, transport, storage, raftConfig, this)
