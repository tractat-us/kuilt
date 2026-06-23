@file:OptIn(kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi::class)

package us.tractat.kuilt.cluster

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.raft.ClusterConfig
import us.tractat.kuilt.raft.Committed
import us.tractat.kuilt.raft.InMemoryRaftStorage
import us.tractat.kuilt.raft.MembershipChangeInProgressException
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftConfig
import us.tractat.kuilt.raft.RaftEnvelope
import us.tractat.kuilt.raft.RaftNode
import us.tractat.kuilt.raft.RaftRole
import us.tractat.kuilt.raft.RaftStorage
import us.tractat.kuilt.raft.RaftTransport
import us.tractat.kuilt.raft.raftNode
import us.tractat.kuilt.session.LeaveReason
import us.tractat.kuilt.session.Room
import us.tractat.kuilt.session.RoomHost
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val log = KotlinLogging.logger {}

/**
 * Server-side cluster facade: an M-voter [VoterMesh] plus a relay accept loop that
 * admits learner clients via [RoomHost].
 *
 * ## Construction
 *
 * Use [CoroutineScope.serverCluster] — wires M voters over an in-process channel
 * network and mounts a relay accept loop via [host].
 *
 * ## Voter mesh
 *
 * M voters communicate via in-process [RaftTransport] channels (K_M complete graph).
 * Each voter runs in a child scope of the injected [CoroutineScope]. Under virtual time
 * the same topology is wired by [us.tractat.kuilt.raft.test.MultiNodeRaftSim] and proven
 * via [us.tractat.kuilt.cluster.VoterMesh] in commonTest.
 *
 * ## Learner admission and message routing
 *
 * Each accepted WebSocket connection becomes a two-peer Room. The server:
 * 1. Waits for the room roster to show the admitted peer.
 * 2. Derives its [NodeId] from the peer identity — matching what the client's
 *    [SeamRaftTransport][us.tractat.kuilt.raft.SeamRaftTransport] reports as its `selfId`.
 * 3. Registers the room Seam in the [LearnerRouter]: relay coroutines bridge the Seam's
 *    incoming to each voter's inbound flow, and voter sends to the learner NodeId are
 *    routed back through the Seam.
 * 4. Calls [RaftNode.changeMembership] on the leader to add the learner.
 * 5. Holds the room alive via `awaitCancellation` until the connection closes.
 *
 * The [LearnerRouter] is the key integration primitive for S3b-3: it makes the voter
 * channel transports aware of dynamically-connected learner Seams without rebuilding
 * the voter nodes.
 *
 * ## Lifecycle
 *
 * - Voter nodes start on construction.
 * - Call [start] (in a launched coroutine) to run the relay accept loop.
 * - [awaitLeader] delegates to [VoterMesh.awaitLeader].
 * - [close] cancels the server scope.
 *
 * @see CoroutineScope.serverCluster for the construction entry point.
 */
public class ServerCluster internal constructor(
    /** The underlying voter mesh — exposes [VoterMesh.voterNodes] and [VoterMesh.awaitLeader]. */
    public val mesh: VoterMesh,
    private val host: RoomHost,
    private val voterConfig: ClusterConfig,
    private val router: LearnerRouter,
    private val serverScope: CoroutineScope,
) {
    /** The live voter node map — delegates to [mesh]. */
    public val voterNodes: Map<NodeId, RaftNode> get() = mesh.voterNodes

    /**
     * The committed log stream from the first voter.
     *
     * For multi-consumer scenarios collect directly from [voterNodes].
     */
    public val committed: Flow<Committed> get() = mesh.committed

    /**
     * Run the primary relay accept loop ([host]). Each accepted connection is admitted
     * as a learner. Convenience for the single-relay deployment; equivalent to
     * `runRelay(host)`.
     *
     * Suspends until the scope is cancelled or an unrecoverable accept failure occurs.
     * Invoke from a `launch` in the owning scope.
     */
    public suspend fun start(): Unit = runRelay(host)

    /**
     * Run the relay accept loop for [relayHost], admitting each accepted connection as a
     * learner into this cluster's **shared** voter mesh and [LearnerRouter].
     *
     * Multiple relay hosts can front one voter cluster: mount each by launching
     * `runRelay(host)` in its own coroutine. Because the accept loop's lifecycle is owned
     * by the launching scope (see [RoomHost.start]), cancelling that coroutine stops
     * **just that relay endpoint** — its rooms tear (so connected learners reconnect to
     * another endpoint) while the voter mesh and sibling relays keep running. This is the
     * server-side half of cross-relay failover (#544): a learner re-admitted on a surviving
     * relay keeps the same [NodeId] and resumes proposing against the same Raft log.
     *
     * @param relayHost The [RoomHost] whose accepted connections become learners.
     */
    public suspend fun runRelay(relayHost: RoomHost) {
        relayHost.start { room -> admitLearner(room) }
    }

    /**
     * Admit one accepted [room] connection as a learner: wait for the roster, derive the
     * learner [NodeId], register its Seam in the shared [LearnerRouter], add it to cluster
     * membership via the leader, then hold the room open until the connection closes or the
     * relay scope cancels. The `finally` deregisters the learner route on disconnect.
     */
    private suspend fun admitLearner(room: Room) {
        val admittedSet = try {
            withTimeout(10.seconds) { room.roster.first { it.isNotEmpty() } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log.warn(e) { "server-cluster: roster wait failed" }
            runCatchingCancellable { room.leave(LeaveReason.Normal) }
            return
        }

        val admittedPeer = admittedSet.first()
        val learnerId = NodeId(admittedPeer.id.value)
        log.info { "server-cluster: admitting learner $learnerId" }

        // Register the room Seam so voter transports can route Raft messages
        // to and from the learner over the WebSocket. Must happen before
        // changeMembership so the leader can start sending AppendEntries
        // as soon as the config change is applied.
        val seamChannel = room.channel("raft")
        router.addLearner(learnerId, seamChannel, serverScope)

        val withLearner = ClusterConfig(
            voters = voterConfig.voters,
            learners = voterConfig.learners + learnerId,
        )
        changeMembershipWithRetry(learnerId, withLearner)

        // Hold the room open until the connection closes or scope cancels.
        // The finally block removes the learner route when the WebSocket closes.
        try {
            awaitCancellation()
        } finally {
            router.removeLearner(learnerId)
        }
    }

    /**
     * Call [RaftNode.changeMembership] with bounded retry on
     * [MembershipChangeInProgressException].
     *
     * Raft serializes membership changes — only one may be in flight at a time. When two
     * clients connect concurrently, the second [admitLearner] hits this exception because
     * the first client's config change is still uncommitted. Rather than swallowing it
     * (which leaves the learner registered in [LearnerRouter] but permanently absent from
     * cluster membership), we wait a short interval and retry. Once the in-flight change
     * commits, the next attempt succeeds.
     *
     * A bounded retry count (not an unbounded loop) ensures a genuinely stuck cluster
     * (e.g. no leader or a leader that keeps losing leadership) gives up rather than
     * looping forever. Any other exception is logged and returned to the caller.
     */
    private suspend fun changeMembershipWithRetry(learnerId: NodeId, config: ClusterConfig) {
        val maxAttempts = 20
        val retryDelay = 200.milliseconds
        repeat(maxAttempts) { attempt ->
            val leader = mesh.awaitLeader()
            try {
                leader.changeMembership(config)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: MembershipChangeInProgressException) {
                log.debug { "server-cluster: changeMembership in progress for $learnerId, attempt ${attempt + 1}/$maxAttempts — retrying" }
                delay(retryDelay)
            } catch (e: Throwable) {
                log.warn(e) { "server-cluster: changeMembership failed for $learnerId" }
                return
            }
        }
        log.warn { "server-cluster: changeMembership gave up after $maxAttempts attempts for $learnerId" }
    }

    /** Suspend until a voter is elected leader. Delegates to [VoterMesh.awaitLeader]. */
    public suspend fun awaitLeader(): RaftNode = mesh.awaitLeader()

    /** Cancel the server scope — stops all voter nodes and the relay accept loop. */
    public fun close() {
        mesh.close()
    }
}

// ── Production constructor ───────────────────────────────────────────────────

/**
 * Construct a [ServerCluster] tied to this [CoroutineScope].
 *
 * Wires M voters over an in-process channel network, then mounts a relay accept loop
 * via [host]. The scope is the dispatcher injection point — no real-clock default is used.
 *
 * Voter nodes start immediately. Call [ServerCluster.awaitLeader] before accepting clients,
 * then call [ServerCluster.start] (in a `launch`) to run the relay accept loop.
 *
 * @param host The [RoomHost] for accepting learner connections.
 * @param voterIds Ordered list of voter [NodeId]s. Non-empty; odd count recommended.
 * @param raftConfig Raft timing and virtual-time flags. **Required** — no default.
 * @param storageFactory Per-voter [RaftStorage] factory. Defaults to [InMemoryRaftStorage].
 */
public fun CoroutineScope.serverCluster(
    host: RoomHost,
    voterIds: List<NodeId>,
    raftConfig: RaftConfig,
    storageFactory: (NodeId) -> RaftStorage = { InMemoryRaftStorage() },
): ServerCluster {
    require(voterIds.isNotEmpty()) { "voterIds must be non-empty" }
    val clusterConfig = ClusterConfig(voters = voterIds.toSet())
    val serverScope = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
    val router = LearnerRouter()
    val voterNodes = buildVoterChannelMesh(voterIds, clusterConfig, raftConfig, storageFactory, serverScope, router)
    val mesh = VoterMesh(voterNodes = voterNodes, scope = serverScope)
    return ServerCluster(
        mesh = mesh,
        host = host,
        voterConfig = clusterConfig,
        router = router,
        serverScope = serverScope,
    )
}

// ── Learner router ───────────────────────────────────────────────────────────

/**
 * Routes Raft messages between in-process voter nodes and learner clients connected
 * via WebSocket [Seam]s.
 *
 * Maintains two directions per learner:
 * - **Inbound (learner → voters):** flows from the learner's Seam are routed to the
 *   **current leader's** inbound [MutableSharedFlow] only. A learner never legitimately
 *   addresses a follower (all messages are Forward/AppendEntriesResponse/InstallSnapshotResponse
 *   directed at the leader). Fanning to all voters causes followers to reply `NotLeader`
 *   inline, racing the leader's committed reply — a deterministic M>1 bug.
 * - **Outbound (voter → learner):** [sendToLearner] routes a voter's `sendTo(learnerId, ...)`
 *   call to the learner's Seam via [Seam.broadcast].
 *
 * ## Thread safety
 *
 * All mutable state ([learnerSeams], [inboundByVoter], [learnerRelayJobs]) is guarded by
 * an atomicfu reentrant lock. Suspend calls are always issued *outside* the locked section.
 * [RaftNode.role] reads and [MutableSharedFlow.tryEmit] calls are non-suspending and can
 * run outside the lock after snapshot acquisition.
 *
 * [inboundByVoter] and [voterNodes] are populated by [registerVoterInbound] /
 * [bindVoterNodes] from [buildVoterChannelMesh] before any [addLearner] call —
 * no ordering race between registration and first use.
 */
internal class LearnerRouter {

    private val lock = reentrantLock()

    /** Learner NodeId → its WebSocket Seam. */
    private val learnerSeams: MutableMap<NodeId, Seam> = mutableMapOf()

    /** Voter NodeId → that voter's inbound SharedFlow — populated once at mesh construction. */
    private val inboundByVoter: MutableMap<NodeId, MutableSharedFlow<RaftEnvelope>> = mutableMapOf()

    /** Voter NodeId → RaftNode — bound after node construction so we can read [RaftNode.role]. */
    private var voterNodes: Map<NodeId, RaftNode> = emptyMap()

    /** Learner NodeId → relay coroutine Job (seam.incoming → leader voter inbound). */
    private val learnerRelayJobs: MutableMap<NodeId, Job> = mutableMapOf()

    /** Peers StateFlow — tracks connected learner NodeIds; used in voter transport peers. */
    val learnersFlow: MutableStateFlow<Set<NodeId>> = MutableStateFlow(emptySet())

    /**
     * Register a voter's inbound [MutableSharedFlow] keyed by [voterId].
     *
     * Called once per voter during mesh construction, before any [addLearner] call.
     */
    fun registerVoterInbound(voterId: NodeId, flow: MutableSharedFlow<RaftEnvelope>) {
        lock.withLock { inboundByVoter[voterId] = flow }
    }

    /**
     * Bind the voter node map so the relay can read [RaftNode.role] to find the current leader.
     *
     * Called once after all voter nodes are constructed, before [addLearner].
     */
    fun bindVoterNodes(nodes: Map<NodeId, RaftNode>) {
        lock.withLock { voterNodes = nodes }
    }

    /**
     * Register a learner's Seam and start a relay coroutine.
     *
     * Each inbound envelope from the learner is delivered to the **current leader voter's**
     * inbound [MutableSharedFlow] only. If no leader is currently known, the envelope is
     * dropped — the learner's propose will retry after receiving [LeadershipLostException],
     * which is acceptable. Falling back to fan-all would reintroduce the M>1 bug.
     *
     * Learner NodeId is added to [learnersFlow] so voter transports report it in their `peers`.
     *
     * @param learnerId The learner's [NodeId] (derived from the Seam's `selfId`).
     * @param seam The learner's room Seam — a two-peer WebSocket Seam.
     * @param scope Scope for the relay coroutine. Bound to the server scope.
     */
    fun addLearner(learnerId: NodeId, seam: Seam, scope: CoroutineScope) {
        val (inboundSnapshot, nodesSnapshot) = lock.withLock {
            learnerSeams[learnerId] = seam
            learnersFlow.update { it + learnerId }
            inboundByVoter.toMap() to voterNodes
        }
        val relayJob = scope.launch {
            runCatchingCancellable {
                seam.incoming.collect { swatch ->
                    val sender = swatch.sender ?: return@collect
                    val envelope = RaftEnvelope(NodeId(sender.value), swatch.toByteArray())
                    val leaderId = nodesSnapshot.entries
                        .firstOrNull { it.value.role.value is RaftRole.Leader }
                        ?.key
                    if (leaderId != null) {
                        inboundSnapshot[leaderId]?.tryEmit(envelope)
                    } else {
                        log.debug { "server-cluster: no leader — dropping learner envelope from ${envelope.from}" }
                    }
                }
            }
        }
        lock.withLock { learnerRelayJobs[learnerId] = relayJob }
    }

    /**
     * Deregister a learner and cancel its relay coroutine.
     *
     * Called from the `finally` block of the [ServerCluster.start] `onRoom` lambda
     * when the WebSocket closes or the scope is cancelled.
     */
    fun removeLearner(learnerId: NodeId) {
        val job = lock.withLock {
            learnerSeams.remove(learnerId)
            learnersFlow.update { it - learnerId }
            learnerRelayJobs.remove(learnerId)
        }
        job?.cancel()
    }

    /**
     * Route a voter's `sendTo(learnerId, message)` to the learner's Seam.
     *
     * Returns silently if the learner is not registered (e.g. tore before the send arrived).
     */
    suspend fun sendToLearner(learnerId: NodeId, message: ByteArray) {
        val seam = lock.withLock { learnerSeams[learnerId] } ?: return
        runCatchingCancellable { seam.broadcast(message) }
            .onFailure { log.debug { "server-cluster: drop to learner $learnerId on tear" } }
    }
}

// ── In-process voter channel transport ──────────────────────────────────────

/**
 * Wire [voterIds] into a K_M complete-graph mesh using [Channel]-backed [RaftTransport]s,
 * extended with learner routing via [LearnerRouter].
 *
 * Each voter gets:
 * - An inbound [MutableSharedFlow] that fans in from both voter channels and learner Seams.
 * - An inbound [Channel] for voter-to-voter messages.
 * - Access to the [LearnerRouter] for voter-to-learner sends.
 *
 * This mirrors [us.tractat.kuilt.raft.test.MultiNodeRaftNetwork] for production in-process
 * deployments; tests use [us.tractat.kuilt.raft.test.MultiNodeRaftNetwork] directly
 * (via [voterMeshFromNodes]) so the test scheduler drives delivery.
 */
private fun buildVoterChannelMesh(
    voterIds: List<NodeId>,
    clusterConfig: ClusterConfig,
    raftConfig: RaftConfig,
    storageFactory: (NodeId) -> RaftStorage,
    scope: CoroutineScope,
    router: LearnerRouter,
): Map<NodeId, RaftNode> {
    val voterChannels = voterIds.associateWith { Channel<RaftEnvelope>(Channel.UNLIMITED) }
    val voterPeersFlow = MutableStateFlow(voterIds.toSet())

    val voterNodes = voterIds.associateWith { id ->
        // Fan-in SharedFlow: receives from both the voter channel relay and learner Seam relays.
        val inbound = MutableSharedFlow<RaftEnvelope>(extraBufferCapacity = Int.MAX_VALUE)
        router.registerVoterInbound(id, inbound)

        // Relay the voter's own inbound channel into the SharedFlow.
        val childScope = CoroutineScope(scope.coroutineContext + Job(scope.coroutineContext[Job]))
        childScope.launch {
            voterChannels.getValue(id).receiveAsFlow().collect { inbound.emit(it) }
        }

        val transport = voterChannelTransport(id, voterChannels, voterPeersFlow, inbound, router)
        val storage = storageFactory(id)
        childScope.raftNode(clusterConfig, transport, storage, raftConfig)
    }
    // Bind voter nodes so the router can read roles to route learner messages to the leader.
    router.bindVoterNodes(voterNodes)
    return voterNodes
}

private fun voterChannelTransport(
    id: NodeId,
    voterChannels: Map<NodeId, Channel<RaftEnvelope>>,
    voterPeersState: MutableStateFlow<Set<NodeId>>,
    inbound: MutableSharedFlow<RaftEnvelope>,
    router: LearnerRouter,
): RaftTransport = object : RaftTransport {

    override val selfId: NodeId = id

    /** Combined voter + learner peer set, excluding this voter's own id. */
    override val peers: StateFlow<Set<NodeId>> = object : StateFlow<Set<NodeId>> {
        override val value: Set<NodeId>
            get() = voterPeersState.value - id + router.learnersFlow.value

        override val replayCache: List<Set<NodeId>> get() = listOf(value)

        // Emit whenever either sub-flow changes. We never return here because
        // collect on an infinite flow never returns normally; the suppress marks that
        // the Nothing requirement is satisfied by the fact that the collect block is
        // an infinite loop (no normal exit path exists).
        override suspend fun collect(collector: FlowCollector<Set<NodeId>>): Nothing {
            combine(voterPeersState, router.learnersFlow) { voters, learners ->
                voters - id + learners
            }.collect(collector)
            // Unreachable: an infinite flow's collect never returns normally.
            error("unreachable")
        }
    }

    override val incoming: Flow<RaftEnvelope> = inbound

    override val maxPayloadBytes: Int? = null

    override suspend fun sendTo(peer: NodeId, message: ByteArray) {
        val voterChannel = voterChannels[peer]
        if (voterChannel != null) {
            voterChannel.send(RaftEnvelope(id, message))
        } else {
            router.sendToLearner(peer, message)
        }
    }
}
