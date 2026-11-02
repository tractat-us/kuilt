package us.tractat.kuilt.tcp

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
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
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.FlushOutcome
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The limit of what `Drained` promises, over a real socket: a slow consumer that is still sending can
 * lose the tail of a burst its peer flushed before closing.
 *
 * On macOS it usually does: closing a socket with the peer's bytes unread resets the connection, and the
 * consumer's read fails partway through a frame. Usually, not always: one full build's three trials
 * delivered 2507, 2507 and all 3000. On a Linux CI runner the same test delivered every frame in 3 of 3
 * trials, on a run that passed the control too. So **this asserts the promise and reports the loss rather
 * than requiring it.** Whatever arrives is the start of what was sent, in order, and the flush said
 * `Drained`, because it was right about the sender's end. The `arrived` counts are in this test's output,
 * in the results XML, so what a given machine did stays on the record.
 *
 * The loss itself is pinned on one clock by `LinkSeamFlushTest.aSendThatFailsThrowsAwayWhatTheSeamHadNotDelivered`,
 * which reds when #3111 fixes the receiver. The control beside this one is the same burst, the same
 * consumer and no frames coming back, and every frame arrives.
 */
class TcpFlushPeerTrafficLimitTest {
    private val rigScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val selector = SelectorManager(Dispatchers.IO)

    @AfterTest
    fun tearDown() {
        selector.close()
        rigScope.cancel()
    }

    private class Trial(val outcome: FlushOutcome, val arrived: List<Int>, val peerFramesSent: Int)

    private suspend fun trial(peerTraffic: Boolean): Trial = coroutineScope {
        val listener = aSocket(selector).tcp().bind("127.0.0.1", 0)
        val port = (listener.localAddress as InetSocketAddress).port
        val host = async { TcpLoom.host(listener, PeerId("sender"), selector).host(Pattern("limit")) }
        val receiver = TcpLoom.join(PeerId("consumer"), selector).join(TcpAddress("127.0.0.1", port))
        val sender = host.await()
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
        listener.close()
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

    private suspend fun trials(peerTraffic: Boolean): List<Trial> =
        rigScope.async { List(TRIALS) { trial(peerTraffic) } }.await()

    private fun assertEveryTrialIsHonest(trials: List<Trial>) {
        assertAll(
            { assertEquals(TRIALS, trials.size, "rig: every trial ran") },
            { assertEquals(TRIALS, trials.count { it.outcome == FlushOutcome.Drained }, "every flush said Drained") },
            { assertTrue(trials.all { t -> t.arrived == t.arrived.indices.toList() }, "what arrives is an in-order prefix") },
        )
    }

    /** Asserts the promise, and reports the loss: see the class KDoc for why it does not require one. */
    @Test
    fun aSlowConsumerStillSendingGetsAnInOrderPrefixOfAFlushedBurst(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val trials = trials(peerTraffic = true)
            println("peerTraffic=true burst=$BURST arrived=${trials.map { it.arrived.size }}")
            assertEveryTrialIsHonest(trials)
            assertTrue(trials.all { it.peerFramesSent > 0 }, "rig: the peer sent nothing in some trial")
        }

    /** The control: the same burst to the same slow consumer, with nothing coming back. */
    @Test
    fun withNothingComingBackTheSameBurstArrivesWhole(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val trials = trials(peerTraffic = false)
            println("peerTraffic=false burst=$BURST arrived=${trials.map { it.arrived.size }}")
            assertEveryTrialIsHonest(trials)
            assertEquals(List(TRIALS) { BURST }, trials.map { it.arrived.size }, "every frame in every trial")
        }

    private companion object {
        const val TRIALS = 3
        const val BURST = 3_000
        const val FRAME_BYTES = 16 * 1024
        const val CONSUMER_PAUSES_EVERY = 50
        val CONSUMER_PAUSE = 1.milliseconds
        const val PEER_FRAME_BYTES = 4_096
        val PEER_FRAME_EVERY = 1.milliseconds
    }
}
