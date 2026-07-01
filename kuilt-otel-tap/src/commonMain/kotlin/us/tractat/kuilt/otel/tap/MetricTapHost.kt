@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.otel.tap

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.ScopedCloseable
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.otel.MetricCatalog
import us.tractat.kuilt.otel.WarpMetricExporter
import us.tractat.kuilt.quilter.Quilter

private val logger = KotlinLogging.logger("us.tractat.kuilt.otel.tap.MetricTapHost")

/**
 * The device side of a metric tap: a peer that offers its converged metric buffer for
 * extraction.
 *
 * An app that wants its metrics reachable from a test or CI process calls
 * [installMetricTap] once. From then on the device hosts a small session another peer can
 * join to pull the metrics out of — handy on a phone or simulator where metrics are
 * otherwise hard to get at. Until [installMetricTap] is called, nothing is opened: the tap
 * is entirely opt-in.
 *
 * Under the surface the host rides a [Quilter] over the woven [Seam], replicating the
 * device's [MetricCatalog] (counters, gauges, cardinalities) to whoever joins.
 * Replication is idempotent by construction, so a puller that reconnects converges without
 * double-counting.
 *
 * Close the returned host to stop offering metrics and release the replicator.
 */
public class MetricTapHost internal constructor(
    private val seam: Seam,
    private val exporter: WarpMetricExporter,
    parentScope: CoroutineScope,
    private val config: MetricTapConfig,
) : ScopedCloseable(parentScope) {

    // Seeded with the buffer's current converged state so a puller that joins before any
    // new metric is recorded still receives the full snapshot via the replicator's
    // first-contact full-state exchange.
    private val replicator: Quilter<MetricCatalog> = Quilter(
        seam = seam,
        initial = exporter.snapshotAll(),
        valueSerializer = metricCatalogSerializer(),
        scope = scope,
        config = config.quilterConfig,
        binaryFormat = MetricTapCbor,
    )

    init {
        scope.launch { offerLoop() }
    }

    /**
     * This peer's own id within the session — useful for diagnostics and per-device
     * artifact naming on a multi-device harness.
     */
    public val selfId: PeerId get() = seam.selfId

    /**
     * Offer the buffer's current converged state for replication **now**, without waiting
     * for the next [MetricTapConfig.syncInterval] tick.
     *
     * Reads the device buffer's current [MetricCatalog] snapshot and, if it differs from
     * what the replicator holds, hands it over as a local mutation. The merge is
     * idempotent, so calling this repeatedly is harmless.
     */
    public fun sync() {
        val snapshot = exporter.snapshotAll()
        if (snapshot != replicator.state.value) {
            replicator.apply(Patch(snapshot))
        }
    }

    private suspend fun offerLoop() {
        while (true) {
            delay(config.syncInterval)
            sync()
        }
    }

    override fun onClose() {
        logger.debug { "MetricTapHost(${seam.selfId}) closing" }
        replicator.close()
    }
}

/**
 * Install an opt-in metric tap on this device and start offering its metric buffer for
 * extraction. **A no-op until called** — kuilt never opens a metric session implicitly.
 *
 * Hosts a session on [loom] and continuously offers [exporter]'s metric buffer for
 * replication to any peer that joins. The tap is fabric-agnostic: pass a loopback
 * WebSocket [Loom] for a simulator/CI puller (the default and safest choice), or a
 * LAN/peer-to-peer [Loom] to reach a real device. Discovery and admission are the
 * [Loom]'s concern, not the tap's.
 *
 * @param loom the fabric to host the session on. Bind it to loopback by default.
 * @param exporter the device's metric buffer to offer.
 * @param scope the scope the host's replicator runs in. Closing the returned host (or
 *   cancelling this scope) stops the tap.
 * @param config tap tuning; the defaults suit a developer turning the tap on to debug.
 */
public suspend fun installMetricTap(
    loom: Loom,
    exporter: WarpMetricExporter,
    scope: CoroutineScope,
    config: MetricTapConfig = MetricTapConfig(),
): MetricTapHost {
    val seam = loom.host(config.pattern)
    return MetricTapHost(seam, exporter, scope, config)
}
