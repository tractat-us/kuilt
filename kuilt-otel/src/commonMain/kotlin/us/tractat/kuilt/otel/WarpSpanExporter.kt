@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.otel

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.crdt.ORSet
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.store.StoreKey

private val logger = KotlinLogging.logger("us.tractat.kuilt.otel.WarpSpanExporter")

/**
 * A CRDT-backed span exporter.
 *
 * Spans are stored as [SpanRecord] values in an [ORSet]: adding the same value
 * twice is idempotent. Auto-stamping can give a retry a different value, even with
 * the same [SpanRecord.spanId]; see [export] for failed-write retry limits.
 * Reconnecting peers reconcile by sharing their
 * [ORSet] deltas through the kuilt anti-entropy layer rather than replaying a
 * queue, so only missing spans move over the wire.
 *
 * ## Key inversion
 *
 * [export] returns [ExportResult.Success] the moment the span is **durably written
 * to the [DurableStore]** — not when it is delivered to any backend. Delivery is
 * asynchronous and eventually consistent; the CRDT merge guarantees that any
 * replica which receives the span will incorporate it correctly, even if the span
 * arrives out of order or more than once.
 *
 * ## Buffer cap
 *
 * When the in-memory CRDT already holds [maxSpans] spans, [export] evicts one before it
 * inserts: the earliest by [SpanRecord.startEpochNanos] under [BufferPolicy.DROP_OLDEST], the
 * latest under [BufferPolicy.DROP_NEWEST]. The victim is chosen from the spans already held,
 * so the incoming span is always kept. [merge] does not evict. The read of the stored spans
 * that lands, in [recover] or in the first write turn after a refused one, evicts whatever its
 * join brought in beyond [maxSpans] in one batch, by the same policy, before that turn's own
 * insert. **Every eviction is logged** with enough detail to correlate against a backend's
 * orphan-span index.
 *
 * ## Failure reporting
 *
 * A refused durable write is logged **once per outage, not once per attempt** (#2593). Every call
 * retries the write the last one could not land, so a store that stays broken is one unchanging
 * condition, and reporting it per call produced a line — and a stack trace — per export, forever.
 * [export], [merge] and [clear] share one latch because they write one key; the next line comes
 * after the store has taken a write. Every failure is still returned to the caller as
 * [ExportResult.Failure] carrying the cause, so nothing is hidden from a programmatic reader — only
 * from the log. See `durableWriteOutage`.
 *
 * @param replica The [ReplicaId] for this device/process. Must be unique and stable
 *   across restarts (a UUID is recommended).
 * @param store The [DurableStore] to persist CRDT state. Use [InMemoryDurableStore]
 *   in tests; wire a platform WAL (JVM file, IndexedDB, etc.) in production.
 * @param maxSpans Maximum number of spans buffered in memory before eviction.
 *   Defaults to [DEFAULT_MAX_SPANS].
 * @param bufferPolicy What to do when [maxSpans] is exceeded. Defaults to
 *   [BufferPolicy.DROP_OLDEST].
 *
 * @sample us.tractat.kuilt.otel.sampleWarpSpanExporter
 */
public class WarpSpanExporter(
    private val replica: ReplicaId,
    private val store: DurableStore,
    private val maxSpans: Int = DEFAULT_MAX_SPANS,
    private val bufferPolicy: BufferPolicy = BufferPolicy.DROP_OLDEST,
    private val causalClock: WarpCausalClock? = null,
) {
    // Two-tier locking, both explicit primitives (repo policy: correctness must hold under
    // a real multi-threaded dispatcher; limitedParallelism(1) confinement is BANNED).
    //
    //  - `lock` (atomicfu reentrant) guards the in-memory `spans` mutation only. No suspend
    //    call is ever made inside it — Cbor encode/decode and CRDT ops are pure.
    //  - `ioMutex` (coroutine Mutex) serializes the whole *durable-write* critical section
    //    across concurrent export()/merge() calls: the clock persist and the span store
    //    write form one ordered unit. This is what makes the crash-window invariant
    //    (durable clock seq >= every durable span dot) hold even under concurrent export(),
    //    and closes a spans lost-update where a stale encoded snapshot could drop a
    //    concurrently-added span (#1053). Suspend IO is legal inside a coroutine Mutex;
    //    `lock` is never held across an `ioMutex` acquisition, so the two never deadlock.
    private val lock = reentrantLock()
    private val ioMutex = Mutex()
    private var spans: ORSet<SpanRecord> = ORSet.empty()

    /**
     * Whether this exporter has read the stored spans, guarded by [lock] and landed only under
     * [ioMutex]. A write turn writes the whole set over [STORE_KEY], so until a read has landed it
     * would replace spans it never saw (#3390). Every write turn therefore reads first, and writes
     * only once the state is [SpanReadState.Recovered], which nothing moves it out of.
     *
     * Until then a local export adds nothing to [spans]. An `ORSet` add mints the next dot from the
     * set's own context, and before the read that context lacks every stored dot, so the add would
     * reuse a dot a stored span already holds and the join would conflate the two. So [export] adds
     * only inside [ioMutex], after the read has landed and joined the stored set, and then takes the
     * live path exactly. A refused read leaves it nothing to undo.
     */
    private var readState: SpanReadState = SpanReadState.Unread
    private val persistence = PersistenceHealthTracker<StoreKey>()
    private val spanWork = setOf(STORE_KEY)
    private val clockAndSpanWork = if (causalClock == null) spanWork else spanWork + WarpCausalClock.STORE_KEY

    /**
     * Completed persistence turns and unresolved failures, independent of outage logging.
     * Includes a configured clock's write as part of export/clear, not a second acknowledgement.
     * Recovery covers the span buffer; [WarpTelemetry.recover] also reports its clock's recovery.
     *
     * @sample us.tractat.kuilt.otel.samplePersistenceHealth
     */
    public val health: StateFlow<PersistenceHealth> = persistence.health

    /** Count a clock recovery that did not land cleanly as this exporter's recovery failure. */
    internal fun clockRecovered(outcome: ClockRecovery) {
        when (outcome) {
            ClockRecovery.Recovered -> Unit
            is ClockRecovery.Undecodable -> persistence.recoveryFailed(outcome.cause)
            is ClockRecovery.Refused -> persistence.recoveryFailed(outcome.cause)
        }
    }

    /**
     * Whether a durable-write outage is currently **open** — i.e. a refused store write has already
     * been reported and the store has not taken a write since. Read and written only by
     * [durableWriteSucceeded] and [durableWriteFailed]; nothing else touches it.
     *
     * ## What it is for
     *
     * A refused write is retried by the next call, so a store that stays broken — a quota-bound
     * `IndexedDbDurableStore` is the shape [WarpLogRecordExporter]'s KDoc names — would otherwise
     * report one unchanging condition once per export, forever, each line carrying a stack trace.
     * Measured here at 300 throwable-bearing lines over a 300-export outage (#2593), matching what
     * #2237 measured on the sibling.
     *
     * ## What the population is, and why that is the whole design
     *
     * Exactly the durable writes to [STORE_KEY]: [export], [merge] and [clear], which are the three
     * functions that call `store.write` and the three that emit a `"durable write failed"` line.
     * One latch across all three, not one each — they write the *same key*, so "the store is
     * refusing writes" is a single condition however it is reached, and announcing it once per path
     * is the same defect at a smaller constant.
     *
     * **It is deliberately not keyed on a failure counter, and specifically not on an
     * [ExporterHealth]-style `consecutiveFailures` streak.** That was tried on the sibling and was
     * itself the bug (#2237): a streak counts *every* failure, while only the store path reports
     * one, so any member of the difference opens the streak first and the store's own outage is
     * then reported **zero** times rather than once — worse than the noise the dedup replaced,
     * because the log points at the wrong subsystem while the store's exception goes unmentioned.
     * A latch owned by the durable-write arms makes the counted population and the reported
     * population the same set by construction. Keep it that way: anything that calls
     * [durableWriteFailed] without having attempted a write to [STORE_KEY] reintroduces the defect.
     *
     * **On this exporter no test can guard that, and the reason is the guarantee itself**: all
     * three callers write [STORE_KEY], so the two populations coincide by construction and there is
     * no non-write path to pre-empt the report. The obligation above binds a *future* caller, and
     * nothing would red if one broke it. [WarpMetricExporter] is where such a path does exist — its
     * `clear` fails on a refused delete — and
     * `WarpMetricExporterFailureReportingTest.aRefusedClearDeleteDoesNotSwallowTheDurableWriteReport`
     * is the analogous guard there.
     *
     * ## Why a boolean here, where [WarpMetricExporter] needs a set of keys
     *
     * Because a partial refusal cannot alternate this latch. [export] and [clear] write the causal
     * clock's key *and* [STORE_KEY] inside one guarded turn, so a store refusing either
     * one fails the whole turn and the latch stays open; a success means every write in the turn
     * landed. [WarpLogRecordExporter.commit] groups a turn's writes the same way. [WarpMetricExporter]
     * does not — each of its calls writes exactly one key — so a store refusing one key while
     * accepting another would alternate a boolean there, and it keys on the refused-key set instead.
     * Do not copy this boolean to an exporter whose writes are not grouped into a turn.
     *
     * ## Concurrency
     *
     * A [MutableStateFlow], not an `atomicfu` `atomic()`: the atomicfu **Gradle plugin** is not
     * applied in this build (the dependency is present for the multiplatform `locks` API only), so
     * an atomic *field* would be untransformed. [MutableStateFlow.compareAndSet] is a real
     * lock-free CAS on every target — never dispatcher confinement, per repo policy.
     *
     * [ioMutex] serializes the whole durable-write section, so today two of these cannot overlap;
     * the CAS is what keeps this correct without depending on that. **Exactly one racing caller
     * reports**: the failure arm reports only when it wins `false → true`, and a loser is by
     * definition looking at an outage someone has already announced.
     *
     * The reset is a plain unconditional write rather than a CAS, and the asymmetry is deliberate.
     * The only interleaving it admits is a success landing between a failure's CAS and the next
     * failure's, which costs **one duplicate line** for a store that really did take a write in
     * between — a repeat is honest there. The shape that must not happen is the reverse: a latch
     * left `true` against a healthy store, which would silence the *next* outage entirely. An
     * unconditional write cannot lose, so it cannot leave the latch open.
     */
    private val durableWriteOutage = MutableStateFlow(false)

    private companion object {
        private val STORE_KEY = StoreKey("otel.spans")
        // alwaysUseByteString ensures traceId/spanId bytes are encoded as CBOR
        // major type 2 (byte string) rather than an array of integers, halving the
        // wire size vs. the default array encoding.
        private val cbor = Cbor { alwaysUseByteString = true }
        private val spanSerializer = ORSet.serializer(SpanRecord.serializer())
    }

    /**
     * Recover persisted span state from [store]. Call once at startup before
     * any calls to [export].
     *
     * If no persisted state exists, the exporter starts with an empty set. The stored set is
     * joined with whatever is already in memory, then evicted to [maxSpans], so a recovery never
     * drops a live span, nor a stored one beyond what the cap requires.
     *
     * **A refused read fails closed** (#3390). A refused read includes a `CancellationException` the
     * [store] mints at this live caller, from its own `withTimeout`, say; a cancellation of the
     * caller's own job still propagates (#3388). It is recorded as a recovery failure, and until a
     * read lands every write turn ([export], [merge], [clear]) makes the read first and, while it
     * is still refused, returns [ExportResult.Failure] without writing, so it never replaces spans
     * it could not see. Such a turn counts as a failed turn in [health], resolved by the first write
     * that lands. A refused export adds its span nowhere; a remote set [merge]d meanwhile stays in
     * memory, and the read that lands joins it with the stored set. An exporter nobody recovered
     * reads on its first write turn the same way.
     *
     * An undecodable entry is recorded as a recovery failure and starts the exporter fresh: no later
     * read could do better, and the next write replaces it.
     */
    public suspend fun recover(): Unit = ioMutex.withLock {
        when (val read = readSpans()) {
            is SpanRead.Refused -> spanReadRefused(read.cause)
            is SpanRead.Landed -> spanReadLanded(read)
        }
    }

    /**
     * The read a write turn makes before it writes, if none has landed: `null` when the turn may
     * write, or the refusal it must return. Call under [ioMutex].
     */
    private suspend fun readBeforeWrite(): ExportResult.Failure? {
        if (lock.withLock { readState == SpanReadState.Recovered }) return null
        return when (val read = readSpans()) {
            is SpanRead.Refused -> {
                spanReadRefused(read.cause)
                // A refused turn is a failed turn of the span write, so the write that lands resolves
                // it. It is not a refused write, so it opens no write outage (#3390).
                persistence.failed(spanWork, read.cause)
                ExportResult.Failure(read.cause)
            }
            is SpanRead.Landed -> {
                spanReadLanded(read)
                null
            }
        }
    }

    private suspend fun readSpans(): SpanRead {
        val bytes = try {
            store.read(STORE_KEY)
        } catch (cause: Throwable) {
            // A cancellation the store minted is a refused read; only our own propagates (#3388).
            currentCoroutineContext().ensureActive()
            return SpanRead.Refused(cause)
        } ?: return SpanRead.Absent
        return runCatchingCancellable { cbor.decodeFromByteArray(spanSerializer, bytes) }
            .fold(onSuccess = { SpanRead.Found(it) }, onFailure = { SpanRead.Undecodable(it) })
    }

    /**
     * A refused read is reported at ERROR the first time and at debug after that, since a store that
     * stays locked would otherwise log a stack trace per write turn. A [SpanReadState.Recovered]
     * exporter stays recovered: it holds every span it has written, so a refusal teaches it nothing.
     */
    private fun spanReadRefused(cause: Throwable) {
        persistence.recoveryFailed(cause)
        val first = lock.withLock {
            when (readState) {
                SpanReadState.Unread -> true.also { readState = SpanReadState.Unrecovered }
                SpanReadState.Unrecovered, SpanReadState.Recovered -> false
            }
        }
        if (first) {
            logger.error(cause) { "otel.spans: store read failed; writing nothing until a read lands" }
        } else {
            logger.debug { "otel.spans: store read still refused: $cause" }
        }
    }

    /**
     * Join what the read found with memory, evict the join's excess over the cap, and allow writes.
     * Memory holds no local export yet, only remote sets merged meanwhile, so the join is one
     * `piece` and the excess one batch.
     */
    private fun spanReadLanded(read: SpanRead.Landed) {
        if (read is SpanRead.Undecodable) {
            persistence.recoveryFailed(read.cause)
            logger.warn(read.cause) { "otel.spans: corrupt store entry, starting fresh" }
        }
        lock.withLock {
            if (read is SpanRead.Found) spans = read.spans.piece(spans)
            evictExcess()
            readState = SpanReadState.Recovered
        }
    }

    /**
     * Export one span: insert it into the CRDT and durably flush to [store].
     *
     * If a [WarpCausalClock] was supplied, an **unstamped** span is auto-stamped with
     * causal context before insert (#846) — an explicit [SpanRecord.causalStamp]
     * always wins. Until the clock's recovery read has landed (refused, or never made), each
     * export makes that read first; until it lands the span is saved **unstamped** and the
     * clock's entry is not written, so no dot already in the store is minted again (#3390). The
     * turn then counts only the span's write in [health].
     *
     * ## Crash-window invariant (#1053)
     *
     * The clock is persisted **before** the span's durable write, establishing the
     * invariant *the durable clock `seq` is always ≥ every durable span's dot*. A crash
     * in the two-write window can then only **lose** the span (retried on the next
     * export) — it can never strand a persisted span at a dot the recovered clock would
     * re-mint. Persisting the span first (the reverse order) leaves that hole: span at
     * `seq=N` durable, clock still `<N`, and the next [WarpCausalClock.tick] on restart
     * re-mints `N`, violating the clock's uniqueness guarantee.
     *
     * The invariant holds **unconditionally, including under concurrent `export()` on a
     * multi-threaded dispatcher**: the clock persist and the span write are serialized as
     * one ordered unit by an internal coroutine `Mutex`, so the last durable clock write
     * always reflects a `seq` ≥ every durable span's dot rather than being clobbered by an
     * older concurrent snapshot. The span snapshot is re-encoded inside that section, so a
     * concurrent add is never dropped by a stale snapshot either.
     *
     * On a failure or a cancellation, a freshly stamped span is removed from the in-memory
     * set, whether cancellation arrives inside the write or while waiting for the write
     * section, and the cancellation then propagates. So a caller that cancels an export
     * (through its own timeout, say) and retries the unstamped span holds one stamped copy,
     * and an export that is cancelled or fails with no retry **loses its span from memory**.
     * The clock is not rolled back: its sequence stays advanced and its frontier keeps the
     * removed dot, so the next stamp can name a predecessor that never resolves. That loses a
     * real edge too: [WarpCausalClock.tick] collapses the frontier, so the next span's only
     * path to earlier spans ran through the removed dot. The lost edge is tracked by #3028.
     *
     * This only undoes the live add. A failed or cancelled write may already have committed,
     * or may commit later, and because a recovered exporter's stamp enters memory before the
     * write section, a concurrent export's successful write can carry it as well. The store then
     * holds a copy memory has dropped, until the next write replaces the snapshot. A restart in
     * that window recovers it, and retrying the original unstamped span mints another dot and
     * creates a second stamped copy.
     *
     * Until a read of the stored spans has landed, the export adds nothing before the write
     * section, and inside it makes that read first (#3390). While the store refuses the read, the
     * export returns [ExportResult.Failure] having changed nothing: its span is in neither memory
     * nor the store, so the caller's retry is its only copy. A minted stamp is simply dropped; the
     * clock is not rolled back, as above. The read that lands joins the stored set, and the export
     * then adds and writes its span exactly as a recovered one does.
     *
     * Returns [ExportResult.Success] after the durable write returns. A failure in that section
     * returns [ExportResult.Failure], and so does a `CancellationException` the [store] mints at
     * this live caller from its own `withTimeout`: only the caller's own cancellation propagates
     * (#3388). Neither failure nor cancellation establishes that the span was not persisted. See [DurableStore.write]
     * for the indeterminate commit outcome.
     *
     * When the buffer is full, the eviction policy fires first, logging the
     * dropped span, then the new span is inserted, so the new span is never the victim.
     */
    public suspend fun export(span: SpanRecord): ExportResult {
        // Auto-stamp causal context (#846): a configured clock fills an unstamped
        // span so causal links form with no caller change; an explicit stamp wins.
        val stamped = when {
            causalClock == null -> span
            span.causalStamp != null -> span
            // An unrecovered clock mints nothing (#3390): the span is saved unstamped.
            else -> when (val tick = tickFrom(causalClock)) {
                is ClockTick.Minted -> span.copy(causalStamp = tick.stamp)
                ClockTick.Unrecovered -> span
            }
        }
        // A fresh dot was minted only when copy() produced a new instance; an explicit
        // or absent stamp returns `span` itself. Only a minted stamp needs rollback —
        // without a newly minted stamp, re-adding the same full value is idempotent.
        val minted = stamped !== span
        // A recovered exporter adds before the write section, as it always has. One whose read has not
        // landed adds nothing yet: the add would reuse a dot a stored span holds (#3390), so it waits
        // for the join inside the section.
        var added = lock.withLock {
            (readState == SpanReadState.Recovered).also { recovered -> if (recovered) insert(stamped) }
        }
        // Serialize the durable-write section across concurrent export()/merge() so the
        // clock persist and the span write are one ordered unit (#1053).
        return try {
            ioMutex.withLock {
                // Never write over spans a refused read missed (#3390). A refusal leaves nothing to
                // undo: the span was not added.
                readBeforeWrite()?.let { return@withLock it }
                if (!added) {
                    // The read has landed and joined the stored set, so this add mints above it and
                    // evicts from it exactly as the live path does.
                    lock.withLock { insert(stamped) }
                    added = true
                }
                var work = clockAndSpanWork
                val written = try {
                    // Re-encode the *latest* spans inside the section so no concurrent add is
                    // dropped by a stale snapshot; encoding before the persist keeps the
                    // persisted seq ≥ every dot in this snapshot (seq is monotonic).
                    val encoded = lock.withLock { cbor.encodeToByteArray(spanSerializer, spans) }
                    // Persist the clock BEFORE the span so the durable clock seq is always ≥
                    // every durable span's dot — a crash between the two writes can only lose
                    // the span, never re-mint its dot on recover (#1053). An unrecovered clock
                    // writes nothing, and no dot in this snapshot came from it (#3390).
                    if (causalClock?.persist(store) == ClockPersist.Unrecovered) work = spanWork
                    store.write(STORE_KEY, encoded)
                    Result.success(Unit)
                } catch (cause: Throwable) {
                    // Only this coroutine's own cancellation propagates, to the rollback below. One
                    // the store minted from its own `withTimeout` is a refused write (#3388).
                    currentCoroutineContext().ensureActive()
                    Result.failure(cause)
                }
                written.fold(
                    onSuccess = {
                        persistence.acknowledged(work)
                        durableWriteSucceeded()
                        ExportResult.Success
                    },
                    onFailure = { cause ->
                        persistence.failed(work, cause)
                        if (minted) removeFromMemory(stamped)
                        // Roll back before the outage report; the health count above records the turn
                        // either way. The caller's own cancellation propagates past this fold, so it is
                        // counted as neither acknowledged nor failed; reporting only opens an outage once.
                        durableWriteFailed(cause) {
                            logger.error(it) { "WarpSpanExporter: durable write failed for span ${stamped.spanId}" }
                        }
                    },
                )
            }
        } catch (cancelled: CancellationException) {
            // Around the whole section, not the write: a recovered waiter on ioMutex has its stamp in
            // memory too. The removal takes no suspension point, so it completes while this coroutine
            // is cancelled.
            if (minted && added) removeFromMemory(stamped)
            throw cancelled
        }
    }

    /**
     * Mint a stamp from [clock], first making its recovery read if none has landed: refused, or
     * never made (#3390). The read holds [ioMutex], the section every clock persist runs in, so it
     * never reads past a write this exporter is making. [ClockTick.Unrecovered] while the read is
     * still refused: the span goes out unstamped.
     */
    private suspend fun tickFrom(clock: WarpCausalClock): ClockTick {
        if (!clock.isArmed) ioMutex.withLock { clockRecovered(clock.retryRecovery(store)) }
        return clock.tick()
    }

    /**
     * Remove a freshly stamped value from memory. This does not undo a landed store write or prevent
     * a duplicate on retry after recovery. `remove` leaves concurrent adds of different spans alone.
     */
    private fun removeFromMemory(stamped: SpanRecord) {
        lock.withLock { spans = spans.piece { set -> set.remove(stamped) } }
    }

    /**
     * Read a snapshot of the current in-memory [ORSet] for gossip / anti-entropy.
     *
     * Once a read of the stored spans has landed, the set holds what it found, joined with every
     * remote set [merge]d and every span exported since, less the buffer cap's evictions and what
     * [clear] dropped. Until then it holds only the remote sets merged meanwhile: neither the
     * stored spans nor any local export, which waits for that read (#3390).
     */
    public fun snapshot(): ORSet<SpanRecord> = lock.withLock { spans }

    /**
     * Merge an [ORSet] received from another replica (via anti-entropy / gossip)
     * into this exporter's state, then flush the merged result to [store].
     *
     * If a [WarpCausalClock] was supplied, the remote replica's causal frontier is
     * folded into the local clock first, so the next auto-stamped span records those
     * remote dots as predecessors — the cross-replica happens-before path (#846).
     *
     * Idempotent: merging the same set twice produces the same result.
     *
     * Until a read of the stored spans has landed, the merge makes that read first, and while the
     * store refuses it returns [ExportResult.Failure] without writing (#3390). The remote set stays
     * in memory and is written with the stored one once a read lands.
     */
    public suspend fun merge(remote: ORSet<SpanRecord>): ExportResult {
        // Fold the remote replica's causal frontier so the next local tick records
        // those dots as predecessors — the cross-replica happens-before path (#846).
        causalClock?.observe(remote.elements.mapNotNull { it.causalStamp?.dot }.toSet())
        lock.withLock { spans = spans.piece(remote) }
        // Share the durable-write section with export() so a concurrent export()+merge()
        // cannot lost-update the STORE_KEY snapshot (#1053).
        return ioMutex.withLock {
            // The remote spans stay in memory; the write waits for a read to land (#3390).
            readBeforeWrite()?.let { return@withLock it }
            val written = try {
                val encoded = lock.withLock { cbor.encodeToByteArray(spanSerializer, spans) }
                store.write(STORE_KEY, encoded)
                Result.success(Unit)
            } catch (cause: Throwable) {
                // A cancellation the store minted is a refused write; only our own propagates (#3388).
                currentCoroutineContext().ensureActive()
                Result.failure(cause)
            }
            written.fold(
                onSuccess = {
                    persistence.acknowledged(spanWork)
                    durableWriteSucceeded()
                    ExportResult.Success
                },
                onFailure = { cause ->
                    persistence.failed(spanWork, cause)
                    durableWriteFailed(cause) {
                        logger.error(it) { "WarpSpanExporter: durable write failed during merge" }
                    }
                },
            )
        }
    }

    /**
     * Drop every span this exporter holds and persist the emptied set (#2208).
     *
     * Removal, not [ORSet.empty]: an `ORSet` removal **retains** `causal.context`, so the
     * retired dots stay witnessed and a peer re-merging the pre-clear adds is dominated rather
     * than resurrecting them. An emptied-by-reset set would re-mint dots this replica has
     * already used, and a peer whose context already holds one would treat the *new* span as
     * seen-and-removed — swallowing it silently.
     *
     * The key is rewritten rather than deleted, because the retained context is what the
     * paragraph above rests on and it lives in those bytes.
     *
     * One [ORSet.removeAll], not a per-element fold. Absorbing a patch is a causal join over the
     * whole set, so removing one element at a time pays a join per element: measured here at
     * [DEFAULT_MAX_SPANS] that fold was quadratic and cost seconds, which is why the bulk form
     * exists (#2245). The two are otherwise indistinguishable — same elements gone, same dots
     * retired, same retained context, same bytes.
     *
     * A configured [WarpCausalClock]'s **frontier** is emptied here too, and its `seq` left
     * alone. The frontier would otherwise name dots of spans this call just removed, so the next
     * auto-stamped span would carry predecessors that can never resolve — links pointing at
     * deliberately forgotten spans, which is noise rather than causality.
     *
     * Shares [ioMutex] with [export] and [merge] so a concurrent export cannot land a stale
     * encoded snapshot after the clear.
     *
     * **Never throws**, on the same terms as [export]: a refused durable write is reported as
     * [ExportResult.Failure] carrying the store's cause, never propagated. Retrying a failed
     * clear re-converges — [ORSet.removeAll] over an already-emptied set is the lattice
     * identity, emptying an already-empty frontier is too, and neither write is gated on the
     * drop having moved anything, so the retry writes the same bytes the first attempt would
     * have. Gating them on that is the obvious optimisation and it silently breaks the retry:
     * a failed clear has already emptied memory, so a gated retry would find nothing to drop,
     * write nothing, and report [ExportResult.Success] over a store that still holds every span.
     *
     * A clear whose read of the stored spans is still refused changes nothing, in memory or in the
     * store, and returns [ExportResult.Failure] (#3390). Dropping only what memory holds would let
     * every unread stored span come back on the next join.
     *
     * **On a failed write, [snapshot] already reads empty while the store still holds every span**, and
     * a configured clock's [WarpCausalClock.frontier] likewise reads empty while the persisted
     * one still names the pre-clear dots. Both mutations precede the writes and — unlike
     * [export], which rolls a freshly-minted stamp back out — neither is undone. A caller that
     * uses the span count as a baseline must treat any non-[ExportResult.Success] as *count
     * unknown* rather than as zero. On success the count reads zero synchronously, because the
     * drop precedes the write.
     */
    public suspend fun clear(): ExportResult = ioMutex.withLock {
        // A clear that cannot read the stored spans changes nothing: dropping memory and writing
        // the empty set would leave every unread stored span to come back on the next join (#3390).
        readBeforeWrite()?.let { return@withLock it }
        // A clock whose recovery read has not landed (refused, or never made) is read here too, so a
        // clear is not the turn that writes a `seq` below the durable one (#3390). Still refused,
        // the clear writes the spans and leaves the clock's entry alone.
        causalClock?.let { clockRecovered(it.retryRecovery(store)) }
        var work = clockAndSpanWork
        val written = try {
            val encoded = lock.withLock {
                spans = spans.piece { it.removeAll(it.elements) }
                cbor.encodeToByteArray(spanSerializer, spans)
            }
            // The frontier belongs to this method, not to the facade — so a caller reaching this
            // exporter directly, rather than through WarpTelemetry.clear(), gets it too. It names
            // dots of spans that no longer exist, and stamping the next span with predecessors
            // that can never resolve produces links to deliberately forgotten spans.
            // `seq` is deliberately untouched; see WarpCausalClock.clearFrontier.
            causalClock?.clearFrontier()
            // Clock before spans, the same order and for the same reason as export() (#1053).
            if (causalClock?.persist(store) == ClockPersist.Unrecovered) work = spanWork
            store.write(STORE_KEY, encoded)
            Result.success(Unit)
        } catch (cause: Throwable) {
            // A cancellation the store minted is a refused write; only our own propagates (#3388).
            currentCoroutineContext().ensureActive()
            Result.failure(cause)
        }
        written.fold(
            onSuccess = {
                persistence.acknowledged(work)
                durableWriteSucceeded()
                ExportResult.Success
            },
            onFailure = { cause ->
                persistence.failed(work, cause)
                durableWriteFailed(cause) {
                    logger.error(it) { "WarpSpanExporter: durable write failed during clear" }
                }
            },
        )
    }

    /**
     * The store took a write, so the next refusal is a new outage worth reporting.
     *
     * Call from the success arm of **every** durable write, including [clear]'s: leaving one out
     * would let a latch stay open across a store that demonstrably recovered, which silences the
     * next real outage — the one failure mode [durableWriteOutage] must not have.
     */
    private fun durableWriteSucceeded() {
        durableWriteOutage.value = false
    }

    /**
     * Report [cause] through [report] only if this failure **opens** an outage, and return the
     * failure either way.
     *
     * Call from the failure arm of a durable write and nowhere else — see [durableWriteOutage] for
     * why the population must be exactly the writes to [STORE_KEY]. The throwable is passed on to
     * [report] and kept on the line: unlike a refused sweep of superseded garbage, a refused
     * durable write is the store rejecting the application's own data, and at one line per outage
     * the trace is affordable.
     */
    private fun durableWriteFailed(cause: Throwable, report: (Throwable) -> Unit): ExportResult {
        if (durableWriteOutage.compareAndSet(expect = false, update = true)) report(cause)
        return ExportResult.Failure(cause)
    }

    /**
     * The live insert: evict one span if the set is at the cap, then add [stamped], so the incoming
     * span is never the victim. Must be called with [lock] held, on a recovered exporter.
     */
    private fun insert(stamped: SpanRecord) {
        if (spans.elements.size >= maxSpans) evictOne()
        spans = spans.piece { it.add(replica, stamped) }
    }

    /** Evict the [bufferPolicy]'s victim. Must be called with [lock] held. */
    private fun evictOne() {
        val current = spans.elements
        val victim = when (bufferPolicy) {
            BufferPolicy.DROP_OLDEST -> current.minByOrNull { it.startEpochNanos }
            BufferPolicy.DROP_NEWEST -> current.maxByOrNull { it.startEpochNanos }
        } ?: return
        logEviction(victim)
        spans = spans.piece { it.remove(victim) }
    }

    /**
     * Evict everything beyond [maxSpans] in one batch: the victims are picked once, by the order
     * repeated [evictOne] calls would pick them, and removed with one [ORSet.removeAll], since a
     * removal per victim would pay a causal join each (#2245). Must be called with [lock] held.
     */
    private fun evictExcess() {
        val current = spans.elements
        val excess = current.size - maxSpans
        if (excess <= 0) return
        // Stable sorts, so ties go in iteration order, as minBy/maxBy break them in evictOne.
        val victims = when (bufferPolicy) {
            BufferPolicy.DROP_OLDEST -> current.sortedBy { it.startEpochNanos }
            BufferPolicy.DROP_NEWEST -> current.sortedByDescending { it.startEpochNanos }
        }.take(excess).toSet()
        victims.forEach(::logEviction)
        spans = spans.piece { it.removeAll(victims) }
    }

    private fun logEviction(victim: SpanRecord) {
        logger.warn {
            "WarpSpanExporter: buffer cap ($maxSpans) reached, evicting span " +
                "traceId=${victim.traceId} spanId=${victim.spanId} name=${victim.name} " +
                "policy=$bufferPolicy"
        }
    }
}

/** The result of a [WarpSpanExporter.export] or [WarpSpanExporter.merge] call. */
public sealed interface ExportResult {
    /** The span was durably written to the local store. */
    public data object Success : ExportResult

    /** The durable write failed; whether the span was persisted is indeterminate. */
    public data class Failure(public val cause: Throwable) : ExportResult
}

/** Whether a [WarpSpanExporter] has read its stored spans. */
private sealed interface SpanReadState {
    /** No read has landed or been refused yet: an exporter starts here. */
    data object Unread : SpanReadState

    /** A read was refused before any landed, so a write would replace spans never seen. */
    data object Unrecovered : SpanReadState

    /** A read has landed and its spans are joined into memory, so a write carries them. */
    data object Recovered : SpanReadState
}

/** One read of the stored spans: refused, so worth retrying, or [Landed] on what the store holds. */
private sealed interface SpanRead {
    data class Refused(val cause: Throwable) : SpanRead

    /** The read reached the store. Only a refusal is worth retrying. */
    sealed interface Landed : SpanRead

    data object Absent : Landed

    data class Undecodable(val cause: Throwable) : Landed

    data class Found(val spans: ORSet<SpanRecord>) : Landed
}
