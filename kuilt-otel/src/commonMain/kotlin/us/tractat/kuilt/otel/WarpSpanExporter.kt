@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.otel

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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
 * When the in-memory CRDT already holds [maxSpans] spans, [export] evicts one as it
 * inserts: the earliest by [SpanRecord.startEpochNanos] under [BufferPolicy.DROP_OLDEST], the
 * latest under [BufferPolicy.DROP_NEWEST]. The victim is chosen from the spans already held,
 * so an export's own eviction never takes the span it is adding, and it is committed only with
 * the add, so an export whose add is refused evicts nothing (#3441). An export builds its add and
 * its eviction inside the durable-write section, writes them, and makes them visible only once the
 * write has returned (#3517), so the bytes an export writes always carry its own span, and an export
 * whose write fails evicts nothing from memory either. A later export's eviction can take it. That
 * is all the cap promises the incoming span. [merge] does not evict. The read of the stored spans
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
     * The set dots that failed export turns minted and never published: a set holding no span whose
     * context witnesses exactly those dots. Guarded by [lock], written only under [ioMutex].
     *
     * `ORSet.add` mints from the set's context, and a failed turn publishes nothing (#3517), so
     * without this the next export would mint the failed turn's dot again for a different span. That
     * matters because a failed write can still commit, after its caller stopped waiting and over
     * newer bytes ([DurableStore.write]): the store would then hold the failed span on the dot a peer
     * has from the next span, and a merge after a restart would drop both.
     *
     * So the next turn builds on [spans] joined with this, mints past it, and its bytes carry these
     * dots durably; it is emptied when that turn publishes. It is **never** joined into [spans]
     * directly: a context-only dot in [snapshot] before any write carries it would itself escape, as
     * a removal a peer applies to whatever span a restart mints on it.
     *
     * A restart loses it, which is safe: no peer was shown one of these dots, and a store that holds
     * one, through a late commit, holds it in its own context, so recovery mints past it. A [merge] or
     * [clear] write in between does not carry it, and does not need to: if it overwrites a late
     * commit, the dot is in no store and no peer's set.
     */
    private var unpublished: ORSet<SpanRecord> = ORSet.empty()

    /**
     * The highest `seq` of this replica's dots that this exporter knows is used, guarded by [lock]
     * (#3435). Every span write carries it in the bytes, so a later process whose clock entry is
     * corrupt floors above dots no stored span still holds: one evicted past [maxSpans], one [clear]
     * removed, one only a peer has.
     *
     * It only rises. It folds in the value the landing read found carried, every own dot a [merge]
     * brings, every own dot an export adds, an explicit stamp's included, and, at each encode, a
     * configured clock's `seq` once its read has landed. So it is at least every own dot [spans] has
     * held, and the landing read relies on that instead of scanning the join: a new path that puts an
     * own dot into [spans] must raise it too. A [clear]
     * leaves it alone, as the clock's `seq` is left alone. Over-claiming costs a gap in the clock's
     * sequence, which nothing reads as missing: [inferCausalLinks] resolves a dot by equality alone.
     */
    private var ownHighWater: Long = 0L

    /**
     * Whether this exporter has read the stored spans, guarded by [lock] and landed only under
     * [ioMutex]. A write turn writes the whole set over [STORE_KEY], so until a read has landed it
     * would replace spans it never saw (#3390). Every write turn therefore reads first, or skips the
     * read inside a refusal's backoff window (#3436), and writes only once the state is
     * [SpanReadState.Recovered], which nothing moves it out of.
     *
     * Until then a local export adds nothing to [spans]. An `ORSet` add mints the next dot from the
     * set's own context, and before the read that context lacks every stored dot, so the add would
     * reuse a dot a stored span already holds and the join would conflate the two. [export] builds
     * its add only inside [ioMutex], after this turn's read has landed and joined the stored set, as
     * every export does. A refused read leaves it nothing to undo.
     */
    private var readState: SpanReadState = SpanReadState.Unread
    private val persistence = PersistenceHealthTracker<StoreKey>()
    private val spanWork = setOf(STORE_KEY)
    private val clockWork = setOf(WarpCausalClock.STORE_KEY)
    private val clockAndSpanWork = if (causalClock == null) spanWork else spanWork + clockWork

    /**
     * Completed persistence turns and unresolved failures, independent of outage logging.
     * Includes a configured clock's write as part of export/clear, not a second acknowledgement.
     * A turn that skips the clock's write, because its read has not landed, counts per work key
     * instead: its span work as the span write went, and its clock work as one failed turn that only
     * a later clock write resolves (#3426). So does an export that wanted a dot from an exhausted
     * clock ([ClockTick.Exhausted]) and saved its span unstamped, though its clock write lands; the
     * turn that mints the last dot, a clear, and a span with its own stamp want no dot they cannot
     * get, so they are not counted, and their clock write resolves those failures. Recovery covers
     * the span buffer; [WarpTelemetry.recover] also reports its clock's recovery.
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
     * Make the read [clock] still needs, or skip it inside the clock's backoff window (#3436), and
     * return what the turn met. A skip is reported as the refused read it stands for, with the last
     * refusal's cause, and makes no store call; the turn spends it through [spendClockSkip] once its
     * reads are done.
     */
    private suspend fun retryClockRead(clock: WarpCausalClock): ClockReadTurn {
        clock.owedReadSkip()?.let { window ->
            persistence.recoveryFailed(window.cause)
            logger.debug { "otel.causal.clock: store read was refused; this turn skips it and the clock: ${window.cause}" }
            return ClockReadTurn(window.cause, window)
        }
        val outcome = clock.retryRecovery(store)
        clockRecovered(outcome)
        return ClockReadTurn((outcome as? ClockRecovery.Refused)?.cause, skipped = null)
    }

    /**
     * Spend the turn of the clock's window that [turn] skipped, if it skipped one. Called once the
     * turn's reads are done, so a caller cancelled in a later read spends nothing; and a skip makes no
     * store call, so it asks `ensureActive` first, as a read's own `ensureActive` would.
     */
    private suspend fun spendClockSkip(clock: WarpCausalClock?, turn: ClockReadTurn?) {
        val window = turn?.skipped ?: return
        currentCoroutineContext().ensureActive()
        checkNotNull(clock).spendSkip(window)
    }

    /**
     * Persist [clock] ahead of the span write, and return why the turn's clock work failed though
     * nothing threw: a skipped write, with [refusal] as the cause (#3426), or `null` once the write
     * lands. A refused write throws.
     *
     * [refusal] is this turn's retry of the clock's read, which every turn makes while the clock is
     * unarmed, or the last refusal's cause when the turn skipped that read inside its window (#3436).
     * So a skipped write always has one: a read that lands arms the clock, nothing disarms it, and a
     * clock the retry found armed was armed already. A skip without one would be a bug here, and it
     * throws, which fails the whole turn closed.
     */
    private suspend fun persistClock(clock: WarpCausalClock, refusal: Throwable?): Throwable? =
        when (clock.persist(store)) {
            ClockPersist.Unrecovered -> checkNotNull(refusal) { "otel.causal.clock: skipped its write with no refused read this turn" }
            ClockPersist.Persisted -> null
        }

    /**
     * Count one write turn in [health], per work key (#3426). A turn whose writes all ran counts once,
     * over the span's key and a configured clock's together, as [writeFailure] says. A turn that
     * skipped the clock's write, or that wanted a dot from an exhausted clock and saved its span
     * unstamped ([clockFailure] is why), counts its span work by [writeFailure] and its clock work as a
     * failed turn under the clock's key alone. So a clock refused forever stays outstanding however
     * many spans land, and exhaustion counts once per span that wanted a dot. The first turn after
     * either that lands the clock's write and wanted no dot it could not get resolves them: a span with
     * its own stamp, or a clear. The span work is counted last, so [PersistenceHealth.lastFailure]
     * names a refused write over a clock failure.
     */
    private fun countTurn(writeFailure: Throwable?, clockFailure: Throwable?) {
        val work = if (clockFailure == null) {
            clockAndSpanWork
        } else {
            persistence.failed(clockWork, clockFailure)
            spanWork
        }
        if (writeFailure == null) persistence.acknowledged(work) else persistence.failed(work, writeFailure)
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

        /** Appended to a span's name to find a value the set does not hold; see `mintedDotOf`. */
        private const val UNPUBLISHED_PROBE_SUFFIX = "\u0000"

        /** The cause a turn that wanted a dot from an exhausted clock is counted with: it had none left. */
        private fun exhaustedClock(): Throwable =
            IllegalStateException("otel.causal.clock: seq is Long.MAX_VALUE, so spans are saved unstamped")

        // alwaysUseByteString ensures traceId/spanId bytes are encoded as CBOR
        // major type 2 (byte string) rather than an array of integers, halving the
        // wire size vs. the default array encoding.
        private val cbor = Cbor { alwaysUseByteString = true }
        private val storedSerializer = StoredSpans.serializer()
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
     * The turns' re-reads back off, counted in write turns (#3436): after `k` refused reads in a
     * row, the next `2^k − 1` turns, `k` capped at 6 so at most 63, refuse as a refused read does,
     * with its cause and as a failed turn, without touching the store. A long outage then costs one
     * read of the whole span blob per 64 turns rather than one per turn, each paying the store's
     * timeout. This call is an explicit request, so it **always reads**, window or not. A refusal
     * here counts toward `k` like any other, and the read that lands, here or in a turn, ends the
     * window for good, since a recovered exporter never reads again.
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
     *
     * Inside a refusal's backoff window ([SpanReadState.Unrecovered.backoff], #3436) the turn skips
     * the read and refuses exactly as a refused read does, with the last refusal's cause, and spends
     * one turn of the window. It is the turn's last read: an [export]'s retry of the clock's read
     * comes before it. A skip makes no store call, so it asks `ensureActive` before spending, and a
     * cancelled caller is cancelled here as a read's `ensureActive` cancels it, spending nothing.
     */
    private suspend fun readBeforeWrite(): ExportResult.Failure? {
        val state = lock.withLock { readState }
        if (state == SpanReadState.Recovered) return null
        if (state is SpanReadState.Unrecovered && state.backoff.skipTurns > 0) {
            currentCoroutineContext().ensureActive()
            lock.withLock {
                // ioMutex is the guarantee: this runs under it, and so does every writer of readState,
                // so nothing replaced the window since it was read above. The check fails loudly if
                // that ever stops being true, rather than spending a window this turn did not read.
                check(readState === state) { "otel.spans: the read window was replaced outside ioMutex" }
                readState = state.skipped()
            }
            logger.debug { "otel.spans: store read was refused; this turn skips the read and writes nothing: ${state.backoff.cause}" }
            return refusedTurn(state.backoff.cause)
        }
        return when (val read = readSpans()) {
            is SpanRead.Refused -> {
                spanReadRefused(read.cause)
                refusedTurn(read.cause)
            }
            is SpanRead.Landed -> {
                spanReadLanded(read)
                null
            }
        }
    }

    /**
     * A turn the refused or skipped read stopped is a failed turn of the span write, so the write
     * that lands resolves it. It is not a refused write, so it opens no write outage (#3390).
     */
    private fun refusedTurn(cause: Throwable): ExportResult.Failure {
        persistence.failed(spanWork, cause)
        return ExportResult.Failure(cause)
    }

    private suspend fun readSpans(): SpanRead {
        val bytes = try {
            store.read(STORE_KEY)
        } catch (cause: Throwable) {
            // A cancellation the store minted is a refused read; only our own propagates (#3388).
            currentCoroutineContext().ensureActive()
            return SpanRead.Refused(cause)
        } ?: return SpanRead.Absent
        return runCatchingCancellable { cbor.decodeFromByteArray(storedSerializer, bytes) }
            .fold(onSuccess = { SpanRead.Found(it) }, onFailure = { SpanRead.Undecodable(it) })
    }

    /**
     * A refused read is reported at ERROR the first time and at debug after that, since a store that
     * stays locked would otherwise log a stack trace per write turn. A [SpanReadState.Recovered]
     * exporter stays recovered: it holds every span it has written, so a refusal teaches it nothing.
     * Otherwise it is one more refusal in a row, so a longer window; see [RefusedReadBackoff.after].
     */
    private fun spanReadRefused(cause: Throwable) {
        persistence.recoveryFailed(cause)
        val first = lock.withLock {
            when (val current = readState) {
                SpanReadState.Unread -> true.also { readState = SpanReadState.Unrecovered(RefusedReadBackoff.after(null, cause)) }
                is SpanReadState.Unrecovered -> false.also { readState = SpanReadState.Unrecovered(RefusedReadBackoff.after(current.backoff, cause)) }
                SpanReadState.Recovered -> false
            }
        }
        if (first) {
            logger.error(cause) { "otel.spans: store read failed; writing nothing until a read lands" }
        } else {
            logger.debug { "otel.spans: store read still refused: $cause" }
        }
    }

    /**
     * Join what the read found with memory, raise the clock's floor, evict the join's excess over the
     * cap, and allow writes. Memory holds no local export yet, only remote sets merged meanwhile, so
     * the join is one `piece` and the excess one batch.
     *
     * The floor is [ownHighWater] once the carried mark (#3435) is folded in, raised **before** the
     * eviction so an evicted span's dot still counts (#3407). That covers every own dot in the join,
     * a span's stamp and every predecessor it names, with no scan of the join: each path that puts an
     * own dot into [spans] raises the mark by it. A stored span's dot was in memory when its bytes were
     * encoded, so the mark they carry covers it, and a merged span's dot was folded in by [merge]. The
     * carried mark also covers the dots no stored span holds any more: evicted before the last write,
     * cleared, or held only by a peer. Once this replica's span is evicted, a remote span that observed
     * it may be the only evidence its dot was minted, and re-minting it would resolve that predecessor
     * to a new span. The floor is raised before the state turns [SpanReadState.Recovered], in the same
     * hold of [lock], and [export] mints only from a recovered exporter under that lock, so no stamp is
     * minted below it. A clock whose own entry was corrupt starts at `seq=0`
     * and would otherwise re-mint those dots.
     *
     * An undecodable entry, the bare set this key held before #3435 included, starts fresh and loses
     * its carried mark with it: the floor then rests on the clock's entry and on what [merge]s folded
     * in meanwhile.
     */
    private fun spanReadLanded(read: SpanRead.Landed) {
        if (read is SpanRead.Undecodable) {
            persistence.recoveryFailed(read.cause)
            logger.warn(read.cause) { "otel.spans: corrupt store entry, starting fresh" }
        }
        lock.withLock {
            if (read is SpanRead.Found) {
                spans = read.stored.spans.piece(spans)
                raiseHighWater(read.stored.ownHighWater)
            }
            causalClock?.raiseFloor(ownHighWater)
            evictExcess()
            readState = SpanReadState.Recovered
        }
    }

    /**
     * The highest `seq` among this replica's dots in [set], stamps and predecessors both. Must be
     * called with [lock] held when [set] is [spans]'s.
     */
    private fun highestOwnDot(set: Collection<SpanRecord>): Long? = set
        .flatMap { span -> span.causalStamp?.let { it.predecessors + it.dot }.orEmpty() }
        .filter { it.replica == replica }
        .maxOfOrNull { it.seq }

    /** Fold [usedSeq] into [ownHighWater], which only rises. Must be called with [lock] held. */
    private fun raiseHighWater(usedSeq: Long) {
        if (usedSeq > ownHighWater) ownHighWater = usedSeq
    }

    /**
     * Encode [set] with the high-water mark this write carries (#3435). A configured clock's `seq`
     * is folded in first, so the mark covers every dot it minted, an unpublished turn's and an evicted
     * span's included, and the clock entry this process read. Every dot in [set] was minted before
     * this encode, so the mark is at least each of them. Must be called with [lock] held.
     */
    private fun encodeSpans(set: ORSet<SpanRecord>): ByteArray {
        causalClock?.highWater()?.let(::raiseHighWater)
        return cbor.encodeToByteArray(storedSerializer, StoredSpans(set, ownHighWater))
    }

    /**
     * Export one span: insert it into the CRDT and durably flush to [store].
     *
     * If a [WarpCausalClock] was supplied, an **unstamped** span is auto-stamped with
     * causal context before insert (#846) — an explicit [SpanRecord.causalStamp]
     * always wins. Until the clock's recovery read has landed (refused, or never made), each
     * export makes that read first, or skips it inside the clock's backoff window as the span read
     * backs off (see [recover]); until it lands the span is saved **unstamped** and the
     * clock's entry is not written, so no dot already in the store is minted again (#3390). The
     * turn then counts in [health] per work key: the span's write as it went, and the skipped clock
     * write as a failed turn the first clock write that lands resolves (#3426).
     *
     * An explicit stamp of this replica's, its own dot or a predecessor it names, raises the clock's
     * floor when the span is added, so the clock never mints that dot itself (#3435). Its `seq` is
     * taken as given: a stamp claiming [Long.MAX_VALUE] leaves the clock [ClockTick.Exhausted].
     *
     * Every write carries this replica's high-water mark in the span bytes, at least every dot the
     * clock has minted and every own dot the set has held. A restart whose clock entry is corrupt
     * floors the clock above it, so a dot whose span was since evicted or cleared is not minted again
     * (#3435).
     *
     * ## The span is published only after its writes return (#3517)
     *
     * The whole turn runs inside an internal write section: the stamp, the add and the eviction are
     * built on a **candidate** set, the candidate is written, and only once both writes have returned
     * is it joined into the set [snapshot] and [merge] read. So no dot this turn mints is visible
     * before the store holds it. The span carries two of this replica's dots, its [CausalStamp]'s and
     * the one the `ORSet` mints for the add, and either one shown to a peer ahead of its write could
     * be minted again by a restart whose store never held it. Two spans sharing the stamp's dot
     * conflate their causal links, and two adds sharing the set's dot make a merge drop **both** spans.
     *
     * A failed or cancelled turn publishes nothing, so memory never holds a span the turn could not
     * write, and there is nothing to roll back. The clock is not rolled back either: its sequence stays
     * advanced and its frontier keeps the unpublished dot, so the next stamp can name a predecessor that
     * never resolves. That loses a real edge too: [WarpCausalClock.tick] collapses the frontier, so the
     * next span's only path to earlier spans ran through that dot. The lost edge is tracked by #3028.
     *
     * A failed or cancelled write may already have committed, or may commit later. The store then
     * holds a span memory never published, until the next write replaces the snapshot. A restart in
     * that window recovers it, and retrying the original unstamped span mints another dot and creates
     * a second stamped copy.
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
     * older concurrent snapshot. The candidate is built from the latest set inside that section, so
     * a concurrent add is never dropped by a stale snapshot either, and a [merge] that joins a remote
     * set while the writes are in flight is kept, since publishing joins rather than replaces.
     *
     * Until a read of the stored spans has landed, the export makes that read first inside the write
     * section (#3390), or refuses without it inside the backoff window [recover] describes (#3436).
     * While the store refuses the read, the export returns [ExportResult.Failure] having changed
     * nothing, the clock included: its span is in neither memory nor the store, so the caller's retry
     * is its only copy. The read that lands joins the stored set and raises the clock above every dot
     * of this replica's it found (#3407), and the export then builds, writes and publishes its span
     * as any other does.
     *
     * Returns [ExportResult.Success] after the durable write returns, with the span in [snapshot]. A
     * failure in that section returns [ExportResult.Failure], and so does a `CancellationException`
     * the [store] mints at this live caller from its own `withTimeout`: only the caller's own
     * cancellation propagates (#3388). Neither failure nor cancellation establishes that the span was
     * not persisted. See [DurableStore.write] for the indeterminate commit outcome.
     *
     * When the buffer is full, the eviction policy picks a victim among the spans already held and
     * the new span is inserted, so this export's own eviction never takes it, and the bytes it writes
     * always carry it. A later export's eviction can take it.
     *
     * **An add the set refuses changes nothing.** Past the set's own clock ceiling, which a [merge]
     * of a peer's set naming this replica's dot near [Long.MAX_VALUE] can bring within reach, the
     * add throws `ArithmeticException` (#3429). The export then evicts, stamps and writes nothing,
     * counts one failed turn of its span work in [health], and returns that exception as the
     * [ExportResult.Failure]'s cause (#3441). The set's context never moves back, so once it is at
     * the ceiling every export is refused this way.
     */
    public suspend fun export(span: SpanRecord): ExportResult {
        // Auto-stamp causal context (#846): a configured clock fills an unstamped
        // span so causal links form with no caller change; an explicit stamp wins.
        val clock = causalClock?.takeIf { span.causalStamp == null }
        // A configured clock whose recovery read has not landed (refused, or never made) makes it first
        // (#3390), whatever the span's stamp: the turn writes the clock either way, so an explicitly
        // stamped span that skipped the read would count a clock failure its turn never retried (#3426).
        // The read holds ioMutex, the section every clock persist runs in, so it never reads past a
        // write this exporter is making.
        // Inside the clock's backoff window that read is skipped, and the skip is spent after the span
        // read below (#3436).
        val clockRead = causalClock?.takeIf { !it.isArmed }?.let { ioMutex.withLock { retryClockRead(it) } }
        val clockRefusal = clockRead?.refusal
        // Serialize the durable-write section across concurrent export()/merge()/clear() so the clock
        // persist and the span write are one ordered unit (#1053), and so nothing this turn mints is
        // published before both have returned (#3517).
        return ioMutex.withLock {
            // Never write over spans a refused read missed (#3390). A refusal leaves nothing to
            // undo: the span was neither stamped nor added. The span read is the turn's last, so
            // a skip of the clock's read is spent after it, refused or not (#3436).
            val unread = readBeforeWrite()
            spendClockSkip(causalClock, clockRead)
            unread?.let { return@withLock it }
            // What this turn built, to publish once its writes return; `null` until it is built.
            var candidate: Candidate? = null
            // Why this turn's clock work failed though its writes ran, or `null` when it did not.
            var clockFailure: Throwable? = null
            val written = try {
                val encoded = lock.withLock {
                    // The read has landed, joined the stored set and raised the clock's floor, so
                    // this stamp mints above every stored dot (#3407), and this add mints above the
                    // set's dots and evicts from it. Built from the latest set, so no concurrent add
                    // is dropped by a stale snapshot, and encoded in the same hold of `lock`.
                    val built = candidateFor(span, clock)
                    candidate = built
                    encodeSpans(built.set)
                }
                // Persist the clock BEFORE the span so the durable clock seq is always ≥
                // every durable span's dot — a crash between the two writes can only lose
                // the span, never re-mint its dot on recover (#1053). An unrecovered clock
                // writes nothing, and no dot in this snapshot came from it (#3390). A turn that
                // wanted a dot from an exhausted clock writes the clock, and still failed its clock
                // work: its span is saved unstamped (#3426).
                clockFailure = causalClock?.let { persistClock(it, clockRefusal) }
                    ?: if (candidate?.stamping?.exhausted == true) exhaustedClock() else null
                store.write(STORE_KEY, encoded)
                Result.success(Unit)
            } catch (refused: CeilingRefusal) {
                // The add was refused before anything moved or was written: a refused turn, not
                // a refused write, so it opens no write outage (#3441).
                return@withLock refusedAtCeiling(refused.ceiling)
            } catch (cause: Throwable) {
                // The write may still commit, so the next turn must not mint this turn's set dot
                // again, whether the turn failed or its caller was cancelled. Kept before the
                // cancellation check, and with no suspension point, so a cancelled turn keeps it too.
                candidate?.let(::keepUnpublished)
                // Only this coroutine's own cancellation propagates, publishing nothing. One the
                // store minted from its own `withTimeout` is a refused write (#3388).
                currentCoroutineContext().ensureActive()
                Result.failure(cause)
            }
            written.fold(
                onSuccess = {
                    // Both writes returned, so every dot the candidate minted is durable: only now
                    // may a peer see it (#3517). No suspension point lies between the write and here.
                    candidate?.let(::publish)
                    countTurn(writeFailure = null, clockFailure)
                    durableWriteSucceeded()
                    ExportResult.Success
                },
                onFailure = { cause ->
                    // Nothing was published, so there is nothing to roll back; the set dot was kept.
                    countTurn(writeFailure = cause, clockFailure)
                    durableWriteFailed(cause) {
                        logger.error(it) { "WarpSpanExporter: durable write failed for span ${span.spanId}" }
                    }
                },
            )
        }
    }

    /**
     * [span] stamped by [clock], or [span] itself when there is no clock to stamp it (none
     * configured, or the span's explicit stamp wins), or when [clock] is unrecovered or exhausted and mints
     * nothing, so the span is saved unstamped (#3390). Only an exhausted tick marks the [Stamping]
     * exhausted: the turn that mints the last dot got one (#3426). Called with [lock] held, so a
     * recovered exporter mints only above the floor its landing read raised (#3407).
     */
    private fun stampOf(span: SpanRecord, clock: WarpCausalClock?): Stamping = when (val tick = clock?.tick()) {
        is ClockTick.Minted -> Stamping(span.copy(causalStamp = tick.stamp), exhausted = false)
        ClockTick.Exhausted -> Stamping(span, exhausted = true)
        ClockTick.Unrecovered, null -> Stamping(span, exhausted = false)
    }

    /**
     * Make [candidate] the set [snapshot] and [merge] read, once its writes have returned (#3517).
     * Must be called under [ioMutex]: no other turn can then have changed [spans] since the candidate
     * was built except a [merge]'s join of a remote set, which runs before its own write section. So
     * the candidate replaces an unchanged set outright, and is joined into a merged one, which keeps
     * the remote spans and still drops the candidate's victim, whose dots the candidate has seen.
     *
     * The candidate was built on [unpublished] too, so its bytes carried every dot a failed turn kept,
     * and those are now durable: [unpublished] empties with the publish.
     */
    private fun publish(candidate: Candidate) {
        lock.withLock {
            spans = if (spans === candidate.base) candidate.set else spans.piece(candidate.set)
            unpublished = ORSet.empty()
        }
        candidate.victim?.let(::logEviction)
    }

    /**
     * Keep the set dot [candidate] minted, since its turn failed or was cancelled and published
     * nothing. Joined, not replaced: a run of failed turns keeps every dot, and a context mints past
     * its contiguous run only, so a hole left by a replaced one would be minted again. Must be called
     * under [ioMutex], which every writer of [unpublished] holds.
     */
    private fun keepUnpublished(candidate: Candidate) {
        lock.withLock { unpublished = unpublished.piece(candidate.minted) }
    }

    /**
     * Read a snapshot of the current in-memory [ORSet] for gossip / anti-entropy.
     *
     * Once a read of the stored spans has landed, the set holds what it found, joined with every
     * remote set [merge]d and every span exported since, less the buffer cap's evictions and what
     * [clear] dropped. Until then it holds only the remote sets merged meanwhile: neither the
     * stored spans nor any local export, which waits for that read (#3390).
     *
     * A local export shows here only once its writes have returned (#3517), so every dot this
     * process's exports minted that the set holds was durable when it showed. An export whose writes
     * are in flight, or failed, is not here.
     *
     * Durable is not final, though. A failed write can still commit after its caller stopped waiting,
     * over newer bytes ([DurableStore.write]). The exporter keeps such a turn's set dot, so the span
     * shown next never shares it. What that leaves open: a restart over the late commit reads a set
     * context that lacks the set dots of spans shown after the failed one, so an export it makes
     * before a peer hands those spans back can mint one of them again. The clock's dots are not
     * affected, since the clock key is written ahead of each span write and only after it returns.
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
     * The clock's floor is raised too, to the highest dot of this replica's the remote set carries
     * or names, in the same hold of `lock` as the join (#3407). A peer can hand back spans this
     * replica minted and then lost, and a stamp minted after they land must not collide with one. The
     * high-water mark the span bytes carry (#3435) already covers every own dot that was in memory
     * when a span write was encoded, and [snapshot] shows an export only once its writes have landed
     * (#3517), so a stamp minted before the merge collides only with a dot whose durable record this
     * store has since lost, as a corrupt entry does.
     * The raise takes the peer's `seq` as given: a dot at `Long.MAX_VALUE` leaves the clock
     * [ClockTick.Exhausted], so later spans are saved unstamped. Whether a merge should refuse such
     * a dot is #3432.
     *
     * Idempotent: merging the same set twice produces the same result.
     *
     * Until a read of the stored spans has landed, the merge makes that read first, or skips it inside
     * the backoff window [recover] describes (#3436), and while the read is refused or skipped it
     * returns [ExportResult.Failure] without writing (#3390). The remote set stays
     * in memory and is written with the stored one once a read lands.
     */
    public suspend fun merge(remote: ORSet<SpanRecord>): ExportResult {
        // Fold the remote replica's causal frontier so the next local tick records
        // those dots as predecessors — the cross-replica happens-before path (#846).
        causalClock?.observe(remote.elements.mapNotNull { it.causalStamp?.dot }.toSet())
        lock.withLock {
            spans = spans.piece(remote)
            highestOwnDot(remote.elements)?.let { usedSeq ->
                raiseHighWater(usedSeq)
                causalClock?.raiseFloor(usedSeq)
            }
        }
        // Share the durable-write section with export() so a concurrent export()+merge()
        // cannot lost-update the STORE_KEY snapshot (#1053).
        return ioMutex.withLock {
            // The remote spans stay in memory; the write waits for a read to land (#3390).
            readBeforeWrite()?.let { return@withLock it }
            val written = try {
                val encoded = lock.withLock { encodeSpans(spans) }
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
     * A clear whose read of the stored spans is still refused, or skipped inside the backoff window
     * [recover] describes (#3436), changes nothing, in memory or in the store, and returns
     * [ExportResult.Failure] (#3390). Dropping only what memory holds would let
     * every unread stored span come back on the next join.
     *
     * **On a failed write, [snapshot] already reads empty while the store still holds every span**, and
     * a configured clock's [WarpCausalClock.frontier] likewise reads empty while the persisted
     * one still names the pre-clear dots. Both mutations precede the writes and — unlike
     * [export], which publishes its span only once its writes return — neither is undone. A caller that
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
        // the clear writes the spans and leaves the clock's entry alone, a failed clock turn (#3426).
        val clockRead = causalClock?.let { retryClockRead(it) }
        spendClockSkip(causalClock, clockRead)
        val clockRefusal = clockRead?.refusal
        var clockFailure: Throwable? = null
        val written = try {
            val encoded = lock.withLock {
                spans = spans.piece { it.removeAll(it.elements) }
                // The high-water mark is not reset: the removed spans' dots stay used (#3435).
                encodeSpans(spans)
            }
            // The frontier belongs to this method, not to the facade — so a caller reaching this
            // exporter directly, rather than through WarpTelemetry.clear(), gets it too. It names
            // dots of spans that no longer exist, and stamping the next span with predecessors
            // that can never resolve produces links to deliberately forgotten spans.
            // `seq` is deliberately untouched; see WarpCausalClock.clearFrontier.
            causalClock?.clearFrontier()
            // Clock before spans, the same order and for the same reason as export() (#1053).
            clockFailure = causalClock?.let { persistClock(it, clockRefusal) }
            store.write(STORE_KEY, encoded)
            Result.success(Unit)
        } catch (cause: Throwable) {
            // A cancellation the store minted is a refused write; only our own propagates (#3388).
            currentCoroutineContext().ensureActive()
            Result.failure(cause)
        }
        written.fold(
            onSuccess = {
                countTurn(writeFailure = null, clockFailure)
                durableWriteSucceeded()
                ExportResult.Success
            },
            onFailure = { cause ->
                countTurn(writeFailure = cause, clockFailure)
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
     * Build this turn's [Candidate] from [spans]: stamp [span] by [clock], evict one span if the set
     * is at the cap, and add the stamped span, so this turn never evicts the span it adds; a later one
     * may. [spans] itself is left alone: [publish] makes the candidate visible once its writes have
     * returned (#3517). Must be called with [lock] held, under [ioMutex], on a recovered exporter.
     *
     * ## Refuse first, then build
     *
     * [ORSet.add] throws `ArithmeticException` once this replica's seq in the set's context is at
     * [Long.MAX_VALUE] (#3429), and a [merge] can put it there. In the old order (evict, then add) a
     * refused export had already dropped a span from memory, and its clock had already ticked for a
     * span nothing would hold (#3441). So the add is tried first, on the unstamped span, and its
     * refusal is thrown as a [CeilingRefusal] before the clock ticks. The trial is exact rather than a
     * check that approximates it: an add's dot comes from the set's context and [replica] alone,
     * never from the element, and the trial and the real add run on the same receiver (the set after
     * the eviction, built first as a pure local), so the trial refuses exactly when the real add
     * would, even when the store's dots are not all in its context. The clock has no
     * way to take a tick back, which is why the trial comes before it rather than a rollback after.
     *
     * Everything after the add is field writes and floor raises (`maxOf`), none of which can throw;
     * keep it that way.
     */
    private fun candidateFor(span: SpanRecord, clock: WarpCausalClock?): Candidate {
        val base = spans
        // Past every set dot a failed turn kept, so this add never mints one of them again. The join
        // removes nothing: those dots are on no span, so no element of [base] carries one.
        val start = if (unpublished == ORSet.empty<SpanRecord>()) base else base.piece(unpublished)
        val victim = if (start.elements.size >= maxSpans) victimOf(start.elements) else null
        val evicted = victim?.let { chosen -> start.piece { it.remove(chosen) } } ?: start
        val trial = try {
            evicted.add(replica, span)
        } catch (ceiling: ArithmeticException) {
            throw CeilingRefusal(ceiling)
        }
        val stamping = stampOf(span, clock)
        val added = evicted.piece { it.add(replica, stamping.span) }
        val minted = mintedDotOf(evicted, trial.delta)
        // An explicit stamp of this replica's is a used dot the clock did not mint: raise the clock
        // now, so it never mints that dot, and the mark, so a restart floors above it once its span is
        // evicted (#3435). A minted stamp is the clock's `seq`, so for one this leaves the clock alone.
        // Raised before the writes, and kept if they fail: a raise only ever costs a gap.
        highestOwnDot(listOf(stamping.span))?.let { usedSeq ->
            raiseHighWater(usedSeq)
            causalClock?.raiseFloor(usedSeq)
        }
        return Candidate(base, added, victim, stamping, minted)
    }

    /**
     * A set holding no span whose context witnesses exactly the dot an add to [evicted] mints, built
     * from [trialAdd], the delta of the trial add of the unstamped span on [evicted]. An add's dot
     * comes from the context alone, so it is the dot the real add minted too.
     *
     * The add's delta also witnesses the dots it supersedes when the set already holds that very
     * value, and kept as a removal those would drop the held copy from the next turn. So in that case
     * the dot is taken from an add of a value the set does not hold: a copy of the span renamed until
     * it is absent, which the finite set guarantees. Neither value ever enters [spans] or the store.
     */
    private fun mintedDotOf(evicted: ORSet<SpanRecord>, trialAdd: ORSet<SpanRecord>): ORSet<SpanRecord> {
        val added = trialAdd.elements.single()
        val absent = generateSequence(added) { it.copy(name = it.name + UNPUBLISHED_PROBE_SUFFIX) }
            .first { it !in evicted.elements }
        val delta = if (absent === added) trialAdd else evicted.add(replica, absent).delta
        return delta.piece { it.remove(absent) }
    }

    /** The [bufferPolicy]'s victim among [current], or `null` when it is empty. */
    private fun victimOf(current: Set<SpanRecord>): SpanRecord? = when (bufferPolicy) {
        BufferPolicy.DROP_OLDEST -> current.minByOrNull { it.startEpochNanos }
        BufferPolicy.DROP_NEWEST -> current.maxByOrNull { it.startEpochNanos }
    }

    /**
     * A turn whose add the set's clock ceiling refused (#3441): count one failed turn of the span
     * work, which the next span write that lands resolves, and return the ceiling's exception as the
     * failure. It made no write, so it opens no write outage (see [durableWriteOutage]); and it is
     * logged at debug only, since a set at the ceiling stays there and every export would repeat it.
     */
    private fun refusedAtCeiling(ceiling: ArithmeticException): ExportResult {
        persistence.failed(spanWork, ceiling)
        logger.debug { "otel.spans: add refused at the set's clock ceiling; nothing evicted, stamped or written: $ceiling" }
        return ExportResult.Failure(ceiling)
    }

    /**
     * Evict everything beyond [maxSpans] in one batch: the victims are picked once, by the order
     * repeated [victimOf] picks would take them, and removed with one [ORSet.removeAll], since a
     * removal per victim would pay a causal join each (#2245). Must be called with [lock] held.
     */
    private fun evictExcess() {
        val current = spans.elements
        val excess = current.size - maxSpans
        if (excess <= 0) return
        // Stable sorts, so ties go in iteration order, as minBy/maxBy break them in victimOf. A
        // removal mints no dot, so unlike candidateFor's add this cannot meet the ceiling (#3441).
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

/**
 * [WarpSpanExporter]'s add refused at the set's clock ceiling, before anything moved: [ceiling] is
 * the `ArithmeticException` [ORSet.add] threw (#3441). Private, so a catch of it can only ever see
 * that refusal, and never a store's or an encoder's exception of the same type.
 */
private class CeilingRefusal(val ceiling: ArithmeticException) : Exception(ceiling.message, ceiling)

/**
 * What one turn met at a [WarpCausalClock] whose read is owed: [refusal], the cause of a refused or
 * skipped read, `null` when the read landed or was not needed; and [skipped], the backoff window the
 * turn skipped inside, still to be spent (#3436).
 */
private class ClockReadTurn(val refusal: Throwable?, val skipped: RefusedReadBackoff?)

/**
 * The span one export saves, and whether it wanted a dot from an exhausted clock and so saves it
 * unstamped: the one case of a landed clock write whose clock work still failed (#3426).
 */
private class Stamping(val span: SpanRecord, val exhausted: Boolean)

/**
 * One export's turn, built under the write section and not yet visible: [set] is [base], the set the
 * turn read, joined with the dots failed turns kept, with [victim] evicted and [stamping]'s span
 * added. [WarpSpanExporter] writes [set], and publishes it only once the writes have returned
 * (#3517). [minted] is a set holding no span whose context witnesses the one dot the add minted,
 * which a failed turn keeps so the next one mints past it.
 */
private class Candidate(
    val base: ORSet<SpanRecord>,
    val set: ORSet<SpanRecord>,
    val victim: SpanRecord?,
    val stamping: Stamping,
    val minted: ORSet<SpanRecord>,
)

/** Whether a [WarpSpanExporter] has read its stored spans. */
private sealed interface SpanReadState {
    /** No read has landed or been refused yet: an exporter starts here. */
    data object Unread : SpanReadState

    /**
     * A read was refused before any landed, so a write would replace spans never seen. A write turn
     * skips the read while [backoff] has turns left, and each one spent is a [skipped] window
     * (#3436). The read that lands ends the window for good, since nothing leaves [Recovered].
     */
    class Unrecovered(val backoff: RefusedReadBackoff) : SpanReadState {
        fun skipped(): Unrecovered = Unrecovered(backoff.skipped())
    }

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

    data class Found(val stored: StoredSpans) : Landed
}

/**
 * What [WarpSpanExporter] writes under `otel.spans`: the [spans] and this replica's [ownHighWater],
 * the highest `seq` of its dots known used when the bytes were encoded (#3435). The set alone cannot
 * say so: a span evicted or cleared takes its dot with it.
 *
 * The bytes before #3435 were the bare set. Nobody stored that format, so it is not migrated: it
 * fails to decode here and reads as a corrupt entry.
 */
@Serializable
internal class StoredSpans(
    @SerialName("spans") val spans: ORSet<SpanRecord>,
    @SerialName("ownHighWater") val ownHighWater: Long,
) {
    init {
        require(ownHighWater >= 0L) { "ownHighWater must not be negative: $ownHighWater" }
    }
}
