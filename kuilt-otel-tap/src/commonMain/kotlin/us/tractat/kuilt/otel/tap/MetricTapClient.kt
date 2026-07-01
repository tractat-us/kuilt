@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.otel.tap

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.core.ScopedCloseable
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.otel.MetricCatalog
import us.tractat.kuilt.otel.MetricKey
import us.tractat.kuilt.quilter.Quilter

/**
 * A reconstructed snapshot of a device's metric buffer — the CRDTs collapsed to the plain
 * numbers they report.
 */
public data class MetricSnapshot(
    public val sums: Map<MetricKey, Long>,
    public val doubleSums: Map<MetricKey, Double>,
    public val gauges: Map<MetricKey, Double>,
    public val cardinalities: Map<MetricKey, Long>,
)

/**
 * The test/harness side of a metric tap: join a device and read its metrics out.
 *
 * Given a [Seam] already woven onto the device's session (the harness joins the same
 * [us.tractat.kuilt.core.Loom] the device hosts on), a client either takes a one-shot
 * [pull] of everything recorded so far or [tail]s the metrics live as they change. The
 * underlying replication is a converging merge, so the reconstructed numbers match the
 * device's, even across a reconnect.
 *
 * Unlike the log tap's per-record [LogTapClient.tail], metrics have no per-record identity
 * — a counter grows, a gauge is overwritten, a sketch absorbs — so the live view here is
 * the whole converged [MetricSnapshot], re-emitted whenever it changes.
 *
 * Close the client to release its replicator.
 */
public class MetricTapClient(
    private val seam: Seam,
    parentScope: CoroutineScope,
    private val config: MetricTapConfig = MetricTapConfig(),
) : ScopedCloseable(parentScope) {

    private val replicator: Quilter<MetricCatalog> = Quilter(
        seam = seam,
        initial = MetricCatalog(),
        valueSerializer = metricCatalogSerializer(),
        scope = scope,
        config = config.quilterConfig,
        binaryFormat = MetricTapCbor,
    )

    /**
     * Pull a snapshot of the device's metrics: wait for the host's state to replicate in
     * and settle, then collapse each CRDT to its reported value.
     *
     * Waits until the host is connected and its first-contact full-state has actually
     * merged — not merely until a peer appears — so it never returns the still-empty
     * initial state over an asynchronous link. Bounded by [MetricTapConfig.pullTimeout]: a
     * pull that does not converge in time throws
     * [kotlinx.coroutines.TimeoutCancellationException] rather than returning a partial
     * result. The result is point-in-time; call again (or use [tail]) to observe later
     * changes.
     *
     * **Empty is not a fast return.** This waits for the *first non-empty* state, so a device
     * that has recorded no metrics yet does not resolve to an empty [MetricSnapshot] — the
     * call blocks for the full [MetricTapConfig.pullTimeout] and then throws
     * [kotlinx.coroutines.TimeoutCancellationException]. Use [tail] if you need to observe a
     * buffer that may legitimately be empty for a while.
     */
    public suspend fun pull(): MetricSnapshot = withTimeout(config.pullTimeout) {
        awaitRemotePeer()
        val firstNonEmpty = replicator.state.first { it.isNotEmpty() }
        collapse(settle(firstNonEmpty))
    }

    /**
     * Tail the device's metrics live: emits the whole converged [MetricSnapshot] each time
     * the replicated state changes (deduped by value). The flow replays the current state
     * on collection, then continues until the collector is cancelled.
     */
    public fun tail(): Flow<MetricSnapshot> =
        replicator.state.map { collapse(it) }.distinctUntilChanged()

    private fun collapse(cat: MetricCatalog) = MetricSnapshot(
        sums = cat.sums.mapValues { it.value.value },
        doubleSums = cat.doubleSums.mapValues { it.value.value },
        // LWWRegister.value is nullable; a replicated gauge entry always carries a value
        // (the key exists only because setGauge wrote one). requireNotNull, never `!!`.
        gauges = cat.gauges.mapValues { requireNotNull(it.value.value) { "gauge ${it.key} has no value" } },
        cardinalities = cat.cardinalities.mapValues { it.value.estimate() },
    )

    private fun MetricCatalog.isNotEmpty(): Boolean =
        sums.isNotEmpty() || doubleSums.isNotEmpty() || gauges.isNotEmpty() || cardinalities.isNotEmpty()

    private suspend fun awaitRemotePeer() {
        seam.peers.first { peers -> peers.any { it != seam.selfId } }
    }

    /**
     * Wait for the replicated state to stop advancing, starting from [initial]. Each step
     * waits up to [MetricTapConfig.pullSettleStep] for the next distinct state; a quiet
     * step means the snapshot has settled. Bounded by [SETTLE_ITERATIONS] and, via the
     * caller, by [MetricTapConfig.pullTimeout].
     */
    private suspend fun settle(initial: MetricCatalog): MetricCatalog {
        var current = initial
        repeat(SETTLE_ITERATIONS) {
            val next = withTimeoutOrNull(config.pullSettleStep) {
                replicator.state.first { it != current }
            } ?: return current
            current = next
        }
        return current
    }

    override fun onClose() {
        replicator.close()
    }

    private companion object {
        const val SETTLE_ITERATIONS = 32
    }
}
