@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.otel

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.crdt.DDSketch
import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.GCounterDouble
import us.tractat.kuilt.crdt.HyperLogLog
import us.tractat.kuilt.crdt.LWWRegister
import us.tractat.kuilt.crdt.ReplicaId

private val logger = KotlinLogging.logger("us.tractat.kuilt.otel.WarpMetricExporter")

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
 * - **Exponential histogram** — a [DDSketch] per [MetricKey]. Answers quantile
 *   queries ("p99 latency?") within a relative accuracy α; the join is lossless,
 *   so merged replicas equal the sketch of the combined stream. Exports as an OTLP
 *   `ExponentialHistogramDataPoint`, which requires α to align with an integer OTLP
 *   scale — configure via [alphaForOtlpScale] (the default [histogramPrototype] is
 *   scale-[DEFAULT_OTLP_HISTOGRAM_SCALE]-aligned, α ≈ 1.08%). Like [HyperLogLog]
 *   precision, the sketch configuration is a cluster-wide constant: sketches merge
 *   only when it matches exactly.
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
 * - **Histogram α is OTLP-gated.** Only OTLP-aligned sketch accuracies are accepted
 *   ([alphaForOtlpScale]); a free-form α has no integer OTLP scale, and re-bucketing
 *   would break the accuracy guarantee, so ingestion rejects it up front rather than
 *   letting an OTLP drain fail later.
 *
 * @param replica Stable unique identity for this device/process (use a UUID).
 * @param store Durable persistence backend. [InMemoryDurableStore] in tests.
 * @param maxMetrics Maximum number of distinct [MetricKey]s across all kinds.
 * @param bufferPolicy Eviction strategy when [maxMetrics] is exceeded.
 * @param histogramPrototype The empty [DDSketch] every new histogram series starts
 *   from — its `(relativeAccuracy, minIndexedValue, maxIndexedValue)` is the
 *   cluster-wide histogram configuration. Must be empty and OTLP-aligned; defaults to
 *   scale [DEFAULT_OTLP_HISTOGRAM_SCALE] (α ≈ 1.08%) over the DDSketch default range.
 *
 * @sample us.tractat.kuilt.otel.sampleWarpMetricExporter
 */
public class WarpMetricExporter(
    private val replica: ReplicaId,
    private val store: DurableStore,
    private val maxMetrics: Int = DEFAULT_MAX_METRICS,
    private val bufferPolicy: MetricBufferPolicy = MetricBufferPolicy.DROP_OLDEST,
    private val histogramPrototype: DDSketch =
        DDSketch.empty(relativeAccuracy = alphaForOtlpScale(DEFAULT_OTLP_HISTOGRAM_SCALE)),
) {
    init {
        require(histogramPrototype.count == 0L) {
            "histogramPrototype must be an empty sketch, had count ${histogramPrototype.count}"
        }
        // Fail fast on a non-OTLP-aligned accuracy (throws IllegalArgumentException).
        otlpScaleFor(histogramPrototype.relativeAccuracy)
    }

    // Two-tier locking, both explicit primitives (repo policy: correctness must hold under a real
    // multi-threaded dispatcher; limitedParallelism(1) confinement is BANNED).
    //
    //  - `lock` (atomicfu reentrant) guards the five CRDT maps only. No suspend call is ever made
    //    inside it — CBOR encode/decode and CRDT mutations are pure.
    //  - `writeMutex` (coroutine Mutex) serializes the whole *durable-write* critical section: the
    //    mutate-and-encode and the store write form one ordered unit. Without it a mutation could
    //    encode the whole map, suspend, and land those stale bytes after a concurrent [clear] had
    //    already deleted the keys — resurrecting every pre-clear series on the next recover
    //    (#2232). The same shape was closed in WarpSpanExporter by #1053 and in
    //    WarpLogRecordExporter by #2187; this was the third exporter, and [clear] is what made it
    //    permanent rather than self-healing.
    //
    // Acquisition order is `writeMutex` then `lock`, never the reverse, and `lock` is never held
    // across a `writeMutex` acquisition — so the two cannot deadlock.
    private val lock = reentrantLock()
    private val writeMutex = Mutex()

    // LinkedHashMap preserves insertion order, which drives the DROP_OLDEST/DROP_NEWEST
    // eviction policies. The *insertion* of a new key records its age; we don't update
    // order on access (this is not an LRU cache).
    private val sums: LinkedHashMap<MetricKey, GCounter> = LinkedHashMap()
    private val sumsDouble: LinkedHashMap<MetricKey, GCounterDouble> = LinkedHashMap()
    private val gauges: LinkedHashMap<MetricKey, LWWRegister<Double>> = LinkedHashMap()
    private val cardinalities: LinkedHashMap<MetricKey, HyperLogLog> = LinkedHashMap()
    private val histograms: LinkedHashMap<MetricKey, DDSketch> = LinkedHashMap()

    private companion object {
        private val SUM_STORE_KEY = StoreKey("otel.metrics.sums")
        private val SUM_DOUBLE_STORE_KEY = StoreKey("otel.metrics.sums.double")
        private val GAUGE_STORE_KEY = StoreKey("otel.metrics.gauges")
        private val CARDINALITY_STORE_KEY = StoreKey("otel.metrics.cardinalities")
        private val HISTOGRAM_STORE_KEY = StoreKey("otel.metrics.histograms")

        private val cbor = Cbor { alwaysUseByteString = true }

        private val metricKeySerializer = MetricKey.serializer()
        private val gcounterSerializer = GCounter.serializer()
        private val gcounterDoubleSerializer = GCounterDouble.serializer()
        private val lwwSerializer = LWWRegister.serializer(Double.serializer())
        private val hllSerializer = HyperLogLog.serializer()
        private val ddSketchSerializer = DDSketch.serializer()

        private val sumsSerializer = MapSerializer(metricKeySerializer, gcounterSerializer)
        private val sumsDoubleSerializer = MapSerializer(metricKeySerializer, gcounterDoubleSerializer)
        private val gaugesSerializer = MapSerializer(metricKeySerializer, lwwSerializer)
        private val cardinalitiesSerializer = MapSerializer(metricKeySerializer, hllSerializer)
        private val histogramsSerializer = MapSerializer(metricKeySerializer, ddSketchSerializer)
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
        recoverSumsDouble()
        recoverGauges()
        recoverCardinalities()
        recoverHistograms()
    }

    private suspend fun recoverHistograms() {
        val bytes = runCatchingCancellable { store.read(HISTOGRAM_STORE_KEY) }.getOrElse { cause ->
            logger.error(cause) { "otel.metrics.histograms: store read failed, starting fresh" }
            return
        } ?: return
        val recovered = runCatchingCancellable<Map<MetricKey, DDSketch>> {
            cbor.decodeFromByteArray(histogramsSerializer, bytes)
        }.getOrElse { cause ->
            logger.warn(cause) { "otel.metrics.histograms: corrupt store entry, starting fresh" }
            return
        }
        lock.withLock { recovered.forEach { (k, v) -> histograms[k] = v } }
    }

    private suspend fun recoverSumsDouble() {
        val bytes = runCatchingCancellable { store.read(SUM_DOUBLE_STORE_KEY) }.getOrElse { cause ->
            logger.error(cause) { "otel.metrics.sums.double: store read failed, starting fresh" }
            return
        } ?: return
        val recovered = runCatchingCancellable<Map<MetricKey, GCounterDouble>> {
            cbor.decodeFromByteArray(sumsDoubleSerializer, bytes)
        }.getOrElse { cause ->
            logger.warn(cause) { "otel.metrics.sums.double: corrupt store entry, starting fresh" }
            return
        }
        lock.withLock { recovered.forEach { (k, v) -> sumsDouble[k] = v } }
    }

    private suspend fun recoverSums() {
        val bytes = runCatchingCancellable { store.read(SUM_STORE_KEY) }.getOrElse { cause ->
            logger.error(cause) { "otel.metrics.sums: store read failed, starting fresh" }
            return
        } ?: return
        val recovered = runCatchingCancellable<Map<MetricKey, GCounter>> {
            cbor.decodeFromByteArray(sumsSerializer, bytes)
        }.getOrElse { cause ->
            logger.warn(cause) { "otel.metrics.sums: corrupt store entry, starting fresh" }
            return
        }
        lock.withLock { recovered.forEach { (k, v) -> sums[k] = v } }
    }

    private suspend fun recoverGauges() {
        val bytes = runCatchingCancellable { store.read(GAUGE_STORE_KEY) }.getOrElse { cause ->
            logger.error(cause) { "otel.metrics.gauges: store read failed, starting fresh" }
            return
        } ?: return
        val recovered = runCatchingCancellable<Map<MetricKey, LWWRegister<Double>>> {
            cbor.decodeFromByteArray(gaugesSerializer, bytes)
        }.getOrElse { cause ->
            logger.warn(cause) { "otel.metrics.gauges: corrupt store entry, starting fresh" }
            return
        }
        lock.withLock { recovered.forEach { (k, v) -> gauges[k] = v } }
    }

    private suspend fun recoverCardinalities() {
        val bytes = runCatchingCancellable { store.read(CARDINALITY_STORE_KEY) }.getOrElse { cause ->
            logger.error(cause) { "otel.metrics.cardinalities: store read failed, starting fresh" }
            return
        } ?: return
        val recovered = runCatchingCancellable<Map<MetricKey, HyperLogLog>> {
            cbor.decodeFromByteArray(cardinalitiesSerializer, bytes)
        }.getOrElse { cause ->
            logger.warn(cause) { "otel.metrics.cardinalities: corrupt store entry, starting fresh" }
            return
        }
        lock.withLock { recovered.forEach { (k, v) -> cardinalities[k] = v } }
    }

    // ── Clearing ──────────────────────────────────────────────────────────────

    /**
     * Drop every metric series this exporter holds and delete its five persisted keys (#2208).
     *
     * **Local-only, and it cannot be otherwise.** [GCounter] and [HyperLogLog] are monotonic
     * join-semilattices: a merge takes the element-wise maximum, so a peer holding the
     * pre-clear state restores it. [LWWRegister] has no "cleared" value to write. Unlike
     * [WarpLogRecordExporter.clear] and [WarpSpanExporter.clear], which suppress the state they
     * drop, this one only forgets it locally. On a replica that does not gossip its metrics —
     * the case this exists for — the distinction never arises.
     *
     * The keys are deleted rather than rewritten empty: there is no retained context to
     * preserve here, so deleting reclaims the bytes and [recover] treats an absent key as empty.
     *
     * **Never throws.** A refused delete returns [MetricExportResult.Failure]; the in-memory
     * maps are cleared either way, so a retry converges.
     *
     * Holds [writeMutex] across the whole turn, so a mutation that encoded the pre-clear map
     * cannot land its stale bytes after the deletes. Without that fence the resurrection is
     * **permanent**: nothing rewrites a deleted key unless that metric kind is used afresh, so a
     * restart brings back every pre-clear series (#2232).
     */
    public suspend fun clear(): MetricExportResult = writeMutex.withLock {
        lock.withLock {
            sums.clear()
            sumsDouble.clear()
            gauges.clear()
            cardinalities.clear()
            histograms.clear()
        }
        runCatchingCancellable {
            store.delete(SUM_STORE_KEY)
            store.delete(SUM_DOUBLE_STORE_KEY)
            store.delete(GAUGE_STORE_KEY)
            store.delete(CARDINALITY_STORE_KEY)
            store.delete(HISTOGRAM_STORE_KEY)
        }.fold(
            onSuccess = { MetricExportResult.Success },
            onFailure = { cause ->
                logger.error(cause) { "otel.metrics: durable delete failed during clear" }
                MetricExportResult.Failure(cause)
            },
        )
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
    public suspend fun incrementSum(key: MetricKey, by: Long = 1L): MetricExportResult =
        writeMutex.withLock {
            val encoded = lock.withLock {
                maybeEvictForNewKey(key, sums)
                val current = sums.getOrPut(key) { GCounter.ZERO }
                sums[key] = current.piece(current.inc(replica, by).delta)
                encodeSums()
            }
            persistSums(encoded, key)
        }

    /**
     * Merge a remote [GCounter] snapshot into this exporter's sum for [key].
     *
     * Idempotent: merging the same snapshot twice produces the same result.
     * Returns [MetricExportResult.Success] after the durable write.
     */
    public suspend fun mergeSum(key: MetricKey, remote: GCounter): MetricExportResult =
        writeMutex.withLock {
            val encoded = lock.withLock {
                val current = sums[key] ?: GCounter.ZERO
                sums[key] = current.piece(remote)
                encodeSums()
            }
            persistSums(encoded, key)
        }

    /** Read the current sum value for [key], or 0 if the key has never been incremented. */
    public fun sumValue(key: MetricKey): Long = lock.withLock {
        sums[key]?.value ?: 0L
    }

    /** Return a snapshot of the [GCounter] for [key] (for gossip/anti-entropy). */
    public fun sumSnapshot(key: MetricKey): GCounter = lock.withLock {
        sums[key] ?: GCounter.ZERO
    }

    // ── Double sum (GCounterDouble) ────────────────────────────────────────────

    /**
     * Increment the exact-precision cumulative sum for [key] by [by] on this replica.
     * The double-precision sibling of [incrementSum]; a monotonic OTLP `DOUBLE_SUM`
     * routes here, keeping full precision (no truncation, no fixed-point scaling).
     * Returns [MetricExportResult.Success] after the durable write.
     */
    public suspend fun incrementSumDouble(key: MetricKey, by: Double): MetricExportResult =
        writeMutex.withLock {
            val encoded = lock.withLock {
                maybeEvictForNewKey(key, sumsDouble)
                val current = sumsDouble.getOrPut(key) { GCounterDouble.ZERO }
                sumsDouble[key] = current.piece(current.inc(replica, by).delta)
                encodeSumsDouble()
            }
            persistSumsDouble(encoded, key)
        }

    /**
     * Merge a remote [GCounterDouble] snapshot into this exporter's double-sum for [key].
     *
     * Idempotent: merging the same snapshot twice produces the same result.
     * Returns [MetricExportResult.Success] after the durable write.
     */
    public suspend fun mergeSumDouble(key: MetricKey, remote: GCounterDouble): MetricExportResult =
        writeMutex.withLock {
            val encoded = lock.withLock {
                val current = sumsDouble[key] ?: GCounterDouble.ZERO
                sumsDouble[key] = current.piece(remote)
                encodeSumsDouble()
            }
            persistSumsDouble(encoded, key)
        }

    /** Read the current double-sum value for [key], or 0.0 if the key has never been incremented. */
    public fun doubleSumValue(key: MetricKey): Double = lock.withLock {
        sumsDouble[key]?.value ?: 0.0
    }

    /** Return a snapshot of the [GCounterDouble] for [key] (for gossip/anti-entropy). */
    public fun doubleSumSnapshot(key: MetricKey): GCounterDouble = lock.withLock {
        sumsDouble[key] ?: GCounterDouble.ZERO
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
    ): MetricExportResult = writeMutex.withLock {
        val encoded = lock.withLock {
            maybeEvictForNewKey(key, gauges)
            val current = gauges.getOrPut(key) { LWWRegister.empty<Double>() }
            val write = current.set(replica, timestamp, value)
            gauges[key] = current.piece(write)
            encodeGauges()
        }
        persistGauges(encoded, key)
    }

    /**
     * Merge a remote [LWWRegister] snapshot into this exporter's gauge for [key].
     *
     * Idempotent. Returns [MetricExportResult.Success] after the durable write.
     */
    public suspend fun mergeGauge(
        key: MetricKey,
        remote: LWWRegister<Double>,
    ): MetricExportResult = writeMutex.withLock {
        val encoded = lock.withLock {
            val current = gauges[key] ?: LWWRegister.empty<Double>()
            gauges[key] = current.piece(remote)
            encodeGauges()
        }
        persistGauges(encoded, key)
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
    public suspend fun addCardinality(key: MetricKey, element: String): MetricExportResult =
        writeMutex.withLock {
            val encoded = lock.withLock {
                maybeEvictForNewKey(key, cardinalities)
                val current = cardinalities.getOrPut(key) { HyperLogLog.empty() }
                val patch = current.add(element)
                cardinalities[key] = current.piece(patch.delta)
                encodeCardinalities()
            }
            persistCardinalities(encoded, key)
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
    ): MetricExportResult = writeMutex.withLock {
        val encoded = lock.withLock {
            val current = cardinalities[key] ?: HyperLogLog.empty()
            cardinalities[key] = current.piece(remote)
            encodeCardinalities()
        }
        persistCardinalities(encoded, key)
    }

    /** Return the current distinct-element estimate for [key], or 0 if no elements have been added. */
    public fun cardinalityEstimate(key: MetricKey): Long = lock.withLock {
        cardinalities[key]?.estimate() ?: 0L
    }

    /** Return a snapshot of the [HyperLogLog] for [key] (for gossip/anti-entropy). */
    public fun cardinalitySnapshot(key: MetricKey): HyperLogLog = lock.withLock {
        cardinalities[key] ?: HyperLogLog.empty()
    }

    // ── Exponential histogram (DDSketch) ──────────────────────────────────────

    /**
     * Record one measurement [value] into the histogram for [key]. A new series starts
     * from [histogramPrototype], so every series shares the cluster-wide configuration.
     * Returns [MetricExportResult.Success] after the durable write.
     *
     * **No double-count under merge-retry.** The per-bucket counts are per-replica
     * [GCounter] slots, so re-merging the same remote snapshot never inflates them.
     * Sequential [recordHistogram] calls each *do* count — the correct cumulative
     * distribution semantics.
     *
     * @throws IllegalArgumentException if [value] is NaN or infinite.
     */
    public suspend fun recordHistogram(key: MetricKey, value: Double): MetricExportResult =
        writeMutex.withLock {
            val encoded = lock.withLock {
                maybeEvictForNewKey(key, histograms)
                val current = histograms.getOrPut(key) { histogramPrototype }
                histograms[key] = current.piece(current.add(replica, value).delta)
                encodeHistograms()
            }
            persistHistograms(encoded, key)
        }

    /**
     * Merge a remote [DDSketch] snapshot into this exporter's histogram for [key].
     *
     * Idempotent and lossless: the merged sketch equals the sketch of the combined
     * measurement stream. Returns [MetricExportResult.Success] after the durable write.
     *
     * @throws IllegalArgumentException if [remote]'s relative accuracy is not
     *   OTLP-aligned (see [alphaForOtlpScale]), or if [key] already holds a sketch
     *   with a different configuration (DDSketch configs must match exactly to merge).
     */
    public suspend fun mergeHistogram(key: MetricKey, remote: DDSketch): MetricExportResult {
        // Validation stays outside the mutex: it throws by contract, and a throwing caller has no
        // business holding the durable-write section.
        otlpScaleFor(remote.relativeAccuracy) // reject non-OTLP-aligned sketches up front
        return writeMutex.withLock {
            val encoded = lock.withLock {
                val current = histograms[key]
                histograms[key] = current?.piece(remote) ?: remote
                encodeHistograms()
            }
            persistHistograms(encoded, key)
        }
    }

    /**
     * Estimate the [q]-quantile (`q` in `[0, 1]`) of all values recorded for [key],
     * within the sketch's relative accuracy — or `null` if the series holds no values.
     */
    public fun histogramQuantile(key: MetricKey, q: Double): Double? = lock.withLock {
        histograms[key]?.takeIf { it.count > 0L }?.quantile(q)
    }

    /** Return a snapshot of the [DDSketch] for [key] (for gossip/anti-entropy). */
    public fun histogramSnapshot(key: MetricKey): DDSketch = lock.withLock {
        histograms[key] ?: histogramPrototype
    }

    // ── Diagnostics ───────────────────────────────────────────────────────────

    /** Total number of distinct [MetricKey]s tracked across all kinds. */
    public fun metricCount(): Int = lock.withLock { totalCount() }

    /**
     * A converged snapshot of **every** metric series across all five kinds, as one
     * replicable [MetricCatalog]. The metric analogue of the log buffer's `snapshot()`;
     * the tap host offers this value to a joining puller.
     */
    public fun snapshotAll(): MetricCatalog = lock.withLock {
        MetricCatalog(
            sums = sums.toMap(),
            doubleSums = sumsDouble.toMap(),
            gauges = gauges.toMap(),
            cardinalities = cardinalities.toMap(),
            histograms = histograms.toMap(),
        )
    }

    // ── Encoding (called inside lock) ─────────────────────────────────────────

    private fun encodeSums(): ByteArray =
        cbor.encodeToByteArray(sumsSerializer, sums)

    private fun encodeSumsDouble(): ByteArray =
        cbor.encodeToByteArray(sumsDoubleSerializer, sumsDouble)

    private fun encodeGauges(): ByteArray =
        cbor.encodeToByteArray(gaugesSerializer, gauges)

    private fun encodeCardinalities(): ByteArray =
        cbor.encodeToByteArray(cardinalitiesSerializer, cardinalities)

    private fun encodeHistograms(): ByteArray =
        cbor.encodeToByteArray(histogramsSerializer, histograms)

    // ── Persistence (called outside lock) ────────────────────────────────────

    private suspend fun persistSums(encoded: ByteArray, key: MetricKey): MetricExportResult =
        persist(SUM_STORE_KEY, encoded, key)

    private suspend fun persistSumsDouble(encoded: ByteArray, key: MetricKey): MetricExportResult =
        persist(SUM_DOUBLE_STORE_KEY, encoded, key)

    private suspend fun persistGauges(encoded: ByteArray, key: MetricKey): MetricExportResult =
        persist(GAUGE_STORE_KEY, encoded, key)

    private suspend fun persistCardinalities(encoded: ByteArray, key: MetricKey): MetricExportResult =
        persist(CARDINALITY_STORE_KEY, encoded, key)

    private suspend fun persistHistograms(encoded: ByteArray, key: MetricKey): MetricExportResult =
        persist(HISTOGRAM_STORE_KEY, encoded, key)

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
     * Eviction selects from whichever of the four maps holds the oldest/newest entry
     * by insertion order. The evicted key is always logged at WARN.
     */
    private fun <V> maybeEvictForNewKey(key: MetricKey, map: LinkedHashMap<MetricKey, V>) {
        if (key in map) return
        if (totalCount() < maxMetrics) return
        evictOne()
    }

    private fun totalCount(): Int =
        sums.size + sumsDouble.size + gauges.size + cardinalities.size + histograms.size

    private fun evictOne() {
        val victim = when (bufferPolicy) {
            MetricBufferPolicy.DROP_OLDEST -> pickDropOldestVictim()
            MetricBufferPolicy.DROP_NEWEST -> pickDropNewestVictim()
        } ?: return
        logEviction(victim)
        sums.remove(victim)
        sumsDouble.remove(victim)
        gauges.remove(victim)
        cardinalities.remove(victim)
        histograms.remove(victim)
    }

    /**
     * The eviction victim under DROP_OLDEST: the first key of the first non-empty map in
     * fixed map order (sums → sumsDouble → gauges → cardinalities → histograms). NOT a
     * true cross-map insertion-oldest — insertion time is not comparable across the five
     * LinkedHashMaps.
     */
    private fun pickDropOldestVictim(): MetricKey? =
        listOfNotNull(
            sums.keys.firstOrNull(), sumsDouble.keys.firstOrNull(),
            gauges.keys.firstOrNull(), cardinalities.keys.firstOrNull(),
            histograms.keys.firstOrNull(),
        ).firstOrNull()

    /**
     * The eviction victim under DROP_NEWEST: the last key of the last non-empty map in the
     * same fixed map order. NOT a true cross-map insertion-newest (see [pickDropOldestVictim]).
     */
    private fun pickDropNewestVictim(): MetricKey? =
        listOfNotNull(
            sums.keys.lastOrNull(), sumsDouble.keys.lastOrNull(),
            gauges.keys.lastOrNull(), cardinalities.keys.lastOrNull(),
            histograms.keys.lastOrNull(),
        ).lastOrNull()

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
