@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.otel

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.RgaId
import us.tractat.kuilt.crdt.RgaOp
import us.tractat.kuilt.crdt.ReplicaId

// Explicit, package-qualified name — NOT the `logger {}` lambda form. On
// Kotlin/Native the lambda form resolves to an EMPTY logger name, which would make
// this internal logger indistinguishable from an application logger and defeat the
// self-capture exclusion in :kuilt-otel-logging (`LogCapture` drops events whose
// loggerName starts with `us.tractat.kuilt`). The exporter logs on its eviction
// hot path, so an unnamed log here would be re-captured and re-exported in an
// unbounded loop. Keep this name stable and under the kuilt package.
private val logger = KotlinLogging.logger("us.tractat.kuilt.otel.WarpLogRecordExporter")

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
 * When the in-memory [Rga] exceeds [maxRecords], the oldest or newest record
 * (depending on [bufferPolicy]) is evicted before the new record is inserted.
 * **Every eviction is logged** with enough detail to correlate against a backend's
 * log index.
 *
 * ## On-disk layout — segmented op-log
 *
 * An [Rga] *is* an op-log, and [Rga.piece] is an idempotent union of two op-logs.
 * So the exporter does not need one key holding the whole log: it keeps the log in
 * **segments** of at most [segmentOps] operations, each under its own key
 * (`otel.logs.seg.<n>`), plus a small [LogSegmentIndex] naming the live ones
 * (`otel.logs.idx`).
 *
 * [export] appends the [us.tractat.kuilt.crdt.RgaOp.Insert] that
 * [Rga.insertAfter] already returns to the **active** segment and rewrites only
 * that segment, so the encode-and-write cost is O([segmentOps]) — a constant,
 * independent of how many records the log holds. The previous layout re-encoded
 * and rewrote the entire log on every single record, which is O(N) work once per
 * record: Θ(N²) time and Θ(N²) bytes to accumulate N records (#1860).
 *
 * Recovery reads the segments named by the index and unions them with [Rga.piece].
 * Set union is commutative and idempotent, so the reconstruction is exact and
 * order-independent — and the persisted [Rga] wire form derives its Lamport clock
 * from the op-set, so nothing is lost by not persisting it per segment.
 *
 * ## Reclamation, and what [maxRecords] now bounds
 *
 * [maxRecords] still bounds *visibility* exactly: eviction tombstones one record at
 * a time, as before. What changed is that the tombstoned record's `Insert` op —
 * which carries its full body — is now **physically dropped** once its whole
 * segment is superseded, instead of being rewritten to disk forever. A segment is
 * reclaimed when every record it inserted has been evicted and dropping its
 * `Remove` ops cannot un-tombstone an `Insert` that survives elsewhere.
 *
 * Because reclamation is segment-granular, the bytes on disk plateau at roughly
 * [maxRecords] plus up to one segment of already-evicted records — a constant
 * overhead, where previously the file grew without bound for the life of the device.
 *
 * Two honest limits. Reclamation shrinks the *store*, not the in-memory op-log,
 * which still accumulates tombstoned ops for the life of the process; bounding that
 * needs [Rga.compact] and the causal-stability inputs it demands. And after a
 * segment is reclaimed, a surviving op that referenced a dropped predecessor
 * re-roots to the front of the sequence: for an append-only single-replica log —
 * the shape a device produces — that leaves the visible order untouched, but a log
 * that has absorbed concurrent remote inserts may see the relative order of its
 * *oldest surviving* records shift after a restart.
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
    private var log: Rga<LogRecord> = Rga.empty()

    // ── Derived state, threaded forward across export() calls ────────────────
    //
    // All three are functions of `log` alone and were recomputed from it on every
    // export. That is pure waste in this access pattern: `Rga` is immutable, so
    // `insertAfter` hands back a NEW instance whose `sequence` lazy is cold —
    // meaning a full `computeSequence()` (a groupBy plus a sortedDescending per
    // sibling group) ran once per exported record, plus two more O(N) passes for
    // the eviction gate and the tail filter. Maintaining them incrementally makes
    // the common (non-evicting) export path touch the sequence not at all.
    //
    // Every write to `log` must update these in the same locked section. The four
    // events that move them out from under the cache — a DROP_NEWEST eviction
    // (which removes the tail), a DROP_OLDEST eviction that empties the log, a
    // merge whose remote inserts sort after the local tail, and a recover() that
    // replaces the log wholesale — are pinned by WarpLogRecordExporterTailCacheTest.

    /** Predecessor for the next append: the last visible element, or [RgaId.HEAD] when empty. */
    private var tail: RgaId = RgaId.HEAD

    /** Number of visible (non-tombstoned) records in [log] — the eviction gate. */
    private var visibleCount: Int = 0

    // Maps recordId → RgaId of the Insert op, so that re-export is a no-op.
    // Mutated in place on export()/eviction and rebuilt from the op-log on recover().
    private val seenIds: MutableMap<ByteString, RgaId> = mutableMapOf()

    // A MutableStateFlow owns no CoroutineScope, so the health surface adds no
    // scope ownership to this type. `update {}` is an atomic CAS loop — a real
    // thread-safe primitive, not dispatcher confinement (repo policy).
    // ── Persisted segments ───────────────────────────────────────────────────
    //
    // Guarded by `lock`, like everything above. Only the ids each sealed segment
    // holds are retained, not its ops: reclamation needs to know which records a
    // segment inserted and which it tombstoned, and nothing more. `log` remains the
    // one in-memory op-log — the segments are a persistence partition of it, and
    // `log.ops == union(segment.ops)` is the invariant every write path preserves.

    private val sealedSegments: MutableMap<Int, SegmentIds> = linkedMapOf()
    private var activeSegment: Rga<LogRecord> = Rga.empty()
    private var activeNumber: Int = 0
    private var activeOpCount: Int = 0
    private var nextSegmentNumber: Int = 1

    /**
     * Whether the store holds an index matching [sealedSegments]/[activeNumber].
     *
     * Cleared on any failed batch, so the next attempt rewrites it: a partially
     * applied batch can have left the index naming segments that were never written.
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
    }

    /** The ids one persisted segment holds: from its `Insert` ops and its `Remove` ops. */
    private class SegmentIds(val inserts: Set<RgaId>, val removes: Set<RgaId>)

    /** One durable-store mutation. A null [bytes] is a delete. */
    private class StoreAction(val key: StoreKey, val bytes: ByteArray?)

    /** Everything [recover] reconstructs from the store, before it is installed. */
    private class RecoveredState(
        val log: Rga<LogRecord>,
        val sealedSegments: Map<Int, SegmentIds>,
        val activeNumber: Int,
        val activeSegment: Rga<LogRecord>,
        val nextSegmentNumber: Int,
    )

    /**
     * Recover persisted log state from [store]. Call once at startup before
     * any calls to [export].
     *
     * Rebuilds the dedup map from the op-log so that re-export of previously
     * persisted records remains a no-op after a process restart.
     *
     * If no persisted state exists, the exporter starts with an empty log.
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
        // The index is the migration's commit point, so a legacy key that outlives it
        // is a delete that never landed — not data. Segment 0 already holds it.
        store.delete(LEGACY_KEY)

        val sealed = linkedMapOf<Int, SegmentIds>()
        var merged = Rga.empty<LogRecord>()
        for (number in index.sealedSegments) {
            val segment = readSegment(number) ?: continue
            sealed[number] = segmentIdsOf(segment)
            merged = merged.piece(segment)
        }
        val active = readSegment(index.active) ?: Rga.empty()
        return RecoveredState(
            log = merged.piece(active),
            sealedSegments = sealed,
            activeNumber = index.active,
            activeSegment = active,
            nextSegmentNumber = maxOf(index.next, index.active + 1),
        )
    }

    /**
     * Read one segment, or `null` if the store lacks it or cannot decode it.
     *
     * Absence is **expected**, not corruption: the index is written before the
     * segment it allocates, so a crash in that window leaves the index naming a
     * segment that was never written. An undecodable segment costs only the records
     * it held, where the single-blob layout lost the whole log to one bad byte.
     */
    private suspend fun readSegment(number: Int): Rga<LogRecord>? {
        val bytes = store.read(segmentKey(number))
        if (bytes == null) {
            logger.debug { "otel.logs: segment $number is named by the index but absent" }
            return null
        }
        return runCatchingCancellable { cbor.decodeFromByteArray(logSerializer, bytes) }
            .getOrElse { cause ->
                logger.warn(cause) { "otel.logs: segment $number is corrupt, dropping its records" }
                null
            }
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
     * The inherited segment is reclaimed by the normal path once its records age out.
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
            sealedSegments = linkedMapOf(LEGACY_SEGMENT to segmentIdsOf(recovered)),
            activeNumber = index.active,
            activeSegment = Rga.empty(),
            nextSegmentNumber = index.next,
        )
    }

    /** Install a [RecoveredState] and its already-materialized entries. Must hold [lock]. */
    private fun install(recovered: RecoveredState, entries: List<Pair<RgaId, LogRecord>>) {
        log = recovered.log
        sealedSegments.clear()
        sealedSegments.putAll(recovered.sealedSegments)
        activeSegment = recovered.activeSegment
        activeNumber = recovered.activeNumber
        activeOpCount = opCountOf(recovered.activeSegment)
        nextSegmentNumber = recovered.nextSegmentNumber
        indexPersisted = true
        installDerivedState(entries)
    }

    /**
     * Export one log record: append it to the [Rga] and durably flush to [store].
     *
     * Returns [ExportResult.Success] after the durable write. If the record's
     * [LogRecord.recordId] was already exported (including across process restarts
     * after [recover]), returns [ExportResult.Success] immediately without
     * inserting a duplicate.
     *
     * **Never throws.** Every failure — a throwing [store], and also a failure
     * inside the in-memory CRDT insert, the eviction, or the CBOR encode — is
     * returned as [ExportResult.Failure] and reflected on [health]. The store is
     * the failure this method was originally written for, but it is not the only
     * one reachable, and a caller on the logging path cannot handle a thrown
     * exception: it would surface inside an application's own logging call (#1860).
     */
    public suspend fun export(record: LogRecord): ExportResult {
        val actions = runCatchingCancellable {
            lock.withLock {
                if (record.recordId in seenIds) return ExportResult.Success
                maybeEvict(record)
                val (newLog, insertOp) = log.insertAfter(
                    replica = replica,
                    after = tail,
                    value = record,
                )
                log = newLog
                tail = insertOp.id
                visibleCount++
                seenIds[record.recordId] = insertOp.id
                // The op `insertAfter` already handed back is exactly what the segment
                // needs — the append is O(1) and the encode below is O(segmentOps).
                appendToActiveSegment(insertOp)
                pendingWrites()
            }
        }.getOrElse { cause ->
            logger.error(cause) {
                "WarpLogRecordExporter: buffer update failed for record ${record.recordId}"
            }
            return failure(cause)
        }
        return commit(actions) { cause ->
            logger.error(cause) {
                "WarpLogRecordExporter: durable write failed for record ${record.recordId}"
            }
        }
    }

    /**
     * Read a snapshot of the current in-memory [Rga] for gossip / anti-entropy.
     *
     * The returned [Rga] reflects all records exported since the last [recover]
     * or process start, minus any that were evicted due to the buffer cap.
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
    public suspend fun merge(remote: Rga<LogRecord>): ExportResult {
        val actions = runCatchingCancellable {
            lock.withLock {
                log = log.piece(remote)
                // A remote insert can land anywhere, including after the local tail.
                rebuildDerivedState()
                adoptRemoteSegment(remote)
            }
        }.getOrElse { cause ->
            logger.error(cause) { "WarpLogRecordExporter: buffer update failed during merge" }
            return failure(cause)
        }
        return commit(actions) { cause ->
            logger.error(cause) { "WarpLogRecordExporter: durable write failed during merge" }
        }
    }

    // ── Segmented persistence ──────────────────────────────────────────────────

    /**
     * Apply a batch of store mutations in order, then report it through [health].
     *
     * One export is **one** accepted write no matter how many keys its batch
     * touched, so [ExporterHealth.accepted] keeps meaning "records durably taken",
     * not "store calls made".
     */
    private suspend fun commit(actions: List<StoreAction>, logFailure: (Throwable) -> Unit): ExportResult =
        runCatchingCancellable {
            actions.forEach { action ->
                if (action.bytes == null) store.delete(action.key) else store.write(action.key, action.bytes)
            }
        }.fold(
            onSuccess = {
                lock.withLock { indexPersisted = true }
                success()
            },
            onFailure = { cause ->
                // A half-applied batch can have left the index naming segments that
                // were never written. Rewriting it on the next attempt re-converges.
                lock.withLock { indexPersisted = false }
                logFailure(cause)
                failure(cause)
            },
        )

    /**
     * The store mutations one [export] owes: the active segment, plus a roll when it
     * is full. **This is the whole cost of an export** — one segment's worth of CBOR,
     * never the whole log. Must hold [lock].
     */
    private fun pendingWrites(): List<StoreAction> {
        val actions = mutableListOf<StoreAction>()
        // The index names the active segment, so it has to exist on disk before any
        // content is written into a segment it announces.
        if (!indexPersisted) actions += StoreAction(INDEX_KEY, encodeIndex())
        actions += StoreAction(segmentKey(activeNumber), cbor.encodeToByteArray(logSerializer, activeSegment))
        if (activeOpCount >= segmentOps) actions += rollActiveSegment()
        return actions
    }

    /**
     * Seal the full active segment, reclaim whatever that makes droppable, and open a
     * fresh active segment. Must hold [lock].
     *
     * The deletes land **before** the index that stops naming them. The reverse order
     * would leak a segment nothing ever reads or deletes again; this order can at worst
     * leave the index naming a segment that is already gone, which [readSegment]
     * tolerates by design.
     */
    private fun rollActiveSegment(): List<StoreAction> {
        sealedSegments[activeNumber] = segmentIdsOf(activeSegment)
        val reclaimed = reclaimableSegments()
        reclaimed.forEach { sealedSegments.remove(it) }
        if (reclaimed.isNotEmpty()) {
            logger.debug { "otel.logs: reclaimed fully-superseded segments $reclaimed" }
        }
        activeNumber = nextSegmentNumber++
        activeSegment = Rga.empty()
        activeOpCount = 0
        return reclaimed.map { StoreAction(segmentKey(it), null) } + StoreAction(INDEX_KEY, encodeIndex())
    }

    /**
     * The sealed segments whose ops can be physically dropped from the store.
     *
     * This is what makes [maxRecords] bound the *file* and not just the view. Evicting
     * a record only tombstones it; its `Insert` op keeps carrying the full body, and
     * under the previous layout that op was re-encoded to disk on every subsequent
     * export, forever. Dropping the segment removes the op outright.
     *
     * Two conditions, both necessary:
     * 1. every record the segment inserted is already evicted, so no visible record
     *    goes with it; and
     * 2. none of its `Remove` ops tombstones an `Insert` that survives in another
     *    segment — dropping one of those would resurrect an evicted record.
     *
     * (2) is evaluated against the segments that are live *now*, so two segments that
     * straddle an insert/remove pair are reclaimed one round apart rather than
     * together. Conservative, and it always makes progress.
     *
     * Must hold [lock].
     */
    private fun reclaimableSegments(): List<Int> {
        val evicted = log.tombstones
        val activeInserts = activeSegment.sequence.toSet()
        return sealedSegments.entries
            .filter { (number, ids) ->
                ids.inserts.all { it in evicted } &&
                    ids.removes.none { id ->
                        id !in ids.inserts && isInsertedElsewhere(id, number, activeInserts)
                    }
            }
            .map { it.key }
    }

    /** Whether some segment other than [exceptSegment] holds the `Insert` for [id]. Must hold [lock]. */
    private fun isInsertedElsewhere(id: RgaId, exceptSegment: Int, activeInserts: Set<RgaId>): Boolean =
        id in activeInserts ||
            sealedSegments.any { (number, ids) -> number != exceptSegment && id in ids.inserts }

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
        sealedSegments[number] = segmentIdsOf(remote)
        return listOf(
            StoreAction(INDEX_KEY, encodeIndex()),
            StoreAction(segmentKey(number), cbor.encodeToByteArray(logSerializer, remote)),
        )
    }

    /** Absorb one op into the active segment. Must hold [lock]. */
    private fun appendToActiveSegment(op: RgaOp<LogRecord>) {
        activeSegment = activeSegment.apply(op)
        activeOpCount++
    }

    /** Must hold [lock]. */
    private fun encodeIndex(): ByteArray = cbor.encodeToByteArray(
        indexSerializer,
        LogSegmentIndex(
            sealedSegments = sealedSegments.keys.sorted(),
            active = activeNumber,
            next = nextSegmentNumber,
        ),
    )

    /**
     * The ids [segment] holds. [Rga.sequence] enumerates every id with an `Insert` op
     * and [Rga.tombstones] every id with a `Remove` op — the two public projections
     * that let reclamation reason about a segment without reaching into its op-set.
     */
    private fun segmentIdsOf(segment: Rga<LogRecord>) =
        SegmentIds(inserts = segment.sequence.toSet(), removes = segment.tombstones)

    /**
     * How many ops [segment] holds, for the roll threshold. A `Remove` whose `Insert`
     * lives in another segment appears only in [Rga.tombstones], so the two projections
     * are disjoint exactly where they should be and the sum is the true op count.
     */
    private fun opCountOf(segment: Rga<LogRecord>): Int =
        segment.sequence.size + segment.tombstones.size

    // ── Health bookkeeping ─────────────────────────────────────────────────────
    //
    // MutableStateFlow.update is an atomic compare-and-set loop, so these are
    // correct under a real multi-threaded dispatcher without taking `lock` —
    // and without extending `lock`'s hold time across the durable write.

    /** Record a successful durable write and return [ExportResult.Success]. */
    private fun success(): ExportResult {
        healthState.update { it.copy(accepted = it.accepted + 1, consecutiveFailures = 0) }
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

    /** Must be called with [lock] held. */
    private fun maybeEvict(incoming: LogRecord) {
        if (visibleCount < maxRecords) return
        val index = when (bufferPolicy) {
            BufferPolicy.DROP_OLDEST -> 0
            BufferPolicy.DROP_NEWEST -> visibleCount - 1
        }
        val (newLog, removeOp) = log.removeAt(index) ?: return
        val evictedRecord = log.toList()[index]
        logger.warn {
            "WarpLogRecordExporter: buffer cap ($maxRecords) reached, evicting record " +
                "recordId=${evictedRecord.recordId} body=${evictedRecord.body?.take(80)} " +
                "policy=$bufferPolicy (incoming recordId=${incoming.recordId})"
        }
        log = newLog
        // The tombstone is an op like any other, so it rides in the active segment.
        // It is what eventually makes the evicted record's own segment reclaimable.
        appendToActiveSegment(removeOp)
        seenIds.remove(evictedRecord.recordId)
        visibleCount--
        tail = when {
            // Nothing left to append after.
            visibleCount == 0 -> RgaId.HEAD
            // DROP_OLDEST removed visible index 0. With at least one element still
            // standing, the first and last visible elements are distinct, so the
            // tail is untouched.
            bufferPolicy == BufferPolicy.DROP_OLDEST -> tail
            // DROP_NEWEST removed visible index size-1 — the tail itself. The new
            // tail is its predecessor, which only the sequence knows.
            else -> tailIdOf(newLog)
        }
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

    /**
     * The [RgaId] of the last visible element of [rga], or [RgaId.HEAD] if it has none.
     *
     * O(N) over the materialized sequence — reached only when a [BufferPolicy.DROP_NEWEST]
     * eviction removes the tail, a path that already pays for the sequence inside
     * [Rga.removeAt].
     */
    private fun tailIdOf(rga: Rga<LogRecord>): RgaId =
        rga.sequence.lastOrNull { it !in rga.tombstones } ?: RgaId.HEAD
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
