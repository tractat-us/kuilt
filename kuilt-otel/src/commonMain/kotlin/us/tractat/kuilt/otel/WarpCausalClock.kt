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
import us.tractat.kuilt.math.plusOrNull
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
 * ## Recovery is mandatory, and enforced
 *
 * A restart that reset `seq` to 0 would re-mint dots already used by earlier spans
 * and silently corrupt causality. So a clock **mints and persists only after a [recover] read
 * has landed**. Until then [tick] answers [ClockTick.Unrecovered] and [persist] answers
 * [ClockPersist.Unrecovered], minting and writing nothing. [persist] after the spans of a batch
 * are durably exported. [tick] itself stays pure (non-suspending) so the lock stays tight;
 * persistence is the caller's explicit step.
 *
 * ## A refused read fails closed
 *
 * A [recover] whose read the store **refuses** (an exception, or a cancellation the store
 * minted from its own `withTimeout`) returns [ClockRecovery.Refused]. On a clock whose read had
 * not landed, the durable high-water mark exists but is unknown, so the clock keeps refusing to
 * mint a dot that may already be used or write a `seq` below the durable one (#3390). [observe]
 * and [frontier] still work. The next [recover] whose read lands arms the clock and keeps what it
 * observed meanwhile. A [WarpSpanExporter] retries that read on each export and clear and saves
 * unstamped spans until it lands: causal edges are lost, a dot is never re-minted.
 *
 * A clock whose read has landed is never demoted by a refusal. This process is the only writer of
 * the clock's entry, and it has minted only above the `seq` it read, so its own `seq` is at least
 * the durable one. Demoting it would strand a dot already handed to a span whose turn has not
 * persisted yet.
 *
 * An **undecodable** entry is different: the bytes were read and cannot be decoded, so no later
 * read can do better. The clock starts fresh, at `seq=0` or at the floor below, and the next
 * [persist] replaces the entry, so bad data never leaves it dead.
 *
 * Fresh does not mean unused, because durable spans still carry this replica's old dots (#3407).
 * So a [WarpSpanExporter] whose read of the stored spans lands raises the clock's floor to the
 * highest own-replica dot that read found, a span's stamp or a predecessor a span names, before the
 * turn mints anything, and a clock that has not read yet keeps the floor until its read lands. A
 * merge raises it the same way from the remote set. Still uncovered: a dot no stored span carries
 * or names (its span evicted past `maxSpans` or cleared before the restart) can be re-minted after
 * a corrupt entry until a peer hands it back, and a stamp minted before that merge keeps the
 * collision (#3435). The floor takes every stored or merged dot's `seq` as given, so one claiming
 * `Long.MAX_VALUE` leaves the clock [ClockTick.Exhausted] (#3432).
 *
 * ## Thread-safety
 *
 * `seq` and the frontier are one sealed state guarded by an explicit reentrant lock, so the
 * clock is correct under a real multi-threaded dispatcher — not merely under a test dispatcher.
 * `limitedParallelism(1)` confinement is BANNED (see CLAUDE.md).
 *
 * @param replica the stable [ReplicaId] for this device/process; namespaces every dot.
 */
public class WarpCausalClock(private val replica: ReplicaId) {

    private val lock = reentrantLock()
    private var state: ClockState = ClockState.initial

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
     * Answers [ClockTick.Unrecovered], minting nothing, until a [recover] read has landed: the
     * clock does not know which dots are already used. Answers [ClockTick.Exhausted], minting
     * nothing and leaving the clock as it was, once `seq` is `Long.MAX_VALUE`: there is no dot left,
     * and wrapping would re-mint one.
     */
    public fun tick(): ClockTick = lock.withLock {
        when (val current = state) {
            is ClockState.Unread, is ClockState.Unrecovered -> ClockTick.Unrecovered
            is ClockState.Armed -> {
                val next = current.seq.plusOrNull(1L) ?: return@withLock ClockTick.Exhausted
                val newDot = Dot(replica, next)
                state = ClockState.Armed(newDot.seq, setOf(newDot))
                ClockTick.Minted(CausalStamp(dot = newDot, predecessors = current.frontier))
            }
        }
    }

    /** Whether a [recover] read has landed, so the clock mints and persists. */
    internal val isArmed: Boolean get() = lock.withLock { state is ClockState.Armed }

    /**
     * Fold a [remote] frontier (received from another replica via anti-entropy) into
     * the local frontier, so the next [tick] records those remote dots as
     * predecessors — the cross-replica causal path.
     */
    public fun observe(remote: Set<Dot>): Unit = lock.withLock {
        state = state.observed(remote)
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
     * not causality. On a clock that has not read yet, the frontier the first read brings back
     * is dropped too.
     *
     * The caller persists — [persist] is this class's explicit step, not an implicit one.
     */
    internal fun clearFrontier(): Unit = lock.withLock { state = state.cleared() }

    /**
     * Record that every dot up to [usedSeq] is already used, so the next [tick] mints above it
     * (#3407). It only ever raises `seq`: a clock already past [usedSeq] is left alone. A clock whose
     * read has not landed keeps the floor and mints nothing yet; the read that lands, a corrupt or
     * missing entry included, arms it at no less than the floor.
     */
    internal fun raiseFloor(usedSeq: Long): Unit = lock.withLock { state = state.floored(usedSeq) }

    /**
     * Reload `(seq, frontier)` from [store], arming the clock. Call once at startup: until a read
     * lands, [tick] and [persist] refuse. A missing entry answers [ClockRecovery.Recovered] and an
     * undecodable one [ClockRecovery.Undecodable], and both arm the clock at `seq=0`, or at the floor
     * a landed span read already raised ([WarpSpanExporter], #3407). A read
     * never moves `seq` backwards: an entry older than what this clock already minted is ignored.
     *
     * A refused read, including a cancellation the store minted, answers
     * [ClockRecovery.Refused] and is logged at ERROR. A clock whose read had not landed stays
     * unable to mint or persist until a later call here reads the store, so the durable
     * high-water mark is never overwritten and no dot is minted twice (#3390). A clock whose read
     * had landed keeps minting. A cancellation of the caller's own job still propagates.
     */
    public suspend fun recover(store: DurableStore): ClockRecovery = when (val read = read(store)) {
        is ClockRead.Refused -> {
            logger.error(read.cause) { "otel.causal.clock: store read failed; minting nothing until a read lands" }
            lock.withLock { state = state.refused() }
            ClockRecovery.Refused(read.cause)
        }
        is ClockRead.Landed -> arm(read)
    }

    /**
     * Make the read a clock still needs, if its read has not landed: the span exporter's per-turn
     * retry, which also arms a clock nobody recovered. An armed clock answers
     * [ClockRecovery.Recovered] with no read. A repeated refusal logs at debug, not error, because the
     * outage was already reported once, and a store that stays locked would otherwise log a stack
     * trace per export.
     */
    internal suspend fun retryRecovery(store: DurableStore): ClockRecovery {
        if (isArmed) return ClockRecovery.Recovered
        return when (val read = read(store)) {
            is ClockRead.Refused -> {
                val first = lock.withLock { (state is ClockState.Unread).also { state = state.refused() } }
                if (first) {
                    logger.error(read.cause) { "otel.causal.clock: store read failed; minting nothing until a read lands" }
                } else {
                    logger.debug { "otel.causal.clock: store read still refused: ${read.cause}" }
                }
                ClockRecovery.Refused(read.cause)
            }
            is ClockRead.Landed -> arm(read).also {
                logger.info { "otel.causal.clock: store read landed; minting" }
            }
        }
    }

    private suspend fun read(store: DurableStore): ClockRead {
        val bytes = try {
            store.read(STORE_KEY)
        } catch (cause: Throwable) {
            // A cancellation the store minted is a refused read; only this coroutine's own
            // cancellation propagates (#3388).
            currentCoroutineContext().ensureActive()
            return ClockRead.Refused(cause)
        } ?: return ClockRead.Absent
        return runCatchingCancellable { cbor.decodeFromByteArray(stateSerializer, bytes) }
            .fold(onSuccess = { ClockRead.Found(it) }, onFailure = { ClockRead.Undecodable(it) })
    }

    private fun arm(read: ClockRead.Landed): ClockRecovery {
        lock.withLock {
            state = when (read) {
                is ClockRead.Found -> state.joinedWith(read.entry)
                ClockRead.Absent, is ClockRead.Undecodable -> state.withoutEntry()
            }
        }
        return when (read) {
            is ClockRead.Undecodable -> {
                logger.warn(read.cause) { "otel.causal.clock: corrupt store entry, starting fresh" }
                ClockRecovery.Undecodable(read.cause)
            }
            ClockRead.Absent, is ClockRead.Found -> ClockRecovery.Recovered
        }
    }

    /**
     * Durably persist the current `(seq, frontier)` to [store]. Call after a batch of
     * [tick]s is durably exported so a restart never re-mints a used dot.
     *
     * Answers [ClockPersist.Unrecovered] with no write until a [recover] read has landed: writing
     * then would replace a durable high-water mark this clock never saw. A store that refuses the
     * write still throws, as [DurableStore.write] does.
     */
    public suspend fun persist(store: DurableStore): ClockPersist {
        val snapshot = lock.withLock {
            when (val current = state) {
                is ClockState.Unread, is ClockState.Unrecovered -> null
                is ClockState.Armed -> PersistedClock(current.seq, current.frontier)
            }
        } ?: return ClockPersist.Unrecovered
        store.write(STORE_KEY, cbor.encodeToByteArray(stateSerializer, snapshot))
        return ClockPersist.Persisted
    }
}

/** What [WarpCausalClock.recover] found. */
public sealed interface ClockRecovery {
    /**
     * The read landed: the clock resumed from the store's entry, or found none and starts at `seq=0`,
     * or at the floor a landed span read already raised.
     */
    public data object Recovered : ClockRecovery

    /**
     * The entry would not decode. The clock starts at `seq=0`, or at the floor a landed span read
     * already raised, and its next persist replaces the entry.
     */
    public data class Undecodable(public val cause: Throwable) : ClockRecovery

    /**
     * The store refused the read. A clock whose read had not landed still mints and persists
     * nothing; one whose read had landed keeps minting, since this process wrote every `seq` the
     * store can hold.
     */
    public data class Refused(public val cause: Throwable) : ClockRecovery
}

/** What [WarpCausalClock.tick] did. */
public sealed interface ClockTick {
    /** A fresh dot, carried in [stamp]. */
    public data class Minted(public val stamp: CausalStamp) : ClockTick

    /** No recovery read has landed, because it was refused or never made, so it minted nothing. */
    public data object Unrecovered : ClockTick

    /**
     * Every dot is used: `seq` is `Long.MAX_VALUE`, so it minted nothing. An honest clock never gets
     * here. A floor raised from a stored span that claims this replica's last dot does (#3407), and
     * the clock then stays exhausted, so a [WarpSpanExporter] saves every later span unstamped.
     */
    public data object Exhausted : ClockTick
}

/** What [WarpCausalClock.persist] did. */
public sealed interface ClockPersist {
    /** The clock's `(seq, frontier)` was written. */
    public data object Persisted : ClockPersist

    /** No recovery read has landed, because it was refused or never made, so it wrote nothing. */
    public data object Unrecovered : ClockPersist
}

/**
 * What the clock knows about the dots already used. A sealed type rather than a flag beside `seq`,
 * so every path that mints, persists or meets a refused read has to say what it does in each case.
 *
 * Only a landed read reaches [Armed], and only [Armed] mints or persists. So a clock that holds a
 * `seq` has read the durable one and minted only above it, and as the only writer of the clock's
 * key it is at least as high as the store. A refused read then teaches it nothing, and demoting it
 * would strand a dot already handed to a span whose turn is still waiting to persist. [Unread] and
 * [Unrecovered] behave alike; they differ only in whether a refusal has been reported yet.
 */
private sealed interface ClockState {
    val frontier: Set<Dot>

    fun observed(remote: Set<Dot>): ClockState = when (this) {
        is Unread -> copy(frontier = frontier + remote)
        is Unrecovered -> copy(frontier = frontier + remote)
        is Armed -> copy(frontier = frontier + remote)
    }

    /** The frontier is dropped, and so is the durable one a first read would bring back. */
    fun cleared(): ClockState = when (this) {
        is Unread -> copy(frontier = emptySet(), frontierCleared = true)
        is Unrecovered -> copy(frontier = emptySet(), frontierCleared = true)
        is Armed -> copy(frontier = emptySet())
    }

    /** A refused read moves [Unread] to [Unrecovered] and leaves an [Armed] clock alone. */
    fun refused(): ClockState = if (this is Unread) Unrecovered(frontier, frontierCleared, floor) else this

    /** Every dot up to [usedSeq] is used. Raises, never lowers: `max`, on every arm. */
    fun floored(usedSeq: Long): ClockState = when (this) {
        is Unread -> copy(floor = maxOf(floor, usedSeq))
        is Unrecovered -> copy(floor = maxOf(floor, usedSeq))
        is Armed -> copy(seq = maxOf(seq, usedSeq))
    }

    /**
     * Fold in the store's [entry]. An [Armed] clock never moves backwards, so a stale or racing read
     * cannot wind it below a dot it already minted. A clock that has not read yet takes the entry's
     * `seq`, and its frontier joins the one held here, unless a clear dropped the frontier since.
     */
    fun joinedWith(entry: PersistedClock): Armed = when (this) {
        is Armed -> if (entry.seq <= seq) this else Armed(entry.seq, entry.frontier + frontier)
        is Unread -> Armed(maxOf(entry.seq, floor), if (frontierCleared) frontier else entry.frontier + frontier)
        is Unrecovered -> Armed(maxOf(entry.seq, floor), if (frontierCleared) frontier else entry.frontier + frontier)
    }

    /** A read that found no usable entry: a clock that has not read yet starts at its floor, `seq=0` without one. */
    fun withoutEntry(): Armed = when (this) {
        is Armed -> this
        is Unread -> Armed(floor, frontier)
        is Unrecovered -> Armed(floor, frontier)
    }

    /**
     * No read has landed or been refused yet: the state a clock is constructed in. [floor] is the
     * highest dot a read of the stored spans found used, kept for the read that arms the clock.
     */
    data class Unread(override val frontier: Set<Dot>, val frontierCleared: Boolean, val floor: Long) : ClockState

    /**
     * A read was refused before any landed, so the durable high-water mark is unknown and nothing may
     * be minted or persisted. [frontier] collects what [WarpCausalClock.observe] saw meanwhile, and
     * [floor] what a read of the stored spans found used.
     */
    data class Unrecovered(override val frontier: Set<Dot>, val frontierCleared: Boolean, val floor: Long) : ClockState

    /** Every dot up to [seq] is used and nothing above it is: the next one minted is `seq + 1`. */
    data class Armed(val seq: Long, override val frontier: Set<Dot>) : ClockState

    companion object {
        val initial: ClockState = Unread(emptySet(), frontierCleared = false, floor = 0L)
    }
}

/** One recovery read: refused, so worth retrying, or [Landed] on what the store holds. */
private sealed interface ClockRead {
    data class Refused(val cause: Throwable) : ClockRead

    /** The read reached the store. Only a refusal is worth retrying. */
    sealed interface Landed : ClockRead

    data object Absent : Landed

    data class Undecodable(val cause: Throwable) : Landed

    data class Found(val entry: PersistedClock) : Landed
}

/** Serialized clock state: the high-water [seq] and the causal [frontier]. */
@Serializable
private data class PersistedClock(val seq: Long, val frontier: Set<Dot>)
