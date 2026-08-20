package us.tractat.kuilt.demo.relay

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.demo.TapWire
import us.tractat.kuilt.otel.MetricKey
import us.tractat.kuilt.otel.MetricKind
import us.tractat.kuilt.otel.WarpLogRecordExporter
import us.tractat.kuilt.otel.WarpMetricExporter
import us.tractat.kuilt.otel.logging.CaptureConfig
import us.tractat.kuilt.otel.logging.installLogCapture
import us.tractat.kuilt.otel.tap.installLogTap
import us.tractat.kuilt.otel.tap.installMetricTap
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.websocket.KtorServerLoom
import kotlin.coroutines.coroutineContext
import kotlin.random.Random
import kotlin.time.Clock

// The relay's own application logger. Two deliberate choices:
//
//  - Named OUTSIDE kuilt's own `us.tractat.kuilt` package: log capture drops
//    everything under that prefix (kuilt never captures its own internals — see
//    :kuilt-otel-logging), so a relay logger under it would be captured into
//    nothing. A plain "patchwork.relay" name reads as an application, and its lines
//    are exactly what a reach-in harness pulls.
//  - Created LAZILY, so the logger is built only on first use — which happens after
//    installLogCapture (below) has switched kotlin-logging to its capturing factory.
//    A logger built *before* that switch binds to the plain SLF4J backend for good
//    and its lines never reach the capture buffer. Lazy defers construction past the
//    install, so every relay line is captured. (This bit us once: an eager top-level
//    val initialises at class-load, before installRelayObservability's body runs.)
private val appLog by lazy { KotlinLogging.logger("patchwork.relay") }

/** The relay's telemetry key names — the numbers a puller reads off the metric tap. */
private object RelayMetrics {
    val PEERS_CONNECTED = MetricKey("patchwork.peers.connected", MetricKind.GAUGE)
    val QUILT_PATCHES = MetricKey("patchwork.quilt.patches", MetricKind.GAUGE)
    val QUILT_UPDATES = MetricKey("patchwork.quilt.updates", MetricKind.SUM)
}

/**
 * Turn the relay into something you can *reach into*.
 *
 * A running peer normally keeps its logs and metrics to itself — on a phone or a CI
 * box those are exactly the things you cannot get at. This wires the relay so a
 * laptop can pull them out live:
 *
 * - **Capture.** [installLogCapture] hooks the relay's own log output into a
 *   CRDT-backed buffer, so every line the relay writes is retained and replayable.
 * - **Metrics.** A [WarpMetricExporter] holds a few honest numbers about the table —
 *   how many peers are connected, how many patches are on the quilt, how many quilt
 *   updates have flowed — each backed by the CRDT that matches its shape.
 * - **Taps.** [installLogTap] and [installMetricTap] each host a tiny session another
 *   peer can join and pull from. They ride a second WebSocket server (the
 *   [TapWire.DEFAULT_PORT] port) so telemetry is cleanly separate from the Patchwork
 *   hub the peers stitch on.
 *
 * Admission is left [Open][us.tractat.kuilt.otel.tap.LogTapAdmission.Open]: this is a
 * loopback demo with no secret to protect. On a real device you would gate the taps
 * behind a join code instead.
 *
 * Everything runs on [scope]; cancelling it (and the returned handles closing with it)
 * shuts the taps down. Returns the bound tap port so `main` can print how to reach in.
 */
internal suspend fun installRelayObservability(
    relay: PatchworkRelay,
    scope: CoroutineScope,
    tapPort: Int,
): Int {
    // One stable identity for this relay incarnation's telemetry. The DurableStore is
    // in-memory — a demo relay keeps nothing across restarts.
    val replica = ReplicaId("patchwork-relay")
    val store = InMemoryDurableStore()
    val logExporter = WarpLogRecordExporter(replica = replica, store = store)
    val metricExporter = WarpMetricExporter(replica = replica, store = store)

    // Real clock and RNG are fine here: this is an application entrypoint, not a test.
    // The buffer now fills with every line the relay logs through `appLog`.
    installLogCapture(
        exporter = logExporter,
        config = CaptureConfig(),
        clock = Clock.System,
        random = Random.Default,
        scope = scope,
    )

    // Seed the metric buffer so a puller always converges: a pull() blocks until the
    // buffer is non-empty, so the relay reports its starting numbers up front rather
    // than only once the first peer arrives.
    recordRelayMetrics(metricExporter, peers = 0, patches = 0)
    appLog.info { "relay observability up — logs and metrics are now reachable" }

    // The taps' own WebSocket server, separate from the Patchwork hub. Each tap gets
    // its own path and its own fixed peer id (see TapWire) so the harness can address
    // both from one client.
    lateinit var logLoom: KtorServerLoom
    lateinit var metricLoom: KtorServerLoom
    val tapServer = embeddedServer(Netty, port = tapPort) {
        logLoom = KtorServerLoom(this, TapWire.LOG_PATH, selfPeerId = TapWire.LOG_SERVER_ID)
        metricLoom = KtorServerLoom(this, TapWire.METRIC_PATH, selfPeerId = TapWire.METRIC_SERVER_ID)
    }
    tapServer.start(wait = false)
    val boundPort = tapServer.engine.resolvedConnectors().first().port

    // Keep the metric numbers current as the table changes, and mirror the same events
    // into the captured log so a puller sees both signals move together.
    scope.launch {
        relay.peers.collect { peers ->
            val connected = (peers.size - 1).coerceAtLeast(0) // minus the relay itself
            appLog.info { "peers connected: $connected" }
            recordRelayMetrics(metricExporter, peers = connected, patches = null)
        }
    }
    scope.launch {
        relay.quilt.collect { board ->
            val patches = board.entries.size
            appLog.info { "quilt updated: $patches patches" }
            recordRelayMetrics(metricExporter, peers = null, patches = patches)
            metricExporter.incrementSum(RelayMetrics.QUILT_UPDATES)
        }
    }

    // A puller joins to pull, then leaves; another may join later (a second `:demo-tap`
    // run, or the same one switching from a snapshot to a live tail). Re-accept
    // forever, retiring the previous host as soon as the next puller connects so at
    // most one idle replicator lingers.
    scope.launch { acceptLoop("log") { installLogTap(logLoom, logExporter, scope) } }
    scope.launch { acceptLoop("metric") { installMetricTap(metricLoom, metricExporter, scope) } }

    return boundPort
}

/**
 * Record the relay's current gauges. Pass `null` for a signal that hasn't changed so
 * each collector updates only its own gauge. Gauge timestamps are the local clock in
 * millis — monotonic enough for a single-process demo.
 */
private suspend fun recordRelayMetrics(
    metrics: WarpMetricExporter,
    peers: Int?,
    patches: Int?,
) {
    val now = Clock.System.now().toEpochMilliseconds()
    if (peers != null) metrics.setGauge(RelayMetrics.PEERS_CONNECTED, peers.toDouble(), now)
    if (patches != null) metrics.setGauge(RelayMetrics.QUILT_PATCHES, patches.toDouble(), now)
}

/**
 * Accept pullers forever. [install] hosts one tap session and suspends until a puller
 * connects, then returns the host; we close the previous one and wait for the next.
 * Bounds lingering hosts to one — the currently-connected puller's.
 */
private suspend fun acceptLoop(label: String, install: suspend () -> AutoCloseable) {
    var current: AutoCloseable? = null
    while (coroutineContext.isActive) {
        val next = install()
        current?.close()
        current = next
        appLog.info { "$label tap: a puller connected" }
    }
}
