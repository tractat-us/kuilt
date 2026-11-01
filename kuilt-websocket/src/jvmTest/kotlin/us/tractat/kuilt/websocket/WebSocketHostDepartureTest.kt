package us.tractat.kuilt.websocket

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers // ALLOW-realDispatcher: real-socket proof — a real Netty server and OkHttp client have no virtual-time option
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.session.MembershipEvent
import us.tractat.kuilt.session.Room
import us.tractat.kuilt.session.SeamRoomFactory
import us.tractat.kuilt.test.assertAll
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

/**
 * A host's `leave` over `LinkSeam` on a real localhost socket (#2670). `LinkSeam.close` does not drain
 * its outbox, so a frame sent and closed in one breath is usually lost here: 10 of 60 arrived when
 * measured, against 60 of 60 after a 5 ms pause. That makes this the sharpest of the real-fabric checks.
 *
 * Several trials because this is a race on real threads, and one run of it can win by luck: a `leave`
 * with no wait in it survived a single trial when measured. The window outlives the ceiling, so a
 * terminal event inside the ceiling can only have come from the frame.
 *
 * **Opt-in (`-Pserver.realnet.tests=true`), because it is that race.** Twenty straight wins of a real
 * 100 ms wait on a shared runner is not something `ci-required` should turn on, and the evidence for it
 * came from one laptop. `NwHostDepartureRoomTest` and `RoomConformanceSuite` keep a `leave` with no wait
 * red in the merge gate, without a real socket.
 */
class WebSocketHostDepartureTest {

    private companion object {
        const val TRIALS = 20
        const val PATH = "/ws/host-departure"
        val FORMATION_CEILING = 30.seconds
    }

    private val heartbeat = HeartbeatConfig(reconnectWindow = 2.minutes)
    private val clock = { Clock.System.now() }

    @BeforeTest
    fun setUp() {
        Assume.assumeTrue(
            "Skipped: set -Pserver.realnet.tests=true to run real-network integration tests",
            System.getProperty("server.realnet.tests") == "true",
        )
    }

    @Test
    fun aJoinerLearnsOfTheHostsLeaveOverARealSocket() = runBlocking {
        withContext(Dispatchers.Default) {
            var ran = 0
            val outcomes = List(TRIALS) {
                ran += 1
                oneDeparture()
            }
            assertAll(
                { assertEquals(TRIALS, ran, "every trial ran") },
                { assertEquals(List(TRIALS) { "HostLeft" }, outcomes, "the terminal reason, trial by trial") },
            )
        }
    }

    /**
     * `KtorRoomHost`'s documented shutdown: its accept scope is cancelled, so the room's own scope is too,
     * and the frame cannot go through a lane writer. The in-memory half of this is
     * `HostDepartureTest.aHostShutDownByCancellingItsScopeStillSaysSo`; this is the same path over the seam
     * this host really uses.
     */
    @Test
    fun aJoinerLearnsOfAKtorRoomHostShutDownByItsScope() = runBlocking {
        withContext(Dispatchers.Default) {
            var ran = 0
            val outcomes = List(TRIALS) {
                ran += 1
                oneScopeCancelledDeparture()
            }
            assertAll(
                { assertEquals(TRIALS, ran, "every trial ran") },
                { assertEquals(List(TRIALS) { "HostLeft" }, outcomes, "the terminal reason, trial by trial") },
            )
        }
    }

    /** One real room, one host `leave`: the joiner's terminal reason, or what it saw instead. */
    private suspend fun oneDeparture(): String {
        val context = currentCoroutineContext()
        val scope = CoroutineScope(context + SupervisorJob(context[Job]))
        lateinit var serverLoom: KtorServerLoom
        val server = embeddedServer(Netty, port = 0) { serverLoom = KtorServerLoom(this, PATH) }
        server.start(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        val client = HttpClient(OkHttp) { install(ClientWebSockets) }
        try {
            val (host, joiner) = withTimeout(FORMATION_CEILING) {
                coroutineScope {
                    val h = async { SeamRoomFactory(serverLoom, scope, clock, heartbeat).host(Pattern("room")) }
                    val j = async {
                        SeamRoomFactory(KtorClientLoom(client), scope, clock, heartbeat).join(
                            WebSocketAdvertisement("ws://localhost:$port$PATH", serverLoom.selfPeerId, "room"),
                        )
                    }
                    h.await() to j.await()
                }
            }
            withTimeout(FORMATION_CEILING) { host.roster.first { it.size == 1 } }
            withTimeout(FORMATION_CEILING) { joiner.roster.first { it.size == 1 } }
            return outcomeOf(joiner, scope) { host.leave() }
        } finally {
            scope.cancel()
            client.close()
            server.stop(0, 100)
        }
    }

    /** One real `KtorRoomHost`, one joiner, one cancelled accept scope: the joiner's terminal reason. */
    private suspend fun oneScopeCancelledDeparture(): String {
        val context = currentCoroutineContext()
        val scope = CoroutineScope(context + SupervisorJob(context[Job]))
        val serverPeerId = PeerId("ktor-room-host")
        lateinit var roomHost: KtorRoomHost
        val server = embeddedServer(Netty, port = 0) {
            roomHost = KtorRoomHost(this, PATH, serverPeerId, Pattern("room"))
        }
        server.start(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        val client = HttpClient(OkHttp) { install(ClientWebSockets) }
        try {
            val handedOver = CompletableDeferred<Room>()
            val hosting = scope.launch {
                roomHost.start { room ->
                    handedOver.complete(room)
                    awaitCancellation()
                }
            }
            val joiner = withTimeout(FORMATION_CEILING) {
                SeamRoomFactory(KtorClientLoom(client), scope, clock, heartbeat).join(
                    WebSocketAdvertisement("ws://localhost:$port$PATH", serverPeerId, "room"),
                )
            }
            val host = withTimeout(FORMATION_CEILING) { handedOver.await() }
            withTimeout(FORMATION_CEILING) { host.roster.first { it.size == 1 } }
            withTimeout(FORMATION_CEILING) { joiner.roster.first { it.size == 1 } }
            return outcomeOf(joiner, scope) { hosting.cancelAndJoin() }
        } finally {
            scope.cancel()
            client.close()
            server.stop(0, 100)
        }
    }

    /** Subscribe to [joiner], run [depart], and report the terminal reason or what it saw instead. */
    private suspend fun outcomeOf(joiner: Room, scope: CoroutineScope, depart: suspend () -> Unit): String {
        val events = mutableListOf<MembershipEvent>()
        val terminal = CompletableDeferred<MembershipEvent.HostLost>()
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            joiner.events.collect { event ->
                events += event
                if (event is MembershipEvent.HostLost) terminal.complete(event)
            }
        }

        depart()

        val lost = withTimeoutOrNull(10.seconds) { terminal.await() }
        val windowOpened = events.any { it is MembershipEvent.WindowOpened }
        return when {
            lost == null -> "no terminal event: $events"
            windowOpened -> "${lost.reason} after a window: $events"
            else -> lost.reason.toString()
        }
    }
}
