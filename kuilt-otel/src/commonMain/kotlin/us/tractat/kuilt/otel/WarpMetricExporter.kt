@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.otel

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.crdt.DDSketch
import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.GCounterDouble
import us.tractat.kuilt.crdt.HyperLogLog
import us.tractat.kuilt.crdt.LWWRegister
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.Quilted
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.math.plusOrNull
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.store.StoreKey

private val logger = KotlinLogging.logger("us.tractat.kuilt.otel.WarpMetricExporter")

/**
 * A CRDT-backed metric exporter for cumulative sums, gauges, cardinality estimates, and
 * exponential histograms.
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
 *   only when it matches exactly. Values arrive one at a time through [recordHistogram], or
 *   already bucketed — one interval of an SDK's exponential histogram — as a
 *   [MetricUpdate.AddExponentialHistogram] at [histogramScale] or finer.
 *
 * ## Key inversion
 *
 * Every mutating method — [incrementSum], [incrementSumDouble], [setGauge], [addCardinality],
 * [recordHistogram], their `merge*` twins, and the batch form [record] — returns [MetricExportResult.Success] only once
 * the updated CRDT is **durably written to [DurableStore]**, never when it is delivered to any
 * backend. Delivery is asynchronous and eventually consistent.
 *
 * A [MetricExportResult.Failure] means "not yet durable", not "not counted": the in-memory
 * CRDT keeps the change, and the next successful write of that kind persists it. Retrying a
 * failed [incrementSum] therefore adds it twice.
 *
 * Two Failures mean "not counted". One is a refusal, before anything is applied, of a sum
 * increment or histogram update that would carry one of this replica's counts past
 * [Long.MAX_VALUE]: its cause is a [MetricOverflowException] (#3318, #3354), and nothing was
 * applied or written. The other is a kind whose persisted series no read has joined yet: its
 * updates are not applied and its key is not written until one does, and the cause is the store's
 * refusal of the read (#3390). A kind is read by [recover], or by its own first turn on an
 * exporter that skipped [recover], and again by every turn while the store refuses. See [recover].
 *
 * ## Batches
 *
 * Every write re-encodes the whole map of its kind, so a caller holding many updates at once — an
 * SDK export, say — hands them to [record] as a list of [MetricUpdate]s. That is one encode and one
 * write per kind the batch touches instead of one per update, under the same contract; the
 * single-point mutators above are [record] with one update.
 *
 * ## Buffer cap
 *
 * The total number of distinct [MetricKey]s across all kinds is bounded by [maxMetrics].
 * When the cap is exceeded, the [bufferPolicy] selects a series to evict. **Every eviction
 * is logged** — the metric name and kind are emitted at WARN so an operator can detect
 * label-cardinality explosions.
 *
 * ## Failure reporting
 *
 * A refused durable write is logged **once per outage, not once per attempt** (#2593). Every
 * mutating call retries the write the last one could not land, so a store that stays broken is one
 * unchanging condition, and reporting it per call produced a line — and a stack trace — per metric
 * write, forever.
 *
 * The outage is tracked as the **set of store keys currently being refused**, and the line is
 * emitted only by the failure that makes that set non-empty. All five metric kinds therefore share
 * one outage, and — the part a boolean latch gets wrong — a store that refuses one key while
 * accepting another stays a single outage rather than re-reporting on every alternation. The next
 * line comes only once the store has taken back every key it was refusing. Every failure is still
 * returned to the caller as [MetricExportResult.Failure] carrying the cause, so nothing is hidden
 * from a programmatic reader — only from the log. See `durableWriteOutage`.
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
    }

    /**
     * The OTLP exponential-histogram scale every histogram series here is kept at — the scale
     * [histogramPrototype]'s accuracy aligns with, [DEFAULT_OTLP_HISTOGRAM_SCALE] by default.
     *
     * Every series holds exactly [histogramPrototype]'s configuration, so this one number describes
     * them all: a new series starts from the prototype, [mergeHistogram] refuses a sketch of any
     * other configuration, and [recover] drops a persisted series of another configuration.
     *
     * A [MetricUpdate.AddExponentialHistogram] must arrive at this scale or a finer one: a coarser
     * bucket cannot be split without breaking the sketch's accuracy, so [record] refuses it. A
     * producer that controls its bucketing, such as an OpenTelemetry SDK's exponential-histogram
     * aggregation, should cap its own scale at this value.
     */
    // Computing it fails fast on a non-OTLP-aligned accuracy (throws IllegalArgumentException).
    public val histogramScale: Int = otlpScaleFor(histogramPrototype.relativeAccuracy)

    // Two-tier locking, both explicit primitives (repo policy: correctness must hold under a real
    // multi-threaded dispatcher; limitedParallelism(1) confinement is BANNED).
    //
    //  - `lock` (atomicfu reentrant) guards the five CRDT maps only. No suspend call is ever made
    //    inside it — CBOR encode/decode and CRDT mutations are pure.
    //  - `writeMutex` (coroutine Mutex) serializes the whole *durable-write* critical section: the
    //    mutate-and-encode and the store write form one ordered unit. [recover] and the read a turn
    //    owes after a refused one run inside it too, so a read and the join it installs never
    //    interleave with a write of the same kind (#3390). Without it a mutation could
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
    private val persistence = PersistenceHealthTracker<StoreKey>()

    /**
     * Completed persistence turns and unresolved failures, tracked separately for each stored
     * kind and for clear's delete sequence. Successful reads do not acknowledge writes.
     *
     * @sample us.tractat.kuilt.otel.samplePersistenceHealth
     */
    public val health: StateFlow<PersistenceHealth> = persistence.health

    // LinkedHashMap preserves insertion order, which drives the DROP_OLDEST/DROP_NEWEST
    // eviction policies. The *insertion* of a new key records its age; we don't update
    // order on access (this is not an LRU cache).
    private val sums: LinkedHashMap<MetricKey, GCounter> = LinkedHashMap()
    private val sumsDouble: LinkedHashMap<MetricKey, GCounterDouble> = LinkedHashMap()
    private val gauges: LinkedHashMap<MetricKey, LWWRegister<Double>> = LinkedHashMap()
    private val cardinalities: LinkedHashMap<MetricKey, HyperLogLog> = LinkedHashMap()
    private val histograms: LinkedHashMap<MetricKey, DDSketch> = LinkedHashMap()

    /** The sum series a [record] has already been refused for; read only inside [lock] (#3318). */
    private val refusedSums = FirstSightings()

    /** The histogram series a [record] has already been refused for; read only inside [lock] (#3354). */
    private val refusedHistograms = FirstSightings()

    /**
     * Whether a durable-write outage is currently **open** — i.e. a refused store write has already
     * been reported and the store has not taken a write since. Read and written only by [persist];
     * nothing else touches it.
     *
     * ## What it is for
     *
     * A refused write is retried by the next mutating call, so a store that stays broken — a
     * quota-bound `IndexedDbDurableStore` is the shape [WarpLogRecordExporter]'s KDoc names — would
     * otherwise report one unchanging condition once per metric write, forever, each line carrying
     * a stack trace. Measured here at 300 throwable-bearing lines over a 300-write outage (#2593),
     * matching what #2237 measured on the sibling.
     *
     * ## What the population is, and why that is the whole design
     *
     * Exactly [persist] — the single function through which every mutating method on this exporter,
     * across all five metric kinds and all five store keys, performs its durable write, and the
     * single function that emits a `"durable write failed for metric"` line.
     *
     * ## Why it holds a SET of keys rather than a boolean
     *
     * Because a store may refuse **one** key while accepting the others, and each write is to
     * exactly one key ([record] writes each kind it touched separately). A boolean latch alternates under that workload: `recordHistogram`
     * fails and opens it, `incrementSum` succeeds and clears it, the next `recordHistogram` fails
     * and reports again — one line per failing call, unbounded, which is the defect this latch
     * exists to prevent, back at a workload-dependent constant.
     *
     * That is not a contrived store. `RefuseSegmentWritesStore`'s KDoc names it: an
     * `IndexedDbDurableStore` under quota pressure refuses **large** writes while small ones
     * succeed — and a [DDSketch] histogram blob against a [GCounter] map is exactly that size
     * split, so the failing pair is `otel.metrics.histograms` against `otel.metrics.sums`.
     *
     * ([WarpSpanExporter] needs no set: its three write paths share one key, and its clock persist
     * rides inside the same guarded turn, so a partial refusal fails the whole turn and
     * leaves the latch open. [WarpLogRecordExporter] is immune for the same grouping reason.
     * [persist] has no such grouping, which is what makes the set necessary here specifically.)
     *
     * The rule is therefore **report on the `empty → non-empty` transition, and remove a key on its
     * own success**. A success on `sums` removes a key the set does not contain, so the set stays
     * non-empty and the histogram's next refusal stays quiet; only a store that has taken back
     * every key it refused empties the set and re-arms the report.
     *
     * **[clear] is deliberately outside it.** Its failure is a refused `delete`, not a refused
     * write; it reports through a line of its own, is not retried, and — this is the operative part
     * — a store that takes a delete has proven nothing about whether it will take a write. Folding
     * it in would make the counted population wider than the reported one, which is precisely the
     * defect #2237 turned out to be on the sibling once the obvious dedup shipped there: keyed on
     * anything that moves on *every* failure, a member of the difference opens the key first and the
     * store's own outage is then reported **zero** times rather than once. That is worse than the
     * noise the dedup replaced, because the log then points at the wrong subsystem entirely.
     * `WarpMetricExporterFailureReportingTest.aRefusedClearDeleteDoesNotSwallowTheDurableWriteReport`
     * is the arm that reds if the two are ever merged.
     *
     * ## Concurrency
     *
     * The set is bounded by the five store keys, so it cannot grow.
     *
     * ## Concurrency
     *
     * A [MutableStateFlow], not an `atomicfu` `atomic()`: the atomicfu **Gradle plugin** is not
     * applied in this build (the dependency is present for the multiplatform `locks` API only), so
     * an atomic *field* would be untransformed. `getAndUpdate`/`update` are real lock-free CAS
     * loops on every target — never dispatcher confinement, per repo policy.
     *
     * [writeMutex] serializes whole turns, so today two [persist] calls cannot overlap; the CAS is
     * what keeps this correct without depending on that. **Exactly one racing caller reports**: the
     * failure arm adds its key and reports only if `getAndUpdate` handed it back an *empty* set, so
     * a loser is by definition looking at an outage someone has already announced.
     *
     * Both arms are CAS loops that retry until they land, so neither can lose an update. That
     * matters most on the success arm: the shape that must not happen is a key left in the set
     * against a store that has resumed taking it, which would silence the *next* outage entirely.
     * A dropped *failure* update would merely cost a duplicate line, which is honest — the
     * asymmetry is in the cost, not in the mechanism.
     */
    private val durableWriteOutage = MutableStateFlow<Set<StoreKey>>(emptySet())

    private companion object {
        // Bookkeeping identity only; never passed to the store.
        private val CLEAR_WORK = setOf(StoreKey("otel.metrics.clear"))
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
     * What one read of a kind's persisted series found. The split that matters is the last two
     * arms: a store that **refused** the read leaves what is on disk unknown, while bytes that came
     * back and did not **decode** are known to be bad.
     */
    private sealed interface KindRead<out V> {
        /** Nothing of this kind was ever persisted. */
        data object Absent : KindRead<Nothing>

        class Found<V>(val series: Map<MetricKey, V>) : KindRead<V>

        /** Bytes came back and did not decode. Bad data, so the kind starts fresh. */
        class Undecodable(val cause: Throwable) : KindRead<Nothing>

        /**
         * The store refused the read, a store-minted cancellation included. Each kind is one
         * whole-map key, so a write now would replace series this run never saw (#3390).
         */
        class Refused(val cause: Throwable) : KindRead<Nothing>
    }

    /** Whether a kind's write turns may run, or first owe a read of its persisted series. */
    private sealed interface PersistedRead {
        /** Read, absent, or undecodable and started fresh. Turns of this kind write. */
        data object Settled : PersistedRead

        /**
         * The read was [KindRead.Refused]. Every turn of this kind reads again first. [first] is
         * whether this refusal is the kind's first since a read last landed, the one that is logged.
         */
        class Owed(val cause: Throwable, val first: Boolean) : PersistedRead
    }

    /** Why a kind still owes a read of its persisted series. */
    private enum class OwedRead {
        /** Nothing has read it yet: the exporter was just built, and [recover] has not run. */
        UNREAD,

        /** A read of it was refused, and no read has landed since. */
        REFUSED,
    }

    /**
     * The kinds whose persisted series no read has yet joined into memory; a kind absent here is
     * [PersistedRead.Settled]. **Every kind starts owed**, as [OwedRead.UNREAD]: an exporter that
     * never calls [recover] holds the same fresh map a refused read leaves, so its first write of a
     * kind would replace every series a previous process made durable (#3390). Its first turn of
     * each kind reads first instead, and joins what it finds, exactly as after a refused read.
     *
     * Guarded by [lock] and changed only under [writeMutex], by [settle], in the same [lock]
     * section that installs what the read found, and by a [clear] that deleted every key. So no
     * turn can see a kind settled whose series are not yet in memory.
     *
     * **Why a turn of an owed kind admits nothing, rather than applying in memory and skipping only
     * the write.** Three of the five kinds count per replica — [GCounter], [GCounterDouble] and
     * [DDSketch]'s bucket cells — and their join is the per-replica **maximum**. An update applied
     * to the fresh map a refused read left would start this replica's slot again from zero beside
     * the durable one: a second history of the same slot. The heal's join keeps the larger of the
     * two and silently drops the other (a durable `5` and an unread-time `+10` join to `10`, not
     * `15`). Admitting nothing keeps memory on the store's own history, so the join on heal is
     * exact, and it is idempotent, so a repeated read cannot double-count either.
     */
    private var owedReads: Map<StoredKind, OwedRead> = StoredKind.entries.associateWith { OwedRead.UNREAD }

    /**
     * Reload persisted metric state from [store]. Call once at startup before any
     * calls to the mutating methods, so the readers see the persisted series from the start. A
     * mutating call made first reads its kind itself before it writes, so skipping this never
     * overwrites what is on disk. Idempotent: a second call re-reads the same bytes and joins
     * them into memory, so a series memory already holds ahead of the store, an increment a refused
     * write left there say, is kept.
     *
     * If persisted state is corrupt or absent for a given kind, that kind starts fresh
     * (a warning is logged). The other kinds are unaffected.
     *
     * A **refused** read, a `CancellationException` the [store] mints at this live caller from its
     * own `withTimeout` included, costs its kind alone and **fails closed** (#3390): every later
     * call that would write that kind, [record] and the `merge*` methods, reads it again first, and
     * while the read is still refused admits **nothing** of that kind, writes nothing of it, and
     * returns [MetricExportResult.Failure] with the read's cause. Updates of the other kinds in the
     * same batch go ahead. The read that lands joins the persisted series into memory and that turn
     * runs. Until then the kind reads as empty. Starting fresh here instead wrote a map naming only
     * this run's series over the persisted one: a durable sum of `5` read back as `1`.
     *
     * Serialized with every write turn by the same mutex, so it may run concurrently with them.
     * A cancellation of the caller's own job still propagates (#3388).
     */
    public suspend fun recover(): Unit = writeMutex.withLock {
        StoredKind.entries.forEach { kind ->
            val read = readKind(kind)
            if (read is PersistedRead.Owed) {
                persistence.recoveryFailed(read.cause)
                logRefusal(kind, read)
            }
        }
    }

    /**
     * Log a refused read of [kind]: at ERROR the first time, at DEBUG after that, as a store that
     * stays unreadable is refused again on every turn of the kind and would otherwise log a stack
     * trace per write.
     */
    private fun logRefusal(kind: StoredKind, read: PersistedRead.Owed) {
        if (read.first) {
            logger.error(read.cause) {
                "${kind.storeKey.name}: store read failed; this kind's writes are refused until a read succeeds"
            }
        } else {
            logger.debug {
                "${kind.storeKey.name}: persisted series still unreadable, so this turn admits and writes " +
                    "nothing of the kind: ${read.cause}"
            }
        }
    }

    /**
     * Read [kind]'s persisted series, join what it found into memory, and record whether the
     * kind's turns may now write. Shared by [recover] and the read a turn owes ([refusedReads]).
     * Must hold [writeMutex].
     */
    private suspend fun readKind(kind: StoredKind): PersistedRead = when (kind) {
        StoredKind.SUMS -> settle(kind, read(kind, sumsSerializer), sums)
        StoredKind.SUMS_DOUBLE -> settle(kind, read(kind, sumsDoubleSerializer), sumsDouble)
        StoredKind.GAUGES -> settle(kind, read(kind, gaugesSerializer), gauges)
        StoredKind.CARDINALITIES -> settle(kind, read(kind, cardinalitiesSerializer), cardinalities)
        StoredKind.HISTOGRAMS -> settle(kind, read(kind, histogramsSerializer).keepingPrototypeConfiguration(), histograms)
    }

    private suspend fun <V> read(kind: StoredKind, serializer: KSerializer<Map<MetricKey, V>>): KindRead<V> {
        val bytes = try {
            store.read(kind.storeKey)
        } catch (cause: Throwable) {
            // Only this coroutine's own cancellation may end it; one the store minted is a refused
            // read (#3388).
            currentCoroutineContext().ensureActive()
            return KindRead.Refused(cause)
        } ?: return KindRead.Absent
        return runCatchingCancellable { cbor.decodeFromByteArray(serializer, bytes) }
            .fold(onSuccess = { KindRead.Found(it) }, onFailure = { KindRead.Undecodable(it) })
    }

    /**
     * Join a [KindRead.Found] into [series] and settle [kind], or mark it owed on a refusal.
     *
     * The join and the change to [owedReads] are **one** [lock] section, so a kind is never settled
     * with its persisted series not yet joined, nor joined while still marked owed. It is a join
     * rather than a replace so that a second [recover] keeps what memory holds ahead of the store,
     * and the series it adds are held to [maxMetrics] like any other.
     */
    private fun <V : Quilted<V>> settle(
        kind: StoredKind,
        read: KindRead<V>,
        series: LinkedHashMap<MetricKey, V>,
    ): PersistedRead {
        if (read is KindRead.Undecodable) {
            persistence.recoveryFailed(read.cause)
            logger.warn(read.cause) { "${kind.storeKey.name}: corrupt store entry, starting fresh" }
        }
        return lock.withLock {
            if (read is KindRead.Found) {
                read.series.forEach { (key, persisted) -> series[key] = series[key]?.piece(persisted) ?: persisted }
                while (totalCount() > maxMetrics) evictOne()
            }
            if (read is KindRead.Refused) {
                val first = owedReads[kind] != OwedRead.REFUSED
                owedReads = owedReads + (kind to OwedRead.REFUSED)
                PersistedRead.Owed(read.cause, first)
            } else {
                owedReads = owedReads - kind
                PersistedRead.Settled
            }
        }
    }

    /**
     * A series written under another configuration — the prototype changed across a restart —
     * cannot be kept: its bucket indices mean other values, so an AddExponentialHistogram mapped at
     * histogramScale would land at the wrong values, and a peer's merge would refuse it.
     */
    private fun KindRead<DDSketch>.keepingPrototypeConfiguration(): KindRead<DDSketch> {
        if (this !is KindRead.Found) return this
        val (kept, foreign) = series.entries.partition { (_, sketch) -> sketch.hasPrototypeConfiguration() }
        if (foreign.isEmpty()) return this
        logger.warn {
            "otel.metrics.histograms: dropping ${foreign.size} recovered series written under another " +
                "configuration than histogramPrototype (${foreign.first().value.configuration()} vs " +
                "${histogramPrototype.configuration()}); the next histogram write removes them from the store"
        }
        return KindRead.Found(kept.associate { (key, sketch) -> key to sketch })
    }

    /**
     * Read again each of [kinds] whose read is owed, in the fixed kind order, and return the ones
     * still refused with their causes. Must hold [writeMutex].
     *
     * A turn of a kind returned here admits nothing of it and writes nothing of it; it counts as a
     * failed turn of that kind on [health]. Only the kind's first refusal is logged as an error,
     * whether [recover] or a turn met it; see [logRefusal].
     */
    private suspend fun refusedReads(kinds: Set<StoredKind>): Map<StoredKind, Throwable> {
        val owed = lock.withLock { owedReads.keys }
        if (owed.none { it in kinds }) return emptyMap()
        val refused = LinkedHashMap<StoredKind, Throwable>()
        StoredKind.entries.filter { it in kinds && it in owed }.forEach { kind ->
            when (val read = readKind(kind)) {
                is PersistedRead.Owed -> {
                    refused[kind] = read.cause
                    persistence.failed(setOf(kind.storeKey), read.cause)
                    logRefusal(kind, read)
                }
                PersistedRead.Settled -> logger.debug { "${kind.storeKey.name}: persisted series read; writes of the kind go ahead" }
            }
        }
        return refused
    }

    /**
     * Run [turn] under [writeMutex] once [kind]'s persisted series has been read; while that read is
     * still refused, return its [MetricExportResult.Failure] without running [turn]. The `merge*`
     * methods' turn; [record] reads per kind itself, since a batch spans kinds.
     */
    private suspend inline fun writeTurn(kind: StoredKind, turn: () -> MetricExportResult): MetricExportResult =
        writeMutex.withLock {
            refusedReads(setOf(kind))[kind]?.let { MetricExportResult.Failure(it) } ?: turn()
        }

    /** Whether this sketch can live in [histograms]: [histogramPrototype]'s exact configuration. */
    private fun DDSketch.hasPrototypeConfiguration(): Boolean =
        relativeAccuracy == histogramPrototype.relativeAccuracy &&
            minIndexedValue == histogramPrototype.minIndexedValue &&
            maxIndexedValue == histogramPrototype.maxIndexedValue

    private fun DDSketch.configuration(): String =
        "(α=$relativeAccuracy, range=[$minIndexedValue, $maxIndexedValue])"

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
     * Its failure line is **not** deduplicated against the durable-write outage latch, and that is
     * deliberate on both arms: a `clear` is caller-initiated rather than retried, so it cannot
     * produce the unbounded volume #2593 is about; and a store that takes a delete has proven
     * nothing about whether it will take a write, so letting a refused delete open — or close —
     * that latch would let one path silence the other. See `durableWriteOutage`.
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
        val deleted = try {
            store.delete(SUM_STORE_KEY)
            store.delete(SUM_DOUBLE_STORE_KEY)
            store.delete(GAUGE_STORE_KEY)
            store.delete(CARDINALITY_STORE_KEY)
            store.delete(HISTOGRAM_STORE_KEY)
            Result.success(Unit)
        } catch (cause: Throwable) {
            // `clear` never throws: a cancellation the store minted is a refused delete, and only
            // this coroutine's own cancellation propagates (#3388).
            currentCoroutineContext().ensureActive()
            Result.failure(cause)
        }
        deleted.fold(
            onSuccess = {
                // Every key is now known absent and memory is empty, so no kind still owes a read.
                lock.withLock { owedReads = emptyMap() }
                persistence.acknowledged(CLEAR_WORK)
                MetricExportResult.Success
            },
            onFailure = { cause ->
                persistence.failed(CLEAR_WORK, cause)
                logger.error(cause) { "otel.metrics: durable delete failed during clear" }
                MetricExportResult.Failure(cause)
            },
        )
    }

    // ── Batch ─────────────────────────────────────────────────────────────────

    /**
     * Apply a **batch** of [updates] in order, then make one durable write per metric kind the
     * batch touched — rather than one per update.
     *
     * Each mutator on this exporter re-encodes **every** series of its kind and writes the result,
     * so N single-point calls cost N whole-map encodes and N writes, and an SDK export of N points
     * through `KuiltMetricExporter` grew quadratically in the series count (#3284). A batch pays that
     * fixed cost once per kind: a batch of a thousand sum increments is one encode of the sums and
     * one write.
     *
     * **The durability contract is unchanged** — it is the single-point mutators' contract, stated
     * for a batch, and those mutators are now this method with one update:
     *
     * - Returns [MetricExportResult.Success] only once **every** kind the batch touched is durably
     *   written. Nothing is held back waiting for more updates; the batch is whatever the caller
     *   had in hand, and it is on disk before this returns.
     * - Every touched kind's write is **attempted**, even after one is refused, and the **first**
     *   [MetricExportResult.Failure] (in the fixed kind order sums, double sums, gauges,
     *   cardinalities, histograms) is returned. Stopping at the first refusal would leave the later
     *   kinds off disk, which applying the updates one by one never does.
     * - A kind whose persisted series no read has joined yet — [recover] was refused, or never
     *   called — is read first. While that read is refused, the batch's updates **of that kind** are not applied and
     *   the kind is not written, and its Failure is the read's cause; the other kinds go ahead.
     * - A [MetricExportResult.Failure] from a write means "not yet durable", not "not counted"
     *   (an unread kind, above, and a refused batch, below, are the Failures that mean "not
     *   counted"): every update in
     *   the batch stays in memory, and the next successful write of a refused kind persists it. So
     *   the batch is **not atomic** on disk — a kind the store took is durable even when another
     *   kind was refused — and retrying a failed batch counts its increments twice, exactly as
     *   retrying a failed [incrementSum] does.
     * - On [health] each kind's write is its own turn: a batch counts one turn per kind it
     *   touched, and a partial refusal moves both `acknowledged` and `failed`.
     *
     * In memory the exporter ends where applying [updates] one by one through [incrementSum],
     * [incrementSumDouble], [setGauge], [addCardinality] and [recordHistogram] — or, for a
     * [MetricUpdate.AddExponentialHistogram], through a batch of that one update — would leave it,
     * eviction included: the [maxMetrics] cap is decided per update, in order. On disk, a touched
     * kind holds its state as of the **end** of the batch. The one place that differs from the
     * single-point sequence is a kind whose series a *later* update of another kind evicted: written
     * one by one, the evicted series lingers on disk until that kind's next write; here it is
     * already gone. A kind the batch never touched is not rewritten, as before.
     *
     * An empty [updates] is [MetricExportResult.Success] and touches neither memory nor the store.
     * Each [MetricUpdate] validates its own arguments at construction. Before **any** update is
     * applied, the batch is checked twice more, in this order:
     *
     * 1. What only this exporter's configuration can judge — a [MetricUpdate.AddExponentialHistogram]
     *    coarser than [histogramScale], or one whose count lists the caller has since made negative —
     *    is checked for **every** update and **thrown** as [IllegalArgumentException], since it is
     *    a caller's mistake. Nothing in memory or on disk changes.
     * 2. Whether an update would carry one of this replica's counts past [Long.MAX_VALUE] is
     *    checked for the whole batch, counting its earlier updates to the same series, against the
     *    series as they stand. For a [MetricUpdate.IncrementSum] that is this replica's count for its
     *    sum series (#3318). For a [MetricUpdate.RecordHistogram] or
     *    [MetricUpdate.AddExponentialHistogram] it is this replica's slot in each bucket the update
     *    touches, and in the sketch's zero and overflow counters, and also the sum of counts that
     *    fold onto one bucket (#3354). A weighted count can get there on its own. Such a batch is
     *    **refused**: it returns a [MetricExportResult.Failure] whose cause is a
     *    [MetricOverflowException] naming the series, a [MetricSumOverflowException] or a
     *    [MetricHistogramOverflowException], and nothing from it is applied or written. The refusal
     *    is logged at WARN the first time a series causes one and at DEBUG after that.
     *
     * So neither a bad histogram scale nor an overflow leaves a batch applied half-way, and the
     * one-by-one equivalence above holds for every batch this accepts.
     *
     * @throws IllegalArgumentException if an update holds an exponential histogram whose scale is
     *   below [histogramScale]; nothing in the batch is applied.
     * @sample us.tractat.kuilt.otel.sampleWarpMetricExporterBatch
     */
    public suspend fun record(updates: List<MetricUpdate>): MetricExportResult {
        if (updates.isEmpty()) return MetricExportResult.Success
        updates.forEach(::validate)
        return writeMutex.withLock {
            // A kind whose persisted series is still unread admits nothing; the rest of the batch
            // goes on. Read first, so the overflow check below counts what the store held (#3390).
            val unread = refusedReads(updates.mapTo(HashSet()) { it.storedKind })
            val admitted = if (unread.isEmpty()) updates else updates.filter { it.storedKind !in unread }
            var overflow: MetricOverflowException? = null
            var firstRefusal = false
            val writes = lock.withLock {
                overflow = firstOverflowLocked(admitted)?.also { refused ->
                    firstRefusal = when (refused) {
                        is MetricSumOverflowException -> refusedSums.firstSighting(refused.key, sums.keys)
                        is MetricHistogramOverflowException -> refusedHistograms.firstSighting(refused.key, histograms.keys)
                    }
                }
                if (overflow != null) return@withLock emptyList()
                // The last series each kind's updates named — only for the failure log line.
                val touched = LinkedHashMap<StoredKind, MetricKey>()
                admitted.forEach { update -> touched[applyLocked(update)] = update.key }
                StoredKind.entries.mapNotNull { kind ->
                    touched[kind]?.let { lastKey -> PendingWrite(kind, encode(kind), lastKey) }
                }
            }
            overflow?.let { refused ->
                // WARN once per series: a saturated delta series is refused again on every export.
                val line = { "WarpMetricExporter: batch of ${updates.size} refused, nothing applied: ${refused.message}" }
                if (firstRefusal) logger.warn(line) else logger.debug(line)
                return@withLock MetricExportResult.Failure(refused)
            }
            val failures = HashMap<StoredKind, MetricExportResult.Failure>()
            unread.forEach { (kind, cause) -> failures[kind] = MetricExportResult.Failure(cause) }
            writes.forEach { write ->
                val result = persist(write.kind.storeKey, write.encoded, write.lastKey)
                if (result is MetricExportResult.Failure) failures[write.kind] = result
            }
            StoredKind.entries.firstNotNullOfOrNull { failures[it] } ?: MetricExportResult.Success
        }
    }

    /** One kind's encoded map, ready to write outside [lock]. */
    private class PendingWrite(val kind: StoredKind, val encoded: ByteArray, val lastKey: MetricKey)

    /** The persisted map [this] update changes. */
    private val MetricUpdate.storedKind: StoredKind
        get() = when (this) {
            is MetricUpdate.IncrementSum -> StoredKind.SUMS
            is MetricUpdate.IncrementSumDouble -> StoredKind.SUMS_DOUBLE
            is MetricUpdate.SetGauge -> StoredKind.GAUGES
            is MetricUpdate.AddCardinality -> StoredKind.CARDINALITIES
            is MetricUpdate.RecordHistogram, is MetricUpdate.AddExponentialHistogram -> StoredKind.HISTOGRAMS
        }

    /** The five persisted maps, in the fixed order a batch writes them. */
    private enum class StoredKind(val storeKey: StoreKey) {
        SUMS(SUM_STORE_KEY),
        SUMS_DOUBLE(SUM_DOUBLE_STORE_KEY),
        GAUGES(GAUGE_STORE_KEY),
        CARDINALITIES(CARDINALITY_STORE_KEY),
        HISTOGRAMS(HISTOGRAM_STORE_KEY),
    }

    /**
     * Refuse an update this exporter cannot apply, before [record] applies any of the batch. Every
     * variant has an arm, so a new one has to decide what it needs checked here; most need nothing,
     * because their constructor already refused what their CRDT would.
     */
    private fun validate(update: MetricUpdate) {
        when (update) {
            is MetricUpdate.IncrementSum,
            is MetricUpdate.IncrementSumDouble,
            is MetricUpdate.SetGauge,
            is MetricUpdate.AddCardinality,
            is MetricUpdate.RecordHistogram,
            -> Unit
            is MetricUpdate.AddExponentialHistogram -> {
                require(update.scale >= histogramScale) {
                    "AddExponentialHistogram for ${update.key.name} is at scale ${update.scale}, coarser than this " +
                        "exporter's histogramScale $histogramScale; its buckets cannot be split without losing accuracy"
                }
                // Checked at construction too, but the lists are the caller's: one mutated since would
                // otherwise make DDSketch.addBuckets throw after earlier updates were applied.
                require(update.positiveCounts.all { it >= 0L } && update.negativeCounts.all { it >= 0L }) {
                    "AddExponentialHistogram for ${update.key.name} holds a negative count"
                }
            }
        }
    }

    /**
     * The refusal for the first update in [updates] that would carry one of this replica's counts
     * past [Long.MAX_VALUE], counting the batch's own earlier updates to the same series, or `null`
     * when every one fits. Called inside [lock], before anything is applied.
     *
     * A [MetricUpdate.IncrementSum] is checked against this replica's count for its series (#3318).
     * A histogram update is checked by making it, through [DDSketch.addOrNull] and
     * [DDSketch.addBucketsOrNull], against a running copy of its series that the batch's earlier
     * histogram updates have already been made to (#3354). Those are the very calls [applyLocked]
     * makes, so the check cannot disagree with the apply about what fits, and an
     * [MetricUpdate.AddExponentialHistogram] whose own counts fold past [Long.MAX_VALUE] is caught
     * here too.
     *
     * Checked against the series as they stand before the batch. An eviction the batch triggers can
     * only remove a series, never raise a count, so this never accepts a batch [applyLocked] would
     * refuse part-way; at worst it refuses one that would just fit after such an eviction.
     */
    private fun firstOverflowLocked(updates: List<MetricUpdate>): MetricOverflowException? {
        val runningSums = HashMap<MetricKey, Long>()
        val runningHistograms = HashMap<MetricKey, DDSketch>()
        for (update in updates) {
            when (update) {
                is MetricUpdate.IncrementSum -> {
                    val count = runningSums[update.key] ?: sums[update.key]?.count(replica) ?: 0L
                    runningSums[update.key] = count.plusOrNull(update.by)
                        ?: return MetricSumOverflowException(update.key, count, update.by)
                }
                is MetricUpdate.RecordHistogram,
                is MetricUpdate.AddExponentialHistogram,
                -> {
                    val current = runningHistograms[update.key] ?: histograms[update.key] ?: histogramPrototype
                    val patch = histogramPatchOrNull(current, update)
                        ?: return MetricHistogramOverflowException(update.key)
                    runningHistograms[update.key] = current.piece(patch.delta)
                }
                is MetricUpdate.IncrementSumDouble,
                is MetricUpdate.SetGauge,
                is MetricUpdate.AddCardinality,
                -> Unit
            }
        }
        return null
    }

    /**
     * The patch [update] makes to the histogram [current], or `null` when it would carry one of this
     * replica's slots past [Long.MAX_VALUE]. The one place a histogram update is turned into a
     * patch, so [firstOverflowLocked]'s check and [applyLocked] cannot drift apart.
     */
    private fun histogramPatchOrNull(current: DDSketch, update: MetricUpdate): Patch<DDSketch>? = when (update) {
        is MetricUpdate.RecordHistogram -> current.addOrNull(replica, update.value)
        is MetricUpdate.AddExponentialHistogram -> {
            val shift = update.scale - histogramScale
            val positive = sketchBuckets(update.positiveOffset, update.positiveCounts, shift)
            val negative = sketchBuckets(update.negativeOffset, update.negativeCounts, shift)
            if (positive == null || negative == null) {
                null
            } else {
                current.addBucketsOrNull(replica, positive, negative, update.zeroCount)
            }
        }
        else -> error("not a histogram update: $update")
    }

    /** Apply one update to its map, evicting first if it opens a new series. Called inside [lock]. */
    private fun applyLocked(update: MetricUpdate): StoredKind = when (update) {
        is MetricUpdate.IncrementSum -> {
            maybeEvictForNewKey(update.key, sums)
            val current = sums.getOrPut(update.key) { GCounter.ZERO }
            sums[update.key] = current.piece(current.inc(replica, update.by).delta)
            StoredKind.SUMS
        }
        is MetricUpdate.IncrementSumDouble -> {
            maybeEvictForNewKey(update.key, sumsDouble)
            val current = sumsDouble.getOrPut(update.key) { GCounterDouble.ZERO }
            sumsDouble[update.key] = current.piece(current.inc(replica, update.by).delta)
            StoredKind.SUMS_DOUBLE
        }
        is MetricUpdate.SetGauge -> {
            maybeEvictForNewKey(update.key, gauges)
            val current = gauges.getOrPut(update.key) { LWWRegister.empty<Double>() }
            gauges[update.key] = current.piece(current.set(replica, update.timestamp, update.value))
            StoredKind.GAUGES
        }
        is MetricUpdate.AddCardinality -> {
            maybeEvictForNewKey(update.key, cardinalities)
            val current = cardinalities.getOrPut(update.key) { HyperLogLog.empty() }
            cardinalities[update.key] = current.piece(current.add(update.element).delta)
            StoredKind.CARDINALITIES
        }
        is MetricUpdate.RecordHistogram,
        is MetricUpdate.AddExponentialHistogram,
        -> {
            maybeEvictForNewKey(update.key, histograms)
            val current = histograms.getOrPut(update.key) { histogramPrototype }
            val patch = checkNotNull(histogramPatchOrNull(current, update)) {
                "histogram update for ${update.key.name} overflows, yet record's check passed it"
            }
            histograms[update.key] = current.piece(patch.delta)
            StoredKind.HISTOGRAMS
        }
    }

    /**
     * Re-index one sign's OTLP buckets, `offset + i` at a scale [shift] steps finer than
     * [histogramScale], into the sketch's own indices at [histogramScale].
     *
     * Dropping [shift] steps of scale merges each run of `2^shift` buckets into one, so OTLP index
     * `j` becomes `j >> shift` — the floor of `j / 2^shift`. It must be the **arithmetic** shift:
     * integer division truncates toward zero, which puts a negative odd index one bucket too high.
     * The sketch's index is the OTLP index plus one, the inverse of the OTLP render. The arithmetic
     * is in `Long` so an absurd offset cannot wrap; anything past the sketch's range is clamped by
     * [DDSketch.addBuckets].
     *
     * Merged buckets' counts are added checked, and `null` means they passed [Long.MAX_VALUE]:
     * counts that each fit can still add up past it (#3354).
     */
    private fun sketchBuckets(offset: Int, counts: List<Long>, shift: Int): Map<Int, Long>? {
        val out = LinkedHashMap<Int, Long>()
        counts.forEachIndexed { i, n ->
            if (n == 0L) return@forEachIndexed
            val index = ((offset.toLong() + i) shr shift) + 1L
            val clamped = index.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
            out[clamped] = (out[clamped] ?: 0L).plusOrNull(n) ?: return null
        }
        return out
    }

    /** Encode one kind's whole map. Called inside [lock]. */
    private fun encode(kind: StoredKind): ByteArray = when (kind) {
        StoredKind.SUMS -> encodeSums()
        StoredKind.SUMS_DOUBLE -> encodeSumsDouble()
        StoredKind.GAUGES -> encodeGauges()
        StoredKind.CARDINALITIES -> encodeCardinalities()
        StoredKind.HISTOGRAMS -> encodeHistograms()
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
     *
     * Returns a [MetricExportResult.Failure] whose cause is a [MetricSumOverflowException], having
     * applied and written nothing, when this replica's count for [key] cannot take [by] more
     * without passing [Long.MAX_VALUE].
     */
    public suspend fun incrementSum(key: MetricKey, by: Long = 1L): MetricExportResult =
        record(listOf(MetricUpdate.IncrementSum(key, by)))

    /**
     * Merge a remote [GCounter] snapshot into this exporter's sum for [key].
     *
     * Idempotent: merging the same snapshot twice produces the same result.
     * Returns [MetricExportResult.Success] after the durable write.
     */
    public suspend fun mergeSum(key: MetricKey, remote: GCounter): MetricExportResult =
        writeTurn(StoredKind.SUMS) {
            val encoded = lock.withLock {
                val current = sums[key] ?: GCounter.ZERO
                sums[key] = current.piece(remote)
                encodeSums()
            }
            persistSums(encoded, key)
        }

    /**
     * Read the current sum value for [key], or 0 if the key has never been incremented.
     *
     * @throws ArithmeticException if the replicas' counts for [key] add up past [Long.MAX_VALUE];
     *   [sumSnapshot] and [GCounter.valueOrNull] read it without throwing.
     */
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
        record(listOf(MetricUpdate.IncrementSumDouble(key, by)))

    /**
     * Merge a remote [GCounterDouble] snapshot into this exporter's double-sum for [key].
     *
     * Idempotent: merging the same snapshot twice produces the same result.
     * Returns [MetricExportResult.Success] after the durable write.
     */
    public suspend fun mergeSumDouble(key: MetricKey, remote: GCounterDouble): MetricExportResult =
        writeTurn(StoredKind.SUMS_DOUBLE) {
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
    ): MetricExportResult = record(listOf(MetricUpdate.SetGauge(key, value, timestamp)))

    /**
     * Merge a remote [LWWRegister] snapshot into this exporter's gauge for [key].
     *
     * Idempotent. Returns [MetricExportResult.Success] after the durable write.
     */
    public suspend fun mergeGauge(
        key: MetricKey,
        remote: LWWRegister<Double>,
    ): MetricExportResult = writeTurn(StoredKind.GAUGES) {
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
        record(listOf(MetricUpdate.AddCardinality(key, element)))

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
    ): MetricExportResult = writeTurn(StoredKind.CARDINALITIES) {
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
     * Returns a [MetricExportResult.Failure] whose cause is a [MetricHistogramOverflowException],
     * having applied and written nothing, when this replica's slot for [value] is already at
     * [Long.MAX_VALUE].
     *
     * @throws IllegalArgumentException if [value] is NaN or infinite.
     */
    public suspend fun recordHistogram(key: MetricKey, value: Double): MetricExportResult =
        record(listOf(MetricUpdate.RecordHistogram(key, value)))

    /**
     * Merge a remote [DDSketch] snapshot into this exporter's histogram for [key].
     *
     * Idempotent and lossless: the merged sketch equals the sketch of the combined
     * measurement stream. Returns [MetricExportResult.Success] after the durable write.
     *
     * @throws IllegalArgumentException if [remote]'s configuration — relative accuracy,
     *   `minIndexedValue` and `maxIndexedValue` — is not exactly [histogramPrototype]'s. Every
     *   series here holds that configuration, which is what lets [histogramScale] describe them
     *   all; a fresh key does not take a foreign one either.
     */
    public suspend fun mergeHistogram(key: MetricKey, remote: DDSketch): MetricExportResult {
        // Validation stays outside the mutex: it throws by contract, and a throwing caller has no
        // business holding the durable-write section.
        require(remote.hasPrototypeConfiguration()) {
            "mergeHistogram for ${key.name}: remote sketch ${remote.configuration()} does not match " +
                "histogramPrototype ${histogramPrototype.configuration()}"
        }
        return writeTurn(StoredKind.HISTOGRAMS) {
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
     *
     * @throws ArithmeticException if the series' total count does not fit in a `Long` (#3343).
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

    /**
     * The one durable write on this exporter — every mutating method funnels through here.
     *
     * **Reports a refused write once per outage, not once per attempt** (#2593): the next call
     * retries the write this one could not land, so a store that stays broken is one unchanging
     * condition and reporting it per call produced a line, and a stack trace, per metric write,
     * forever. The latch is [durableWriteOutage]; see it for what the population is and why [clear]
     * is not in it.
     *
     * The throwable is **kept** on the line. Unlike a refused sweep of superseded garbage, a refused
     * durable write is the store rejecting the application's own data, and at one line per outage
     * the trace is affordable. Every failure is returned to the caller as
     * [MetricExportResult.Failure] carrying the cause regardless, so deduplicating the *line* hides
     * nothing from a programmatic reader.
     */
    private suspend fun persist(storeKey: StoreKey, encoded: ByteArray, metricKey: MetricKey): MetricExportResult {
        val written = try {
            store.write(storeKey, encoded)
            Result.success(Unit)
        } catch (cause: Throwable) {
            // Only this coroutine's own cancellation propagates. One the store minted from its own
            // `withTimeout` is a refused write, so the caller gets a Failure it can retry (#3388).
            currentCoroutineContext().ensureActive()
            Result.failure(cause)
        }
        return written.fold(
            onSuccess = {
                persistence.acknowledged(setOf(storeKey))
                // The store took THIS key back. It says nothing about the others, so only this
                // one leaves the set; the outage stays open while any key is still refused.
                durableWriteOutage.update { it - storeKey }
                MetricExportResult.Success
            },
            onFailure = { cause ->
                persistence.failed(setOf(storeKey), cause)
                // One CAS decides and records: the caller that finds the set empty is the one
                // that opened the outage, and it is the only one that reports.
                val before = durableWriteOutage.getAndUpdate { it + storeKey }
                if (before.isEmpty()) {
                    logger.error(cause) {
                        "WarpMetricExporter: durable write failed for metric ${metricKey.name} (${metricKey.kind})"
                    }
                }
                MetricExportResult.Failure(cause)
            },
        )
    }

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

    /**
     * The durable write failed; whether the metric was persisted is indeterminate.
     *
     * Two exceptions mean nothing was counted. A [cause] that is a [MetricOverflowException]: the
     * call was refused before anything was applied, and retrying it is refused the same way. And
     * a call to a kind whose persisted series no read has joined yet, because the store refused the
     * read: its updates of that kind were not applied, and [cause] is the store's refusal of the read; see
     * [WarpMetricExporter.recover].
     */
    public data class Failure(public val cause: Throwable) : MetricExportResult
}

/**
 * Why a [WarpMetricExporter.record] was refused: an update would carry one of this replica's
 * counts for the series [key] past [Long.MAX_VALUE]. A wrapped count would be negative, and a
 * counter's merge, a max, would drop it, so the update would be lost everywhere with nothing to
 * say so. It arrives as the cause of a [MetricExportResult.Failure], and the batch that carried it
 * applied nothing.
 *
 * Which kind of series [key] names is the subclass: [MetricSumOverflowException] for a sum,
 * [MetricHistogramOverflowException] for a histogram. A sum and a histogram can share a
 * [MetricKey], so a caller leaving the series out of a retry needs both. Only the exporter
 * constructs one; a consumer only receives it.
 */
public sealed class MetricOverflowException(message: String) : ArithmeticException(message) {
    /** The series whose count cannot take the update. */
    public abstract val key: MetricKey
}

/**
 * A [MetricOverflowException] for a sum: an increment would carry this replica's count for the sum
 * series [key] past [Long.MAX_VALUE] (#3318).
 *
 * @property key the sum series that cannot take the increment.
 * @property count this replica's count for [key] before the refused increment, including the
 *   batch's own earlier increments to it.
 * @property by the refused increment.
 */
public class MetricSumOverflowException internal constructor(
    override val key: MetricKey,
    public val count: Long,
    public val by: Long,
) : MetricOverflowException(
    "sum series ${key.name} ${key.attributes}: this replica's count $count cannot take $by more " +
        "without passing Long.MAX_VALUE",
)

/**
 * A [MetricOverflowException] for a histogram (#3354): a [MetricUpdate.RecordHistogram] or
 * [MetricUpdate.AddExponentialHistogram] would carry this replica's slot in one of the series'
 * buckets, or in its zero or overflow counter, past [Long.MAX_VALUE], counting the batch's own
 * earlier updates to it. So would counts that each fit but fold onto one bucket past it.
 *
 * @property key the histogram series that cannot take the update.
 */
public class MetricHistogramOverflowException internal constructor(
    override val key: MetricKey,
) : MetricOverflowException(
    "histogram series ${key.name} ${key.attributes}: this replica's count in one of its buckets, or in its " +
        "zero or overflow counter, cannot take this batch's values without passing Long.MAX_VALUE",
)
