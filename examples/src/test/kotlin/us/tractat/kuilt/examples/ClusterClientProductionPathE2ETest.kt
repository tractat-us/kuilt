package us.tractat.kuilt.examples

import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.cluster.ClusterClient
import us.tractat.kuilt.cluster.ClusterEndpoints
import us.tractat.kuilt.cluster.clusterClient
import us.tractat.kuilt.cluster.serverCluster
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.raft.ClusterConfig
import us.tractat.kuilt.raft.Committed
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftConfig
import us.tractat.kuilt.websocket.KtorClientLoom
import us.tractat.kuilt.websocket.KtorRoomHost
import us.tractat.kuilt.websocket.WebSocketAdvertisement
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end integration test for [ClusterClient] using the PRODUCTION
 * [CoroutineScope.clusterClient] extension over **real Ktor WebSocket sockets**.
 *
 * ## What this proves (#544)
 *
 * A single-endpoint `clusterClient()` call proposes a command and observes it committed —
 * exercising the production path (not the `clusterClientWithNode` test-only path that S3b-3
 * had to fall back to). The client's `selfPeerId` is pinned on the [KtorClientLoom] so its
 * wire identity equals `clientNodeId`, and the production reconnect loop joins via
 * `SeamRoomFactory` so it goes through the `ServerCluster` admit handshake — the two changes
 * that make the production extension usable end-to-end at all.
 *
 * ## Why selfPeerId matters (root cause of #544)
 *
 * Before this fix, `KtorClientLoom.weave()` minted a fresh random [PeerId] on every join.
 * On reconnect the server admitted a *different* learner [NodeId] — Raft routing broke.
 * The fix pins `selfPeerId` once at loom construction; the caller derives `clientNodeId`
 * from this stable identity.
 *
 * ## Not covered here — cross-server failover
 *
 * The full failover leg (≥2 relay endpoints, kill the entry server, client re-admits on
 * another keeping its identity, a subsequent propose commits) requires the relay endpoints
 * to front **one shared voter cluster** — i.e. the M>1 voter mesh over real sockets, which
 * is tracked by #545. Two independent single-node clusters cannot share a Raft log, so a
 * learner cannot meaningfully fail over between them. The stable-identity primitive proven
 * here is the prerequisite; the shared-cluster failover proof lands with #545.
 *
 * ## Real-socket discipline
 *
 * All timeouts here are hard wall-clock bounds — [RaftNode] uses real-clock delays
 * and cannot be driven by a virtual-time scheduler. Every [withTimeout] is sized
 * conservatively to avoid CI flakiness; none of the raftCfg timers are tightened
 * below what reliably converges on a cold JVM.
 */
class ClusterClientProductionPathE2ETest {

    private val pathA = "/ws/failover-a"
    private val serverPeerIdA = PeerId("cluster-server-a")
    private val roomPattern = Pattern("failover-e2e-room")

    /** Fast but not too tight — elections must converge on a cold CI runner. */
    private val raftCfg = RaftConfig(
        electionTimeoutMin = 5.milliseconds,
        electionTimeoutMax = 10.milliseconds,
        heartbeatInterval = 2.milliseconds,
        random = Random(544),
    )

    // ── Leg 1: single endpoint — connect → propose → commit ──────────────────

    @Test
    fun `clusterClient production path — propose commits end-to-end through serverCluster`() =
        testApplication {
            val voterId = NodeId(serverPeerIdA.value)
            val clientNodeId = NodeId("cluster-client-leg1")

            val host = KtorRoomHost(
                application = application,
                path = pathA,
                serverPeerId = serverPeerIdA,
                pattern = roomPattern,
            )

            // Pin the loom's selfPeerId to match clientNodeId — this is the key fix from #544.
            val clientLoom = KtorClientLoom(
                httpClient = createClient { install(WebSockets) },
                selfPeerId = PeerId(clientNodeId.value),
            )

            val advertisementA = WebSocketAdvertisement(
                url = "ws://localhost$pathA",
                serverPeerId = serverPeerIdA,
                displayName = "cluster-client",
            )

            val clientCommitted = CompletableDeferred<ByteArray>()

            coroutineScope {
                val serverScope = CoroutineScope(coroutineContext + Job())
                val cluster = serverScope.serverCluster(
                    host = host,
                    voterIds = listOf(voterId),
                    raftConfig = raftCfg,
                )

                serverScope.launch { cluster.start() }

                val clientScope = CoroutineScope(coroutineContext + Job())
                val clusterConfig = ClusterConfig(
                    voters = setOf(voterId),
                    learners = setOf(clientNodeId),
                )
                val client: ClusterClient = clientScope.clusterClient(
                    loom = clientLoom,
                    clusterEndpoints = ClusterEndpoints(listOf(advertisementA)),
                    clientNodeId = clientNodeId,
                    clusterConfig = clusterConfig,
                    raftConfig = raftCfg,
                    clock = { Clock.System.now() },
                )

                clientScope.launch {
                    client.committed
                        .first { it is Committed.Entry }
                        .let { clientCommitted.complete((it as Committed.Entry).entry.command) }
                }

                val command = "action:leg1".encodeToByteArray()
                withTimeout(20.seconds) { client.propose(command) }

                val payload = withTimeout(10.seconds) { clientCommitted.await() }
                assertContentEquals(command, payload, "client committed payload must match proposed command")

                client.close()
                clientScope.cancel()
                cluster.close()
                serverScope.cancel()
            }
        }
}
