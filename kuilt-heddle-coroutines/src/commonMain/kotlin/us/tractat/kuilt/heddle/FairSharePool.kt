package us.tractat.kuilt.heddle

import kotlinx.atomicfu.AtomicInt
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Wait your turn for a slot. A build farm has four agents and two lanes, web and mobile; a program
 * handing them out wants to say "run this in web's lane when it is web's turn" and have that one
 * line wait. A `FairSharePool` holds a [LocalHeddle], which knows whose turn it is, and a count of
 * [slots]. [withSlot] suspends until fair share grants the caller's leaf a slot, runs the work, and
 * charges the lane the time the work took, however it ends. [acquire] is the same wait on its own:
 * the caller hands the slot back with [complete] or [cancel].
 *
 * The part that is easy to get wrong, and silent when it is, is a waiter that gives up at the moment
 * it is granted. The pool hands that slot back: a waiter cancelled while queued leaves its queue,
 * and one cancelled after its grant was made but before it took hands the lease back to the engine,
 * so the farm never runs one agent short.
 *
 * ## How it works
 *
 * One lock guards the engine, a first-in-first-out queue of waiters per leaf, the set of leases the
 * pool holds open, and the slot count. After every change, while fewer than [slots] leases are open
 * and some leaf has a waiter, the pool asks the engine for the next lease, with every leaf that has
 * a waiter as both the waiting and the eligible set, and grants it to the head waiter of the leaf the
 * engine picked. A waiter that asked for more than the engine's default reservation has the lease
 * extended to it; if the engine cannot extend it, the lease is granted at the default and the rest
 * is charged as an overrun at [complete]. Waiters are resumed only once the lock is released.
 *
 * A grant's [Lease.startedAt] is [now] at the moment the grant is made, which can be a little
 * before its waiter runs. [withSlot] starts its clock when the block starts, so that gap is never
 * charged.
 *
 * ## The engine's open leases are adopted
 *
 * The leases the engine already holds open when the pool is built, say after a [LocalHeddle.restore],
 * count against [slots] like any other, and [complete] and [cancel] settle them. [now] counts in whole
 * cost units from `clockStart`, so persist `now()` beside the snapshot and pass it back, plus the
 * downtime if you know it, as `clockStart` after a restart: an adopted lease's `startedAt` is then
 * on the same clock. With the default of `0`, an adopted lease reads as just started.
 *
 * So a pool can restart with every slot held. A caller that waited through [withSlot] or [acquire]
 * died with the old program, and nothing will ever settle the lease it held, so the pool grants
 * nothing until those leases are closed. Once the pool is built, find them with
 * `pool.withEngine { engine, _ -> engine.reconcileLeases(live, pool.now()) }`, naming as `live` only
 * the leases whose work survived, and settle each orphan with
 * `pool.complete(orphan.lease.id, orphan.defaultSettlement)` after the block returns. [now] takes no
 * lock, so it is safe to read inside the block. With a `clockStart` of `0`, [now] reads no later
 * than any adopted lease's `startedAt`, so every default settlement reads `0`.
 *
 * ## Refusals
 *
 * A leaf no lease can end at is refused, never queued for good: [acquire] answers
 * [SlotOutcome.Refused] with the engine's own [WaitExplanation.NoLiveLineage] or
 * [WaitExplanation.LifetimeExhausted]. When every slot is busy that is checked on arrival, once per
 * leaf that has no waiter yet, so a pool with no free slot, or with none at all, still refuses an
 * unleasable name at once and queues a healthy one. A leaf is always asked about on its own, so a
 * full lane next in line never gets another leaf's waiters refused.
 *
 * ## Failures
 *
 * A failure while handing out slots, the engine throwing or answering something its own contract
 * rules out, is delivered to the waiters: every queued waiter's [acquire] throws it, and the call
 * that set the hand-out going, a stranger's [complete] or a cancelled waiter's handler, returns as
 * usual. The next arrival starts a fresh hand-out. An engine refusing to take back the lease of a
 * waiter cancelled after its grant is delivered the same way: the slot is freed, and the engine,
 * which still holds the lease, reports it as an orphan through [LocalHeddle.reconcileLeases].
 *
 * A failure with no waiter queued has nobody to go to, and is dropped: a [withEngine] block that
 * leaves the engine throwing, or an engine refusing a hand-back, while nobody waits, leaves no trace
 * beyond the engine's own state. That is tracked by #3570.
 *
 * ## Threads
 *
 * Every member is safe to call from any thread. Resuming happens outside the lock, after the
 * outermost call returns from it. A waiter resumed through a dispatcher that runs it inline with no
 * event loop, such as a direct executor, and that calls [complete] at once, nests one resume per
 * such [complete] on the stack. Coroutines' own `Unconfined` dispatcher queues them on the thread's
 * event loop instead.
 *
 * The pool owns the engine from here on: reach it only through [withEngine], which runs under the
 * pool's lock and catches the pool up afterwards, and change the slot count with [resize].
 *
 * @param engine the scheduler deciding whose turn it is. The pool reconciles its capacity to [slots].
 * @param slots how many leases may be open at once, at least `0`.
 * @param timeSource the clock [now] reads and [withSlot] measures with. It should be monotonic: a
 *   reading that goes back before a mark is taken as no time at all, so a block whose clock stepped
 *   back is charged `0`, and [now] never reads below [clockStart].
 * @param costUnit how long one unit of [LocalHeddle] cost is: positive and finite.
 * @param clockStart what [now] reads at construction, in cost units; non-negative.
 * @throws IllegalArgumentException when an argument is out of range, or the engine refuses to
 *   reconcile its capacity to [slots]; the engine is unchanged.
 * @sample us.tractat.kuilt.heddle.sampleWithSlot
 * @sample us.tractat.kuilt.heddle.sampleAcquireSlot
 * @sample us.tractat.kuilt.heddle.sampleRestartAPool
 */
public class FairSharePool(
    engine: LocalHeddle,
    slots: Int,
    timeSource: TimeSource.WithComparableMarks,
    costUnit: Duration,
    clockStart: Long = 0L,
) {
    private val engine: LocalHeddle
    private val timeSource: TimeSource.WithComparableMarks
    private val costUnit: Duration
    private val costUnitNanos: Long
    private val clockStart: Long
    private val origin: ComparableTimeMark

    /** How many leases may be open at once. */
    private var slots: Int

    private val lock = reentrantLock()

    /** Each leaf's waiters, head first. Insertion-ordered, so it iterates alike on every target. */
    private val queues = LinkedHashMap<GroupId, ArrayDeque<Waiter>>()

    /** The leases this pool counts against [slots]. */
    private val open: HashSet<LeaseId>

    /** Resumptions collected under the lock, delivered once the outermost [mutate] releases it. */
    private var pending = ArrayList<Resumption>()

    /** How many [mutate] frames this thread is inside; only the outermost dispatches and drains. */
    private var depth = 0

    private val defaultReservation: Long

    init {
        require(slots >= 0) { "slots must be non-negative, was $slots" }
        require(costUnit.isPositive() && costUnit.isFinite()) { "costUnit must be positive and finite, was $costUnit" }
        require(clockStart >= 0L) { "clockStart must be non-negative, was $clockStart" }
        engine.reconcileCapacity(slots.toLong())
        this.engine = engine
        this.slots = slots
        this.timeSource = timeSource
        this.costUnit = costUnit
        this.costUnitNanos = costUnit.inWholeNanoseconds
        this.clockStart = clockStart
        this.defaultReservation = engine.defaultReservation
        this.open = engine.snapshot().leases.mapTo(HashSet()) { it.id }
        this.origin = timeSource.markNow()
    }

    /** The pool's clock: `clockStart` plus the whole cost units elapsed since construction, floored. */
    public fun now(): Long {
        val units = nowUnits()
        return if (units > Long.MAX_VALUE - clockStart) Long.MAX_VALUE else clockStart + units
    }

    /**
     * Suspend until fair share grants [leaf] a slot, run [block] with its lease, and charge the lease
     * the time the block took, in whole cost units rounded up. Answers what [block] returned.
     *
     * The charge is made however the block ends: when it returns, when it throws, and when it is
     * cancelled part-way, and the throwable or the cancellation then goes on to the caller. Work that
     * failed or was cancelled still held the slot, and a lane whose work fails fast must not get the
     * farm for free. The clock starts when the block starts, so the moment between the grant and the
     * caller resuming is never charged. A caller cancelled before its block starts is handed back
     * with nothing charged, as at [acquire].
     *
     * A block may settle its own lease with [complete] or [cancel]; the charge at its end then finds
     * the lease closed, answers [LeaseOutcome.Unknown], and changes nothing.
     *
     * @param reservation the cost the caller expects the work to take, as at [acquire].
     * @throws SlotRefusedException where [acquire] would answer [SlotOutcome.Refused]: no lease can be
     *   granted to [leaf], and [block] never runs.
     * @throws IllegalArgumentException when [reservation] is not in `1..`[LocalHeddle.MAX_RESERVATION],
     *   before anything waits.
     * @throws Exception the pool's failure, when it fails while the caller is queued: whatever the
     *   engine threw, or an [IllegalStateException] for an answer the engine's contract rules out
     *   (see Failures, above). [block] never runs.
     * @sample us.tractat.kuilt.heddle.sampleWithSlot
     */
    public suspend fun <T> withSlot(leaf: GroupId, reservation: Long = defaultReservation, block: suspend (Lease) -> T): T {
        val lease = when (val outcome = acquire(leaf, reservation)) {
            is SlotOutcome.Granted -> outcome.lease
            is SlotOutcome.Refused -> throw SlotRefusedException(outcome.reason)
        }
        val started = timeSource.markNow()
        // No catch and no NonCancellable: complete never suspends, so it runs even once cancelled.
        try {
            return block(lease)
        } finally {
            // Clamped here, not refused in costUnits: a throw in this finally would leak the slot and
            // replace the block's own throwable. A clock that stepped back charges nothing.
            complete(lease.id, costUnits(started.elapsedNow().coerceAtLeast(Duration.ZERO), costUnit))
        }
    }

    /**
     * Suspend until fair share grants [leaf] a slot, and answer [SlotOutcome.Granted] with its lease,
     * or [SlotOutcome.Refused] when no lease can end at [leaf] as the tree stands now. The caller
     * holds a granted slot until it calls [complete] or [cancel].
     *
     * Cancelling the caller while it waits, or after its grant was made but before it resumed, frees
     * the slot: the lease is cancelled with nothing charged.
     *
     * @param reservation the cost the caller expects the work to take. Below the engine's default the
     *   lease holds the default, since a lease cannot shrink. Above it, the lease holds [reservation]
     *   when the engine can extend it that far; otherwise it holds the default, and the rest arrives
     *   as an overrun at [complete].
     * @throws IllegalArgumentException when [reservation] is not in `1..`[LocalHeddle.MAX_RESERVATION],
     *   before anything waits.
     * @throws Exception the pool's failure, when it fails while the caller is queued: whatever the
     *   engine threw, or an [IllegalStateException] for an answer the engine's contract rules out
     *   (see Failures, above).
     * @sample us.tractat.kuilt.heddle.sampleAcquireSlot
     */
    public suspend fun acquire(leaf: GroupId, reservation: Long = defaultReservation): SlotOutcome {
        require(reservation in 1L..LocalHeddle.MAX_RESERVATION) {
            "reservation must be in 1..${LocalHeddle.MAX_RESERVATION}, was $reservation"
        }
        return suspendCancellableCoroutine { cont ->
            val waiter = Waiter(leaf, reservation, cont)
            // First, so a cancel that lands while the waiter is queued always finds the handler.
            cont.invokeOnCancellation { mutate { removeWaiter(waiter) } }
            mutate { if (cont.isActive) arrive(waiter) }
        }
    }

    /**
     * Close [lease] and charge it [actualCost], as [LocalHeddle.complete] does, with the pool's
     * waiting leaves as the waiting set. A lease the pool counts frees its slot, and the next waiter
     * is granted it. A lease the engine does not hold answers [LeaseOutcome.Unknown] or
     * [LeaseOutcome.Stale] and changes nothing.
     *
     * @throws IllegalArgumentException when [actualCost] is negative.
     */
    public fun complete(lease: LeaseId, actualCost: Long): LeaseOutcome = mutate {
        engine.complete(lease, actualCost, queues.keys).also {
            if (it is LeaseOutcome.Done) open.remove(lease)
            afterCompleteForTest(lease, actualCost, it)
        }
    }

    /**
     * Close [lease] without charging it, as [LocalHeddle.cancel] does. A lease the pool counts frees
     * its slot. A lease the engine does not hold changes nothing.
     */
    public fun cancel(lease: LeaseId): LeaseOutcome = mutate {
        engine.cancel(lease).also { if (it is LeaseOutcome.Done) open.remove(lease) }
    }

    /**
     * Change how many leases may be open at once to [slots], reconciling the engine's capacity to
     * it. Raising the count grants queued waiters at once, in the engine's own order. Lowering it
     * preempts nothing: every open lease stays open, and the next grant waits until fewer than
     * [slots] are.
     *
     * @throws IllegalArgumentException when [slots] is negative, or the engine refuses to reconcile
     *   its capacity to it (see [LocalHeddle.reconcileCapacity]); the pool keeps its old count.
     */
    public fun resize(slots: Int) {
        require(slots >= 0) { "slots must be non-negative, was $slots" }
        mutate {
            // The engine first, so a count it refuses is never assigned.
            engine.reconcileCapacity(slots.toLong())
            this.slots = slots
        }
    }

    /**
     * Run [block] on the engine, under the pool's lock, and answer what it returned. This is the one
     * way to reach the engine once it is in a pool: [LocalHeddle.persist] for a snapshot,
     * [LocalHeddle.explain] for why a lane waits, [LocalHeddle.apply] for a reload,
     * [LocalHeddle.reconcileLeases] to find orphans. Every other thread waits on the lock meanwhile,
     * so keep the block short.
     *
     * The block is handed the pool's waiting set beside the engine: every leaf with a waiter
     * queued, as a copy taken when the block starts. It is the set the engine's own calls take as
     * `waiting`, and a guessed one skews them: an overrunning [LocalHeddle.complete] or an
     * [LocalHeddle.extend] observes it as demand, so a set missing a queued lane changes later
     * picks. `explain(leaf, waiting, waiting)` answers why a lane waits, as the pool's next pick
     * sees it. To settle a lease the pool granted, prefer [complete] or [cancel]: they work from
     * inside the block and use the live set.
     *
     * When the block ends, however it ends, the pool catches up with what it did, then hands out
     * slots and resumes the waiters it granted, refused or failed, after releasing the lock:
     * - a pool lease the engine no longer holds, completed or cancelled through the engine, frees
     *   its slot;
     * - the waiters of a leaf no lease can end at any more, say one a reload removed, are refused;
     * - a waiter the block's changes made grantable, say by a reload lifting a ceiling, is granted.
     *
     * A refusal reads the tree as it stands when the waiter arrives, so a waiter for a leaf that a
     * later block attaches is refused before that block runs: attach the leaf first, then wait on it.
     *
     * The slot count is the pool's: change it with [resize]. A block that calls
     * [LocalHeddle.reconcileCapacity] itself changes the engine only until the block ends, when the
     * pool reconciles the engine back to [slots].
     *
     * What it does not catch up with: a lease the block opens with [LocalHeddle.acquire] or
     * [LocalHeddle.admit] is never counted against [slots], though [complete] and [cancel] still
     * close it, and a lease the pool already let go of is never counted again. That is the one an
     * engine refused to take back from a cancelled waiter (see Failures, above): the engine still
     * holds it, and [LocalHeddle.reconcileLeases] reports it as an orphan.
     *
     * Lease ids are never reused under a pool: the engine numbers each lease one past the last in
     * its epoch, a reload keeps every open lease's id, and the pool's engine is never replaced. So a
     * late [complete] for a lease the block already closed answers [LeaseOutcome.Unknown] (or
     * [LeaseOutcome.Stale] for a lease from an earlier epoch), and never closes another caller's
     * lease.
     *
     * A block may call back into the pool, [complete], [cancel] or [withEngine]; that inner call
     * hands out nothing and resumes nothing, and this call does both once the block ends.
     *
     * @param block given the engine and the waiting set: the leaves with a waiter queued as the
     *   block starts.
     * @sample us.tractat.kuilt.heddle.sampleReloadWhileWaiting
     */
    public fun <R> withEngine(block: (engine: LocalHeddle, waiting: Set<GroupId>) -> R): R = mutate {
        val waiting = queues.keys.toSet()
        try {
            block(engine, waiting)
        } finally {
            catchUp()
        }
    }

    /** The leases the pool counts against [slots]. */
    internal val openForTest: Int get() = lock.withLock { open.size }

    /** Every queued waiter, across every leaf. */
    internal val waitersForTest: Int get() = lock.withLock { queues.values.sumOf { it.size } }

    /** How many queued waiters a cancellation handler has removed from their queue. */
    internal val removedWhileQueuedForTest: AtomicInt = atomic(0)

    /** How many granted leases a waiter cancelled before its grant took has handed back. */
    internal val handedBackForTest: AtomicInt = atomic(0)

    /** Called just before each grant or refusal is resumed, outside the lock. */
    internal var beforeResumeForTest: (SlotOutcome) -> Unit = {}

    /** Called under the lock after each [complete]'s engine answer, with the lease, the cost and the answer. */
    internal var afterCompleteForTest: (lease: LeaseId, actualCost: Long, outcome: LeaseOutcome) -> Unit = { _, _, _ -> }

    /** Called under the lock before each engine acquire a hand-out makes; a throw rigs a dispatch failure. */
    internal var dispatchFaultForTest: (() -> Unit)? = null

    /** Called under the lock just before a hand-back's engine cancel; a throw rigs the engine refusing it. */
    internal var handBackFaultForTest: (() -> Unit)? = null

    /**
     * Run [block] under the lock. The outermost frame then hands out slots, even when [block]
     * threw, and resumes what was collected once it has released the lock. An inner frame, reached
     * by re-entering the pool from under its own lock, does neither.
     */
    private fun <R> mutate(block: () -> R): R {
        var drained: List<Resumption> = emptyList()
        try {
            return lock.withLock {
                depth++
                try {
                    try {
                        block()
                    } finally {
                        if (depth == 1) dispatch()
                    }
                } finally {
                    depth--
                    if (depth == 0 && pending.isNotEmpty()) {
                        drained = pending
                        pending = ArrayList()
                    }
                }
            }
        } finally {
            drained.forEach(::resumeOne)
        }
    }

    /** A new waiter: queue it, unless it is the first for an unleasable leaf while no slot is free. */
    private fun arrive(waiter: Waiter) {
        queues[waiter.leaf]?.let {
            it.addLast(waiter)
            return
        }
        // With a slot free the hand-out reaches this leaf at once, and needs no check.
        if (open.size >= slots) {
            unleasable(waiter.leaf)?.let {
                pending += Resumption.Refuse(waiter.cont, SlotOutcome.Refused(it))
                return
            }
        }
        queues[waiter.leaf] = ArrayDeque<Waiter>().apply { addLast(waiter) }
    }

    /**
     * After a [withEngine] block: reconcile the engine back to [slots], forget the pool leases the
     * engine no longer holds, and refuse the waiters of every leaf it can no longer lease. Throws
     * nothing, so the block's own throwable or answer reaches the caller: an engine failing here
     * fails the waiters, as in [dispatch].
     */
    private fun catchUp() {
        try {
            // Undoes a block's own reconcileCapacity. The same count again changes nothing, and the
            // pool reconciled this one already, so the engine cannot refuse it.
            engine.reconcileCapacity(slots.toLong())
            val held = engine.snapshot().leases.mapTo(HashSet()) { it.id }
            // retainAll only removes: a lease the pool let go of is never counted again.
            open.retainAll(held)
            refuseUnleasable(queues.keys.toList())
        } catch (failure: Exception) { // ALLOW-ise: nothing inside the try suspends
            failAll(failure)
        }
    }

    private fun removeWaiter(waiter: Waiter) {
        val queue = queues[waiter.leaf] ?: return
        if (!queue.remove(waiter)) return
        removedWhileQueuedForTest.incrementAndGet()
        if (queue.isEmpty()) queues.remove(waiter.leaf)
    }

    /** Hand out slots while one is free and a leaf has a waiter. Throws nothing: a failure goes to the waiters. */
    private fun dispatch() {
        try {
            while (open.size < slots && queues.isNotEmpty()) {
                dispatchFaultForTest?.invoke()
                when (val out = engine.acquire(queues.keys, queues.keys, now(), defaultReservation)) {
                    is AcquireOutcome.Acquired -> grant(out.lease)
                    AcquireOutcome.NothingEligible -> return
                    AcquireOutcome.SupplyExhausted ->
                        throw IllegalStateException("the engine answered SupplyExhausted with $slots slots reconciled")
                    is AcquireOutcome.NoLiveLineage, is AcquireOutcome.LifetimeExhausted ->
                        check(refuseUnleasable(queues.keys.toList()) > 0) {
                            "the engine answered $out, yet no waiting leaf is unleasable on its own"
                        }
                }
            }
        } catch (failure: Exception) { // ALLOW-ise: nothing inside the try suspends
            failAll(failure)
        }
    }

    /** Grant [acquired] to the head waiter of its leaf, extended to the waiter's reservation. */
    private fun grant(acquired: Lease) {
        val queue = checkNotNull(queues[acquired.leaf]) { "the engine leased ${acquired.leaf.value}, which has no waiter" }
        val waiter = queue.removeFirst()
        if (queue.isEmpty()) queues.remove(acquired.leaf)
        open += acquired.id
        // Collected at the default first, so an extend that throws still leaves the waiter its lease.
        val at = pending.size
        pending += Resumption.Grant(waiter.cont, SlotOutcome.Granted(acquired))
        if (waiter.reservation > acquired.reserved) {
            val extended = engine.extend(acquired.id, waiter.reservation - acquired.reserved, queues.keys)
            if (extended is LeaseOutcome.Done && extended.unrecorded == 0L) {
                pending[at] = Resumption.Grant(waiter.cont, SlotOutcome.Granted(acquired.copy(reserved = waiter.reservation)))
            }
        }
    }

    /**
     * Refuse every waiter of each leaf in [leaves] that no lease can end at, asking about each leaf
     * on its own: asked beside other leaves, a healthy leaf reads as exhausted when another full lane
     * is next in line. Returns how many waiters were refused.
     */
    private fun refuseUnleasable(leaves: Collection<GroupId>): Int {
        var refused = 0
        for (leaf in leaves) {
            val reason = unleasable(leaf) ?: continue
            val queue = queues.remove(leaf) ?: continue
            queue.forEach { pending += Resumption.Refuse(it.cont, SlotOutcome.Refused(reason)) }
            refused += queue.size
        }
        return refused
    }

    /** The engine's reason no lease can end at [leaf], or `null` when one can. */
    private fun unleasable(leaf: GroupId): WaitExplanation? {
        val alone = setOf(leaf)
        return when (val answer = engine.explain(leaf, alone, alone)) {
            is WaitExplanation.NoLiveLineage, is WaitExplanation.LifetimeExhausted -> answer
            else -> null
        }
    }

    private fun failAll(failure: Throwable) {
        queues.values.forEach { queue -> queue.forEach { pending += Resumption.Fail(it.cont, failure) } }
        queues.clear()
    }

    /**
     * Return a granted lease whose waiter was cancelled before its grant took. This runs as a
     * cancellation callback, where a throw reaches the uncaught-exception handler, so it throws
     * nothing: the slot is freed first, and an engine that refuses the cancel fails the waiters, as
     * a dispatch failure does. The engine then still holds the lease, which
     * [LocalHeddle.reconcileLeases] reports as an orphan.
     */
    private fun handBack(lease: LeaseId) {
        mutate {
            if (open.remove(lease)) {
                handedBackForTest.incrementAndGet()
                try {
                    handBackFaultForTest?.invoke()
                    engine.cancel(lease)
                } catch (failure: Exception) { // ALLOW-ise: nothing inside the try suspends
                    failAll(failure)
                }
            }
        }
    }

    private fun resumeOne(resumption: Resumption) {
        when (resumption) {
            is Resumption.Grant -> {
                beforeResumeForTest(resumption.outcome)
                val lease = resumption.outcome.lease.id
                resumption.cont.resume(resumption.outcome) { _, _, _ -> handBack(lease) }
            }
            is Resumption.Refuse -> {
                beforeResumeForTest(resumption.outcome)
                resumption.cont.resume(resumption.outcome)
            }
            is Resumption.Fail -> resumption.cont.resumeWithException(resumption.failure)
        }
    }

    /** Whole cost units since construction, floored. */
    private fun nowUnits(): Long = origin.elapsedNow().coerceAtLeast(Duration.ZERO).inWholeNanoseconds / costUnitNanos

    /** One caller waiting for a slot. Compared by identity, so a cancelled waiter removes only itself. */
    private class Waiter(val leaf: GroupId, val reservation: Long, val cont: CancellableContinuation<SlotOutcome>)

    private sealed interface Resumption {
        class Grant(val cont: CancellableContinuation<SlotOutcome>, val outcome: SlotOutcome.Granted) : Resumption
        class Refuse(val cont: CancellableContinuation<SlotOutcome>, val outcome: SlotOutcome.Refused) : Resumption
        class Fail(val cont: CancellableContinuation<SlotOutcome>, val failure: Throwable) : Resumption
    }
}

/**
 * [elapsed] in whole [costUnit]s, rounded up, so any time at all costs at least one unit. Divides in
 * whole nanoseconds and adds the remainder's unit separately: `(e + u - 1) / u` would overflow near
 * `Long.MAX_VALUE`.
 */
internal fun costUnits(elapsed: Duration, costUnit: Duration): Long {
    require(!elapsed.isNegative()) { "elapsed must be non-negative, was $elapsed" }
    require(costUnit.isPositive()) { "costUnit must be positive, was $costUnit" }
    val e = elapsed.inWholeNanoseconds
    val u = costUnit.inWholeNanoseconds
    return e / u + if (e % u != 0L) 1L else 0L
}
