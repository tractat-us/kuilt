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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.TransportCapability
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.TimeSource

/**
 * The record of one test server's life and of every dial made at it, rendered into the failure only
 * when a dial fails or is cancelled, so a handshake red names its own race from a single test-results
 * XML (#3754, #3783).
 *
 * A failed WebSocket dial says "expected 101 but was 404" or "the server prematurely closed the
 * connection" and nothing else. Neither says **which listener** answered. #2433 established that a
 * 404 from a kuilt route means the path did not resolve, and that `start(wait = false)` cannot return
 * before the route is installed — so a 404 at a healthy rig means the request reached a *different*
 * application. This keeps the evidence to tell those apart:
 *
 *  - the instants the route was installed, the server started, and each dial began and failed or
 *    was cancelled;
 *  - every request **this** server received, with the peer id it carried and the status it answered;
 *  - on failure only, the TCP listeners on the port, from `lsof`, beside this process's own pid.
 *
 * If the server recorded no request after the dial, some other listener answered. For a server on
 * the wildcard, that can be a socket on the same port. A server bound to one exact address (the rigs
 * bind `127.0.0.1` since #3774) cannot share it, so the dial went to a different address or port.
 * The verdict says which case applies. If the server recorded the request, its own answer is in the
 * log.
 *
 * Instants are `at=+<ms>` on one monotonic clock started when the rig was built, with that start's
 * wall-clock instant in the header so the record can be lined up against other processes' logs.
 *
 * The green path costs a few strings per test: the listener scan runs only from [failureReport].
 */
internal class HandshakeForensics(
    private val path: String,
    timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) {
    private val origin = timeSource.markNow()
    private val wallOrigin = Instant.now()
    private val events = ConcurrentLinkedQueue<Event>()

    @Volatile
    private var boundPort: Int? = null

    @Volatile
    private var boundHost: String? = null

    /**
     * The order events happened in. "After the dial" is decided by this rather than by [Event.atMillis]:
     * a request that finished in the same millisecond the dial began, such as a route probe just
     * before it, would otherwise count as the dial's.
     */
    private val sequence = AtomicLong()

    private class Event(val seq: Long, val atMillis: Long, val kind: Kind, val line: String)

    private enum class Kind { SERVER_REQUEST, OTHER }

    private fun record(kind: Kind, line: String): Event =
        Event(sequence.getAndIncrement(), origin.elapsedNow().inWholeMilliseconds, kind, line).also { events += it }

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
    fun routeInstalled() {
        record(Kind.OTHER, "server.route-installed path=$path")
    }

    /** Call once `start(wait = false)` has returned, with the connector the engine actually bound. */
    fun serverStarted(connector: EngineConnectorConfig) {
        boundPort = connector.port
        boundHost = connector.host
        record(Kind.OTHER, "server.started bound=${connector.host}:${connector.port}")
    }

    /**
     * Record a dial at [target], and when it ends in anything but a seam, attach [failureReport] to
     * what it threw as a suppressed exception.
     *
     * That includes a **cancellation** (#3783). A dial that hangs until a bound around it expires
     * fails the test with that bound's `TimeoutCancellationException`, which is the one failure that
     * would otherwise carry no record. The cancellation is rethrown untouched, so it reaches the
     * caller as the cancellation it is and never as a failure. Whose it was decides only the record's
     * wording, never the control flow. [currentCoroutineContext]'s `isActive` is the predicate
     * `ensureActive()` throws on: inactive means the caller's job was cancelled while the dial was in progress; active
     * means the dial threw a cancellation of its own, such as a bound inside it expiring.
     */
    suspend fun <T> dial(target: String, block: suspend () -> T): T {
        val dialAt = record(Kind.OTHER, "client.dial target=$target")
        try {
            return block()
        } catch (cancelled: CancellationException) {
            val outcome = if (currentCoroutineContext().isActive) Outcome.CANCELLED_BY_DIAL else Outcome.CANCELLED_BY_CALLER
            record(Kind.OTHER, "client.dial-cancelled ${outcome.label} ${cancelled::class.simpleName}: ${cancelled.message}")
            cancelled.rootCause().addSuppressed(HandshakeForensicsReport(failureReport(dialAt, outcome, cancelled.cause)))
            throw cancelled
        } catch (failure: Throwable) {
            record(Kind.OTHER, "client.dial-failed ${failure::class.simpleName}: ${failure.message}")
            failure.rootCause().addSuppressed(HandshakeForensicsReport(failureReport(dialAt)))
            throw failure
        }
    }

    /** How a dial ended without a seam, which decides how the verdict opens. */
    enum class Outcome(val label: String) {
        FAILED("failed"),
        CANCELLED_BY_CALLER("by its caller"),
        CANCELLED_BY_DIAL("by the dial itself, its caller still live"),
    }

    /** The whole record, a verdict on whether this server saw [dial], and the port's listeners. */
    private fun failureReport(dial: Event, outcome: Outcome = Outcome.FAILED, cancelCause: Throwable? = null): String {
        val dialAtMillis = dial.atMillis
        val log = events.sortedBy { it.seq }
        val seen = log.filter { it.kind == Kind.SERVER_REQUEST && it.seq > dial.seq }
        val verdict =
            when {
                outcome != Outcome.FAILED -> cancelledVerdict(outcome, dialAtMillis, seen.size, cancelCause)
                seen.isNotEmpty() ->
                    "THIS server received ${seen.size} request(s) after the dial; its own answer is in the log."
                boundHost in WILDCARD_HOSTS ->
                    "THIS server received no request after the dial at=+${dialAtMillis}ms, so whatever answered it " +
                        "(a 404, a premature close) was another listener on port $boundPort. This server holds " +
                        "the wildcard, and on macOS a socket bound to 127.0.0.1:$boundPort takes loopback dials " +
                        "away from it; see the listeners below."
                else ->
                    "THIS server received no request after the dial at=+${dialAtMillis}ms. It holds " +
                        "$boundHost:$boundPort exactly, so no other socket can bind that address: whatever " +
                        "answered (a 404, a premature close) was reached at a different address or port. Compare " +
                        "the dial target with server.started, and see the listeners below."
            }
        val port = boundPort
        return buildString {
            appendLine("Handshake forensics (#3754) — rig clock started at $wallOrigin, this pid=${ProcessHandle.current().pid()}")
            appendLine("verdict: $verdict")
            log.forEach { appendLine("  at=+${it.atMillis}ms ${it.line}") }
            appendLine(if (port == null) "listeners: the server never reported a bound port" else listenersOn(port))
        }
    }

    private fun cancelledVerdict(outcome: Outcome, dialAtMillis: Long, seen: Int, cancelCause: Throwable?): String {
        val whose =
            if (outcome == Outcome.CANCELLED_BY_CALLER) {
                "The dial was still in progress when its caller's job was cancelled" +
                    (cancelCause?.let { " (cause: ${it::class.simpleName}: ${it.message})" } ?: "") + "."
            } else {
                "The dial threw a cancellation of its own while its caller was still live, such as a bound " +
                    "inside the dial expiring; a delegate minting one is itself a contract breach, since " +
                    "Loom.weave must not."
            }
        val reached =
            if (seen > 0) {
                "THIS server received $seen request(s) after the dial at=+${dialAtMillis}ms, so the request " +
                    "arrived and the handshake never completed; its side is in the log."
            } else {
                "THIS server received no request after the dial at=+${dialAtMillis}ms, so the connection or " +
                    "the request never reached its application. Compare the dial target with server.started, " +
                    "and see the listeners below."
            }
        return "$whose $reached"
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

/** How a connector reports the wildcard: Ktor's default host, and the IPv6 and empty spellings. */
private val WILDCARD_HOSTS = setOf("0.0.0.0", "::", "*", "")

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
 * The TCP listeners on [port], from `lsof`, which both macOS and the Linux runners carry, and the
 * command line of every listening process that is not this one. A Gradle test worker's command line
 * names the checkout it runs from, which is what says whose server took the port. Bounded, and never
 * throws: a missing tool is reported rather than masking the dial failure it explains.
 */
internal fun listenersOn(port: Int): String {
    val self = ProcessHandle.current().pid()
    val lsof = runBounded("lsof", "-w", "-nP", "-iTCP:$port", "-sTCP:LISTEN")
    val owners =
        lsof.lines().drop(1)
            .mapNotNull { it.trim().split(WHITESPACE).getOrNull(1)?.toLongOrNull() }
            .distinct()
            .filter { it != self }
            .map { pid -> "  pid=$pid: " + runBounded("ps", "-o", "command=", "-p", "$pid").take(MAX_COMMAND_CHARS) }
    return buildString {
        append("listeners on :$port:\n").append(lsof.ifEmpty { "  (none)" })
        if (owners.isNotEmpty()) append("\nother listening processes:\n").append(owners.joinToString("\n"))
    }
}

/**
 * The stdout of [command], trimmed, or a line saying why there is none. The output is read while the
 * process runs, since a long command line can fill the pipe and stall a process nobody is reading.
 */
private fun runBounded(vararg command: String): String =
    try {
        val process = ProcessBuilder(*command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val output = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().readText().trimEnd() }
        if (process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            output.get(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } else {
            process.destroyForcibly()
            "${command.first()} did not answer within ${PROCESS_TIMEOUT_SECONDS}s"
        }
    } catch (failure: IOException) {
        "${command.first()} unavailable — ${failure.message}"
    } catch (failure: ExecutionException) {
        "${command.first()} output unreadable — ${failure.cause}"
    } catch (failure: TimeoutException) {
        "${command.first()} output not read within ${PROCESS_TIMEOUT_SECONDS}s — $failure"
    }

private val WHITESPACE = Regex("""\s+""")
private const val PROCESS_TIMEOUT_SECONDS = 5L
private const val MAX_COMMAND_CHARS = 400
