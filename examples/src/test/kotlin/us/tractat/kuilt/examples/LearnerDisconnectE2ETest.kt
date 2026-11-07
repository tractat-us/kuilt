package us.tractat.kuilt.examples

import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.cluster.ClusterClient
import us.tractat.kuilt.cluster.ClusterEndpoints
import us.tractat.kuilt.cluster.ServerCluster
import us.tractat.kuilt.cluster.clusterClient
import us.tractat.kuilt.cluster.serverCluster
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.raft.ClusterConfig
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftConfig
import us.tractat.kuilt.websocket.KtorClientLoom
import us.tractat.kuilt.websocket.KtorRoomHost
import us.tractat.kuilt.websocket.WebSocketAdvertisement
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A learner whose client goes away is removed from the config once its grace runs out (#3127), over
 * the production relay: a real [KtorRoomHost] and [KtorClientLoom] on Ktor's in-process test engine.
 *
 * The removal is driven only by the connection closing, as in production. No test code cancels the
 * server's per-connection `onRoom` coroutine; before #3127 nothing in production did either, so the
 * admission never ended, its spoke was never withdrawn and the grace never started.
 *
 * Two ways a client goes away: it closes cleanly ([ClusterClient.close], which sends its goodbye), or
 * its owning scope is cancelled with no close at all, which drops the socket.
 */
class LearnerDisconnectE2ETest {

    private val path = "/ws/learner-disconnect"
    private val serverPeerId = PeerId("learner-disconnect-relay")
    private val roomPattern = Pattern("learner-disconnect-room")

    private val voters = listOf(NodeId("disconnect-voter-a"), NodeId("disconnect-voter-b"), NodeId("disconnect-voter-c"))
    private val leavingId = NodeId("disconnect-client-leaving")

    private val grace = 500.milliseconds

    private val raftCfg = RaftConfig(
        electionTimeoutMin = 100.milliseconds,
        electionTimeoutMax = 200.milliseconds,
        heartbeatInterval = 20.milliseconds,
        random = Random(3127),
    )

    @Test
    fun `a learner whose client closes is removed after its grace`() = disconnectTest { _, client ->
        client.close()
    }

    @Test
    fun `a learner whose client scope is cancelled without closing is removed after its grace`() =
        disconnectTest { clientScope, _ -> clientScope.cancel() }

    /**
     * Admit [leavingId], let [goAway] end its client, then wait for the leader's learner set to drop
     * it. The bound is the grace plus slack for the leave to cross the relay and the removal to commit;
     * on `main` before #3127 the learner is never removed, however long the wait.
     */
    private fun disconnectTest(goAway: suspend (CoroutineScope, ClusterClient) -> Unit) = testApplication {
        val host = KtorRoomHost(application, path, serverPeerId, roomPattern)
        coroutineScope {
            val serverScope = CoroutineScope(coroutineContext + Job())
            val cluster = serverScope.serverCluster(
                host = host,
                voterIds = voters,
                raftConfig = raftCfg,
                learnerGrace = grace,
            )
            val clientScope = CoroutineScope(coroutineContext + Job())
            try {
                serverScope.launch { cluster.start() }
                withTimeout(20.seconds) { cluster.awaitLeader() }

                val client = connect(clientScope)
                withTimeout(20.seconds) { client.propose("hello".encodeToByteArray()) }
                awaitLeaderLearners(cluster, "the client is admitted") { leavingId in it }

                goAway(clientScope, client)

                awaitLeaderLearners(cluster, "the departed learner is removed after its grace") { leavingId !in it }
            } finally {
                // On failure too, so a red run does not leave a live cluster beating for the rest of the JVM.
                clientScope.cancel()
                cluster.close()
                serverScope.cancel()
            }
        }
    }

    private fun ApplicationTestBuilder.connect(scope: CoroutineScope): ClusterClient = scope.clusterClient(
        loom = KtorClientLoom(httpClient = createClient { install(WebSockets) }, selfPeerId = PeerId(leavingId.value)),
        clusterEndpoints = ClusterEndpoints(listOf(WebSocketAdvertisement("ws://localhost$path", serverPeerId, "client"))),
        clientNodeId = leavingId,
        clusterConfig = ClusterConfig(voters = voters.toSet(), learners = setOf(leavingId)),
        raftConfig = raftCfg,
        clock = { Clock.System.now() },
    )

    /** Poll the current leader's learner set until [holds]; fails naming [what] after a bounded wait. */
    private suspend fun awaitLeaderLearners(cluster: ServerCluster, what: String, holds: (Set<NodeId>) -> Boolean) {
        val reached = withTimeoutOrNull(grace + 15.seconds) {
            while (!holds(cluster.awaitLeader().membership.value.learners)) delay(20.milliseconds)
            true
        }
        assertNotNull(
            reached,
            "$what: timed out; leader learners are ${cluster.awaitLeader().membership.value.learners}",
        )
    }
}
