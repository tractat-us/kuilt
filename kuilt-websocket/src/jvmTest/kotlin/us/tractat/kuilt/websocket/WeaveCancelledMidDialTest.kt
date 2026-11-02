package us.tractat.kuilt.websocket

import io.ktor.client.HttpClient
import io.ktor.client.plugins.api.createClientPlugin
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
import io.ktor.websocket.close
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers // ALLOW-realDispatcher: real sockets and a real client, whose dial must run while the caller's thread is held
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.server.websocket.WebSockets as ServerWebSockets

/**
 * Cancelling a weave mid-dial leaves no socket behind (#3044), with one engine's exception pinned too.
 *
 * Ktor's `webSocketSession` runs the handshake on the [HttpClient]'s own scope, so a cancel ends the
 * caller's wait and nothing else. Every test here lets the far end report what the socket did, and
 * every window is forced rather than raced:
 *
 *  - **Before the upgrade answers.** [HeldHandshakeServer] has read the request and is holding the
 *    `101`, so the client cannot have a session yet. The cancel lands, and only then does the far end
 *    answer, which also gives a surviving socket a reason to end.
 *  - **With no answer ever coming.** The same cancel, with that reason taken away. This is the arm that
 *    tells an abort from a socket that was only waiting for the far end, and OkHttp is the difference.
 *  - **As the session arrives.** The caller's thread is held while the handshake finishes, until
 *    [HeldCallerThread] has seen the dial's result queued for it. The cancel lands then, so the caller
 *    takes the cancellation instead of a session already delivered to it, which is the one window that
 *    aborting the request cannot reach.
 *  - **With the Close frame still on its way.** [SlowSocketWriter] keeps the socket's writer off a
 *    thread while the session around it reports itself closed, which is when the call used to be cut.
 *
 * Time is real and bounded. [OBSERVATION] is only ever waited out on a red: a green reaches the far
 * end's verdict in well under a second, so it can be generous enough for a loaded box. Two arms wait
 * on a green because the wait is the claim, [OKHTTP_WATCH] and [ABANDON_BOUND], and both are short.
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

    private fun clientOn(dispatcher: CoroutineDispatcher): HttpClient =
        HttpClient(CIO) {
            install(ClientWebSockets)
            engine { this.dispatcher = dispatcher }
        }.also { clients += it }

    private fun advertisement(url: String = server.url) =
        WebSocketAdvertisement(url = url, serverPeerId = FAR_END, sessionName = "held")

    private fun clientLoom(httpClient: HttpClient): Loom =
        KtorClientLoom(httpClient, Dispatchers.Default, selfPeerId = SELF)

    private fun meshLoom(httpClient: HttpClient): Loom =
        KtorMeshClientLoom(httpClient, Dispatchers.Default, selfPeerId = SELF, random = Random(1))

    // ── Cancelled before the upgrade answers ─────────────────────────────────────────────────────

    private suspend fun assertAbandonedDialClosesTheSocket(loom: Loom) {
        val endedWith = CompletableDeferred<Throwable?>()
        val weave = rigScope.launch { loom.join(advertisement()) }
        weave.invokeOnCompletion { endedWith.complete(it) }
        server.awaitRequest()
        assertFalse(weave.isCompleted, "rig: the weave finished before the far end answered, so nothing is in flight")

        weave.cancelAndJoin()
        server.finishHandshake()

        assertTrue(endedWith.await() is CancellationException, "the caller's own cancel reaches it as a cancel")
        assertTheSocketClosed(server.farEnd())
    }

    @Test
    fun aKtorClientLoomWeaveCancelledBeforeTheUpgradeAnswersLeavesNoSocket() = realTime {
        assertAbandonedDialClosesTheSocket(clientLoom(client()))
    }

    @Test
    fun aKtorMeshClientLoomWeaveCancelledBeforeTheUpgradeAnswersLeavesNoSocket() = realTime {
        assertAbandonedDialClosesTheSocket(meshLoom(client()))
    }

    /** OkHttp did not abort, so it finishes the upgrade it was asked for and only then closes. */
    @Test
    fun anOkHttpWeaveCancelledBeforeTheUpgradeAnswersClosesOnceTheFarEndAnswers() = realTime {
        val weave = rigScope.launch { clientLoom(client(OkHttp)).join(advertisement()) }
        server.awaitRequest()
        assertFalse(weave.isCompleted, "rig: the weave finished before the far end answered, so nothing is in flight")

        weave.cancelAndJoin()
        server.finishHandshake()

        assertEquals<HeldHandshakeServer.FarEnd>(
            HeldHandshakeServer.FarEnd.CloseFrame(code = INTERNAL_ERROR_CLOSE, socketEnded = true),
            server.farEnd(),
            "OkHttp's behaviour moved, so ownedWebSocketSession's KDoc needs another look",
        )
    }

    // ── Cancelled with no answer ever coming ─────────────────────────────────────────────────────

    /** An abort: the far end says nothing, so the socket can only end because the client ended it. */
    @Test
    fun aKtorClientLoomWeaveCancelledWithNoAnswerComingEndsTheSocket() = realTime {
        val weave = rigScope.launch { clientLoom(client()).join(advertisement()) }
        server.awaitRequest()
        assertFalse(weave.isCompleted, "rig: the weave finished with no answer, so nothing is in flight")

        weave.cancelAndJoin()
        server.leaveUnanswered()

        assertEquals<HeldHandshakeServer.FarEnd>(
            HeldHandshakeServer.FarEnd.SocketEnded,
            server.farEnd(),
            "a cancel with no answer coming has to abort the request itself",
        )
    }

    /**
     * The exception `ownedWebSocketSession`'s KDoc states: OkHttp keeps this socket, and kuilt cannot
     * reach it. Pinned so an upgrade that fixes it says so, rather than leaving the KDoc wrong.
     *
     * `readTimeout(0)` is what makes this deterministic. OkHttp's default gives up after about 10 s, so
     * at that setting this arm would depend on how long the box took to get here.
     */
    @Test
    fun anOkHttpWeaveCancelledWithNoAnswerComingKeepsItsSocket() = realTime {
        val unanswered = HeldHandshakeServer(OKHTTP_WATCH)
        val noReadTimeout = HttpClient(OkHttp) {
            install(ClientWebSockets)
            engine { config { readTimeout(0, TimeUnit.SECONDS) } }
        }.also { clients += it }
        try {
            val weave = rigScope.launch {
                clientLoom(noReadTimeout).join(advertisement(unanswered.url))
            }
            unanswered.awaitRequest()
            assertFalse(weave.isCompleted, "rig: the weave finished with no answer, so nothing is in flight")

            weave.cancelAndJoin()
            unanswered.leaveUnanswered()

            assertEquals<HeldHandshakeServer.FarEnd>(
                HeldHandshakeServer.FarEnd.StillOpen,
                unanswered.farEnd(),
                "OkHttp ended this socket, so ownedWebSocketSession's KDoc is out of date",
            )
        } finally {
            unanswered.close()
        }
    }

    // ── Cancelled as the session arrives ─────────────────────────────────────────────────────────

    /**
     * Cancels the weave with the session already handed over and the caller not yet running, and returns
     * how long the cancelled weave then took to end.
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
        beforeTheCancel: suspend () -> Unit = {},
    ): Duration {
        var woven: Seam? = null
        val weave = rigScope.launch(callerThread) { woven = loom.join(advertisement(url)) }
        requestSeen()
        callerThread.hold()
        val queuedWithTheCallerHeld = callerThread.dispatches

        finishHandshake()
        callerThread.awaitDispatchPast(queuedWithTheCallerHeld)
        beforeTheCancel()
        weave.cancel()
        val released = TimeSource.Monotonic.markNow()
        callerThread.release()
        weave.join()
        val waited = released.elapsedNow()

        assertTrue(weave.isCancelled, "rig: the weave was not cancelled")
        assertNull(woven, "rig: the caller got its seam, so it is the owner and this window was not reached")
        return waited
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

        cancelAsTheSessionArrives(clientLoom(client()), url, requestSeen::await, { answer.complete(Unit) })

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

    /**
     * The shield around the close removes the only other way out, so something else has to bound it. A
     * far end that never answers the Close frame is what OkHttp sits on: it holds the socket for that
     * answer on a clock of its own, and the cancelled caller must not wait out the same clock.
     *
     * The bound is also what makes the two steps inside it visible, because this far end keeps the
     * session up for all of it. A real wait reaches [ABANDON_BOUND] and then cuts the call. Take away
     * the `NonCancellable` shield and the join throws at once, so the cut never runs and the call stays
     * on the client. Take away the join and nothing waits, so the close gets no time to land. The
     * elapsed check is a lower bound on a real clock, so a slow box cannot fail it.
     *
     * The last assertion says the unclaimed session's close was still sent, on the arm where nothing
     * else in this test would notice if it were not.
     */
    @Test
    fun aFarEndThatNeverAnswersTheCloseCannotHoldUpTheCancel() = realTime {
        val silent = HeldHandshakeServer(OBSERVATION, answersClose = false)
        val httpClient = client(OkHttp)
        try {
            val waited = withTimeoutOrNull(HANG_BOUND) {
                cancelAsTheSessionArrives(
                    clientLoom(httpClient),
                    silent.url,
                    silent::awaitRequest,
                    silent::finishHandshake,
                )
            }
            assertNotNull(waited, "the cancelled weave was still waiting on the far end after $HANG_BOUND")
            val liveCalls = httpClient.coroutineContext.job.children.count { it.isActive }
            val seen = "after $waited, with $liveCalls call(s) still on the client"
            assertTrue(waited >= ABANDON_BOUND, "the weave did not wait out the close: it returned $seen")
            assertEquals(0, liveCalls, "the bound expired and the call was not cut: the weave returned $seen")
            assertTrue(silent.closeFrameSeen, "the unclaimed session's close was never sent")
        } finally {
            silent.close()
        }
    }

    // ── With the Close frame still on its way ────────────────────────────────────────────────────

    /**
     * CIO's session closes its outgoing side the moment the Close frame is queued, before the socket's
     * writer has run. Ending the call on that signal cancels the writer with the frame still in its
     * queue, and the far end hears a dropped connection. Left to chance that is a few dials in a
     * thousand, which is how it reached CI as one red in a class that had passed its reruns.
     * [SlowSocketWriter] makes it the only outcome.
     */
    @Test
    fun aCloseFrameStillWaitingOnItsWriterSurvivesTheCancel() = realTime {
        val writer = SlowSocketWriter()
        cancelAsTheSessionArrives(
            clientLoom(clientOn(writer)),
            server.url,
            server::awaitRequest,
            server::finishHandshake,
            beforeTheCancel = writer::awaitParked,
        )
        assertACleanClose(server.farEnd())
        assertTrue(writer.lateRuns > 0, "rig: the socket's writer was never delayed, so this window was not reached")
    }

    /** The same signal ends the call when the caller owns the session, so an ordinary close lost its frame too. */
    @Test
    fun aCloseFrameStillWaitingOnItsWriterSurvivesTheOwnersClose() = realTime {
        val writer = SlowSocketWriter()
        val httpClient = clientOn(writer)
        val session = rigScope.async { httpClient.ownedWebSocketSession(server.url) }
        server.awaitRequest()
        server.finishHandshake()
        val owned = session.await()
        writer.awaitParked()

        owned.close()

        assertACleanClose(server.farEnd())
        assertTrue(writer.lateRuns > 0, "rig: the socket's writer was never delayed, so this window was not reached")
    }

    /** Waiting for the far end's answer must not become waiting forever: the socket still ends without it. */
    @Test
    fun aFarEndThatNeverAnswersTheCloseStillLosesTheSocket() = realTime {
        val silent = HeldHandshakeServer(OBSERVATION, answersClose = false)
        val httpClient = client()
        try {
            val session = rigScope.async { httpClient.ownedWebSocketSession(silent.url) }
            silent.awaitRequest()
            silent.finishHandshake()

            session.await().close()

            assertACleanClose(silent.farEnd())
        } finally {
            silent.close()
        }
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

    /**
     * The window [Loom.weave]'s contract is about. A plugin throwing here stands in for anything in the
     * call that mints a cancellation with this caller alive, as the Darwin engine does for a session it
     * has closed.
     */
    @Test
    fun aCancellationTheCallerDidNotAskForArrivesAsAFailure() = realTime {
        val minting = HttpClient(CIO) {
            install(ClientWebSockets)
            install(createClientPlugin("mints-a-cancellation") {
                onRequest { _, _ -> throw CancellationException("nobody cancelled the caller") }
            })
        }.also { clients += it }

        val failure = attempt { clientLoom(minting).join(advertisement()) }.failure

        assertNotNull(failure, "the dial cannot have succeeded: the plugin refuses every request")
        assertFalse(failure is CancellationException, "the caller was not cancelled, so it must not hear one: $failure")
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

    /**
     * Lets the socket's writer start on time and then runs it [WRITER_LATENESS] after it was due, with
     * everything else on time. It has to start first: a writer woken from sleep is what a cancel can
     * stop, whereas one that has not started yet finds the frame already queued and writes it. Ktor's
     * writer is found by its coroutine name, which is not a promise, so an arm using this counts
     * [lateRuns] rather than trusting that the name still matches.
     *
     * The lateness only decides whether the window opens. It has to stay well under the bound on the
     * close, so that a real wait outlasts it on a slow box as well.
     */
    private class SlowSocketWriter : CoroutineDispatcher() {
        private val runs = AtomicInteger()
        private val parked = CountDownLatch(1)

        val lateRuns: Int get() = (runs.get() - 1).coerceAtLeast(0)

        /** Returns once the writer has started and gone to sleep on its empty queue. */
        suspend fun awaitParked() {
            while (parked.count > 0) {
                currentCoroutineContext().ensureActive()
                delay(POLL)
            }
        }

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (context[CoroutineName]?.name != SOCKET_WRITER) {
                Dispatchers.IO.dispatch(context, block)
                return
            }
            val task = if (runs.incrementAndGet() == 1) {
                Runnable {
                    block.run()
                    parked.countDown()
                }
            } else {
                Runnable {
                    Thread.sleep(WRITER_LATENESS.inWholeMilliseconds)
                    block.run()
                }
            }
            Dispatchers.IO.dispatch(context, task)
        }
    }

    private companion object {
        val SELF = PeerId("self")
        val FAR_END = PeerId("far-end")
        const val NETTY_PATH = "/route"
        const val NORMAL_CLOSE = 1000
        const val INTERNAL_ERROR_CLOSE = 1011
        const val SOCKET_WRITER = "ws-writer"
        val WRITER_LATENESS = 250.milliseconds
        val POLL = 5.milliseconds

        /** How long a socket kuilt cannot reach is watched. Unlike [OBSERVATION], a green waits this out. */
        val OKHTTP_WATCH = 3.seconds

        /** Waited out only on a red; see the class KDoc. */
        val OBSERVATION = 15.seconds
        val HANG_BOUND = 10.seconds
        val TEST_BOUND = 60.seconds

        /** Real time, bounded, so a regression fails as an assertion rather than a task timeout. */
        fun realTime(body: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(TEST_BOUND) { body() } }
    }
}
