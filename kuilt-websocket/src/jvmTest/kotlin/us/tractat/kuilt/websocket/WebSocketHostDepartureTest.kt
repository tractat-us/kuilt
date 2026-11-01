package us.tractat.kuilt.websocket

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers // ALLOW-realDispatcher: real-socket proof — a real Netty server and OkHttp client have no virtual-time option
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.session.MembershipEvent
import us.tractat.kuilt.session.SeamRoomFactory
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
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
 */
class WebSocketHostDepartureTest {

    private companion object {
        const val TRIALS = 20
        const val PATH = "/ws/host-departure"
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

    /** One real room, one host `leave`: the joiner's terminal reason, or what it saw instead. */
    private suspend fun oneDeparture(): String {
        val context = currentCoroutineContext()
        val scope = CoroutineScope(context + SupervisorJob(context[Job]))
        lateinit var serverLoom: KtorServerLoom
        val server = embeddedServer(Netty, port = 0) { serverLoom = KtorServerLoom(this, PATH) }
        server.start(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        val client = HttpClient(OkHttp) { install(ClientWebSockets) }
        val heartbeat = HeartbeatConfig(reconnectWindow = 2.minutes)
        val clock = { Clock.System.now() }
        try {
            val (host, joiner) = coroutineScope {
                val h = async { SeamRoomFactory(serverLoom, scope, clock, heartbeat).host(Pattern("room")) }
                val j = async {
                    SeamRoomFactory(KtorClientLoom(client), scope, clock, heartbeat).join(
                        WebSocketAdvertisement("ws://localhost:$port$PATH", serverLoom.selfPeerId, "room"),
                    )
                }
                h.await() to j.await()
            }
            withTimeout(30.seconds) { host.roster.first { it.size == 1 } }
            withTimeout(30.seconds) { joiner.roster.first { it.size == 1 } }
            val events = mutableListOf<MembershipEvent>()
            val terminal = CompletableDeferred<MembershipEvent.HostLost>()
            scope.launch {
                joiner.events.collect { event ->
                    events += event
                    if (event is MembershipEvent.HostLost) terminal.complete(event)
                }
            }
            delay(5.milliseconds) // the events flow does not replay, so be subscribed before the frame lands

            host.leave()

            val lost = withTimeoutOrNull(10.seconds) { terminal.await() }
            val windowOpened = events.any { it is MembershipEvent.WindowOpened }
            return when {
                lost == null -> "no terminal event: $events"
                windowOpened -> "${lost.reason} after a window: $events"
                else -> lost.reason.toString()
            }
        } finally {
            scope.cancel()
            client.close()
            server.stop(0, 100)
        }
    }
}
