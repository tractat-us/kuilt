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
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end **cross-relay failover** test for [ClusterClient] over **real Ktor WebSocket
 * sockets**, part of epic #485 (closes #544).
 *
 * ## What this proves
 *
 * Two relay endpoints ([KtorRoomHost] A and B, distinct `serverPeerId`s and paths) front
 * **one shared M=3 voter mesh** + one [us.tractat.kuilt.cluster.ServerCluster] /
 * `LearnerRouter`. A production [clusterClient] with a pinned `selfPeerId`:
 *
 * 1. Connects to relay A → proposes → commits.
 * 2. Relay A is killed (its accept-loop scope is cancelled — its rooms tear; the **voter
 *    mesh and relay B keep running**).
 * 3. The client's reconnect loop rotates to relay B and re-admits **keeping the same
 *    `clientNodeId`**.
 * 4. A subsequent propose commits — on the **same Raft log** (the new entry's index is the
 *    successor of the pre-failover entry, and both replicate to the voters).
 *
 * ## Why this needs the single-relay-peer addressing fix (#544)
 *
 * Leadership is **not** moved on failover: it stays on whichever voter the election picked
 * (a still-alive voter — only relay A's *host* died, not a voter). The client re-admitted on
 * relay B forwards proposals to the leader through relay B, even though relay B's
 * `serverPeerId` need not align with the leader voter. That works because
 * [us.tractat.kuilt.cluster.ManagedRaftTransport] addresses its single relay peer and the
 * shared `LearnerRouter` routes on to the current leader. Without that fix the post-failover
 * propose would address an absent peer and hang.
 *
 * ## Real-socket discipline
 *
 * [KtorRoomHost] and [us.tractat.kuilt.raft.RaftNode] use real wall-clock delays. Every
 * [withTimeout] is a hard bound sized conservatively for cold CI runners; the post-failover
 * leg is given a wider budget (reconnect + re-admit + commit).
 */
class ClusterClientFailoverE2ETest {

    private val pathA = "/ws/failover-relay-a"
    private val pathB = "/ws/failover-relay-b"
    private val serverPeerIdA = PeerId("cluster-relay-a")
    private val serverPeerIdB = PeerId("cluster-relay-b")
    private val roomPattern = Pattern("failover-e2e-room")

    // M=3 voters. Two are "aligned" with a relay's serverPeerId; the third is unaligned.
    private val voterA = NodeId(serverPeerIdA.value)
    private val voterB = NodeId(serverPeerIdB.value)
    private val voterC = NodeId("cluster-voter-c")

    /** M=3 real-socket election timing — wide enough to converge on a cold CI runner. */
    private val raftCfg = RaftConfig(
        electionTimeoutMin = 100.milliseconds,
        electionTimeoutMax = 200.milliseconds,
        heartbeatInterval = 20.milliseconds,
        random = Random(544),
    )

    @Test
    fun `client fails over to a surviving relay and keeps committing on the same log`() =
        testApplication {
            val clientNodeId = NodeId("cluster-client-failover")

            // Two relay front-ends on the same test application, distinct paths + identities.
            val hostA = KtorRoomHost(application, pathA, serverPeerIdA, roomPattern)
            val hostB = KtorRoomHost(application, pathB, serverPeerIdB, roomPattern)

            // Pin the loom's selfPeerId to clientNodeId so the client keeps a stable wire
            // identity across the failover reconnect (the #544 prerequisite).
            val clientLoom = KtorClientLoom(
                httpClient = createClient { install(WebSockets) },
                selfPeerId = PeerId(clientNodeId.value),
            )
            val advA = WebSocketAdvertisement("ws://localhost$pathA", serverPeerIdA, "client")
            val advB = WebSocketAdvertisement("ws://localhost$pathB", serverPeerIdB, "client")

            // A voter's committed stream, to prove both commands replicate to the shared mesh.
            val voterCmd1 = CompletableDeferred<ByteArray>()
            val voterCmd2 = CompletableDeferred<ByteArray>()

            coroutineScope {
                // ── One shared voter mesh + cluster, two relay hosts ──────────────────
                val serverScope = CoroutineScope(coroutineContext + Job())
                val cluster = serverScope.serverCluster(
                    host = hostA,
                    voterIds = listOf(voterA, voterB, voterC),
                    raftConfig = raftCfg,
                )

                // Observe one voter's committed log (voterC is unaligned with either relay).
                serverScope.launch {
                    var seen = 0
                    cluster.voterNodes.getValue(voterC).committed
                        .collect { committed ->
                            if (committed is Committed.Entry) {
                                when (seen++) {
                                    0 -> voterCmd1.complete(committed.entry.command)
                                    1 -> voterCmd2.complete(committed.entry.command)
                                }
                            }
                        }
                }

                // Relay A in its own scope (killable independently); relay B in another.
                val relayScopeA = CoroutineScope(coroutineContext + Job())
                val relayScopeB = CoroutineScope(coroutineContext + Job())
                relayScopeA.launch { cluster.start() }          // mounts hostA
                relayScopeB.launch { cluster.runRelay(hostB) }  // mounts hostB

                // A leader must exist before admission's awaitLeader/changeMembership runs.
                withTimeout(20.seconds) { cluster.awaitLeader() }

                // ── Client: production extension over [advA, advB], round-robin from A ──
                val clientScope = CoroutineScope(coroutineContext + Job())
                val clusterConfig = ClusterConfig(
                    voters = setOf(voterA, voterB, voterC),
                    learners = setOf(clientNodeId),
                )
                val client: ClusterClient = clientScope.clusterClient(
                    loom = clientLoom,
                    clusterEndpoints = ClusterEndpoints(listOf(advA, advB)),
                    clientNodeId = clientNodeId,
                    clusterConfig = clusterConfig,
                    raftConfig = raftCfg,
                    clock = { Clock.System.now() },
                )

                // ── Leg 1: connect to relay A → propose → commit ──────────────────────
                val cmd1 = "action:before-failover".encodeToByteArray()
                val entry1 = withTimeout(20.seconds) { client.propose(cmd1) }
                assertContentEquals(cmd1, entry1.command, "leg-1 commit payload")

                // ── Kill relay A: cancel its scope → rooms tear → client seam tears ───
                relayScopeA.cancel()

                // ── Leg 2: client rotates to relay B, re-admits, proposes → commit ────
                // A propose issued during the reconnect gap forwards to a torn seam and is
                // lost (the engine does not auto-resend a forward it believed delivered).
                // The real-client contract is to RETRY with the same requestId until it
                // lands on the surviving relay; the pinned requestId makes it exactly-once
                // (any duplicate coalesces via DedupKey). This is the #532 "fresh-join +
                // dedup intact" failover path.
                val cmd2 = "action:after-failover".encodeToByteArray()
                val failoverRequestId = 7777L
                val entry2 = withTimeout(40.seconds) {
                    var committed: us.tractat.kuilt.raft.LogEntry? = null
                    while (committed == null) {
                        committed = try {
                            withTimeout(3.seconds) { client.propose(cmd2, requestId = failoverRequestId) }
                        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                            null // reconnect/re-admit still in flight — retry the same requestId
                        }
                    }
                    committed
                }
                assertContentEquals(cmd2, entry2.command, "leg-2 commit payload")

                // Same shared log: the post-failover entry is the successor of the pre one.
                assertTrue(
                    entry2.index > entry1.index,
                    "post-failover commit (${entry2.index}) extends the same log past ${entry1.index}",
                )

                // Both commands replicated to the shared voter mesh.
                assertContentEquals(cmd1, withTimeout(10.seconds) { voterCmd1.await() }, "voter sees cmd1")
                assertContentEquals(cmd2, withTimeout(10.seconds) { voterCmd2.await() }, "voter sees cmd2")

                // ── Teardown ──────────────────────────────────────────────────────────
                client.close()
                clientScope.cancel()
                cluster.close()
                serverScope.cancel()
                relayScopeB.cancel()
            }
        }
}
