@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.otel

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.RgaId
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
 * @param replica The [ReplicaId] for this device/process. Must be unique and stable
 *   across restarts (a UUID is recommended).
 * @param store The [DurableStore] to persist CRDT state. Use [InMemoryDurableStore]
 *   in tests; wire a platform WAL (JVM file, IndexedDB, etc.) in production.
 * @param maxRecords Maximum number of records buffered in memory before eviction.
 *   Defaults to [DEFAULT_MAX_LOG_RECORDS].
 * @param bufferPolicy What to do when [maxRecords] is exceeded. Defaults to
 *   [BufferPolicy.DROP_OLDEST].
 *
 * @sample us.tractat.kuilt.otel.sampleWarpLogRecordExporter
 */
public class WarpLogRecordExporter(
    private val replica: ReplicaId,
    private val store: DurableStore,
    private val maxRecords: Int = DEFAULT_MAX_LOG_RECORDS,
    private val bufferPolicy: BufferPolicy = BufferPolicy.DROP_OLDEST,
) {
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

    private companion object {
        private val STORE_KEY = StoreKey("otel.logs")
        private val cbor = Cbor { alwaysUseByteString = true }
        private val logSerializer = Rga.wireSerializer(LogRecord.serializer())
    }

    /**
     * Recover persisted log state from [store]. Call once at startup before
     * any calls to [export].
     *
     * Rebuilds the dedup map from the op-log so that re-export of previously
     * persisted records remains a no-op after a process restart.
     *
     * If no persisted state exists, the exporter starts with an empty log.
     */
    public suspend fun recover() {
        val bytes = store.read(STORE_KEY) ?: return
        val recovered = runCatchingCancellable<Rga<LogRecord>> {
            cbor.decodeFromByteArray(logSerializer, bytes)
        }.getOrNull() ?: run {
            logger.warn { "otel.logs: corrupt store entry, starting fresh" }
            return
        }
        lock.withLock {
            log = recovered
            rebuildDerivedState()
        }
    }

    /**
     * Export one log record: append it to the [Rga] and durably flush to [store].
     *
     * Returns [ExportResult.Success] after the durable write. If the record's
     * [LogRecord.recordId] was already exported (including across process restarts
     * after [recover]), returns [ExportResult.Success] immediately without
     * inserting a duplicate.
     *
     * Returns [ExportResult.Failure] only if the [store] itself throws.
     */
    public suspend fun export(record: LogRecord): ExportResult {
        val encoded = lock.withLock {
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
            cbor.encodeToByteArray(logSerializer, log)
        }
        return runCatchingCancellable { store.write(STORE_KEY, encoded) }
            .fold(
                onSuccess = { ExportResult.Success },
                onFailure = { cause ->
                    logger.error(cause) {
                        "WarpLogRecordExporter: durable write failed for record ${record.recordId}"
                    }
                    ExportResult.Failure(cause)
                },
            )
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
     */
    public suspend fun merge(remote: Rga<LogRecord>): ExportResult {
        val encoded = lock.withLock {
            log = log.piece(remote)
            // A remote insert can land anywhere, including after the local tail.
            rebuildDerivedState()
            cbor.encodeToByteArray(logSerializer, log)
        }
        return runCatchingCancellable { store.write(STORE_KEY, encoded) }
            .fold(
                onSuccess = { ExportResult.Success },
                onFailure = { cause ->
                    logger.error(cause) { "WarpLogRecordExporter: durable write failed during merge" }
                    ExportResult.Failure(cause)
                },
            )
    }

    /** Must be called with [lock] held. */
    private fun maybeEvict(incoming: LogRecord) {
        if (visibleCount < maxRecords) return
        val index = when (bufferPolicy) {
            BufferPolicy.DROP_OLDEST -> 0
            BufferPolicy.DROP_NEWEST -> visibleCount - 1
        }
        val (newLog, _) = log.removeAt(index) ?: return
        val evictedRecord = log.toList()[index]
        logger.warn {
            "WarpLogRecordExporter: buffer cap ($maxRecords) reached, evicting record " +
                "recordId=${evictedRecord.recordId} body=${evictedRecord.body?.take(80)} " +
                "policy=$bufferPolicy (incoming recordId=${incoming.recordId})"
        }
        log = newLog
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
    private fun rebuildDerivedState() {
        val entries = log.entries()
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
