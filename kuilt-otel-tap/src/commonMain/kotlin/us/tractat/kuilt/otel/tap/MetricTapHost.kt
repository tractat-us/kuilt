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
import us.tractat.kuilt.core.Tag
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
    rawSeam: Seam,
    private val exporter: WarpMetricExporter,
    parentScope: CoroutineScope,
    private val config: MetricTapConfig,
    admission: LogTapAdmission = LogTapAdmission.Open,
) : ScopedCloseable(parentScope) {

    // When admission is not Open, the woven seam is wrapped in a token gate that runs in
    // this host's own [scope] — so closing the host stops the gate — and only surfaces the
    // replicator to a peer that has proven the join code. Replicating over the bare
    // `rawSeam` when a gate was requested would run the replicator ungated — a metric-exfil
    // hole — so the gated seam is what the [Quilter] rides.
    private val seam: Seam = rawSeam.gatedIfNeeded(admission.offeringRole(), scope)

    // Seeded with the buffer's current converged state so a puller that joins before any
    // new metric is recorded still receives the full snapshot via the replicator's
    // first-contact full-state exchange.
    private val replicator: Quilter<MetricCatalog> = Quilter(
        seam = seam,
        initial = exporter.snapshotAll(),
        valueSerializer = metricCatalogSerializer(),
        scope = scope,
        config = config.quilterConfig,
        binaryFormat = TapCbor,
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
 * @param admission how peers are admitted. The default [LogTapAdmission.Open] keeps the
 *   loopback-safe ungated behaviour; pass [LogTapAdmission.Verify] to require a join code
 *   before a peer can pull (see [installMetricTapJoining] for the device-joins topology).
 *   The same [LogTapAdmission] type gates both the log and metric taps.
 *
 * @sample us.tractat.kuilt.otel.tap.sampleMetricTapHostAndPull
 * @sample us.tractat.kuilt.otel.tap.sampleGatedMetricTap
 */
public suspend fun installMetricTap(
    loom: Loom,
    exporter: WarpMetricExporter,
    scope: CoroutineScope,
    config: MetricTapConfig = MetricTapConfig(),
    admission: LogTapAdmission = LogTapAdmission.Open,
): MetricTapHost {
    admission.announceGatedTap()
    val seam = loom.host(config.pattern)
    return MetricTapHost(seam, exporter, scope, config, admission)
}

/**
 * Install a metric tap where the device **joins** a session the puller hosts, instead of
 * hosting one itself.
 *
 * The tap's replication is symmetric — the replicator carries the device's metric buffer to
 * the other peer regardless of which side opened the rendezvous — so a device that cannot
 * host a server or advertise itself (an iOS device has no WebSocket server and no mDNS
 * advertiser) can still offer its metrics by *joining* a laptop that hosts and advertises.
 * The metrics still flow device → laptop. The metric-tap sibling of [installLogTapJoining].
 *
 * @param loom the fabric to join on (e.g. a WebSocket client loom).
 * @param exporter the device's metric buffer to offer.
 * @param scope the scope the host's replicator runs in.
 * @param tag the rendezvous to join (e.g. an advertisement discovered over mDNS).
 * @param config tap tuning.
 * @param admission how the pulling peer is admitted — [LogTapAdmission.Verify] to require a
 *   join code, or [LogTapAdmission.Open] on a trusted link.
 */
public suspend fun installMetricTapJoining(
    loom: Loom,
    exporter: WarpMetricExporter,
    scope: CoroutineScope,
    tag: Tag,
    config: MetricTapConfig = MetricTapConfig(),
    admission: LogTapAdmission = LogTapAdmission.Open,
): MetricTapHost {
    admission.announceGatedTap()
    val seam = loom.join(tag)
    return MetricTapHost(seam, exporter, scope, config, admission)
}
