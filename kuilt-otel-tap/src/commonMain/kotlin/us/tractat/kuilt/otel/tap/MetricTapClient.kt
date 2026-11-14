@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class, ExperimentalVersionOverloading::class)

package us.tractat.kuilt.otel.tap

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.core.ScopedCloseable
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.crdt.DDSketch
import us.tractat.kuilt.otel.MetricCatalog
import us.tractat.kuilt.otel.MetricKey
import us.tractat.kuilt.quilter.Quilter

private val logger = KotlinLogging.logger("us.tractat.kuilt.otel.tap.MetricTapClient")

/**
 * A reconstructed snapshot of a device's metric buffer — the CRDTs collapsed to the plain
 * numbers they report.
 *
 * A histogram reports a distribution rather than one number, so [histograms] carries the
 * sketch itself: ask it for [DDSketch.quantile] or [DDSketch.count].
 *
 * [sums] leaves out, with a WARN naming it the first time, a series whose replicas' counts add up past
 * [Long.MAX_VALUE] (#3318): the total has no `Long` to be, and one such series must not stop
 * [MetricTapClient.pull] or [MetricTapClient.tail] reporting every other one.
 */
public data class MetricSnapshot(
    public val sums: Map<MetricKey, Long>,
    public val doubleSums: Map<MetricKey, Double>,
    public val gauges: Map<MetricKey, Double>,
    public val cardinalities: Map<MetricKey, Long>,
    @IntroducedAt("0.8")
    public val histograms: Map<MetricKey, DDSketch> = emptyMap(),
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
 *
 * @param admission how this client is admitted by the host. The default [LogTapAdmission.Open]
 *   matches an ungated host; pass [LogTapAdmission.Present] with the shown code when the host
 *   was installed with [LogTapAdmission.Verify].
 */
public class MetricTapClient(
    seam: Seam,
    parentScope: CoroutineScope,
    private val config: MetricTapConfig = MetricTapConfig(),
    admission: LogTapAdmission = LogTapAdmission.Open,
) : ScopedCloseable(parentScope) {

    // When admission is not Open, wrap the joined seam in the pulling-side token gate,
    // running in this client's own [scope], so it answers the host's challenge with the
    // presented code before any replication is expected.
    private val seam: Seam = seam.gatedIfNeeded(admission.pullingRole(), scope)

    private val replicator: Quilter<MetricCatalog> = Quilter(
        seam = this.seam,
        initial = MetricCatalog(),
        valueSerializer = metricCatalogSerializer(),
        scope = scope,
        config = config.quilterConfig,
        binaryFormat = TapCbor,
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
        seam.awaitRemotePeer()
        val firstNonEmpty = replicator.state.first { it.isNotEmpty() }
        collapse(replicator.state.settle(config.pullSettleStep, firstNonEmpty), leftOut)
    }

    /**
     * Tail the device's metrics live: emits the whole converged [MetricSnapshot] each time
     * the replicated state changes (deduped by value). The flow replays the current state
     * on collection, then continues until the collector is cancelled.
     */
    public fun tail(): Flow<MetricSnapshot> =
        replicator.state.map { collapse(it, leftOut) }.distinctUntilChanged()

    /** The sum series already reported left out of a snapshot, WARNed once each (#3318). */
    private val leftOut = LeftOutSums()

    private fun MetricCatalog.isNotEmpty(): Boolean =
        sums.isNotEmpty() || doubleSums.isNotEmpty() || gauges.isNotEmpty() || cardinalities.isNotEmpty() ||
            histograms.isNotEmpty()

    override fun onClose() {
        replicator.close()
    }
}

/**
 * Collapse each CRDT in [cat] to the number it reports; see [MetricSnapshot] for what is left out.
 * A left-out series is logged at WARN the first time [leftOut] sees it and at DEBUG after that.
 */
internal fun collapse(cat: MetricCatalog, leftOut: LeftOutSums): MetricSnapshot = MetricSnapshot(
    sums = buildMap {
        cat.sums.forEach { (key, counter) ->
            val total = counter.valueOrNull()
            if (total == null) {
                val line = { "MetricTapClient: sum series ${key.name} ${key.attributes} left out: its total passes Long.MAX_VALUE" }
                if (leftOut.firstSighting(key, cat.sums.keys)) logger.warn(line) else logger.debug(line)
            } else {
                put(key, total)
            }
        }
    },
    doubleSums = cat.doubleSums.mapValues { it.value.value },
    // LWWRegister.value is nullable; a replicated gauge entry always carries a value
    // (the key exists only because setGauge wrote one). requireNotNull, never `!!`.
    gauges = cat.gauges.mapValues { requireNotNull(it.value.value) { "gauge ${it.key} has no value" } },
    cardinalities = cat.cardinalities.mapValues { it.value.estimate() },
    histograms = cat.histograms,
)

/**
 * The sum series a snapshot has already left out, so each is WARNed once rather than on every
 * collapse a [MetricTapClient.tail] makes (#3318). A total past [Long.MAX_VALUE] stays past it, so
 * every later collapse meets the same series again.
 *
 * Bounded by the live series: each sighting first forgets every key not in the live set it is
 * given. Guarded by a lock rather than by a dispatcher, since a [MetricTapClient.pull] and any
 * number of [MetricTapClient.tail] collectors may collapse at once. `:kuilt-otel` keeps the same
 * set for its own sites; it is `internal` there, hence this copy.
 */
internal class LeftOutSums {
    private val lock = reentrantLock()
    private val seen = HashSet<MetricKey>()

    /** Whether this is [key]'s first sighting since it was last outside [live]. */
    fun firstSighting(key: MetricKey, live: Set<MetricKey>): Boolean = lock.withLock {
        seen.retainAll(live)
        seen.add(key)
    }
}
