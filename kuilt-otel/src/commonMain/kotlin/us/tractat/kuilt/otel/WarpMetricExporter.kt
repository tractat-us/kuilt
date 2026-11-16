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
import us.tractat.kuilt.crdt.PNCounter
import us.tractat.kuilt.crdt.PNCounterDouble
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.Quilted
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.math.minusOrNull
import us.tractat.kuilt.math.plusOrNull
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.store.StoreKey
import kotlin.math.abs

private val logger = KotlinLogging.logger("us.tractat.kuilt.otel.WarpMetricExporter")

/**
 * A CRDT-backed metric exporter for cumulative sums, gauges, cardinality estimates, exponential
 * histograms and up-down sums.
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
 * - **Up-down sum** — a [PNCounter] per [MetricKey], or a [PNCounterDouble] for fractions: a level
 *   that goes both ways and adds up across replicas, such as in-flight requests across a fleet
 *   (#3307). Each replica sets **its own share** to the level it reports ([setUpDownSum]), so the
 *   series reads the sum of every replica's level, the same level set again writes nothing, and a
 *   restart that keeps [replica] and the store counts nothing twice. Only a slot's owner moves it,
 *   so a replica that never returns keeps its last share in the sum.
 *
 *   **A slot that starts over misleads every peer that already saw it.** An eviction, a [clear] or
 *   a [MetricUpdate.Forget] drops the series here, and the next set starts this replica's slot
 *   again from nothing. Here the value is right at once. A peer that merged the old slot keeps the
 *   larger of each half, so it reads a stale share: one that saw `(10, 0)` and then merges
 *   `(4, 2)` reads 8, not 2, and stays wrong until this replica's increments pass 10. Nothing
 *   here repairs that peer, and a tap puller never sends anything back.
 *
 * ## Key inversion
 *
 * Every mutating method — [incrementSum], [incrementSumDouble], [setGauge], [addCardinality],
 * [recordHistogram], [setUpDownSum] and its siblings, their `merge*` twins, the batch form [record], and
 * [clear] — reports success
 * only once the updated CRDT is **durably written to [DurableStore]**, never when it is delivered to
 * any backend. Delivery is asynchronous and eventually consistent.
 *
 * The two families return different types, because retrying them means different things:
 *
 * - [record] and the single-point mutators return a [MetricExportResult]. Counting an update twice
 *   is wrong, so the result says which updates a retry may send. [MetricExportResult.Refused] is
 *   an overflow: nothing was counted, and the same call may be refused again until that series is
 *   cleared or evicted.
 *   [MetricExportResult.Partial] lists in `uncounted` exactly the updates of a kind whose read the
 *   store refused (#3390), so recording that list again counts each of them once. Every other
 *   update of a Partial was counted, in memory, and is not yet on disk where its write was
 *   refused; the next successful write of that kind persists it, so retrying it counts it twice.
 * - The `merge*` methods and [clear] return a [MetricWriteResult]. A merge is a lattice join and a
 *   clear is a delete, so calling either again is always safe and the result needs no more than
 *   [MetricWriteResult.Failure]'s cause. A merge over a kind whose read is refused merges nothing,
 *   and its cause is a [MetricKindUnreadException].
 *
 * A kind is read by [recover], or by its own first turn on an exporter that skipped [recover], and
 * again by its turns while the store refuses, on a window that backs off per kind. See [recover].
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
 * emitted only by the failure that makes that set non-empty. All seven stored kinds therefore share
 * one outage, and — the part a boolean latch gets wrong — a store that refuses one key while
 * accepting another stays a single outage rather than re-reporting on every alternation. The next
 * line comes only once the store has taken back every key it was refusing. Every failure is still
 * returned to the caller, carrying the cause, so nothing is hidden
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
 * @param replica Stable unique identity for this device/process (use a UUID). Keep it, with [store],
 *   across restarts: an up-down sum's share lives in this replica's slot, so a process that comes
 *   back under a new id leaves its old share in the sum for good.
 * @param store Durable persistence backend. [InMemoryDurableStore] in tests.
 * @param maxMetrics Maximum number of distinct [MetricKey]s across all kinds; at least 1.
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
        // Below one, a cap cannot be met the same way twice: a recover evicts to nothing, while a
        // write of a new series finds nothing to evict for it and keeps it.
        require(maxMetrics >= 1) { "maxMetrics must be at least 1, was $maxMetrics" }
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
    //  - `lock` (atomicfu reentrant) guards the seven CRDT maps only. No suspend call is ever made
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
    private val upDownSums: LinkedHashMap<MetricKey, PNCounter> = LinkedHashMap()
    private val upDownSumsDouble: LinkedHashMap<MetricKey, PNCounterDouble> = LinkedHashMap()

    /** The sum series a [record] has already been refused for; read only inside [lock] (#3318). */
    private val refusedSums = FirstSightings()

    /** The histogram series a [record] has already been refused for; read only inside [lock] (#3354). */
    private val refusedHistograms = FirstSightings()

    /**
     * The integer up-down sum series a [record] has already been refused for; read only inside
     * [lock] (#3307). Apart from [refusedUpDownSumsDouble] because one key names a series in both
     * maps, and each series' first refusal is news.
     */
    private val refusedUpDownSums = FirstSightings()

    /** The fractional twin of [refusedUpDownSums]. */
    private val refusedUpDownSumsDouble = FirstSightings()

    /**
     * Whether a durable-write outage is currently **open** — i.e. a refused store write has already
     * been reported and the store has not taken a write since. Written only by [persist].
     *
     * A key in it is also a kind whose memory is **ahead of its disk**: its last write was refused,
     * and only a write of it lands what that write carried. So [record] reads it too, and writes
     * such a kind again even for an update that moved nothing, such as an up-down level reported
     * again (#3307). Without that, a steady level never retried, its share never reached disk, and
     * the key stayed here for good, so the next outage on any kind was never reported. One set
     * serves both, rather than a second set of kinds ahead of disk, because the two are the same
     * fact: added by a refused write of the key, removed by a landed one, and only [persist] sees
     * either. A second copy would be a second place for that fact to drift.
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
     * across all seven stored kinds and all seven store keys, performs its durable write, and the
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
     * The set is bounded by the seven store keys, so it cannot grow.
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
        private val SUM_STORE_KEY = StoredKind.SUMS.storeKey
        private val SUM_DOUBLE_STORE_KEY = StoredKind.SUMS_DOUBLE.storeKey
        private val GAUGE_STORE_KEY = StoredKind.GAUGES.storeKey
        private val CARDINALITY_STORE_KEY = StoredKind.CARDINALITIES.storeKey
        private val HISTOGRAM_STORE_KEY = StoredKind.HISTOGRAMS.storeKey
        private val UP_DOWN_SUM_STORE_KEY = StoredKind.UP_DOWN_SUMS.storeKey
        private val UP_DOWN_SUM_DOUBLE_STORE_KEY = StoredKind.UP_DOWN_SUMS_DOUBLE.storeKey

        private val cbor = Cbor { alwaysUseByteString = true }

        private val metricKeySerializer = MetricKey.serializer()
        private val gcounterSerializer = GCounter.serializer()
        private val gcounterDoubleSerializer = GCounterDouble.serializer()
        private val lwwSerializer = LWWRegister.serializer(Double.serializer())
        private val hllSerializer = HyperLogLog.serializer()
        private val ddSketchSerializer = DDSketch.serializer()
        private val pnCounterSerializer = PNCounter.serializer()
        private val pnCounterDoubleSerializer = PNCounterDouble.serializer()

        private val sumsSerializer = MapSerializer(metricKeySerializer, gcounterSerializer)
        private val sumsDoubleSerializer = MapSerializer(metricKeySerializer, gcounterDoubleSerializer)
        private val gaugesSerializer = MapSerializer(metricKeySerializer, lwwSerializer)
        private val cardinalitiesSerializer = MapSerializer(metricKeySerializer, hllSerializer)
        private val histogramsSerializer = MapSerializer(metricKeySerializer, ddSketchSerializer)
        private val upDownSumsSerializer = MapSerializer(metricKeySerializer, pnCounterSerializer)
        private val upDownSumsDoubleSerializer = MapSerializer(metricKeySerializer, pnCounterDoubleSerializer)
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
         * The read was [KindRead.Refused]. A turn of this kind reads again first, or skips the read
         * inside the kind's backoff window. [first] is whether this refusal is the kind's first since
         * a read last landed, the one that is logged.
         */
        class Owed(val cause: Throwable, val first: Boolean) : PersistedRead
    }

    /** Why a kind still owes a read of its persisted series. */
    private sealed interface OwedRead {
        /** Nothing has read it yet: the exporter was just built, and [recover] has not run. */
        data object Unread : OwedRead

        /**
         * A read of it was refused, and no read has landed since. The kind's turns skip the read
         * while [backoff] has turns left, and each one spent is a [skipped] window (#3436).
         */
        class Refused(val backoff: RefusedReadBackoff) : OwedRead {
            fun skipped(): Refused = Refused(backoff.skipped())
        }
    }

    /**
     * The kinds whose persisted series no read has yet joined into memory; a kind absent here is
     * [PersistedRead.Settled]. **Every kind starts owed**, as [OwedRead.Unread]: an exporter that
     * never calls [recover] holds the same fresh map a refused read leaves, so its first write of a
     * kind would replace every series a previous process made durable (#3390). Its first turn of
     * each kind reads first instead, and joins what it finds, exactly as after a refused read.
     *
     * Changed only by [settle], by [clear], and by [refusedReads] spending a turn of a backoff
     * window, and read only by [refusedReads] and [settle]. All of them hold [writeMutex], as every write turn does, and that is what keeps a turn from
     * seeing a kind settled whose series are not yet in memory: no turn runs between a read and
     * the [settle] that installs it. [lock] only makes the field itself safe to touch.
     *
     * A [clear] empties it whether or not its deletes land: once memory is empty, what is on disk
     * is unwanted, not unseen, and a read would bring back the series the clear dropped.
     *
     * **Why a turn of an owed kind admits nothing, rather than applying in memory and skipping only
     * the write.** Five of the seven kinds count per replica — [GCounter], [GCounterDouble], [DDSketch]'s bucket
     * cells and the two up-down counters' halves — and their join is the per-replica **maximum**. An update applied
     * to the fresh map a refused read left would start this replica's slot again from zero beside
     * the durable one: a second history of the same slot. The heal's join keeps the larger of the
     * two and silently drops the other (a durable `5` and an unread-time `+10` join to `10`, not
     * `15`). Admitting nothing keeps memory on the store's own history, so the join on heal is
     * exact, and it is idempotent, so a repeated read cannot double-count either.
     */
    private var owedReads: Map<StoredKind, OwedRead> = StoredKind.entries.associateWith { OwedRead.Unread }

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
     * own `withTimeout` included, costs its kind alone and **fails closed** (#3390): a later call
     * that would write that kind, [record] or a `merge*` method, reads it again first, or, inside
     * the kind's backoff window below, refuses it without reading, and while the read is still
     * refused or skipped admits **nothing** of that kind and writes nothing of it. A
     * [record] returns a [MetricExportResult.Partial] listing those updates as `uncounted`, and a
     * `merge*` a [MetricWriteResult.Failure]; either carries a [MetricKindUnreadException] for the
     * kind. Updates of the other kinds in the same batch go ahead. The read that lands joins the
     * persisted series into memory and that turn runs. Until then the readers see what memory
     * already held of the kind: nothing, unless an earlier read of it in this process landed, in
     * which case memory keeps that read's series and the refusal changes none of them. Starting
     * fresh here instead wrote a map naming only this run's series over the persisted one: a
     * durable sum of `5` read back as `1`.
     *
     * The re-reads back off **per kind**, counted in the kind's own turns: after `k` refused reads of
     * a kind in a row, the next `2^k − 1` turns that touch the kind, `k` capped at 6 so at most 63,
     * refuse it as a refused read does, without touching the store (#3436). A turn that does not
     * touch the kind spends nothing of its window. A batch refused for an overflow spends one as
     * well, since it would have read the kind; it still counts no turn on `health`. A refused
     * histograms read backs off the histograms alone. A long outage of a kind then costs one store read per 64 of its turns rather
     * than one per turn, each paying the store's timeout. This call is an explicit request, so it
     * **always reads every kind**, window or not; a read of a kind that lands, here or in a turn,
     * closes that kind's window and resets its `k`, and one refused here counts toward `k` like any
     * other.
     *
     * Serialized with every write turn by the same mutex, so it may run concurrently with them.
     * A cancellation of the caller's own job still propagates (#3388).
     */
    public suspend fun recover(): Unit = writeMutex.withLock {
        StoredKind.entries.forEach { kind ->
            val read = readKind(kind)
            if (read is PersistedRead.Owed) {
                logRefusal(kind, read)
            }
        }
    }

    /**
     * Log a refused read of [kind]: at ERROR the first time, at DEBUG after that, as a store that
     * stays unreadable is refused again on its window and would otherwise log a stack
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
        StoredKind.UP_DOWN_SUMS -> settle(kind, read(kind, upDownSumsSerializer), upDownSums)
        StoredKind.UP_DOWN_SUMS_DOUBLE -> settle(kind, read(kind, upDownSumsDoubleSerializer), upDownSumsDouble)
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
     * Must hold [writeMutex], and that is what orders the join before any turn of [kind]: every
     * turn holds it too, so none can see the kind settled before its series are joined. The join
     * and the change to [owedReads] share one [lock] section, but nothing that holds only [lock]
     * reads [owedReads], so the shared section is tidiness, not the guarantee. It is a join rather
     * than a replace so that a second [recover] keeps what memory holds ahead of the store, and the
     * series it adds are held to [maxMetrics] like any other.
     */
    private fun <V : Quilted<V>> settle(
        kind: StoredKind,
        read: KindRead<V>,
        series: LinkedHashMap<MetricKey, V>,
    ): PersistedRead {
        if (read is KindRead.Refused) persistence.recoveryFailed(read.cause)
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
                // One more refusal in a row, so a longer window; see RefusedReadBackoff.after.
                val prior = (owedReads[kind] as? OwedRead.Refused)?.backoff
                owedReads = owedReads + (kind to OwedRead.Refused(RefusedReadBackoff.after(prior, read.cause)))
                PersistedRead.Owed(read.cause, first = prior == null)
            } else {
                // Any read of the kind that lands closes its window and resets its count.
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
     * still refused, in that order. A kind inside its backoff window is not read: it is returned as
     * refused, with the last refusal's cause, and one turn of its window is spent. Must hold
     * [writeMutex].
     *
     * A turn of a kind returned here admits nothing of it and writes nothing of it, read or
     * skipped. The caller counts it as a failed turn of that kind on [health], through
     * [countRefused], once it knows the turn is a turn: a [record] refused for an overflow applies
     * nothing and is no turn of any kind. Only the kind's first refusal is logged as an error,
     * whether [recover] or a turn met it; see [logRefusal]. A skip logs at debug.
     *
     * Only a turn that would have read a kind spends its window: the kinds outside [kinds] are not
     * touched. The skips are spent last, after every read this turn makes, and only once
     * `ensureActive` has passed. A skip makes no store call, so without that check it would not
     * notice its caller's own cancellation; and spent first, it would be lost to a cancellation that
     * ends a later read of the same turn. A cancelled caller is cancelled here as a read's
     * `ensureActive` cancels it, and spends no turn of any kind's window.
     */
    private suspend fun refusedReads(kinds: Set<StoredKind>): Map<StoredKind, MetricKindUnreadException> {
        val owed = lock.withLock { owedReads }.filterKeys { it in kinds }
        if (owed.isEmpty()) return emptyMap()
        val skips = owed.entries.mapNotNull { (kind, why) ->
            (why as? OwedRead.Refused)?.takeIf { it.backoff.skipTurns > 0 }?.let { kind to it }
        }.toMap()
        val refused = HashMap<StoredKind, MetricKindUnreadException>()
        StoredKind.entries.filter { it in owed && it !in skips }.forEach { kind ->
            when (val read = readKind(kind)) {
                is PersistedRead.Owed -> {
                    refused[kind] = MetricKindUnreadException(kind.storeKey, read.cause)
                    logRefusal(kind, read)
                }
                PersistedRead.Settled -> logger.debug { "${kind.storeKey.name}: persisted series read; writes of the kind go ahead" }
            }
        }
        if (skips.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            lock.withLock {
                // writeMutex is the guarantee: every caller of refusedReads and every writer of
                // owedReads holds it, so nothing replaced a window since it was read above. The check
                // fails loudly if that ever stops being true, rather than spending a window it did not read.
                skips.forEach { (kind, seen) ->
                    check(owedReads[kind] === seen) { "a kind's read window was replaced outside writeMutex" }
                    owedReads = owedReads + (kind to seen.skipped())
                }
            }
            skips.forEach { (kind, seen) ->
                refused[kind] = MetricKindUnreadException(kind.storeKey, seen.backoff.cause)
                logger.debug {
                    "${kind.storeKey.name}: persisted series was refused; this turn skips the read and admits " +
                        "and writes nothing of the kind: ${seen.backoff.cause}"
                }
            }
        }
        return StoredKind.entries.mapNotNull { kind -> refused[kind]?.let { kind to it } }.toMap(LinkedHashMap())
    }

    /** Count each turn [refusedReads] refused as a failed turn of its kind, with the store's cause. */
    private fun countRefused(refused: Map<StoredKind, MetricKindUnreadException>) {
        refused.forEach { (kind, unread) -> persistence.failed(setOf(kind.storeKey), unread.cause) }
    }

    /**
     * Run [turn] under [writeMutex] once [kind]'s persisted series has been read; while that read is
     * still refused, or skipped inside the kind's backoff window, return a
     * [MetricWriteResult.Failure] without running [turn]. The `merge*`
     * methods' turn; [record] reads per kind itself, since a batch spans kinds.
     */
    private suspend inline fun writeTurn(kind: StoredKind, turn: () -> MetricWriteResult): MetricWriteResult =
        writeMutex.withLock {
            val refused = refusedReads(setOf(kind))
            countRefused(refused)
            refused[kind]?.let { MetricWriteResult.Failure(it) } ?: turn()
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
     * Drop every metric series this exporter holds and delete its seven persisted keys (#2208).
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
     * **Never throws.** A refused delete returns [MetricWriteResult.Failure]; the in-memory
     * maps are cleared either way, so a retry converges. No kind owes a read after a clear, so a
     * kind whose delete was refused is not read back by its own next write, which replaces what
     * the delete left and so finishes the clear for that kind. Until that write lands the dropped
     * series are still on disk, though: a [recover] called before it reads them back into memory,
     * where the kind's next write persists them again, and so does the first [recover] after a
     * restart. Retrying the clear until it returns [MetricWriteResult.Success] is what removes them.
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
    public suspend fun clear(): MetricWriteResult = writeMutex.withLock {
        lock.withLock {
            sums.clear()
            sumsDouble.clear()
            gauges.clear()
            cardinalities.clear()
            histograms.clear()
            upDownSums.clear()
            upDownSumsDouble.clear()
            // Whatever is on disk now is unwanted, not unseen, so no kind owes a read of it, even
            // if a delete below is refused: a read would bring back the series this call drops.
            owedReads = emptyMap()
        }
        val deleted = try {
            store.delete(SUM_STORE_KEY)
            store.delete(SUM_DOUBLE_STORE_KEY)
            store.delete(GAUGE_STORE_KEY)
            store.delete(CARDINALITY_STORE_KEY)
            store.delete(HISTOGRAM_STORE_KEY)
            store.delete(UP_DOWN_SUM_STORE_KEY)
            store.delete(UP_DOWN_SUM_DOUBLE_STORE_KEY)
            Result.success(Unit)
        } catch (cause: Throwable) {
            // `clear` never throws: a cancellation the store minted is a refused delete, and only
            // this coroutine's own cancellation propagates (#3388).
            currentCoroutineContext().ensureActive()
            Result.failure(cause)
        }
        deleted.fold(
            onSuccess = {
                persistence.acknowledged(CLEAR_WORK)
                MetricWriteResult.Success
            },
            onFailure = { cause ->
                persistence.failed(CLEAR_WORK, cause)
                logger.error(cause) { "otel.metrics: durable delete failed during clear" }
                MetricWriteResult.Failure(cause)
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
     * - A kind whose persisted series no read has joined yet — [recover] was refused, or never
     *   called — is read first, or, inside the kind's backoff window (see [recover]), refused
     *   without a read. While that read is refused or skipped, the batch's updates **of that kind**
     *   are not counted: not applied, and the kind's write is never attempted. The other kinds go
     *   ahead. The result is a [MetricExportResult.Partial] whose `uncounted` is exactly those
     *   updates, in batch order, so recording that list again counts each of them once; its
     *   `causes` holds a [MetricKindUnreadException] per unread kind.
     * - Every write of a kind that was read is **attempted**, even after one is refused. Stopping at
     *   the first refusal would leave the later kinds off disk, which applying the updates one by
     *   one never does. A refused write makes the result a [MetricExportResult.Partial] too, with
     *   the store's refusal among its `causes`, after the unread kinds' and in the fixed kind order
     *   sums, double sums, gauges, cardinalities, histograms, up-down sums, double up-down sums.
     * - A refused write means "not yet durable", not "not counted": its updates stay in memory, are
     *   not in `uncounted`, and the next successful write of a refused kind persists them. So the
     *   batch is **not atomic** on disk — a kind the store took is durable even when another kind
     *   was refused — and retrying an update of a refused write counts it twice, exactly as
     *   retrying an [incrementSum] whose write was refused does.
     * - On [health] each kind's write is its own turn: a batch counts one turn per kind it
     *   touched, and a partial refusal moves both `acknowledged` and `failed`. An unread kind's
     *   turn counts as failed. A refused batch is no turn of any kind and moves nothing.
     *
     * In memory the exporter ends where applying [updates] one by one through [incrementSum],
     * [incrementSumDouble], [setGauge], [addCardinality], [recordHistogram] and the up-down methods —
     * or, for a [MetricUpdate.AddExponentialHistogram] or a [MetricUpdate.Forget], through a batch of
     * that one update — would leave it,
     * eviction included: the [maxMetrics] cap is decided per update, in order. On disk, a touched
     * kind holds its state as of the **end** of the batch. The one place that differs from the
     * single-point sequence is a kind whose series a *later* update of another kind evicted: written
     * one by one, the evicted series lingers on disk until that kind's next write; here it is
     * already gone. A kind the batch never touched is not rewritten, as before.
     * An update that changes nothing — an up-down level set to the share it already holds, or a
     * [MetricUpdate.Forget] of a series the exporter does not hold — touches no kind, so a batch of
     * only those writes nothing and counts no turn. The exception is a kind whose last write the
     * store refused: memory holds what that write carried and disk does not, so such an update
     * writes the kind again, and that is a turn of it.
     *
     * An empty [updates] is [MetricExportResult.Success] and touches neither memory nor the store.
     * Each [MetricUpdate] validates its own arguments at construction. Before **any** update is
     * applied, the batch is checked twice more, in this order:
     *
     * 1. What only this exporter's configuration can judge — a [MetricUpdate.AddExponentialHistogram]
     *    coarser than [histogramScale], or one whose count lists the caller has since made negative —
     *    is checked for **every** update and **thrown** as [IllegalArgumentException], since it is
     *    a caller's mistake. Nothing in memory or on disk changes.
     * 2. Whether an update would carry a sum, histogram or up-down sum series past what it holds is
     *    checked for the whole batch, counting its earlier updates to the same series, against the
     *    series as they stand. For a [MetricUpdate.IncrementSum] that is its sum series' total —
     *    every replica's slot, a peer's merged in included — which is the value the OTLP bridge
     *    exports (#3445); this replica's slot is part of it (#3318). For a [MetricUpdate.RecordHistogram] or
     *    [MetricUpdate.AddExponentialHistogram] it is the series' total count — summed over every
     *    replica's slots, a peer's merged in included, across both signs' buckets and the zero
     *    counter — which is the count the OTLP bridge exports (#3403); and with it this replica's
     *    slot in each bucket the update touches, in the zero and overflow counters, and the sum of
     *    counts that fold onto one bucket (#3354). A weighted count can get there on its own. An
     *    up-down update is checked by making it, as for a histogram: refused when it would move one of
     *    this replica's slots past what it holds, or leave an integer series' total over every
     *    replica outside a `Long` (#3307). Such a batch is
     *    **refused**: it returns a [MetricExportResult.Refused] whose `overflow` names the series,
     *    a [MetricSumOverflowException], [MetricHistogramOverflowException] or
     *    [MetricUpDownSumOverflowException], and nothing from it
     *    is applied or written, the kinds that were read included. The refusal is logged at WARN the
     *    first time a series causes one and at DEBUG after that.
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
            val unread = refusedReads(updates.flatMapTo(HashSet()) { it.storedKinds })
            val admitted = if (unread.isEmpty()) updates else updates.filter { it.isAdmittedPast(unread) }
            var overflow: MetricOverflowException? = null
            var firstRefusal = false
            val writes = lock.withLock {
                overflow = firstOverflowLocked(admitted)?.also { refused ->
                    firstRefusal = when (refused) {
                        is MetricSumOverflowException -> refusedSums.firstSighting(refused.key, sums.keys)
                        is MetricHistogramOverflowException -> refusedHistograms.firstSighting(refused.key, histograms.keys)
                        is MetricUpDownSumOverflowException ->
                            if (refused.fractional) {
                                refusedUpDownSumsDouble.firstSighting(refused.key, upDownSumsDouble.keys)
                            } else {
                                refusedUpDownSums.firstSighting(refused.key, upDownSums.keys)
                            }
                    }
                }
                if (overflow != null) return@withLock emptyList()
                // The last series each kind's updates named — only for the failure log line. An
                // update that changed nothing, a level set again or a forget of an absent series,
                // touches no kind, so it costs no write — unless the kind's last write was refused:
                // then memory is ahead of disk, and only a write puts it there. A steady level
                // otherwise never retries, and the refused key holds the outage open for good.
                // writeMutex orders this read after every persist that changed the set.
                val aheadOfDisk = durableWriteOutage.value
                val touched = LinkedHashMap<StoredKind, MetricKey>()
                admitted.forEach { update ->
                    val changed = applyLocked(update)
                    update.storedKinds
                        .filter { kind -> kind in changed || kind.storeKey in aheadOfDisk }
                        .forEach { kind -> touched[kind] = update.key }
                }
                StoredKind.entries.mapNotNull { kind ->
                    touched[kind]?.let { lastKey -> PendingWrite(kind, encode(kind), lastKey) }
                }
            }
            overflow?.let { refused ->
                // WARN once per series: a saturated delta series is refused again on every export.
                val line = { "WarpMetricExporter: batch of ${updates.size} refused, nothing applied: ${refused.message}" }
                if (firstRefusal) logger.warn(line) else logger.debug(line)
                return@withLock MetricExportResult.Refused(refused)
            }
            // Past the overflow check, so this is a turn of every kind the batch touched.
            countRefused(unread)
            val notDurable = writes.mapNotNull { write -> persist(write.kind.storeKey, write.encoded, write.lastKey) }
            val causes = unread.values + notDurable
            if (causes.isEmpty()) {
                MetricExportResult.Success
            } else {
                // Only the unread kinds' updates: every other update was applied, so a retry of it
                // would count it twice.
                MetricExportResult.Partial(updates.filterNot { it.isAdmittedPast(unread) }, causes)
            }
        }
    }

    /** One kind's encoded map, ready to write outside [lock]. */
    private class PendingWrite(val kind: StoredKind, val encoded: ByteArray, val lastKey: MetricKey)

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
            is MetricUpdate.AddUpDownSum,
            is MetricUpdate.AddUpDownSumDouble,
            is MetricUpdate.SetUpDownSum,
            is MetricUpdate.SetUpDownSumDouble,
            is MetricUpdate.Forget,
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
     * The refusal for the first update in [updates] that would carry a sum or histogram series'
     * total over every replica past [Long.MAX_VALUE], counting the batch's own earlier updates to the
     * same series, or `null` when every one fits. Called inside [lock], before anything is applied.
     *
     * A [MetricUpdate.IncrementSum] is checked against its series' total, read with
     * [GCounter.valueOrNull] (#3445): the exact sum over every replica's slot, a peer's merged in
     * included, which is the value the OTLP bridge exports and the one it skips the series for when
     * it does not fit. The batch's earlier increments are then added to it checked, so no
     * intermediate wraps. This replica's slot is part of that total, because slots are never
     * negative, so a slot that cannot take an increment (#3318) is one the total cannot take either,
     * and [GCounter.inc] in [applyLocked] cannot throw for a batch this accepts.
     * A histogram update is checked by making it, through [DDSketch.addOrNull] and
     * [DDSketch.addBucketsOrNull], against a running copy of its series that the batch's earlier
     * histogram updates have already been made to (#3354). Those are the very calls [applyLocked]
     * makes, so the check cannot disagree with the apply about what fits, and an
     * [MetricUpdate.AddExponentialHistogram] whose own counts fold past [Long.MAX_VALUE] is caught
     * here too. The running copy's total is then read with [DDSketch.countOrNull] (#3403): the
     * exact sum over every replica — a peer's slots merged in included — of every bucket and the
     * zero counter, which is the `count` the OTLP bridge exports and the one it skips the series
     * for when it does not fit. Every slot is part of that total, because slots are never negative,
     * so a slot that cannot take an
     * update is one the total cannot take either, and a batch is refused exactly when the bridge
     * could not export the series it would leave.
     *
     * Checked against the series as they stand before the batch. An eviction the batch triggers can
     * only remove a series, never raise a count, so this never accepts a batch [applyLocked] would
     * refuse part-way; at worst it refuses one that would just fit after such an eviction.
     * An up-down series an eviction or a [MetricUpdate.Forget] resets starts again from zero, where
     * any update this check accepts fits: a share of zero moved to any level but [Long.MIN_VALUE], which
     * no slot can reach from any state, holds it.
     */
    private fun firstOverflowLocked(updates: List<MetricUpdate>): MetricOverflowException? {
        val runningSums = HashMap<MetricKey, Long>()
        val runningHistograms = HashMap<MetricKey, DDSketch>()
        val runningUpDownSums = HashMap<MetricKey, PNCounter>()
        val runningUpDownSumsDouble = HashMap<MetricKey, PNCounterDouble>()
        for (update in updates) {
            when (update) {
                is MetricUpdate.AddUpDownSum,
                is MetricUpdate.SetUpDownSum,
                -> {
                    val current = runningUpDownSums[update.key] ?: upDownSums[update.key] ?: PNCounter.ZERO
                    runningUpDownSums[update.key] = upDownSumNextOrNull(current, update)
                        ?: return MetricUpDownSumOverflowException(update.key, fractional = false)
                }
                is MetricUpdate.AddUpDownSumDouble,
                is MetricUpdate.SetUpDownSumDouble,
                -> {
                    val current = runningUpDownSumsDouble[update.key] ?: upDownSumsDouble[update.key] ?: PNCounterDouble.ZERO
                    runningUpDownSumsDouble[update.key] = upDownSumDoubleNextOrNull(current, update)
                        ?: return MetricUpDownSumOverflowException(update.key, fractional = true)
                }
                // A later update to a forgotten series starts it again from nothing, as applyLocked will.
                is MetricUpdate.Forget -> update.storedKinds.forEach { kind ->
                    when (kind) {
                        StoredKind.SUMS -> runningSums[update.key] = 0L
                        StoredKind.HISTOGRAMS -> runningHistograms[update.key] = histogramPrototype
                        StoredKind.UP_DOWN_SUMS -> runningUpDownSums[update.key] = PNCounter.ZERO
                        StoredKind.UP_DOWN_SUMS_DOUBLE -> runningUpDownSumsDouble[update.key] = PNCounterDouble.ZERO
                        StoredKind.SUMS_DOUBLE, StoredKind.GAUGES, StoredKind.CARDINALITIES -> Unit
                    }
                }
                is MetricUpdate.IncrementSum -> {
                    // The total the bridge exports as the point's value: every replica's slot, summed
                    // exactly. A slot that fits can still carry it past Long with a peer's merged in (#3445).
                    val total = runningSums[update.key] ?: (sums[update.key] ?: GCounter.ZERO).valueOrNull()
                        ?: return MetricSumOverflowException(update.key, total = null, by = update.by)
                    runningSums[update.key] = total.plusOrNull(update.by)
                        ?: return MetricSumOverflowException(update.key, total, update.by)
                }
                is MetricUpdate.RecordHistogram,
                is MetricUpdate.AddExponentialHistogram,
                -> {
                    val current = runningHistograms[update.key] ?: histograms[update.key] ?: histogramPrototype
                    val patch = histogramPatchOrNull(current, update)
                        ?: return MetricHistogramOverflowException(update.key)
                    val next = current.piece(patch.delta)
                    // The total the bridge exports as the point's count: every replica's slots, both
                    // signs and the zeros, summed exactly. Slots that each fit can add up past it (#3403).
                    next.countOrNull() ?: return MetricHistogramOverflowException(update.key)
                    runningHistograms[update.key] = next
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

    /**
     * The counter [update] leaves the up-down sum [current] at, [current] itself when it moves
     * nothing, or `null` when it cannot be applied: it would carry one of this replica's slots past
     * [Long.MAX_VALUE], or leave a total over every replica that does not fit in a `Long`, which the
     * OTLP bridge could not export. The one place an integer up-down update is worked out, so
     * [firstOverflowLocked]'s check and [applyLocked] cannot drift apart.
     *
     * A [MetricUpdate.SetUpDownSum] moves this replica's own slot by `level − count(replica)`, its
     * share, so the series' value is the sum of every replica's level and the same level set twice
     * moves nothing the second time.
     */
    private fun upDownSumNextOrNull(current: PNCounter, update: MetricUpdate): PNCounter? {
        val move = when (update) {
            is MetricUpdate.AddUpDownSum -> update.by
            is MetricUpdate.SetUpDownSum -> update.level.minusOrNull(current.count(replica)) ?: return null
            else -> error("not an integer up-down update: $update")
        }
        if (move == 0L) return current
        // A move past what one slot holds cannot be made. Long.MIN_VALUE is the one move whose
        // magnitude does not fit, and its slot could not take it either.
        if (move == Long.MIN_VALUE) return null
        // Nothing in here suspends, and ArithmeticException is not a CancellationException, so this
        // narrow catch cannot swallow a cancellation.
        val next = try {
            current.piece((if (move > 0L) current.increment(replica, move) else current.decrement(replica, -move)).delta)
        } catch (_: ArithmeticException) {
            return null
        }
        return next.takeIf { it.valueOrNull() != null }
    }

    /**
     * The fractional twin of [upDownSumNextOrNull]: `null` only when the move is not finite or would
     * take one of this replica's slots past [Double.MAX_VALUE]. A total over every replica that rounds
     * to infinity is still exported, as OTLP/JSON's string form of a non-finite double.
     *
     * A share is a difference of two growing totals, so a set can land a rounding step off its level.
     * A [MetricUpdate.SetUpDownSumDouble] that would bring the share no closer to the level than it
     * already is moves nothing. Each write that lands brings it strictly closer, and a distance
     * between doubles cannot fall forever, so a level reported again stops writing, rather than
     * rewriting the series every collection, which a plain `level − share` move can do. How many
     * writes that takes is not proven: an unrecorded random search saw at most three, and
     * `aRepeatedDoubleLevelSettlesForEveryLevel` holds 200 levels to three.
     */
    private fun upDownSumDoubleNextOrNull(current: PNCounterDouble, update: MetricUpdate): PNCounterDouble? {
        val share = current.count(replica)
        val move = when (update) {
            is MetricUpdate.AddUpDownSumDouble -> update.by
            is MetricUpdate.SetUpDownSumDouble -> update.level - share
            else -> error("not a fractional up-down update: $update")
        }
        if (move == 0.0) return current
        if (!move.isFinite()) return null
        // Nothing in here suspends; see upDownSumNextOrNull.
        val next = try {
            current.piece((if (move > 0.0) current.increment(replica, move) else current.decrement(replica, -move)).delta)
        } catch (_: ArithmeticException) {
            return null
        }
        if (update is MetricUpdate.SetUpDownSumDouble &&
            abs(update.level - next.count(replica)) >= abs(update.level - share)
        ) {
            return current
        }
        return next
    }

    /**
     * Apply one update to its map, evicting first if it opens a new series, and return the kinds it
     * changed: none for an update that moved nothing, so its batch writes nothing for it. Called
     * inside [lock].
     */
    private fun applyLocked(update: MetricUpdate): Set<StoredKind> = when (update) {
        is MetricUpdate.IncrementSum -> {
            maybeEvictForNewKey(update.key, sums)
            val current = sums.getOrPut(update.key) { GCounter.ZERO }
            sums[update.key] = current.piece(current.inc(replica, update.by).delta)
            setOf(StoredKind.SUMS)
        }
        is MetricUpdate.IncrementSumDouble -> {
            maybeEvictForNewKey(update.key, sumsDouble)
            val current = sumsDouble.getOrPut(update.key) { GCounterDouble.ZERO }
            sumsDouble[update.key] = current.piece(current.inc(replica, update.by).delta)
            setOf(StoredKind.SUMS_DOUBLE)
        }
        is MetricUpdate.SetGauge -> {
            maybeEvictForNewKey(update.key, gauges)
            val current = gauges.getOrPut(update.key) { LWWRegister.empty<Double>() }
            gauges[update.key] = current.piece(current.set(replica, update.timestamp, update.value))
            setOf(StoredKind.GAUGES)
        }
        is MetricUpdate.AddCardinality -> {
            maybeEvictForNewKey(update.key, cardinalities)
            val current = cardinalities.getOrPut(update.key) { HyperLogLog.empty() }
            cardinalities[update.key] = current.piece(current.add(update.element).delta)
            setOf(StoredKind.CARDINALITIES)
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
            setOf(StoredKind.HISTOGRAMS)
        }
        is MetricUpdate.AddUpDownSum,
        is MetricUpdate.SetUpDownSum,
        -> applyUpDownLocked(update, upDownSums, PNCounter.ZERO, StoredKind.UP_DOWN_SUMS, ::upDownSumNextOrNull)
        is MetricUpdate.AddUpDownSumDouble,
        is MetricUpdate.SetUpDownSumDouble,
        -> applyUpDownLocked(update, upDownSumsDouble, PNCounterDouble.ZERO, StoredKind.UP_DOWN_SUMS_DOUBLE, ::upDownSumDoubleNextOrNull)
        // Local-only: drops the series from this exporter and opens nothing, so it never evicts.
        is MetricUpdate.Forget -> update.storedKinds.filterTo(LinkedHashSet()) { kind -> seriesOf(kind).remove(update.key) != null }
    }

    /**
     * Apply an up-down [update] to [series] through [next], the same function [firstOverflowLocked]
     * checked it with. A new series is a change even at level zero, so it is written and exported.
     */
    private fun <C : Quilted<C>> applyUpDownLocked(
        update: MetricUpdate,
        series: LinkedHashMap<MetricKey, C>,
        zero: C,
        kind: StoredKind,
        next: (C, MetricUpdate) -> C?,
    ): Set<StoredKind> {
        maybeEvictForNewKey(update.key, series)
        val current = series[update.key]
        val moved = checkNotNull(next(current ?: zero, update)) {
            "up-down update for ${update.key.name} overflows, yet record's check passed it"
        }
        if (current != null && moved == current) return emptySet()
        series[update.key] = moved
        return setOf(kind)
    }

    /** The map that holds [kind]'s series. */
    private fun seriesOf(kind: StoredKind): LinkedHashMap<MetricKey, out Quilted<*>> = when (kind) {
        StoredKind.SUMS -> sums
        StoredKind.SUMS_DOUBLE -> sumsDouble
        StoredKind.GAUGES -> gauges
        StoredKind.CARDINALITIES -> cardinalities
        StoredKind.HISTOGRAMS -> histograms
        StoredKind.UP_DOWN_SUMS -> upDownSums
        StoredKind.UP_DOWN_SUMS_DOUBLE -> upDownSumsDouble
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
        StoredKind.UP_DOWN_SUMS -> encodeUpDownSums()
        StoredKind.UP_DOWN_SUMS_DOUBLE -> encodeUpDownSumsDouble()
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
     * Returns a [MetricExportResult.Refused] whose `overflow` is a [MetricSumOverflowException],
     * having applied and written nothing, when the series' total over every replica cannot take [by]
     * more without passing [Long.MAX_VALUE] (#3445).
     */
    public suspend fun incrementSum(key: MetricKey, by: Long = 1L): MetricExportResult =
        record(listOf(MetricUpdate.IncrementSum(key, by)))

    /**
     * Merge a remote [GCounter] snapshot into this exporter's sum for [key].
     *
     * Idempotent: merging the same snapshot twice produces the same result.
     * Returns [MetricWriteResult.Success] after the durable write.
     *
     * **Not refused when the join carries the total past [Long.MAX_VALUE].** [record] refuses an
     * increment that would (#3445), but a merge is a lattice join: refusing it would leave this
     * replica's counter apart from the peer's for good, and the peer's own counter, and every other
     * replica that joins it, would still hold the total. So the join is kept, the OTLP bridge skips
     * the series with a WARN naming it, and every later [record] increment to it is refused.
     */
    public suspend fun mergeSum(key: MetricKey, remote: GCounter): MetricWriteResult =
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
     * Returns [MetricWriteResult.Success] after the durable write.
     */
    public suspend fun mergeSumDouble(key: MetricKey, remote: GCounterDouble): MetricWriteResult =
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
     * Idempotent. Returns [MetricWriteResult.Success] after the durable write.
     */
    public suspend fun mergeGauge(
        key: MetricKey,
        remote: LWWRegister<Double>,
    ): MetricWriteResult = writeTurn(StoredKind.GAUGES) {
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
     * deduplicated. Idempotent. Returns [MetricWriteResult.Success] after the durable
     * write.
     */
    public suspend fun mergeCardinality(
        key: MetricKey,
        remote: HyperLogLog,
    ): MetricWriteResult = writeTurn(StoredKind.CARDINALITIES) {
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
     * Returns a [MetricExportResult.Refused] whose `overflow` is a [MetricHistogramOverflowException],
     * having applied and written nothing, when the series' total count is already at
     * [Long.MAX_VALUE] (#3403), or this replica's slot for [value] is.
     *
     * @throws IllegalArgumentException if [value] is NaN or infinite.
     */
    public suspend fun recordHistogram(key: MetricKey, value: Double): MetricExportResult =
        record(listOf(MetricUpdate.RecordHistogram(key, value)))

    /**
     * Merge a remote [DDSketch] snapshot into this exporter's histogram for [key].
     *
     * Idempotent and lossless: the merged sketch equals the sketch of the combined
     * measurement stream. Returns [MetricWriteResult.Success] after the durable write.
     *
     * **Not refused when the join carries the total past [Long.MAX_VALUE].** [record] refuses an
     * update that would (#3403), but a merge is a lattice join: refusing it would leave this
     * replica's sketch apart from the peer's for good, and the peer's own sketch, and every other
     * replica that joins it, would still hold the total. So the join is kept, the OTLP bridge skips
     * the series with a WARN naming it, and every later [record] update to it is refused.
     *
     * @throws IllegalArgumentException if [remote]'s configuration — relative accuracy,
     *   `minIndexedValue` and `maxIndexedValue` — is not exactly [histogramPrototype]'s. Every
     *   series here holds that configuration, which is what lets [histogramScale] describe them
     *   all; a fresh key does not take a foreign one either.
     */
    public suspend fun mergeHistogram(key: MetricKey, remote: DDSketch): MetricWriteResult {
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

    // ── Up-down sum (PNCounter / PNCounterDouble) ─────────────────────────────

    /**
     * Set this replica's share of the up-down sum for [key] to [level], the way an OpenTelemetry
     * UpDownCounter reports its level each collection (#3307). This replica's slot moves by
     * `level − its share`, so the series' value is the sum of every replica's level: two replicas at
     * 3 and 4 read 7 once merged. Setting the level it already holds writes nothing, unless the
     * store refused the last write of the kind: then it writes again, so a steady level still lands
     * a share a refused write left in memory only.
     *
     * A restart that kept [replica] and the store gets its share back from [recover], or from the
     * kind's first turn, so the same level reported after a restart moves nothing. Two things stay in
     * the sum until someone moves them: a series this replica does not report again keeps its last
     * level, and a replica that never comes back keeps its share, since only a slot's owner moves it.
     *
     * An eviction, a [clear] or a [MetricUpdate.Forget] does **not** heal on the next set for a peer
     * that already merged this series. The set starts this replica's slot again from nothing, and a
     * peer keeps the larger of each half of the old slot and the new one, so it reads a stale share
     * until this replica's increments pass the old ones. The value here is right; see the class KDoc.
     *
     * Returns a [MetricExportResult.Refused] whose `overflow` is a [MetricUpDownSumOverflowException],
     * having applied and written nothing, when the move would carry this replica's slot past
     * [Long.MAX_VALUE] or leave a total over every replica that does not fit in a `Long`.
     *
     * @sample us.tractat.kuilt.otel.sampleWarpMetricExporterUpDownSum
     */
    public suspend fun setUpDownSum(key: MetricKey, level: Long): MetricExportResult =
        record(listOf(MetricUpdate.SetUpDownSum(key, level)))

    /**
     * Move this replica's share of the up-down sum for [key] by [by], up or down: the change form of
     * [setUpDownSum], for a caller counting arrivals and departures itself.
     *
     * @throws IllegalArgumentException if [by] is `0` or [Long.MIN_VALUE].
     */
    public suspend fun addUpDownSum(key: MetricKey, by: Long): MetricExportResult =
        record(listOf(MetricUpdate.AddUpDownSum(key, by)))

    /**
     * Merge a remote [PNCounter] snapshot into this exporter's up-down sum for [key].
     *
     * Idempotent: a lattice join. Not refused when the join leaves a total that does not fit in a
     * `Long`, for the reason [mergeSum] gives; the OTLP bridge skips the series instead.
     */
    public suspend fun mergeUpDownSum(key: MetricKey, remote: PNCounter): MetricWriteResult =
        writeTurn(StoredKind.UP_DOWN_SUMS) {
            val encoded = lock.withLock {
                upDownSums[key] = upDownSums[key]?.piece(remote) ?: remote
                encodeUpDownSums()
            }
            persistUpDownSums(encoded, key)
        }

    /**
     * The up-down sum for [key]: the sum of every replica's share, or 0 if none has been set.
     *
     * @throws ArithmeticException if the shares add up past a `Long`; [upDownSumSnapshot] and
     *   [PNCounter.valueOrNull] read it without throwing.
     */
    public fun upDownSumValue(key: MetricKey): Long = lock.withLock {
        upDownSums[key]?.value ?: 0L
    }

    /** Return a snapshot of the [PNCounter] for [key] (for gossip/anti-entropy). */
    public fun upDownSumSnapshot(key: MetricKey): PNCounter = lock.withLock {
        upDownSums[key] ?: PNCounter.ZERO
    }

    /**
     * Set this replica's share of the fractional up-down sum for [key] to [level]: the double twin of
     * [setUpDownSum].
     *
     * A share is a difference of two growing totals, so it carries a rounding error that grows with
     * how far it has moved in all, and a set can land a rounding step off its level. A set that would
     * bring the share no closer moves nothing, so a level reported again settles within a few
     * writes. Returns a [MetricExportResult.Refused] when the move would take one of this
     * replica's slots past [Double.MAX_VALUE].
     *
     * @throws IllegalArgumentException if [level] is NaN or infinite.
     */
    public suspend fun setUpDownSumDouble(key: MetricKey, level: Double): MetricExportResult =
        record(listOf(MetricUpdate.SetUpDownSumDouble(key, level)))

    /**
     * Move this replica's share of the fractional up-down sum for [key] by [by]: the double twin of
     * [addUpDownSum].
     *
     * @throws IllegalArgumentException if [by] is zero, NaN or infinite.
     */
    public suspend fun addUpDownSumDouble(key: MetricKey, by: Double): MetricExportResult =
        record(listOf(MetricUpdate.AddUpDownSumDouble(key, by)))

    /** Merge a remote [PNCounterDouble] snapshot into this exporter's fractional up-down sum for [key]. Idempotent. */
    public suspend fun mergeUpDownSumDouble(key: MetricKey, remote: PNCounterDouble): MetricWriteResult =
        writeTurn(StoredKind.UP_DOWN_SUMS_DOUBLE) {
            val encoded = lock.withLock {
                upDownSumsDouble[key] = upDownSumsDouble[key]?.piece(remote) ?: remote
                encodeUpDownSumsDouble()
            }
            persistUpDownSumsDouble(encoded, key)
        }

    /** The fractional up-down sum for [key], or 0.0 if none has been set. */
    public fun upDownSumDoubleValue(key: MetricKey): Double = lock.withLock {
        upDownSumsDouble[key]?.value ?: 0.0
    }

    /** Return a snapshot of the [PNCounterDouble] for [key] (for gossip/anti-entropy). */
    public fun upDownSumDoubleSnapshot(key: MetricKey): PNCounterDouble = lock.withLock {
        upDownSumsDouble[key] ?: PNCounterDouble.ZERO
    }

    // ── Diagnostics ───────────────────────────────────────────────────────────

    /** Total number of distinct [MetricKey]s tracked across all kinds. */
    public fun metricCount(): Int = lock.withLock { totalCount() }

    /**
     * A converged snapshot of **every** metric series across all seven maps, as one
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
            upDownSums = upDownSums.toMap(),
            doubleUpDownSums = upDownSumsDouble.toMap(),
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

    private fun encodeUpDownSums(): ByteArray =
        cbor.encodeToByteArray(upDownSumsSerializer, upDownSums)

    private fun encodeUpDownSumsDouble(): ByteArray =
        cbor.encodeToByteArray(upDownSumsDoubleSerializer, upDownSumsDouble)

    // ── Persistence (called outside lock) ────────────────────────────────────

    private suspend fun persistSums(encoded: ByteArray, key: MetricKey): MetricWriteResult =
        persist(SUM_STORE_KEY, encoded, key).asWriteResult()

    private suspend fun persistSumsDouble(encoded: ByteArray, key: MetricKey): MetricWriteResult =
        persist(SUM_DOUBLE_STORE_KEY, encoded, key).asWriteResult()

    private suspend fun persistGauges(encoded: ByteArray, key: MetricKey): MetricWriteResult =
        persist(GAUGE_STORE_KEY, encoded, key).asWriteResult()

    private suspend fun persistCardinalities(encoded: ByteArray, key: MetricKey): MetricWriteResult =
        persist(CARDINALITY_STORE_KEY, encoded, key).asWriteResult()

    private suspend fun persistHistograms(encoded: ByteArray, key: MetricKey): MetricWriteResult =
        persist(HISTOGRAM_STORE_KEY, encoded, key).asWriteResult()

    private suspend fun persistUpDownSums(encoded: ByteArray, key: MetricKey): MetricWriteResult =
        persist(UP_DOWN_SUM_STORE_KEY, encoded, key).asWriteResult()

    private suspend fun persistUpDownSumsDouble(encoded: ByteArray, key: MetricKey): MetricWriteResult =
        persist(UP_DOWN_SUM_DOUBLE_STORE_KEY, encoded, key).asWriteResult()

    /** A [persist] refusal, or `null` for a landed write, as a merge's result. */
    private fun Throwable?.asWriteResult(): MetricWriteResult = this?.let { MetricWriteResult.Failure(it) } ?: MetricWriteResult.Success

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
     * the trace is affordable. Every failure is returned to the caller carrying the cause
     * regardless, so deduplicating the *line* hides nothing from a programmatic reader.
     *
     * Returns the store's refusal, or `null` once the write landed.
     */
    private suspend fun persist(storeKey: StoreKey, encoded: ByteArray, metricKey: MetricKey): Throwable? {
        val written = try {
            store.write(storeKey, encoded)
            Result.success(Unit)
        } catch (cause: Throwable) {
            // Only this coroutine's own cancellation propagates. One the store minted from its own
            // `withTimeout` is a refused write, which the caller is told of rather than cancelled by (#3388).
            currentCoroutineContext().ensureActive()
            Result.failure(cause)
        }
        return written.fold(
            onSuccess = {
                persistence.acknowledged(setOf(storeKey))
                // The store took THIS key back. It says nothing about the others, so only this
                // one leaves the set; the outage stays open while any key is still refused.
                durableWriteOutage.update { it - storeKey }
                null
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
                cause
            },
        )
    }

    // ── Eviction (called inside lock) ─────────────────────────────────────────

    /**
     * If [key] is new (not present in [map]) and [totalCount] has reached [maxMetrics],
     * evict one series from the combined pool according to [bufferPolicy].
     *
     * Eviction selects from whichever of the seven maps holds the oldest/newest entry
     * by insertion order. The evicted key is always logged at WARN.
     */
    private fun <V> maybeEvictForNewKey(key: MetricKey, map: LinkedHashMap<MetricKey, V>) {
        if (key in map) return
        if (totalCount() < maxMetrics) return
        evictOne()
    }

    private fun totalCount(): Int =
        sums.size + sumsDouble.size + gauges.size + cardinalities.size + histograms.size +
            upDownSums.size + upDownSumsDouble.size

    /**
     * Evict one series under [bufferPolicy]. Called only at or over [maxMetrics], which is at least
     * 1, so there is always a series to evict.
     */
    private fun evictOne() {
        val victim = checkNotNull(
            when (bufferPolicy) {
                MetricBufferPolicy.DROP_OLDEST -> pickDropOldestVictim()
                MetricBufferPolicy.DROP_NEWEST -> pickDropNewestVictim()
            },
        ) { "the buffer is at its cap of $maxMetrics, yet holds no series to evict" }
        logEviction(victim)
        sums.remove(victim)
        sumsDouble.remove(victim)
        gauges.remove(victim)
        cardinalities.remove(victim)
        histograms.remove(victim)
        upDownSums.remove(victim)
        upDownSumsDouble.remove(victim)
    }

    /**
     * The eviction victim under DROP_OLDEST: the first key of the first non-empty map in
     * fixed map order (sums → sumsDouble → gauges → cardinalities → histograms → upDownSums →
     * upDownSumsDouble). NOT a true cross-map insertion-oldest — insertion time is not comparable across the seven
     * LinkedHashMaps.
     */
    private fun pickDropOldestVictim(): MetricKey? =
        listOfNotNull(
            sums.keys.firstOrNull(), sumsDouble.keys.firstOrNull(),
            gauges.keys.firstOrNull(), cardinalities.keys.firstOrNull(),
            histograms.keys.firstOrNull(), upDownSums.keys.firstOrNull(), upDownSumsDouble.keys.firstOrNull(),
        ).firstOrNull()

    /**
     * The eviction victim under DROP_NEWEST: the last key of the last non-empty map in the
     * same fixed map order. NOT a true cross-map insertion-newest (see [pickDropOldestVictim]).
     */
    private fun pickDropNewestVictim(): MetricKey? =
        listOfNotNull(
            sums.keys.lastOrNull(), sumsDouble.keys.lastOrNull(),
            gauges.keys.lastOrNull(), cardinalities.keys.lastOrNull(),
            histograms.keys.lastOrNull(), upDownSums.keys.lastOrNull(), upDownSumsDouble.keys.lastOrNull(),
        ).lastOrNull()

    private fun logEviction(victim: MetricKey) {
        logger.warn {
            "WarpMetricExporter: buffer cap ($maxMetrics) reached, evicting metric " +
                "name=${victim.name} kind=${victim.kind} attrs=${victim.attributes} " +
                "policy=$bufferPolicy"
        }
    }
}

/** The seven maps a [WarpMetricExporter] persists, one store key each, in the fixed order a batch writes them. */
internal enum class StoredKind(val storeKey: StoreKey) {
    SUMS(StoreKey("otel.metrics.sums")),
    SUMS_DOUBLE(StoreKey("otel.metrics.sums.double")),
    GAUGES(StoreKey("otel.metrics.gauges")),
    CARDINALITIES(StoreKey("otel.metrics.cardinalities")),
    HISTOGRAMS(StoreKey("otel.metrics.histograms")),
    UP_DOWN_SUMS(StoreKey("otel.metrics.updown")),
    UP_DOWN_SUMS_DOUBLE(StoreKey("otel.metrics.updown.double")),
}

/**
 * The persisted maps [this] update may change: one for every update but a [MetricUpdate.Forget],
 * which names a series by its key's kind, and a sum or up-down sum's key names a series in both the
 * integer and the fractional map.
 */
internal val MetricUpdate.storedKinds: Set<StoredKind>
    get() = when (this) {
        is MetricUpdate.IncrementSum -> setOf(StoredKind.SUMS)
        is MetricUpdate.IncrementSumDouble -> setOf(StoredKind.SUMS_DOUBLE)
        is MetricUpdate.SetGauge -> setOf(StoredKind.GAUGES)
        is MetricUpdate.AddCardinality -> setOf(StoredKind.CARDINALITIES)
        is MetricUpdate.RecordHistogram, is MetricUpdate.AddExponentialHistogram -> setOf(StoredKind.HISTOGRAMS)
        is MetricUpdate.AddUpDownSum, is MetricUpdate.SetUpDownSum -> setOf(StoredKind.UP_DOWN_SUMS)
        is MetricUpdate.AddUpDownSumDouble, is MetricUpdate.SetUpDownSumDouble -> setOf(StoredKind.UP_DOWN_SUMS_DOUBLE)
        is MetricUpdate.Forget -> when (key.kind) {
            MetricKind.SUM -> setOf(StoredKind.SUMS, StoredKind.SUMS_DOUBLE)
            MetricKind.GAUGE -> setOf(StoredKind.GAUGES)
            MetricKind.CARDINALITY -> setOf(StoredKind.CARDINALITIES)
            MetricKind.EXPONENTIAL_HISTOGRAM -> setOf(StoredKind.HISTOGRAMS)
            MetricKind.UP_DOWN_SUM -> setOf(StoredKind.UP_DOWN_SUMS, StoredKind.UP_DOWN_SUMS_DOUBLE)
        }
    }

/** Whether none of the maps [this] update may change is one whose persisted series is still [unread]. */
private fun MetricUpdate.isAdmittedPast(unread: Map<StoredKind, *>): Boolean = storedKinds.none { it in unread }

/**
 * The result of [WarpMetricExporter.record] and of the single-point mutators built on it. Each arm
 * says what a retry may send: counting an update twice is as wrong as dropping it.
 */
public sealed interface MetricExportResult {
    /** Every update was counted, and every kind the call touched is durably written. */
    public data object Success : MetricExportResult

    /**
     * Nothing was counted: an update would carry a sum or histogram series' total over every
     * replica past [Long.MAX_VALUE], so the whole call was refused before anything was applied or written. The
     * same call may be refused again until that series is cleared or evicted; a retry has to leave
     * out the series [overflow] names.
     */
    public data class Refused(public val overflow: MetricOverflowException) : MetricExportResult

    /**
     * The call did not end durable. [uncounted] is exactly the updates that were not applied, in
     * call order: those of a kind whose persisted series the store refused to read (#3390). Recording
     * [uncounted] again counts each of them once. Every other update was counted, in memory, and
     * may not be on disk yet where its kind's write was refused, so retrying it counts it twice; the
     * next successful write of its kind persists it.
     *
     * [causes] is diagnostic and never empty: a [MetricKindUnreadException] per unread kind, then
     * the store's refusal of each write, in the fixed kind order. Whether a refused write's bytes
     * landed is indeterminate.
     */
    public data class Partial(
        public val uncounted: List<MetricUpdate>,
        public val causes: List<Throwable>,
    ) : MetricExportResult {
        init {
            require(causes.isNotEmpty()) { "a Partial needs a cause" }
        }
    }
}

/**
 * The result of a `merge*` on [WarpMetricExporter], or of [WarpMetricExporter.clear]. A merge is a
 * lattice join and a clear is a delete, so either may always be called again: a second merge of the
 * same state changes nothing, and a second clear deletes what the first could not.
 */
public sealed interface MetricWriteResult {
    /** The merge or the clear is durably written. */
    public data object Success : MetricWriteResult

    /**
     * The call did not end durable. A merge's [cause] is the store's refusal of the write, whose
     * merged state stays in memory, or a [MetricKindUnreadException], in which case nothing was
     * merged. A clear's is the refused delete; memory is cleared either way.
     */
    public data class Failure(public val cause: Throwable) : MetricWriteResult
}

/**
 * A kind of metric whose persisted series no read has joined into memory yet, because the store
 * refused the read (#3390). Nothing of that kind was applied, and its store key [storeKey] was not
 * written. The store's refusal is [cause]. It arrives in a [MetricExportResult.Partial]'s causes or
 * as a [MetricWriteResult.Failure]'s cause; only the exporter constructs one.
 *
 * @property storeKey the persisted map of the kind that could not be read.
 */
public class MetricKindUnreadException internal constructor(
    public val storeKey: StoreKey,
    /** The store's refusal of the read. */
    override val cause: Throwable,
) : Exception("${storeKey.name}: the store refused the read of this kind, so none of its updates were counted", cause)

/**
 * Why a [WarpMetricExporter.record] was refused: an update would carry the series [key]'s total
 * over every replica past [Long.MAX_VALUE]. The total is what the OTLP bridge exports, so a series
 * past it is one the bridge skips. The narrower case, this replica's own slot passing
 * [Long.MAX_VALUE], is part of it: a wrapped slot would be negative, and a counter's merge, a max,
 * would drop it, so the update would be lost everywhere with nothing to say so. It arrives as a
 * [MetricExportResult.Refused], and the batch that carried it applied nothing.
 *
 * Which kind of series [key] names is the subclass: [MetricSumOverflowException] for a sum,
 * [MetricHistogramOverflowException] for a histogram, [MetricUpDownSumOverflowException] for an
 * up-down sum. Series of different kinds can share a [MetricKey], so a caller leaving the series out
 * of a retry needs both. Only the exporter
 * constructs one; a consumer only receives it.
 */
public sealed class MetricOverflowException(message: String) : ArithmeticException(message) {
    /** The series whose count cannot take the update. */
    public abstract val key: MetricKey
}

/**
 * A [MetricOverflowException] for a sum: an increment would carry the sum series [key]'s total
 * past [Long.MAX_VALUE] (#3445), counting the batch's own earlier increments to it. The total is
 * summed over every replica's slot, a peer's merged in included, and is the value the OTLP bridge
 * exports, so a series past it is one the bridge could not export. That includes the narrower case
 * of #3318, this replica's own slot passing [Long.MAX_VALUE].
 *
 * @property key the sum series that cannot take the increment.
 * @property total the series' total over every replica before the refused increment, including the
 *   batch's own earlier increments to it; `null` when that total already passes [Long.MAX_VALUE],
 *   which a [WarpMetricExporter.mergeSum] join can leave.
 * @property by the refused increment.
 */
public class MetricSumOverflowException internal constructor(
    override val key: MetricKey,
    public val total: Long?,
    public val by: Long,
) : MetricOverflowException(
    "sum series ${key.name} ${key.attributes}: " +
        if (total == null) {
            "its total over every replica already passes Long.MAX_VALUE"
        } else {
            "its total over every replica, $total, cannot take $by more without passing Long.MAX_VALUE"
        },
)

/**
 * A [MetricOverflowException] for a histogram: a [MetricUpdate.RecordHistogram] or
 * [MetricUpdate.AddExponentialHistogram] would carry the series' total count past
 * [Long.MAX_VALUE] (#3403), counting the batch's own earlier updates to it. The total is summed over
 * every replica's slots, both signs' buckets and the zero counter, and is the count the OTLP bridge
 * exports, so a series past it is one the bridge could not export. That includes the narrower cases
 * of #3354: this replica's slot in a bucket, or in the zero or overflow counter, passing
 * [Long.MAX_VALUE], and counts that each fit but fold onto one bucket past it.
 *
 * @property key the histogram series that cannot take the update.
 */
public class MetricHistogramOverflowException internal constructor(
    override val key: MetricKey,
) : MetricOverflowException(
    "histogram series ${key.name} ${key.attributes}: its total count, or this replica's count in one of its " +
        "buckets or in its zero or overflow counter, cannot take this batch's values without passing Long.MAX_VALUE",
)

/**
 * A [MetricOverflowException] for an up-down sum (#3307): a [MetricUpdate.AddUpDownSum],
 * [MetricUpdate.SetUpDownSum] or a fractional twin would move one of this replica's slots past what
 * it holds — [Long.MAX_VALUE], or [Double.MAX_VALUE] for the fractional map — counting the batch's own
 * earlier updates to the series. For the integer map it is also refused when the total over every
 * replica it would leave does not fit in a `Long`, since the OTLP bridge could not export it.
 *
 * An update that brings such a total back into range is not refused, unlike a sum's: an up-down
 * total moves both ways.
 *
 * @property key the up-down series that cannot take the update.
 */
public class MetricUpDownSumOverflowException internal constructor(
    override val key: MetricKey,
    /** Whether the refused series is the fractional one; [key] names a series in both maps. */
    internal val fractional: Boolean,
) : MetricOverflowException(
    "up-down sum series ${key.name} ${key.attributes}: this batch would move this replica's share past what " +
        "its slots hold, or leave a total over every replica that does not fit in a Long",
)
