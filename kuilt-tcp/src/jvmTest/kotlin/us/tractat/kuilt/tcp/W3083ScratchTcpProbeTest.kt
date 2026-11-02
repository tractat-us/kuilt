package us.tractat.kuilt.tcp

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers // ALLOW-realDispatcher: scratch
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.core.fabric.Connection
import java.io.File
import kotlin.test.Test

/** Scratch: what `tcpConnection.send` throws after a close, local and remote. Deleted before the PR is ready. */
class W3083ScratchTcpProbeTest {
    private val report = File(System.getProperty("user.home"), ".claude/jobs/3635b804/tmp/w3083-tcp-probe.txt").apply { parentFile.mkdirs() }

    private suspend fun sendUntilThrows(label: String, conn: Connection) {
        var attempts = 0
        val outcome = withTimeoutOrNull(20_000) {
            withContext(Dispatchers.IO) {
                while (attempts < 4_000) {
                    attempts++
                    try {
                        conn.send(ByteArray(64 * 1024))
                    } catch (t: Throwable) {
                        return@withContext "${t::class.qualifiedName}: ${t.message} isCE=${t is CancellationException} callerActive=${currentCoroutineContext().isActive} cause=${t.cause}"
                    }
                    delay(1)
                }
                "never threw"
            }
        } ?: "wedged in send"
        report.appendText("$label attempts=$attempts -> $outcome\n")
    }

    private suspend fun cancelledCaller(label: String, conn: Connection) {
        val outcome = withContext(Dispatchers.IO) {
            val job = async {
                currentCoroutineContext().cancel()
                try {
                    conn.send(ByteArray(8))
                    "returned"
                } catch (t: Throwable) {
                    "${t::class.qualifiedName}: ${t.message} isCE=${t is CancellationException}"
                }
            }
            try { job.await() } catch (t: Throwable) { "await threw $t" }
        }
        report.appendText("$label cancelled-caller -> $outcome\n")
    }

    private fun rig(block: suspend (Connection, Connection, io.ktor.network.sockets.Socket, io.ktor.network.sockets.Socket) -> Unit) = runBlocking {
        val selector = SelectorManager(Dispatchers.IO)
        val server = aSocket(selector).tcp().bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port
        try {
            val accepted = async { server.accept() }
            val dialed = aSocket(selector).tcp().connect("127.0.0.1", port)
            val acceptedSocket = accepted.await()
            val a = tcpConnection(dialed, Dispatchers.IO)
            val b = tcpConnection(acceptedSocket, Dispatchers.IO)
            block(a, b, dialed, acceptedSocket)
        } finally {
            server.close()
            selector.close()
        }
    }

    @Test
    fun localConnectionClose() = rig { a, b, _, _ ->
        a.close()
        sendUntilThrows("local Connection.close", a)
        cancelledCaller("local Connection.close", a)
        runCatching { b.close() }
    }

    @Test
    fun localSocketCloseOnly() = rig { a, b, dialed, _ ->
        dialed.close()
        delay(100)
        sendUntilThrows("local socket.close only", a)
        cancelledCaller("local socket.close only", a)
        runCatching { b.close() }
    }

    @Test
    fun remoteConnectionClose() = rig { a, b, _, _ ->
        b.close()
        delay(100)
        sendUntilThrows("remote Connection.close", a)
        cancelledCaller("remote Connection.close", a)
        runCatching { a.close() }
    }

    @Test
    fun remoteCloseWithReaderRunning() = rig { a, b, _, _ ->
        val reader = launch(Dispatchers.IO) { runCatching { a.incoming.collect { } } }
        b.close()
        withTimeoutOrNull(5_000) { reader.join() }
        sendUntilThrows("remote close, reader finished=${!reader.isActive}", a)
        cancelledCaller("remote close, reader finished=${!reader.isActive}", a)
        reader.cancel()
        runCatching { a.close() }
    }

    @Test
    fun remoteSocketAbort() = rig { a, b, _, accepted ->
        val reader = launch(Dispatchers.IO) { runCatching { a.incoming.collect { } } }
        accepted.cancel()
        withTimeoutOrNull(5_000) { reader.join() }
        sendUntilThrows("remote socket cancel, reader finished=${!reader.isActive}", a)
        cancelledCaller("remote socket cancel, reader finished=${!reader.isActive}", a)
        reader.cancel()
        runCatching { a.close() }
        runCatching { b.close() }
    }

    @Test
    fun localSocketCancel() = rig { a, b, dialed, _ ->
        dialed.cancel()
        delay(100)
        sendUntilThrows("local socket cancel", a)
        cancelledCaller("local socket cancel", a)
        runCatching { b.close() }
    }
}
