package us.tractat.kuilt.examples

import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.cluster.WebSocketVoter
import us.tractat.kuilt.cluster.voterMeshOverWebSockets
import us.tractat.kuilt.raft.Committed
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftConfig
import us.tractat.kuilt.websocket.KtorConnectionSource
import kotlin.coroutines.ContinuationInterceptor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end integration test for the **real inter-server voter seam** (#794 slice 4).
 *
 * ## What this proves
 *
 * Three voter [us.tractat.kuilt.raft.RaftNode]s wired over **real Ktor WebSocket sockets** — one
 * `meshSeam` of server-to-server connections per voter, via
 * [voterMeshOverWebSockets] — (a) elect a leader across the real voter mesh, and (b) replicate a
 * committed entry to **all three** voters over those sockets. This is the capability the in-process
 * `serverCluster` (voters over in-JVM `Channel`s) cannot demonstrate: the voter core surviving a
 * real network between servers.
 *
 * Unlike [ServerClusterM3E2ETest] — which puts a learner client on the real socket but keeps the
 * voter↔voter core in-process — here the **voter↔voter** links are the real ones. No client is
 * involved: a proposal is committed directly on the elected leader.
 *
 * ## Topology
 *
 * The three "servers" are three logical voters, each with its own [KtorConnectionSource]-mounted
 * route on the single test application. They dial each other over real loopback WebSockets by the
 * canonical rule ([voterMeshOverWebSockets]: lower [NodeId] dials higher), forming a K_3 mesh —
 * exactly the wiring that would span three JVMs on three machines, co-located here so one test can
 * drive it.
 *
 * ## Real-socket discipline
 *
 * [us.tractat.kuilt.raft.RaftNode] and the mesh read loops use real wall-clock delays. Every
 * [withTimeout] is a hard bound sized for cold CI runners; election timeouts are seeded so a leader
 * actually wins. There is no virtual time here — this is a real-socket test by construction.
 */
class VoterMeshOverSeamsE2ETest {

    private val voterA = NodeId("voter-a")
    private val voterB = NodeId("voter-b")
    private val voterC = NodeId("voter-c")

    private val pathA = "/ws/voter-seam-a"
    private val pathB = "/ws/voter-seam-b"
    private val pathC = "/ws/voter-seam-c"

    /**
     * Wider election windows than M=1 tests (100–200ms) — the K_3 voter mesh needs real loopback
     * round-trips for vote grants, and a seeded RNG so timeouts differ and one voter wins. Still
     * fast enough to finish well under the propose timeout.
     */
    private val raftCfg = RaftConfig(
        electionTimeoutMin = 100.milliseconds,
        electionTimeoutMax = 200.milliseconds,
        heartbeatInterval = 20.milliseconds,
        random = Random(794),
    )

    @Test
    fun `M=3 voters over real WebSocket seams — elect a leader and replicate a committed entry`() =
        testApplication {
            val dispatcher = currentCoroutineContext()[ContinuationInterceptor] as CoroutineDispatcher

            // Per-voter deferreds proving the entry replicates to every voter over the real sockets.
            val voterCommits = mapOf(
                voterA to CompletableDeferred<ByteArray>(),
                voterB to CompletableDeferred<ByteArray>(),
                voterC to CompletableDeferred<ByteArray>(),
            )

            coroutineScope {
                // ── Each voter's inbound accept route on the single test application ────
                val sourceA = KtorConnectionSource(application, pathA)
                val sourceB = KtorConnectionSource(application, pathB)
                val sourceC = KtorConnectionSource(application, pathC)

                val httpClient = createClient { install(WebSockets) }
                val serverScope = CoroutineScope(coroutineContext + Job())

                // ── Form the K_3 inter-server mesh over real loopback WebSockets ────────
                val mesh = serverScope.voterMeshOverWebSockets(
                    voters = listOf(
                        WebSocketVoter(voterA, sourceA, "ws://localhost$pathA"),
                        WebSocketVoter(voterB, sourceB, "ws://localhost$pathB"),
                        WebSocketVoter(voterC, sourceC, "ws://localhost$pathC"),
                    ),
                    httpClient = httpClient,
                    dispatcher = dispatcher,
                    raftConfig = raftCfg,
                    random = Random(794),
                )

                // Collect the first committed entry on each voter before proposing.
                mesh.voterNodes.forEach { (voterId, node) ->
                    serverScope.launch {
                        node.committed
                            .first { it is Committed.Entry }
                            .let { committed ->
                                voterCommits.getValue(voterId).complete(
                                    (committed as Committed.Entry).entry.command,
                                )
                            }
                    }
                }

                // ── (a) A leader is elected across the real voter mesh ─────────────────
                val leader = withTimeout(20.seconds) { mesh.awaitLeader() }

                // ── (b) A committed entry replicates to all three voters ───────────────
                val command = "action:voter-seam-move=1".encodeToByteArray()
                withTimeout(20.seconds) { leader.propose(command) }

                voterCommits.forEach { (voterId, deferred) ->
                    val payload = withTimeout(10.seconds) { deferred.await() }
                    assertContentEquals(
                        command,
                        payload,
                        "voter $voterId must commit the entry over the real seam",
                    )
                }

                // ── Teardown ────────────────────────────────────────────────────────────
                mesh.close()
                serverScope.cancel()
            }
        }
}
