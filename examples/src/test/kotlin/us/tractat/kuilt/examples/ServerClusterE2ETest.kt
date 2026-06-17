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
import us.tractat.kuilt.cluster.clusterClientWithNode
import us.tractat.kuilt.cluster.serverCluster
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.raft.ClusterConfig
import us.tractat.kuilt.raft.Committed
import us.tractat.kuilt.raft.InMemoryRaftStorage
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftConfig
import us.tractat.kuilt.raft.SeamRaftTransport
import us.tractat.kuilt.raft.raftNode
import us.tractat.kuilt.session.SeamRoomFactory
import us.tractat.kuilt.websocket.KtorClientLoom
import us.tractat.kuilt.websocket.KtorRoomHost
import us.tractat.kuilt.websocket.WebSocketAdvertisement
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end integration test for the [us.tractat.kuilt.cluster.ServerCluster] +
 * [ClusterClient] facade over **real Ktor WebSocket sockets**.
 *
 * ## What this proves (S3b-3 of #513, part of epic #485)
 *
 * - [us.tractat.kuilt.cluster.ServerCluster] stands up an M=1 voter mesh and admits a
 *   learner client via [us.tractat.kuilt.websocket.KtorRoomHost]'s relay accept loop.
 * - [ClusterClient.propose] forwards a command to the leader, which commits it and
 *   replicates it back — the committed entry appears on [ClusterClient.committed].
 * - The server's [us.tractat.kuilt.cluster.VoterMesh.committed] stream also emits the
 *   same entry — cross-side consistency is confirmed.
 *
 * ## M=1 scope
 *
 * This test uses a single voter. The M=3 mesh is already proven under simulation by #541;
 * a real-socket M=3 variant is deferred as a follow-up (see S3c notes).
 *
 * ## Real-socket discipline
 *
 * [KtorRoomHost] uses real Ktor WebSocket sockets that cannot be driven by a virtual-time
 * scheduler. [us.tractat.kuilt.raft.RaftNode] also uses real-clock
 * [kotlinx.coroutines.delay] for elections. Every [kotlinx.coroutines.withTimeout] here
 * is a hard bound — there is no virtual clock to advance.
 *
 * ## Ordering constraints (mirrors [RelayRoomTest])
 *
 * 1. Server's relay accept loop (`start()`) must be launched before the client joins.
 * 2. Room admit handshake must complete on both sides before the client's [RaftNode]
 *    starts — `RoomChannelSeam.incoming` drops frames from un-admitted peers silently.
 * 3. The client's [RaftNode] starts with a locally-derived [ClusterConfig] (voters known,
 *    own NodeId derived from the Seam selfId). The server's `ServerCluster.start()`
 *    runs `awaitLeader()` + `changeMembership()` concurrently. The learner's `propose()`
 *    blocks naturally until the leader has applied the membership change and begun sending
 *    AppendEntries — no explicit signal between server and client is needed.
 *
 * ## Why the server's room stays open
 *
 * `ServerCluster.start()` holds each admitted room via `awaitCancellation()` — the room
 * (and its WebSocket) stays open until the server scope is cancelled. This is the
 * mechanism that keeps the replication channel alive for the full test duration.
 */
class ServerClusterE2ETest {

    private val serverPath = "/ws/cluster-e2e"
    private val serverPeerId = PeerId("cluster-server")
    private val roomPattern = Pattern("cluster-e2e-room")

    /** Fast timing so elections complete without long wall-clock waits. */
    private val raftCfg = RaftConfig(
        electionTimeoutMin = 5.milliseconds,
        electionTimeoutMax = 10.milliseconds,
        heartbeatInterval = 2.milliseconds,
        random = Random(99),
    )

    @Test
    fun `ClusterClient propose commits end-to-end through ServerCluster facade`() =
        testApplication {
            // The voter NodeId must match the KtorRoomHost's serverPeerId so that
            // the client's SeamRaftTransport (which sees swatch.sender = serverPeerId)
            // routes AppendEntries from the correct NodeId. The LearnerRouter sends via
            // seam.broadcast; the client's LinkSeam stamps sender = remoteId = serverPeerId.
            val voterId = NodeId(serverPeerId.value)

            val host = KtorRoomHost(
                application = application,
                path = serverPath,
                serverPeerId = serverPeerId,
                pattern = roomPattern,
            )

            val clientLoom = KtorClientLoom(createClient { install(WebSockets) })

            // Each side resolves its own deferred when it observes the committed entry.
            val serverCommittedPayload = CompletableDeferred<ByteArray>()
            val clientCommittedPayload = CompletableDeferred<ByteArray>()

            coroutineScope {
                // ── Server: ServerCluster facade ──────────────────────────────────────
                //
                // serverCluster() wires one voter (M=1) in-process and mounts the
                // KtorRoomHost relay accept loop.
                val serverScope = CoroutineScope(coroutineContext + Job())
                val cluster = serverScope.serverCluster(
                    host = host,
                    voterIds = listOf(voterId),
                    raftConfig = raftCfg,
                )

                // Collect committed entries from the voter mesh before start() runs.
                serverScope.launch {
                    cluster.committed
                        .first { it is Committed.Entry }
                        .let { committed ->
                            serverCommittedPayload.complete(
                                (committed as Committed.Entry).entry.command,
                            )
                        }
                }

                // Launch the relay accept loop. ServerCluster.start() holds each admitted
                // room open via awaitCancellation() — the WebSocket stays alive until
                // serverScope is cancelled below.
                serverScope.launch { cluster.start() }

                // ── Client: SeamRoomFactory join → derive NodeId → RaftNode ─────────
                //
                // Follows the same ordering discipline as RelayRoomTest:
                //   1. join() to get the Seam and Room
                //   2. Wait for the admit handshake on the client side (roster non-empty)
                //   3. Build learnerConfig from locally-known voter + Seam-derived clientNodeId
                //   4. Start the learner RaftNode — propose() blocks until leader starts sending
                //      AppendEntries (which happens after ServerCluster applies changeMembership)

                val clientScope = CoroutineScope(coroutineContext + Job())
                val clientRoom = SeamRoomFactory.systemClock(loom = clientLoom, scope = clientScope)
                    .join(
                        WebSocketAdvertisement(
                            url = "ws://localhost$serverPath",
                            serverPeerId = serverPeerId,
                            displayName = "cluster-client",
                        ),
                    )
                val clientSeam = clientRoom.channel("raft")

                // Derive the client's NodeId from the Seam selfId (UUID assigned at join time).
                // The ServerCluster admission loop derives the same NodeId from the room roster.
                val clientNodeId = NodeId(clientSeam.selfId.value)
                val learnerConfig = ClusterConfig(
                    voters = setOf(voterId),
                    learners = setOf(clientNodeId),
                )

                // Ensure the admit handshake is complete before starting the RaftNode.
                withTimeout(5.seconds) { clientRoom.roster.first { it.isNotEmpty() } }

                val clientNode = clientScope.raftNode(
                    clusterConfig = learnerConfig,
                    transport = SeamRaftTransport(clientSeam),
                    storage = InMemoryRaftStorage(),
                    raftConfig = raftCfg,
                )

                // Wrap in ClusterClient facade — this is what S3b-3 proves.
                val client: ClusterClient = clusterClientWithNode(clientNode)

                // Observe committed entries via the ClusterClient.committed surface.
                clientScope.launch {
                    client.committed
                        .first { it is Committed.Entry }
                        .let { committed ->
                            clientCommittedPayload.complete(
                                (committed as Committed.Entry).entry.command,
                            )
                        }
                }

                // ── Propose via ClusterClient ──────────────────────────────────────
                //
                // propose() forwards the command to the leader (the single voter),
                // which commits it and replicates it back to the learner via AppendEntries.
                //
                // propose() blocks internally until the leader is known. The leader becomes
                // known to the learner only after ServerCluster.start() applies changeMembership
                // and the leader begins sending AppendEntries — so no explicit synchronisation
                // between server and client is required.

                val command = "action:move=1".encodeToByteArray()
                withTimeout(15.seconds) { client.propose(command) }

                val serverPayload = withTimeout(10.seconds) { serverCommittedPayload.await() }
                val clientPayload = withTimeout(10.seconds) { clientCommittedPayload.await() }

                assertContentEquals(command, serverPayload, "server committed payload must match")
                assertContentEquals(command, clientPayload, "client committed payload must match")

                // ── Teardown ──────────────────────────────────────────────────────
                client.close()
                clientScope.cancel()
                cluster.close()
                serverScope.cancel()
            }
        }
}
