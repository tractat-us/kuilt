package us.tractat.kuilt.examples

import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.cluster.ClusterClient
import us.tractat.kuilt.cluster.ClusterEndpoints
import us.tractat.kuilt.cluster.clusterClient
import us.tractat.kuilt.cluster.serverCluster
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.identity.InMemoryDeviceKeyStore
import us.tractat.kuilt.raft.ClientId
import us.tractat.kuilt.raft.AutoSerialRefusedException
import us.tractat.kuilt.raft.ClientIdCollisionException
import us.tractat.kuilt.raft.ClientIdentity
import us.tractat.kuilt.raft.ClusterConfig
import us.tractat.kuilt.raft.Committed
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftConfig
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.websocket.KtorClientLoom
import us.tractat.kuilt.websocket.KtorRoomHost
import us.tractat.kuilt.websocket.WebSocketAdvertisement
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A real [clusterClient] restarted under a durable id, end to end through a real [serverCluster].
 * It keeps its log in memory and cannot be told otherwise, so every restart brings its own history
 * back over replication, and the floor its caller hands over is all that says the history is its own
 * (#3060).
 *
 * `MultiNodeRaftSim` cannot stand in here: the subject is the client's own wiring, room join and relay
 * included. The WebSocket runs in-process under `testApplication`, so no loopback upgrade is involved.
 * The clock is real, and every [withTimeout] is a backstop rather than a measure.
 *
 * Deterministic arms for the same contract, over the wiped learner this models, are in
 * `DurableIdentityRestartTest` in `:kuilt-raft`.
 */
class ClusterClientDurableRestartE2ETest {

    private val path = "/ws/durable-restart"
    private val serverPeerId = PeerId("durable-restart-server")
    private val voterId = NodeId(serverPeerId.value)
    private val clientNodeId = NodeId("durable-restart-client")
    private val clientId = ClientId("durable-restart-client-id")
    private val clusterConfig = ClusterConfig(voters = setOf(voterId), learners = setOf(clientNodeId))
    private val backstop = 20.seconds

    private val raftCfg = RaftConfig(
        electionTimeoutMin = 5.milliseconds,
        electionTimeoutMax = 10.milliseconds,
        heartbeatInterval = 2.milliseconds,
        random = Random(3060),
    )

    @Test
    fun `a restarted client takes its own history from its floor and proposes above it`() = withRestartedClient(floor = 2L) { rig ->
        val failure = rig.replayOutcome(entries = 2)
        assertNull(failure, "no failure across the catch-up: $failure")

        // The one-argument form cannot be covered by a floor, and the client surfaces the node's refusal.
        val refusal = assertFailsWith<AutoSerialRefusedException> { rig.client.propose("not-pinned".encodeToByteArray()) }
        val next = withTimeout(backstop) { rig.client.propose("cmd-3".encodeToByteArray(), requestId = 3L) }

        assertAll(
            { assertContains(refusal.message.orEmpty(), "propose(command, requestId)") },
            { assertEquals(3L, next.dedupKey?.requestId) },
            { assertEquals(listOf(1L, 2L), rig.walked.value.take(2), "the rig: replication brought the history back") },
        )
    }

    @Test
    fun `a restarted client with a stale floor fails on its own history`() = withRestartedClient(floor = 1L) { rig ->
        val failure = rig.replayOutcome(entries = 2)

        assertIs<ClientIdCollisionException>(failure, "the serial above the floor must read as a collision: $failure")
        assertContains(failure.message.orEmpty(), "foreign serial 2", message = "the serial it names")
    }

    /** Issues serials 1 and 2 from a first incarnation, then boots a second under [floor] and runs [body]. */
    private fun withRestartedClient(floor: Long, body: suspend (RestartedClient) -> Unit) = testApplication {
        val host = KtorRoomHost(
            application = application,
            path = path,
            serverPeerId = serverPeerId,
            pattern = Pattern("durable-restart-room"),
            deviceKeyStore = InMemoryDeviceKeyStore(),
        )
        coroutineScope {
            val serverScope = CoroutineScope(coroutineContext + Job())
            val cluster = serverScope.serverCluster(host = host, voterIds = listOf(voterId), raftConfig = raftCfg)
            serverScope.launch { cluster.start() }

            val firstScope = CoroutineScope(coroutineContext + Job())
            val first = firstScope.boot(this@testApplication, floor = 0L)
            (1L..2L).forEach { serial ->
                withTimeout(backstop) { first.propose("cmd-$serial".encodeToByteArray(), requestId = serial) }
            }
            first.close()
            firstScope.cancel()

            val failure = CompletableDeferred<Throwable>()
            val handler = CoroutineExceptionHandler { _, thrown -> failure.complete(thrown) }
            val secondScope = CoroutineScope(coroutineContext + Job() + handler)
            val second = secondScope.boot(this@testApplication, floor)
            val walked = MutableStateFlow<List<Long>>(emptyList())
            secondScope.launch {
                second.committed.collect { committed ->
                    if (committed is Committed.Entry) {
                        committed.entry.dedupKey?.let { key -> walked.update { it + key.requestId } }
                    }
                }
            }

            body(RestartedClient(second, walked, failure))

            second.close()
            secondScope.cancel()
            cluster.close()
            serverScope.cancel()
        }
    }

    private fun CoroutineScope.boot(test: ApplicationTestBuilder, floor: Long): ClusterClient = clusterClient(
        loom = KtorClientLoom(
            httpClient = test.createClient { install(WebSockets) },
            selfPeerId = PeerId(clientNodeId.value),
        ),
        clusterEndpoints = ClusterEndpoints(
            listOf(
                WebSocketAdvertisement(
                    url = "ws://localhost$path",
                    serverPeerId = serverPeerId,
                    sessionName = "durable-restart",
                ),
            ),
        ),
        clientNodeId = clientNodeId,
        clusterConfig = clusterConfig,
        raftConfig = raftCfg,
        deviceKeyStore = InMemoryDeviceKeyStore(),
        identity = ClientIdentity.Durable(clientId, issuedThrough = floor),
        clock = { Clock.System.now() },
    )

    private inner class RestartedClient(
        val client: ClusterClient,
        val walked: MutableStateFlow<List<Long>>,
        private val failure: CompletableDeferred<Throwable>,
    ) {
        /** Ends on the first of: the client surfaced [entries] keyed entries, or its node failed. */
        suspend fun replayOutcome(entries: Int): Throwable? = withTimeout(backstop) {
            val replayed = async { walked.first { it.size >= entries } }
            try {
                select {
                    failure.onAwait { it }
                    replayed.onAwait { null }
                }
            } finally {
                replayed.cancel()
            }
        }
    }
}
