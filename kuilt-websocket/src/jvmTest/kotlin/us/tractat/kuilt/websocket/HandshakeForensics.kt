package us.tractat.kuilt.websocket

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.application.install
import io.ktor.server.engine.EngineConnectorConfig
import io.ktor.server.plugins.origin
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import kotlinx.coroutines.CancellationException
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.TransportCapability
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.time.TimeSource

/**
 * The record of one test server's life and of every dial made at it, rendered into the failure only
 * when a dial fails, so a handshake red names its own race from a single test-results XML (#3754).
 *
 * A failed WebSocket dial says "expected 101 but was 404" or "the server prematurely closed the
 * connection" and nothing else. Neither says **which listener** answered. #2433 established that a
 * 404 from a kuilt route means the path did not resolve, and that `start(wait = false)` cannot return
 * before the route is installed — so a 404 at a healthy rig means the request reached a *different*
 * application. This keeps the evidence to tell those apart:
 *
 *  - the instants the route was installed, the server started, and each dial began and failed;
 *  - every request **this** server received, with the peer id it carried and the status it answered;
 *  - on failure only, the TCP listeners on the port, from `lsof`, beside this process's own pid.
 *
 * If the server recorded no request after the dial, the dial reached some other listener on its
 * port. If it recorded the request, its own answer is in the log.
 *
 * Instants are `at=+<ms>` on one monotonic clock started when the rig was built, with that start's
 * wall-clock instant in the header so the record can be lined up against other processes' logs.
 *
 * The green path costs a few strings per test: the listener scan runs only from [failureReport].
 */
internal class HandshakeForensics(private val path: String) {
    private val origin = TimeSource.Monotonic.markNow()
    private val wallOrigin = Instant.now()
    private val events = ConcurrentLinkedQueue<Event>()

    @Volatile
    private var boundPort: Int? = null

    private class Event(val atMillis: Long, val kind: Kind, val line: String)

    private enum class Kind { SERVER_REQUEST, OTHER }

    private fun record(kind: Kind, line: String) {
        events += Event(origin.elapsedNow().inWholeMilliseconds, kind, line)
    }

    /**
     * Install the request recorder on [application]. Call it inside the `embeddedServer` module
     * lambda, **before** the loom, so a request the loom's route never sees is still recorded.
     */
    fun install(application: Application) {
        application.install(
            createApplicationPlugin("HandshakeForensics") {
                onCall { call -> record(Kind.SERVER_REQUEST, "server.request ${call.describe()}") }
                on(ResponseSent) { call ->
                    record(Kind.OTHER, "server.response ${call.request.uri} status=${call.response.status()}")
                }
            },
        )
    }

    /** Call in the module lambda once the route is mounted. */
    fun routeInstalled() = record(Kind.OTHER, "server.route-installed path=$path")

    /** Call once `start(wait = false)` has returned, with the connector the engine actually bound. */
    fun serverStarted(connector: EngineConnectorConfig) {
        boundPort = connector.port
        record(Kind.OTHER, "server.started bound=${connector.host}:${connector.port}")
    }

    /** Record a dial at [target], and on a failure attach [failureReport] to it as a suppressed exception. */
    suspend fun <T> dial(target: String, block: suspend () -> T): T {
        val dialAt = origin.elapsedNow().inWholeMilliseconds
        record(Kind.OTHER, "client.dial target=$target")
        try {
            return block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            record(Kind.OTHER, "client.dial-failed ${failure::class.simpleName}: ${failure.message}")
            failure.rootCause().addSuppressed(HandshakeForensicsReport(failureReport(dialAt)))
            throw failure
        }
    }

    /** The whole record, a verdict on whether this server saw the dial, and the port's listeners. */
    fun failureReport(dialAtMillis: Long): String {
        val log = events.sortedBy { it.atMillis }
        val seen = log.filter { it.kind == Kind.SERVER_REQUEST && it.atMillis >= dialAtMillis }
        val verdict =
            if (seen.isEmpty()) {
                "THIS server received no request after the dial at=+${dialAtMillis}ms, so whatever answered it " +
                    "(a 404, a premature close) was another listener on port $boundPort. On macOS a socket bound " +
                    "to 127.0.0.1:$boundPort takes loopback dials away from a server bound to the wildcard; see " +
                    "the listeners below."
            } else {
                "THIS server received ${seen.size} request(s) after the dial; its own answer is in the log."
            }
        val port = boundPort
        return buildString {
            appendLine("Handshake forensics (#3754) — rig clock started at $wallOrigin, this pid=${ProcessHandle.current().pid()}")
            appendLine("verdict: $verdict")
            log.forEach { appendLine("  at=+${it.atMillis}ms ${it.line}") }
            appendLine(if (port == null) "listeners: the server never reported a bound port" else listenersOn(port))
        }
    }
}

/** Carries the rendered record; attached as a suppressed exception so the dial's own failure keeps its type. */
internal class HandshakeForensicsReport(report: String) : Throwable(report, null, false, false)

/**
 * The innermost throwable of the cause chain, which is where the report has to hang. With assertions
 * on, kotlinx.coroutines recovers stack traces: each resumption rethrows a fresh copy whose cause is
 * the original, and drops the instance in between. A report on the caught instance is lost with it
 * (measured on the CIO conformance rig: the XML had neither the report nor a `Suppressed:` line). The
 * innermost one is the original, and every copy keeps it as its cause.
 */
internal fun Throwable.rootCause(): Throwable {
    var current = this
    val visited = mutableSetOf<Throwable>()
    while (visited.add(current)) current = current.cause ?: break
    return current
}

/** The forensic reports anywhere on [this]'s cause chain. */
internal fun Throwable.forensicReports(): List<HandshakeForensicsReport> =
    generateSequence(this) { it.cause.takeIf { cause -> cause !== it } }
        .take(MAX_CAUSE_DEPTH)
        .flatMap { it.suppressed.asSequence() }
        .filterIsInstance<HandshakeForensicsReport>()
        .toList()

private const val MAX_CAUSE_DEPTH = 32

/**
 * Decorates the client loom of a conformance rig so every dial runs through [forensics]. The suite's
 * `join` reaches [weave] through [Loom]'s default `join`, so this overrides only what a client loom
 * does, and hands back the real [Seam] untouched.
 */
internal class ForensicLoom(
    private val delegate: Loom,
    private val forensics: HandshakeForensics,
    private val describe: (Rendezvous) -> String,
) : Loom {
    override suspend fun weave(rendezvous: Rendezvous): Seam =
        forensics.dial(describe(rendezvous)) { delegate.weave(rendezvous) }

    override fun capability(): TransportCapability = delegate.capability()

    override fun availability(): FabricAvailability = delegate.availability()
}

private fun ApplicationCall.describe(): String =
    "${request.httpMethod.value} ${request.uri} from=${request.origin.remoteAddress}:${request.origin.remotePort}"

/**
 * The TCP listeners on [port], from `lsof`, which both macOS and the Linux runners carry. Bounded,
 * and never throws: a missing tool is reported rather than masking the dial failure it explains.
 */
internal fun listenersOn(port: Int): String =
    try {
        val process =
            ProcessBuilder("lsof", "-nP", "-iTCP:$port", "-sTCP:LISTEN")
                .redirectErrorStream(true)
                .start()
        if (!process.waitFor(LSOF_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            "listeners on :$port: lsof did not answer within ${LSOF_TIMEOUT_SECONDS}s"
        } else {
            val out = process.inputStream.bufferedReader().readText().trimEnd()
            "listeners on :$port (lsof exit=${process.exitValue()}):\n" + out.ifEmpty { "  (none)" }
        }
    } catch (failure: java.io.IOException) {
        "listeners on :$port: lsof unavailable — ${failure.message}"
    }

private const val LSOF_TIMEOUT_SECONDS = 5L
