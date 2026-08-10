@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.otel

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.RgaId
import us.tractat.kuilt.crdt.RgaOp
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece

// Explicit, package-qualified name — NOT the `logger {}` lambda form. On
// Kotlin/Native the lambda form resolves to an EMPTY logger name, which would make
// this internal logger indistinguishable from an application logger and defeat the
// self-capture exclusion in :kuilt-otel-logging (`LogCapture` drops events whose
// loggerName starts with `us.tractat.kuilt`). The exporter still logs from the
// eviction path — rate-limited now (#2218) rather than per record, but under load
// that is a line arriving while records are being exported, so an unnamed log here
// would be re-captured and re-exported, feeding itself. Keep this name stable and
// under the kuilt package.
private val logger = KotlinLogging.logger("us.tractat.kuilt.otel.WarpLogRecordExporter")

/**
 * How many ops [segment] holds, for the roll threshold.
 *
 * Delegates to [Rga.opCount], which counts every op — Inserts, Removes and retained
 * `Compact`s alike. A `sequence.size + tombstones.size` projection is blind to `Compact`
 * (invisible to both), so it would silently undercount a segment that carries one.
 *
 * Top-level (not a class member) because it depends only on [segment], not on any
 * exporter instance state — which also lets test source call it directly ([Rga.opCount]
 * is `public`, so nothing but naming keeps `opCountOf` itself internal here).
 * Internal so `WarpLogRecordExporterSegmentTest`'s `opCountOfForTest` shim can call the
 * production function instead of duplicating its logic.
 */
internal fun opCountOf(segment: Rga<LogRecord>): Int = segment.opCount

/**
 * A CRDT-backed log-record exporter.
 *
 * Log records are stored in an [Rga]`<`[LogRecord]`>`: an ordered, append-only
 * sequence. Insertion order within a single replica is preserved; cross-replica
 * ordering is resolved by RGA's Lamport tiebreak — deterministic but not
 * wall-clock accurate under clock skew.
 *
 * ## Idempotency
 *
 * Each [LogRecord] carries a caller-assigned [LogRecord.recordId] (8 bytes).
 * [export] tracks which record ids have already been inserted: re-exporting a
 * record with the same [LogRecord.recordId] returns [ExportResult.Success]
 * immediately without inserting a duplicate into the [Rga]. The dedup state is
 * rebuilt from the op-log on [recover], so idempotency survives process restarts.
 *
 * ## Key inversion
 *
 * [export] returns [ExportResult.Success] the moment the record is **durably
 * written to the [DurableStore]** — not when it is delivered to any backend.
 * Delivery is asynchronous and eventually consistent; the CRDT merge guarantees
 * that any replica which receives the record will incorporate it correctly, even
 * if the record arrives out of order or more than once.
 *
 * ## Buffer cap
 *
 * [maxRecords] bounds how many records stay visible, and [bufferPolicy] decides which
 * record gives way. **Every drop is counted** — exactly, per record, on
 * [ExporterHealth.dropped] and [ExporterHealth.refused] — and a rate-limited `info` line
 * reports the running total so the loss is not silent for a consumer who never reads
 * [health].
 *
 * Per-record correlation was given up deliberately (#2218). This class used to log every
 * drop with the evicted record's id and body, detailed enough to line up against a backend's
 * log index — written when eviction was exceptional. At [DEFAULT_MAX_LOG_RECORDS] the buffer
 * is full permanently, so every exported record evicts one and that line became a per-record
 * narration of a ring buffer doing exactly what it is configured to do, on the export hot
 * path. A signal that fires always is not one. What is lost is *which* records went; what is
 * kept, and is what an operator actually reads, is *how many*.
 *
 * - [BufferPolicy.DROP_OLDEST] evicts visible index 0 and then inserts the arrival, so
 *   the buffer is a sliding window over the most recent [maxRecords] records.
 * - [BufferPolicy.DROP_NEWEST] refuses the **arrival**. At a full buffer the newest
 *   record is the one arriving, so "drop the newest" drops it: on the **export path** the
 *   buffer freezes at the first [maxRecords] records and appends no further op — no
 *   insert, no eviction, no tombstone.
 *
 * [merge] is the exception, and it is the intended production path, not a corner:
 * [snapshot]/[merge] exist so this exporter can participate in gossip. A merge folds a
 * remote op-log in wholesale, so it can push the visible count **past** [maxRecords] —
 * remote inserts interleaving into the visible order, remote tombstones arriving from a
 * peer running a different [bufferPolicy] — after which the gate simply keeps refusing.
 * Neither policy evicts on the merge path.
 *
 * So the claim for `DROP_NEWEST` is about what this replica *emits*, not about what its
 * buffer holds: it never authors a `Remove`, which is what makes **its own** contribution
 * to the shared op-log a downward-closed prefix (#2127). The two exporters that share
 * [BufferPolicy] resolve "newest" differently — [WarpSpanExporter] evicts the newest
 * *buffered* span and admits the arrival — and that is the reason this one does not.
 *
 * ## On-disk layout — segmented op-log
 *
 * An [Rga] *is* an op-log, and [Rga.piece] is an idempotent union of two op-logs.
 * So the exporter does not need one key holding the whole log: it keeps the log in
 * **segments** of at most [segmentOps] operations, each under its own key
 * (`otel.logs.seg.<n>`), plus a small [LogSegmentIndex] naming the live ones
 * (`otel.logs.idx`).
 *
 * [export] appends the [us.tractat.kuilt.crdt.RgaOp.Insert]s that
 * [Rga.insertAllAfter] already returns to the **active** segment and rewrites only
 * that segment **once per turn**, so the encode-and-write cost is O([segmentOps])
 * per turn — a constant, independent of how many records the log holds, and
 * amortised across however many records the turn took (#2194). The previous layout
 * re-encoded and rewrote the entire log on every single record, which is O(N) work
 * once per record: Θ(N²) time and Θ(N²) bytes to accumulate N records (#1860).
 *
 * Recovery reads the segments named by the index and unions them with [Rga.piece].
 * Set union is commutative and idempotent, so the reconstruction is exact and
 * order-independent — and the persisted [Rga] wire form derives its Lamport clock
 * from the op-set, so nothing is lost by not persisting it per segment.
 *
 * ## Windowing the in-memory op-log
 *
 * Eviction only *tombstones*: the evicted record's `Insert` op — body and all — stays
 * in the log, so the in-memory op-log grew with the number of records ever exported
 * even though [maxRecords] held visibility flat. [Rga.dropWindow] is the fix, and this
 * exporter calls it in **passes** ([windowPass]): everything outside the retained
 * window is dropped from `log`.
 *
 * **How cheaply the drop is recorded depends on who authored the dot**, and only one of
 * the two arms is a bound. This replica's own dots fold into a per-author compaction
 * **floor** — O(authors), not O(elements dropped) — so a log fed only by [export] settles
 * back to O([maxRecords]) after every pass. A *foreign* author's dot cannot: raising
 * another author's floor entry would annihilate dots it may not have minted yet, so
 * [Rga.dropWindow] records those in an explicit [RgaOp.Compact] costing one
 * `(RgaId -> RgaId)` pair each — and nothing ever prunes them, because a purge retains
 * `Compact` unconditionally and [Rga.piece] unions the positions it carries. So the
 * honest in-memory bound is **O([maxRecords]) on the export path, plus one bodiless pair
 * per foreign element ever windowed away on the [merge] path.**
 *
 * That second term is a strict improvement on what preceded it — before windowing, a
 * merged-in foreign `Insert` was retained whole, body included — but it is growth, not a
 * bound, and a replica that gossips accumulates it for as long as the process lives.
 * Bounding it needs the same causal-stability argument segment retirement does, and is
 * not attempted here. `WarpLogRecordExporterWindowingTest` measures both terms against
 * each other.
 *
 * **[Rga.compact] is not the mechanism, and could not be.** It is the obvious candidate — it is
 * the reclamation this codebase already had — and it reclaims *nothing at all* here, for a
 * structural reason rather than a tuning one. Its condition 4 refuses to collect a tombstone that
 * is still some live element's `after`, and this log is an append **chain**: every record is
 * inserted after the previous one, so every element except the tail is the predecessor of a live
 * successor. [BufferPolicy.DROP_OLDEST] evicts index 0, which at every [maxRecords] above one is
 * the element furthest from being the tail — so the one tombstone condition 4 would accept is the
 * one this exporter never produces. At `maxRecords = 1` that stops holding: index 0 *is* the tail,
 * and the record replacing it is appended after [RgaId.HEAD], so condition 4 would accept the
 * eviction. It reclaims nothing there either, because condition 3 blocks independently and at
 * every cap: a delivered frontier is a fact about peers, and this class holds a [DurableStore],
 * not a [us.tractat.kuilt.core.Seam]. Windowing
 * exists because forgetting *position* needs no barrier at all — the next paragraph is why.
 *
 * Windowing is sound without a causal-stability barrier because it deliberately forgets
 * *position*, not *identity*: a dropped dot stays suppressed, so a peer that still holds
 * the raw `Insert` cannot push the record back in through [merge] — [Rga.piece] merges
 * the suppression and re-purges under it. Which suppressor does the work follows the same
 * split as the cost: the **floor** for this replica's own dots, and the retained
 * [RgaOp.Compact]'s compacted-id set for a foreign author's. What is given up is the
 * stability of a survivor's position when its predecessor is dropped; see
 * [Rga.compactedBelow].
 *
 * ## Retiring superseded segments
 *
 * Windowing alone leaves the store growing: a windowed-away `Insert` leaves `log`, but it
 * stays in whichever *sealed* segment it landed in. A sealed segment whose every op the
 * suppression state already covers contributes **nothing** to what recovery reconstructs —
 * the union re-purges those ops under the floor and the retained `Compact`s — so its key can
 * be deleted. That is what a [windowPass] does next, and **on the export path** it is what keeps
 * the number of keys recovery opens flat instead of growing with the records ever exported — the
 * gossip path's key count is not bound this way; see below.
 *
 * It is not simply "delete a key", and two rules make it safe:
 *
 * - **A segment carrying an [RgaOp.Compact] is never retired.** A `Compact` is the only
 *   carrier of [Rga]'s "once compacted, always compacted" guarantee for the ids it names, and
 *   nothing prunes one; dropping it lets a peer that never received the compaction re-admit
 *   the purged record. The legacy migration's segment and any [merge]-adopted segment can
 *   carry one.
 * - **Unknown content means keep.** A segment is retired only on *positive* evidence that
 *   every op it holds is superseded — never on the absence of evidence to the contrary. A
 *   segment whose content could not be read keeps its key and its place in the index.
 *
 * The residue is the merge path's, again — and **on disk it is not the bodiless pair the
 * in-memory bound is priced in.** A foreign author's dots are covered by an explicit
 * [RgaOp.Compact], nothing prunes one, and any segment carrying one is therefore pinned — where
 * pinned means retained **entire**: every [RgaOp.Insert] it holds, bodies included, for the life
 * of the store. Two shapes reach it:
 *
 * - a sealed segment that happened to be active when a pass minted a `Compact` keeps its full
 *   [segmentOps] ops — ~123 KB at [DEFAULT_LOG_SEGMENT_OPS]; and
 * - a [merge] persists the remote op-log **verbatim** under a key of its own, so merging from a
 *   peer whose log carries a `Compact` — which any peer that has itself windowed a foreign
 *   author's dots does, i.e. any peer in a steady-state mesh — pins that peer's whole log. At
 *   [DEFAULT_MAX_LOG_RECORDS] that is megabytes per merge.
 *
 * So the on-disk total settles for a replica fed by [export] and grows in **whole records** for
 * one fed by gossip — a coarser split than the in-memory bound's. Consolidation — rewriting a
 * pinned segment's `Compact` forward so the segment itself can go — is the obvious escape and is
 * deliberately absent: §9 of the design declined it, and nothing implements it.
 *
 * @param replica The [ReplicaId] for this device/process. Must be unique and stable
 *   across restarts (a UUID is recommended).
 * @param store The [DurableStore] to persist CRDT state. Use [InMemoryDurableStore]
 *   in tests; wire a platform WAL (JVM file, IndexedDB, etc.) in production.
 * @param maxRecords Maximum number of records buffered in memory before eviction.
 *   Defaults to [DEFAULT_MAX_LOG_RECORDS].
 * @param bufferPolicy What to do when [maxRecords] is exceeded. Defaults to
 *   [BufferPolicy.DROP_OLDEST].
 * @param segmentOps Operations per persisted segment — the ceiling on how many bytes
 *   one [export] rewrites. Pure tuning: smaller writes less per record but keeps more
 *   keys. Defaults to [DEFAULT_LOG_SEGMENT_OPS].
 *
 * @sample us.tractat.kuilt.otel.sampleWarpLogRecordExporter
 */
public class WarpLogRecordExporter(
    private val replica: ReplicaId,
    private val store: DurableStore,
    private val maxRecords: Int = DEFAULT_MAX_LOG_RECORDS,
    private val bufferPolicy: BufferPolicy = BufferPolicy.DROP_OLDEST,
    private val segmentOps: Int = DEFAULT_LOG_SEGMENT_OPS,
) {
    init {
        require(segmentOps >= 1) { "segmentOps must be at least 1; got $segmentOps" }
        // A zero cap is total, silent telemetry loss from a public constructor argument:
        // under DROP_NEWEST the gate refuses every record forever, and under DROP_OLDEST
        // every record is inserted and immediately evicted.
        require(maxRecords >= 1) { "maxRecords must be at least 1; got $maxRecords" }
    }

    // The lock guards 'log' and every derived field below. No suspend calls are
    // made inside the locked section — Cbor encode/decode and the CRDT mutations
    // are pure (non-suspending). The store write is performed outside the lock on
    // the encoded snapshot.
    //
    // An explicit reentrant lock is the repo policy for scope-owning types:
    // correctness must hold under a real multi-threaded dispatcher, not just the
    // test dispatcher. limitedParallelism(1) confinement is BANNED — see CLAUDE.md.
    private val lock = reentrantLock()

    /**
     * Serializes one **write turn** — build the turn's actions under [lock], then apply them to
     * [store] — so that a turn's actions reach the store in the order they were built.
     *
     * [lock] alone cannot give that. It is a thread-blocking primitive, so it is released the
     * instant the actions are built and every `store.write` in [commit] runs outside it. Two
     * overlapping turns therefore build in one order and land in another, and the CRDT's own
     * convergence does not rescue the store, because a turn's write does not *merge* into a key —
     * it **overwrites** it:
     *
     * - the **active-segment write** carries that whole segment, so a turn whose bytes were
     *   encoded first and land last silently discards the other turn's records. [export] has
     *   already returned [ExportResult.Success] for them, which is the one thing this class
     *   promises never to do (see "Key inversion"). Measured, not argued: before this mutex, 32
     *   concurrent exports on a real thread pool recovered **zero** of the eight records the
     *   exporter still held.
     * - the **index write** carries the whole layout, so a stale one drops the segment numbers a
     *   fresher one had just added. With no key-enumeration API a segment the index stops naming
     *   is unreachable and unsweepable forever.
     * - a [StoreAction.Sweep] is ordered behind its **own** turn's covering write, and nothing
     *   ordered it against another turn's. A delete could therefore land on a segment whose
     *   covering floor a stale active-segment write had just overwritten away — precisely the
     *   inversion [retireSupersededSegments] names as the unsafe order, reached without inverting
     *   anything *within* a turn.
     *
     * Building **inside** the same critical section that applies is what makes build order and
     * apply order the same order by construction rather than a property to be maintained. It also
     * closes the window the deferred retirement move opened: a turn cannot read [sealedSegments]
     * between another turn's [retireSupersededSegments] and its [applyRetirement], so a segment
     * number cannot be staged for retirement twice.
     *
     * **Do not narrow this to [commit] alone.** It looks equivalent and holds the lock for less
     * time, and it is wrong twice: two turns would still build in one order and acquire in
     * another, and a turn could still build all its actions while another turn's commit is
     * in flight — which is the double-staging window verbatim.
     *
     * The narrow form is not wrong everywhere, and the sibling makes the distinction checkable.
     * [WarpSpanExporter] holds its coroutine `Mutex` over the durable section **alone** and is
     * correct, *because* it re-encodes the latest state inside that section — its bytes are
     * derived after the mutex is held, so no write it makes can be stale (#1053). This class
     * cannot borrow that argument: [commit] is handed bytes that were encoded before it was
     * called, over field mutations ([appendToActiveSegment], [windowPass], [adoptRemoteSegment])
     * that are already applied. Re-deriving inside [commit] is not on
     * offer either — a turn is an ordered sequence of writes across several keys, and an
     * interleaved turn has already moved the fields those writes describe. So: one write, a
     * narrow mutex; several keys' writes built from mutated state, the whole turn.
     *
     * `anExportDoesNotBuildItsBatchWhileAnotherExportsCommitIsInFlight`
     * (`WarpLogRecordExporterConcurrencyTest`) is what pins the narrow form out, and the
     * observable it uses is the log rather than the store. A double-stage is idempotent at every
     * step ([applyRetirement] filters, `removeAll` and `remove` no-op, and a repeat delete of an
     * absent key is a no-op), so it leaves no trace on [store] to assert on — but the *in-memory*
     * insert a second turn performs while the first is parked in `store.write` is visible through
     * [snapshot] immediately. One property is still unasserted: the residual reorder between
     * build order and acquire order shrinks from one store write wide to a few instructions wide,
     * which a stress loop does not reach.
     *
     * A [Mutex] and not [lock] because a turn suspends — holding a thread-blocking lock across
     * `store.write` would park a dispatcher thread for the length of an I/O. It is a real
     * mutual-exclusion primitive, not `limitedParallelism(1)` confinement: this type owns no scope
     * and no dispatcher, and stays correct on a multi-threaded one. Named `writeMutex` rather than
     * the sibling's `ioMutex` deliberately: the two cover different spans, and `ioMutex` would name
     * exactly the narrower one the paragraphs above ban.
     *
     * **Acquisition order is [writeMutex] then [lock], never the reverse.** [snapshot] takes only
     * [lock], and [commit] takes [lock] briefly while holding this one.
     *
     * The cost has two halves and only one of them is free. **Export against export** was never
     * genuinely concurrent: every one of them rewrites the *same* active-segment key, so
     * overlapping only decided which of them won, and queueing them changes nothing but the word
     * for it. **Merge against export** is a real new serialization: a [merge] does an O(remote)
     * CRDT join and writes a whole adopted segment, and every [export] now waits behind that — on
     * the logging hot path, where the capture edge above this exporter (`CapturingAppender` in
     * `:kuilt-otel-logging`) is a bounded queue that drops the oldest events once it fills. A
     * gossiping replica therefore pays for this mutex in log events. It is still the right trade —
     * the alternative is a store that silently discards records [export] has already returned
     * [ExportResult.Success] for — but it is a trade, not free.
     */
    private val writeMutex = Mutex()

    private var log: Rga<LogRecord> = Rga.empty()

    // ── Derived state, threaded forward across export() calls ────────────────
    //
    // All three are functions of `log` alone and were recomputed from it on every
    // export. That is pure waste in this access pattern: `Rga` is immutable, so
    // `insertAllAfter` hands back a NEW instance whose `sequence` lazy is cold —
    // meaning a full `computeSequence()` (a groupBy plus a sortedDescending per
    // sibling group) ran once per exported record, plus two more O(N) passes for
    // the eviction gate and the tail filter. Maintaining them incrementally makes
    // the common (non-evicting) export path touch the sequence not at all.
    //
    // Every write to `log` must update these in the same locked section. The three
    // events that move them out from under the cache — a DROP_OLDEST eviction that
    // empties the log, a merge whose remote inserts sort after the local tail, and a
    // recover() that replaces the log wholesale — are pinned by
    // WarpLogRecordExporterTailCacheTest. Eviction is the *only* thing that can remove
    // an element on the export path, and it only ever removes a leading prefix of the
    // visible sequence, so no export can invalidate `tail` while the log is non-empty.

    /** Predecessor for the next append: the last visible element, or [RgaId.HEAD] when empty. */
    private var tail: RgaId = RgaId.HEAD

    /** Number of visible (non-tombstoned) records in [log] — the eviction gate. */
    private var visibleCount: Int = 0

    /**
     * Evictions since the last [windowPass]. One of the two things that make a pass due —
     * see [windowPassDue], and [windowPass] for why passes are grouped rather than
     * per-eviction.
     */
    private var evictionsSincePass: Int = 0

    /**
     * Which [DROP_REPORT_INTERVAL]-wide bucket of `dropped + refused` [reportDropsPeriodically]
     * has already spoken for. Guarded by [lock] — both call sites run inside the turn-building
     * `lock.withLock` block.
     *
     * **Starts at `-1`, not `0`.** At `0` the first bucket compares equal to the initial value,
     * so nothing would be logged until drop number [DROP_REPORT_INTERVAL] — an exporter that
     * drops 800 records would then log **nothing, ever**, while an operator who never polls
     * [health] sees no evidence of loss. That is exactly the silent-loss inversion (#1860) this
     * summary line exists to prevent. At `-1` the *first* drop reports, then once per bucket.
     */
    private var lastDropReport: Long = -1L

    // Maps recordId → RgaId of the Insert op, so that re-export is a no-op.
    // Mutated in place on export()/eviction and rebuilt from the op-log on recover().
    private val seenIds: MutableMap<ByteString, RgaId> = mutableMapOf()

    // A MutableStateFlow owns no CoroutineScope, so the health surface adds no
    // scope ownership to this type. `update {}` is an atomic CAS loop — a real
    // thread-safe primitive, not dispatcher confinement (repo policy).
    // ── Persisted segments ───────────────────────────────────────────────────
    //
    // Guarded by `lock`, like everything above. Only the segment NUMBERS are held —
    // the ops live in `log`, and the segments are how it is persisted.
    //
    // The invariant is that **the segments' union `piece`s to `log`** — NOT that their op-sets
    // are equal. Set equality held before windowing and is false now: a windowed-away `Insert`
    // leaves `log` but stays in whichever SEALED segment it landed in until that segment is
    // retired, so the raw union is a strict superset. What makes recovery exact is that the
    // union is taken with `Rga.piece`, which merges the floor and the retained `Compact`s the
    // active segment carries and re-purges those ops under them. Every write path preserves the
    // `piece` form: a pass's covering state rides in the active-segment write, and a segment is
    // retired only once that state covers every op it holds.
    //
    // A number leaves `sealedSegments` in exactly one way: it moves to `retiringSegments`,
    // which is the on-disk ledger's in-memory mirror, and is deleted from there. It is never
    // simply forgotten — there is no key-enumeration API, so a segment the index stops naming
    // altogether is unreachable and unsweepable forever. A segment that could not be read at
    // startup is recorded as SegmentContent.Pinned and so keeps its place here, which is what
    // keeps a transient read failure costing one segment's records for one run rather than
    // deleting them.

    private val sealedSegments: MutableList<Int> = mutableListOf()

    /**
     * What is positively known about each sealed segment's content — see [SegmentContent].
     *
     * A number **absent** from this map is a segment nothing is known about. [retirableSegments]
     * iterates *these entries*, never [sealedSegments], so an unknown segment is not a candidate
     * at all rather than a candidate whose (missing, therefore empty) content trivially passes
     * every test.
     */
    private val sealedContents: MutableMap<Int, SegmentContent> = mutableMapOf()

    /**
     * The sweep ledger — segments already retired out of [sealedSegments] whose keys are not
     * yet confirmed deleted. Mirrors [LogSegmentIndex.retired]; see it for the crash argument.
     */
    private val retiringSegments: MutableList<Int> = mutableListOf()
    private var activeSegment: Rga<LogRecord> = Rga.empty()
    private var activeNumber: Int = 0
    private var activeOpCount: Int = 0
    private var nextSegmentNumber: Int = 1

    /**
     * Whether the store holds an index matching [sealedSegments]/[activeNumber].
     *
     * Cleared on any failed turn, so the next attempt rewrites it: a partially
     * applied turn can have left the index naming segments that were never written.
     */
    private var indexPersisted: Boolean = false

    private val healthState = MutableStateFlow(ExporterHealth())

    /**
     * Out-of-band health for this exporter — see [ExporterHealth].
     *
     * `export()` reports a failed durable write in its return value, but on the
     * logging path every caller discards it, so a stalled exporter had no way to
     * say so (#1860). Read [kotlinx.coroutines.flow.StateFlow.value] for a
     * point-in-time answer to "has this accepted anything since process start?",
     * or collect the flow to alarm on a stall.
     */
    public val health: StateFlow<ExporterHealth> = healthState.asStateFlow()

    private companion object {
        /** The pre-#1860 single-blob key. Read once, migrated, then deleted forever. */
        private val LEGACY_KEY = StoreKey("otel.logs")

        /** The segment the legacy blob is adopted into, verbatim, by the migration. */
        private const val LEGACY_SEGMENT = 0

        private val INDEX_KEY = StoreKey("otel.logs.idx")
        private const val SEGMENT_KEY_PREFIX = "otel.logs.seg."
        private val cbor = Cbor { alwaysUseByteString = true }
        private val logSerializer = Rga.wireSerializer(LogRecord.serializer())
        private val indexSerializer = LogSegmentIndex.serializer()

        private fun segmentKey(number: Int) = StoreKey("$SEGMENT_KEY_PREFIX$number")

        /**
         * The most ops one record can add to the active segment: its `Insert`, plus the
         * `Remove` of the record it evicted under [BufferPolicy.DROP_OLDEST].
         */
        private const val OPS_PER_RECORD = 2

        /**
         * How many drops-plus-refusals between summary lines — one line per buffer-worth of
         * churn at [DEFAULT_MAX_LOG_RECORDS].
         *
         * Coarse on purpose: the *number* is the signal and it lives on [health]; this line only
         * has to be frequent enough to be noticed and rare enough not to become the thing being
         * reported.
         */
        private const val DROP_REPORT_INTERVAL = 10_000L
    }

    /** One durable-store mutation. */
    private sealed interface StoreAction {
        /** Write [bytes] under [key]. */
        class Put(val key: StoreKey, val bytes: ByteArray) : StoreAction

        /**
         * The **commit point** of a retirement: write [bytes] — an index naming [numbers] under
         * [LogSegmentIndex.retired] — and, only once that write has *returned*, move [numbers]
         * out of [sealedSegments]/[sealedContents] and onto [retiringSegments].
         *
         * The in-memory move being an effect of this action rather than of building the turn is
         * what upholds the one invariant the delete path rests on: **a number appears in an
         * on-disk `retired` list only after a write carrying its covering state was confirmed
         * durable.** [retiringSegments] is what every later [encodeIndex] reads, so a move applied
         * while the turn was still being built would let the *next* turn's leading index write —
         * which is emitted **before** that turn's [activeSegmentWrite] — publish a retirement
         * whose covering write never landed. [loadPersistedState] then sweeps it with no check,
         * and records a recovery could still have read are gone.
         *
         * That is not a theoretical window. A quota-bound store fails **large** writes while small
         * ones succeed (`IndexedDbDurableStore` does): the segment blob is ~123 KB at
         * [DEFAULT_LOG_SEGMENT_OPS] and the index is tiny, so such a store refuses exactly the
         * covering write and accepts exactly the ledger write, indefinitely.
         */
        class CommitRetirement(val bytes: ByteArray, val numbers: List<Int>) : StoreAction

        /**
         * The **commit point** of a roll: write [bytes] — an index naming [sealing] under
         * [LogSegmentIndex.sealedSegments] and [opening] as [LogSegmentIndex.active] — and, only
         * once that write has *returned*, apply the same move in memory ([applyRoll]).
         *
         * Same shape and same reason as [CommitRetirement]: a layout change becomes real in
         * memory only after the write that publishes it has returned. The write it depends on is
         * this turn's [activeSegmentWrite], queued and applied earlier, and [commit] stops at
         * the first failure — so a refused segment write leaves the segment **un-sealed** and the
         * next turn rewrites the same key.
         *
         * Sealing while the turn was still being *built* was wrong twice, and both harms are
         * reachable through the same store shape [CommitRetirement] names — a quota-bound store
         * that refuses **large** writes while small ones succeed:
         *
         * - **A stranded `Compact`.** A roll fires inside a *retiring* turn only when a foreign
         *   author's dots forced the pass to mint an [RgaOp.Compact] (this replica's own dots fold
         *   into the floor and only shrink the segment). In exactly that turn, a refused
         *   [activeSegmentWrite] leaves the segment sealed in memory as [SegmentContent.Pinned]
         *   while its key on disk still holds the *pre-pass* bytes without the `Compact` — and
         *   nothing ever rewrites a sealed key. In-memory `log.compactedIds` still covers those
         *   foreign ids, so a later pass judges the merge-adopted segment holding their `Insert`s
         *   retirable and sweeps it. Neither the ops nor anything suppressing them survives the
         *   restart, and a peer re-admits them on the next [merge] —
         *   `droppingTheSegmentCarryingACompactReAdmitsAForeignAuthorsRecord`'s harm, reached
         *   without deleting anything a rule forbids deleting.
         * - **Permanently pinned phantom segments.** Under sustained segment-write refusal the
         *   small index write keeps succeeding while every segment write fails, so each
         *   [segmentOps] operations sealed a number whose key was never written. On the next start
         *   [readSegment] returns `null`, the segment is recorded [SegmentContent.Pinned], and it
         *   is never retirable — **forever, on every subsequent start**. A transient condition
         *   made the bounded-key-count claim permanently false.
         */
        class CommitRoll(
            val bytes: ByteArray,
            val sealing: Int,
            val content: SegmentContent,
            val opening: Int,
        ) : StoreAction

        /**
         * Best-effort delete of retired segment [number]'s key.
         *
         * Always ordered **after** the [CommitRetirement] whose index named [number] under
         * [LogSegmentIndex.retired] — earlier in this same turn, or in an earlier turn (or an
         * earlier *process*) for a number whose delete was refused and is being retried. That
         * ledger write is itself ordered after the [activeSegmentWrite] carrying the state that
         * supersedes [number], and [commit] applies a turn's actions strictly in order and stops
         * at the first failed write, so both orderings are enforced by construction rather than by
         * remembering to keep them.
         *
         * The startup counterpart in [loadPersistedState] deletes with no ordering of its own —
         * it sweeps [LogSegmentIndex.retired] before it reads anything. It is sound for a
         * different reason, one turn's ordering alone would not give it: [CommitRetirement] is the
         * only thing that can **extend** that list, so anything the next process finds there was
         * already covered by a write that landed first. (Every other index write *restates* the
         * list — a leading `Put(INDEX_KEY, encodeIndex())` writes those same numbers out again,
         * which is free; it is the number appearing for the first time that has to be covered.)
         */
        class Sweep(val number: Int) : StoreAction
    }

    /**
     * The retirement half of one turn: the actions that carry it to disk, and the numbers those
     * actions will move onto the ledger **if** the ledger write lands.
     *
     * [staged] is deliberately not applied to any field here. It is threaded to the other index
     * write a turn can carry ([rollActiveSegment]), which has to project it — that write is
     * encoded while the turn is built, so without the projection it would name a segment the
     * [StoreAction.CommitRetirement] queued just above it has already retired, undoing the ledger.
     */
    private class PendingRetirement(val staged: List<Int>, val actions: List<StoreAction>) {
        companion object {
            val NONE = PendingRetirement(emptyList(), emptyList())
        }
    }

    /**
     * What this exporter positively knows about one sealed segment's content.
     *
     * Retirement **deletes a key**, so it may act only on positive evidence that every record
     * the segment holds is already superseded. This type exists so that "read, and it holds
     * nothing" and "never read" are *different values* rather than the same empty collection.
     * A `sealedContents[n].orEmpty().all { … }` test is vacuously `true` for a segment whose
     * content could not be read, which would turn a transient I/O error into the permanent
     * destruction of a user's telemetry — and there is no `orEmpty()` to write here, because
     * [retirableSegments] draws its candidates from this map's entries rather than looking
     * numbers up in it.
     */
    private sealed interface SegmentContent {
        /**
         * The segment was read (or authored) **in full**: [ids] is every `Insert`/`Remove` id it
         * holds, and it carries no [RgaOp.Compact]. The only case retirement can act on.
         *
         * An empty [ids] means "read, and it holds no records" — genuinely retirable, and a
         * different *value* from having no entry at all. Keeping those two apart is the whole
         * point of the type.
         */
        class Ids(val ids: Set<RgaId>) : SegmentContent

        /**
         * Positively decided **not** retirable, for the lifetime of this exporter instance.
         * Two reasons reach it, and both mean keep:
         *
         * - the segment carries an [RgaOp.Compact], which nothing prunes and whose loss would
         *   revoke [Rga]'s "once compacted, always compacted" guarantee; or
         * - its content could not be read or summarised, and unknown content must mean keep.
         *
         * Holds no ids on purpose: a segment retirement can never act on must not pin an id set
         * in memory for the life of the process.
         */
        data object Pinned : SegmentContent
    }

    /** Everything [recover] reconstructs from the store, before it is installed. */
    private class RecoveredState(
        val log: Rga<LogRecord>,
        val sealedSegments: List<Int>,
        val sealedContents: Map<Int, SegmentContent>,
        /** Ledger entries whose delete has **not** yet been confirmed; still owed. */
        val ledger: List<Int>,
        /** Whether the decoded index named any [LogSegmentIndex.retired] number at all. */
        val ledgerDirty: Boolean,
        val activeNumber: Int,
        val activeSegment: Rga<LogRecord>,
        val nextSegmentNumber: Int,
    )

    /**
     * Recover persisted log state from [store]. Call once at startup, before any call to
     * [export] **or [merge]**, and never concurrently with either.
     *
     * Rebuilds the dedup map from the op-log so that re-export of previously
     * persisted records remains a no-op after a process restart.
     *
     * If no persisted state exists, the exporter starts with an empty log.
     *
     * Deliberately **not** serialized by [writeMutex], unlike [export] and [merge]. Mutual
     * exclusion here would order the store writes while suggesting a safety it cannot deliver: an
     * un-recovered exporter's segment numbering starts at its construction defaults, so a [merge]
     * that runs before this returns allocates a segment number the persisted index already uses
     * and overwrites a live key — whichever order the two are serialized in. What makes a
     * pre-recovery call unsafe is the numbering, not the interleaving, so the fix is the contract
     * on this line rather than a lock.
     *
     * **Never throws.** An unreadable store or an undecodable entry degrades this
     * exporter to "start fresh" rather than propagating. The caller installs log
     * capture *after* awaiting this, so a propagating failure here means capture
     * is never installed at all: the exporter goes silently dead for the whole
     * process lifetime, on every launch, with nothing written and nothing
     * logged (#1860).
     */
    public suspend fun recover() {
        val recovered = runCatchingCancellable { loadPersistedState() }.getOrElse { cause ->
            logger.error(cause) { "otel.logs: persisted state could not be read, starting fresh" }
            recordRecoveryFailure(cause)
            return
        } ?: return
        // The in-memory swap is guarded too: walking the recovered CRDT to derive
        // tail/visibleCount/seenIds must not propagate out of recover() any more
        // than the reads or the decodes may. The walk happens BEFORE the lock is
        // taken — it is a pure function of `recovered` and touches no field — so a
        // throw cannot leave `log` swapped with stale derived state. What runs
        // under the lock is only the assignment, which cannot throw. Segmented
        // recovery does strictly more work per key than the single blob did, so
        // keeping the split matters more here, not less.
        runCatchingCancellable {
            val entries = recovered.log.entries()
            lock.withLock { install(recovered, entries) }
        }.onFailure { cause ->
            logger.error(cause) { "otel.logs: recovered state could not be installed, starting fresh" }
            // Start fresh on the RECORDS, never on the numbering. Leaving the segment
            // numbers at their construction defaults would have the next export write an
            // index naming only segment 0 and overwrite it, orphaning every other segment
            // permanently — there is no key-enumeration API to find them again. Adopting
            // the index's numbering and opening a segment beyond it keeps every existing
            // segment named and untouched, so the next clean start still recovers them.
            lock.withLock { adoptNumberingOnly(recovered) }
            recordRecoveryFailure(cause)
        }
    }

    /**
     * Read the segments the index names and union them back into one op-log, or
     * migrate the legacy single-blob entry if this is the first start on the
     * segmented format. Returns `null` when nothing has ever been persisted.
     *
     * [Rga.piece] is set union, so the order the segments are absorbed in does not
     * matter and absorbing one twice is harmless — which is what makes a partition
     * of the op-log across keys an exact, order-independent reconstruction.
     */
    private suspend fun loadPersistedState(): RecoveredState? {
        val indexBytes = store.read(INDEX_KEY) ?: return migrateLegacyBlob()
        val index = cbor.decodeFromByteArray(indexSerializer, indexBytes)
        sweepLegacyKey()
        // Finish any retirement a crash interrupted between the ledger write and the delete.
        // Idempotent: a key already gone deletes again as a no-op.
        val outstanding = index.retired.filterNot { number -> sweep(number) }

        // Nothing past this point may throw. Once the index has decoded, its numbering
        // is the only thing standing between a restart and renumbering onto segments
        // that are still live — so every per-segment step is guarded individually and
        // the segment list is adopted from the index verbatim, read failures included.
        var merged = Rga.empty<LogRecord>()
        val contents = mutableMapOf<Int, SegmentContent>()
        for (number in index.sealedSegments) {
            val segment = readSegment(number)
            val absorbed = if (segment == null) null else absorb(merged, segment, number)
            // Unknown content must mean KEEP, so this records Pinned rather than leaving a
            // lookup miss behind: a segment that could not be read must not be retirable, and
            // it stays named by the index so the next clean start can recover it. A segment whose
            // `absorb` threw is exactly as unknown — its ops are not in the union this run's
            // suppression state will be judged against, so recording its ids would let a later
            // pass find them all "covered" and delete a segment nothing here ever read.
            contents[number] = if (segment == null || absorbed == null) {
                SegmentContent.Pinned
            } else {
                contentOf(segment, number)
            }
            if (absorbed != null) merged = absorbed
        }
        val active = readSegment(index.active) ?: Rga.empty()
        return RecoveredState(
            log = absorb(merged, active, index.active) ?: merged,
            sealedSegments = index.sealedSegments,
            sealedContents = contents,
            ledger = outstanding,
            ledgerDirty = index.retired.isNotEmpty(),
            activeNumber = index.active,
            activeSegment = active,
            nextSegmentNumber = maxOf(index.next, index.active + 1),
        )
    }

    /**
     * Delete the legacy key, which the presence of an index proves is superseded.
     *
     * Guarded on its own: a failed delete of known garbage must never cost the log.
     * This runs on every start, so a store whose `delete` throws would otherwise fail
     * recovery forever, on every launch — the exact silent-death shape #1860 is about.
     */
    private suspend fun sweepLegacyKey() {
        runCatchingCancellable { store.delete(LEGACY_KEY) }.onFailure { cause ->
            logger.warn(cause) { "otel.logs: superseded legacy entry could not be deleted; retrying next start" }
        }
    }

    /**
     * Union [segment] into [into], or `null` if the union threw — so the caller can tell "absorbed
     * nothing" apart from "absorbed and it added nothing", which retirement has to know.
     */
    private fun absorb(into: Rga<LogRecord>, segment: Rga<LogRecord>, number: Int): Rga<LogRecord>? =
        runCatchingCancellable { into.piece(segment) }.getOrElse { cause ->
            logger.warn(cause) { "otel.logs: segment $number could not be absorbed, dropping its records" }
            null
        }

    /**
     * Read one segment, or `null` if the store cannot supply or decode it.
     *
     * Absence is **expected**, not corruption: the index is written before the
     * segment it allocates, so a crash in that window leaves the index naming a
     * segment that was never written.
     *
     * The [DurableStore.read] is inside the guard, not outside it. Recovery now
     * performs N reads where it used to perform one, so a single transient I/O error
     * on any one of them would otherwise fail the whole recovery — and a failed
     * recovery leaves the numbering at its construction defaults, so the next export
     * would write an index naming only segment 0 and overwrite it, orphaning every
     * other segment permanently in a format with no key-enumeration API to sweep them.
     * One bad read must cost one segment's records, which is what this KDoc has always
     * promised.
     */
    private suspend fun readSegment(number: Int): Rga<LogRecord>? =
        runCatchingCancellable {
            val bytes = store.read(segmentKey(number))
            if (bytes == null) {
                logger.debug { "otel.logs: segment $number is named by the index but absent" }
                null
            } else {
                cbor.decodeFromByteArray(logSerializer, bytes)
            }
        }.getOrElse { cause ->
            logger.warn(cause) { "otel.logs: segment $number is unreadable, dropping its records" }
            null
        }

    /**
     * One-time migration off the pre-#1860 single-blob `otel.logs` key.
     *
     * The blob is adopted **verbatim** as segment [LEGACY_SEGMENT] and sealed, and a
     * fresh active segment is opened beside it — so the very next [export] writes only
     * that small new segment instead of rewriting the inherited log. It is deliberately
     * *not* split into [segmentOps]-sized pieces: splitting needs the individual ops,
     * and re-deriving them from the visible records would mint **new** op identities,
     * which would duplicate every record the next time this replica merged with a peer.
     *
     * The inherited segment is retired like any other **unless it carries an `RgaOp.Compact`**
     * — a pre-#1860 build that ever merged a compacted peer left one in its blob — in which
     * case it is pinned on disk forever, because dropping it would revoke [Rga]'s "once
     * compacted, always compacted" guarantee for the ids it names.
     *
     * Crash-safety rests on the write order: the legacy key stays authoritative until
     * the index — the commit point — is on disk. A crash before that leaves both, and
     * the next start re-runs the whole migration idempotently; a crash after it leaves
     * a stale legacy key that [loadPersistedState] deletes.
     */
    private suspend fun migrateLegacyBlob(): RecoveredState? {
        val bytes = store.read(LEGACY_KEY) ?: return null
        val recovered = cbor.decodeFromByteArray(logSerializer, bytes)
        store.write(segmentKey(LEGACY_SEGMENT), bytes)
        val index = LogSegmentIndex(
            sealedSegments = listOf(LEGACY_SEGMENT),
            active = LEGACY_SEGMENT + 1,
            next = LEGACY_SEGMENT + 2,
        )
        store.write(INDEX_KEY, cbor.encodeToByteArray(indexSerializer, index))
        store.delete(LEGACY_KEY)
        logger.info {
            "otel.logs: migrated the legacy single-blob entry (${bytes.size} bytes, " +
                "${recovered.size} visible records) into segment $LEGACY_SEGMENT"
        }
        return RecoveredState(
            log = recovered,
            sealedSegments = listOf(LEGACY_SEGMENT),
            sealedContents = mapOf(LEGACY_SEGMENT to contentOf(recovered, LEGACY_SEGMENT)),
            ledger = emptyList(),
            ledgerDirty = false,
            activeNumber = index.active,
            activeSegment = Rga.empty(),
            nextSegmentNumber = index.next,
        )
    }

    /**
     * Keep [recovered]'s segment numbering but none of its records, after the records
     * could not be installed. A **fresh** segment is opened past everything the index
     * named, so no existing segment is written to. Must hold [lock].
     */
    private fun adoptNumberingOnly(recovered: RecoveredState) {
        sealedSegments.clear()
        sealedSegments.addAll(recovered.sealedSegments.distinct())
        if (recovered.activeNumber !in sealedSegments) sealedSegments += recovered.activeNumber
        // Records were NOT installed, so nothing is known about any segment's content and every
        // one of them is un-retirable for this run. Said as a map full of Pinned rather than as an
        // empty one: "absence is never a candidate" is a property of how retirableSegments happens
        // to iterate, whereas an explicit Pinned per sealed number is a property of the data.
        installSealedContents(emptyMap())
        retiringSegments.clear()
        retiringSegments.addAll(recovered.ledger)
        nextSegmentNumber = recovered.nextSegmentNumber
        activeNumber = nextSegmentNumber++
        activeSegment = Rga.empty()
        activeOpCount = 0
        indexPersisted = false
    }

    /** Install a [RecoveredState] and its already-materialized entries. Must hold [lock]. */
    private fun install(recovered: RecoveredState, entries: List<Pair<RgaId, LogRecord>>) {
        log = recovered.log
        sealedSegments.clear()
        sealedSegments.addAll(recovered.sealedSegments.distinct())
        installSealedContents(recovered.sealedContents)
        retiringSegments.clear()
        retiringSegments.addAll(recovered.ledger)
        activeSegment = recovered.activeSegment
        activeNumber = recovered.activeNumber
        activeOpCount = opCountOf(recovered.activeSegment)
        nextSegmentNumber = recovered.nextSegmentNumber
        // A ledger the recovered index named is stale the moment its sweep lands, so leave the
        // index dirty and let the next turn rewrite it without the confirmed deletions.
        indexPersisted = !recovered.ledgerDirty
        installDerivedState(entries)
    }

    /**
     * Refill [sealedContents] so its keys are **exactly** [sealedSegments], taking what [known]
     * supplies and [SegmentContent.Pinned] for the rest. Must hold [lock], after
     * [sealedSegments] is set.
     *
     * The pairing is what makes retirement's vacuity hazard unrepresentable rather than merely
     * unwritten — see [retirableSegments]. Both recovery paths run through here so neither can
     * leave a sealed segment with no entry: an absent entry is the shape a future
     * `sealedContents[n] ?: SegmentContent.Ids(emptySet())` would read as "empty, therefore
     * retirable", and that reading deletes a user's telemetry on a transient read error.
     */
    private fun installSealedContents(known: Map<Int, SegmentContent>) {
        sealedContents.clear()
        sealedSegments.forEach { number -> sealedContents[number] = known[number] ?: SegmentContent.Pinned }
    }

    /**
     * Export one log record — the degenerate one-element case of [export].
     *
     * See that overload for the full contract; nothing about durability differs.
     * A one-record call still returns after its own durable write.
     */
    public suspend fun export(record: LogRecord): ExportResult = export(listOf(record))

    /**
     * Export a **batch** of log records: append them to the [Rga] and durably flush
     * to [store] as one write turn.
     *
     * Returns [ExportResult.Success] after the durable write, exactly as the
     * single-record overload does — **the durability contract is unchanged**. This is
     * not OpenTelemetry's `BatchLogRecordProcessor`, which trades a flush window for
     * amortisation; nothing here is held back in the hope more arrives. A batch is
     * whatever the caller already has in hand, and it is written before this returns.
     * What is amortised is the *fixed* cost of a turn — one CRDT append pass, one CBOR
     * encode of the active segment, one segment write — across however many records the
     * caller supplied, instead of paying it once per record (#2194).
     *
     * Records are admitted **in order**, and dedup and the buffer cap stay per-record
     * decisions: a [LogRecord.recordId] already exported (including earlier in this
     * same batch, and including across restarts after [recover]) is skipped, and a
     * record refused by the cap under [BufferPolicy.DROP_NEWEST] is not inserted.
     * Neither counts towards [ExporterHealth.accepted], which means *records durably
     * taken*.
     *
     * ## A batch may span more than one turn
     *
     * [segmentOps] bounds how many bytes one export rewrites, so a batch that would
     * overfill the active segment is split: each turn takes as many records as still
     * fit, writes, rolls, and the next turn continues. (The bound is *approximate* by
     * exactly one op: a [windowPass] runs after the records are admitted and can `piece`
     * one [RgaOp.Compact] into the active segment outside the turn's budget, so a turn
     * can end at `segmentOps + 1` before the roll seals it. That overshoot exists on the
     * per-record path today, and is bounded at one op either way.)
     *
     * Splitting means the batch is **not atomic**: an earlier turn's records are durable
     * even if a later one fails.
     *
     * **Every turn is attempted, even after one fails.** A failing turn has already
     * admitted its records to the in-memory log and to [activeSegment] before its write
     * was refused, so those records are *not* lost — the next successful
     * active-segment write carries them to disk (the same property
     * [retirableSegments] relies on). Abandoning the remainder of a batch would
     * therefore lose records that looping the single-record overload keeps: a
     * quota-bound store that refuses the ~123 KB segment write while accepting small
     * ones would drop everything after the first failed turn, permanently, on every
     * batch, for as long as the condition lasts. So the loop runs to the end and the
     * **first** [ExportResult.Failure] is returned once it does.
     *
     * A turn also never admits more than [maxRecords] records, so the eviction it
     * computes is always a prefix of what the buffer already held.
     *
     * An empty [records] is [ExportResult.Success] and touches neither the log nor the
     * store.
     *
     * **Never throws.** Every failure — a throwing [store], and also a failure inside
     * the in-memory CRDT insert, the eviction, or the CBOR encode — is returned as
     * [ExportResult.Failure] and reflected on [health]. The store is the failure this
     * method was originally written for, but it is not the only one reachable, and a
     * caller on the logging path cannot handle a thrown exception: it would surface
     * inside an application's own logging call (#1860).
     *
     * @sample us.tractat.kuilt.otel.sampleBulkExport
     */
    public suspend fun export(records: List<LogRecord>): ExportResult {
        var from = 0
        var firstFailure: ExportResult.Failure? = null
        while (from < records.size) {
            val outcome = writeMutex.withLock { exportTurn(records, from) }
            val result = outcome.result
            if (firstFailure == null && result is ExportResult.Failure) firstFailure = result
            from += outcome.consumed
        }
        return firstFailure ?: ExportResult.Success
    }

    /** How far one [exportTurn] got, and how it ended. */
    private class TurnOutcome(val consumed: Int, val result: ExportResult)

    /**
     * One write turn over `records[from until …]`: decide the run, mutate the log
     * twice, build the turn's actions, then apply them. Must hold [writeMutex].
     *
     * Always consumes **at least one** record — otherwise [export]'s loop would not
     * terminate — even when the active segment is already at or past [segmentOps],
     * which a [recover] against a store written with a larger [segmentOps] can produce.
     */
    private suspend fun exportTurn(records: List<LogRecord>, from: Int): TurnOutcome {
        var consumed = 0
        var accepted = 0
        val actions = runCatchingCancellable {
            lock.withLock {
                val admitted = ArrayList<LogRecord>()
                // Dedup within the batch as well as against the log: `seenIds` is not
                // updated until the inserts are minted below, so two copies of one id
                // inside a single batch would otherwise both be admitted.
                val pending = HashSet<ByteString>()
                var evictions = 0
                while (from + consumed < records.size && fitsInTurn(consumed, evictions)) {
                    val record = records[from + consumed]
                    consumed++
                    if (record.recordId in seenIds || !pending.add(record.recordId)) continue
                    if (visibleCount + admitted.size - evictions < maxRecords) {
                        admitted += record
                        continue
                    }
                    // The `when` is exhaustive on purpose — a new [BufferPolicy] constant
                    // must not fall through to eviction on a public enum shared with
                    // [WarpSpanExporter].
                    when (bufferPolicy) {
                        // DROP_NEWEST refuses the incoming record, which is what "drop the
                        // newest" means here: at a full buffer the newest record is the one
                        // arriving. So this replica never authors a `Remove`, and the ops it
                        // contributes to the shared op-log are a downward-closed prefix — the
                        // property #2127's bound relies on for this policy. It bounds what is
                        // *emitted*, not what the buffer holds: [merge] can still push
                        // [visibleCount] past [maxRecords], after which this gate simply keeps
                        // refusing.
                        BufferPolicy.DROP_NEWEST -> {
                            recordRefusal()
                            pending.remove(record.recordId)
                        }
                        BufferPolicy.DROP_OLDEST -> {
                            evictions++
                            admitted += record
                        }
                    }
                }
                accepted = admitted.size
                applyTurn(admitted, evictions)
                // Before pendingWrites(), never after: a pass rewrites `activeSegment`,
                // and the active-segment write pendingWrites() already owes is what
                // carries the resulting floor to disk.
                val windowed = windowPassDue() && windowPass()
                // A turn that changed nothing owes nothing. The single-record path
                // returns EARLY on a dedup hit and on a DROP_NEWEST refusal, so both
                // cost ZERO store writes — a property export()'s KDoc states outright,
                // and one a batched turn silently drops unless it is restored here.
                // Without this guard a DROP_NEWEST exporter at a full buffer rewrites
                // the whole ~123 KB active segment on every drain cycle while accepting
                // nothing, forever; and an anti-entropy caller re-exporting an
                // already-exported page pays a segment rewrite per turn for a no-op.
                if (admitted.isEmpty() && evictions == 0 && !windowed) {
                    emptyList()
                } else {
                    pendingWrites(retire = windowed)
                }
            }
        }.getOrElse { cause ->
            logger.error(cause) {
                "WarpLogRecordExporter: buffer update failed for a batch of ${records.size - from} record(s) " +
                    "starting at ${records.getOrNull(from)?.recordId}"
            }
            return TurnOutcome(consumed = maxOf(consumed, 1), result = failure(cause))
        }
        // Nothing to write, so nothing to report: an all-dedup or all-refused turn is
        // Success with no durable write and no movement on `accepted`, exactly as the
        // single-record path's two early returns were.
        if (actions.isEmpty()) return TurnOutcome(consumed = consumed, result = ExportResult.Success)
        val result = commit(actions) { cause ->
            logger.error(cause) {
                "WarpLogRecordExporter: durable write failed for a batch of $accepted record(s)"
            }
        }
        return TurnOutcome(
            consumed = consumed,
            result = if (result is ExportResult.Success) success(accepted) else result,
        )
    }

    /**
     * Whether a turn that has already taken [consumed] records and owes [evictions]
     * tombstones may take one more without breaching [segmentOps]. Must hold [lock].
     *
     * A record costs at most two ops in the active segment — its `Insert`, plus the
     * `Remove` of whatever it evicted — so the bound is checked against the worst case
     * rather than the actual eviction count, which is not known until the record has
     * been admitted. That makes the turn slightly conservative while the buffer is
     * still filling (no evictions yet, so the segment ends half-full and the next turn
     * continues into it); it never makes it wrong.
     *
     * Also caps a turn at [maxRecords] records, which is what lets [applyTurn] evict a
     * *prefix* of the existing buffer rather than having to evict records the same turn
     * just inserted. That cap is what makes [Rga.removeFirst]'s `require` unreachable
     * from here: the last eviction a turn can owe needs `admitted >= maxRecords - 1`
     * while `consumed < maxRecords`, which forces the eviction count to stop exactly at
     * the pre-turn [visibleCount].
     *
     * **The bound is on the record-driven ops only.** A [windowPass] runs *after* the
     * records are admitted and `piece`s its delta into [activeSegment], which can mint
     * one [RgaOp.Compact] outside this budget — so a turn can end at `segmentOps + 1`
     * before [flushActiveSegment] rolls it. That overshoot is bounded at one op and
     * exists identically on the per-record path today; it is named here so the
     * "[segmentOps] is a ceiling" claim is not read as exact.
     */
    private fun fitsInTurn(consumed: Int, evictions: Int): Boolean {
        if (consumed >= maxRecords) return false
        if (consumed == 0) return true
        return activeOpCount + consumed + evictions + OPS_PER_RECORD <= segmentOps
    }

    /**
     * Apply one turn's decisions to [log] in **two** CRDT mutations — the whole point
     * of the batch. Must hold [lock].
     *
     * Eviction runs first and always removes a prefix of what the buffer already held,
     * so nothing this turn inserts can be evicted by it ([fitsInTurn] caps the turn at
     * [maxRecords]). Both halves go through [Rga]'s bulk mutators, so each pays one
     * `ops + newOps` copy and one cache build for the run instead of one per record,
     * and the RGA sequence is materialised once rather than once per eviction.
     *
     * ## Two ways this is not *bit*-identical to the per-record loop
     *
     * Both are unreachable at the production [DEFAULT_MAX_LOG_RECORDS] and neither
     * changes the visible sequence, but "indistinguishable from the loop" is exact at
     * the [Rga] level and only *observationally* exact here, so they are written down.
     *
     * - **Evictions that empty the buffer re-root the run.** When a turn's evictions
     *   take the whole pre-turn buffer, [evictLeading] sets `tail = RgaId.HEAD` before
     *   the inserts, so the run chains after HEAD where the loop would have chained
     *   after the tombstoned predecessor. Same visible order, different `after` links in
     *   the op-log. Reachable only when a turn is as large as the buffer, i.e.
     *   `maxRecords` at or below a turn's size — test-scale configuration, not the
     *   10,000-record default.
     * - **A record re-arriving in the same turn that evicts it is skipped.** [seenIds]
     *   is read at admission time, before this function runs, so a batch containing a
     *   record whose id the same turn is about to evict finds it still present and skips
     *   it; the loop would evict first and then re-admit. Contrived, and arguably the
     *   better answer.
     */
    private fun applyTurn(admitted: List<LogRecord>, evictions: Int) {
        if (evictions > 0) evictLeading(evictions)
        if (admitted.isEmpty()) return
        val (newLog, inserts) = log.insertAllAfter(replica = replica, after = tail, values = admitted)
        log = newLog
        tail = inserts.last().id
        visibleCount += inserts.size
        inserts.forEach { insert -> seenIds[insert.value.recordId] = insert.id }
        appendToActiveSegment(inserts)
    }

    /**
     * Tombstone the [count] oldest visible records, counting each. Must hold [lock].
     *
     * Every drop is **counted** on [ExporterHealth.dropped] — exactly, per record, with a
     * rate-limited summary line for a reader who never polls health ([reportDropsPeriodically]).
     * It is no longer logged individually, and the per-record `recordId`/`body` correlation
     * against a backend's log index is deliberately gone with it: at [DEFAULT_MAX_LOG_RECORDS]
     * the buffer is full permanently, so *every* exported record evicts one and that line was a
     * per-record narration of a ring buffer doing what it is configured to do — on the export
     * hot path, where it was measured (#2218).
     *
     * The ids are read **before** the removal, off the instance whose [Rga.sequence]
     * `removeFirst` is about to walk, so the lazy is computed once for both.
     */
    private fun evictLeading(count: Int) {
        // The leading `count` VISIBLE ids, taken off `sequence` LAZILY. [Rga.entries] would
        // build two eager Θ(N) lists here and then discard all but `count` of them — ≈0.18 ms
        // per record at [DEFAULT_MAX_LOG_RECORDS], measured on an iPhone XS (#2219). `sequence`
        // is already warm on this instance: `removeFirst` below walks the same lazy.
        //
        // This is Θ(leading tombstones + count), NOT Θ(count): eviction tombstones accumulate
        // at the HEAD of `sequence`, and [windowPassDue] only clears them once per [maxRecords]
        // evictions, so the walk skips up to that many before it finds the first visible id.
        // Still strictly less than the full pass it replaces, and one Θ(N) list allocation
        // rather than three — `removeFirst` retains the third (its `visibleSequence()`), which
        // is why this cuts the term rather than removing it.
        val tombstoned = log.tombstones
        val evicted = log.sequence.asSequence()
            .filter { id -> id !in tombstoned }
            .take(count)
            .map { id -> log.valueAt(id) }
            .toList()
        healthState.update { it.copy(dropped = it.dropped + count) }
        reportDropsPeriodically()
        val (newLog, removes) = log.removeFirst(count)
        log = newLog
        // The tombstones are ops like any other, so they ride in the active segment.
        // They hide the records; they reclaim nothing by themselves. Each evicted
        // record's own `Insert` — body and all — stays in whichever segment it landed
        // in until a window pass suppresses it and that segment is retired.
        appendToActiveSegment(removes)
        evicted.forEach { record -> seenIds.remove(record.recordId) }
        visibleCount -= count
        evictionsSincePass += count
        // DROP_OLDEST removes a leading prefix of the visible sequence. With at least
        // one element still standing the first and last visible elements are distinct,
        // so `tail` is untouched; at zero there is nothing left to append after.
        if (visibleCount == 0) tail = RgaId.HEAD
    }

    /**
     * Count a [BufferPolicy.DROP_NEWEST] refusal. Must hold [lock].
     *
     * Named for what it *does* rather than for the decision it records: it no longer logs, and a
     * name like `refuse` reads as the refusal itself and invites a log line back onto the path
     * this exists to keep quiet. The count lands on [ExporterHealth.refused]; the periodic
     * summary is shared with eviction because both are the same cap doing the same job.
     */
    private fun recordRefusal() {
        healthState.update { it.copy(refused = it.refused + 1) }
        reportDropsPeriodically()
    }

    /**
     * Say out loud, at most once per [DROP_REPORT_INTERVAL] drops, that the buffer is
     * recycling — so the loss is not silent for a consumer who never reads [health].
     * Must hold [lock].
     *
     * Counted rather than timed: this type owns no [kotlin.time.Clock], and a wall-clock
     * read per eviction is exactly the kind of per-record cost this change exists to remove
     * (the same argument [ExporterHealth]'s KDoc already makes about `lastFailure`). The
     * interval is deliberately coarse — the *number* is the signal and it is on [health];
     * this line only has to be frequent enough to be noticed and rare enough not to be the
     * thing being reported.
     *
     * Level is `info`, not `warn`: a cap behaving exactly as configured is not a warning.
     */
    private fun reportDropsPeriodically() {
        // ONE read of the flow — both fields off one snapshot, so the bucket cannot straddle
        // a concurrent update and report a total neither value ever had.
        val health = healthState.value
        val total = health.dropped + health.refused
        val bucket = total / DROP_REPORT_INTERVAL
        if (bucket == lastDropReport) return
        lastDropReport = bucket
        logger.info {
            "WarpLogRecordExporter: buffer cap ($maxRecords) recycling under $bufferPolicy — " +
                "$total record(s) dropped or refused so far. This is the cap doing its job; read " +
                "ExporterHealth.dropped / .refused for the running totals."
        }
    }

    /**
     * Read a snapshot of the current in-memory [Rga] for gossip / anti-entropy.
     *
     * The returned [Rga] reflects all records exported since the last [recover]
     * or process start, minus any the buffer cap evicted ([BufferPolicy.DROP_OLDEST])
     * or refused ([BufferPolicy.DROP_NEWEST]).
     */
    public fun snapshot(): Rga<LogRecord> = lock.withLock { log }

    /**
     * Merge an [Rga] received from another replica (via anti-entropy / gossip)
     * into this exporter's state, then flush the merged result to [store].
     *
     * Idempotent: merging the same [Rga] twice produces the same result.
     *
     * **Never throws**, on the same terms as [export]: a failure in the CRDT
     * join, the dedup rebuild, the encode, or the [store] is returned as
     * [ExportResult.Failure] and reflected on [health].
     */
    public suspend fun merge(remote: Rga<LogRecord>): ExportResult = writeMutex.withLock { mergeTurn(remote) }

    /** [merge]'s write turn: build the actions, then apply them. Must hold [writeMutex]. */
    private suspend fun mergeTurn(remote: Rga<LogRecord>): ExportResult {
        val actions = runCatchingCancellable {
            lock.withLock {
                log = log.piece(remote)
                // A remote insert can land anywhere, including after the local tail.
                rebuildDerivedState()
                val adopted = adoptRemoteSegment(remote)
                // A merge is the only path that can push the buffer past the cap without
                // evicting (see [exportTurn]), and under DROP_NEWEST it is the ONLY path that can
                // grow the log at all — that policy never evicts, so the eviction counter
                // alone would leave a gossiping DROP_NEWEST exporter unwindowed forever.
                // Unlike export(), nothing here writes the active segment, so a pass has to
                // add that write itself or its floor never reaches disk — and retirement has to
                // follow it, never precede it. The whole tail is shared with [pendingWrites]
                // rather than restated, because the ROLL belongs to it too and this path once
                // omitted it: a pass grows the active segment on both paths (it mints an
                // `RgaOp.Compact` for the foreign dots it took), so a merge-fed replica that
                // never rolled rewrote one ever-growing key on every merge — Θ(merges²) bytes,
                // the exact defect the segmented layout exists to prevent.
                if (windowPassDue() && windowPass()) adopted + flushActiveSegment(retire = true) else adopted
            }
        }.getOrElse { cause ->
            logger.error(cause) { "WarpLogRecordExporter: buffer update failed during merge" }
            return failure(cause)
        }
        val result = commit(actions) { cause ->
            logger.error(cause) { "WarpLogRecordExporter: durable write failed during merge" }
        }
        return if (result is ExportResult.Success) storeSucceeded() else result
    }

    // ── Segmented persistence ──────────────────────────────────────────────────

    /**
     * Apply one turn's store mutations **in order**, and report a *failure* through
     * [health].
     *
     * **Success is the caller's to report, not this function's.** A turn touches
     * several keys and takes an arbitrary number of records through admission, and
     * [ExporterHealth.accepted] counts *records*, not store calls — a count only the
     * caller has ([exportTurn] reports it with the count; [mergeTurn] reports through
     * [storeSucceeded], because a merge takes no records through admission at all). A
     * failure needs no such count: a refused write is a store fact regardless of who
     * called, so [failure] stays here.
     *
     * The order is load-bearing, not incidental: a [StoreAction.Sweep] deletes a segment's
     * records, and it is safe only after the state that supersedes them is durable. Stopping at
     * the first failed write is what enforces that — a turn whose active-segment write or ledger
     * write did not land never reaches its sweeps.
     *
     * **This is also where a layout change becomes real in memory**, and that is what carries the
     * ordering *between* turns — which stopping at the first failure does not, because a failed
     * turn does not stop the process. Both index writes that move the layout are effects applied
     * here rather than while the turn was built:
     *
     * - [StoreAction.CommitRetirement] moves its numbers onto [retiringSegments] only after
     *   `store.write` has returned, so a turn that failed at or before its active-segment write
     *   leaves that field exactly as it found it, and the next turn's **leading** index write —
     *   emitted before its own active-segment write — has nothing uncovered to publish. It is that
     *   invariant, not any within-turn order, that lets [loadPersistedState] sweep
     *   [LogSegmentIndex.retired] before it reads a thing.
     * - [StoreAction.CommitRoll] seals the active segment only after its own write has returned,
     *   so a refused [activeSegmentWrite] leaves the segment un-sealed and the next turn rewrites
     *   the same key — rather than sealing, in memory, a key that on disk lacks the very
     *   [RgaOp.Compact] the pass just minted into it.
     *
     * A failed **sweep**, by contrast, must not fail the export: the record was durably written,
     * and reporting [ExportResult.Failure] because a delete of superseded garbage was refused
     * would make [ExporterHealth.failed] stop meaning "the store is rejecting writes". So sweeps
     * are best-effort ([sweep]) and an unswept number simply stays in the ledger.
     */
    private suspend fun commit(actions: List<StoreAction>, logFailure: (Throwable) -> Unit): ExportResult {
        val swept = mutableListOf<Int>()
        return runCatchingCancellable {
            actions.forEach { action ->
                when (action) {
                    is StoreAction.Put -> store.write(action.key, action.bytes)
                    is StoreAction.CommitRetirement -> {
                        store.write(INDEX_KEY, action.bytes)
                        lock.withLock { applyRetirement(action.numbers) }
                    }
                    is StoreAction.CommitRoll -> {
                        store.write(INDEX_KEY, action.bytes)
                        lock.withLock { applyRoll(action) }
                    }
                    is StoreAction.Sweep -> if (sweep(action.number)) swept += action.number
                }
            }
        }.fold(
            onSuccess = {
                lock.withLock { ledgerSwept(swept, turnFailed = false) }
                ExportResult.Success
            },
            onFailure = { cause ->
                // A half-applied turn can have left the index naming segments that
                // were never written. Rewriting it on the next attempt re-converges.
                lock.withLock { ledgerSwept(swept, turnFailed = true) }
                logFailure(cause)
                failure(cause)
            },
        )
    }

    /**
     * Retire [swept] from the ledger and decide whether the index is still current.
     * Must hold [lock].
     *
     * A confirmed deletion can only leave [LogSegmentIndex.retired] on a **later** index write,
     * so a turn that swept anything leaves the index dirty even though it succeeded. Numbers
     * whose sweep failed stay in [retiringSegments], and therefore stay named on disk, so the
     * next turn — or the next process start — re-attempts them.
     */
    private fun ledgerSwept(swept: List<Int>, turnFailed: Boolean) {
        retiringSegments.removeAll(swept.toSet())
        indexPersisted = !turnFailed && swept.isEmpty()
    }

    /**
     * Apply a retirement whose ledger write has **returned**: move [numbers] off the sealed
     * layout and onto the in-memory ledger. Must hold [lock].
     *
     * The only writer of [retiringSegments] outside recovery, and it runs only from
     * [StoreAction.CommitRetirement] — which is why every number this field holds is already
     * named under [LogSegmentIndex.retired] on disk, and why every number a later [encodeIndex]
     * publishes there was covered by a write that landed first.
     */
    private fun applyRetirement(numbers: List<Int>) {
        sealedSegments.removeAll(numbers.toSet())
        numbers.forEach { number -> sealedContents.remove(number) }
        retiringSegments += numbers.filter { it !in retiringSegments }
    }

    /**
     * Best-effort delete of one retired segment's key; returns whether the key is gone.
     *
     * Guarded on its own, for the same reason [sweepLegacyKey] is: a store whose `delete` throws
     * (`IndexedDbDurableStore` can) must not turn every export after the first retirement into a
     * failure. The number stays in [retiringSegments] and so stays named by
     * [LogSegmentIndex.retired], which is the only record that the key exists at all.
     */
    private suspend fun sweep(number: Int): Boolean =
        runCatchingCancellable {
            store.delete(segmentKey(number))
            true
        }.getOrElse { cause ->
            logger.warn(cause) { "otel.logs: retired segment $number could not be deleted; retrying" }
            false
        }

    /**
     * The store mutations one [export] owes: a leading index write when the layout has moved,
     * then [flushActiveSegment]. **This is the whole cost of an export** — one segment's worth
     * of CBOR, never the whole log. Must hold [lock].
     */
    private fun pendingWrites(retire: Boolean): List<StoreAction> {
        val actions = mutableListOf<StoreAction>()
        // The index names the active segment, so it has to exist on disk before any content is
        // written into a segment it announces. It can never be the write that COMMITS a
        // retirement, and not because of where it sits in this list: it is `encodeIndex()` over
        // the live fields, and `retiringSegments` only ever holds numbers a
        // StoreAction.CommitRetirement already put on disk (see applyRetirement). So this write
        // restates the ledger; it cannot extend it.
        if (!indexPersisted) actions += StoreAction.Put(INDEX_KEY, encodeIndex())
        actions += flushActiveSegment(retire = retire)
        return actions
    }

    /**
     * Put the active segment on disk, retire what that write supersedes, and roll the segment
     * when it is full — the tail **both** write paths owe, in the one order that is safe. Must
     * hold [lock].
     *
     * [retire] is the caller's answer to "did a [windowPass] just run?", and it gates retirement
     * because a pass is the only thing that puts the *current* suppression state into
     * [activeSegment] — so it is the only point at which the write immediately below is known to
     * carry the state that supersedes whatever is about to be deleted.
     *
     * Shared rather than restated **because the roll is the part a second call site forgets**.
     * [merge] once emitted the segment write and the retirement without it, and the omission was
     * invisible to every export-driven test: a pass *grows* the active segment on the merge path,
     * where a foreign author's dots cannot fold into the floor and are recorded as an
     * [RgaOp.Compact] instead, so a replica fed only by gossip appended one op per merge to a
     * segment nothing ever sealed and rewrote that key **in full** every time — Θ(merges²) bytes
     * written and one key growing without bound, which is what [segmentOps] exists to rule out.
     * A key count stays flat throughout, so only a byte measurement sees it.
     */
    private fun flushActiveSegment(retire: Boolean): List<StoreAction> {
        val actions = mutableListOf<StoreAction>()
        actions += activeSegmentWrite()
        val retirement = if (retire) retireSupersededSegments() else PendingRetirement.NONE
        actions += retirement.actions
        if (activeOpCount >= segmentOps) actions += rollActiveSegment(retirement.staged)
        return actions
    }

    /** The write that puts the current [activeSegment] on disk under its own key. Must hold [lock]. */
    private fun activeSegmentWrite(): StoreAction =
        StoreAction.Put(segmentKey(activeNumber), cbor.encodeToByteArray(logSerializer, activeSegment))

    /**
     * **Stage** the seal of a full active segment and the opening of a fresh one, and emit the
     * index write that commits it. Must hold [lock].
     *
     * The index is rewritten here — once per [segmentOps] operations — because this is one of
     * the two points at which the set of segments changes; the other is
     * [retireSupersededSegments].
     *
     * The sealing point is also where the segment's content is summarised, from the segment
     * itself while it is still in hand. Recording it here rather than re-reading the key later
     * is what makes retirability decidable without opening every sealed segment on every pass.
     * The summary rides on the action and is installed by [applyRoll]; computing it is pure.
     *
     * **Nothing here touches [sealedSegments], [sealedContents], [activeSegment], [activeNumber]
     * or [nextSegmentNumber]** — the same discipline, and for the same reason, as
     * [retireSupersededSegments]. A seal applied while the turn was still being built survives a
     * turn whose [activeSegmentWrite] was refused, which strands the pass's `RgaOp.Compact` on a
     * key nothing will ever rewrite and, under sustained refusal, seals segment numbers that were
     * never written at all. See [StoreAction.CommitRoll], which is where the move is applied.
     *
     * [staged] is this turn's retirement, which has **not** been applied to any field yet either
     * — it lands in [commit], after its ledger write returns. The index encoded here has to
     * project it anyway, because on disk this write follows that ledger write: without the
     * projection it would re-name a retired segment as sealed and drop it from
     * [LogSegmentIndex.retired], leaving a swept key named as live forever in a format with no key
     * enumeration.
     */
    private fun rollActiveSegment(staged: List<Int>): List<StoreAction> {
        val sealing = activeNumber
        val opening = nextSegmentNumber
        return listOf(
            StoreAction.CommitRoll(
                bytes = encodeIndex(
                    sealed = (sealedSegments - staged.toSet()) + sealing,
                    retired = ledgerWith(staged),
                    active = opening,
                    next = opening + 1,
                ),
                sealing = sealing,
                content = contentOf(activeSegment, sealing),
                opening = opening,
            ),
        )
    }

    /**
     * Apply a roll whose index write has **returned**: seal [StoreAction.CommitRoll.sealing] into
     * the layout and open [StoreAction.CommitRoll.opening] as the active segment. Must hold
     * [lock].
     *
     * The post-durable counterpart of [applyRetirement] for the roll: both move their fields only
     * after the write that publishes the move has returned. It is not the only writer of the six
     * fields it touches outside recovery — see [retirableSegments]'s KDoc for the full
     * enumeration of mutation sites. One of those, [adoptRemoteSegment], is **not yet**
     * post-durable — it still moves the layout at build time, before its write returns; that gap
     * is filed as #2186. [writeMutex] serializes whole turns, so nothing can have allocated a
     * segment number between this action being built and being applied; [maxOf] states that
     * rather than relying on it.
     */
    private fun applyRoll(roll: StoreAction.CommitRoll) {
        sealedSegments += roll.sealing
        sealedContents[roll.sealing] = roll.content
        activeNumber = roll.opening
        nextSegmentNumber = maxOf(nextSegmentNumber, roll.opening + 1)
        activeSegment = Rga.empty()
        activeOpCount = 0
    }

    /**
     * Persist [remote]'s op-log as a sealed segment of its own. Must hold [lock].
     *
     * A merge is the one path that cannot append incrementally — remote ops land
     * anywhere in the sequence — so it pays O(remote) once, which is what it already
     * cost. Ops this replica already held are re-persisted in the new segment;
     * [Rga.piece] is idempotent, so the duplication costs bytes and nothing else.
     *
     * The index is written **first** here: a crash before the segment lands leaves the
     * index naming a segment the store lacks, which recovery tolerates, whereas the
     * reverse order would leak an unreferenced segment forever.
     */
    private fun adoptRemoteSegment(remote: Rga<LogRecord>): List<StoreAction> {
        val number = nextSegmentNumber++
        sealedSegments += number
        // Summarised from `remote`, which is exactly what is being persisted under this key —
        // so a remote log carrying an `RgaOp.Compact` pins its segment from the moment it lands.
        sealedContents[number] = contentOf(remote, number)
        return listOf(
            StoreAction.Put(INDEX_KEY, encodeIndex()),
            StoreAction.Put(segmentKey(number), cbor.encodeToByteArray(logSerializer, remote)),
        )
    }

    /**
     * Absorb a run of ops into the active segment. Must hold [lock].
     *
     * The per-op `apply` loop stays: the active segment is bounded at [segmentOps] ops,
     * so it is Θ(segmentOps) not Θ(N), and `apply` handles `Insert` and `Remove`
     * uniformly where [Rga]'s bulk mutators do not.
     */
    private fun appendToActiveSegment(ops: List<RgaOp<LogRecord>>) {
        if (ops.isEmpty()) return
        ops.forEach { op -> activeSegment = activeSegment.apply(op) }
        activeOpCount += ops.size
    }

    /**
     * Encode the index over [sealed] and [retired], defaulting to the layout as it stands.
     * Must hold [lock].
     *
     * The parameters exist for the writes that have to describe a layout no field holds yet — a
     * [StoreAction.CommitRetirement]'s, and the [StoreAction.CommitRoll] that can follow it in the
     * same turn — because each move is applied only once its own write returns. Every other
     * caller passes the fields, and so can only ever restate a ledger that is already on disk.
     */
    private fun encodeIndex(
        sealed: List<Int> = sealedSegments,
        retired: List<Int> = retiringSegments,
        active: Int = activeNumber,
        next: Int = nextSegmentNumber,
    ): ByteArray = cbor.encodeToByteArray(
        indexSerializer,
        LogSegmentIndex(
            sealedSegments = sealed.sorted(),
            active = active,
            next = next,
            retired = retired.sorted(),
        ),
    )

    /** The ledger this turn would leave behind if [staged] commits. Must hold [lock]. */
    private fun ledgerWith(staged: List<Int>): List<Int> =
        retiringSegments + staged.filter { it !in retiringSegments }

    // ── Retiring superseded segments ───────────────────────────────────────────

    /**
     * Summarise [segment]'s content for retirement purposes — see [SegmentContent].
     * [number] is for the log line only.
     *
     * Fails **closed**: a segment whose summary could not be computed is [SegmentContent.Pinned],
     * never a missing entry that a later change could read as "empty, therefore retirable". This
     * also honours [loadPersistedState]'s "nothing past this point may throw".
     */
    private fun contentOf(segment: Rga<LogRecord>, number: Int): SegmentContent =
        runCatchingCancellable {
            if (segment.compactOpCount > 0) {
                SegmentContent.Pinned
            } else {
                // Every op in a Compact-free log is an Insert or a Remove, and `sequence` holds
                // every Insert's id while `tombstones` holds every Remove's, so the union is
                // exactly the ids this segment could contribute to a recovered log.
                SegmentContent.Ids(segment.sequence.toSet() + segment.tombstones)
            }
        }.getOrElse { cause ->
            logger.warn(cause) { "otel.logs: segment $number could not be summarised; it will not be retired" }
            SegmentContent.Pinned
        }

    /**
     * Sealed segments whose every op the current suppression state already covers, and which
     * carry no [RgaOp.Compact] of their own. Must hold [lock].
     *
     * Candidates come from [sealedContents]' **entries**, not from [sealedSegments]. That is the
     * whole defence against the vacuity this file's hazard is: with a lookup, a segment that
     * failed to read would produce an absent (hence empty) id set, `all {}` would return `true`,
     * and it would be deleted. Iterating the evidence means an unknown segment is not a candidate
     * at all — see [SegmentContent].
     *
     * The `check` makes that defence **total** rather than a property of this one function's
     * shape. [sealedContents] holds an entry for *exactly* the sealed segments — [SegmentContent.Pinned]
     * wherever nothing is known — so there is no absent entry for a future `sealedContents[n] ?:`
     * to invent a meaning for, and the hazard is unrepresentable rather than merely unwritten
     * here. Every mutation site pairs the two ([applyRetirement], [applyRoll],
     * [adoptRemoteSegment], [installSealedContents]); this is the point at which a break would
     * cost a user's telemetry, so it is where it is caught.
     *
     * **It fails the export, and that is the intended trade.** The `check` throws, and this runs
     * inside the turn-building block of [export]/[merge], so a break is caught there and returned
     * as [ExportResult.Failure] — the record is not durably written and [ExporterHealth.failed]
     * counts it. A silent `continue` past the broken segment would be the alternative, and on a
     * path that deletes a user's telemetry an inconsistency of exactly this kind is the one thing
     * that must not be stepped over. The blast radius is bounded twice over. Only a turn that
     * just ran a [windowPass] reaches here at all, and a pass comes due about once per
     * [maxRecords] evictions — so at most one export in [maxRecords] can be refused by it. And the
     * refused record is not lost: the throw happens *after* the insert, so it is already in [log]
     * and in [activeSegment], and the next turn's active-segment write carries it to disk.
     *
     * Coverage has two sources and both must be read: the O(authors) [Rga.compactedBelow] floor,
     * which absorbs this replica's own windowed-away dots, and [Rga.compactedIds], which is where
     * a *foreign* author's land because [Rga.dropWindow] can never raise a foreign floor entry.
     */
    private fun retirableSegments(): List<Int> {
        check(sealedContents.keys == sealedSegments.toSet()) {
            "otel.logs: sealedContents must describe exactly the sealed segments — a sealed segment " +
                "nothing is known about is Pinned, never absent; got ${sealedContents.keys} for $sealedSegments"
        }
        val floor = log.compactedBelow
        val compacted = log.compactedIds
        val retirable = mutableListOf<Int>()
        for ((number, content) in sealedContents) {
            if (content !is SegmentContent.Ids) continue
            if (content.ids.all { id -> floor.contains(id.dot) || id in compacted }) retirable += number
        }
        return retirable
    }

    /**
     * **Stage** every retirable segment and emit the actions that carry the retirement to disk.
     * Must hold [lock], and must be appended **after** the active-segment write of a turn that
     * has just run a [windowPass].
     *
     * The order, extending this file's own precedent that the index is the commit point:
     *
     * 1. the covering state — the raised floor, and any `RgaOp.Compact` the pass minted — rides
     *    in the active-segment write the caller has already queued ahead of this;
     * 2. an index write moves the numbers out of `sealedSegments` and into
     *    [LogSegmentIndex.retired]: **the commit point**, and the point at which the same move is
     *    applied in memory ([StoreAction.CommitRetirement]);
     * 3. the keys are swept;
     * 4. a later index write drops the confirmed deletions from the ledger ([ledgerSwept]).
     *
     * **Nothing here touches [sealedSegments], [sealedContents] or [retiringSegments].** That is
     * the load-bearing part, not a stylistic one. Those fields are what [encodeIndex] reads, so
     * moving a number onto the ledger while the turn was still being *built* would publish it on
     * the **next** turn's leading index write — which is emitted before that turn's
     * active-segment write, and so before anything covers it. [loadPersistedState] sweeps
     * [LogSegmentIndex.retired] unconditionally and before it reads a thing, so the next start
     * would then delete a segment whose covering write never landed: not suppression lost, but
     * records a recovery could still have read, gone. Staging the move and applying it in [commit]
     * after the ledger write returns makes that state unrepresentable — which is also what lets
     * that unconditional startup sweep be correct.
     *
     * Both crash windows are safe, and for different reasons. A crash between 1 and 2 leaves the
     * segment named as sealed and present, so recovery simply reads it and the floor re-purges
     * its ops — no loss, and the retirement re-runs on the next pass. A crash between 2 and 3
     * leaves the key present but named only by [LogSegmentIndex.retired]; the segment's records
     * are superseded, so nothing is lost, and the ledger is the only thing that keeps the key
     * reachable in a format with no key enumeration — the next start sweeps it.
     *
     * Inverting 1 and 2 would be the unsafe order, and not because records would vanish: they are
     * already invisible. It is *suppression* that would be lost. Delete a segment whose covering
     * floor never reached disk and the restarted replica holds neither the `Insert`, nor its
     * `Remove`, nor anything that refuses them — so a peer that still holds the raw ops re-admits
     * the records as live on the next [merge]. Sweeps are queued behind the writes they depend on
     * and [commit] stops at the first failed write, which is what makes the order hold rather than
     * merely be documented.
     *
     * Every outstanding ledger entry is re-swept, not just this turn's, so a delete that failed
     * once is retried without waiting for a restart. That is also why an empty [retirableSegments]
     * still emits a ledger write when the ledger is non-empty: it restates a ledger already on
     * disk, and stages nothing.
     */
    private fun retireSupersededSegments(): PendingRetirement {
        val retirable = retirableSegments()
        if (retirable.isEmpty() && retiringSegments.isEmpty()) return PendingRetirement.NONE
        val ledger = ledgerWith(retirable)
        logger.debug { "otel.logs: retiring superseded segments $retirable; ledger would be $ledger" }
        return PendingRetirement(
            staged = retirable,
            actions = buildList {
                add(
                    StoreAction.CommitRetirement(
                        bytes = encodeIndex(sealed = sealedSegments - retirable.toSet(), retired = ledger),
                        numbers = retirable,
                    ),
                )
                ledger.forEach { number -> add(StoreAction.Sweep(number)) }
            },
        )
    }

    // ── Health bookkeeping ─────────────────────────────────────────────────────
    //
    // MutableStateFlow.update is an atomic compare-and-set loop, so these are
    // correct under a real multi-threaded dispatcher without taking `lock` —
    // and without extending `lock`'s hold time across the durable write.

    /** Record a successful durable write of [records] records and return [ExportResult.Success]. */
    private fun success(records: Int): ExportResult {
        healthState.update { it.copy(accepted = it.accepted + records, consecutiveFailures = 0) }
        return ExportResult.Success
    }

    /**
     * Record that the store accepted a write that carried **no admitted records** — the
     * [merge] path. Clears [ExporterHealth.consecutiveFailures] without touching
     * [ExporterHealth.accepted].
     *
     * The split is what keeps `accepted` answering "is this device's own telemetry
     * landing?" on a gossiping replica. A merge writes a whole adopted segment and is
     * real evidence the store is up, so it must clear the failure streak; it takes
     * nothing through admission, so counting it would let a replica that has exported
     * nothing at all report a healthy climbing count.
     */
    private fun storeSucceeded(): ExportResult {
        healthState.update { it.copy(consecutiveFailures = 0) }
        return ExportResult.Success
    }

    /** Record a failed durable write and return [ExportResult.Failure]. */
    private fun failure(cause: Throwable): ExportResult {
        healthState.update {
            it.copy(
                failed = it.failed + 1,
                consecutiveFailures = it.consecutiveFailures + 1,
                lastFailure = cause,
            )
        }
        return ExportResult.Failure(cause)
    }

    /**
     * Record that [recover] could not restore the persisted state.
     *
     * Deliberately does **not** touch [ExporterHealth.failed] — a failed recovery
     * is not a failed write, and conflating them would make `failed > 0` stop
     * meaning "the store is rejecting writes".
     */
    private fun recordRecoveryFailure(cause: Throwable) {
        healthState.update { it.copy(recoveryFailed = true, lastFailure = cause) }
    }

    // ── Windowing the in-memory op-log ─────────────────────────────────────────

    /**
     * Whether the in-memory log has drifted far enough from the retained window to owe a
     * [windowPass]. Must hold [lock].
     *
     * Two arms, because the two policies drift in different ways and neither arm covers the
     * other. [BufferPolicy.DROP_OLDEST] evicts one record per arrival, so [visibleCount] is
     * pinned *at* the cap and never exceeds it — only the eviction count grows. A [merge] can
     * push [visibleCount] past the cap outright, and under [BufferPolicy.DROP_NEWEST] that is
     * the only way the log grows at all: that policy refuses arrivals rather than evicting, so
     * its eviction count is permanently zero.
     */
    private fun windowPassDue(): Boolean =
        evictionsSincePass >= maxRecords || visibleCount > maxRecords

    /**
     * Drop everything outside the retained window from the in-memory log, and absorb the
     * resulting compaction record into the active segment. Returns whether anything moved.
     * Must hold [lock].
     *
     * **Grouped, not per-eviction**, and the reason is the derived-state caches: a pass reads
     * [Rga.sequence], which is a cold lazy on every new [Rga] instance, so it costs a full
     * `computeSequence()` plus the [rebuildDerivedState] walk afterwards. Those O(N) walks are
     * exactly what the incremental `tail`/[visibleCount]/[seenIds] caches exist to keep off the
     * export path (#1860). Running once per [maxRecords] evictions amortises them back to O(1)
     * per record; running per eviction would put an O(N) walk back on every single export.
     *
     * The drop reaches disk through [activeSegment]: [Rga.piece] merges the delta — a raised
     * floor, plus an [RgaOp.Compact] for any foreign author's dots the pass took — into it and
     * purges the segment's own ops beneath it, so the segment write that follows carries the
     * drop forward. Recovery unions the segments — the *sealed* ones still hold the dropped
     * `Insert`s — and the merged floor and `Compact` purge them there too. That is also what
     * makes a sealed segment retirable: once every op it holds is purged out of the union it
     * contributes nothing, so the caller follows the active-segment write with
     * [retireSupersededSegments] and the bytes leave the store as well as the log.
     */
    private fun windowPass(): Boolean {
        evictionsSincePass = 0
        val dropped = idsOutsideWindow() ?: return false
        val (newLog, delta) = log.dropWindow(replica, dropped) ?: return false
        // dropWindow returns null only for an EMPTY drop set; a set that changes nothing —
        // ids already under the floor, or never delivered here — comes back as this very
        // state with an inert delta. Persisting that would rewrite the segment for nothing.
        if (newLog === log) return false
        log = newLog
        activeSegment = activeSegment.piece(delta)
        activeOpCount = opCountOf(activeSegment)
        rebuildDerivedState()
        return true
    }

    /**
     * Every id in [log] that falls outside the retained window of [maxRecords] visible records
     * — the leading prefix of [Rga.sequence], found by walking back from the end and counting
     * visible ids. `null` when nothing falls outside. Must hold [lock].
     *
     * Tombstones inside the window are retained along with it and counted against nothing;
     * tombstones in the prefix are dropped with it, which is the point — an evicted record's
     * `Insert` *and* its `Remove` both leave the log.
     */
    private fun idsOutsideWindow(): Set<RgaId>? {
        val sequence = log.sequence
        val tombstones = log.tombstones
        var visibleSeen = 0
        var cut = sequence.size
        for (i in sequence.indices.reversed()) {
            if (visibleSeen == maxRecords) break
            if (sequence[i] !in tombstones) visibleSeen++
            cut = i
        }
        if (cut == 0) return null
        return sequence.subList(0, cut).toSet()
    }

    /**
     * Recompute [tail], [visibleCount] and [seenIds] from [log] in one pass.
     *
     * The entry point for the two events that replace the log wholesale — [recover]
     * and [merge] — where nothing can be threaded forward. Tombstoned entries are
     * excluded by [Rga.entries], so an evicted record's dedup slot is freed for re-use.
     *
     * Must be called with [lock] held.
     */
    private fun rebuildDerivedState(): Unit = installDerivedState(log.entries())

    /**
     * Assign [tail], [visibleCount] and [seenIds] from an already-materialized
     * [Rga.entries] list.
     *
     * Split out from [rebuildDerivedState] so [recover] can do the O(N) walk
     * *outside* the lock and leave only this assignment inside it: the walk can
     * throw, the assignment cannot, so a failure there cannot leave [log] swapped
     * with stale derived state (#1860).
     *
     * Must be called with [lock] held.
     */
    private fun installDerivedState(entries: List<Pair<RgaId, LogRecord>>) {
        tail = entries.lastOrNull()?.first ?: RgaId.HEAD
        visibleCount = entries.size
        seenIds.clear()
        entries.forEach { (rgaId, record) -> seenIds[record.recordId] = rgaId }
    }
}

/** Maximum number of [LogRecord]s buffered in memory before eviction. */
public const val DEFAULT_MAX_LOG_RECORDS: Int = 10_000

/**
 * Default operations per persisted log segment — the ceiling on how many bytes one
 * [WarpLogRecordExporter.export] rewrites.
 *
 * At the ~491 bytes/record measured against a real device's accumulated store, a
 * segment is ~123 KB: small enough that a per-record rewrite is cheap on a phone's
 * flash, large enough that the default 10,000-record buffer needs only ~40 keys,
 * which recovery reads once at startup. It is a pure tuning knob — correctness does
 * not depend on it, and two replicas need not agree on it.
 */
public const val DEFAULT_LOG_SEGMENT_OPS: Int = 256
