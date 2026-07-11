package us.tractat.kuilt.cluster

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
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
import us.tractat.kuilt.raft.changeMembershipWithRetry
import us.tractat.kuilt.raft.raftNode
import us.tractat.kuilt.session.LeaveReason
import us.tractat.kuilt.session.Room
import us.tractat.kuilt.session.RoomHost
import kotlin.time.Duration.Companion.seconds

private val log = KotlinLogging.logger("us.tractat.kuilt.cluster.ServerCluster")

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
 * 3. Registers the room Seam as a spoke in the [RaftRelayHub]: the hub is the sole
 *    collector of the spoke's raft channel, decodes each `RaftRelay`, and routes it **by
 *    `dest`** to the addressed voter's inbound with the true origin preserved; voter sends
 *    to the learner are wrapped (origin = the true voter) back down the spoke.
 * 4. Calls [RaftNode.changeMembership] on the leader to add the learner.
 * 5. Holds the room alive via `awaitCancellation` until the connection closes.
 *
 * The [RaftRelayHub] is the key integration primitive for S3b-3: it makes the voter
 * channel transports aware of dynamically-connected learner Seams without rebuilding
 * the voter nodes, and keeps the Raft `from` intact end-to-end in both directions.
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
    private val hub: RaftRelayHub,
    /**
     * The two-tier overlay this cluster admits every connection into.
     *
     * Each accepted connection is published here via [OverlayServer.admit] (the client's
     * attachment `client → self` plus its local unicast spoke) and retracted via
     * [OverlayServer.evict] on disconnect — so the attachment directory is populated
     * structurally, not by a hand-rolled roster publish. A federation hands
     * `cluster.overlay::lookup` to its game bootstrap so the leader can pick the core
     * hop for a far player.
     */
    public val overlay: OverlayServer,
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
     * learner into this cluster's **shared** voter mesh and [RaftRelayHub].
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
     * learner [NodeId], register its Seam as a spoke in the shared [RaftRelayHub], add it to
     * cluster membership via the leader, then hold the room open until the connection closes
     * or the relay scope cancels. The `finally` deregisters the spoke on disconnect.
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

        // Register the room Seam as a spoke so voter transports can route Raft messages
        // to and from the learner over the WebSocket. Must happen before
        // changeMembership so the leader can start sending AppendEntries
        // as soon as the config change is applied.
        val seamChannel = room.channel("raft")
        hub.addSpoke(learnerId, seamChannel, serverScope)

        // Discharge the overlay obligation structurally: publish this connection's
        // attachment (`client → self`) and register its app-unicast spoke — a channel
        // DISTINCT from the "raft" leg above. Register-before-membership (same ordering
        // the router follows) so a frame racing in across the core finds the spoke ready.
        overlay.admit(admittedPeer.id, room.channel(OVERLAY_UNICAST_CHANNEL))

        val withLearner = ClusterConfig(
            voters = voterConfig.voters,
            learners = voterConfig.learners + learnerId,
        )
        try {
            mesh.awaitLeader().changeMembershipWithRetry(withLearner)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log.warn(e) { "server-cluster: learner $learnerId admit failed — removing spoke" }
            hub.removeSpoke(learnerId, seamChannel)
            overlay.evict(admittedPeer.id)
            return
        }

        // Hold the room open until the connection closes or scope cancels.
        // The finally block removes the learner spoke and the overlay attachment
        // when the WebSocket closes. The spoke removal is seam-identity-guarded so a
        // late tear from a superseded room cannot cancel a re-admitted spoke.
        try {
            awaitCancellation()
        } finally {
            hub.removeSpoke(learnerId, seamChannel)
            overlay.evict(admittedPeer.id)
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
    val serverScope = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
    val serverPeerId = PeerId(voterIds.first().value)
    // A single-server deployment replicates nothing, so the overlay rides two peerless
    // placeholder seams: its Quilter has no peers to gossip to (it excludes `self` from
    // its target set), attach/lookup are purely local, and the constant `{ 0 }` clock is
    // fine because AttachmentDirectory.nextTimestamp is monotonic via max(now, last + 1).
    val overlay = localOverlay(serverPeerId, serverScope)
    return serverCluster(host, voterIds, raftConfig, overlay, serverScope, storageFactory)
}

/**
 * Construct a [ServerCluster] whose two-tier [overlay] is supplied by the caller —
 * for a **federated** deployment whose [OverlayServer] replicates its attachment
 * directory over a real inter-server seam (e.g. `NamedMux(coreMesh).channel(...)`),
 * so a client admitted here becomes visible to every peer server's routing.
 *
 * Unlike [CoroutineScope.serverCluster]'s default (a local, non-replicating overlay),
 * the caller owns [overlay]'s lifecycle: it is **not** closed by [ServerCluster.close].
 *
 * @param host The [RoomHost] for accepting learner connections.
 * @param voterIds Ordered list of voter [NodeId]s. Non-empty; odd count recommended.
 * @param raftConfig Raft timing and virtual-time flags. **Required** — no default.
 * @param overlay The federated [OverlayServer] every admitted connection is published into.
 * @param storageFactory Per-voter [RaftStorage] factory. Defaults to [InMemoryRaftStorage].
 */
public fun CoroutineScope.serverCluster(
    host: RoomHost,
    voterIds: List<NodeId>,
    raftConfig: RaftConfig,
    overlay: OverlayServer,
    storageFactory: (NodeId) -> RaftStorage = { InMemoryRaftStorage() },
): ServerCluster {
    require(voterIds.isNotEmpty()) { "voterIds must be non-empty" }
    val serverScope = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
    return serverCluster(host, voterIds, raftConfig, overlay, serverScope, storageFactory)
}

/** Shared builder: wires the voter mesh + relay accept loop and threads [overlay] through. */
private fun serverCluster(
    host: RoomHost,
    voterIds: List<NodeId>,
    raftConfig: RaftConfig,
    overlay: OverlayServer,
    serverScope: CoroutineScope,
    storageFactory: (NodeId) -> RaftStorage,
): ServerCluster {
    val clusterConfig = ClusterConfig(voters = voterIds.toSet())
    val hub = RaftRelayHub(voterIds.toSet())
    val voterNodes = buildVoterChannelMesh(voterIds, clusterConfig, raftConfig, storageFactory, serverScope, hub)
    val mesh = VoterMesh(voterNodes = voterNodes, scope = serverScope)
    return ServerCluster(
        mesh = mesh,
        host = host,
        voterConfig = clusterConfig,
        hub = hub,
        overlay = overlay,
        serverScope = serverScope,
    )
}

/**
 * Build the local, non-replicating overlay the default [CoroutineScope.serverCluster]
 * uses: a real [OverlayServer] over two peerless [Seam]s. [self] is both the directory
 * value written for every admitted client and the core-seam identity (they match, so the
 * `route()` local-vs-remote decision always resolves local here). Parented to [scope] so
 * [ServerCluster.close] tears it down.
 */
private fun localOverlay(self: PeerId, scope: CoroutineScope): OverlayServer =
    overlayServer(
        self = self,
        coreSeam = PeerlessSeam(self),
        directorySeam = PeerlessSeam(PeerId("${self.value}#overlay-dir")),
        scope = scope,
        clock = { 0 },
    )

/**
 * A [Seam] with no peers — the placeholder link the single-server local overlay rides.
 * Its [incoming] never completes (so the directory's [us.tractat.kuilt.quilter.Quilter]
 * replicator stays alive) and never emits; [broadcast]/[sendTo] are no-ops (the Quilter
 * excludes `selfId` from its gossip targets, so it never actually sends). Immutable —
 * trivially correct under any dispatcher.
 */
private class PeerlessSeam(override val selfId: PeerId) : Seam {
    override val peers: StateFlow<Set<PeerId>> = MutableStateFlow(setOf(selfId))
    override val state: StateFlow<SeamState> = MutableStateFlow(SeamState.Woven)
    override val incoming: Flow<Swatch> = MutableSharedFlow()
    override suspend fun broadcast(payload: ByteArray): Unit = Unit
    override suspend fun sendTo(peer: PeerId, payload: ByteArray): Unit = Unit
    override suspend fun close(reason: CloseReason): Unit = Unit
}

// ── In-process voter channel transport ──────────────────────────────────────

/**
 * Wire [voterIds] into a K_M complete-graph mesh using [Channel]-backed [RaftTransport]s,
 * extended with learner routing via [RaftRelayHub].
 *
 * Each voter gets:
 * - An inbound [MutableSharedFlow] that fans in from both voter channels and learner spokes.
 * - An inbound [Channel] for voter-to-voter messages.
 * - Access to the [RaftRelayHub] for voter-to-learner sends (dest-routed, origin-preserving).
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
    hub: RaftRelayHub,
): Map<NodeId, RaftNode> {
    val voterChannels = voterIds.associateWith { Channel<RaftEnvelope>(Channel.UNLIMITED) }
    val voterPeersFlow = MutableStateFlow(voterIds.toSet())

    return voterIds.associateWith { id ->
        // Fan-in SharedFlow: receives from both the voter channel relay and learner spoke relays.
        val inbound = MutableSharedFlow<RaftEnvelope>(extraBufferCapacity = Int.MAX_VALUE)
        hub.registerVoterInbound(id, inbound)

        // Relay the voter's own inbound channel into the SharedFlow.
        val childScope = CoroutineScope(scope.coroutineContext + Job(scope.coroutineContext[Job]))
        childScope.launch {
            voterChannels.getValue(id).receiveAsFlow().collect { inbound.emit(it) }
        }

        val transport = voterChannelTransport(id, voterChannels, voterPeersFlow, inbound, hub, childScope)
        val storage = storageFactory(id)
        childScope.raftNode(clusterConfig, transport, storage, raftConfig)
    }
}

private fun voterChannelTransport(
    id: NodeId,
    voterChannels: Map<NodeId, Channel<RaftEnvelope>>,
    voterPeersState: MutableStateFlow<Set<NodeId>>,
    inbound: MutableSharedFlow<RaftEnvelope>,
    hub: RaftRelayHub,
    peersScope: CoroutineScope,
): RaftTransport = object : RaftTransport {

    override val selfId: NodeId = id

    /** Combined voter + learner peer set, excluding this voter's own id. */
    override val peers: StateFlow<Set<NodeId>> =
        combine(voterPeersState, hub.learnersFlow) { voters, learners ->
            voters - id + learners
        }.stateIn(
            scope = peersScope,
            started = SharingStarted.Eagerly,
            initialValue = voterPeersState.value - id + hub.learnersFlow.value,
        )

    override val incoming: Flow<RaftEnvelope> = inbound

    override val maxPayloadBytes: Int? = null

    override suspend fun sendTo(peer: NodeId, message: ByteArray) {
        val voterChannel = voterChannels[peer]
        if (voterChannel != null) {
            voterChannel.send(RaftEnvelope(id, message))
        } else {
            // A learner dest: wrap as RaftRelay(origin = this voter) so the client credits
            // the true voter, and route down that learner's spoke by dest.
            hub.sendToLearner(fromVoter = id, learnerId = peer, bytes = message)
        }
    }
}
