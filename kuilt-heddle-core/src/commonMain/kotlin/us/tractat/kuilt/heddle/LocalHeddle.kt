package us.tractat.kuilt.heddle

import kotlinx.serialization.BinaryFormat
import kotlinx.serialization.StringFormat
import us.tractat.kuilt.crdt.ReplicaId

/**
 * How much supply a [LocalHeddle] keeps ahead of its leases, in rounds of capacity: one round is
 * every slot ([LocalHeddle.reconcileCapacity]) leased once at [LocalPolicy.defaultReservation].
 *
 * When an [LocalHeddle.acquire] or [LocalHeddle.extend] would leave the root holding less than
 * [lowWatermarkSlots] rounds, it first mints enough that the root still holds [targetSlots] rounds
 * after it. So a top-up runs about once per `targetSlots − lowWatermarkSlots` rounds of leases,
 * and the low watermark is the headroom kept for what is never refused: an overrun charged at
 * [LocalHeddle.complete], and an [LocalHeddle.admit]. Work beyond that headroom is still charged,
 * through a mint of its own.
 *
 * @property lowWatermarkSlots the rounds of supply below which a top-up runs. Non-negative.
 * @property targetSlots the rounds of supply a top-up leaves. At least [lowWatermarkSlots].
 */
public data class SupplyPolicy(
    public val lowWatermarkSlots: Long = 1L,
    public val targetSlots: Long = 2L,
) {
    init {
        require(lowWatermarkSlots >= 0L) { "lowWatermarkSlots must be non-negative, was $lowWatermarkSlots" }
        require(targetSlots >= lowWatermarkSlots) {
            "targetSlots must be at least lowWatermarkSlots ($lowWatermarkSlots), was $targetSlots"
        }
    }
}

/**
 * How a [LocalHeddle] sizes a lease, how much idle credit a waking group keeps, and how far ahead
 * it mints supply.
 *
 * @property defaultReservation the cost a lease reserves when the caller passes no estimate, in the
 *   caller's cost unit (time, by convention: R2). Positive, and at most [LocalHeddle.MAX_RESERVATION].
 * @property sleeperCredit the virtual-time credit a group that wakes from idle may keep (design
 *   §10.6). `0`, the default, keeps none. Non-negative. It is not the whole bound: a lease still
 *   open when its group wakes can hand more back at [LocalHeddle.complete] or [LocalHeddle.cancel],
 *   up to its reservation over the edge's weight, tracked by #3238.
 * @property supply how far ahead of its leases the engine mints supply.
 * @sample us.tractat.kuilt.heddle.sampleLocalLease
 */
public data class LocalPolicy(
    public val defaultReservation: Long,
    public val sleeperCredit: Long = 0L,
    public val supply: SupplyPolicy = SupplyPolicy(),
) {
    init {
        require(defaultReservation in 1L..LocalHeddle.MAX_RESERVATION) {
            "defaultReservation must be in 1..${LocalHeddle.MAX_RESERVATION}, was $defaultReservation"
        }
        require(sleeperCredit >= 0L) { "sleeperCredit must be non-negative, was $sleeperCredit" }
    }
}

/**
 * A lease's name: the [epoch] the engine that issued it was started with, and its place in that
 * engine's issue order. Ordered by epoch, then by [seq].
 */
public data class LeaseId(public val epoch: Long, public val seq: Long) : Comparable<LeaseId> {
    override fun compareTo(other: LeaseId): Int =
        compareValuesBy(this, other, LeaseId::epoch, LeaseId::seq)
}

/**
 * One open lease: one slot of work running for [leaf].
 *
 * @property reserved the cost delegated to it so far: its reservation plus every extension.
 * @property startedAt when the work started, as the caller reported it, in the cost unit. Stored
 *   and handed back, never read by a pick: two runs that differ only in start times make the same
 *   picks.
 */
public data class Lease(
    public val id: LeaseId,
    public val leaf: GroupId,
    public val reserved: Long,
    public val startedAt: Long,
)

/**
 * A [LocalHeddle]'s state, as one immutable value: once taken, safe to read or hand to another
 * thread without the caller's scheduling lock. What a restart needs is [LocalHeddleSnapshot], taken
 * by [LocalHeddle.persist]; this is the status view.
 *
 * Mints need no counter: each epoch writes one mint record, `local#<epoch>`, whose amount is the
 * epoch's running total, read back off the [ledger].
 *
 * @property leases every open lease, in [LeaseId] order.
 * @property allocatorMemory the fair-share allocator's wake memory, which a restart must keep for
 *   the next pick to be the one this engine would have made.
 */
public data class LocalHeddleState(
    public val ledger: EntitlementLedger,
    public val leases: List<Lease>,
    public val epoch: Long,
    public val allocatorMemory: AllocatorMemory,
)

/** What [LocalHeddle.acquire] did. */
public sealed interface AcquireOutcome {
    /** A lease was opened. */
    public data class Acquired(public val lease: Lease) : AcquireOutcome, AdmitOutcome

    /** No leaf was eligible for this slot. Nothing changed. */
    public data object NothingEligible : AcquireOutcome

    /**
     * The root holds less than the reservation, and no capacity is reconciled to top it up
     * ([LocalHeddle.reconcileCapacity]). Nothing changed.
     */
    public data object SupplyExhausted : AcquireOutcome

    /**
     * No lineage of ACTIVE edges leads from the root to an eligible leaf, or to the admitted one. A
     * group mid-reshape, a leaf behind a closing edge, a name that is not a leaf, and a group the
     * topology does not declare leasable all land here (see "What a lease ends at" on
     * [LocalHeddle]). Nothing changed, and nothing was charged.
     *
     * From [LocalHeddle.acquire], [at] is always the root: a pick only goes down an edge that leads
     * to a group the descent can stop at, so once it leaves the root it cannot fail. From
     * [LocalHeddle.admit], [at] is the leaf that was named.
     */
    public data class NoLiveLineage(public val at: GroupId) : AcquireOutcome, AdmitOutcome

    /**
     * The pick would have to order a position at its ceiling ([parent] has a child there), or its
     * own delegation would take one there. Positions are kept in a `Long` of 1/16-unit ticks, and
     * one at the ceiling no longer orders exactly, so the pick is refused rather than guessed.
     * Nothing changed, the allocator's wake memory included.
     *
     * Only choosing is refused: [LocalHeddle.admit] and [LocalHeddle.complete] still charge a
     * parent at its ceiling, and the position stays there (see "Failure surface" on [LocalHeddle]).
     */
    public data class PositionCeiling(public val parent: GroupId) : AcquireOutcome
}

/**
 * What [LocalHeddle.admit] did: [AcquireOutcome.Acquired] or [AcquireOutcome.NoLiveLineage]. It
 * shares those two arms with [AcquireOutcome] and has no other, since an admission makes no pick
 * and is never refused for supply.
 */
public sealed interface AdmitOutcome

/**
 * What [LocalHeddle.reconcileLeases] found: the two ways the caller's running work and the
 * engine's open leases can disagree. Both lists are in [LeaseId] order.
 *
 * @property orphaned every open lease the caller did not name as live: work that is no longer
 *   running, and was never completed or cancelled.
 * @property unknown every id the caller named as live that the engine holds no open lease for,
 *   from this epoch or another.
 */
public data class LeaseReconciliation(
    public val orphaned: List<Orphan>,
    public val unknown: List<LeaseId>,
) {
    /**
     * An open lease whose work is no longer running, and how long it ran.
     *
     * @property elapsed `now − lease.startedAt` in the cost unit, or `0` when the caller's clock
     *   went back, say across a restart.
     */
    public data class Orphan(public val lease: Lease, public val elapsed: Long) {
        init {
            require(elapsed >= 0L) { "elapsed must be non-negative, was $elapsed" }
        }

        /** What the engine handed this lease: its reservation plus every extension, [Lease.reserved]. */
        public val granted: Long get() = lease.reserved

        /**
         * The documented settlement, `min(elapsed, granted)`: charge the time it ran, but no more
         * than it was granted, since a dead piece of work's overrun cannot be verified. A caller
         * with evidence of a longer run may [LocalHeddle.complete] it at more.
         */
        public val defaultSettlement: Long get() = minOf(elapsed, granted)
    }
}

/** What [LocalHeddle.extend], [LocalHeddle.complete] or [LocalHeddle.cancel] did. */
public sealed interface LeaseOutcome {
    /**
     * The operation applied. [charged] is the cost charged to the lease's leaf and [overrun] how
     * much of it lay beyond the reservation. Both are `0` for an extend and a cancel.
     */
    public data class Done(public val charged: Long, public val overrun: Long) : LeaseOutcome

    /**
     * An extend the root could not fund, with no capacity reconciled to top it up
     * ([LocalHeddle.reconcileCapacity]). The lease and the ledger are unchanged.
     */
    public data object SupplyExhausted : LeaseOutcome

    /**
     * An extend refused because the lease's path is draining: [edge], the first edge of it below the
     * root that is no longer ACTIVE, takes no new delegation (a reload is removing it). The lease and
     * the ledger are unchanged. The lease still settles: [LocalHeddle.complete] and
     * [LocalHeddle.cancel] are never refused.
     */
    public data class PathDraining(public val edge: AttachmentId) : LeaseOutcome

    /** No open lease has this id, and it is from this engine's own epoch. Nothing changed. */
    public data object Unknown : LeaseOutcome

    /** No open lease has this id, and it is from another epoch than [currentEpoch]. Nothing changed. */
    public data class Stale(public val currentEpoch: Long) : LeaseOutcome

    /**
     * An extend whose delegation would take an edge of the lease's path to its position ceiling, or
     * across a path already there; [parent] is the group above that edge. The lease and the engine
     * are unchanged. The lease still settles: [LocalHeddle.complete] and [LocalHeddle.cancel] are
     * never refused.
     */
    public data class PositionCeiling(public val parent: GroupId) : LeaseOutcome
}

/**
 * A fair-share scheduler for one process: it decides which group's work runs in each free slot,
 * so that over time each group gets the share its weight promises, and it keeps the books on what
 * each one actually used.
 *
 * Each slot is a **lease**. When a slot frees, ask [acquire] who goes next: it picks one leaf of
 * the tree and reserves an estimated cost for it. When the work finishes, [complete] charges what
 * it really cost and hands back what it did not use. Cost is time, by convention, in whatever unit
 * the caller counts in. A lease that runs long is [extend]ed, or charged its overrun at the end;
 * a charge is never refused. Work another scheduler already started is [admit]ted, with no pick;
 * pass the waiting set to [admit] and the wake memory observes it as the pick would have.
 *
 * Start one with [fromTopology], and change the tree while it runs with [apply]: a reload is one
 * unit, a reweight moves the leases open under it onto the new edge, and a removal drains (see
 * [apply]). Runtime children come and go with [attachRuntimeChild] and [detachRuntimeChild].
 *
 * ## How a pick works
 *
 * [acquire] walks down from [root], and at each group makes one EEVDF pick among the children
 * that lead to an eligible leaf ([FairShareAllocator.pickChild]), then delegates the reservation
 * down to the child it picked. "Lead to" is strict: a child counts only when a lineage of ACTIVE
 * edges runs from it down to an eligible leaf, so a subtree whose only leaf sits behind a closing
 * edge is never picked in place of a sibling that can run. The deadline it orders by is
 * `ev + reservation / weight`, and every candidate in one pick shares this slot's reservation: it
 * is EEVDF by the slot's request length, so between two children that request alike, the one
 * further behind goes first. Two sets steer it, and they are different on purpose:
 * - **waiting** is every leaf with work queued that a descent could reach: a leaf behind ACTIVE
 *   edges, the same test the pick applies. It decides who is competing, which is what seats a new
 *   group at its parent's front and what tells a group returning from idle apart from one that
 *   never left.
 * - **eligible** is the waiting leaves this particular slot can run. Only they can be picked.
 *
 * A group that is waiting and reachable but cannot run on this slot keeps its claim: it is not
 * idle, so the next slot it can run on does not clamp it as a waker. Work no descent can reach is
 * a different case and counts as **idle**, even if the caller lists it as waiting: a leaf whose
 * only route crosses a closing edge, or a named group that has gained an ACTIVE child. A subtree
 * holding only such work is observed idle, so it banks no credit through the reshape window
 * (design §10.5): when it can be reached again, say because a new child under it activates, it is
 * clamped as a waker rather than owed the slots it could not have used.
 *
 * ## What a lease ends at
 *
 * A lease ends only at a **leasable** group: a leaf (no ACTIVE child edge) that the topology
 * declares a lane, or a runtime child of a group it marks for them. Being a leaf is not enough. A
 * group whose children are all inactive reads as a leaf in the ledger, and only the topology can
 * tell a lane from a group that has nothing attached right now, such as a marked group before its
 * first runtime child arrives. That group is never leased, and work named at it is never wanted.
 * The pick's terminal, the lift of [acquire]'s two sets into demand, [admit], [explain] and
 * [entitlement] read this one predicate, so none of them can accept a group the others refuse.
 *
 * ## Ceilings
 *
 * A node of the topology may carry a ceiling, [Topology.Node.maxConcurrent]: the most leases open
 * beneath it at once. A lease counts at every group its path passes through, a draining one
 * included. Before a pick builds its demand views, every leaf beneath a group at its ceiling is
 * dropped from both sets, so the pick goes to a sibling, and the capped group counts as idle at its
 * parent: it banks no credit while it waits, and wakes clamped once a lease beneath it closes. A
 * ceiling of `0` pauses everything beneath it. [previewPicks] and [explain] filter the same way,
 * counting the leases already open and the ones a preview opens. The count is derived from the open
 * leases on every call, so a ceiling persists nothing and a reload that changes only ceilings
 * changes no edge. [admit], [extend], [complete] and [apply] charge work that is already running,
 * so no ceiling refuses them, and lowering one preempts nothing.
 *
 * ## Asking why
 *
 * [explain] says why a leaf is not what the next [acquire] picks, or that it is, by running that
 * very pick on a throwaway copy of the engine. [entitlement] says what share of the slots the leaf
 * is owed while the waiting set holds still. Neither changes anything.
 *
 * ## Every delegation is seated first
 *
 * Delegating down an edge that has no virtual-time gauge yet would seat it at its own origin, for
 * good: a permanent lifetime credit (#1752). So every delegation this engine makes, picked or not,
 * goes through one helper that seats an unseated edge at its parent's front first. A pick seats
 * the parent's newborns itself; [admit], [extend] and an overrun at [complete] rely on the helper.
 *
 * ## All or nothing
 *
 * Every call that changes the engine runs on a working copy of its whole state (ledger, open
 * leases, wake memory, capacity) and the engine keeps that copy only when the call returns. So a
 * call that **throws** changes nothing, whatever it throws, and neither does one refused at a
 * position ceiling ([AcquireOutcome.PositionCeiling], [LeaseOutcome.PositionCeiling]): the engine
 * reads, and its [persist]ed snapshot encodes, exactly as before, and the next call starts clean. A
 * whole reload is one operation, however many children it changes.
 *
 * The copy costs one copy of the open leases and of the wake memory per call.
 *
 * **The ordinary refusals keep one change, the allocator's wake memory.** An [acquire] refused with
 * [AcquireOutcome.NoLiveLineage] leaves the ledger as it was, but the pick it tried at the root
 * observed who was waiting, and forgetting that would treat the next observation as the first. So
 * does the `NoLiveLineage` of the [admit] overload that takes a waiting set, which observes it at
 * the root. Neither keeps any part of a rebase: a rebase is kept only with the delegated ledger it
 * belongs to. A ceiling refusal keeps nothing, because its pick could not order the children it observed.
 *
 * ## Supply
 *
 * Supply is minted at [root], and the engine mints its own: tell it how many slots you run with
 * [reconcileCapacity], and [acquire] and [extend] top the root up inside the same operation
 * whenever it would fall below [LocalPolicy.supply]'s low watermark, so supply never runs out
 * between calls to it. The top-up is part of the operation: one that is refused leaves none
 * behind. Until a capacity is reconciled nothing is minted ahead, and [acquire] and [extend] are
 * refused with a `SupplyExhausted` outcome when the root holds less than they need. [complete]
 * and [admit] are never refused: work that already ran or already started is charged, so when
 * the root is short they mint the shortfall. The low watermark is the headroom that keeps them
 * from needing to.
 *
 * Every mint of an epoch, top-ups included, rewrites one record carrying the epoch's running
 * total, so the ledger keeps one mint record per epoch however much it serves.
 *
 * ## Reconciling leases
 *
 * [reconcileLeases] compares the open leases with the work the caller still has running and
 * reports both directions: orphans, open leases whose work is gone, each with how long it ran
 * from its `startedAt`; and live ids the engine holds no lease for. It changes nothing. Settle an
 * orphan with [complete], by default at [LeaseReconciliation.Orphan.defaultSettlement].
 *
 * ## Persisting
 *
 * [persist] takes a [LocalHeddleSnapshot], everything a restart needs, and [restore] starts an
 * engine from one under a later epoch: every open lease is valid again under its old id, the
 * topology last applied is the one it leases by, and the next pick is the one this engine would
 * have made. Encode the snapshot as JSON or CBOR;
 * [decode] reads it back and names an unsupported version or corrupt bytes rather than throwing.
 * After a [restore], settle what did not survive the restart with [reconcileLeases].
 *
 * ## A draining path
 *
 * A reload can close an edge on an open lease's path. Nothing new crosses a closing edge, so an
 * [extend] there returns [LeaseOutcome.PathDraining] and changes nothing. A [complete] still
 * settles: what was reserved is charged at the leaf as usual, and an overrun, which would need a
 * new delegation, is charged instead at the deepest group of the path still reached through ACTIVE
 * edges from the root. That group's own edge advances by the overrun, which is the fairness that
 * still matters, since a closing edge is never picked again. When the root's own child edge is the
 * closing one there is no such group below the root, and the ledger has no counter for spend at
 * the root, so the overrun is reported in [LeaseOutcome.Done] but recorded on no edge.
 *
 * ## What it does not do
 *
 * It reads no clock and draws no random number: every time is a number the caller passes in, and
 * every output is ordered by id, so a replay of the same calls gives the same picks on every
 * target. It owns no concurrency either — call [acquire] once per free slot.
 *
 * **Not thread-safe.** One engine has one writer, and the caller serializes calls to it, [snapshot]
 * included: taking one reads the engine's own maps. The value it returns is immutable, so a reader
 * that must not hold the caller's lock is handed a snapshot taken under it.
 *
 * ## Failure surface
 *
 * Nothing suspends and nothing times out. An argument out of range throws
 * [IllegalArgumentException], a reservation or extension above [MAX_RESERVATION] included.
 *
 * Arithmetic that would leave `Long` throws [ArithmeticException] rather than wrapping. The place
 * a running engine reached it first is now a typed refusal instead: an edge's **position**, its
 * service in ticks of 1/16 of a unit, has a ceiling, `Long.MAX_VALUE` less room for one
 * [MAX_RESERVATION] deadline, divided by its weight's denominator. A joiner inherits its position
 * from the front it is seated at, scaled by its weight against its siblings', so left alone the
 * service one parent could hand down over its lifetime would be bounded, by a bound that depends
 * on the weights. It does not depend on how often the waiting set changes: positions are whole
 * ticks, so no join can grow a denominator (#3313, #3231). At the ceiling:
 * - a call that **chooses**, [acquire] or [extend], is refused with a `PositionCeiling` outcome
 *   naming the parent, and changes nothing. A position there no longer orders exactly, so a pick
 *   over it would be a guess;
 * - a call that **charges**, [admit] or [complete], lands as usual. The books stay exact; only the
 *   position is held at the ceiling.
 *
 * A running engine rarely gets there, because a pick **rebases** its parent first (#3329). Once
 * a child's position passes a sixteenth of its ceiling, every child of that parent comes down by
 * one virtual time, its wake offset first and then its gauge fold, as far as the demanding child
 * furthest behind allows, and an idle child no further than a sixteenth of its ceiling below
 * zero. Every competing child moves alike, so no pick changes. The ceiling is still reachable
 * three ways:
 * - a demanding child sits at zero and is never served, which holds the rebase at nothing;
 * - a parent is only ever charged through [admit], [extend] or an overrun at [complete], and never
 *   picked at, so it is never rebased;
 * - an edge's lifetime **returned** service, reserved but not charged by a [complete] below the
 *   reservation or a [cancel], passes about [positionCeiling]`(w) / 16` units, about
 *   `5.8 × 10¹⁷ / w.denominator`, since a rebase cannot bring returns down (#3360). At
 *   [MAX_RESERVATION] charged 1 unit, a weight of 3/7 gets there in about 4,700 leases; a
 *   600,000-unit reservation returned in full takes about 1.4 × 10¹¹ leases at that weight. A
 *   workload that charges what it reserves never reaches it.
 *
 * A seat or a wake clamp taken against a front that includes a position at
 * its ceiling lands at the joiner's own ceiling, the penalty end, so nothing an understated front
 * would have handed out is kept as credit once the saturated child leaves.
 *
 * The diagnostics still answer in exact [Rational]s, and those can throw [ArithmeticException]
 * before the ceiling: [explain]'s [WaitExplanation.BehindAtLevel] fronts, whose numerator carries
 * the least common multiple of the sibling weights' denominators, and [entitlement].
 *
 * [apply] keeps the weight arithmetic in range the same way: a reload whose weights would put one
 * parent's products of numerators and denominators out of `Long` refuses that child with a
 * [StepRefusal.Refused], and its siblings land.
 *
 * A state the engine's own invariants rule out throws [IllegalStateException]: that is a bug in
 * the engine, or a ledger conflict the engine did not create, such as two live inbound edges into
 * one group, a divergent record, or a cycle on a lease's path (#1737). [apply] never leaves one on
 * an open lease's path: before it swaps a reload in, it checks that every open lease's path is its
 * leaf's one live lineage.
 *
 * @sample us.tractat.kuilt.heddle.sampleLocalLease
 */
public class LocalHeddle private constructor(
    /** The tree's root: the group supply is minted at and every pick starts from. */
    public val root: GroupId,
    private val policy: LocalPolicy,
    /** The boot counter this engine issues lease ids under. */
    public val epoch: Long,
    ledger: EntitlementLedger,
    allocator: FairShareAllocator,
    leasable: Leasable,
    /**
     * Leases open beneath each capped group that this engine does not hold: the real engine's, when
     * this is the throwaway engine a [LocalHeddleView] plays its questions forward on. Empty for
     * every real engine. Never changes, so it lives outside [Core].
     */
    private val openElsewhere: Map<GroupId, Int> = emptyMap(),
) {
    init {
        require(epoch >= 0L) { "epoch must be non-negative, was $epoch" }
    }

    /**
     * Everything a call can change, in one object, so that a call keeps all of it or none of it: see
     * [atomically]. A field of the engine that changes and is not in here would survive a throw.
     */
    private class Core(
        var ledger: EntitlementLedger,
        /** The wake memory lives in the allocator, so the allocator is state, not a collaborator. */
        val allocator: FairShareAllocator,
        /** What the topology lets a lease end at; see "What a lease ends at". Replaced by each [apply]. */
        var leasable: Leasable,
        /** Open leases by id. Looked up, never iterated without sorting. */
        val open: HashMap<LeaseId, OpenLease>,
        var nextSeq: Long,
        /** The capacity last reconciled, and the supply levels it sizes. */
        var capacity: Capacity,
        /** Mints [complete] and [admit] wrote to cover a shortfall at the root. */
        var forcedMints: Int,
    ) {
        /** A working copy: the ledger and leasable are immutable values; the two mutable maps are copied. */
        fun fork(): Core = Core(ledger, allocator.fork(), leasable, HashMap(open), nextSeq, capacity, forcedMints)
    }

    private var core = Core(ledger, allocator, leasable, HashMap(), nextSeq = 0L, capacity = Capacity.NONE, forcedMints = 0)

    /**
     * Run [block] on a working copy of [core], and keep the copy only if [block] returns a result
     * [keep] accepts. On a throw, or a result it refuses, the committed state is put back with one
     * assignment. The body reads and writes the engine's fields as it would without this, because
     * they all resolve through [core]. One swap of one reference, rather than a restore per field, is
     * what makes a forgotten field impossible: there is nowhere else for one to live.
     */
    private inline fun <T> atomically(keep: (T) -> Boolean = { true }, block: () -> T): T {
        val committed = core
        core = committed.fork()
        val result = try {
            block()
        } catch (failure: Throwable) {
            core = committed
            throw failure
        }
        if (!keep(result)) core = committed
        return result
    }

    private var ledger: EntitlementLedger
        get() = core.ledger
        set(value) {
            core.ledger = value
        }
    internal val allocator: FairShareAllocator get() = core.allocator
    private var leasable: Leasable
        get() = core.leasable
        set(value) {
            core.leasable = value
        }
    private val open: HashMap<LeaseId, OpenLease> get() = core.open
    private var nextSeq: Long
        get() = core.nextSeq
        set(value) {
            core.nextSeq = value
        }
    private var capacity: Capacity
        get() = core.capacity
        set(value) {
            core.capacity = value
        }

    /** Mints [complete] and [admit] wrote to cover a shortfall at the root. */
    internal var forcedMints: Int
        get() = core.forcedMints
        private set(value) {
            core.forcedMints = value
        }

    /** The engine's whole state, as an immutable value. Costs a sort of the open leases and of the wake memory. */
    public fun snapshot(): LocalHeddleState =
        LocalHeddleState(ledger, open.values.map { it.lease }.sortedBy { it.id }, epoch, allocator.memory)

    /**
     * Open one lease for the next slot: pick a leaf among [eligible] and reserve [reservation] for
     * it. [waiting] is every leaf with work queued, and must contain [eligible].
     *
     * A pick never reads [startedAt]; it is stored on the lease for the caller's own reconciling.
     *
     * Returns [AcquireOutcome.NothingEligible] when [eligible] is empty, or every leaf in it is
     * beneath a group at its ceiling (see "Ceilings"), [AcquireOutcome.SupplyExhausted] when the root holds less than [reservation] and no capacity
     * is reconciled, and [AcquireOutcome.NoLiveLineage] when no eligible leaf can be reached. In
     * each of those cases the ledger is unchanged, with no top-up either; only the allocator's wake
     * memory may have moved (see the class KDoc). [AcquireOutcome.PositionCeiling] when a pick on
     * the way would order a position at its ceiling, or the delegation would take one there; that
     * changes nothing at all.
     *
     * @throws IllegalArgumentException when [reservation] is not in `1..`[MAX_RESERVATION], or
     *   [eligible] is not a subset of [waiting].
     * @sample us.tractat.kuilt.heddle.sampleLocalLease
     */
    public fun acquire(
        waiting: Set<GroupId>,
        eligible: Set<GroupId>,
        startedAt: Long,
        reservation: Long = policy.defaultReservation,
    ): AcquireOutcome = atomically(keep = { it !is AcquireOutcome.PositionCeiling }) {
        requireReservation("reservation", reservation)
        require(waiting.containsAll(eligible)) { "eligible must be a subset of waiting" }
        if (eligible.isEmpty()) return@atomically AcquireOutcome.NothingEligible
        val (waitingNow, eligibleNow) = belowCeilings(waiting, eligible)
        if (eligibleNow.isEmpty()) return@atomically AcquireOutcome.NothingEligible
        // Topped up on the working value only: a descent that then fails discards the mint with it.
        val base = fundedBase(reservation) ?: return@atomically AcquireOutcome.SupplyExhausted
        when (val descent = descend(base, demands(base, waitingNow, eligibleNow, reservation), eligibleNow, reservation)) {
            is Descent.Refused -> AcquireOutcome.NoLiveLineage(descent.at)
            is Descent.Ceiling -> AcquireOutcome.PositionCeiling(descent.at)
            is Descent.Reached -> {
                // The ledger and the wake offsets its rebases lowered are kept together, or neither.
                ledger = descent.ledger
                descent.rebases.forEach(allocator::adopt)
                AcquireOutcome.Acquired(open(descent.leaf, descent.levels.map { it.edge }, reservation, startedAt))
            }
        }
    }

    /**
     * The pick [acquire] makes, on [base] and this engine's allocator: from [root], one
     * [FairShareAllocator.pickChild] per level and a delegation of [reservation] down the edge it
     * picked, until it reaches an eligible leaf. Every step lands on a local value; [base] is not
     * changed. Only the allocator's wake memory moves, which is why a [LocalHeddleView] runs it on a
     * throwaway engine. A level that rebases its parent (#3329) hands the rebase back in
     * [Descent.Reached.rebases] rather than changing the allocator: its lowered wake offsets belong to
     * the delegated ledger, and are adopted only with it.
     */
    internal fun descend(base: EntitlementLedger, demands: Demands, eligible: Set<GroupId>, reservation: Long): Descent {
        var local = base
        val levels = ArrayList<Level>()
        val rebases = ArrayList<Rebase>()
        var group = root
        while (group !in eligible || !local.isLeasable(group)) {
            val pick = try {
                pickChild(group, local, demands) ?: return Descent.Refused(group)
            } catch (_: PositionCeilingException) {
                return Descent.Ceiling(group)
            }
            afterPickForTest(group)
            val grant = pick.pick.grant
            val edge = grant.attachment
            check(grant.amount == reservation) {
                "picked ${edge.value} for ${grant.amount}, not the reservation $reservation"
            }
            // The settled ledger, not the rebased one: it reads right against the wake offsets the
            // allocator holds until the rebase is adopted, which is what [explain] reads it with.
            levels += Level(group, pick.settled, edge)
            pick.rebase?.let(rebases::add)
            local = checkNotNull(seatThenDelegate(pick.pick.ledger, listOf(edge), reservation, demands.waiting)) {
                "the delegation of $reservation down ${edge.value}, just picked, was refused"
            }
            // The delegation saturated the edge it picked: the grant would be charged inexactly.
            if (local.isAtPositionCeiling(edge)) return Descent.Ceiling(group)
            group = checkNotNull(local.record(edge)) { "picked edge ${edge.value} has no record" }.child
        }
        return Descent.Reached(local, group, levels, rebases)
    }

    /** One level's pick at [group], rebasing it once it grants, unless a test switched that off (#3329). */
    private fun pickChild(group: GroupId, local: EntitlementLedger, demands: Demands): RebasingPick? =
        if (rebaseForTest) {
            allocator.pickChildRebasing(group, local, demands.waiting, demands.eligible)
        } else {
            allocator.pickChild(group, local, demands.waiting, demands.eligible)?.let { RebasingPick(it.ledger, it, rebase = null) }
        }

    /**
     * The ledger a pick of [reservation] runs on, or `null` when the root cannot fund it. The one
     * supply check [acquire] and [explain] share, so the two cannot disagree about it. It reads the
     * engine and writes nothing: whatever it returns is only committed by the caller. It is topped
     * up below the low watermark ([supplied]), so an [explain] sees the supply the [acquire] would.
     */
    internal fun fundedBase(reservation: Long): EntitlementLedger? = supplied(ledger, reservation)

    /**
     * How many leases are open beneath each capped group: this engine's own, by the child of every
     * edge on their paths, draining ones included, plus [openElsewhere]. Derived on each call, so a
     * ceiling keeps nothing of its own. Empty when the topology sets no ceiling.
     */
    internal fun openBeneath(): Map<GroupId, Int> =
        openElsewhere.plusCounts(ledger.openBeneath(open.values.asSequence().map { it.path }, leasable))

    /**
     * [waiting] and [eligible] without every leaf beneath a group at its ceiling: what a pick
     * builds its demand views over, so a capped group is wanted by nobody and counts as idle at its
     * parent. The sets themselves when the topology sets no ceiling. The one filter [acquire], the
     * observing [admit] and a [LocalHeddleView]'s questions share.
     */
    internal fun belowCeilings(waiting: Set<GroupId>, eligible: Set<GroupId>): Pair<Set<GroupId>, Set<GroupId>> {
        if (!leasable.hasCeilings) return waiting to eligible
        val counts = openBeneath()
        val waitingNow = ledger.belowCeilings(waiting, counts, leasable)
        return waitingNow to eligible.filterTo(HashSet()) { it in waitingNow }
    }

    /**
     * This engine's state as a [LocalHeddleView], for the length of one call: the read-only
     * questions are answered there, so the engine and a view of its snapshot cannot disagree. It
     * hands over the live allocator, which the view only ever forks.
     */
    private fun view(): LocalHeddleView =
        LocalHeddleView(root, policy, epoch, ledger, allocator, capacity, leasable, rebase = rebaseForTest, openBeneath = openBeneath())

    /**
     * Charge work another scheduler already started at [leaf]: open a lease for it with no pick,
     * delegating [reservation] down the leaf's lineage. Never refused for supply, since the work is
     * already running; a shortfall at the root is minted.
     *
     * Returns [AcquireOutcome.NoLiveLineage] when [leaf] is not leasable (the root, a group with an
     * active child, or a group the topology declares no lane: see "What a lease ends at"), or has no
     * lineage of active edges to the root. The ledger is then unchanged.
     *
     * @throws IllegalArgumentException when [reservation] is not in `1..`[MAX_RESERVATION].
     * @sample us.tractat.kuilt.heddle.sampleBuildFarm
     */
    public fun admit(leaf: GroupId, startedAt: Long, reservation: Long = policy.defaultReservation): AdmitOutcome = atomically {
        requireReservation("reservation", reservation)
        val base = ledger
        if (!base.canEndAt(leaf)) return@atomically AcquireOutcome.NoLiveLineage(leaf)
        val path = checkNotNull(base.lineageOf(leaf)) { "${leaf.value} can be reached but has no lineage" }

        val (funded, minted) = fund(base, reservation)
        val local = seatThenDelegate(funded, path, reservation, observedDemand(reservation))
            ?: return@atomically AcquireOutcome.NoLiveLineage(leaf)
        ledger = local
        forcedMints += minted
        AcquireOutcome.Acquired(open(leaf, path, reservation, startedAt))
    }

    /**
     * [admit], observing [waiting] on the way: charge work another scheduler already started at
     * [leaf], and leave the wake memory as `acquire(waiting, eligible = {leaf})` would have (#3320).
     * For a caller whose work is always started elsewhere: the plain [admit] observes nothing, so
     * a group it saw idle that comes back is never clamped as a waker.
     *
     * At each group down [leaf]'s lineage it seats the newborns and clamps the wakers against
     * [waiting], as that pick does, then delegates [reservation] down the lineage without picking.
     * So it is never refused for supply or at a position ceiling: a seat or a clamp taken at a
     * ceiling saturates there, and the charge lands as the plain [admit]'s does. The equivalence
     * holds wherever that acquire would lease; where it would be refused, this still charges.
     *
     * Nor is it refused at a concurrency ceiling (see "Ceilings"), so with ceilings set the
     * equivalence covers only a [leaf] with no group at its ceiling on its lineage. It observes
     * [waiting] as that acquire does, every leaf beneath a group at its ceiling counted idle, so
     * beside a capped sibling the two still agree. For a capped [leaf] they part: the acquire picks
     * nothing and returns [AcquireOutcome.NothingEligible], while this charges the work and observes
     * [leaf] as waiting, since it is running.
     *
     * Returns [AcquireOutcome.NoLiveLineage] when [leaf] is not leasable or has no lineage of
     * active edges to the root, as [admit] does. The ledger is then unchanged, but the wake memory
     * has observed [waiting] at the root, as the refused acquire's pick would have when the engine can
     * fund the reservation; where it cannot, that acquire returns [AcquireOutcome.SupplyExhausted]
     * and observes nothing.
     *
     * @throws IllegalArgumentException when [reservation] is not in `1..`[MAX_RESERVATION], or
     *   [leaf] is not in [waiting].
     * @sample us.tractat.kuilt.heddle.sampleBuildFarm
     */
    public fun admit(
        leaf: GroupId,
        waiting: Set<GroupId>,
        startedAt: Long,
        reservation: Long = policy.defaultReservation,
    ): AdmitOutcome = atomically {
        requireReservation("reservation", reservation)
        require(leaf in waiting) { "leaf must be in waiting" }
        val base = ledger
        // Observed as the acquire's pick observes it, a capped leaf idle; the admitted leaf is
        // running work whatever its ceiling, so it is kept.
        val observed = belowCeilings(waiting, emptySet()).first + leaf
        val seen = demands(base, observed, setOf(leaf), reservation).waiting
        if (!base.canEndAt(leaf)) {
            // The refused acquire's pick observes at the root before it finds nothing to pick.
            allocator.settle(root, base, seen)
            return@atomically AcquireOutcome.NoLiveLineage(leaf)
        }
        val path = checkNotNull(base.lineageOf(leaf)) { "${leaf.value} can be reached but has no lineage" }

        val (funded, minted) = fund(base, reservation)
        var local = funded
        for (edge in path) {
            val parent = checkNotNull(local.record(edge)) { "lineage edge ${edge.value} has no record" }.parent
            local = allocator.settle(parent, local, seen)
            local = checkNotNull(seatThenDelegate(local, listOf(edge), reservation, seen)) {
                "the delegation of $reservation down ${edge.value}, admitted down a live lineage, was refused"
            }
        }
        ledger = local
        forcedMints += minted
        AcquireOutcome.Acquired(open(leaf, path, reservation, startedAt))
    }

    /**
     * Reserve [amount] more for [lease], down the path it was opened on. Not a new pick.
     *
     * Returns [LeaseOutcome.PathDraining] when an edge of that path is no longer ACTIVE,
     * [LeaseOutcome.SupplyExhausted] when the root holds less than [amount] and no capacity is
     * reconciled, and [LeaseOutcome.PositionCeiling] when an edge of the path is at its position
     * ceiling once [amount] is delegated. None of them changes anything.
     *
     * @throws IllegalArgumentException when [amount] is not in `1..`[MAX_RESERVATION].
     * @sample us.tractat.kuilt.heddle.sampleBuildFarm
     */
    public fun extend(lease: LeaseId, amount: Long): LeaseOutcome = atomically(keep = { it !is LeaseOutcome.PositionCeiling }) {
        requireReservation("extend amount", amount)
        val current = open[lease] ?: return@atomically missing(lease)
        val base = ledger
        base.firstNotActive(current.path)?.let { return@atomically LeaseOutcome.PathDraining(it) }
        val funded = supplied(base, amount) ?: return@atomically LeaseOutcome.SupplyExhausted
        val delegated = delegateOrFail(funded, current.path, amount)
        current.path.firstOrNull { delegated.isAtPositionCeiling(it) }?.let { edge ->
            return@atomically LeaseOutcome.PositionCeiling(checkNotNull(delegated.record(edge)) { "path edge ${edge.value} has no record" }.parent)
        }
        ledger = delegated
        open[lease] = current.copy(lease = current.lease.copy(reserved = checkedAdd(current.lease.reserved, amount)))
        LeaseOutcome.Done(charged = 0L, overrun = 0L)
    }

    /**
     * Close [lease] and charge its leaf [actualCost]. Beyond the reservation, the shortfall is
     * extended inline first (minted at the root if the root is short) and reported as the overrun;
     * below it, the unused remainder is handed back up the path. Never refused.
     *
     * When an edge of the lease's path is no longer ACTIVE, an overrun cannot be delegated across
     * it. The reservation is still charged at the leaf, and the overrun is charged at the deepest
     * group of the path that ACTIVE edges still reach from the root (see "A draining path" in the
     * class KDoc).
     *
     * @throws IllegalArgumentException when [actualCost] is negative.
     * @sample us.tractat.kuilt.heddle.sampleLocalLease
     */
    public fun complete(lease: LeaseId, actualCost: Long): LeaseOutcome = atomically {
        require(actualCost >= 0L) { "actualCost must be non-negative, was $actualCost" }
        val current = open[lease] ?: return@atomically missing(lease)
        val path = current.path
        val reserved = current.lease.reserved
        val overrun = maxOf(0L, actualCost - reserved)
        // Where an overrun is delegated: the whole path while it is ACTIVE, else the part above the
        // first edge that is not. Empty when the root's own child edge is the one closing.
        val reach = if (overrun == 0L) path else path.subList(0, ledger.firstNotActiveIndex(path) ?: path.size)
        val toLeaf = reach.size == path.size
        var local = ledger
        var minted = 0
        if (overrun > 0L && reach.isNotEmpty()) {
            val (funded, forced) = fund(local, overrun)
            minted = forced
            local = delegateOrFail(funded, reach, overrun)
        }
        // What the leaf holds for this lease, and what of it is charged there; the rest goes back up.
        val atLeafHeld = if (toLeaf) checkedAdd(reserved, overrun) else reserved
        val atLeafCharged = if (toLeaf) actualCost else reserved
        local = spendOrFail(local, path, atLeafCharged)
        if (!toLeaf && reach.isNotEmpty()) local = spendOrFail(local, reach, overrun)
        local = releaseUp(local, path, checkedSub(atLeafHeld, atLeafCharged))
        ledger = local
        forcedMints += minted
        open.remove(lease)
        LeaseOutcome.Done(charged = actualCost, overrun = overrun)
    }

    /**
     * Close [lease] without charging it: everything reserved goes back up the path.
     *
     * @sample us.tractat.kuilt.heddle.sampleBuildFarm
     */
    public fun cancel(lease: LeaseId): LeaseOutcome = atomically {
        val current = open[lease] ?: return@atomically missing(lease)
        ledger = releaseUp(ledger, current.path, current.lease.reserved)
        open.remove(lease)
        LeaseOutcome.Done(charged = 0L, overrun = 0L)
    }

    /**
     * Who would go next: the leaves `reservations.size` calls to [acquire] would pick, in order,
     * if nothing else happened in between. Changes nothing, not even the wake memory. Answered by a
     * view of this engine's state: see [LocalHeddleView.previewPicks].
     *
     * @throws IllegalArgumentException when a count is negative or a reservation is not in `1..`[MAX_RESERVATION].
     * @sample us.tractat.kuilt.heddle.sampleBuildFarm
     */
    public fun previewPicks(waiting: Map<GroupId, Int>, eligible: Set<GroupId>, reservations: List<Long>): List<GroupId> =
        view().previewPicks(waiting, eligible, reservations)

    /**
     * Who would go next, with each leaf's own next job: the leaves a caller's loop over [queues]
     * would pick, in order, acquiring at [reservation] and extending to the winner's head, if
     * nothing else happened in between. Changes nothing, not even the wake memory. Answered by a
     * view of this engine's state: see [LocalHeddleView.previewPicks].
     *
     * @throws IllegalArgumentException when [reservation] or any queued reservation, not only a
     *   head, is not in `1..`[MAX_RESERVATION].
     * @sample us.tractat.kuilt.heddle.samplePreviewQueues
     */
    public fun previewPicks(
        queues: Map<GroupId, List<Long>>,
        eligible: Set<GroupId>,
        reservation: Long = policy.defaultReservation,
    ): List<GroupId> = view().previewPicks(queues, eligible, reservation)

    /**
     * Why [leaf] is not, or is, what the next [acquire] on these same arguments picks. Changes
     * nothing, not even the wake memory. Answered by a view of this engine's state: see
     * [LocalHeddleView.explain].
     *
     * @throws IllegalArgumentException as [acquire] does: [reservation] out of range, or [eligible]
     *   not a subset of [waiting].
     * @throws ArithmeticException when a [WaitExplanation.BehindAtLevel] front does not fit an exact
     *   [Rational], which can happen well below the position ceiling (see "Failure surface").
     * @sample us.tractat.kuilt.heddle.sampleExplainWait
     */
    public fun explain(
        leaf: GroupId,
        waiting: Set<GroupId>,
        eligible: Set<GroupId>,
        reservation: Long = policy.defaultReservation,
    ): WaitExplanation = view().explain(leaf, waiting, eligible, reservation)

    /**
     * [leaf]'s fair share of [totalSlots] slots right now, as an exact rational, while [waiting]
     * stays as it is. Answered by a view of this engine's state: see [LocalHeddleView.entitlement].
     *
     * @throws IllegalArgumentException when [totalSlots] is negative.
     * @throws ArithmeticException when the exact share does not fit in `Long` numerators.
     * @sample us.tractat.kuilt.heddle.sampleExplainWait
     */
    public fun entitlement(leaf: GroupId, waiting: Set<GroupId>, totalSlots: Long): Rational =
        view().entitlement(leaf, waiting, totalSlots)

    /**
     * Tell the engine how many slots the caller runs at once, [totalSlots]. It sizes the supply
     * watermarks of [LocalPolicy.supply] and nothing else: it mints nothing and changes no lease,
     * and calling it again with the same value changes nothing. Until it is called with a positive
     * value no supply is minted ahead, and an [acquire] or [extend] the root cannot fund is refused.
     *
     * @throws IllegalArgumentException when [totalSlots] is negative, or the target it sizes,
     *   `totalSlots × defaultReservation × targetSlots`, is above `Long.MAX_VALUE / 4`.
     * @sample us.tractat.kuilt.heddle.sampleLocalLease
     */
    public fun reconcileCapacity(totalSlots: Long): Unit = atomically {
        capacity = Capacity.sized(totalSlots, policy)
    }

    /**
     * Compare the engine's open leases with [live], the leases whose work the caller still has
     * running, as of [now] in the cost unit. Reports both directions and changes nothing.
     *
     * Each orphan carries how long it ran, `max(0, now − startedAt)`. Which orphans to settle, and
     * at what cost, is the caller's policy; [LeaseReconciliation.Orphan.defaultSettlement] is the
     * documented default. Work that will resume belongs in [live], so it never becomes an orphan.
     *
     * @throws ArithmeticException when `now − startedAt` is positive and does not fit in a `Long`.
     * @sample us.tractat.kuilt.heddle.sampleReconcileLeases
     */
    public fun reconcileLeases(live: Set<LeaseId>, now: Long): LeaseReconciliation {
        val orphaned = open.values.asSequence()
            .map { it.lease }
            .filter { it.id !in live }
            .sortedBy { it.id }
            .map { LeaseReconciliation.Orphan(it, elapsed = if (now <= it.startedAt) 0L else checkedSub(now, it.startedAt)) }
            .toList()
        val unknown = live.filter { it !in open }.sorted()
        return LeaseReconciliation(orphaned, unknown)
    }

    /**
     * Move the tree to [new]: add what it adds, remove what it removes, and give every reweighted
     * group a new edge at its new weight, as [Topology.Companion.diff] lists the steps. Open leases
     * survive it, and each is still charged once, at [complete].
     *
     * **One unit, swapped in once.** The diff is taken here, on the ledger it is applied to, and the
     * whole list runs on a working copy that replaces the ledger at the end, so nothing is ever
     * leased against a state part-way through a reload. A child whose change cannot land yet is
     * reported in [ApplyResult.refusals], and every other child's change still lands in the same
     * swap. Apply the same topology again later, and only what is left is listed.
     *
     * **A reweight moves its open leases with it.** A changed weight is a new edge, and an open
     * lease whose path crosses the old one moves onto the new one in the same unit: its reservation
     * is handed back up the old path, the old edge retires with nothing outstanding, the new one
     * activates (taking over the old edge's spend with a [TopologyStep.Reconcile] when the group is
     * internal), and the reservation is delegated down the new path, which the lease charges from
     * then on. A lease under a group and its child, both reweighted, crosses two old edges and
     * moves once. It keeps its id, leaf and reservation, so the caller sees nothing change.
     * **The reweighted group's fairness history resets:** its new edge is seated at its parent's
     * front, like a newcomer's, so it is neither owed for its past nor charged for it. A child added
     * back before its removal drained is a new edge for the same group, and its draining leases
     * move onto it the same way.
     *
     * **A removal drains.** A removed child is closed at once, so nothing new is leased there, and
     * retires once its open leases complete; until then it is [StepRefusal.Draining], naming the
     * leases it waits for. A removal under a reweighted group wins: that group's reweight waits
     * too, as [StepRefusal.Draining], and changes nothing until every lease beneath it can move. A
     * lease whose work is gone is found by [reconcileLeases] and settled with [complete], so an
     * orphan holds a reload up no longer than that.
     *
     * A rename or a move is a delete plus an add: the new group starts with no history, and the old
     * one drains under its old id. While a top-level group drains, an overrun charged by one of its
     * leases is reported in [LeaseOutcome.Done] but recorded on no edge (see "A draining path").
     *
     * @param generation a counter, `>= 0`, for the edges this reload adds; raised past every
     *   generation the ledger holds, so a reused counter never reuses an id.
     * @throws IllegalArgumentException if [new]'s root is not [root], or [generation] is negative.
     * @sample us.tractat.kuilt.heddle.sampleLocalReload
     */
    public fun apply(new: Topology, generation: Long): ApplyResult = atomically {
        require(new.groupId() == root) {
            "the topology's root ${new.groupId().value} is not this engine's ${root.value}: a change of root is not a reload"
        }
        val steps = Topology.diff(new, ledger, generation)
        // Before the run, so the swap forgets what [new] does not want, not what the old topology did not.
        leasable = Leasable.ByTopology(new)
        run(steps)
    }

    /**
     * Attach the runtime child [name] to the group at [group], read from the root down, at the
     * weight the topology's marker on that group gives ([Topology.attachRuntimeChild]). Applied as
     * one unit, like [apply]. Attaching a child that is already attached does nothing; attaching one
     * whose detach is still draining gives it a new edge and moves its draining leases onto it.
     *
     * @throws IllegalArgumentException if [group] does not name a group the topology marks for
     *   runtime children, [name] is empty, or [generation] is negative.
     * @sample us.tractat.kuilt.heddle.sampleBuildFarm
     */
    public fun attachRuntimeChild(group: List<String>, name: String, generation: Long): ApplyResult =
        atomically { run(topology().attachRuntimeChild(group, name, ledger, generation)) }

    /**
     * Detach the runtime child [name] from the group at [group] ([Topology.detachRuntimeChild]):
     * closed at once, and retired once its open leases complete, until when it is
     * [StepRefusal.Draining].
     *
     * @throws IllegalArgumentException if [group] does not name a group the topology marks for
     *   runtime children, or [name] is empty.
     * @sample us.tractat.kuilt.heddle.sampleBuildFarm
     */
    public fun detachRuntimeChild(group: List<String>, name: String): ApplyResult =
        atomically { run(topology().detachRuntimeChild(group, name, ledger)) }

    private fun topology(): Topology =
        checkNotNull((leasable as? Leasable.ByTopology)?.topology) { "an engine over a hand-built ledger has no topology" }

    /**
     * Apply [steps] as one unit. Each [attempt] starts from the committed ledger. One that finds a
     * child whose change cannot land names it, and the unit is attempted again without any of that
     * child's steps, so the child is left exactly as it was. Each round leaves out at least one more
     * child, so this ends, and the one swap is the attempt in which every child left in landed.
     * That swap also forgets what it can ([forgetQuiesced]).
     */
    private fun run(steps: List<TopologyStep>): ApplyResult {
        val left = LinkedHashMap<GroupId, StepRefusal>()
        while (true) {
            when (val trial = attempt(steps, left.keys)) {
                is Trial.LeaveOut -> {
                    val fresh = trial.refusals.filter { it.child !in left }
                    check(fresh.isNotEmpty()) { "an attempt left out only children already left out: ${trial.refusals}" }
                    for (refusal in fresh) left.getOrPut(refusal.child) { refusal }
                }
                is Trial.Landed -> {
                    ledger = trial.ledger
                    for ((id, path) in trial.moved) open[id] = open.getValue(id).copy(path = path)
                    forgetQuiesced()
                    return ApplyResult(trial.applied, (trial.refusals + left.values).sortedBy { it.child })
                }
            }
        }
    }

    /**
     * Forget every edge the ledger can, in the swap [run] lands (design §5.5), so an engine whose
     * children come and go stays the size of what it holds now rather than of everything it ever
     * held. Two kinds of edge go:
     * - every RETIRED edge [EntitlementLedger.forgetRefusal] passes: a leaf's, with its books
     *   closed. Every one, not only the ones this swap retired, so a ledger restored from a
     *   snapshot written before the engine forgot compacts in its first swap;
     * - every PREPARED edge the topology in force does not want ([Topology.unwantedPrepared]),
     *   which carries nothing, so only the generation floor moves.
     *
     * No edge on an open lease's path is forgotten, and nothing here filters for it, because two
     * guards already hold it: [attempt] has just checked that every open lease's path is its leaf's
     * live lineage, which crosses no RETIRED or PREPARED edge, and the ledger refuses to forget an
     * edge a lease still charges ([ForgetRefusal.OUTSTANDING]). The allocator's memory of each
     * forgotten edge goes in the same swap, since its own pruning needs the edge's record.
     *
     * Reads every edge's lifecycle once, and makes two passes for the replicas the counters name,
     * one to choose the candidates ([EntitlementLedger.forgettable]) and one to forget them.
     * What forgetting changes and what it does not is on [EntitlementLedger.forget].
     */
    private fun forgetQuiesced() {
        if (!forgetsForTest) return
        val quiesced = ledger.quiescedEdges()
        if (quiesced.isEmpty()) return
        val (prepared, retired) = quiesced.partition { ledger.lifecycle(it) == Lifecycle.PREPARED }
        val unwanted = (leasable as? Leasable.ByTopology)?.topology?.unwantedPrepared(prepared, ledger).orEmpty()
        val doomed = ledger.forgettable(retired + unwanted)
        if (doomed.isEmpty()) return
        ledger = checkNotNull(ledger.forget(doomed)) { "the ledger refused to forget ${doomed.map { it.value }}, each of which it passed" }
        allocator.forget(doomed.toHashSet())
    }

    /**
     * One attempt at [steps] on a working copy of the ledger, without the steps of the children in
     * [leftOut]. It lands when every child left in either lands or is a removal waiting on a drain;
     * otherwise it names the children to leave out.
     *
     * A **replacement** is a child whose list retires an edge and activates another. The open leases
     * across its old edges are released up their paths first, which is every unit this engine holds
     * there, so the old edges retire with nothing outstanding. Once every step has run, reconciles
     * included, each moved lease is delegated down its leaf's new lineage through [seatThenDelegate].
     * A replacement is all or nothing: if any part fails it is left out, as [StepRefusal.Refused]
     * unless a lease that cannot move is what holds it ([StepRefusal.Draining]). A retire refused
     * after those releases is refused for units that are not this engine's, which waiting will not
     * cure. A child any of whose steps the ledger refuses outright is left out too. Only a removal
     * waiting on a drain keeps its steps up to the refused retire, since its close is what stops new
     * work going there.
     */
    private fun attempt(steps: List<TopologyStep>, leftOut: Set<GroupId>): Trial {
        val base = ledger
        val replaced = replacedEdges(steps, leftOut)
        // Edges this list closes or retires for a child that gets no new edge: a lease across one
        // drains where it is, and cannot be moved.
        val removed = steps.filter { (it is TopologyStep.Close || it is TopologyStep.Retire) && it.child !in leftOut && it.child !in replaced.values }
            .mapTo(HashSet()) { it.edgeOf() }

        val moving = ArrayList<OpenLease>()
        val stuck = LinkedHashMap<GroupId, MutableList<Lease>>()
        for (lease in open.values.sortedBy { it.lease.id }) {
            val crossed = lease.path.mapNotNull { replaced[it] }.distinct()
            if (crossed.isEmpty()) continue
            val drainsInPlace = lease.path.any { it !in replaced && (it in removed || base.lifecycle(it) != Lifecycle.ACTIVE) }
            if (drainsInPlace) crossed.forEach { stuck.getOrPut(it) { ArrayList() } += lease.lease } else moving += lease
        }
        if (stuck.isNotEmpty()) {
            return Trial.LeaveOut(
                stuck.map { (child, leases) ->
                    val edge = replaced.entries.first { (e, c) -> c == child && leases.any { e in open.getValue(it.id).path } }.key
                    StepRefusal.Draining(child, edge, base.outstanding(edge), leases)
                },
            )
        }

        var local = base
        for (lease in moving) local = releaseUp(local, lease.path, lease.lease.reserved)

        val movingIds = moving.mapTo(HashSet()) { it.lease.id }
        fun refusal(step: TopologyStep, reason: String): StepRefusal {
            val outstanding = if (step is TopologyStep.Retire) local.outstanding(step.edge) else 0L
            if (outstanding == 0L) return StepRefusal.Refused(step.child, step, reason)
            val blocking = open.values.filter { it.lease.id !in movingIds && step.edgeOf() in it.path }.map { it.lease }.sortedBy { it.id }
            return StepRefusal.Draining(step.child, step.edgeOf(), outstanding, blocking)
        }
        val applied = ArrayList<TopologyStep>()
        val draining = ArrayList<StepRefusal>()
        val stopped = HashSet<GroupId>()
        val leaveOut = ArrayList<StepRefusal>()
        for (step in steps) {
            val child = step.child
            if (child in leftOut || child in stopped) continue
            // Retires run bottom-up, so a group's retire waits on every group below it whose change did
            // not land: one stopped in this attempt, or one left out of it.
            val below = (stopped.asSequence() + leftOut.asSequence()).filter { it.value.startsWith(child.value + "/") }.minOrNull()
            val stepped = if (step is TopologyStep.Retire && below != null) null else local.applyStep(step)
            // An activation whose weight would put its parent's weight products out of Long: the
            // ledger would take it, and every pick at that parent would then throw (#3313).
            val overBudget = stepped != null && step is TopologyStep.Activate && !stepped.siblingWeightsFit(step.edge)
            val next = if (overBudget) null else stepped
            if (next != null) {
                local = next
                applied += step
                continue
            }
            val reason = when {
                below != null -> "${below.value}, below it, did not retire"
                overBudget -> "its weight would put its siblings' weight arithmetic out of Long"
                else -> "the ledger refused it"
            }
            if (child in replaced.values) {
                // Every unit this engine held across the old edge was released above, so what is
                // still outstanding there is another writer's, or an engine bug: not a drain.
                val outstanding = (step as? TopologyStep.Retire)?.let { local.outstanding(it.edge) } ?: 0L
                leaveOut += StepRefusal.Refused(child, step, if (outstanding > 0L) "outstanding units this engine does not hold: $outstanding" else reason)
            } else {
                val why = refusal(step, reason)
                if (why is StepRefusal.Refused) leaveOut += why else draining += why
            }
            // The child's first refusal is its answer: its later steps do not run.
            stopped += child
        }
        if (leaveOut.isNotEmpty()) return Trial.LeaveOut(leaveOut)

        val moved = LinkedHashMap<LeaseId, List<AttachmentId>>()
        for (lease in moving) {
            val path = local.lineageOf(lease.lease.leaf)?.takeIf { p -> p.isNotEmpty() && p.all { local.lifecycle(it) == Lifecycle.ACTIVE } }
            val delegated = path?.let { seatThenDelegate(local, it, lease.lease.reserved, observedDemand(lease.lease.reserved)) }
            if (path == null || delegated == null) {
                return Trial.LeaveOut(
                    lease.path.mapNotNull { replaced[it] }.distinct().map { child ->
                        val activate = steps.first { it is TopologyStep.Activate && it.child == child }
                        StepRefusal.Refused(child, activate, "lease ${lease.lease.id} cannot be delegated down ${lease.lease.leaf.value}'s new path")
                    },
                )
            }
            local = delegated
            moved[lease.lease.id] = path
        }

        // Every open lease's path is its leaf's one live lineage: no lease crosses a retired edge, and
        // the reload left no leaf with two live inbound edges, a divergent record or a cycle, which
        // `delegateOrFail` and `releaseUp` throw on. An engine bug if not; the ledger is not swapped.
        for (lease in open.values) {
            val path = moved[lease.lease.id] ?: lease.path
            check(local.lineageOf(lease.lease.leaf) == path) {
                "a reload left lease ${lease.lease.id} on ${path.map { it.value }}, not ${lease.lease.leaf.value}'s lineage " +
                    "${local.lineageOf(lease.lease.leaf)?.map { it.value }}"
            }
        }
        return Trial.Landed(local, applied, draining, moved)
    }

    /**
     * Every edge [steps] retires for a replacement, mapped to its child: a child, not in [leftOut],
     * whose list both retires an edge and activates another.
     */
    private fun replacedEdges(steps: List<TopologyStep>, leftOut: Set<GroupId>): Map<AttachmentId, GroupId> {
        val activated = steps.filterIsInstance<TopologyStep.Activate>().mapTo(HashSet()) { it.child }
        return steps.filterIsInstance<TopologyStep.Retire>()
            .filter { it.child in activated && it.child !in leftOut }
            .associate { it.edge to it.child }
    }

    /**
     * [steps] applied as [apply] applies a diff, for tests: the seam for a list no diff writes, such
     * as one with a step the ledger refuses outright.
     */
    internal fun applyStepsForTest(steps: List<TopologyStep>): ApplyResult = atomically { run(steps) }

    /**
     * Whether [run]'s swap forgets ([forgetQuiesced]); `true` unless a test turns it off, to compare
     * an engine that forgets with one that keeps everything. Not engine state.
     */
    internal var forgetsForTest: Boolean = true

    /**
     * Called after each level's pick, once the wake memory has moved and before the delegation: the
     * seam a test throws from to fail an [acquire] part-way. Not engine state.
     */
    internal var afterPickForTest: (GroupId) -> Unit = {}

    /**
     * Whether this engine rebases a busy parent before its positions reach their ceiling (#3329).
     * Off only in a test's twin, to show a run reaches the ceiling without it and picks the same
     * until then. Not engine state: a [restore] starts with it on.
     */
    internal var rebaseForTest: Boolean = true

    /** How many rebases moved this engine's positions, counting only calls it kept: the witness a test reads. */
    internal val rebasesForTest: Int get() = allocator.rebasesForTest

    /** The path [lease] charges, for tests: what it was opened on, or what a reweight moved it onto. */
    internal fun capturedPath(lease: LeaseId): List<AttachmentId>? = open[lease]?.path

    /**
     * Everything a restart needs, as one value to encode and store: see [LocalHeddleSnapshot]. Like
     * [snapshot], it is taken under the caller's scheduling lock, since it reads the engine's own
     * maps; the value it returns is immutable, so encoding and writing it can happen off the lock.
     * Costs a sort of the open leases and of the wake memory.
     *
     * @sample us.tractat.kuilt.heddle.sampleLocalPersistence
     */
    public fun persist(): LocalHeddleSnapshot = LocalHeddleSnapshot(
        epoch = epoch,
        ledger = ledger,
        allocatorMemory = allocator.memory,
        totalSlots = capacity.slots,
        open = open.values.sortedBy { it.lease.id }.map { PersistedLease(it.lease, it.path) },
        lastApplied = topology(),
    )

    /**
     * Replace the ledger with [transform] of it: the test seam for a topology change while leases
     * are open that [apply] cannot express, such as an edge left closing by hand.
     */
    internal fun reshapeForTest(transform: (EntitlementLedger) -> EntitlementLedger) {
        ledger = transform(ledger)
    }

    /** Mint [amount] at the root, as one act: the test seam for supply laid down ahead of any top-up. */
    internal fun mintSupply(amount: Long) {
        ledger = mintAt(ledger, amount)
    }

    private fun open(leaf: GroupId, path: List<AttachmentId>, reservation: Long, startedAt: Long): Lease {
        val lease = Lease(LeaseId(epoch, nextSeq++), leaf, reservation, startedAt)
        open[lease.id] = OpenLease(lease, path.toList())
        return lease
    }

    private fun missing(lease: LeaseId): LeaseOutcome =
        if (lease.epoch == epoch) LeaseOutcome.Unknown else LeaseOutcome.Stale(epoch)

    /**
     * Seat, then delegate: the one place this engine calls [EntitlementLedger.delegate]. Each edge
     * of [path] that carries no gauge is seated first, with its unseated siblings, at its parent's
     * front over the seated ones as [seatDemand] sees them, so no delegation writes an edge's first
     * gauge at its own origin (#1752). `null` when an edge cannot be seated or cannot take the
     * delegation; [start] is not changed either way.
     *
     * It never rebases (#3329): only a pick does. So a parent that is only ever charged through
     * [admit], [extend] or an overrun at [complete], and never picked at, is not rebased, and its
     * positions climb toward their ceiling as they did before the rebase existed.
     */
    private fun seatThenDelegate(
        start: EntitlementLedger,
        path: List<AttachmentId>,
        amount: Long,
        seatDemand: (AttachmentId) -> Demand,
    ): EntitlementLedger? {
        var local = start
        for (edge in path) {
            if (local.gauge(edge) == null) {
                val parent = local.record(edge)?.parent ?: return null
                local = allocator.seatUnseated(parent, local, seatDemand)
                if (local.gauge(edge) == null) return null
            }
            val patch = local.delegate(SELF, edge, amount) ?: return null
            local = local.piece(patch.delta)
        }
        return local
    }

    /** [seatThenDelegate] down a path this engine captured itself, where a refusal is a bug. */
    private fun delegateOrFail(start: EntitlementLedger, path: List<AttachmentId>, amount: Long): EntitlementLedger =
        checkNotNull(seatThenDelegate(start, path, amount, observedDemand(amount))) {
            "the delegation of $amount down a captured path ${path.map { it.value }} was refused"
        }

    /** Charge [amount] at the end of [path], a prefix of a path this engine captured; a refusal is a bug. */
    private fun spendOrFail(start: EntitlementLedger, path: List<AttachmentId>, amount: Long): EntitlementLedger {
        if (amount == 0L) return start
        val spend = checkNotNull(start.spendCaptured(SELF, path, amount)) {
            "the spend of $amount on the captured path ${path.map { it.value }} was refused"
        }
        return start.piece(spend.delta)
    }

    /** Hand [amount] back up [path], leaf first. */
    private fun releaseUp(start: EntitlementLedger, path: List<AttachmentId>, amount: Long): EntitlementLedger {
        if (amount == 0L) return start
        var local = start
        for (edge in path.asReversed()) {
            val patch = checkNotNull(local.release(SELF, edge, amount)) { "the release of $amount up ${edge.value} was refused" }
            local = local.piece(patch.delta)
        }
        return local
    }

    /**
     * [start] with the root able to fund [need] for an [acquire] or [extend], topped up when needed;
     * `null` when it cannot. When the operation would leave the root below the low watermark, this
     * mints enough that the target is left after it, so the operation is funded whatever its size.
     * With no capacity reconciled both levels are zero and nothing is minted ahead: the root funds
     * [need] from what it holds, or the operation is refused.
     */
    private fun supplied(start: EntitlementLedger, need: Long): EntitlementLedger? {
        val held = start.holdings(root, SELF)
        return when {
            capacity.slots > 0L && held - need < capacity.low -> mintAt(start, checkedSub(checkedAdd(capacity.target, need), held))
            held >= need -> start
            else -> null
        }
    }

    /** [start] with the root holding at least [amount], minting the shortfall; and how many mints that took. */
    private fun fund(start: EntitlementLedger, amount: Long): Pair<EntitlementLedger, Int> {
        val shortfall = amount - start.holdings(root, SELF)
        if (shortfall <= 0L) return start to 0
        return mintAt(start, shortfall) to 1
    }

    /**
     * A demand view for a delegation that is not a pick: each edge as the allocator last observed
     * it. Only seating reads it, to find the front a newborn joins, and it observes nothing.
     */
    private fun observedDemand(amount: Long): (AttachmentId) -> Demand {
        val wants = Demand(targetOutstanding = UNBOUNDED_TARGET, maximumUsefulGrant = amount)
        return { edge -> if (allocator.lastObservedDemanding(edge) == true) wants else Demand.NONE }
    }

    /** This epoch's one mint id, `local#<epoch>`. */
    private val epochMintId: MintId get() = MintId("local#$epoch")

    /**
     * [start] with [amount] more minted at the root. The epoch keeps **one** mint record, whose
     * amount is everything the epoch has minted: mint records join by max, so the record carrying
     * the larger total supersedes the older one, and the count of records stays one per epoch. A
     * record per mint act would grow without bound, and [EntitlementLedger.holdings] at the root and
     * every root delegation's witness fold over all of them.
     *
     * The running total is read off [start], never kept beside it. An operation that mints on a
     * working ledger and is then refused discards that ledger, mint and all; a counter kept on the
     * engine would have advanced anyway, and the next mint would credit the root with supply that
     * was never minted. An engine started over a ledger that already holds its epoch's record
     * carries on from it for the same reason.
     */
    private fun mintAt(start: EntitlementLedger, amount: Long): EntitlementLedger {
        val total = checkedAdd(start.mintRecord(epochMintId)?.amount ?: 0L, amount)
        return start.piece(start.mint(epochMintId, root, SELF, total).delta)
    }

    /** An open lease and the path it was delegated down, which every later charge follows. */
    private data class OpenLease(val lease: Lease, val path: List<AttachmentId>)

    /** The two demand views one pick reads, [waiting] and [eligible]. */
    internal class Demands(val waiting: (AttachmentId) -> Demand, val eligible: (AttachmentId) -> Demand)

    /**
     * The demand views a pick of [reservation] reads on [base], each lifted by the very predicate
     * the descent stops on ([reachable]). A subtree with no leasable leaf reached through ACTIVE
     * edges is never wanted, so no pick can lead into it.
     */
    internal fun demands(base: EntitlementLedger, waiting: Set<GroupId>, eligible: Set<GroupId>, reservation: Long): Demands {
        val wants = Demand(targetOutstanding = UNBOUNDED_TARGET, maximumUsefulGrant = reservation)
        val waitingGroups = base.reachable(waiting)
        // eligible ⊆ waiting, so equal sizes mean equal sets, and the lift is shared.
        val eligibleGroups = if (eligible.size == waiting.size) waitingGroups else base.reachable(eligible)
        return Demands(demandOver(base, waitingGroups, wants), demandOver(base, eligibleGroups, wants))
    }

    /**
     * Whether a lease may end at [group]: the **one leasable predicate**. It is a leaf, it is not
     * the root, and the topology declares it a lane or a runtime child of a marked group
     * ([leasable]). The descent stops only here, and every name a pick or an admission accepts is
     * tested by it, through [reachable]. A [LocalHeddleView] reads the same top-level predicate.
     */
    private fun EntitlementLedger.isLeasable(group: GroupId): Boolean = isLeasable(group, root, leasable)

    /**
     * Every group of [groups] a lease can end at and a delegation from the root can reach — leasable,
     * with every edge of its lineage ACTIVE — together with every group on those lineages: the lift
     * a demand view, an admission and an explanation all read.
     */
    private fun EntitlementLedger.reachable(groups: Collection<GroupId>): Set<GroupId> = reachable(groups, root, leasable)

    /** Whether a lease can end at [group] and a delegation reach it: [isLeasable], through ACTIVE edges only. */
    private fun EntitlementLedger.canEndAt(group: GroupId): Boolean = canEndAt(group, root, leasable)

    /** What one [attempt] at a reload came to. */
    private sealed interface Trial {
        /** Leave these children out and attempt again; the working copy is discarded. */
        class LeaveOut(val refusals: List<StepRefusal>) : Trial

        /** Every child left in landed or is draining: swap [ledger] in and move each lease in [moved] onto its new path. */
        class Landed(
            val ledger: EntitlementLedger,
            val applied: List<TopologyStep>,
            val refusals: List<StepRefusal>,
            val moved: Map<LeaseId, List<AttachmentId>>,
        ) : Trial
    }

    /** What [descend] did. */
    internal sealed interface Descent {
        /** It ended at [leaf], with [ledger] the delegated result, [levels] each pick on the way, and [rebases] to adopt with [ledger]. */
        class Reached(val ledger: EntitlementLedger, val leaf: GroupId, val levels: List<Level>, val rebases: List<Rebase>) : Descent

        /** No pick could be made at [at]. */
        class Refused(val at: GroupId) : Descent

        /** The pick at [at] would order a position at its ceiling, or its delegation would reach one. */
        class Ceiling(val at: GroupId) : Descent
    }

    /** One level of a [Descent]: the pick at [parent] chose [edge], on [seated], the ledger with [parent]'s children seated. */
    internal class Level(val parent: GroupId, val seated: EntitlementLedger, val edge: AttachmentId)

    public companion object {
        /** The one writer. Internal: a consumer never needs to name it. */
        internal val SELF: ReplicaId = ReplicaId("local")

        /**
         * The largest single reservation or extension: 2^44, about 1.8 × 10^13 cost units. A lease
         * that runs longer is [extend]ed, or charged its overrun at [complete], which this does not
         * bound. It does not bound the service a parent hands down over its lifetime either; that
         * ceiling depends on the children's weights (see "Failure surface" in the class KDoc).
         *
         * It exists to keep [UNBOUNDED_TARGET] unbounded in practice. Every pick asks for
         * `UNBOUNDED_TARGET − outstanding` on an edge, so the edge goes on reading as wanting the
         * whole reservation until its outstanding reaches 2^61 − 2^44: over 130,000 maximum-size
         * reservations open under one edge at once.
         */
        public const val MAX_RESERVATION: Long = 1L shl 44

        /**
         * The target every wanted edge advertises, `Long.MAX_VALUE / 4` (just under 2^61): far above
         * any outstanding a run reaches (see [MAX_RESERVATION]), and far enough below `Long.MAX_VALUE`
         * that [HeddlePolicy]'s `targetOutstanding − outstanding`, which is not overflow-checked, cannot
         * wrap for any outstanding above `−3 × 2^61`. A healthy ledger's outstanding is never negative.
         */
        private const val UNBOUNDED_TARGET: Long = Long.MAX_VALUE / 4

        internal fun requireReservation(name: String, amount: Long) =
            require(amount in 1L..MAX_RESERVATION) { "$name must be in 1..$MAX_RESERVATION, was $amount" }

        /** The index of the first edge of [path] that is not ACTIVE, or `null` when every edge is. */
        private fun EntitlementLedger.firstNotActiveIndex(path: List<AttachmentId>): Int? =
            path.indexOfFirst { lifecycle(it) != Lifecycle.ACTIVE }.takeIf { it >= 0 }

        /** Whether the active children beside [edge], itself included, fit [weightsFitTheBudget]. */
        private fun EntitlementLedger.siblingWeightsFit(edge: AttachmentId): Boolean {
            val parent = record(edge)?.parent ?: return true
            return weightsFitTheBudget(activeChildren(parent).mapNotNull { record(it.attachment)?.weight })
        }

        /** What [edge] still carries: issued, less returned and spent. `0` for an edge this ledger does not know. */
        private fun EntitlementLedger.outstanding(edge: AttachmentId): Long = edge(edge)?.outstanding ?: 0L

        /** The edge a step names; the [TopologyStep.Reconcile] of a group names none, and is never asked. */
        private fun TopologyStep.edgeOf(): AttachmentId = when (this) {
            is TopologyStep.Prepare -> record.id
            is TopologyStep.Activate -> edge
            is TopologyStep.Close -> edge
            is TopologyStep.Retire -> edge
            is TopologyStep.Reconcile -> error("a reconcile names no edge")
        }

        /** This ledger with [step] applied, or `null` when the ledger refuses it. A reconcile with nothing to move applies. */
        private fun EntitlementLedger.applyStep(step: TopologyStep): EntitlementLedger? = when (step) {
            is TopologyStep.Prepare -> prepare(step.record)?.let { piece(it.delta) }
            is TopologyStep.Activate -> activate(step.edge)?.let { piece(it.delta) }
            is TopologyStep.Close -> close(step.edge)?.let { piece(it.delta) }
            is TopologyStep.Retire -> retire(step.edge)?.let { piece(it.delta) }
            is TopologyStep.Reconcile -> when (val move = reconcileLocally(step.child)) {
                is Relocation.Moved -> piece(move.patch)
                Relocation.Nothing -> this
                is Relocation.Refused -> null
            }
        }

        /** The first edge of [path] that is not ACTIVE, or `null` when every edge is. */
        private fun EntitlementLedger.firstNotActive(path: List<AttachmentId>): AttachmentId? =
            firstNotActiveIndex(path)?.let(path::get)

        /**
         * An engine running [topology], issuing lease ids under [epoch], with no supply yet: it mints
         * its own once [reconcileCapacity] is called. Its [root] is the topology's root.
         *
         * It boots the way every later reload arrives: [apply] of [topology] to an empty ledger, at
         * generation 0. So the ids it starts with are exactly [Topology.lower]'s, and there is no
         * second, unfloored way to build a tree.
         *
         * @sample us.tractat.kuilt.heddle.sampleLocalLease
         */
        public fun fromTopology(topology: Topology, policy: LocalPolicy, epoch: Long): LocalHeddle {
            val engine = LocalHeddle(
                topology.groupId(), policy, epoch, EntitlementLedger.ZERO, FairShareAllocator(SELF, policyConfig(policy)),
                Leasable.ByTopology(topology),
            )
            val booted = engine.apply(topology, generation = 0L)
            check(booted.refusals.isEmpty()) { "booting from an empty ledger refused ${booted.refusals}" }
            return engine
        }

        /**
         * A throwaway engine over [ledger] that picks, and tops up, as one with [allocator]'s wake
         * memory and [capacity] would next, with [openElsewhere] counted against every ceiling
         * beside the leases it opens itself. [allocator] becomes its own, so pass a fork. What a
         * [LocalHeddleView] plays its questions forward on.
         */
        internal fun scratch(
            root: GroupId,
            policy: LocalPolicy,
            epoch: Long,
            ledger: EntitlementLedger,
            allocator: FairShareAllocator,
            leasable: Leasable,
            capacity: Capacity,
            openElsewhere: Map<GroupId, Int>,
        ): LocalHeddle = LocalHeddle(root, policy, epoch, ledger, allocator, leasable, openElsewhere).also { it.capacity = capacity }

        /**
         * An engine over [ledger] as it stands, with a fresh allocator, leasing what [leasable]
         * declares. For tests over a hand-built ledger; [fromTopology] is the public way to boot.
         */
        internal fun over(ledger: EntitlementLedger, root: GroupId, policy: LocalPolicy, epoch: Long, leasable: Leasable): LocalHeddle =
            LocalHeddle(root, policy, epoch, ledger, FairShareAllocator(SELF, policyConfig(policy)), leasable)

        /**
         * An engine that carries on from [snapshot], issuing new lease ids under [epoch].
         *
         * Every lease open in [snapshot] is open again under its own id, from whatever epoch issued
         * it, and charges down the path it was delegated on. The wake memory and the capacity come
         * back too, so the next picks and top-ups are the ones the persisted engine would have made,
         * and so does the topology it last applied, so it leases exactly the groups that one did.
         * [policy] is taken afresh: pass the one the persisted engine ran with for that to hold.
         *
         * [epoch] must be above [snapshot]'s. Treat it as a boot counter you persist and only ever
         * raise: lease ids are numbered from zero in each epoch, so restoring under an epoch an
         * earlier run already used could hand out an id that run handed out too. An older lease id
         * that is no longer open is then answered [LeaseOutcome.Stale], never [LeaseOutcome.Unknown].
         *
         * @throws IllegalArgumentException when [epoch] is not above [LocalHeddleSnapshot.epoch], or
         *   when [policy] cannot size [LocalHeddleSnapshot.totalSlots] (see [reconcileCapacity]).
         * @sample us.tractat.kuilt.heddle.sampleLocalPersistence
         */
        public fun restore(snapshot: LocalHeddleSnapshot, policy: LocalPolicy, epoch: Long): LocalHeddle {
            require(epoch > snapshot.epoch) {
                "a restore must run under an epoch above the snapshot's ${snapshot.epoch}, was $epoch"
            }
            val allocator = FairShareAllocator(SELF, policyConfig(policy), snapshot.allocatorMemory)
            val leasable = Leasable.ByTopology(snapshot.lastApplied)
            return LocalHeddle(snapshot.root, policy, epoch, snapshot.ledger, allocator, leasable).also { engine ->
                for (persisted in snapshot.open) engine.open[persisted.lease.id] = OpenLease(persisted.lease, persisted.path)
                engine.reconcileCapacity(snapshot.totalSlots)
            }
        }

        /**
         * Read a snapshot from [bytes], encoded in [format] from [LocalHeddleSnapshot]'s serializer.
         * Never throws for what the bytes hold: see [RestoreResult].
         *
         * @sample us.tractat.kuilt.heddle.sampleLocalPersistence
         */
        public fun decode(bytes: ByteArray, format: BinaryFormat): RestoreResult =
            decoding { format.decodeFromByteArray(LocalHeddleSnapshot.serializer(), bytes) }

        /**
         * Read a snapshot from [text], encoded in [format] from [LocalHeddleSnapshot]'s serializer.
         * Never throws for what the text holds: see [RestoreResult].
         *
         * @sample us.tractat.kuilt.heddle.sampleLocalPersistence
         */
        public fun decode(text: String, format: StringFormat): RestoreResult =
            decoding { format.decodeFromString(LocalHeddleSnapshot.serializer(), text) }

        /**
         * [read] as a [RestoreResult]. A format's decode errors are [kotlinx.serialization.SerializationException]s,
         * and a snapshot's own consistency check throws [IllegalArgumentException], which the former
         * extends, so one arm covers malformed bytes and a well-formed but inconsistent snapshot alike.
         * Nothing here suspends, so no cancellation passes through it.
         */
        private inline fun decoding(read: () -> LocalHeddleSnapshot): RestoreResult =
            try {
                RestoreResult.Restored(read())
            } catch (e: UnsupportedSnapshotFormat) {
                RestoreResult.UnsupportedVersion(e.found, LocalHeddleSnapshot.SUPPORTED_FORMAT_VERSIONS)
            } catch (e: IllegalArgumentException) {
                RestoreResult.Corrupt(e.message ?: e.toString())
            }

        /**
         * The allocator's policy: an unbounded quantum, because each pick's reservation rides in its
         * demand's `maximumUsefulGrant`, and no outstanding cap, because the caller owns concurrency.
         */
        internal fun policyConfig(policy: LocalPolicy): PolicyConfig =
            PolicyConfig(quantum = Long.MAX_VALUE, perChildOutstandingCap = Long.MAX_VALUE, sleeperCredit = policy.sleeperCredit)

        /** [wants] for an edge whose child is in [groups], [Demand.NONE] for every other. */
        private fun demandOver(ledger: EntitlementLedger, groups: Set<GroupId>, wants: Demand): (AttachmentId) -> Demand =
            { edge -> if (ledger.record(edge)?.child in groups) wants else Demand.NONE }
    }
}
