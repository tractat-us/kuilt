package us.tractat.kuilt.websocket

import kotlinx.coroutines.CompletableDeferred
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.time.Duration

/**
 * The far end of one WebSocket dial, on a plain loopback socket, with the upgrade answer in the
 * test's hands: it reads the client's request, holds until told to answer, and then reports what
 * the connection did.
 *
 * Plain sockets rather than a Ktor server, because the question is whether the *socket* closes and
 * a Ktor route cannot answer it for a dial that was aborted. With the client gone before the `101`,
 * the route's handler may never run, so "no open session" there reads the same for a closed socket
 * and for a rig that never connected. Here the socket is accepted before anything else happens, and
 * reading it to the end is the observation. [WeaveCancelledMidDialTest] has one arm on a real Netty
 * route as well, for what a Ktor server hears.
 *
 * It serves exactly one connection, on its own daemon thread.
 */
internal class HeldHandshakeServer(private val observation: Duration) : AutoCloseable {

    /** What the far end saw once the upgrade had been answered. */
    sealed interface FarEnd {
        /** The client sent a Close frame with [code], and then the socket ended, or did not. */
        data class CloseFrame(val code: Int?, val socketEnded: Boolean) : FarEnd

        /** The socket ended with no Close frame: an abort. */
        data object SocketEnded : FarEnd

        /** Nothing ended within the observation bound: a socket nobody owns. */
        data object StillOpen : FarEnd
    }

    private val listener = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private val answer = CountDownLatch(1)
    private var answerLine = SWITCHING_PROTOCOLS

    private val requestSeen = CompletableDeferred<Unit>()
    private val dataFrameSeen = CompletableDeferred<Unit>()
    private val verdict = CompletableDeferred<FarEnd>()

    val url: String get() = "ws://127.0.0.1:${listener.localPort}/held"

    init {
        thread(isDaemon = true, name = "held-handshake-${listener.localPort}") { serve() }
    }

    /** Returns once the client's whole upgrade request has arrived, so it is waiting on the answer. */
    suspend fun awaitRequest() = requestSeen.await()

    /** Returns once the client has sent its first application frame over the finished upgrade. */
    suspend fun awaitDataFrame() = dataFrameSeen.await()

    /** Let the handshake finish with `101 Switching Protocols`. */
    fun finishHandshake() = answer.countDown()

    /** Answer the upgrade with a refusal rather than finishing it. */
    fun refuseHandshake() {
        answerLine = FORBIDDEN
        answer.countDown()
    }

    /** What the connection did after the answer, at most [observation] later. */
    suspend fun farEnd(): FarEnd = verdict.await()

    override fun close() {
        answer.countDown()
        listener.close()
    }

    private fun serve() {
        try {
            listener.accept().use { socket ->
                val input = socket.getInputStream()
                val key = readWebSocketKey(input)
                requestSeen.complete(Unit)
                answer.await()
                socket.soTimeout = observation.inWholeMilliseconds.toInt()
                try {
                    socket.getOutputStream().apply {
                        write(responseTo(key).toByteArray())
                        flush()
                    }
                } catch (_: IOException) {
                    // The client is already gone, and the read below will say so.
                }
                verdict.complete(observe(input, socket.getOutputStream()))
            }
        } catch (_: IOException) {
            // The listener closed in teardown, with nobody left to report to.
        }
    }

    private fun responseTo(key: String): String = when (answerLine) {
        SWITCHING_PROTOCOLS ->
            "$answerLine\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: ${acceptOf(key)}\r\n\r\n"
        else -> "$answerLine\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
    }

    private fun observe(input: InputStream, output: OutputStream): FarEnd {
        try {
            while (true) {
                val frame = readFrame(input) ?: return FarEnd.SocketEnded
                when (frame.opcode) {
                    OPCODE_CLOSE -> {
                        answerClose(output)
                        return FarEnd.CloseFrame(frame.closeCode(), socketEnded = endsAfterClose(input))
                    }
                    OPCODE_BINARY, OPCODE_TEXT -> dataFrameSeen.complete(Unit)
                }
            }
        } catch (_: SocketTimeoutException) {
            return FarEnd.StillOpen
        } catch (_: IOException) {
            return FarEnd.SocketEnded
        }
    }

    /** What any live peer does, and what OkHttp waits on before it lets go of the socket. */
    private fun answerClose(output: OutputStream) {
        try {
            output.write(byteArrayOf(0x88.toByte(), 0x00))
            output.flush()
        } catch (_: IOException) {
            // The client did not wait for it, and the read after this will say so.
        }
    }

    /** A Close frame is a promise. This asks whether the socket behind it went away too. */
    private fun endsAfterClose(input: InputStream): Boolean {
        try {
            while (input.read() >= 0) {
                // Whatever is still in flight behind the Close frame.
            }
            return true
        } catch (_: SocketTimeoutException) {
            return false
        } catch (_: IOException) {
            return true
        }
    }

    private class RawFrame(val opcode: Int, val payload: ByteArray) {
        fun closeCode(): Int? =
            if (payload.size < 2) null else (payload[0].toInt() and 0xFF shl 8) or (payload[1].toInt() and 0xFF)
    }

    private fun readFrame(input: InputStream): RawFrame? {
        val first = input.read()
        if (first < 0) return null
        val second = input.read()
        if (second < 0) return null
        val masked = second and 0x80 != 0
        val length = when (val short = second and 0x7F) {
            126 -> input.readNBytes(2).fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
            127 -> input.readNBytes(8).fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
            else -> short
        }
        val mask = if (masked) input.readNBytes(4) else null
        val payload = input.readNBytes(length)
        if (payload.size < length) return null
        if (mask != null) payload.indices.forEach { i -> payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte() }
        return RawFrame(first and 0x0F, payload)
    }

    private fun readWebSocketKey(input: InputStream): String {
        val request = StringBuilder()
        while (!request.endsWith("\r\n\r\n")) {
            val next = input.read()
            if (next < 0) throw IOException("the client went away before finishing its upgrade request")
            request.append(next.toChar())
        }
        return request.lineSequence()
            .first { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }
            .substringAfter(':')
            .trim()
    }

    private fun acceptOf(key: String): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + WEBSOCKET_GUID).toByteArray()))

    private companion object {
        const val SWITCHING_PROTOCOLS = "HTTP/1.1 101 Switching Protocols"
        const val FORBIDDEN = "HTTP/1.1 403 Forbidden"
        const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        const val OPCODE_TEXT = 0x1
        const val OPCODE_BINARY = 0x2
        const val OPCODE_CLOSE = 0x8
    }
}
