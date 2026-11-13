package us.tractat.kuilt.examples

import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.cluster.ClusterClient
import us.tractat.kuilt.cluster.ClusterEndpoints
import us.tractat.kuilt.cluster.clusterClient
import us.tractat.kuilt.cluster.serverCluster
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.identity.InMemoryDeviceKeyStore
import us.tractat.kuilt.raft.ClusterConfig
import us.tractat.kuilt.raft.Committed
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftConfig
import us.tractat.kuilt.websocket.KtorClientLoom
import us.tractat.kuilt.websocket.KtorRoomHost
import us.tractat.kuilt.websocket.WebSocketAdvertisement
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end regression test for concurrent learner admission (#561, #3120).
 *
 * Two clients connect to a real M=3 cluster **at the same time** — both `clusterClient`
 * calls are launched concurrently before either has proposed anything. Two defects have lived
 * here. #561: the second admission's `MembershipChangeInProgressException` was swallowed, so that
 * learner was never admitted. Its fix retried the stale target, which #3120 found evicting the
 * other learner instead. Today no connection changes membership: each voter's learner reconciler
 * adds every registered learner from the leader's current membership.
 *
 * Each client's own proposal commits even after it has been evicted, so committing proves
 * little. What this asserts is that each client applies the **other's** command.
 */
class ConcurrentAdmissionE2ETest {

    private val path = "/ws/concurrent-admission"
    private val serverPeerId = PeerId("concurrent-admission-relay")
    private val roomPattern = Pattern("concurrent-admission-room")

    private val voterA = NodeId("concurrent-voter-a")
    private val voterB = NodeId("concurrent-voter-b")
    private val voterC = NodeId("concurrent-voter-c")

    private val clientAlphaId = NodeId("concurrent-client-alpha")
    private val clientBetaId = NodeId("concurrent-client-beta")

    /** M=3 real-socket election timing — wide enough to converge on a cold CI runner. */
    private val raftCfg = RaftConfig(
        electionTimeoutMin = 100.milliseconds,
        electionTimeoutMax = 200.milliseconds,
        heartbeatInterval = 20.milliseconds,
        random = Random(561),
    )

    @Test
    fun `two clients admitted concurrently both commit their proposals`() = testApplication {
        val adv = WebSocketAdvertisement("ws://localhost$path", serverPeerId, "client")

        val alphaLoom = KtorClientLoom(
            httpClient = createClient { install(WebSockets) },
            selfPeerId = PeerId(clientAlphaId.value),
        )
        val betaLoom = KtorClientLoom(
            httpClient = createClient { install(WebSockets) },
            selfPeerId = PeerId(clientBetaId.value),
        )

        val host = KtorRoomHost(application, path, serverPeerId, roomPattern, InMemoryDeviceKeyStore())

        coroutineScope {
            val serverScope = CoroutineScope(coroutineContext + Job())
            val cluster = serverScope.serverCluster(
                host = host,
                voterIds = listOf(voterA, voterB, voterC),
                raftConfig = raftCfg,
            )

            serverScope.launch { cluster.start() }
            withTimeout(20.seconds) { cluster.awaitLeader() }

            // Both clients connect concurrently — neither waits for the other to be
            // fully admitted before connecting, so their admissions race.
            val alphaScope = CoroutineScope(coroutineContext + Job())
            val betaScope = CoroutineScope(coroutineContext + Job())

            val bothVoters = setOf(voterA, voterB, voterC)
            val clientAlpha = alphaScope.clusterClient(
                loom = alphaLoom,
                clusterEndpoints = ClusterEndpoints(listOf(adv)),
                clientNodeId = clientAlphaId,
                clusterConfig = ClusterConfig(voters = bothVoters, learners = setOf(clientAlphaId)),
                raftConfig = raftCfg,
                deviceKeyStore = InMemoryDeviceKeyStore(),
                clock = { Clock.System.now() },
            )
            val clientBeta = betaScope.clusterClient(
                loom = betaLoom,
                clusterEndpoints = ClusterEndpoints(listOf(adv)),
                clientNodeId = clientBetaId,
                clusterConfig = ClusterConfig(voters = bothVoters, learners = setOf(clientBetaId)),
                raftConfig = raftCfg,
                deviceKeyStore = InMemoryDeviceKeyStore(),
                clock = { Clock.System.now() },
            )

            // The only collector of each client's committed stream; started before any proposal so
            // nothing is missed.
            val alphaSeen = alphaScope.appliedCommandsOf(clientAlpha)
            val betaSeen = betaScope.appliedCommandsOf(clientBeta)

            // Launch both proposals concurrently — if either learner was never admitted
            // to cluster membership its propose hangs forever (the leader never sends
            // AppendEntries for it), and the withTimeout here will catch that.
            val alphaDeferred = async {
                withTimeout(30.seconds) {
                    clientAlpha.propose("alpha:concurrent".encodeToByteArray())
                }
            }
            val betaDeferred = async {
                withTimeout(30.seconds) {
                    clientBeta.propose("beta:concurrent".encodeToByteArray())
                }
            }

            val alphaEntry = alphaDeferred.await()
            val betaEntry = betaDeferred.await()

            assertTrue(alphaEntry.index > 0, "alpha committed at log index ${alphaEntry.index}")
            assertTrue(betaEntry.index > 0, "beta committed at log index ${betaEntry.index}")

            // Each client is still receiving: it has applied the other's command.
            val both = setOf("alpha:concurrent", "beta:concurrent")
            withTimeout(20.seconds) { alphaSeen.first { it.containsAll(both) } }
            withTimeout(20.seconds) { betaSeen.first { it.containsAll(both) } }

            clientAlpha.close()
            clientBeta.close()
            alphaScope.cancel()
            betaScope.cancel()
            cluster.close()
            serverScope.cancel()
        }
    }

    /** The application commands [client] has applied, config entries skipped; its only collector. */
    private fun CoroutineScope.appliedCommandsOf(client: ClusterClient): StateFlow<Set<String>> {
        val seen = MutableStateFlow(emptySet<String>())
        launch(start = CoroutineStart.UNDISPATCHED) {
            client.committed.collect { committed ->
                if (committed is Committed.Entry && committed.entry.config == null) {
                    seen.update { it + committed.entry.command.decodeToString() }
                }
            }
        }
        return seen
    }
}
