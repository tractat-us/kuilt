@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.otel

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.crdt.ORSet
import us.tractat.kuilt.crdt.ReplicaId

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
) {
    // The lock guards 'spans'. No suspend calls are made inside the locked section —
    // Cbor encode/decode and the CRDT mutations are pure (non-suspending). The store
    // write is performed outside the lock on the encoded snapshot.
    //
    // An explicit reentrant lock is the repo policy for scope-owning types: correctness
    // must hold under a real multi-threaded dispatcher, not just the test dispatcher.
    // limitedParallelism(1) confinement is BANNED — see CLAUDE.md thread-safety section.
    private val lock = reentrantLock()
    private var spans: ORSet<SpanRecord> = ORSet.empty()

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
        val bytes = store.read(STORE_KEY) ?: return
        val recovered = runCatchingCancellable<ORSet<SpanRecord>> {
            cbor.decodeFromByteArray(spanSerializer, bytes)
        }.getOrNull() ?: run {
            logger.warn { "otel.spans: corrupt store entry, starting fresh" }
            return
        }
        lock.withLock { spans = recovered }
    }

    /**
     * Export one span: insert it into the CRDT and durably flush to [store].
     *
     * Returns [ExportResult.Success] after the durable write. Returns
     * [ExportResult.Failure] only if the [store] itself throws; the CRDT
     * mutation is never committed without a successful store write.
     *
     * When the buffer is full, the eviction policy fires first, logging the
     * dropped span, then the new span is inserted.
     */
    public suspend fun export(span: SpanRecord): ExportResult {
        val encoded = lock.withLock {
            maybeEvict()
            spans = spans.add(replica, span)
            cbor.encodeToByteArray(spanSerializer, spans)
        }
        return runCatchingCancellable { store.write(STORE_KEY, encoded) }
            .fold(
                onSuccess = { ExportResult.Success },
                onFailure = { cause ->
                    logger.error(cause) { "WarpSpanExporter: durable write failed for span ${span.spanId}" }
                    ExportResult.Failure(cause)
                },
            )
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
     * Idempotent: merging the same set twice produces the same result.
     */
    public suspend fun merge(remote: ORSet<SpanRecord>): ExportResult {
        val encoded = lock.withLock {
            spans = spans.piece(remote)
            cbor.encodeToByteArray(spanSerializer, spans)
        }
        return runCatchingCancellable { store.write(STORE_KEY, encoded) }
            .fold(
                onSuccess = { ExportResult.Success },
                onFailure = { cause ->
                    logger.error(cause) { "WarpSpanExporter: durable write failed during merge" }
                    ExportResult.Failure(cause)
                },
            )
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
        spans = spans.remove(victim)
    }
}

/** The result of a [WarpSpanExporter.export] or [WarpSpanExporter.merge] call. */
public sealed interface ExportResult {
    /** The span was durably written to the local store. */
    public data object Success : ExportResult

    /** The durable write failed; the span is not persisted. */
    public data class Failure(public val cause: Throwable) : ExportResult
}
