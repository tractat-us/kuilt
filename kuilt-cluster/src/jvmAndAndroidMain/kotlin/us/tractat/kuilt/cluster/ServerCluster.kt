package us.tractat.kuilt.cluster

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withTimeout
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
 * ## Learner admission
 *
 * Each accepted WebSocket connection becomes a two-peer Room. The server:
 * 1. Waits for the room roster to show the admitted peer.
 * 2. Derives its [NodeId] from the peer identity.
 * 3. Calls [RaftNode.changeMembership] on the leader to add it as a learner.
 * 4. Holds the room alive via `awaitCancellation` until the connection closes.
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
            awaitCancellation()
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
    val voterNodes = buildVoterChannelMesh(voterIds, clusterConfig, raftConfig, storageFactory, serverScope)
    val mesh = VoterMesh(voterNodes = voterNodes, scope = serverScope)
    return ServerCluster(
        mesh = mesh,
        host = host,
        voterConfig = clusterConfig,
    )
}

// ── In-process voter channel transport ──────────────────────────────────────

/**
 * Wire [voterIds] into a K_M complete-graph mesh using [Channel]-backed [RaftTransport]s.
 *
 * Each voter gets an unlimited inbound [Channel]; `sendTo` delivers envelopes into the
 * target's channel. This mirrors [us.tractat.kuilt.raft.test.MultiNodeRaftNetwork] for
 * production in-process deployments; tests use [us.tractat.kuilt.raft.test.MultiNodeRaftNetwork]
 * directly (via [voterMeshFromNodes]) so the test scheduler drives delivery.
 */
private fun buildVoterChannelMesh(
    voterIds: List<NodeId>,
    clusterConfig: ClusterConfig,
    raftConfig: RaftConfig,
    storageFactory: (NodeId) -> RaftStorage,
    scope: CoroutineScope,
): Map<NodeId, RaftNode> {
    val channels = voterIds.associateWith { Channel<RaftEnvelope>(Channel.UNLIMITED) }
    val peersFlow = MutableStateFlow(voterIds.toSet())

    return voterIds.associateWith { id ->
        val childScope = CoroutineScope(scope.coroutineContext + Job(scope.coroutineContext[Job]))
        val transport = voterChannelTransport(id, channels, peersFlow)
        val storage = storageFactory(id)
        childScope.raftNode(clusterConfig, transport, storage, raftConfig)
    }
}

private fun voterChannelTransport(
    id: NodeId,
    channels: Map<NodeId, Channel<RaftEnvelope>>,
    peersState: MutableStateFlow<Set<NodeId>>,
): RaftTransport = object : RaftTransport {
    override val selfId: NodeId = id
    override val peers: StateFlow<Set<NodeId>> = peersState.asStateFlow()
    override val incoming: kotlinx.coroutines.flow.Flow<RaftEnvelope> =
        channels.getValue(id).receiveAsFlow()
    override val maxPayloadBytes: Int? = null
    override suspend fun sendTo(peer: NodeId, message: ByteArray) {
        channels[peer]?.send(RaftEnvelope(id, message))
    }
}
