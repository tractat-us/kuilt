package us.tractat.kuilt.heddle

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
 *   §10.6). `0`, the default, keeps none. Non-negative.
 * @property supply how far ahead of its leases the engine mints supply.
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
 * What a [LocalHeddle] needs to resume, as one immutable value: once taken, safe to read, encode or
 * hand to another thread without the caller's scheduling lock.
 *
 * The counter an engine numbers its leases from is not here, and need not be: a restarted engine
 * takes a new [epoch] and lease ids are scoped by it, so it can count from zero again without
 * reusing one. Mints need no counter at all: each epoch writes one mint record, `local#<epoch>`,
 * whose amount is the epoch's running total, read back off the [ledger].
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

    /** The root holds less than the reservation. Nothing changed. */
    public data object SupplyExhausted : AcquireOutcome

    /**
     * No lineage of ACTIVE edges leads from the root to an eligible leaf, or to the admitted one. A
     * group mid-reshape, a leaf behind a closing edge, and a name that is not a leaf all land here.
     * Nothing changed, and nothing was charged.
     *
     * From [LocalHeddle.acquire], [at] is always the root: a pick only goes down an edge that leads
     * to a group the descent can stop at, so once it leaves the root it cannot fail. From
     * [LocalHeddle.admit], [at] is the leaf that was named.
     */
    public data class NoLiveLineage(public val at: GroupId) : AcquireOutcome, AdmitOutcome
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
        public val defaultSettlement: Long get() = elapsed
    }
}

/** What [LocalHeddle.extend], [LocalHeddle.complete] or [LocalHeddle.cancel] did. */
public sealed interface LeaseOutcome {
    /**
     * The operation applied. [charged] is the cost charged to the lease's leaf and [overrun] how
     * much of it lay beyond the reservation. Both are `0` for an extend and a cancel.
     */
    public data class Done(public val charged: Long, public val overrun: Long) : LeaseOutcome

    /** An extend the root could not fund. The lease and the ledger are unchanged. */
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
 * a charge is never refused. Work another scheduler already started is [admit]ted, with no pick.
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
 * Each operation runs on a local ledger value and is swapped in once at the end, so an outcome
 * that changes nothing leaves [snapshot]'s ledger the very same object. **The one exception is the
 * allocator's wake memory, which a failed [acquire] may still advance:** the pick it tried at the
 * root observed who was waiting, and forgetting that would treat the next observation as the
 * first.
 *
 * ## Supply
 *
 * Supply is minted at [root]. [acquire] and [extend] are refused with a `SupplyExhausted` outcome
 * when the root holds less than they need. [complete] and [admit] are never refused: work that
 * already ran or already started is charged, so when the root is short they mint the shortfall.
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
 * Arithmetic that would leave `Long` throws [ArithmeticException] rather than wrapping, and one
 * place it is reachable is a lifetime ceiling on service. A pick compares virtual times as exact
 * rationals over `Long`, and their numerators grow with the service a parent has handed down,
 * scaled by its children's weights. So the total service under one parent is bounded, and the bound
 * depends on the weights. In one measurement, integer weights 3 : 1 ran past 2^60 units, while
 * fractional weights 3/7 : 2/5 threw near 2^56. A throw changes nothing, so once a parent is past
 * its ceiling every later [acquire] there throws too.
 *
 * A state the engine's own invariants rule out throws [IllegalStateException]: that is a bug in
 * the engine, or a ledger conflict the engine did not create, such as two live inbound edges into
 * one group, a divergent record, or a cycle on a lease's path (#1737).
 *
 * @sample us.tractat.kuilt.heddle.sampleLocalLease
 */
public class LocalHeddle private constructor(
    /** The tree's root: the group supply is minted at and every pick starts from. */
    public val root: GroupId,
    private val policy: LocalPolicy,
    /** The boot counter this engine issues lease ids under. */
    public val epoch: Long,
    private var ledger: EntitlementLedger,
    private val allocator: FairShareAllocator,
) {
    init {
        require(epoch >= 0L) { "epoch must be non-negative, was $epoch" }
    }

    /** Open leases by id. Looked up, never iterated without sorting. */
    private val open = HashMap<LeaseId, OpenLease>()
    private var nextSeq = 0L


    /** Mints [complete] and [admit] wrote to cover a shortfall at the root. */
    internal var forcedMints: Int = 0
        private set

    /** The engine's whole state, as an immutable value. Costs a sort of the open leases and of the wake memory. */
    public fun snapshot(): LocalHeddleState =
        LocalHeddleState(ledger, open.values.map { it.lease }.sortedBy { it.id }, epoch, allocator.memory)

    /**
     * Open one lease for the next slot: pick a leaf among [eligible] and reserve [reservation] for
     * it. [waiting] is every leaf with work queued, and must contain [eligible].
     *
     * A pick never reads [startedAt]; it is stored on the lease for the caller's own reconciling.
     *
     * Returns [AcquireOutcome.NothingEligible] when [eligible] is empty,
     * [AcquireOutcome.SupplyExhausted] when the root holds less than [reservation], and
     * [AcquireOutcome.NoLiveLineage] when no eligible leaf can be reached. In each of those cases the
     * ledger is unchanged; only the allocator's wake memory may have moved (see the class KDoc).
     *
     * @throws IllegalArgumentException when [reservation] is not in `1..`[MAX_RESERVATION], or
     *   [eligible] is not a subset of [waiting].
     */
    public fun acquire(
        waiting: Set<GroupId>,
        eligible: Set<GroupId>,
        startedAt: Long,
        reservation: Long = policy.defaultReservation,
    ): AcquireOutcome {
        requireReservation("reservation", reservation)
        require(waiting.containsAll(eligible)) { "eligible must be a subset of waiting" }
        if (eligible.isEmpty()) return AcquireOutcome.NothingEligible
        val base = fundedBase(reservation) ?: return AcquireOutcome.SupplyExhausted
        return when (val descent = descend(base, Demands.of(base, waiting, eligible, reservation), eligible, reservation)) {
            is Descent.Refused -> AcquireOutcome.NoLiveLineage(descent.at)
            is Descent.Reached -> {
                ledger = descent.ledger
                AcquireOutcome.Acquired(open(descent.leaf, descent.levels.map { it.edge }, reservation, startedAt))
            }
        }
    }

    /**
     * The pick [acquire] makes, on [base] and this engine's allocator: from [root], one
     * [FairShareAllocator.pickChild] per level and a delegation of [reservation] down the edge it
     * picked, until it reaches an eligible leaf. Every step lands on a local value; [base] is not
     * changed. Only the allocator's wake memory moves, which is why [explain] and [previewPicks] run
     * it on a [scratch] engine.
     */
    private fun descend(base: EntitlementLedger, demands: Demands, eligible: Set<GroupId>, reservation: Long): Descent {
        var local = base
        val levels = ArrayList<Level>()
        var group = root
        while (group == root || group !in eligible || !local.isLeaf(group)) {
            val pick = allocator.pickChild(group, local, demands.waiting, demands.eligible) ?: return Descent.Refused(group)
            val edge = pick.grant.attachment
            check(pick.grant.amount == reservation) {
                "picked ${edge.value} for ${pick.grant.amount}, not the reservation $reservation"
            }
            levels += Level(group, pick.ledger, edge)
            local = checkNotNull(seatThenDelegate(pick.ledger, listOf(edge), reservation, demands.waiting)) {
                "the delegation of $reservation down ${edge.value}, just picked, was refused"
            }
            group = checkNotNull(local.record(edge)) { "picked edge ${edge.value} has no record" }.child
        }
        return Descent.Reached(local, group, levels)
    }

    /**
     * The ledger a pick of [reservation] runs on, or `null` when the root cannot fund it. The one
     * supply check [acquire] and [explain] share, so the two cannot disagree about it. It reads the
     * engine and writes nothing: whatever it returns is only committed by the caller.
     */
    private fun fundedBase(reservation: Long): EntitlementLedger? =
        ledger.takeIf { it.holdings(root, SELF) >= reservation }

    /** An engine over [over] with a copy of this one's wake memory: it picks as this one would next, and moving it moves nothing here. */
    private fun scratch(over: EntitlementLedger): LocalHeddle =
        LocalHeddle(root, policy, epoch, over, FairShareAllocator(SELF, policyConfig(policy), allocator.memory))

    /**
     * Charge work another scheduler already started at [leaf]: open a lease for it with no pick,
     * delegating [reservation] down the leaf's lineage. Never refused for supply, since the work is
     * already running; a shortfall at the root is minted.
     *
     * Returns [AcquireOutcome.NoLiveLineage] when [leaf] is the root, has an active child, or has
     * no lineage of active edges to the root. The ledger is then unchanged.
     *
     * @throws IllegalArgumentException when [reservation] is not in `1..`[MAX_RESERVATION].
     */
    public fun admit(leaf: GroupId, startedAt: Long, reservation: Long = policy.defaultReservation): AdmitOutcome {
        requireReservation("reservation", reservation)
        val base = ledger
        if (leaf == root || !base.isLeaf(leaf)) return AcquireOutcome.NoLiveLineage(leaf)
        val path = base.lineageOf(leaf) ?: return AcquireOutcome.NoLiveLineage(leaf)
        // An empty lineage is the root's, or a name with no inbound edge at all: never a leaf.
        if (path.isEmpty() || path.any { base.lifecycle(it) != Lifecycle.ACTIVE }) return AcquireOutcome.NoLiveLineage(leaf)

        val (funded, minted) = fund(base, reservation)
        val local = seatThenDelegate(funded, path, reservation, observedDemand(reservation))
            ?: return AcquireOutcome.NoLiveLineage(leaf)
        ledger = local
        forcedMints += minted
        return AcquireOutcome.Acquired(open(leaf, path, reservation, startedAt))
    }

    /**
     * Reserve [amount] more for [lease], down the path it was opened on. Not a new pick.
     *
     * Returns [LeaseOutcome.PathDraining] when an edge of that path is no longer ACTIVE, and
     * [LeaseOutcome.SupplyExhausted] when the root holds less than [amount]. Neither changes anything.
     *
     * @throws IllegalArgumentException when [amount] is not in `1..`[MAX_RESERVATION].
     */
    public fun extend(lease: LeaseId, amount: Long): LeaseOutcome {
        requireReservation("extend amount", amount)
        val current = open[lease] ?: return missing(lease)
        val base = ledger
        base.firstNotActive(current.path)?.let { return LeaseOutcome.PathDraining(it) }
        if (base.holdings(root, SELF) < amount) return LeaseOutcome.SupplyExhausted
        ledger = delegateOrFail(base, current.path, amount)
        open[lease] = current.copy(lease = current.lease.copy(reserved = checkedAdd(current.lease.reserved, amount)))
        return LeaseOutcome.Done(charged = 0L, overrun = 0L)
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
     */
    public fun complete(lease: LeaseId, actualCost: Long): LeaseOutcome {
        require(actualCost >= 0L) { "actualCost must be non-negative, was $actualCost" }
        val current = open[lease] ?: return missing(lease)
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
        return LeaseOutcome.Done(charged = actualCost, overrun = overrun)
    }

    /** Close [lease] without charging it: everything reserved goes back up the path. */
    public fun cancel(lease: LeaseId): LeaseOutcome {
        val current = open[lease] ?: return missing(lease)
        ledger = releaseUp(ledger, current.path, current.lease.reserved)
        open.remove(lease)
        return LeaseOutcome.Done(charged = 0L, overrun = 0L)
    }

    /**
     * Who would go next: the leaves `reservations.size` calls to [acquire] would pick, in order,
     * if nothing else happened in between. Changes nothing, not even the wake memory.
     *
     * [waiting] gives each leaf's queued work as a count. A leaf counts as waiting while its count
     * is above zero, and each pick takes one from the leaf it picked. Pick `i` reserves
     * `reservations[i]`, and its eligible set is [eligible] among the leaves still waiting. The list
     * ends early at the first call that would not acquire.
     *
     * @throws IllegalArgumentException when a count is negative or a reservation is not in `1..`[MAX_RESERVATION].
     */
    public fun previewPicks(waiting: Map<GroupId, Int>, eligible: Set<GroupId>, reservations: List<Long>): List<GroupId> {
        require(waiting.values.all { it >= 0 }) { "a queued count must be non-negative" }
        val scratch = scratch(ledger)
        val remaining = HashMap(waiting)
        val picks = ArrayList<GroupId>(reservations.size)
        for (reservation in reservations) {
            val waitingNow = remaining.filterValues { it > 0 }.keys
            val eligibleNow = eligible.filterTo(HashSet()) { it in waitingNow }
            val out = scratch.acquire(waitingNow, eligibleNow, startedAt = 0L, reservation = reservation)
            if (out !is AcquireOutcome.Acquired) break
            val leaf = out.lease.leaf
            picks += leaf
            remaining[leaf] = remaining.getValue(leaf) - 1
        }
        return picks
    }

    /**
     * Why [leaf] is not, or is, what the next [acquire] on these same arguments picks. Changes
     * nothing, not even the wake memory.
     *
     * The reasons are checked in [WaitExplanation.Kind] order, and the first that holds is the
     * answer: [leaf] is not in [waiting], not in [eligible], cannot be reached (the same test the
     * pick applies, so a leaf behind a closing edge or a group with an active child is
     * [WaitExplanation.NoLiveLineage]), or the root cannot fund [reservation]. Past those, the
     * answer is the pick itself, run on a [scratch] copy of the engine with the allocator's wake
     * memory, so a group waking from idle is clamped here exactly as the acquire would clamp it:
     * [WaitExplanation.WouldBePickedNext] when the pick ends at [leaf], and otherwise
     * [WaitExplanation.BehindAtLevel] at the first group where it goes another way.
     *
     * @throws IllegalArgumentException as [acquire] does: [reservation] out of range, or [eligible]
     *   not a subset of [waiting].
     * @sample us.tractat.kuilt.heddle.sampleExplainWait
     */
    public fun explain(
        leaf: GroupId,
        waiting: Set<GroupId>,
        eligible: Set<GroupId>,
        reservation: Long = policy.defaultReservation,
    ): WaitExplanation {
        requireReservation("reservation", reservation)
        require(waiting.containsAll(eligible)) { "eligible must be a subset of waiting" }
        if (leaf !in waiting) return WaitExplanation.NotWaiting
        if (leaf !in eligible) return WaitExplanation.NotEligible
        if (!ledger.canEndAt(leaf)) return WaitExplanation.NoLiveLineage(leaf)
        val base = fundedBase(reservation) ?: return WaitExplanation.SupplyExhausted

        val trial = scratch(base)
        val demands = Demands.of(base, waiting, eligible, reservation)
        val descent = trial.descend(base, demands, eligible, reservation)
        // An eligible leaf the pick can end at, with the root funded: the root's pick has a candidate
        // in the leaf's own top edge, and every pick below goes toward an eligible leaf.
        check(descent is Descent.Reached) { "no pick reached any leaf, though ${leaf.value} is eligible and reachable" }
        if (descent.leaf == leaf) return WaitExplanation.WouldBePickedNext
        val lineage = checkNotNull(base.lineageOf(leaf)) { "${leaf.value} can be reached but has no lineage" }
        // Every group above the leaf on its lineage has an active child, so the pick cannot have
        // stopped there: it left the lineage at some level.
        val level = checkNotNull(lineage.indices.firstOrNull { it >= descent.levels.size || descent.levels[it].edge != lineage[it] }) {
            "the pick went past ${leaf.value}, an eligible leaf on its own path"
        }
        check(level < descent.levels.size) { "the pick stopped above ${leaf.value}, at a group with an active child" }
        val at = descent.levels[level]
        // The trial's wake memory has moved on below this level, but each level writes only its own
        // children's entries, so what it holds for this level's children is what this level read.
        return WaitExplanation.BehindAtLevel(
            parent = at.parent,
            nextChild = checkNotNull(base.record(at.edge)) { "picked edge ${at.edge.value} has no record" }.child,
            leafSideVirtualService = checkNotNull(trial.allocator.effectiveVirtualService(at.seated, at.parent, lineage[level])) {
                "${lineage[level].value} is not an active child of ${at.parent.value}"
            },
            front = checkNotNull(trial.allocator.virtualTime(at.seated, at.parent, demands.waiting)) {
                "${at.parent.value} has no active children, though a pick was made there"
            },
            // Over the children the pick saw as candidates: every one is seated by now, and each is
            // granted the whole reservation, so the set the pick averaged is exactly the set
            // `eligible` wants.
            eligibleFront = checkNotNull(trial.allocator.virtualTime(at.seated, at.parent, demands.eligible)) {
                "${at.parent.value} has no active children, though a pick was made there"
            },
        )
    }

    /**
     * [leaf]'s fair share of [totalSlots] slots right now, as an exact rational: its weight's share
     * among the siblings competing beside it, times its parent's share among the siblings beside
     * that, up to the root, times [totalSlots]. This is the share an idealised fair scheduler
     * (generalised processor sharing) would give it while [waiting] stays as it is.
     *
     * "Competing" is the set a pick takes its front over: an ACTIVE child counts when a leaf of
     * [waiting] that a pick could end at lies under it. So work no descent can reach, behind a
     * closing edge or at a group with an active child, competes for nothing and is owed nothing,
     * and it takes nothing from its siblings' shares. `0` when [leaf] is not in [waiting], or is not
     * a leaf a pick could end at.
     *
     * A share, not a promise: the slots a leaf gets over a run track it only while the waiting set
     * holds still, and a leaf that is waiting but rarely eligible gets less.
     *
     * @throws IllegalArgumentException when [totalSlots] is negative.
     * @throws ArithmeticException when the exact share does not fit in `Long` numerators.
     * @sample us.tractat.kuilt.heddle.sampleExplainWait
     */
    public fun entitlement(leaf: GroupId, waiting: Set<GroupId>, totalSlots: Long): Rational {
        require(totalSlots >= 0L) { "totalSlots must be non-negative, was $totalSlots" }
        val base = ledger
        if (leaf !in waiting || !base.canEndAt(leaf)) return Rational.ZERO
        val competing = base.withDelegableLineages(waiting)
        var share = Rational.of(totalSlots)
        for (edge in checkNotNull(base.lineageOf(leaf)) { "${leaf.value} can be reached but has no lineage" }) {
            val record = checkNotNull(base.record(edge)) { "lineage edge ${edge.value} has no record" }
            val competingWeight = base.activeChildren(record.parent)
                .mapNotNull { base.record(it.attachment)?.takeIf { sibling -> sibling.child in competing } }
                .fold(Rational.ZERO) { sum, sibling -> sum + sibling.weight.asRational() }
            share = share * record.weight.asRational() / competingWeight
        }
        return share
    }

    /**
     * Tell the engine how many slots the caller runs at once, [totalSlots]. It sizes the supply
     * watermarks of [LocalPolicy.supply] and nothing else: it mints nothing and changes no lease,
     * and calling it again with the same value changes nothing. Until it is called with a positive
     * value no supply is minted ahead, and an [acquire] or [extend] the root cannot fund is refused.
     *
     * @throws IllegalArgumentException when [totalSlots] is negative, or the target it sizes,
     *   `totalSlots × defaultReservation × targetSlots`, is above `Long.MAX_VALUE / 4`.
     */
    public fun reconcileCapacity(totalSlots: Long) {
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
     */
    public fun reconcileLeases(live: Set<LeaseId>, now: Long): LeaseReconciliation =
        LeaseReconciliation(emptyList(), emptyList())

    /**
     * Replace the ledger with [transform] of it: the test seam for a topology change while leases
     * are open, until L3's `apply` is the way one arrives.
     */
    internal fun reshapeForTest(transform: (EntitlementLedger) -> EntitlementLedger) {
        ledger = transform(ledger)
    }

    /** Mint [amount] at the root, as one act. L2's top-up replaces this; tests fund through it now. */
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

    /**
     * The two demand views one pick reads, each lifted by the very predicate the descent stops on:
     * a leaf reached through ACTIVE edges ([EntitlementLedger.withDelegableLineages]). A subtree
     * with no such leaf is never wanted, so no pick can lead into it.
     */
    private class Demands(val waiting: (AttachmentId) -> Demand, val eligible: (AttachmentId) -> Demand) {
        companion object {
            fun of(base: EntitlementLedger, waiting: Set<GroupId>, eligible: Set<GroupId>, reservation: Long): Demands {
                val wants = Demand(targetOutstanding = UNBOUNDED_TARGET, maximumUsefulGrant = reservation)
                val waitingGroups = base.withDelegableLineages(waiting)
                // eligible ⊆ waiting, so equal sizes mean equal sets, and the lift is shared.
                val eligibleGroups = if (eligible.size == waiting.size) waitingGroups else base.withDelegableLineages(eligible)
                return Demands(demandOver(base, waitingGroups, wants), demandOver(base, eligibleGroups, wants))
            }
        }
    }

    /** What [descend] did. */
    private sealed interface Descent {
        /** It ended at [leaf], with [ledger] the delegated result and [levels] each pick on the way. */
        class Reached(val ledger: EntitlementLedger, val leaf: GroupId, val levels: List<Level>) : Descent

        /** No pick could be made at [at]. */
        class Refused(val at: GroupId) : Descent
    }

    /** One level of a [Descent]: the pick at [parent] chose [edge], on [seated], the ledger with [parent]'s children seated. */
    private class Level(val parent: GroupId, val seated: EntitlementLedger, val edge: AttachmentId)

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

        private fun requireReservation(name: String, amount: Long) =
            require(amount in 1L..MAX_RESERVATION) { "$name must be in 1..$MAX_RESERVATION, was $amount" }

        /** The index of the first edge of [path] that is not ACTIVE, or `null` when every edge is. */
        private fun EntitlementLedger.firstNotActiveIndex(path: List<AttachmentId>): Int? =
            path.indexOfFirst { lifecycle(it) != Lifecycle.ACTIVE }.takeIf { it >= 0 }

        /**
         * Whether a pick can end at [group]: not the root, a leaf, and reached from the root through
         * ACTIVE edges only. The descent's own terminal test, through the same lift.
         */
        private fun EntitlementLedger.canEndAt(group: GroupId): Boolean = group in withDelegableLineages(listOf(group))

        private fun Weight.asRational(): Rational = Rational.of(numerator, denominator)

        /** The first edge of [path] that is not ACTIVE, or `null` when every edge is. */
        private fun EntitlementLedger.firstNotActive(path: List<AttachmentId>): AttachmentId? =
            firstNotActiveIndex(path)?.let(path::get)

        /** An engine with no topology and no supply, issuing lease ids under [epoch]. */
        public fun fresh(root: GroupId, policy: LocalPolicy, epoch: Long): LocalHeddle =
            over(EntitlementLedger.ZERO, root, policy, epoch)

        /** An engine over [ledger] as it stands, with a fresh allocator. For tests until L3 boots one from a topology. */
        internal fun over(ledger: EntitlementLedger, root: GroupId, policy: LocalPolicy, epoch: Long): LocalHeddle =
            LocalHeddle(root, policy, epoch, ledger, FairShareAllocator(SELF, policyConfig(policy)))

        /**
         * The allocator's policy: an unbounded quantum, because each pick's reservation rides in its
         * demand's `maximumUsefulGrant`, and no outstanding cap, because the caller owns concurrency.
         */
        private fun policyConfig(policy: LocalPolicy): PolicyConfig =
            PolicyConfig(quantum = Long.MAX_VALUE, perChildOutstandingCap = Long.MAX_VALUE, sleeperCredit = policy.sleeperCredit)

        /** [wants] for an edge whose child is in [groups], [Demand.NONE] for every other. */
        private fun demandOver(ledger: EntitlementLedger, groups: Set<GroupId>, wants: Demand): (AttachmentId) -> Demand =
            { edge -> if (ledger.record(edge)?.child in groups) wants else Demand.NONE }
    }
}
