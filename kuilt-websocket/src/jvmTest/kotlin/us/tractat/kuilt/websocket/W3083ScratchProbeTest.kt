package us.tractat.kuilt.websocket

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.close
import io.ktor.server.application.install
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.Test
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

/** Scratch: what `session.send` throws at this repo's Ktor pin. Deleted before the PR is ready. */
class W3083ScratchProbeTest {
    private val report = File(System.getProperty("user.home"), ".claude/jobs/3635b804/tmp/w3083-ws-probe.txt").apply { parentFile.mkdirs() }

    private suspend fun awaitDead(session: DefaultWebSocketSession): Boolean {
        repeat(500) {
            if (session.outgoing.isClosedForSend) return true
            delay(10)
        }
        return false
    }

    private suspend fun row(label: String, session: DefaultWebSocketSession) {
        val dead = awaitDead(session)
        val conn = WebSocketConnection(session)
        val outcome = try {
            conn.send(byteArrayOf(1))
            "returned"
        } catch (t: Throwable) {
            "${t::class.qualifiedName}: ${t.message} isCE=${t is CancellationException} callerActive=${currentCoroutineContext().isActive} cause=${t.cause}"
        }
        report.appendText("$label dead=$dead -> $outcome\n")
    }

    private fun onServer(block: suspend (Int, Channel<DefaultWebSocketServerSession>) -> Unit) {
        val sessions = Channel<DefaultWebSocketServerSession>(Channel.UNLIMITED)
        val server = embeddedServer(Netty, port = 0) {
            install(WebSockets)
            routing {
                webSocket("/p") {
                    sessions.send(this)
                    closeReason.await()
                }
            }
        }
        server.start(wait = false)
        try {
            runBlocking {
                val port = server.engine.resolvedConnectors().first().port
                block(port, sessions)
            }
        } finally {
            server.stop(100, 1_000)
        }
    }

    private fun engineRows(name: String, engine: HttpClientEngineFactory<*>) = onServer { port, sessions ->
        val http = HttpClient(engine) { install(ClientWebSockets) }
        try {
            // Server path: the client closes with a close frame.
            run {
                val client = http.webSocketSession("ws://127.0.0.1:$port/p")
                val server = withTimeout(10_000) { sessions.receive() }
                client.close()
                row("$name server-path remote-close-frame", server)
                awaitDead(client)
            }
            // Server path: the client aborts.
            run {
                val client = http.webSocketSession("ws://127.0.0.1:$port/p")
                val server = withTimeout(10_000) { sessions.receive() }
                client.cancel()
                row("$name server-path remote-abort", server)
            }
            // Server path: local close.
            run {
                val client = http.webSocketSession("ws://127.0.0.1:$port/p")
                val server = withTimeout(10_000) { sessions.receive() }
                server.close()
                row("$name server-path local-close", server)
                client.cancel()
            }
            // Client path: the server closes.
            run {
                val client = http.webSocketSession("ws://127.0.0.1:$port/p")
                val server = withTimeout(10_000) { sessions.receive() }
                server.close()
                row("$name client-path remote-close-frame", client)
            }
            // Client path: local close.
            run {
                val client = http.webSocketSession("ws://127.0.0.1:$port/p")
                val server = withTimeout(10_000) { sessions.receive() }
                client.close()
                row("$name client-path local-close", client)
                server.close()
            }
            // Client path: the server's coroutine dies.
            run {
                val client = http.webSocketSession("ws://127.0.0.1:$port/p")
                val server = withTimeout(10_000) { sessions.receive() }
                server.cancel()
                row("$name client-path remote-abort", client)
            }
        } finally {
            http.close()
        }
    }

    @Test
    fun cio() = engineRows("cio", CIO)

    @Test
    fun okhttp() = engineRows("okhttp", OkHttp)
}
