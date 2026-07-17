package us.tractat.kuilt.cluster

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.fabric.ConnectionSource
import us.tractat.kuilt.raft.InMemoryRaftStorage
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftConfig
import us.tractat.kuilt.raft.RaftStorage
import us.tractat.kuilt.websocket.WebSocketConnection
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * One voter server's inter-server endpoint: its identity, how it **accepts** inbound voter links,
 * and the URL its peers **dial** to reach it.
 *
 * @param nodeId This voter's [NodeId] — also its fabric [PeerId] on the inter-server mesh.
 * @param source Where inbound server-to-server links arrive — a [ConnectionSource] mounted on
 *   this server's own hosted route (e.g. `KtorConnectionSource(application, "/voter-a")`).
 * @param dialUrl The WebSocket URL other voters use to dial this one (e.g. `"ws://host:port/voter-a"`).
 */
public class WebSocketVoter(
    public val nodeId: NodeId,
    public val source: ConnectionSource,
    public val dialUrl: String,
)

/**
 * Form the complete-graph inter-server mesh of M voters over **real WebSocket connections**, then
 * wire them into a [VoterMesh] — the real-network counterpart of `serverCluster`'s in-process
 * voter core.
 *
 * This is the thin **WebSocket** wrapper over the transport-agnostic [assembleVoterMesh]: it supplies
 * each voter's WebSocket accept-source ([WebSocketVoter.source]) and a WebSocket dial (open a client
 * session to the target peer's [WebSocketVoter.dialUrl], wrapped as a [WebSocketConnection]). All the
 * mesh machinery — the K_M lower-id-dials-higher formation rule, the persistent accept-pumps, the
 * per-voter redial supervisors, the formation-timeout teardown, and the hand-off to
 * [voterMeshOverSeams] — lives in [assembleVoterMesh]. The [httpClient] is **not** closed here — the
 * caller owns it.
 *
 * @param voters The M voter endpoints. At least 2; an odd count is recommended for clean quorum.
 * @param httpClient Client used to dial peer voters. **Must** install the WebSockets plugin with a
 *   ping interval — `install(WebSockets) { pingInterval = … }` — so this voter detects a **half-open**
 *   link to a peer it dialed (silently dead TCP, no FIN/RST): without a client ping the dialing side's
 *   read loop blocks forever and the dead peer lingers for the multi-minute TCP-RTO window, so no
 *   redial is ever triggered. The server accept side is already ping-configured (see
 *   [us.tractat.kuilt.websocket.KtorConnectionSource]); the client half is the caller's responsibility
 *   here. **Engine constraint:** use the **CIO** engine, which honours the Ktor client `pingInterval`.
 *   The **OkHttp** engine ignores it — it has its own `WebSocketExtensionsConfig`/`pingInterval` knob —
 *   so an OkHttp-backed client silently gets no half-open detection unless OkHttp's own ping is set.
 * @param dispatcher Scheduler for each mesh's per-link read loops (scheduling only — the mesh
 *   guards its own state with primitives). Production passes `Dispatchers.Default`; tests pass a
 *   dispatcher derived from the test scheduler.
 * @param raftConfig Raft timing and election RNG. **Required** — seed [RaftConfig.random].
 * @param storageFactory Per-voter [RaftStorage] factory. Defaults to [InMemoryRaftStorage].
 * @param random Source of the per-connection mesh nonces and per-voter reconnect backoff jitter. Each
 *   voter is given its own seeded child [Random] for each role (drawn sequentially before any
 *   concurrent formation) so the mesh's dedup tiebreak and the redial jitter are deterministic *and*
 *   no [Random] instance is shared across concurrent coroutines.
 * @param handshakeTimeout Ceiling on a single accepted link's handshake (see [acceptPump]). A conn
 *   that connects but never completes its `MeshHello` exchange is abandoned after this, so it cannot
 *   wedge the persistent accept-pump. Defaults to [DEFAULT_HANDSHAKE_TIMEOUT].
 * @param dialTimeout Ceiling on a single **redial** negotiation (see [superviseVoterReconnection]). A
 *   redial is fired the instant a peer drops, which routinely coincides with the peer still being
 *   unreachable in a byte-dropping way (a half-open corpse, a black-holing firewall); the WebSocket
 *   negotiation of such a dial has no bound of its own (the client ping only reaps an *established*
 *   session), so an unbounded dial would hang forever and wedge the single-flight redial loop — the
 *   dropped edge would never heal. This bounds every redial so a hung negotiation is abandoned and the
 *   backoff loop retries once the path recovers. Defaults to [DEFAULT_DIAL_TIMEOUT].
 * @param formationTimeout Hard bound on initial mesh formation — the initial dials plus awaiting the
 *   full K_M roster on every voter. A stalled handshake or a crashed voter fails formation fast rather
 *   than hanging — and on that failure this function tears down everything it started (cancels the
 *   accept-pumps and closes the partially-formed seams) before rethrowing, since the caller receives no
 *   [VoterMesh] handle to close. Defaults to [DEFAULT_FORMATION_TIMEOUT]. (Post-formation reconnection is
 *   unbounded by design — the supervisor re-dials forever under backoff.)
 */
public suspend fun CoroutineScope.voterMeshOverWebSockets(
    voters: List<WebSocketVoter>,
    httpClient: HttpClient,
    dispatcher: CoroutineDispatcher,
    raftConfig: RaftConfig,
    storageFactory: (NodeId) -> RaftStorage = { InMemoryRaftStorage() },
    random: Random = Random.Default,
    handshakeTimeout: Duration = DEFAULT_HANDSHAKE_TIMEOUT,
    dialTimeout: Duration = DEFAULT_DIAL_TIMEOUT,
    formationTimeout: Duration = DEFAULT_FORMATION_TIMEOUT,
): VoterMesh {
    require(voters.size >= 2) { "voterMeshOverWebSockets needs at least 2 voters, got ${voters.size}" }

    // The two transport-specific inputs to the assembly: where each voter accepts inbound links, and a
    // WebSocket dial keyed by the target peer's dial URL. Both dial call-sites (formation + redial)
    // route through the single `dial` closure below.
    val sourceByNode: Map<NodeId, ConnectionSource> = voters.associate { it.nodeId to it.source }
    val dialUrlByPeer: Map<PeerId, String> = voters.associate { PeerId(it.nodeId.value) to it.dialUrl }

    return assembleVoterMesh(
        voters = voters.map { it.nodeId },
        sourceOf = { node -> sourceByNode.getValue(node) },
        dial = { _, peer -> WebSocketConnection(httpClient.webSocketSession(dialUrlByPeer.getValue(peer))) },
        dispatcher = dispatcher,
        raftConfig = raftConfig,
        random = random,
        handshakeTimeout = handshakeTimeout,
        dialTimeout = dialTimeout,
        formationTimeout = formationTimeout,
        backoffBase = RECONNECT_BACKOFF_BASE,
        backoffCap = RECONNECT_BACKOFF_CAP,
        storageFactory = storageFactory,
    )
}

/** Default handshake ceiling for the persistent accept-pump (see [voterMeshOverWebSockets]). */
private val DEFAULT_HANDSHAKE_TIMEOUT: Duration = 10.seconds

/** Default ceiling on a single redial's WebSocket negotiation (see [voterMeshOverWebSockets]). */
private val DEFAULT_DIAL_TIMEOUT: Duration = 10.seconds

/** Default hard bound on initial mesh formation (see [voterMeshOverWebSockets]). */
private val DEFAULT_FORMATION_TIMEOUT: Duration = 30.seconds

/** Base delay for the reconnect backoff (full jitter, per [ExponentialBackoff]). */
private val RECONNECT_BACKOFF_BASE: Duration = 200.milliseconds

/** Cap on the reconnect backoff — a long-partitioned peer is re-dialed at most this often. */
private val RECONNECT_BACKOFF_CAP: Duration = 30.seconds
