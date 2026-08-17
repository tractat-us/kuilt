package us.tractat.kuilt.websocket

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Suspends until an HTTP request for [path] **resolves** at [host]:[port] — so a WebSocket dial that
 * follows can no longer fail for the one reason a handshake exception cannot tell you about, namely
 * "nothing here serves that path". Throws naming the last status it saw if [timeout] expires.
 *
 * **Why a plain, non-upgrade GET is the right probe, and what its success status actually is.**
 * Ktor mounts `webSocket(path)` as `route(path, Get) { header(Connection, "Upgrade") {
 * header(Upgrade, "websocket") { … } } }` (`io.ktor.server.websocket.webSocketRaw`), and routing
 * maps *which* selector failed onto distinct statuses. Measured against a live
 * [KtorServerLoom] on Ktor 3.5.2:
 *
 * | request                                  | status |
 * |------------------------------------------|--------|
 * | right path + upgrade headers             | 101    |
 * | right path, **no** upgrade headers       | 400    |
 * | right path, POST + upgrade headers       | 405    |
 * | **wrong path**, any headers              | 404    |
 *
 * So `404` — and only `404` — means the *path* did not resolve, which is exactly the question worth
 * asking before dialling. A plain GET is therefore a complete liveness probe **and** it cannot
 * disturb the system under test: it never upgrades, so it never lands a stray [Seam] in the server
 * loom's accept queue the way a real handshake probe would.
 *
 * Note the success status is `400`, not `200` — a live kuilt WebSocket route has no non-upgrade
 * handler to answer with. Anything that is not a 404 proves the path resolved; this deliberately
 * does not assert `400` exactly, because that particular code is Ktor's internal mapping for a
 * failed header selector and is far less stable than "an unmatched path is a 404".
 *
 * Recorded for #2433, where a single contended-box run of
 * [WebSocketPingHalfOpenTest] failed its dial with `expected status code 101 but was 404` and left
 * no way to tell a missing route from a foreign listener.
 *
 * Runs each attempt on a short-lived daemon thread rather than blocking the caller: a server built
 * by `embeddedServer` *inside* `runBlocking` inherits that `runBlocking`'s event loop as its
 * application context, so blocking the calling thread to read a socket could stall the very server
 * being probed.
 */
internal suspend fun awaitPathResolves(
    host: String,
    port: Int,
    path: String,
    timeout: Duration = ROUTE_LIVENESS_TIMEOUT,
) {
    val deadline = TimeSource.Monotonic.markNow() + timeout
    var last: Observation
    while (true) {
        last = probeOffThread(host, port, path)
        if (last is Observation.Status && last.code != HTTP_NOT_FOUND) return
        if (deadline.hasPassedNow()) break
        delay(RETRY_INTERVAL)
    }
    throw AssertionError(
        "Route \"$path\" never resolved at $host:$port within $timeout — last observation: ${last.detail}. " +
            "A 404 means the request reached a live listener whose routing table has no such path: either " +
            "the route was never installed on this server, or this port is being served by a different " +
            "application. Anything else here means the port was not reachable at all. " +
            "(A live kuilt WebSocket route answers a plain GET with 400 Bad Request — see #2433.)",
    )
}

private sealed interface Observation {
    val detail: String

    data class Status(val code: Int, override val detail: String) : Observation

    data class Unreachable(override val detail: String) : Observation
}

/** One blocking request/response, off the caller's thread, delivered back through a deferred. */
private suspend fun probeOffThread(host: String, port: Int, path: String): Observation {
    val result = CompletableDeferred<Observation>()
    thread(isDaemon = true, name = "route-liveness-probe") { result.complete(probe(host, port, path)) }
    return result.await()
}

private fun probe(host: String, port: Int, path: String): Observation =
    try {
        Socket(host, port).use { socket ->
            socket.soTimeout = SOCKET_TIMEOUT_MILLIS
            val request =
                "GET $path HTTP/1.1\r\n" +
                    "Host: $host:$port\r\n" +
                    "Connection: close\r\n\r\n"
            socket.getOutputStream().apply {
                write(request.toByteArray())
                flush()
            }
            val statusLine =
                BufferedReader(InputStreamReader(socket.getInputStream())).readLine()?.trim()
                    ?: return Observation.Unreachable("the connection closed without a response")
            val code = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
                ?: return Observation.Unreachable("an unparseable status line \"$statusLine\"")
            Observation.Status(code, statusLine)
        }
    } catch (failure: IOException) {
        Observation.Unreachable("${failure::class.simpleName}: ${failure.message}")
    }

/**
 * Generous: this bounds a real socket on a possibly contended box, and is a wedge backstop rather
 * than an assertion — a healthy server resolves on the first attempt, so the value is never reached
 * on a green run.
 */
private val ROUTE_LIVENESS_TIMEOUT = 15.seconds
private val RETRY_INTERVAL = 25.milliseconds
private const val SOCKET_TIMEOUT_MILLIS = 5_000
private const val HTTP_NOT_FOUND = 404
