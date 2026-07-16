package us.tractat.kuilt.cluster

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.fabric.ConnectionSource
import us.tractat.kuilt.core.fabric.Mesh
import us.tractat.kuilt.core.fabric.acceptPump
import us.tractat.kuilt.core.fabric.hubMesh
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.core.util.ExponentialBackoff
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
 * ## Mesh formation — canonical dial rule
 *
 * The M servers must form a K_M complete graph where **each pair connects exactly once** (a
 * double-dial would waste a socket and force the mesh's dedup lottery to arbitrate). The rule is
 * purely positional and needs no coordination: order the voters by [NodeId], and **the lower id
 * dials the higher**. So the voter ranked `i` (0-based) dials the `M-1-i` voters above it and
 * accepts exactly `i` inbound links from the voters below it. For every pair the lower id is the
 * sole dialer and the higher id the sole acceptor — one connection, deterministically.
 *
 * Each server's [Mesh] starts **empty** and grows by [Mesh.addLink]: the dialer adds the connection
 * it opened, the acceptor adds the one it accepted, and both ends run [Mesh.addLink] concurrently.
 * Building empty-then-`addLink` (rather than handing dialed connections to `meshSeam` at
 * construction) is what keeps formation deadlock-free — no server's mesh construction blocks on a
 * handshake that another server can only service once *its own* construction has returned.
 *
 * Once every link is up, the seams are handed to [voterMeshOverSeams], which wraps each in a
 * `SeamRaftTransport` and starts the voter [us.tractat.kuilt.raft.RaftNode]s.
 *
 * ## Reconnection — a dropped inter-server link heals
 *
 * Formation is not the end of the story: a voter-to-voter link can drop at any time (a peer
 * restarts, the network blips, a half-open TCP corpse is reaped by the WebSocket ping). Three pieces
 * keep the K_M mesh whole for the life of the [VoterMesh]:
 *
 * - **A persistent accept-pump per voter, running from t0.** Each voter's inbound route is drained
 *   forever by [acceptPump] (not just the `index` links formation expects), so a peer that re-dials
 *   after a drop is admitted exactly as an initial joiner was.
 * - **A per-voter redial supervisor.** [superviseVoterReconnection] watches each voter's `peers` and,
 *   whenever a peer this voter is the designated dialer for (the lower-id-dials-higher rule) goes
 *   absent, re-dials it under [ExponentialBackoff] full jitter until it returns — then falls idle.
 * - **A `hubMesh` per voter** (never terminal on drain), so losing a link removes only that peer and
 *   the seam keeps serving the rest while the supervisor re-dials.
 *
 * Both run on the mesh lifecycle scope built up front here (so they can start before formation) and
 * handed to [voterMeshOverSeams]; [VoterMesh.close] cancels pumps + supervisors + nodes together.
 *
 * ## Lifecycle
 *
 * Pumps, supervisors, and voter nodes all run on the mesh lifecycle scope (a child of the receiver);
 * [VoterMesh.close] cancels it and stops them all — and then, because this path **owns** the per-voter
 * `hubMesh` seams (`ownsSeams = true`), it gracefully closes each seam too. The seams run on their own
 * `SupervisorJob` scopes, so cancelling the mesh scope alone would NOT close them: without the graceful
 * close the inter-server WebSocket sessions would stay ESTABLISHED and still answer pings, and peers
 * would hold this voter in-roster as a **zombie** indefinitely. The [httpClient] is still **not** closed
 * here — the caller owns it.
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
    val ordered = voters.sortedBy { it.nodeId.value }

    // Draw child Randoms per voter up front (single-threaded) so nothing is shared across the
    // concurrent handshakes / redial loops below — a seeded Random is not thread-safe. The mesh
    // nonce source and the backoff jitter source are DISTINCT instances per voter: they are driven
    // concurrently (the mesh draws a nonce on every addLink — including redials — while the
    // supervisor draws jitter between redials), so they must not share one non-thread-safe Random.
    val voterRandom = ordered.associate { it.nodeId to Random(random.nextLong()) }
    val backoffRandom = ordered.associate { it.nodeId to Random(random.nextLong()) }

    // Every voter's mesh starts empty; links are added from both ends via addLink (see kdoc).
    val meshes: Map<NodeId, Mesh> = ordered.associate { voter ->
        voter.nodeId to hubMesh(
            selfId = PeerId(voter.nodeId.value),
            connections = emptyList(),
            dispatcher = dispatcher,
            random = voterRandom.getValue(voter.nodeId),
        )
    }

    // Build the mesh lifecycle scope UP FRONT — the persistent accept-pumps must run from t0 (before
    // formation), and the supervisors and voter nodes join it later. VoterMesh.close cancels it, so
    // pumps + supervisors + nodes all stop together. (It could not be VoterMesh.scope: that scope
    // does not exist until voterMeshOverSeams returns, after formation.)
    val meshScope = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
    val fullPeerIdSet: Set<PeerId> = ordered.map { PeerId(it.nodeId.value) }.toSet()
    val dialUrlByPeer: Map<PeerId, String> = ordered.associate { PeerId(it.nodeId.value) to it.dialUrl }

    try {
        // (a) Persistent accept-pump per voter, from t0. Drains each voter's inbound route forever, so a
        // peer that re-dials after a drop is admitted just like an initial joiner — not merely the fixed
        // `index` links formation expects.
        ordered.forEach { voter ->
            meshScope.acceptPump(
                source = voter.source,
                handshakeTimeout = handshakeTimeout,
                onFailure = {},
                handle = { conn -> meshes.getValue(voter.nodeId).addLink(conn) },
            )
        }

        // (b) Initial dials + await the full K_M roster, under a formation timeout. coroutineScope joins
        // every dial and roster-await before we build the nodes, so each voter's peer set is complete
        // before its RaftNode starts (synchronous formation). containsAll (over ==) is robust to a stray
        // non-voter conn on the route.
        withTimeout(formationTimeout) {
            coroutineScope {
                ordered.forEachIndexed { index, voter ->
                    val mesh = meshes.getValue(voter.nodeId)
                    // Dial every higher-ranked voter once (lower id dials higher).
                    launch {
                        ordered.drop(index + 1).forEach { higher ->
                            mesh.addLink(WebSocketConnection(httpClient.webSocketSession(higher.dialUrl)))
                        }
                    }
                    launch { mesh.peers.first { it.containsAll(fullPeerIdSet) } }
                }
            }
        }

        // (c) Per-voter redial supervisor on meshScope. Started AFTER formation, when every dial target is
        // already present, so the loops sit idle until a real drop. Each voter re-dials only the peers it
        // is the designated dialer for (the higher-ranked ones), so no pair is ever double-dialed.
        ordered.forEachIndexed { index, voter ->
            val higher = ordered.drop(index + 1).map { PeerId(it.nodeId.value) }.toSet()
            meshScope.superviseVoterReconnection(
                mesh = meshes.getValue(voter.nodeId),
                dialTargets = higher,
                dial = { peer -> WebSocketConnection(httpClient.webSocketSession(dialUrlByPeer.getValue(peer))) },
                backoff = ExponentialBackoff(
                    base = RECONNECT_BACKOFF_BASE,
                    cap = RECONNECT_BACKOFF_CAP,
                    random = backoffRandom.getValue(voter.nodeId),
                ),
                dialTimeout = dialTimeout,
            )
        }

        // (d) Hand meshScope to voterMeshOverSeams so close() cancels pumps + supervisors + nodes together.
        // ownsSeams = true: the hubMesh seams were created HERE, so VoterMesh.close must gracefully close
        // them (their SupervisorJob scopes are not under meshScope) — otherwise cancelling meshScope leaves
        // the inter-server WebSocket sessions ESTABLISHED and peers hold this voter as a zombie forever.
        return voterMeshOverSeams(
            voterSeams = meshes,
            raftConfig = raftConfig,
            meshScope = meshScope,
            storageFactory = storageFactory,
            ownsSeams = true,
        )
    } catch (e: Throwable) {
        // Formation failed (e.g. formationTimeout fired on a stalled/crashed voter) — the caller never
        // received a VoterMesh, so it has NO handle to close the mesh scope. Tear down everything this
        // function started before rethrowing, or the accept-pumps drain forever and the partially-formed
        // seams (each on its own SupervisorJob scope, NOT a child of meshScope) linger with their live
        // WebSocket sessions.
        meshScope.cancel()                       // stop the persistent accept-pumps + any supervisors
        // Close the internally-created hubMesh seams: their SupervisorJob scopes are not under meshScope,
        // so cancelling meshScope does not close them. Uncancellable so a TimeoutCancellationException
        // context does not skip the cleanup; best-effort per seam.
        withContext(NonCancellable) {
            meshes.values.forEach { runCatchingCancellable { it.close() } }
        }
        throw e
    }
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
