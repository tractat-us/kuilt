package us.tractat.kuilt.demo.tap

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import us.tractat.kuilt.demo.TapWire
import us.tractat.kuilt.otel.LogRecord
import us.tractat.kuilt.otel.tap.LogTapClient
import us.tractat.kuilt.otel.tap.MetricSnapshot
import us.tractat.kuilt.otel.tap.MetricTapClient
import us.tractat.kuilt.websocket.KtorClientLoom
import us.tractat.kuilt.websocket.WebSocketAdvertisement

/**
 * Reach into a running relay and pull its telemetry.
 *
 * The relay keeps a live record of what it's doing — every log line it writes, and a
 * few running numbers (peers connected, patches on the quilt). Normally that stays
 * inside the relay process. This harness joins the relay's two telemetry taps, pulls a
 * snapshot of both, and prints a small panel — then, with `--tail`, keeps streaming the
 * relay's logs live until you stop it.
 *
 * ```
 * ./gradlew :demo-relay:run                                   # start the relay first
 * ./gradlew :demo-tap:run                                     # one-shot telemetry panel
 * ./gradlew :demo-tap:run --args="--tail"                     # panel, then live log tail
 * ./gradlew :demo-tap:run --args="--host=1.2.3.4 --port=9191" # reach a relay elsewhere
 * ```
 *
 * The reach-in is symmetric kuilt underneath: the relay *hosts* a tiny session over the
 * tap fabric and the harness *joins* it, exactly as any two peers meet. Because the taps
 * replicate CRDT-backed buffers, a pull is always a whole, consistent snapshot — never a
 * torn half — and a reconnecting tail never doubles a line or drops one.
 */
fun main(args: Array<String>) {
    val host = args.firstNotNullOfOrNull { it.substringAfter("--host=", "").ifBlank { null } } ?: "localhost"
    val port = args.firstNotNullOfOrNull { it.substringAfter("--port=", "").toIntOrNull() } ?: TapWire.DEFAULT_PORT
    val tail = args.any { it == "--tail" }

    // A CIO WebSocket client — the fabric the harness joins the relay's taps over. One
    // client loom serves both taps; each join addresses a different path + peer id.
    val http = HttpClient(CIO) { install(WebSockets) }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val clientLoom = KtorClientLoom(http)

    val logTap = WebSocketAdvertisement(
        url = "ws://$host:$port${TapWire.LOG_PATH}",
        serverPeerId = TapWire.LOG_SERVER_ID,
        sessionName = "patchwork-log-tap",
    )
    val metricTap = WebSocketAdvertisement(
        url = "ws://$host:$port${TapWire.METRIC_PATH}",
        serverPeerId = TapWire.METRIC_SERVER_ID,
        sessionName = "patchwork-metric-tap",
    )

    runBlocking {
        println("Reaching into the Patchwork relay at $host:$port ...")
        val logClient = LogTapClient(clientLoom.join(logTap), scope)
        val metricClient = MetricTapClient(clientLoom.join(metricTap), scope)

        // pull() waits for the relay's first-contact full state to actually merge, so
        // each snapshot is complete — never the still-empty initial state.
        val logs = logClient.pull()
        val metrics = metricClient.pull()
        printPanel(logs, metrics)

        if (tail) {
            // tail() replays everything already known, then streams new lines live —
            // each exactly once, in the relay's order — until this process is killed.
            println()
            println("Live log tail (Ctrl-C to stop):")
            logClient.tail().collect { record -> println("  " + formatLog(record)) }
        } else {
            logClient.close()
            metricClient.close()
            scope.cancel()
            http.close()
        }
    }
}

/** Print the small telemetry panel: the metric numbers, then the tail of the log. */
private fun printPanel(logs: List<LogRecord>, metrics: MetricSnapshot) {
    println()
    println("== Patchwork relay telemetry ==")
    println()

    println("metrics:")
    metrics.gauges.forEach { (key, value) -> println("  ${key.name.padEnd(28)} = $value  (gauge)") }
    metrics.sums.forEach { (key, value) -> println("  ${key.name.padEnd(28)} = $value  (sum)") }
    metrics.cardinalities.forEach { (key, value) -> println("  ${key.name.padEnd(28)} ~ $value  (distinct)") }
    if (metrics.gauges.isEmpty() && metrics.sums.isEmpty() && metrics.cardinalities.isEmpty()) {
        println("  (none reported yet)")
    }

    println()
    val recent = logs.takeLast(LAST_LOG_LINES)
    println("logs: ${logs.size} captured, last ${recent.size}:")
    recent.forEach { record -> println("  " + formatLog(record)) }
    if (logs.isEmpty()) println("  (none captured yet)")
}

/** One log line, rendered as `[LEVEL] body`. */
private fun formatLog(record: LogRecord): String {
    val level = record.severityText ?: "?"
    return "[$level] ${record.body.orEmpty()}"
}

private const val LAST_LOG_LINES = 10
