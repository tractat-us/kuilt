package us.tractat.kuilt.cluster

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
import us.tractat.kuilt.raft.raftNode
import us.tractat.kuilt.session.LeaveReason
import us.tractat.kuilt.session.Room
import us.tractat.kuilt.session.RoomHost
import kotlin.time.Duration
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
 * 4. Holds the admission while the peer is in the room's roster, and withdraws the spoke and the
 *    overlay attachment once it leaves: a transport tear, the client's goodbye or an expired
 *    partition all drop it from the roster.
 *
 * Every accepted connection must be its own [Room], as [us.tractat.kuilt.websocket.KtorRoomHost]
 * makes it. Each room admits its first member only, so a [RoomHost] that surfaces one room holding
 * many members admits one learner.
 *
 * Registering the spoke is also what admits the learner. Every voter runs a learner reconciler
 * over [RaftRelayHub.learnersFlow]; the leader's commits one membership change adding every
 * registered learner it does not yet have, built from its current membership. No connection
 * changes membership itself, which is what used to let one admission evict another (#3120).
 * The reconciler is the sole membership writer this class runs: a caller changing membership
 * through [voterNodes] directly can race it. Built with a `learnerGrace`, it also removes: a learner
 * disconnected for the grace leaves the config, and that includes one a caller added directly
 * through [voterNodes] that never connects.
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
    learnerRemoval: LearnerRemoval,
) {
    init {
        // One reconciler per voter; the role gate inside means only the leader acts, so leadership
        // moving between voters needs no hand-off here. Under a grace, one LearnerGrace is shared
        // by all of them, so a leadership change keeps the pending removals.
        when (learnerRemoval) {
            LearnerRemoval.Never ->
                mesh.voterNodes.values.forEach { serverScope.launchLearnerReconciler(it, hub.learnersFlow) }
            is LearnerRemoval.AfterGrace -> {
                val grace = LearnerGrace(hub.learnersFlow, learnerRemoval.grace, serverScope)
                mesh.voterNodes.values.forEach { serverScope.launchLearnerReconciler(it, grace) }
            }
        }
    }

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
     * learner [NodeId], register its Seam as a spoke in the shared [RaftRelayHub] (which is what
     * requests its admission: the leader's learner reconciler commits it), then hold the admission
     * until the peer leaves the room's roster or the relay scope cancels. The `finally` withdraws the
     * spoke and the overlay attachment, which is what starts a learner grace.
     */
    private suspend fun admitLearner(room: Room) {
        val admittedSet = try {
            withTimeout(10.seconds) { room.roster.first { it.isNotEmpty() } }
        } catch (e: Throwable) {
            // Whose cancellation is this? The `withTimeout` above MINTS a
            // `TimeoutCancellationException` and throws it at us without cancelling this job — and
            // that timeout is the one case this `try` exists for. A `catch (CancellationException)
            // { throw e }` here would match it first and make everything below dead code, so the
            // room would never be left and one learner's silence would read as the relay being
            // cancelled (#2292). `ensureActive()` is the discriminator: it throws only when THIS
            // job is genuinely cancelled, and falls through on the minted one.
            currentCoroutineContext().ensureActive()
            log.warn(e) { "server-cluster: roster wait failed" }
            runCatchingCancellable { room.leave(LeaveReason.Normal) }
            return
        }

        val admittedPeer = admittedSet.first()
        val learnerId = NodeId(admittedPeer.id.value)
        log.info { "server-cluster: admitting learner $learnerId" }

        // Register the room Seam as a spoke so voter transports can route Raft messages
        // to and from the learner over the WebSocket. Registering is also what requests the
        // learner's admission (via hub.learnersFlow), so the spoke exists before the leader
        // sends its first AppendEntries.
        val seamChannel = room.channel("raft")
        hub.addSpoke(learnerId, seamChannel, serverScope)

        // Discharge the overlay obligation structurally: publish this connection's
        // attachment (`client → self`) and register its app-unicast spoke — a channel
        // DISTINCT from the "raft" leg above. Register-before-membership (same ordering
        // the router follows) so a frame racing in across the core finds the spoke ready.
        val unicastChannel = room.channel(OVERLAY_UNICAST_CHANNEL)
        overlay.admit(admittedPeer.id, unicastChannel)

        // Membership is not changed here. The spoke registration above put learnerId into
        // hub.learnersFlow, and each voter's learner reconciler (launched in init) commits it from
        // the leader's current membership. Changing membership per connection, from a config read
        // here, is what let one admission evict another (#3120).

        // Hold the admission while the peer is a member of this room. The host room drops it on a
        // transport tear, on the client's goodbye and when a partition expires, and nothing else would
        // end this coroutine: no production RoomHost cancels onRoom when its connection closes (#3127,
        // #3129). Both removals are identity-guarded, so a late close of a room this learner has since
        // been re-admitted past leaves the newer admission alone.
        try {
            room.roster.first { roster -> roster.none { it.id == admittedPeer.id } }
            log.info { "server-cluster: learner $learnerId left its room" }
        } finally {
            hub.removeSpoke(learnerId, seamChannel)
            overlay.evict(admittedPeer.id, unicastChannel)
        }
    }

    /** Suspend until a voter is elected leader. Delegates to [VoterMesh.awaitLeader]. */
    public suspend fun awaitLeader(): RaftNode = mesh.awaitLeader()

    /**
     * Cancel the server scope — stops all voter nodes and the relay accept loop — by delegating to
     * [VoterMesh.close]. `suspend` because [VoterMesh.close] is now suspend (it may gracefully close
     * internally-owned inter-server seams). This cluster's mesh is built over in-process channels with
     * no owned seams, so here `close()` just cancels the scope, but the suspend signature is uniform.
     */
    public suspend fun close() {
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
): ServerCluster = localServerCluster(host, voterIds, raftConfig, storageFactory, LearnerRemoval.Never)

/**
 * Construct a [ServerCluster] that removes a learner from the config once it has been disconnected
 * for [learnerGrace]. Otherwise the same as the overload without it, which never removes one.
 *
 * Without removal the learner set grows with every [NodeId] that ever connected, and the leader keeps
 * replicating to all of them. Use this when clients take a fresh [NodeId] per session.
 *
 * The grace is keyed on [NodeId], not on a connection: a client that reconnects under the same id
 * inside it (a fresh room after a tear, or another relay after failover) is never removed, and one
 * that reconnects after it is simply admitted again and catches up. Removal is a learner-set-only
 * membership change, so the voter set and its quorum are untouched. A learner already in the config
 * when this cluster starts (one admitted before a restart from durable [storageFactory]) gets a full
 * grace to reconnect.
 *
 * @param learnerGrace How long a learner may stay disconnected before it is removed. Non-negative;
 *   `Duration.ZERO` removes it as soon as its connection closes.
 */
public fun CoroutineScope.serverCluster(
    host: RoomHost,
    voterIds: List<NodeId>,
    raftConfig: RaftConfig,
    learnerGrace: Duration,
    storageFactory: (NodeId) -> RaftStorage = { InMemoryRaftStorage() },
): ServerCluster =
    localServerCluster(host, voterIds, raftConfig, storageFactory, LearnerRemoval.AfterGrace(learnerGrace))

/** The overloads without a caller-supplied overlay, which build the local one. */
private fun CoroutineScope.localServerCluster(
    host: RoomHost,
    voterIds: List<NodeId>,
    raftConfig: RaftConfig,
    storageFactory: (NodeId) -> RaftStorage,
    learnerRemoval: LearnerRemoval,
): ServerCluster {
    require(voterIds.isNotEmpty()) { "voterIds must be non-empty" }
    val serverScope = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
    val serverPeerId = PeerId(voterIds.first().value)
    // A single-server deployment replicates nothing, so the overlay rides two peerless
    // placeholder seams: its Quilter has no peers to gossip to (it excludes `self` from
    // its target set), attach/lookup are purely local, and the constant `{ 0 }` clock is
    // fine because AttachmentDirectory.nextTimestamp is monotonic via max(now, last + 1).
    val overlay = localOverlay(serverPeerId, serverScope)
    return serverCluster(host, voterIds, raftConfig, overlay, serverScope, storageFactory, learnerRemoval)
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
): ServerCluster = federatedServerCluster(host, voterIds, raftConfig, overlay, storageFactory, LearnerRemoval.Never)

/**
 * The federated form of the [learnerGrace] overload: a caller-supplied [overlay], and a learner
 * removed once it has been disconnected for [learnerGrace]. See that overload for the grace.
 *
 * @param learnerGrace How long a learner may stay disconnected before it is removed. Non-negative.
 */
public fun CoroutineScope.serverCluster(
    host: RoomHost,
    voterIds: List<NodeId>,
    raftConfig: RaftConfig,
    overlay: OverlayServer,
    learnerGrace: Duration,
    storageFactory: (NodeId) -> RaftStorage = { InMemoryRaftStorage() },
): ServerCluster =
    federatedServerCluster(host, voterIds, raftConfig, overlay, storageFactory, LearnerRemoval.AfterGrace(learnerGrace))

/** The overloads with a caller-supplied overlay. */
private fun CoroutineScope.federatedServerCluster(
    host: RoomHost,
    voterIds: List<NodeId>,
    raftConfig: RaftConfig,
    overlay: OverlayServer,
    storageFactory: (NodeId) -> RaftStorage,
    learnerRemoval: LearnerRemoval,
): ServerCluster {
    require(voterIds.isNotEmpty()) { "voterIds must be non-empty" }
    val serverScope = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
    return serverCluster(host, voterIds, raftConfig, overlay, serverScope, storageFactory, learnerRemoval)
}

/** Shared builder: wires the voter mesh + relay accept loop and threads [overlay] through. */
private fun serverCluster(
    host: RoomHost,
    voterIds: List<NodeId>,
    raftConfig: RaftConfig,
    overlay: OverlayServer,
    serverScope: CoroutineScope,
    storageFactory: (NodeId) -> RaftStorage,
    learnerRemoval: LearnerRemoval,
): ServerCluster {
    val clusterConfig = ClusterConfig(voters = voterIds.toSet())
    val hub = RaftRelayHub(voterIds.toSet())
    val voterNodes = buildVoterChannelMesh(voterIds, clusterConfig, raftConfig, storageFactory, serverScope, hub)
    val mesh = VoterMesh(voterNodes = voterNodes, scope = serverScope)
    return ServerCluster(
        mesh = mesh,
        host = host,
        hub = hub,
        overlay = overlay,
        serverScope = serverScope,
        learnerRemoval = learnerRemoval,
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
    // ALLOW-bareSeamState: a constant — nothing retains a handle to the flow, so it has no writer at all.
    override val state: StateFlow<SeamState> = MutableStateFlow(SeamState.Woven)
    override val incoming: Flow<Swatch> = MutableSharedFlow()
    override suspend fun broadcast(payload: ByteArray): Unit = Unit

    /**
     * A no-op for a remote — this placeholder has none — but a self-send is still REFUSED (#2428).
     * `peers` here is exactly `{ selfId }`, so self is the only id a caller could plausibly address,
     * and answering it with silence is how a caller's mistake becomes an invisible dropped frame.
     */
    override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
        require(peer != selfId) { "Cannot send to self — use broadcast if you intend to loop back" }
    }

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

/** Whether a [ServerCluster] ever removes a learner, and after how long a disconnect. */
internal sealed interface LearnerRemoval {
    /** Learners are only ever added: the overloads without a grace. */
    data object Never : LearnerRemoval

    /**
     * A learner disconnected for [grace] is removed from the config. Validated here, before any
     * scope or voter exists, so a bad grace cannot leave voter nodes running on a scope nobody holds.
     */
    data class AfterGrace(val grace: Duration) : LearnerRemoval {
        init {
            require(!grace.isNegative()) { "learnerGrace must not be negative, was $grace" }
        }
    }
}
