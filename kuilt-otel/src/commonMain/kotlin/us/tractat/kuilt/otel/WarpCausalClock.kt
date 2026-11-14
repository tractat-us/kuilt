@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.otel

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.StoreKey

private val logger = KotlinLogging.logger("us.tractat.kuilt.otel.WarpCausalClock")

/**
 * Mints causal [Dot]s for spans and tracks the current happens-before frontier.
 *
 * Call [tick] once per span, in the order the spans are created: it allocates the
 * span's own [Dot] and records the frontier observed so far as that span's
 * predecessors. The frontier then collapses to the new dot, so the *next* span's
 * predecessors point back here — building the happens-before chain a
 * [SpanLink][inferCausalLinks] is later derived from.
 *
 * The clock is **span-scoped**: it advances on span events only. A predecessor dot
 * can still fail to resolve: a rolled-back export or an evicted span leaves a dot no
 * span in the set holds, and [inferCausalLinks] skips it. Because [tick] collapses the
 * frontier, that also drops the path through the missing span to its own predecessors;
 * the lost edge is tracked by #3028.
 * Cross-signal causality (a log or metric happening-before a span) is future work.
 *
 * ## Recovery is mandatory
 *
 * A restart that reset `seq` to 0 would re-mint dots already used by earlier spans
 * and silently corrupt causality. Always [recover] from a [DurableStore] at startup,
 * and [persist] after the spans of a batch are durably exported. [tick] itself stays
 * pure (non-suspending) so the lock stays tight; persistence is the caller's
 * explicit step.
 *
 * ## A refused read fails closed
 *
 * A [recover] whose read the store **refuses** (an exception, or a cancellation the store
 * minted from its own `withTimeout`) leaves the clock **unrecovered**: the durable high-water
 * mark exists but is unknown. In that state [tick] and [persist] throw [IllegalStateException]
 * rather than mint a dot that may already be used or write a `seq` below the durable one (#3390).
 * [observe] and [frontier] still work. The next [recover] whose read succeeds re-arms the clock
 * and keeps what it observed meanwhile. A [WarpSpanExporter] retries that read on each export
 * and exports unstamped spans until it lands: causal edges are lost, a dot is never re-minted.
 *
 * A **corrupt** entry is different: the bytes were read and cannot be decoded, so no later read
 * can do better. The clock starts fresh at `seq=0` and the next [persist] replaces the entry, so
 * bad data never leaves it dead.
 *
 * ## Thread-safety
 *
 * `seq` and the frontier are one sealed state guarded by an explicit reentrant lock, so the
 * clock is correct under a real multi-threaded dispatcher — not merely under a test dispatcher. `limitedParallelism(1)` confinement is BANNED (see CLAUDE.md).
 *
 * @param replica the stable [ReplicaId] for this device/process; namespaces every dot.
 */
public class WarpCausalClock(private val replica: ReplicaId) {

    private val lock = reentrantLock()
    private var state: ClockState = ClockState.Armed(seq = 0L, frontier = emptySet())

    internal companion object {
        /** The durable-store key this clock persists under. Exposed `internal` so a
         *  crash-window test can target it precisely rather than duplicating the literal. */
        internal val STORE_KEY = StoreKey("otel.causal.clock")
        private val cbor = Cbor { alwaysUseByteString = true }
        private val stateSerializer = PersistedClock.serializer()
    }

    /**
     * Mint the next span's [CausalStamp]: a fresh [Dot] whose predecessors are the
     * current frontier (empty on the very first tick). Advances the frontier to the
     * new dot.
     *
     * @throws IllegalStateException if the last [recover] could not read the store: the clock
     *   does not know which dots are already used, so it mints none. [recover] again to re-arm it.
     */
    public fun tick(): CausalStamp =
        checkNotNull(tickIfArmed()) { "otel.causal.clock: the recovery read was refused; recover before minting" }

    /** [tick], or `null` while the clock is unrecovered: the span exporter's unstamped path. */
    internal fun tickIfArmed(): CausalStamp? = lock.withLock {
        when (val current = state) {
            is ClockState.Unrecovered -> null
            is ClockState.Armed -> {
                val newDot = Dot(replica, current.seq + 1)
                state = ClockState.Armed(newDot.seq, setOf(newDot))
                CausalStamp(dot = newDot, predecessors = current.frontier)
            }
        }
    }

    /** Whether the last [recover] was refused, so the clock mints and persists nothing. */
    internal val isUnrecovered: Boolean get() = lock.withLock { state is ClockState.Unrecovered }

    /**
     * Fold a [remote] frontier (received from another replica via anti-entropy) into
     * the local frontier, so the next [tick] records those remote dots as
     * predecessors — the cross-replica causal path.
     */
    public fun observe(remote: Set<Dot>): Unit = lock.withLock {
        state = state.withFrontier(state.frontier + remote)
    }

    /** A snapshot of the current causal frontier. */
    public fun frontier(): Set<Dot> = lock.withLock { state.frontier }

    /**
     * Forget the causal frontier while leaving `seq` untouched — the clock's half of a
     * span clear (#2208).
     *
     * The asymmetry is the point. `seq` must never regress: a reset one re-mints dots already
     * used by earlier spans, which is the corruption this class's "Recovery is mandatory"
     * section exists to prevent, and a clear is not a restart. The **frontier** must go,
     * because it names dots of spans the clear just dropped — so the next [tick] would stamp a
     * post-clear span with predecessors that can never resolve. [inferCausalLinks] tolerates
     * that (an unresolved dot is skipped and logged at debug, never thrown), so this is not a
     * correctness repair: it is that a link pointing at a deliberately forgotten span is noise,
     * not causality.
     *
     * The caller persists — [persist] is this class's explicit step, not an implicit one.
     */
    internal fun clearFrontier(): Unit = lock.withLock { state = state.withFrontier(emptySet()) }

    /**
     * Reload `(seq, frontier)` from [store]. Call once at startup before any [tick];
     * a missing or corrupt entry leaves the clock fresh (seq 0, empty frontier). A read never
     * moves `seq` backwards: an entry older than what this clock already minted is ignored.
     *
     * A refused read, including a cancellation the store minted, leaves the clock
     * **unrecovered** instead: [tick] and [persist] throw until a later call here reads the
     * store, so the durable high-water mark is never overwritten and no dot is minted twice
     * (#3390). On this public overload the refusal is reported by an ERROR log line. A
     * cancellation of the caller's own job still propagates.
     */
    public suspend fun recover(store: DurableStore): Unit = recover(store) {}

    /** Lets the owning telemetry facade include clock recovery in span persistence health. */
    internal suspend fun recover(store: DurableStore, onFailure: (Throwable) -> Unit) {
        when (val read = read(store, onFailure)) {
            is ClockRead.Refused -> {
                onFailure(read.cause)
                logger.error(read.cause) { "otel.causal.clock: store read failed; minting nothing until a read lands" }
                lock.withLock { state = ClockState.Unrecovered(state.seq, state.frontier) }
            }
            is ClockRead.Reached -> arm(read)
        }
    }

    /**
     * Retry the read a refused [recover] could not make, if the clock is still unrecovered: the span
     * exporter's per-turn retry. A refusal here logs at debug, not error, because the outage was
     * already reported once, and a store that stays locked would otherwise log a stack trace per export.
     */
    internal suspend fun retryRecovery(store: DurableStore, onFailure: (Throwable) -> Unit) {
        if (!isUnrecovered) return
        when (val read = read(store, onFailure)) {
            is ClockRead.Refused -> {
                onFailure(read.cause)
                logger.debug { "otel.causal.clock: store read still refused: ${read.cause}" }
            }
            is ClockRead.Reached -> {
                arm(read)
                logger.info { "otel.causal.clock: store read landed; minting again" }
            }
        }
    }

    /**
     * Read the entry. A corrupt one is reported through [onFailure] here and reads as no entry, since
     * no later read could decode it either; only a refusal is left for the caller to decide on.
     */
    private suspend fun read(store: DurableStore, onFailure: (Throwable) -> Unit): ClockRead {
        val bytes = try {
            store.read(STORE_KEY)
        } catch (cause: Throwable) {
            // A cancellation the store minted is a refused read; only this coroutine's own
            // cancellation propagates (#3388).
            currentCoroutineContext().ensureActive()
            return ClockRead.Refused(cause)
        } ?: return ClockRead.Reached(entry = null)
        val entry = runCatchingCancellable { cbor.decodeFromByteArray(stateSerializer, bytes) }
            .getOrElse { cause ->
                onFailure(cause)
                logger.warn(cause) { "otel.causal.clock: corrupt store entry, starting fresh" }
                null
            }
        return ClockRead.Reached(entry)
    }

    private fun arm(read: ClockRead.Reached): Unit = lock.withLock { state = state.joinedWith(read.entry) }

    /**
     * Durably persist the current `(seq, frontier)` to [store]. Call after a batch of
     * [tick]s is durably exported so a restart never re-mints a used dot.
     *
     * @throws IllegalStateException if the last [recover] could not read the store: writing now
     *   would replace a durable high-water mark this clock never saw. [recover] again first.
     */
    public suspend fun persist(store: DurableStore) {
        check(persistIfArmed(store)) { "otel.causal.clock: the recovery read was refused; recover before persisting" }
    }

    /**
     * [persist], or `false` with no write while the clock is unrecovered: the span exporter's path,
     * which still writes its spans. An unrecovered clock stamps none, so none needs this write.
     */
    internal suspend fun persistIfArmed(store: DurableStore): Boolean {
        val snapshot = lock.withLock {
            when (val current = state) {
                is ClockState.Unrecovered -> null
                is ClockState.Armed -> PersistedClock(current.seq, current.frontier)
            }
        } ?: return false
        store.write(STORE_KEY, cbor.encodeToByteArray(stateSerializer, snapshot))
        return true
    }
}

/**
 * What the clock knows about the dots already used. A sealed type rather than a flag beside `seq`,
 * so every path that mints or persists has to say what it does when the high-water mark is unknown.
 */
private sealed interface ClockState {
    /** The highest `seq` this clock has minted or read. */
    val seq: Long
    val frontier: Set<Dot>

    fun withFrontier(frontier: Set<Dot>): ClockState = when (this) {
        is Armed -> copy(frontier = frontier)
        is Unrecovered -> copy(frontier = frontier)
    }

    /**
     * Fold in a read that reached the store: [durable] is its entry, `null` when there was none or it
     * would not decode, which arms a fresh clock at the [seq] held here. Never moves [seq]
     * backwards, so a stale or racing read cannot re-arm a clock below a dot it already minted. When the entry is newer, its frontier joins the one held here,
     * which keeps what [WarpCausalClock.observe] saw while the clock was unrecovered.
     */
    fun joinedWith(durable: PersistedClock?): Armed =
        if (durable == null || durable.seq <= seq) Armed(seq, frontier)
        else Armed(durable.seq, durable.frontier + frontier)

    /** Every dot up to [seq] is used and nothing above it is: the next one minted is `seq + 1`. */
    data class Armed(override val seq: Long, override val frontier: Set<Dot>) : ClockState

    /**
     * A recovery read was refused, so [seq] is only a lower bound on the durable high-water mark and
     * nothing may be minted or persisted. [frontier] collects what [WarpCausalClock.observe] saw.
     */
    data class Unrecovered(override val seq: Long, override val frontier: Set<Dot>) : ClockState
}

/** One recovery read: refused, so worth retrying, or it reached what the store holds. */
private sealed interface ClockRead {
    data class Refused(val cause: Throwable) : ClockRead

    /** [entry] is `null` when there was none, or when it would not decode. */
    data class Reached(val entry: PersistedClock?) : ClockRead
}

/** Serialized clock state: the high-water [seq] and the causal [frontier]. */
@Serializable
private data class PersistedClock(val seq: Long, val frontier: Set<Dot>)
