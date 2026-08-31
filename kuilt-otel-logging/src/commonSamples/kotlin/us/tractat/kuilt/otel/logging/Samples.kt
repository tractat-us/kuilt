package us.tractat.kuilt.otel.logging

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.otel.WarpLogRecordExporter
import us.tractat.kuilt.store.InMemoryDurableStore
import kotlin.random.Random
import kotlin.time.Clock

/** @suppress — sample only */
internal fun sampleInstallLogCapture(scope: CoroutineScope): LogCaptureInstallation {
    // The durable, offline-first buffer captured lines are written into. In
    // production wire a platform WAL instead of the in-memory store.
    val exporter = WarpLogRecordExporter(
        replica = ReplicaId("device-uuid-abc123"),
        store = InMemoryDurableStore(),
    )

    // One call, identical on JVM, Android, iOS, macOS and wasmJs. Time and
    // randomness are injected — `Clock.System` and `Random.Default` in production,
    // a virtual clock and a seeded RNG in a test.
    val installation = installLogCapture(
        exporter = exporter,
        config = CaptureConfig(minLevel = LogLevel.INFO),
        clock = Clock.System,
        random = Random.Default,
        scope = scope,
    )

    // Your app keeps logging exactly the way it always has — no call-site change.
    // Every line at or above INFO now also lands in the buffer.
    val log = KotlinLogging.logger("com.example.Checkout")
    log.info { "user checked out" }

    // `close()` is how you stop capture: it restores the previous appender and
    // stops buffering. Cancelling `scope` alone leaks the appender — see
    // LogCaptureInstallation. Hold the handle for as long as capture should run.
    return installation
}

/** @suppress — sample only */
internal suspend fun sampleWithActiveTrace() {
    // installLogCapture(...) was called with CoroutineContextTraceProvider(); on
    // wasmJs / iOS / macOS that is how the sampling gate learns the current trace.
    val log = KotlinLogging.logger("com.example.Checkout")

    // Whoever starts a span wraps the work. Every line logged inside — here and in
    // any child coroutine — is stamped with this trace when the sampler kept it, or
    // dropped when it didn't. No call-site change to the logging itself.
    val trace = ActiveTrace(
        traceId = ByteString(ByteArray(16) { 1 }),
        spanId = ByteString(ByteArray(8) { 2 }),
        sampled = true,
    )
    withActiveTrace(trace) {
        log.info { "charged the card" } // stamped with trace/span id
    }

    // Outside any withActiveTrace scope the line is untraced — captured unstamped
    // (default) or dropped, per CaptureConfig.untracedPolicy.
    log.info { "background heartbeat" }
}

/** @suppress — sample only */
internal suspend fun sampleWithLogContext() {
    val log = KotlinLogging.logger("com.example.Session")

    // This process runs two sessions at once. A CaptureConfig.attributeMapper is
    // installed on the whole process, so it could only ever stamp whichever session
    // is "current" — and would stamp the other session's lines with it too. Binding
    // the id to the scope that emits makes it per-emitter instead.
    withLogContext("session.id" to "server-game-42") {
        log.info { "dealt the opening hand" } // session.id = server-game-42
    }

    // Concurrently, on another scope, with its own binding. Neither borrows the
    // other's id, however they interleave.
    withLogContext("session.id" to "mesh-7") {
        // Nesting merges, and the inner scope wins a collision — narrower scope wins.
        withLogContext("turn" to "3") {
            log.info { "peer joined" } // session.id = mesh-7, turn = 3
        }
    }

    // Outside any scope, capture is exactly what it was before.
    log.info { "background heartbeat" }
}
