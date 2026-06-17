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
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftConfig
import us.tractat.kuilt.raft.RaftEnvelope
import us.tractat.kuilt.raft.RaftNode
import us.tractat.kuilt.raft.RaftStorage
import us.tractat.kuilt.raft.RaftTransport
import us.tractat.kuilt.raft.raftNode
import us.tractat.kuilt.session.LeaveReason
import us.tractat.kuilt.websocket.KtorRoomHost
import kotlin.time.Duration.Companion.seconds

private val log = KotlinLogging.logger {}

/**
 * Server-side cluster facade: an M-voter [VoterMesh] plus a relay accept loop that
 * admits learner clients via [KtorRoomHost].
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
    private val host: KtorRoomHost,
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
     * Run the relay accept loop. Each accepted connection is admitted as a learner.
     *
     * Suspends until the scope is cancelled or an unrecoverable accept failure occurs.
     * Invoke from a `launch` in the owning scope.
     */
    public suspend fun start() {
        host.start { room ->
            val admittedSet = try {
                withTimeout(10.seconds) { room.roster.first { it.isNotEmpty() } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log.warn(e) { "server-cluster: roster wait failed" }
                runCatchingCancellable { room.leave(LeaveReason.Normal) }
                return@start
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

            val leader = mesh.awaitLeader()
            val withLearner = ClusterConfig(
                voters = voterConfig.voters,
                learners = voterConfig.learners + learnerId,
            )
            try {
                leader.changeMembership(withLearner)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log.warn(e) { "server-cluster: changeMembership failed for $learnerId" }
            }

            // Hold the room open until the connection closes or scope cancels.
            // The finally block removes the learner route when the WebSocket closes.
            try {
                awaitCancellation()
            } finally {
                router.removeLearner(learnerId)
            }
        }
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
 * @param host The [KtorRoomHost] for accepting learner connections.
 * @param voterIds Ordered list of voter [NodeId]s. Non-empty; odd count recommended.
 * @param raftConfig Raft timing and virtual-time flags. **Required** — no default.
 * @param storageFactory Per-voter [RaftStorage] factory. Defaults to [InMemoryRaftStorage].
 */
public fun CoroutineScope.serverCluster(
    host: KtorRoomHost,
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
 * - **Inbound (learner → voters):** flows from the learner's Seam are multiplexed into
 *   each voter's [MutableSharedFlow] via relay coroutines launched on [addLearner].
 * - **Outbound (voter → learner):** [sendToLearner] routes a voter's `sendTo(learnerId, ...)`
 *   call to the learner's Seam via [Seam.broadcast].
 *
 * ## Thread safety
 *
 * All mutable state ([learnerSeams], [inboundFlows], [learnerRelayJobs]) is guarded by
 * an atomicfu reentrant lock. Suspend calls are always issued *outside* the locked section.
 *
 * [inboundFlows] is populated by [registerVoterInbound] from [buildVoterChannelMesh]
 * before any [addLearner] call — no ordering race between registration and first use.
 */
internal class LearnerRouter {

    private val lock = reentrantLock()

    /** Learner NodeId → its WebSocket Seam. */
    private val learnerSeams: MutableMap<NodeId, Seam> = mutableMapOf()

    /** Voter inbound SharedFlows — populated once at mesh construction, never mutated after. */
    private val inboundFlows: MutableList<MutableSharedFlow<RaftEnvelope>> = mutableListOf()

    /** Learner NodeId → relay coroutine Job (seam.incoming → voter inbounds). */
    private val learnerRelayJobs: MutableMap<NodeId, Job> = mutableMapOf()

    /** Peers StateFlow — tracks connected learner NodeIds; used in voter transport peers. */
    val learnersFlow: MutableStateFlow<Set<NodeId>> = MutableStateFlow(emptySet())

    /**
     * Register a voter's inbound [MutableSharedFlow] so [addLearner] can fan-in
     * learner messages to it. Called once per voter during mesh construction.
     */
    fun registerVoterInbound(flow: MutableSharedFlow<RaftEnvelope>) {
        lock.withLock { inboundFlows.add(flow) }
    }

    /**
     * Register a learner's Seam and start relay coroutines.
     *
     * Delivers learner messages (from the Seam's incoming flow) to every voter's
     * inbound [MutableSharedFlow]. Learner NodeId is added to [learnersFlow] so
     * voter transports report it in their `peers`.
     *
     * @param learnerId The learner's [NodeId] (derived from the Seam's `selfId`).
     * @param seam The learner's room Seam — a two-peer WebSocket Seam.
     * @param scope Scope for the relay coroutine. Bound to the server scope.
     */
    fun addLearner(learnerId: NodeId, seam: Seam, scope: CoroutineScope) {
        val flows = lock.withLock {
            learnerSeams[learnerId] = seam
            learnersFlow.update { it + learnerId }
            inboundFlows.toList()
        }
        val relayJob = scope.launch {
            runCatchingCancellable {
                seam.incoming.collect { swatch ->
                    val sender = swatch.sender ?: return@collect
                    val envelope = RaftEnvelope(NodeId(sender.value), swatch.payload)
                    flows.forEach { flow -> flow.tryEmit(envelope) }
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

    return voterIds.associateWith { id ->
        // Fan-in SharedFlow: receives from both the voter channel relay and learner Seam relays.
        val inbound = MutableSharedFlow<RaftEnvelope>(extraBufferCapacity = Int.MAX_VALUE)
        router.registerVoterInbound(inbound)

        // Relay the voter's own inbound channel into the SharedFlow.
        val childScope = CoroutineScope(scope.coroutineContext + Job(scope.coroutineContext[Job]))
        childScope.launch {
            voterChannels.getValue(id).receiveAsFlow().collect { inbound.emit(it) }
        }

        val transport = voterChannelTransport(id, voterChannels, voterPeersFlow, inbound, router)
        val storage = storageFactory(id)
        childScope.raftNode(clusterConfig, transport, storage, raftConfig)
    }
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
