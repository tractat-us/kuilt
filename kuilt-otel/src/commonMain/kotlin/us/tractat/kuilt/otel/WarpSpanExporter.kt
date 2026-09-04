@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.otel

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.flow.MutableStateFlow
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
 * Spans are stored in an [ORSet]`<`[SpanRecord]`>` keyed by [SpanRecord.spanId]:
 * adding the same span twice is a set-union and therefore idempotent — a retry
 * can never double-count. Reconnecting peers reconcile by sharing their
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
 * When the in-memory CRDT exceeds [maxSpans], the oldest span (by
 * [SpanRecord.startEpochNanos]) is evicted according to the [bufferPolicy] before
 * the new span is inserted. **Every eviction is logged** with enough detail to
 * correlate against a backend's orphan-span index.
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
     * clock's key *and* [STORE_KEY] inside one `runCatchingCancellable`, so a store refusing either
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
     * If no persisted state exists, the exporter starts with an empty set.
     */
    public suspend fun recover() {
        val bytes = runCatchingCancellable { store.read(STORE_KEY) }.getOrElse { cause ->
            logger.error(cause) { "otel.spans: store read failed, starting fresh" }
            return
        } ?: return
        val recovered = runCatchingCancellable<ORSet<SpanRecord>> {
            cbor.decodeFromByteArray(spanSerializer, bytes)
        }.getOrElse { cause ->
            logger.warn(cause) { "otel.spans: corrupt store entry, starting fresh" }
            return
        }
        lock.withLock { spans = recovered }
    }

    /**
     * Export one span: insert it into the CRDT and durably flush to [store].
     *
     * If a [WarpCausalClock] was supplied, an **unstamped** span is auto-stamped with
     * causal context before insert (#846) — an explicit [SpanRecord.causalStamp]
     * always wins.
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
     * If either durable write fails, a freshly-minted stamp is rolled back out of the
     * in-memory set so a retrying caller re-adds exactly **one** copy rather than
     * accumulating a second stamped copy of the same span. The minted dot's `seq` is
     * left spent — a harmless gap, since the durable clock already covers it.
     *
     * Returns [ExportResult.Success] after the durable write. Returns
     * [ExportResult.Failure] only if the [store] itself throws; the CRDT
     * mutation is never committed without a successful store write.
     *
     * When the buffer is full, the eviction policy fires first, logging the
     * dropped span, then the new span is inserted.
     */
    public suspend fun export(span: SpanRecord): ExportResult {
        // Auto-stamp causal context (#846): a configured clock fills an unstamped
        // span so causal links form with no caller change; an explicit stamp wins.
        val stamped = when {
            causalClock == null -> span
            span.causalStamp != null -> span
            else -> span.copy(causalStamp = causalClock.tick())
        }
        // A fresh dot was minted only when copy() produced a new instance; an explicit
        // or absent stamp returns `span` itself. Only a minted stamp needs rollback —
        // a re-added unstamped/explicit span is idempotent by ORSet value.
        val minted = stamped !== span
        lock.withLock {
            maybeEvict()
            spans = spans.piece { it.add(replica, stamped) }
        }
        // Serialize the durable-write section across concurrent export()/merge() so the
        // clock persist and the span write are one ordered unit (#1053).
        return ioMutex.withLock {
            runCatchingCancellable {
                // Re-encode the *latest* spans inside the section so no concurrent add is
                // dropped by a stale snapshot; encoding before the persist keeps the
                // persisted seq ≥ every dot in this snapshot (seq is monotonic).
                val encoded = lock.withLock { cbor.encodeToByteArray(spanSerializer, spans) }
                // Persist the clock BEFORE the span so the durable clock seq is always ≥
                // every durable span's dot — a crash between the two writes can only lose
                // the span, never re-mint its dot on recover (#1053).
                causalClock?.persist(store)
                store.write(STORE_KEY, encoded)
            }.fold(
                onSuccess = {
                    durableWriteSucceeded()
                    ExportResult.Success
                },
                onFailure = { cause ->
                    if (minted) {
                        // Undo the in-memory add so a retry produces exactly one stamped
                        // copy. remove() targets this span's unique dot value, so it never
                        // clobbers a concurrent add of a different span.
                        lock.withLock { spans = spans.piece { current -> current.remove(stamped) } }
                    }
                    // Rollback first, and unconditionally: it is owed on every failed write,
                    // whereas the line below is owed only on the one that opens the outage.
                    durableWriteFailed(cause) {
                        logger.error(it) { "WarpSpanExporter: durable write failed for span ${stamped.spanId}" }
                    }
                },
            )
        }
    }

    /**
     * Read a snapshot of the current in-memory [ORSet] for gossip / anti-entropy.
     *
     * The returned set reflects all spans exported since the last [recover] or
     * process start, minus any that were evicted due to the buffer cap.
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
     */
    public suspend fun merge(remote: ORSet<SpanRecord>): ExportResult {
        // Fold the remote replica's causal frontier so the next local tick records
        // those dots as predecessors — the cross-replica happens-before path (#846).
        causalClock?.observe(remote.elements.mapNotNull { it.causalStamp?.dot }.toSet())
        lock.withLock { spans = spans.piece(remote) }
        // Share the durable-write section with export() so a concurrent export()+merge()
        // cannot lost-update the STORE_KEY snapshot (#1053).
        return ioMutex.withLock {
            runCatchingCancellable {
                val encoded = lock.withLock { cbor.encodeToByteArray(spanSerializer, spans) }
                store.write(STORE_KEY, encoded)
            }.fold(
                onSuccess = {
                    durableWriteSucceeded()
                    ExportResult.Success
                },
                onFailure = { cause ->
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
     * **On failure, [snapshot] already reads empty while the store still holds every span**, and
     * a configured clock's [WarpCausalClock.frontier] likewise reads empty while the persisted
     * one still names the pre-clear dots. Both mutations precede the writes and — unlike
     * [export], which rolls a freshly-minted stamp back out — neither is undone. A caller that
     * uses the span count as a baseline must treat any non-[ExportResult.Success] as *count
     * unknown* rather than as zero. On success the count reads zero synchronously, because the
     * drop precedes the write.
     */
    public suspend fun clear(): ExportResult = ioMutex.withLock {
        runCatchingCancellable {
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
            causalClock?.persist(store)
            store.write(STORE_KEY, encoded)
        }.fold(
            onSuccess = {
                durableWriteSucceeded()
                ExportResult.Success
            },
            onFailure = { cause ->
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

    /** Must be called with [lock] held. */
    private fun maybeEvict() {
        val current = spans.elements
        if (current.size < maxSpans) return
        val victim = when (bufferPolicy) {
            BufferPolicy.DROP_OLDEST -> current.minByOrNull { it.startEpochNanos }
            BufferPolicy.DROP_NEWEST -> current.maxByOrNull { it.startEpochNanos }
        } ?: return
        logger.warn {
            "WarpSpanExporter: buffer cap ($maxSpans) reached, evicting span " +
                "traceId=${victim.traceId} spanId=${victim.spanId} name=${victim.name} " +
                "policy=$bufferPolicy"
        }
        spans = spans.piece { it.remove(victim) }
    }
}

/** The result of a [WarpSpanExporter.export] or [WarpSpanExporter.merge] call. */
public sealed interface ExportResult {
    /** The span was durably written to the local store. */
    public data object Success : ExportResult

    /** The durable write failed; the span is not persisted. */
    public data class Failure(public val cause: Throwable) : ExportResult
}
