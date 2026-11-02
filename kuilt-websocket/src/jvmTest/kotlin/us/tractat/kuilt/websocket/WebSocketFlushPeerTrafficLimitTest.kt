package us.tractat.kuilt.websocket

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers // ALLOW-realDispatcher: real sockets on a real clock. The consumer's pace has to be real, or a burst and a close outrun nothing, and runTest's clock would skip it.
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.FlushOutcome
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

/**
 * The limit of what `Drained` promises, over a real socket: a slow consumer that is still sending can
 * lose the tail of a burst its peer flushed before closing.
 *
 * Its send fails on the link the close ended, that tears its seam, and a torn seam stops reading what it
 * had not got to. Whether a given run loses anything depends on that send landing before the consumer
 * has caught up, which is a race on a real clock, and the TCP sibling of this test has already gone the
 * other way on a CI runner. So **this asserts the promise and reports the loss rather than requiring
 * it.** Whatever arrives is the start of what was sent, in order, and the flush said `Drained`, because
 * it was right about the sender's end. The `arrived` counts are in this test's output, in the results
 * XML, so what a given machine did stays on the record.
 *
 * The loss itself is pinned on one clock by `LinkSeamFlushTest.aSendThatFailsThrowsAwayWhatTheSeamHadNotDelivered`,
 * which reds when #3111 fixes the receiver. The control beside this one is the same burst, the same
 * consumer and no frames coming back, and every frame arrives.
 */
class WebSocketFlushPeerTrafficLimitTest {
    private val rigScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>
    private lateinit var serverLoom: KtorServerLoom
    private lateinit var httpClient: HttpClient
    private var port = 0

    // Started here rather than in `runBlocking`, whose receiver would bind `embeddedServer` to the
    // `CoroutineScope` overload and parent the server to the blocking job.
    @BeforeTest
    fun setUp() {
        server = embeddedServer(Netty, port = 0) { serverLoom = KtorServerLoom(this, PATH) }
        server.start(wait = false)
        port = runBlocking { server.engine.resolvedConnectors().first().port }
        httpClient = HttpClient(CIO) { install(ClientWebSockets) }
    }

    @AfterTest
    fun tearDown() {
        httpClient.close()
        server.stop(gracePeriodMillis = 100, timeoutMillis = 1_000)
        rigScope.cancel()
    }

    private class Trial(val outcome: FlushOutcome, val arrived: List<Int>, val peerFramesSent: Int)

    private suspend fun connectedPair(): Pair<Seam, Seam> = coroutineScope {
        val host = async { serverLoom.host(Pattern("limit")) }
        val advertisement = WebSocketAdvertisement(
            url = "ws://127.0.0.1:$port$PATH",
            serverPeerId = serverLoom.selfPeerId,
            sessionName = "limit",
        )
        val joiner = KtorClientLoom(httpClient).join(advertisement)
        host.await() to joiner
    }

    private suspend fun trial(peerTraffic: Boolean, fromHost: Boolean): Trial = coroutineScope {
        val (host, joiner) = connectedPair()
        val (sender, receiver) = if (fromHost) host to joiner else joiner to host
        sender.peers.first { receiver.selfId in it }
        val peerFramesSent = AtomicInteger()
        val arrived = async {
            val indices = mutableListOf<Int>()
            receiver.incoming.take(BURST).collect { frame ->
                val head = frame.toByteArray()
                indices += ((head[0].toInt() and 0xFF) shl 8) or (head[1].toInt() and 0xFF)
                if (indices.size % CONSUMER_PAUSES_EVERY == 0) delay(CONSUMER_PAUSE)
            }
            indices.toList()
        }
        // The burst waits for the peer's first frame, so a trial cannot finish before the peer got going.
        val peerStarted = CompletableDeferred<Unit>()
        val peer = if (peerTraffic) launch { sendUntilTorn(receiver, peerFramesSent, peerStarted) } else null
        if (peerTraffic) peerStarted.await()
        repeat(BURST) { index ->
            val frame = ByteArray(FRAME_BYTES)
            frame[0] = (index shr 8).toByte()
            frame[1] = index.toByte()
            sender.sendTo(receiver.selfId, frame)
        }
        val outcome = sender.flush(Duration.INFINITE)
        sender.close()
        val result = arrived.await()
        peer?.cancelAndJoin()
        receiver.close()
        Trial(outcome, result, peerFramesSent.get())
    }

    private suspend fun sendUntilTorn(seam: Seam, sent: AtomicInteger, started: CompletableDeferred<Unit>) {
        try {
            while (currentCoroutineContext().isActive) {
                seam.broadcast(ByteArray(PEER_FRAME_BYTES))
                sent.incrementAndGet()
                started.complete(Unit)
                delay(PEER_FRAME_EVERY)
            }
        } catch (_: IllegalStateException) {
            // A cancellation is an IllegalStateException too, and only this job's own may pass.
            currentCoroutineContext().ensureActive()
            // Otherwise the seam tore, which is what this rig is waiting for.
        }
    }

    private fun assertEveryTrialIsHonest(trials: List<Trial>) {
        assertAll(
            { assertEquals(TRIALS_PER_END * 2, trials.size, "rig: every trial ran") },
            { assertEquals(trials.size, trials.count { it.outcome == FlushOutcome.Drained }, "every flush said Drained") },
            { assertTrue(trials.all { t -> t.arrived == t.arrived.indices.toList() }, "what arrives is an in-order prefix") },
        )
    }

    private suspend fun trialsFromBothEnds(peerTraffic: Boolean): List<Trial> =
        rigScope.async {
            listOf(true, false).flatMap { fromHost -> List(TRIALS_PER_END) { trial(peerTraffic, fromHost) } }
        }.await()

    /** Asserts the promise, and reports the loss: see the class KDoc for why it does not require one. */
    @Test
    fun aSlowConsumerStillSendingGetsAnInOrderPrefixOfAFlushedBurst(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val trials = trialsFromBothEnds(peerTraffic = true)
            println("peerTraffic=true burst=$BURST arrived=${trials.map { it.arrived.size }}")
            assertEveryTrialIsHonest(trials)
            assertTrue(trials.all { it.peerFramesSent > 0 }, "rig: the peer sent nothing in some trial")
        }

    /** The control: the same burst to the same slow consumer, with nothing coming back. */
    @Test
    fun withNothingComingBackTheSameBurstArrivesWhole(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val trials = trialsFromBothEnds(peerTraffic = false)
            println("peerTraffic=false burst=$BURST arrived=${trials.map { it.arrived.size }}")
            assertEveryTrialIsHonest(trials)
            assertEquals(List(trials.size) { BURST }, trials.map { it.arrived.size }, "every frame in every trial")
        }

    private companion object {
        const val PATH = "/ws/flush-limit"
        const val TRIALS_PER_END = 2
        const val BURST = 600
        const val FRAME_BYTES = 256
        const val CONSUMER_PAUSES_EVERY = 50
        val CONSUMER_PAUSE = 20.milliseconds
        const val PEER_FRAME_BYTES = 2_048
        val PEER_FRAME_EVERY = 100.milliseconds
    }
}
