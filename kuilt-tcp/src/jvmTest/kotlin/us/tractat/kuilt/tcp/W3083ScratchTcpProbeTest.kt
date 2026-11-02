package us.tractat.kuilt.tcp

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers // ALLOW-realDispatcher: scratch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.core.fabric.Connection
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test

/** Scratch: what `tcpConnection.send` throws after a close, local and remote. Deleted before the PR is ready. */
class W3083ScratchTcpProbeTest {
    private val report = File(System.getProperty("user.home"), ".claude/jobs/3635b804/tmp/w3083-tcp-probe2.txt").apply { parentFile.mkdirs() }

    private fun describe(t: Throwable, active: Boolean) =
        "${t::class.qualifiedName}: ${t.message} isCE=${t is CancellationException} callerActive=$active cause=${t.cause}"

    private class Rig(
        val a: Connection,
        val b: Connection,
        val dialed: Socket,
        val accepted: Socket,
        val selector: SelectorManager,
    )

    private fun rig(block: suspend CoroutineScope.(Rig) -> Unit) = runBlocking {
        val selector = SelectorManager(Dispatchers.IO)
        val server = aSocket(selector).tcp().bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port
        try {
            val accepted = async { server.accept() }
            val dialed = aSocket(selector).tcp().connect("127.0.0.1", port)
            val acceptedSocket = accepted.await()
            block(Rig(tcpConnection(dialed, Dispatchers.IO), tcpConnection(acceptedSocket, Dispatchers.IO), dialed, acceptedSocket, selector))
        } finally {
            runCatching { server.close() }
            runCatching { selector.close() }
        }
    }

    /** Fill the kernel buffers against a peer that reads nothing, then apply [action] while the sender is stuck. */
    private fun stuck(label: String, action: suspend (Rig) -> Unit) = rig { r ->
        val outcome = AtomicReference<String?>(null)
        val progress = AtomicLong(0)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                while (true) {
                    r.a.send(ByteArray(64 * 1024))
                    progress.incrementAndGet()
                }
            } catch (t: Throwable) {
                outcome.set(describe(t, currentCoroutineContext().isActive))
            }
        }
        var last = -1L
        val reachedStuck = withTimeoutOrNull(20_000) {
            while (true) {
                val p = progress.get()
                if (p == last && p > 0) break
                last = p
                withContext(Dispatchers.IO) { Thread.sleep(500) }
            }
            true
        } == true
        withContext(Dispatchers.IO) { withTimeoutOrNull(5_000) { action(r) } }
        val finished = withContext(Dispatchers.IO) {
            withTimeoutOrNull(10_000) {
                while (outcome.get() == null) delay(20)
                true
            }
        }
        report.appendText("$label reachedStuck=$reachedStuck frames=${progress.get()} -> ${outcome.get() ?: "WEDGED after ${finished}"}\n")
        scope.cancel()
    }

    /** Cancel the caller, then send. */
    private fun cancelledCaller(label: String, kill: suspend (Rig) -> Unit) = rig { r ->
        kill(r)
        delay(100)
        val outcome = AtomicReference<String?>(null)
        val job = launch(Dispatchers.IO) {
            currentCoroutineContext().cancel(CancellationException("caller-rig"))
            try {
                r.a.send(ByteArray(8))
                outcome.set("returned")
            } catch (t: Throwable) {
                outcome.set(describe(t, currentCoroutineContext().isActive))
            }
        }
        job.join()
        report.appendText("$label cancelled-caller -> ${outcome.get()}\n")
    }

    /** Send on a live link with the caller cancelled. */
    @Test
    fun cancelledOnLive() = cancelledCaller("live link") { }

    @Test
    fun cancelledOnLocalClose() = cancelledCaller("local Connection.close") { it.a.close() }

    @Test
    fun cancelledOnRemoteClose() = cancelledCaller("remote Connection.close") { it.b.close() }

    @Test
    fun cancelledOnSocketCancel() = cancelledCaller("local socket cancel") { it.dialed.cancel() }

    @Test
    fun stuckThenLocalConnectionClose() = stuck("stuck, local Connection.close") { it.a.close() }

    @Test
    fun stuckThenLocalSocketClose() = stuck("stuck, local socket.close") { it.dialed.close() }

    @Test
    fun stuckThenLocalSocketCancel() = stuck("stuck, local socket.cancel") { it.dialed.cancel() }

    @Test
    fun stuckThenSelectorClose() = stuck("stuck, selector.close") { it.selector.close() }

    @Test
    fun stuckThenRemoteConnectionClose() = stuck("stuck, remote Connection.close") { it.b.close() }

    @Test
    fun stuckThenRemoteSocketClose() = stuck("stuck, remote socket.close") { it.accepted.close() }

    @Test
    fun stuckThenRemoteSocketCancel() = stuck("stuck, remote socket.cancel") { it.accepted.cancel() }

    @Test
    fun idleSelectorCloseThenSend() = rig { r ->
        r.selector.close()
        delay(100)
        var attempts = 0
        val outcome = withContext(Dispatchers.IO) {
            withTimeoutOrNull(10_000) {
                while (attempts < 2_000) {
                    attempts++
                    try {
                        r.a.send(ByteArray(64 * 1024))
                    } catch (t: Throwable) {
                        return@withTimeoutOrNull describe(t, currentCoroutineContext().isActive)
                    }
                }
                "never threw"
            }
        }
        report.appendText("idle, selector.close attempts=$attempts -> ${outcome ?: "WEDGED"}\n")
    }
}
