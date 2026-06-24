@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.otel

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.HyperLogLog
import us.tractat.kuilt.crdt.LWWRegister
import us.tractat.kuilt.crdt.ReplicaId

private val logger = KotlinLogging.logger {}

/**
 * A CRDT-backed metric exporter for cumulative sums, gauges, and cardinality estimates.
 *
 * Each metric kind is backed by a different CRDT chosen to match its semantics:
 *
 * - **Sum** — a [GCounter] per [MetricKey]. Cumulative, monotonically increasing.
 *   Two replicas that independently saw N and M events report N+M after merge.
 *   Re-exporting the same increment is not idempotent in the counter sense (each
 *   call to [incrementSum] adds to the total), but the GCounter lattice guarantees
 *   that merging the same remote snapshot twice does not inflate the total —
 *   so **merge under retry is safe**.
 *
 * - **Gauge** — a [LWWRegister]`<Double>` per [MetricKey]. Last-writer-wins by
 *   `(timestamp, replicaId)` tiebreak. A later timestamp from any replica wins;
 *   tie-breaking on [ReplicaId] is deterministic regardless of arrival order.
 *
 * - **Cardinality** — a [HyperLogLog] per [MetricKey]. Estimates distinct element
 *   counts (~0.81% relative error at default precision `p=14`). The join is
 *   element-wise max of register arrays: two replicas that independently added
 *   overlapping sets produce the same merged estimate — no double-counting.
 *
 * ## Key inversion
 *
 * [incrementSum], [setGauge], and [addCardinality] all return [MetricExportResult.Success]
 * the moment the updated CRDT is **durably written to [DurableStore]** — not when it is
 * delivered to any backend. Delivery is asynchronous and eventually consistent.
 *
 * ## Buffer cap
 *
 * The total number of distinct [MetricKey]s across all kinds is bounded by [maxMetrics].
 * When the cap is exceeded, the [bufferPolicy] selects a series to evict. **Every eviction
 * is logged** — the metric name and kind are emitted at WARN so an operator can detect
 * label-cardinality explosions.
 *
 * ## Thread safety
 *
 * An explicit `reentrantLock` guards all mutable state. Suspend calls (store reads/writes)
 * are performed **outside** the lock section. This is correct under a genuinely
 * multi-threaded dispatcher — `limitedParallelism(1)` confinement is explicitly banned
 * per repo policy.
 *
 * ## Honest limits
 *
 * - **Clock skew.** Gauge timestamps are the producer's local clock. An offline device
 *   with a slow clock may have its gauge silently overwritten by a peer with a faster
 *   clock even if the slow-clock value is "newer" in wall time. An HLC offset could
 *   be estimated on reconnect but is not yet implemented.
 * - **Cardinality bound.** HyperLogLog precision is fixed at `p=14` (~0.81% error,
 *   12 KB per series). Very small cardinalities (< ~5 distinct elements) have higher
 *   relative error; the linear-counting correction reduces but does not eliminate this.
 * - **Histogram deferred.** Histogram metrics are not implemented here; they require
 *   a merge-able approximate quantile structure (DDSketch / t-digest). Filed as a
 *   follow-up: see [issue #798](https://github.com/tractat-us/kuilt/issues/798).
 *
 * @param replica Stable unique identity for this device/process (use a UUID).
 * @param store Durable persistence backend. [InMemoryDurableStore] in tests.
 * @param maxMetrics Maximum number of distinct [MetricKey]s across all kinds.
 * @param bufferPolicy Eviction strategy when [maxMetrics] is exceeded.
 *
 * @sample us.tractat.kuilt.otel.sampleWarpMetricExporter
 */
public class WarpMetricExporter(
    private val replica: ReplicaId,
    private val store: DurableStore,
    private val maxMetrics: Int = DEFAULT_MAX_METRICS,
    private val bufferPolicy: MetricBufferPolicy = MetricBufferPolicy.DROP_OLDEST,
) {
    // The lock guards all three CRDT maps. No suspend calls are made inside the locked
    // section — CBOR encode/decode and CRDT mutations are pure (non-suspending).
    // An explicit reentrantLock is the repo policy; limitedParallelism(1) is banned.
    private val lock = reentrantLock()

    // LinkedHashMap preserves insertion order, which drives the DROP_OLDEST/DROP_NEWEST
    // eviction policies. The *insertion* of a new key records its age; we don't update
    // order on access (this is not an LRU cache).
    private val sums: LinkedHashMap<MetricKey, GCounter> = LinkedHashMap()
    private val gauges: LinkedHashMap<MetricKey, LWWRegister<Double>> = LinkedHashMap()
    private val cardinalities: LinkedHashMap<MetricKey, HyperLogLog> = LinkedHashMap()

    private companion object {
        private val SUM_STORE_KEY = StoreKey("otel.metrics.sums")
        private val GAUGE_STORE_KEY = StoreKey("otel.metrics.gauges")
        private val CARDINALITY_STORE_KEY = StoreKey("otel.metrics.cardinalities")

        private val cbor = Cbor { alwaysUseByteString = true }

        private val metricKeySerializer = MetricKey.serializer()
        private val gcounterSerializer = GCounter.serializer()
        private val lwwSerializer = LWWRegister.serializer(Double.serializer())
        private val hllSerializer = HyperLogLog.serializer()

        private val sumsSerializer = MapSerializer(metricKeySerializer, gcounterSerializer)
        private val gaugesSerializer = MapSerializer(metricKeySerializer, lwwSerializer)
        private val cardinalitiesSerializer = MapSerializer(metricKeySerializer, hllSerializer)
    }

    // ── Recovery ───────────────────────────────────────────────────────────────

    /**
     * Reload persisted metric state from [store]. Call once at startup before any
     * calls to the mutating methods. Idempotent: a second call re-reads and re-decodes
     * the same bytes.
     *
     * If persisted state is corrupt or absent for a given kind, that kind starts fresh
     * (a warning is logged). The other kinds are unaffected.
     */
    public suspend fun recover() {
        recoverSums()
        recoverGauges()
        recoverCardinalities()
    }

    private suspend fun recoverSums() {
        val bytes = store.read(SUM_STORE_KEY) ?: return
        val recovered = runCatchingCancellable<Map<MetricKey, GCounter>> {
            cbor.decodeFromByteArray(sumsSerializer, bytes)
        }.getOrNull() ?: run {
            logger.warn { "otel.metrics.sums: corrupt store entry, starting fresh" }
            return
        }
        lock.withLock { recovered.forEach { (k, v) -> sums[k] = v } }
    }

    private suspend fun recoverGauges() {
        val bytes = store.read(GAUGE_STORE_KEY) ?: return
        val recovered = runCatchingCancellable<Map<MetricKey, LWWRegister<Double>>> {
            cbor.decodeFromByteArray(gaugesSerializer, bytes)
        }.getOrNull() ?: run {
            logger.warn { "otel.metrics.gauges: corrupt store entry, starting fresh" }
            return
        }
        lock.withLock { recovered.forEach { (k, v) -> gauges[k] = v } }
    }

    private suspend fun recoverCardinalities() {
        val bytes = store.read(CARDINALITY_STORE_KEY) ?: return
        val recovered = runCatchingCancellable<Map<MetricKey, HyperLogLog>> {
            cbor.decodeFromByteArray(cardinalitiesSerializer, bytes)
        }.getOrNull() ?: run {
            logger.warn { "otel.metrics.cardinalities: corrupt store entry, starting fresh" }
            return
        }
        lock.withLock { recovered.forEach { (k, v) -> cardinalities[k] = v } }
    }

    // ── Sum (GCounter) ─────────────────────────────────────────────────────────

    /**
     * Increment the cumulative sum for [key] by [by] on this replica. Returns
     * [MetricExportResult.Success] after the durable write.
     *
     * **No double-count under merge-retry.** Calling [mergeSum] with the same remote
     * snapshot more than once is always safe — GCounter's element-wise max join is
     * idempotent. Sequential calls to [incrementSum] each *do* add to the total, which
     * is the correct cumulative semantics.
     */
    public suspend fun incrementSum(key: MetricKey, by: Long = 1L): MetricExportResult {
        val encoded = lock.withLock {
            maybeEvictForNewKey(key, sums)
            val current = sums.getOrPut(key) { GCounter.ZERO }
            sums[key] = current.piece(current.inc(replica, by).delta)
            encodeSums()
        }
        return persistSums(encoded, key)
    }

    /**
     * Merge a remote [GCounter] snapshot into this exporter's sum for [key].
     *
     * Idempotent: merging the same snapshot twice produces the same result.
     * Returns [MetricExportResult.Success] after the durable write.
     */
    public suspend fun mergeSum(key: MetricKey, remote: GCounter): MetricExportResult {
        val encoded = lock.withLock {
            val current = sums[key] ?: GCounter.ZERO
            sums[key] = current.piece(remote)
            encodeSums()
        }
        return persistSums(encoded, key)
    }

    /** Read the current sum value for [key], or 0 if the key has never been incremented. */
    public fun sumValue(key: MetricKey): Long = lock.withLock {
        sums[key]?.value ?: 0L
    }

    /** Return a snapshot of the [GCounter] for [key] (for gossip/anti-entropy). */
    public fun sumSnapshot(key: MetricKey): GCounter = lock.withLock {
        sums[key] ?: GCounter.ZERO
    }

    // ── Gauge (LWWRegister<Double>) ────────────────────────────────────────────

    /**
     * Record the current value of a gauge for [key]. The `(timestamp, replica)` pair
     * determines which write wins across replicas — a higher timestamp always wins;
     * equal timestamps break on [ReplicaId] lexicographic order.
     *
     * Callers should use a monotonically increasing [timestamp] per replica to avoid
     * silent drops. Returns [MetricExportResult.Success] after the durable write.
     */
    public suspend fun setGauge(
        key: MetricKey,
        value: Double,
        timestamp: Long,
    ): MetricExportResult {
        val encoded = lock.withLock {
            maybeEvictForNewKey(key, gauges)
            val current = gauges.getOrPut(key) { LWWRegister.empty<Double>() }
            val write = current.set(replica, timestamp, value)
            gauges[key] = current.piece(write)
            encodeGauges()
        }
        return persistGauges(encoded, key)
    }

    /**
     * Merge a remote [LWWRegister] snapshot into this exporter's gauge for [key].
     *
     * Idempotent. Returns [MetricExportResult.Success] after the durable write.
     */
    public suspend fun mergeGauge(
        key: MetricKey,
        remote: LWWRegister<Double>,
    ): MetricExportResult {
        val encoded = lock.withLock {
            val current = gauges[key] ?: LWWRegister.empty<Double>()
            gauges[key] = current.piece(remote)
            encodeGauges()
        }
        return persistGauges(encoded, key)
    }

    /** Read the current gauge value for [key], or `null` if no value has been set. */
    public fun gaugeValue(key: MetricKey): Double? = lock.withLock {
        gauges[key]?.value
    }

    /** Return a snapshot of the [LWWRegister] for [key] (for gossip/anti-entropy). */
    public fun gaugeSnapshot(key: MetricKey): LWWRegister<Double> = lock.withLock {
        gauges[key] ?: LWWRegister.empty()
    }

    // ── Cardinality (HyperLogLog) ──────────────────────────────────────────────

    /**
     * Add [element] to the HyperLogLog sketch for [key]. Elements are hashed with
     * MurmurHash3 — the element string is the canonical identifier (e.g. a user id,
     * session id, or request id).
     *
     * **No double-count under retry.** Adding the same element twice produces the same
     * hash and the same register max — the estimate is unchanged. Returns
     * [MetricExportResult.Success] after the durable write.
     */
    public suspend fun addCardinality(key: MetricKey, element: String): MetricExportResult {
        val encoded = lock.withLock {
            maybeEvictForNewKey(key, cardinalities)
            val current = cardinalities.getOrPut(key) { HyperLogLog.empty() }
            val patch = current.add(element)
            cardinalities[key] = current.piece(patch.delta)
            encodeCardinalities()
        }
        return persistCardinalities(encoded, key)
    }

    /**
     * Merge a remote [HyperLogLog] snapshot into this exporter's sketch for [key].
     *
     * The join is element-wise max, so duplicates across replicas are automatically
     * deduplicated. Idempotent. Returns [MetricExportResult.Success] after the durable
     * write.
     */
    public suspend fun mergeCardinality(
        key: MetricKey,
        remote: HyperLogLog,
    ): MetricExportResult {
        val encoded = lock.withLock {
            val current = cardinalities[key] ?: HyperLogLog.empty()
            cardinalities[key] = current.piece(remote)
            encodeCardinalities()
        }
        return persistCardinalities(encoded, key)
    }

    /** Return the current distinct-element estimate for [key], or 0 if no elements have been added. */
    public fun cardinalityEstimate(key: MetricKey): Long = lock.withLock {
        cardinalities[key]?.estimate() ?: 0L
    }

    /** Return a snapshot of the [HyperLogLog] for [key] (for gossip/anti-entropy). */
    public fun cardinalitySnapshot(key: MetricKey): HyperLogLog = lock.withLock {
        cardinalities[key] ?: HyperLogLog.empty()
    }

    // ── Diagnostics ───────────────────────────────────────────────────────────

    /** Total number of distinct [MetricKey]s tracked across all kinds. */
    public fun metricCount(): Int = lock.withLock { sums.size + gauges.size + cardinalities.size }

    // ── Encoding (called inside lock) ─────────────────────────────────────────

    private fun encodeSums(): ByteArray =
        cbor.encodeToByteArray(sumsSerializer, sums)

    private fun encodeGauges(): ByteArray =
        cbor.encodeToByteArray(gaugesSerializer, gauges)

    private fun encodeCardinalities(): ByteArray =
        cbor.encodeToByteArray(cardinalitiesSerializer, cardinalities)

    // ── Persistence (called outside lock) ────────────────────────────────────

    private suspend fun persistSums(encoded: ByteArray, key: MetricKey): MetricExportResult =
        persist(SUM_STORE_KEY, encoded, key)

    private suspend fun persistGauges(encoded: ByteArray, key: MetricKey): MetricExportResult =
        persist(GAUGE_STORE_KEY, encoded, key)

    private suspend fun persistCardinalities(encoded: ByteArray, key: MetricKey): MetricExportResult =
        persist(CARDINALITY_STORE_KEY, encoded, key)

    private suspend fun persist(storeKey: StoreKey, encoded: ByteArray, metricKey: MetricKey): MetricExportResult =
        runCatchingCancellable { store.write(storeKey, encoded) }
            .fold(
                onSuccess = { MetricExportResult.Success },
                onFailure = { cause ->
                    logger.error(cause) {
                        "WarpMetricExporter: durable write failed for metric ${metricKey.name} (${metricKey.kind})"
                    }
                    MetricExportResult.Failure(cause)
                },
            )

    // ── Eviction (called inside lock) ─────────────────────────────────────────

    /**
     * If [key] is new (not present in [map]) and [totalCount] has reached [maxMetrics],
     * evict one series from the combined pool according to [bufferPolicy].
     *
     * Eviction selects from whichever of the three maps holds the oldest/newest entry
     * by insertion order. The evicted key is always logged at WARN.
     */
    private fun <V> maybeEvictForNewKey(key: MetricKey, map: LinkedHashMap<MetricKey, V>) {
        if (key in map) return
        if (totalCount() < maxMetrics) return
        evictOne()
    }

    private fun totalCount(): Int = sums.size + gauges.size + cardinalities.size

    private fun evictOne() {
        val victim = when (bufferPolicy) {
            MetricBufferPolicy.DROP_OLDEST -> pickOldest()
            MetricBufferPolicy.DROP_NEWEST -> pickNewest()
        } ?: return
        logEviction(victim)
        sums.remove(victim)
        gauges.remove(victim)
        cardinalities.remove(victim)
    }

    /** The insertion-first key across all three maps (LinkedHashMap preserves insertion order). */
    private fun pickOldest(): MetricKey? =
        // We cannot compare insertion time across maps, so we take the first key of
        // the first non-empty map in a stable ordering (sums → gauges → cardinalities).
        listOfNotNull(sums.keys.firstOrNull(), gauges.keys.firstOrNull(), cardinalities.keys.firstOrNull())
            .firstOrNull()

    /** The insertion-last key across all three maps. */
    private fun pickNewest(): MetricKey? =
        listOfNotNull(sums.keys.lastOrNull(), gauges.keys.lastOrNull(), cardinalities.keys.lastOrNull())
            .lastOrNull()

    private fun logEviction(victim: MetricKey) {
        logger.warn {
            "WarpMetricExporter: buffer cap ($maxMetrics) reached, evicting metric " +
                "name=${victim.name} kind=${victim.kind} attrs=${victim.attributes} " +
                "policy=$bufferPolicy"
        }
    }
}

/** The result of a [WarpMetricExporter] mutating call. */
public sealed interface MetricExportResult {
    /** The metric was durably written to the local store. */
    public data object Success : MetricExportResult

    /** The durable write failed; the metric is not persisted. */
    public data class Failure(public val cause: Throwable) : MetricExportResult
}
