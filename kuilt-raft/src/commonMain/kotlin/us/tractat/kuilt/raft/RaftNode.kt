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
 * 5. **Propose** from **any** node by calling [RaftNode.propose] — non-leaders forward
 *    the command to the current leader (Raft §8); the call suspends until a quorum
 *    commits the entry and returns the committed [LogEntry].
 * 6. **Apply** by collecting [RaftNode.committed] on every node — committed
 *    instructions appear here in index order as [Committed] values.
 * 8. **(Optional) Compact** by publishing a state-machine snapshot into
 *    [RaftNode.snapshots]; raft discards the covered log prefix and catches lagging
 *    peers up with a [Committed.Install] reset.
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
 * // Apply the committed stream (runs on every node in the cluster)
 * scope.launch {
 *     var appliedIndex = 0L
 *     node.committed.collect { committed ->
 *         when (committed) {
 *             is Committed.Entry -> { applyToStateMachine(committed.entry.command); appliedIndex = committed.entry.index }
 *             is Committed.Install -> { resetStateMachineTo(committed.snapshot.state); appliedIndex = committed.snapshot.throughIndex }
 *         }
 *         // Optionally publish a snapshot so raft can compact the log prefix:
 *         // node.snapshots.value = Snapshot(appliedIndex, serializeStateMachine())
 *     }
 * }
 *
 * // Propose from any node — the leader appends directly; followers/candidates/learners forward (Raft §8)
 * scope.launch {
 *     try {
 *         val committed = node.propose("set x=1".encodeToByteArray())
 *         println("committed at index ${committed.index}")
 *     } catch (e: LeadershipLostException) {
 *         // leader stepped down mid-flight — outcome unknown, retry with idempotent key
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
 * on a learner node forwards the command to the current leader (Raft §8) like any
 * non-leader; the committed entry replicates back through normal AppendEntries.
 *
 * @see RaftNode for the runtime interface
 * @see CoroutineScope.raftNode for the construction entry point
 */
package us.tractat.kuilt.raft

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import us.tractat.kuilt.core.checkNotUnderTestDispatcher
import us.tractat.kuilt.raft.internal.RaftEngine

/**
 * The runtime interface for a single Raft cluster member.
 *
 * Obtain an instance via [CoroutineScope.raftNode]. The node starts running
 * immediately and remains active until [close] is called or the owning
 * [CoroutineScope] is cancelled.
 *
 * **Testing multi-node clusters?** Use `MultiNodeRaftSim` from `:kuilt-raft-test` — it handles
 * the ceremony (in-process network, per-node seeded [RaftConfig.random] for symmetry-breaking,
 * child scopes on `backgroundScope`, bounded await helpers) so you don't hand-roll it per test.
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
     * Informational; [propose] automatically forwards to the leader — callers
     * do not need to redirect manually.
     */
    public val leader: StateFlow<NodeId?>

    /**
     * The effective cluster membership as of the latest committed config entry.
     *
     * Reflects the [ClusterConfig] the node is currently operating under.
     * During a joint-consensus transition (§6) this is the target C_new; once
     * C_new is committed the transition is complete and this reflects C_new.
     *
     * Collect this flow to track membership changes — for example, to feed a
     * [us.tractat.kuilt.warp.WarpNode] with a strongly-consistent roster via
     * `raftNode.rosterSnapshot()`.
     *
     * The initial value is the bootstrap [ClusterConfig] passed to [CoroutineScope.raftNode].
     */
    public val membership: StateFlow<ClusterConfig>

    /**
     * The index of the highest log entry known to be committed by a quorum.
     *
     * Advances monotonically. An entry at index `i` is safe to apply to a
     * state machine once `commitIndex >= i`.
     */
    public val commitIndex: StateFlow<Long>

    /**
     * A hot [Flow] of committed instructions in index order, delivered as [Committed] values.
     *
     * Every node in the cluster — leader and followers alike — emits the same sequence here.
     * Collect this flow to drive a state machine: apply [Committed.Entry] entries and reset
     * to [Committed.Install] snapshots as they arrive.
     *
     * The internal §5.4.2 election no-op ([LogEntry.isNoOp]) is withheld: it advances
     * [commitIndex] but never appears here, so consumers see only application commands
     * and need not filter. Application-proposed entries always surface, including those
     * with an empty [LogEntry.command].
     *
     * Replay=0; late collectors miss entries emitted before they subscribed.
     * To resume without gaps after subscribing late (e.g. on crash recovery),
     * use [committedFrom].
     */
    public val committed: Flow<Committed>

    /**
     * A cold [Flow] that **replays** already-committed [Committed] values from
     * [fromIndex] (inclusive), then transparently tails the live [committed] stream —
     * with no gap or duplicate at the seam.
     *
     * Unlike [committed] (replay=0), this lets a late subscriber catch up: a state
     * machine that has applied up to index `i` resumes with `committedFrom(i + 1)`
     * and sees every subsequent instruction exactly once, in index order. The internal
     * §5.4.2 no-op ([LogEntry.isNoOp]) is withheld here too.
     *
     * If [fromIndex] falls at or below [compactionFloor], a [Committed.Install] is
     * emitted first so the consumer can reset its state machine before replaying entries
     * above the floor.
     *
     * Each collection is independent and snapshots the committed log on subscription,
     * so collecting is more expensive than [committed]; prefer [committed] when you
     * subscribe before the first proposal and only need the live tail.
     *
     * @param fromIndex the first log index to replay (1-based). `0` or `1` replays
     *   from the start of the retained log.
     */
    public fun committedFrom(fromIndex: Long): Flow<Committed>

    /**
     * The outbound snapshot channel. The consumer publishes its latest durable state-machine snapshot
     * here on its own clock (`snapshots.value = Snapshot(appliedIndex, bytes)`); raft samples it on its
     * own clock to compact the log prefix and to serve a lagging follower. Conflated — only the newest
     * snapshot matters. Leaving it `null` disables compaction (the pre-compaction behavior).
     */
    public val snapshots: MutableStateFlow<Snapshot?>

    /** The `lastIncludedIndex` of the most recent compaction, or `0` if nothing has been compacted. */
    public val compactionFloor: StateFlow<Long>

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
     * and [LogEntry.term]).
     *
     * May be called from **any** node in the cluster (Raft §8):
     * - The **leader** appends the entry directly.
     * - Every other role (**Follower**, **Candidate**, **Learner**) forwards the command
     *   to the current leader and suspends until the leader commits it. The committed
     *   entry then replicates back to the calling node through the normal AppendEntries path.
     * - If no leader is known yet (during an election), the call waits, cancellably, until
     *   a leader is elected and then forwards.
     *
     * **Cancellation is best-effort.** A forward that is still queued when the caller cancels
     * is guaranteed not to be sent. However, a command already forwarded to — or appended by —
     * the leader may still commit even if the caller cancels; Raft does not support revocation
     * of in-flight proposals.
     *
     * @throws LeadershipLostException if a forwarded proposal is rejected by the target leader
     *   (e.g. the leader stepped down); the caller may retry on the new leader.
     * @throws NotLeaderException only in terminal cases: the node is [close]d, or the leader
     *   is rejecting proposals because a leadership transfer is in flight.
     */
    public suspend fun propose(command: ByteArray): LogEntry

    /**
     * Proposes [command] with a caller-pinned [requestId] (Raft §8 client serial) under this node's
     * `clientId`, then suspends until a quorum commits it (same semantics as [propose]).
     *
     * A **durable** client replays the *same* [requestId] on a post-crash retry to get exactly-once
     * across the crash: the proposer stamps `DedupKey(clientId, requestId)` onto the entry, it rides
     * the forward hop unchanged, and the consumer's dedup table ([ClientSessionTable]) skips a serial
     * it has already applied. The auto-serial [propose] form draws the next monotonic serial itself.
     */
    public suspend fun propose(command: ByteArray, requestId: Long): LogEntry

    /**
     * Confirms this leader still holds a voter-quorum at its current term, then returns a
     * **read index**: a commit index `ri` such that any state machine that has applied through
     * `ri` reflects every write committed before this call. The read is linearizable once the
     * caller's apply loop reaches `ri`. The leader does **not** write to the log.
     *
     * Concurrent calls in the same heartbeat window share one round. A single-voter cluster
     * returns immediately. A freshly-elected leader suspends until its current-term no-op
     * commits before returning. Because the state machine is external (driven by [committed]),
     * the caller must wait until it has applied through the returned index — see [awaitRead].
     *
     * @throws NotLeaderException if this node is not the leader (including learners).
     * @throws LeadershipLostException if leadership is lost before the round confirms.
     */
    public suspend fun readIndex(): Long {
        throw NotLeaderException("readIndex: not the current leader")
    }

    /**
     * Requests a cluster membership change to [target], suspending until the
     * change commits as C_new, then returning [target].
     *
     * Only the leader may call this. On non-leaders the default implementation
     * throws [NotLeaderException] immediately, so [FakeRaftNode] in
     * `:kuilt-raft-test` requires no change — it inherits this default.
     *
     * ## Two change classes
     *
     * If [target].voters equals the current voter set (a **learner-set-only** change),
     * a single `Simple(target)` config entry is appended — quorum is unchanged.
     * If the voter set differs (a **voter-set change**), the transition goes through
     * joint consensus: a `Joint(C_old, C_new)` entry is appended first (both old and
     * new majorities are required for commit and election during this phase), and once
     * it commits a final `Simple(C_new)` entry is appended. The call suspends until that
     * `Simple(C_new)` commits. If this leader is not a voter in C_new, it steps down once
     * C_new is durable.
     *
     * ## Failure modes
     *
     * - [NotLeaderException] — this node is not the leader.
     * - [MembershipChangeInProgressException] — a config entry is already uncommitted;
     *   only one change may be in flight at a time.
     * - [IllegalArgumentException] — [target].voters is empty.
     * - [LeadershipLostException] — leadership was lost mid-transition; the change may
     *   or may not have committed on some nodes.
     */
    public suspend fun changeMembership(target: ClusterConfig): ClusterConfig {
        throw NotLeaderException("changeMembership: not the current leader")
    }

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
     * Initiates a graceful leadership transfer to [target] per Raft §3.10, and suspends until
     * the transfer either completes (target wins an election) or fails.
     *
     * **Protocol**:
     * 1. The leader stops accepting new [propose] calls (they receive [NotLeaderException]).
     * 2. The leader sends AppendEntries to bring [target]'s log up to the current [commitIndex].
     * 3. The leader sends a `TimeoutNow` message to [target].
     * 4. [target] immediately starts a real election (bypassing its election-timeout wait).
     * 5. If [target] wins, the old leader steps down naturally on seeing the higher term.
     *    This call returns normally.
     * 6. If [target] does not win within one election-timeout window, the auto-timeout fires:
     *    the old leader resumes accepting proposals and this call throws [LeadershipTransferException].
     *
     * **Cancellation**: call [cancelTransfer] from a separate coroutine to abort early.
     * The [LeadershipTransferException] will carry `"cancelled"` in its message.
     *
     * **Constraints**:
     * - Only the leader may call this. Non-leaders throw [NotLeaderException] immediately.
     * - [target] must be a voter in the current cluster config, and must not be `this` node's own id.
     *   Invalid targets throw [IllegalArgumentException] immediately.
     * - While a transfer is in flight, [propose] throws [NotLeaderException].
     *
     * @param target The [NodeId] of the voter to transfer leadership to.
     * @throws NotLeaderException if this node is not currently the leader.
     * @throws IllegalArgumentException if [target] is not a known voter, or is this node's own id.
     * @throws LeadershipTransferException if the transfer timed out or was cancelled.
     */
    public suspend fun transferLeadership(target: NodeId): Unit =
        throw NotLeaderException("transferLeadership: not the current leader")

    /**
     * Aborts an in-flight [transferLeadership] and re-enables proposal acceptance.
     *
     * If no transfer is in flight, this is a no-op. The suspended [transferLeadership] call
     * will throw [LeadershipTransferException] with reason "cancelled".
     */
    public fun cancelTransfer(): Unit = Unit

    /**
     * Shuts down this node, cancelling all internal coroutines.
     *
     * Idempotent. Equivalent to cancelling the owning [CoroutineScope].
     */
    public suspend fun close()
}

/**
 * Linearizable read barrier: confirms the read index via [RaftNode.readIndex], then suspends
 * until [applied] reaches it, returning that index. [applied] is the caller's own monotonic
 * applied-index flow, advanced as it consumes [RaftNode.committed].
 *
 * @throws NotLeaderException if not the leader.
 * @throws LeadershipLostException if leadership is lost before the round confirms.
 */
public suspend fun RaftNode.awaitRead(applied: StateFlow<Long>): Long {
    val ri = readIndex()
    applied.first { it >= ri }
    return ri
}

/**
 * Creates and starts a [RaftNode] whose lifetime is tied to this [CoroutineScope].
 *
 * This is the single construction entry point for `kuilt-raft`. Using a scope
 * extension means the node participates in structured concurrency — it is
 * cancelled automatically when the scope completes or is cancelled, and any
 * exception in the node propagates to the scope's supervisor.
 *
 * **Test-dispatcher guard.** If the scope contains a `kotlinx.coroutines.test.TestDispatcher`,
 * a diagnostic is emitted because real `RaftNode` uses real-clock [kotlinx.coroutines.delay]
 * for elections — under virtual time those delays never advance automatically and the test will
 * deadlock silently for the full `runTest` timeout. Use `FakeRaftNode` from `:kuilt-raft-test`
 * for unit tests instead. Set [RaftConfig.strictTestGuard] to `true` to throw rather than warn.
 *
 * @param clusterConfig The cluster membership (voters + optional learners).
 * @param transport The messaging layer connecting this node to its peers.
 * @param storage Durable state for this node's term, vote, and log.
 * @param raftConfig Timing parameters. Defaults are suitable for LAN; adjust
 *   for high-latency or test environments.
 * @param identity How this node obtains its Raft §8 dedup [ClientId]. [ClientIdentity.Auto] (default)
 *   mints `ClientId.auto(thisNodeId, raftConfig.random)` — a per-incarnation id giving at-least-once
 *   forwarding without cross-crash dedup, re-minted on collision. Pass
 *   [ClientIdentity.Durable] with a **stable** [ClientId] the caller persists itself for exactly-once
 *   across process restarts (replay the same `requestId` on retry). See [ClientSessionTable].
 * @param onMetric Optional callback invoked on the engine's coroutine at each [RaftMetric]
 *   transition. Use to route metrics to Prometheus, StatsD, OpenTelemetry, or a test
 *   assertion. **Must not block** — the callback runs synchronously on the engine actor;
 *   blocking stalls replication for the entire cluster. `null` (default) disables the hook.
 * @return A running [RaftNode] ready to receive proposals and emit committed entries.
 */
public fun CoroutineScope.raftNode(
    clusterConfig: ClusterConfig,
    transport: RaftTransport,
    storage: RaftStorage,
    raftConfig: RaftConfig = RaftConfig(),
    identity: ClientIdentity = ClientIdentity.Auto,
    onMetric: ((RaftMetric) -> Unit)? = null,
): RaftNode {
    checkNotUnderTestDispatcher(
        scope = this,
        typeName = "RaftNode",
        substitute = "FakeRaftNode from :kuilt-raft-test",
        strict = raftConfig.strictTestGuard,
        expectVirtualTime = raftConfig.expectVirtualTime,
    )
    return RaftEngine(clusterConfig, transport, storage, raftConfig, this, onMetric, identity)
}
