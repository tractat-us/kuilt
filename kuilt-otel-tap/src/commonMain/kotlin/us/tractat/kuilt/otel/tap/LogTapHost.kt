@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.otel.tap

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.ScopedCloseable
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.otel.LogRecord
import us.tractat.kuilt.otel.WarpLogRecordExporter
import us.tractat.kuilt.quilter.Quilter

private val logger = KotlinLogging.logger {}

/**
 * The device side of a log tap: a peer that offers its captured logs for extraction.
 *
 * An app that wants its logs to be reachable from a test or CI process calls
 * [installLogTap] once. From then on the device hosts a small session that another peer
 * can join and pull the logs out of — handy on a phone or simulator where logs are
 * otherwise hard to get at. Until [installLogTap] is called, nothing is opened and no
 * session exists: the tap is entirely opt-in.
 *
 * Under the surface the host rides a [us.tractat.kuilt.quilter.Quilter] over the woven
 * [Seam], replicating the device's log buffer (an `Rga<LogRecord>`) to whoever joins.
 * Replication is idempotent and order-preserving by construction, so a puller that
 * reconnects never sees a duplicate or a gap.
 *
 * Close the returned host to stop offering logs and release the replicator.
 */
public class LogTapHost internal constructor(
    private val seam: Seam,
    private val exporter: WarpLogRecordExporter,
    parentScope: CoroutineScope,
    private val config: LogTapConfig,
) : ScopedCloseable(parentScope) {

    // Seeded with the buffer's current contents so a puller that joins before any new
    // record is captured still receives the full backlog via the replicator's
    // first-contact full-state exchange.
    private val replicator: Quilter<us.tractat.kuilt.crdt.Rga<LogRecord>> = Quilter(
        seam = seam,
        initial = exporter.snapshot(),
        valueSerializer = logRgaSerializer(),
        scope = scope,
        config = config.quilterConfig,
        binaryFormat = LogTapCbor,
    )

    init {
        scope.launch { offerLoop() }
    }

    /**
     * This peer's own id within the session — useful for diagnostics and per-device
     * artifact naming on a multi-device harness.
     */
    public val selfId: us.tractat.kuilt.core.PeerId get() = seam.selfId

    /**
     * Offer any newly captured records for replication **now**, without waiting for the
     * next [LogTapConfig.syncInterval] tick.
     *
     * Reads the device buffer's current snapshot and, if it carries records the
     * replicator has not yet seen, hands the snapshot to the replicator as a local
     * mutation. The merge is idempotent, so calling this repeatedly is harmless.
     */
    public fun sync() {
        val snapshot = exporter.snapshot()
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
        logger.debug { "LogTapHost(${seam.selfId}) closing" }
        replicator.close()
    }
}

/**
 * Install an opt-in log tap on this device and start offering captured logs for
 * extraction. **A no-op until called** — kuilt never opens a log session implicitly.
 *
 * Hosts a session on [loom] and continuously offers [exporter]'s log buffer for
 * replication to any peer that joins. The tap is fabric-agnostic: pass a loopback
 * WebSocket [Loom] for a simulator/CI puller (the default and safest choice — it binds
 * only the local loopback interface), or a LAN/peer-to-peer [Loom] to reach a real
 * device. Discovery and admission are the [Loom]'s concern, not the tap's.
 *
 * @param loom the fabric to host the session on. Bind it to loopback by default.
 * @param exporter the device's captured-log buffer to offer.
 * @param scope the scope the host's replicator runs in. Closing the returned host (or
 *   cancelling this scope) stops the tap.
 * @param config tap tuning; the defaults suit a developer turning the tap on to debug.
 *
 * @sample us.tractat.kuilt.otel.tap.sampleLogTapHostAndPull
 */
public suspend fun installLogTap(
    loom: Loom,
    exporter: WarpLogRecordExporter,
    scope: CoroutineScope,
    config: LogTapConfig = LogTapConfig(),
): LogTapHost {
    val seam = loom.host(config.pattern)
    return LogTapHost(seam, exporter, scope, config)
}
