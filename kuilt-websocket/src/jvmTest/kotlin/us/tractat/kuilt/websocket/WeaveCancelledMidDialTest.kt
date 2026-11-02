package us.tractat.kuilt.websocket

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers // ALLOW-realDispatcher: real sockets and a real client, whose dial must run while the caller's thread is held
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.server.websocket.WebSockets as ServerWebSockets

/**
 * Cancelling a weave mid-dial leaves no socket behind (#3044).
 *
 * Ktor's `webSocketSession` runs the handshake on the [HttpClient]'s own scope, so a cancel ends the
 * caller's wait and nothing else. Every test here lets the far end report what the socket did, and
 * every window is forced rather than raced:
 *
 *  - **Before the upgrade answers.** [HeldHandshakeServer] has read the request and is holding the
 *    `101`, so the client cannot have a session yet. The cancel lands, and only then does the far end
 *    answer.
 *  - **As the session arrives.** The caller's thread is held while the handshake finishes, until
 *    [HeldCallerThread] has seen the dial's result queued for it. The cancel lands then, so the caller
 *    takes the cancellation instead of a session already delivered to it, which is the one window that
 *    aborting the request cannot reach.
 *
 * Time is real and bounded. A bound here is only ever waited out on a red: a green reaches the far
 * end's verdict in well under a second, so [OBSERVATION] can be generous enough for a loaded box.
 */
class WeaveCancelledMidDialTest {

    private val rigScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val server = HeldHandshakeServer(OBSERVATION)
    private val clients = mutableListOf<HttpClient>()
    private val callerThread = HeldCallerThread()
    private var nettyServer: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null

    @AfterTest
    fun tearDown() {
        callerThread.close()
        rigScope.cancel()
        server.close()
        clients.forEach { it.close() }
        nettyServer?.stop(gracePeriodMillis = 100, timeoutMillis = 1_000)
    }

    private fun client(engine: HttpClientEngineFactory<*> = CIO): HttpClient =
        HttpClient(engine) { install(ClientWebSockets) }.also { clients += it }

    private fun advertisement(url: String = server.url) =
        WebSocketAdvertisement(url = url, serverPeerId = FAR_END, sessionName = "held")

    private fun clientLoom(httpClient: HttpClient): Loom =
        KtorClientLoom(httpClient, Dispatchers.Default, selfPeerId = SELF)

    private fun meshLoom(httpClient: HttpClient): Loom =
        KtorMeshClientLoom(httpClient, Dispatchers.Default, selfPeerId = SELF, random = Random(1))

    // ── Cancelled before the upgrade answers ─────────────────────────────────────────────────────

    private suspend fun assertAbandonedDialClosesTheSocket(loom: Loom) {
        val weave = rigScope.launch { loom.join(advertisement()) }
        server.awaitRequest()
        assertFalse(weave.isCompleted, "rig: the weave finished before the far end answered, so nothing is in flight")

        weave.cancelAndJoin()
        server.finishHandshake()

        assertTheSocketClosed(server.farEnd())
    }

    @Test
    fun aKtorClientLoomWeaveCancelledBeforeTheUpgradeAnswersLeavesNoSocket() = realTime {
        assertAbandonedDialClosesTheSocket(clientLoom(client()))
    }

    @Test
    fun aKtorClientLoomWeaveCancelledBeforeTheUpgradeAnswersLeavesNoSocketOnOkHttp() = realTime {
        assertAbandonedDialClosesTheSocket(clientLoom(client(OkHttp)))
    }

    @Test
    fun aKtorMeshClientLoomWeaveCancelledBeforeTheUpgradeAnswersLeavesNoSocket() = realTime {
        assertAbandonedDialClosesTheSocket(meshLoom(client()))
    }

    // ── Cancelled as the session arrives ─────────────────────────────────────────────────────────

    /**
     * Cancels the weave with the session already handed over and the caller not yet running.
     *
     * [requestSeen] and [finishHandshake] are the far end's: the first returns once the dial is waiting
     * on the answer, the second lets it through. Holding the caller only after the first, and finishing
     * only after the hold, means the dial's result can only land in the held queue.
     */
    private suspend fun cancelAsTheSessionArrives(
        loom: Loom,
        url: String,
        requestSeen: suspend () -> Unit,
        finishHandshake: () -> Unit,
    ) {
        var woven: Seam? = null
        val weave = rigScope.launch(callerThread) { woven = loom.join(advertisement(url)) }
        requestSeen()
        callerThread.hold()
        val queuedWithTheCallerHeld = callerThread.dispatches

        finishHandshake()
        callerThread.awaitDispatchPast(queuedWithTheCallerHeld)
        weave.cancel()
        callerThread.release()
        weave.join()

        assertTrue(weave.isCancelled, "rig: the weave was not cancelled")
        assertNull(woven, "rig: the caller got its seam, so it is the owner and this window was not reached")
    }

    private suspend fun cancelAsTheSessionArrivesAtTheHeldServer(loom: Loom) =
        cancelAsTheSessionArrives(loom, server.url, server::awaitRequest, server::finishHandshake)

    @Test
    fun aKtorClientLoomWeaveCancelledAsTheSessionArrivesLeavesNoSocket() = realTime {
        cancelAsTheSessionArrivesAtTheHeldServer(clientLoom(client()))
        assertACleanClose(server.farEnd())
    }

    @Test
    fun aKtorClientLoomWeaveCancelledAsTheSessionArrivesLeavesNoSocketOnOkHttp() = realTime {
        cancelAsTheSessionArrivesAtTheHeldServer(clientLoom(client(OkHttp)))
        assertACleanClose(server.farEnd())
    }

    @Test
    fun aKtorMeshClientLoomWeaveCancelledAsTheSessionArrivesLeavesNoSocket() = realTime {
        cancelAsTheSessionArrivesAtTheHeldServer(meshLoom(client()))
        assertACleanClose(server.farEnd())
    }

    /** The same window on a real Ktor server, for what a route hears. */
    @Test
    fun aKtorServerHearsANormalCloseFromASessionThatArrivedAfterTheCancel() = realTime {
        val requestSeen = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<Unit>()
        val heard = CompletableDeferred<CloseReason?>()
        val netty = startHeldRoute(requestSeen, answer, heard)
        val url = "ws://127.0.0.1:${netty.engine.resolvedConnectors().first().port}$NETTY_PATH"

        cancelAsTheSessionArrives(clientLoom(client()), url, requestSeen::await) { answer.complete(Unit) }

        val reason = withTimeoutOrNull(OBSERVATION) { heard.await() }
        assertNotNull(reason, "the route heard no close within $OBSERVATION, so the socket is open with no owner")
        assertEquals(CloseReason.Codes.NORMAL, reason.knownReason, "a session nobody wanted closes cleanly: $reason")
    }

    // Not inside `realTime`, whose receiver would bind `embeddedServer` to the `CoroutineScope` overload,
    // parent the server to the test's bound, and hold the test open until that bound expires.
    private fun startHeldRoute(
        requestSeen: CompletableDeferred<Unit>,
        answer: CompletableDeferred<Unit>,
        heard: CompletableDeferred<CloseReason?>,
    ): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {
        val netty = embeddedServer(Netty, port = 0) {
            install(ServerWebSockets)
            intercept(ApplicationCallPipeline.Plugins) {
                requestSeen.complete(Unit)
                answer.await()
            }
            routing {
                webSocket(NETTY_PATH) { heard.complete(closeReason.await()) }
            }
        }
        nettyServer = netty
        netty.start(wait = false)
        return netty
    }

    // ── After the upgrade, before the mesh preamble ──────────────────────────────────────────────

    /**
     * Not the bare dial: this is the mesh's handshake frame, which already took ownership in #2587.
     * Pinned over a real socket because the mesh loom's whole claim needs both halves.
     */
    @Test
    fun aKtorMeshClientLoomWeaveCancelledDuringThePreambleLeavesNoSocket() = realTime {
        val weave = rigScope.launch { meshLoom(client()).join(advertisement()) }
        server.awaitRequest()
        server.finishHandshake()
        server.awaitDataFrame()
        assertFalse(weave.isCompleted, "rig: the far end never answered the preamble, so the weave cannot be done")

        weave.cancelAndJoin()

        assertTheSocketClosed(server.farEnd())
    }

    // ── A dial that fails stays a failure ────────────────────────────────────────────────────────

    /** A weave on a client that is already closed fails, and never as a cancellation of its caller. */
    private suspend fun assertFailsOnAClosedClient(loom: Loom, httpClient: HttpClient) {
        httpClient.close()
        val outcome = withTimeoutOrNull(HANG_BOUND) { attempt { loom.join(advertisement()) } }
        assertNotNull(outcome, "a weave on a closed client hung past $HANG_BOUND instead of failing")
        val failure = outcome.failure
        assertNotNull(failure, "a weave on a closed client must not produce a seam")
        assertFalse(failure is CancellationException, "the caller was not cancelled, so it must not hear one: $failure")
    }

    @Test
    fun aKtorClientLoomWeaveOnAClosedClientFailsRatherThanHangs() = realTime {
        val httpClient = client()
        assertFailsOnAClosedClient(clientLoom(httpClient), httpClient)
    }

    @Test
    fun aKtorMeshClientLoomWeaveOnAClosedClientFailsRatherThanHangs() = realTime {
        val httpClient = client()
        assertFailsOnAClosedClient(meshLoom(httpClient), httpClient)
    }

    /** A refused upgrade still reaches the caller as what it is. */
    @Test
    fun aRefusedUpgradeFailsTheWeaveWithAnOrdinaryException() = realTime {
        val outcome = CompletableDeferred<Outcome>()
        rigScope.launch { outcome.complete(attempt { clientLoom(client()).join(advertisement()) }) }
        server.awaitRequest()

        server.refuseHandshake()

        val failure = outcome.await().failure
        assertNotNull(failure, "a refused upgrade must not produce a seam")
        assertFalse(failure is CancellationException, "a refusal is a failure, not a cancellation: $failure")
    }

    // ── Rig ──────────────────────────────────────────────────────────────────────────────────────

    private fun assertTheSocketClosed(farEnd: HeldHandshakeServer.FarEnd) {
        assertNotEquals<HeldHandshakeServer.FarEnd>(
            HeldHandshakeServer.FarEnd.StillOpen,
            farEnd,
            "nothing closed the socket within $OBSERVATION, so it is open with no owner",
        )
        if (farEnd is HeldHandshakeServer.FarEnd.CloseFrame) {
            assertTrue(farEnd.socketEnded, "a Close frame arrived but the socket stayed up within $OBSERVATION: $farEnd")
        }
    }

    /**
     * The session reached this end, so there is a socket to close properly. A dropped connection would
     * pass [assertTheSocketClosed], which is why the two are not one assertion.
     */
    private fun assertACleanClose(farEnd: HeldHandshakeServer.FarEnd) {
        assertEquals<HeldHandshakeServer.FarEnd>(
            HeldHandshakeServer.FarEnd.CloseFrame(code = NORMAL_CLOSE, socketEnded = true),
            farEnd,
            "a session nobody claimed gets a normal close, and then its socket ends",
        )
    }

    private class Outcome(val failure: Throwable?)

    private suspend fun attempt(block: suspend () -> Unit): Outcome {
        val failure = try {
            block()
            null
        } catch (thrown: Throwable) {
            thrown
        }
        currentCoroutineContext().ensureActive()
        return Outcome(failure)
    }

    /**
     * A single thread a test can stop, which counts what is queued for it. A dispatch after the hold is
     * the caller's continuation being resumed: the only thing it suspends on is the dial.
     */
    private class HeldCallerThread : CoroutineDispatcher(), AutoCloseable {
        private val executor = Executors.newSingleThreadExecutor()
        private val released = CountDownLatch(1)
        private val queued = AtomicInteger()

        val dispatches: Int get() = queued.get()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queued.incrementAndGet()
            executor.execute(block)
        }

        fun hold() {
            val running = CountDownLatch(1)
            dispatch(Job()) {
                running.countDown()
                released.await()
            }
            running.await()
        }

        fun release() = released.countDown()

        suspend fun awaitDispatchPast(count: Int) {
            while (queued.get() <= count) {
                currentCoroutineContext().ensureActive()
                delay(POLL)
            }
        }

        override fun close() {
            release()
            executor.shutdownNow()
        }
    }

    private companion object {
        val SELF = PeerId("self")
        val FAR_END = PeerId("far-end")
        const val NETTY_PATH = "/route"
        const val NORMAL_CLOSE = 1000
        val POLL = 5.milliseconds

        /** Waited out only on a red; see the class KDoc. */
        val OBSERVATION = 15.seconds
        val HANG_BOUND = 10.seconds
        val TEST_BOUND = 60.seconds

        /** Real time, bounded, so a regression fails as an assertion rather than a task timeout. */
        fun realTime(body: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(TEST_BOUND) { body() } }
    }
}
