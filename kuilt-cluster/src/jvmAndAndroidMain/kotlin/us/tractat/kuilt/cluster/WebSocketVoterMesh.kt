package us.tractat.kuilt.cluster

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.fabric.ConnectionSource
import us.tractat.kuilt.core.fabric.Mesh
import us.tractat.kuilt.core.fabric.hubMesh
import us.tractat.kuilt.raft.InMemoryRaftStorage
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftConfig
import us.tractat.kuilt.raft.RaftStorage
import us.tractat.kuilt.websocket.WebSocketConnection
import kotlin.random.Random

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
 * ## Lifecycle
 *
 * Voter nodes run on child scopes of the receiver (via [voterMeshOverSeams]); [VoterMesh.close]
 * stops them. The [httpClient] is **not** closed here — the caller owns it.
 *
 * @param voters The M voter endpoints. At least 2; an odd count is recommended for clean quorum.
 * @param httpClient Client used to dial peer voters. Must have the WebSockets plugin installed.
 * @param dispatcher Scheduler for each mesh's per-link read loops (scheduling only — the mesh
 *   guards its own state with primitives). Production passes `Dispatchers.Default`; tests pass a
 *   dispatcher derived from the test scheduler.
 * @param raftConfig Raft timing and election RNG. **Required** — seed [RaftConfig.random].
 * @param storageFactory Per-voter [RaftStorage] factory. Defaults to [InMemoryRaftStorage].
 * @param random Source of the per-connection mesh nonces. Each voter is given its own seeded child
 *   [Random] (drawn sequentially before any concurrent formation) so the mesh's dedup tiebreak is
 *   deterministic *and* no [Random] instance is shared across the concurrent handshakes.
 */
public suspend fun CoroutineScope.voterMeshOverWebSockets(
    voters: List<WebSocketVoter>,
    httpClient: HttpClient,
    dispatcher: CoroutineDispatcher,
    raftConfig: RaftConfig,
    storageFactory: (NodeId) -> RaftStorage = { InMemoryRaftStorage() },
    random: Random = Random.Default,
): VoterMesh {
    require(voters.size >= 2) { "voterMeshOverWebSockets needs at least 2 voters, got ${voters.size}" }
    val ordered = voters.sortedBy { it.nodeId.value }

    // Draw one child Random per voter up front (single-threaded) so nothing is shared across the
    // concurrent handshakes below — a seeded Random is not thread-safe.
    val voterRandom = ordered.associate { it.nodeId to Random(random.nextLong()) }

    // Every voter's mesh starts empty; links are added from both ends via addLink (see kdoc).
    val meshes: Map<NodeId, Mesh> = ordered.associate { voter ->
        voter.nodeId to hubMesh(
            selfId = PeerId(voter.nodeId.value),
            connections = emptyList(),
            dispatcher = dispatcher,
            random = voterRandom.getValue(voter.nodeId),
        )
    }

    // Form all M*(M-1)/2 links concurrently. coroutineScope joins every dial and accept before we
    // build the nodes, so each voter's peer set is complete before its RaftNode starts.
    coroutineScope {
        ordered.forEachIndexed { index, voter ->
            val mesh = meshes.getValue(voter.nodeId)
            // Accept exactly `index` inbound links — one from each lower-ranked voter.
            launch { repeat(index) { mesh.addLink(voter.source.accept()) } }
            // Dial every higher-ranked voter once.
            launch {
                ordered.drop(index + 1).forEach { higher ->
                    mesh.addLink(WebSocketConnection(httpClient.webSocketSession(higher.dialUrl)))
                }
            }
        }
    }

    return voterMeshOverSeams(
        voterSeams = meshes,
        raftConfig = raftConfig,
        storageFactory = storageFactory,
    )
}
