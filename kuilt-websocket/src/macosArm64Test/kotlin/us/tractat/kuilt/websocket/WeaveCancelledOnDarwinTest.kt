package us.tractat.kuilt.websocket

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.network.sockets.port
import io.ktor.util.encodeBase64
import io.ktor.util.sha1
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readFully
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeByteArray
import io.ktor.utils.io.writeStringUtf8
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers // ALLOW-realDispatcher: real sockets and URLSession, whose delegate needs a queue the test is not blocking
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.IOException
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import kotlin.coroutines.CoroutineContext
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

/**
 * [ownedWebSocketSession] on the engine iOS and macOS actually use.
 *
 * `WeaveCancelledMidDialTest` measures CIO and OkHttp on the JVM. Darwin hands the socket to
 * URLSession, so what a cancel does to it is a separate question, with the same two forced windows:
 * before the upgrade answers, and as the session arrives. The far end is a plain loopback socket that
 * holds the answer until told, and reports what the connection did.
 */
class WeaveCancelledOnDarwinTest {

    private val rigScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val selector = SelectorManager(Dispatchers.Default)
    private val gate = GatedDispatcher(Dispatchers.Default)
    private val clients = mutableListOf<HttpClient>()

    @AfterTest
    fun tearDown() {
        rigScope.cancel()
        clients.forEach { it.close() }
        selector.close()
    }

    /**
     * Built off the test's thread. Darwin takes `NSOperationQueue.currentQueue()` as URLSession's
     * delegate queue, and on the thread `runBlocking` holds, that queue would never run a callback.
     */
    private suspend fun darwinClient(): HttpClient = withContext(Dispatchers.Default) {
        HttpClient(Darwin) { install(WebSockets) }.also { clients += it }
    }

    private fun loom(httpClient: HttpClient): Loom = KtorClientLoom(httpClient, Dispatchers.Default, selfPeerId = SELF)

    private fun advertisement(farEnd: HeldUpgrade) =
        WebSocketAdvertisement(url = farEnd.url, serverPeerId = FAR_END, sessionName = "held")

    @Test
    fun aWeaveCancelledBeforeTheUpgradeAnswersLeavesNoSocket() = realTime {
        val farEnd = HeldUpgrade(aSocket(selector).tcp().bind("127.0.0.1", 0), rigScope)
        val client = darwinClient()
        val weave = rigScope.launch { loom(client).join(advertisement(farEnd)) }
        farEnd.requestSeen.await()
        assertFalse(weave.isCompleted, "rig: the weave finished before the far end answered, so nothing is in flight")

        weave.cancelAndJoin()
        farEnd.answer.complete(Unit)

        assertNotEquals<FarEndSaw>(FarEndSaw.StillOpen, farEnd.saw.await(), "nothing closed the socket, so nobody owns it")
    }

    @Test
    fun aWeaveCancelledAsTheSessionArrivesGetsANormalClose() = realTime {
        val farEnd = HeldUpgrade(aSocket(selector).tcp().bind("127.0.0.1", 0), rigScope)
        val client = darwinClient()
        var woven: Seam? = null
        val weave = rigScope.launch(gate) { woven = loom(client).join(advertisement(farEnd)) }
        farEnd.requestSeen.await()
        gate.hold()

        farEnd.answer.complete(Unit)
        gate.awaitAResumption()
        weave.cancel()
        gate.release()
        weave.join()

        assertTrue(weave.isCancelled, "rig: the weave was not cancelled")
        assertNull(woven, "rig: the caller got its seam, so it is the owner and this window was not reached")
        assertEquals<FarEndSaw>(FarEndSaw.CloseFrame(NORMAL_CLOSE), farEnd.saw.await())
    }

    @Test
    fun aWeaveOnAClosedClientFailsRatherThanHangs() = realTime {
        val farEnd = HeldUpgrade(aSocket(selector).tcp().bind("127.0.0.1", 0), rigScope)
        val client = darwinClient()
        client.close()

        val failure = withTimeoutOrNull(HANG_BOUND) {
            try {
                loom(client).join(advertisement(farEnd))
                null
            } catch (thrown: Throwable) {
                currentCoroutineContext().ensureActive()
                thrown
            }
        }

        assertNotNull(failure, "a weave on a closed client hung past $HANG_BOUND, or produced a seam")
        assertFalse(failure is CancellationException, "the caller was not cancelled, so it must not hear one: $failure")
    }

    private sealed interface FarEndSaw {
        data class CloseFrame(val code: Int?) : FarEndSaw

        data object SocketEnded : FarEndSaw

        data object StillOpen : FarEndSaw
    }

    /** One upgrade on a plain socket, answered when told, then read until the connection says something. */
    private class HeldUpgrade(private val listener: ServerSocket, scope: CoroutineScope) {
        val requestSeen = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<Unit>()
        val saw = CompletableDeferred<FarEndSaw>()
        val url: String get() = "ws://127.0.0.1:${listener.port}/held"

        init {
            scope.launch {
                val socket = listener.accept()
                val input = socket.openReadChannel()
                val output = socket.openWriteChannel(autoFlush = true)
                val key = readWebSocketKey(input)
                requestSeen.complete(Unit)
                answer.await()
                saw.complete(withTimeoutOrNull(OBSERVATION) { observe(key, input, output) } ?: FarEndSaw.StillOpen)
                socket.close()
            }
        }

        private suspend fun readWebSocketKey(input: ByteReadChannel): String {
            var key = ""
            while (true) {
                val line = input.readUTF8Line() ?: throw IOException("the client went away mid-request")
                if (line.isEmpty()) return key
                if (line.startsWith("Sec-WebSocket-Key:", ignoreCase = true)) key = line.substringAfter(':').trim()
            }
        }

        private suspend fun observe(key: String, input: ByteReadChannel, output: ByteWriteChannel): FarEndSaw =
            try {
                output.writeStringUtf8(
                    "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                        "Sec-WebSocket-Accept: ${acceptOf(key)}\r\n\r\n",
                )
                var verdict: FarEndSaw = FarEndSaw.SocketEnded
                while (verdict == FarEndSaw.SocketEnded) {
                    val closeCode = readCloseCodeIfCloseFrame(input) ?: continue
                    output.writeByteArray(byteArrayOf(0x88.toByte(), 0x00))
                    verdict = FarEndSaw.CloseFrame(closeCode)
                }
                verdict
            } catch (_: IOException) {
                FarEndSaw.SocketEnded
            }

        /** Reads one client frame. Returns the Close code, or null for any other frame. */
        private suspend fun readCloseCodeIfCloseFrame(input: ByteReadChannel): Int? {
            val header = ByteArray(2).also { input.readFully(it) }
            val length = header[1].toInt() and 0x7F
            val mask = ByteArray(4).also { input.readFully(it) }
            val payload = ByteArray(length).also { input.readFully(it) }
            payload.indices.forEach { i -> payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte() }
            if (header[0].toInt() and 0x0F != OPCODE_CLOSE || payload.size < 2) return null
            return (payload[0].toInt() and 0xFF shl 8) or (payload[1].toInt() and 0xFF)
        }

        private fun acceptOf(key: String): String = sha1((key + WEBSOCKET_GUID).encodeToByteArray()).encodeBase64()
    }

    /**
     * Holds what is dispatched to it, for a window only the test decides. What lands during a hold is
     * the caller's continuation being resumed: the only thing it suspends on is the dial.
     */
    private class GatedDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
        private val lock = reentrantLock()
        private val held = ArrayDeque<Runnable>()
        private var holding = false

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            val heldHere = lock.withLock {
                if (holding) held.addLast(block)
                holding
            }
            if (!heldHere) delegate.dispatch(context, block)
        }

        fun hold() = lock.withLock { holding = true }

        fun release() {
            val drained = lock.withLock {
                holding = false
                held.toList().also { held.clear() }
            }
            drained.forEach { delegate.dispatch(kotlin.coroutines.EmptyCoroutineContext, it) }
        }

        suspend fun awaitAResumption() {
            while (lock.withLock { held.isEmpty() }) {
                currentCoroutineContext().ensureActive()
                delay(POLL)
            }
        }
    }

    private companion object {
        val SELF = PeerId("self")
        val FAR_END = PeerId("far-end")
        const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        const val OPCODE_CLOSE = 0x8
        const val NORMAL_CLOSE = 1000
        val POLL = 5.milliseconds

        /** Waited out only on a red. */
        val OBSERVATION = 15.seconds
        val HANG_BOUND = 10.seconds
        val TEST_BOUND = 45.seconds

        /** Real time, bounded, so a regression fails as an assertion rather than a task timeout. */
        fun realTime(body: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(TEST_BOUND) { body() } }
    }
}
