package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.ReplicaId

/**
 * How a [LocalHeddle] sizes a lease and how much idle credit a waking group keeps.
 *
 * @property defaultReservation the cost a lease reserves when the caller passes no estimate, in the
 *   caller's cost unit (time, by convention: R2). Positive.
 * @property sleeperCredit the virtual-time credit a group that wakes from idle may keep (design
 *   §10.6). `0`, the default, keeps none. Non-negative.
 */
public data class LocalPolicy(
    public val defaultReservation: Long,
    public val sleeperCredit: Long = 0L,
) {
    init {
        require(defaultReservation >= 1L) { "defaultReservation must be positive, was $defaultReservation" }
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
 * Everything a [LocalHeddle] holds, as one immutable value: safe to read, encode or hand to
 * another thread without the caller's scheduling lock.
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
     * No live lineage leads from the root to an eligible leaf, or to the admitted one: the walk
     * found no child it could grant under [at]. A group mid-reshape, a leaf whose edge is closing,
     * and a name that is not a leaf all land here. Nothing changed, and nothing was charged.
     */
    public data class NoLiveLineage(public val at: GroupId) : AcquireOutcome, AdmitOutcome
}

/**
 * What [LocalHeddle.admit] did: [AcquireOutcome.Acquired] or [AcquireOutcome.NoLiveLineage]. It
 * shares those two arms with [AcquireOutcome] and has no other, since an admission makes no pick
 * and is never refused for supply.
 */
public sealed interface AdmitOutcome

/** What [LocalHeddle.extend], [LocalHeddle.complete] or [LocalHeddle.cancel] did. */
public sealed interface LeaseOutcome {
    /**
     * The operation applied. [charged] is the cost charged to the lease's leaf and [overrun] how
     * much of it lay beyond the reservation. Both are `0` for an extend and a cancel.
     */
    public data class Done(public val charged: Long, public val overrun: Long) : LeaseOutcome

    /** An extend the root could not fund. The lease and the ledger are unchanged. */
    public data object SupplyExhausted : LeaseOutcome

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
 * down to the child it picked. The deadline it orders by is `ev + reservation / weight`: a short
 * job from a group that is behind goes first. Two sets steer it, and they are different on
 * purpose:
 * - **waiting** is every leaf with work queued. It decides who is competing, which is what seats a
 *   new group at its parent's front and what tells a group returning from idle apart from one that
 *   never left.
 * - **eligible** is the waiting leaves this particular slot can run. Only they can be picked.
 *
 * A group that is waiting but cannot run on this slot keeps its claim: it is not idle, so the next
 * slot it can run on does not clamp it as a waker.
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
 * allocator's wake memory, which a failed [acquire] may still advance:** the picks it made on the
 * way down observed who was waiting, and forgetting that would treat the next observation as the
 * first.
 *
 * ## Supply
 *
 * Supply is minted at [root]. [acquire] and [extend] are refused with a `SupplyExhausted` outcome
 * when the root holds less than they need. [complete] and [admit] are never refused: work that
 * already ran or already started is charged, so when the root is short they mint the shortfall.
 *
 * ## What it does not do
 *
 * It reads no clock and draws no random number: every time is a number the caller passes in, and
 * every output is ordered by id, so a replay of the same calls gives the same picks on every
 * target. It owns no concurrency either — call [acquire] once per free slot.
 *
 * **Not thread-safe.** One engine has one writer, and the caller serializes calls to it. A reader
 * that must not take the caller's lock reads [snapshot], which is immutable.
 *
 * ## Failure surface
 *
 * Nothing suspends and nothing times out. An argument out of range throws
 * [IllegalArgumentException]. Arithmetic that would leave `Long` throws [ArithmeticException]
 * rather than wrapping. A state the engine's own invariants rule out throws
 * [IllegalStateException]: that is a bug in the engine, not in the call (#1737).
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
    private var nextMint = 0L

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
     * @throws IllegalArgumentException when [reservation] is not positive, or [eligible] is not a
     *   subset of [waiting].
     */
    public fun acquire(
        waiting: Set<GroupId>,
        eligible: Set<GroupId>,
        startedAt: Long,
        reservation: Long = policy.defaultReservation,
    ): AcquireOutcome {
        require(reservation >= 1L) { "reservation must be positive, was $reservation" }
        require(waiting.containsAll(eligible)) { "eligible must be a subset of waiting" }
        if (eligible.isEmpty()) return AcquireOutcome.NothingEligible
        val base = ledger
        if (base.holdings(root, SELF) < reservation) return AcquireOutcome.SupplyExhausted

        val wants = Demand(targetOutstanding = UNBOUNDED_TARGET, maximumUsefulGrant = reservation)
        val waitingDemand = demandOver(base, withLineage(base, waiting), wants)
        val eligibleDemand = demandOver(base, withLineage(base, eligible), wants)

        var local = base
        val path = ArrayList<AttachmentId>()
        var group = root
        while (group == root || group !in eligible || !isLeaf(local, group)) {
            val pick = allocator.pickChild(group, local, waitingDemand, eligibleDemand)
                ?: return AcquireOutcome.NoLiveLineage(group)
            val edge = pick.grant.attachment
            check(pick.grant.amount == reservation) {
                "picked ${edge.value} for ${pick.grant.amount}, not the reservation $reservation"
            }
            local = checkNotNull(seatThenDelegate(pick.ledger, listOf(edge), reservation, waitingDemand)) {
                "the delegation of $reservation down ${edge.value}, just picked, was refused"
            }
            path += edge
            group = checkNotNull(local.record(edge)) { "picked edge ${edge.value} has no record" }.child
        }

        ledger = local
        return AcquireOutcome.Acquired(open(group, path, reservation, startedAt))
    }

    /**
     * Charge work another scheduler already started at [leaf]: open a lease for it with no pick,
     * delegating [reservation] down the leaf's lineage. Never refused for supply, since the work is
     * already running; a shortfall at the root is minted.
     *
     * Returns [AcquireOutcome.NoLiveLineage] when [leaf] is the root, has an active child, or has
     * no lineage of active edges to the root. The ledger is then unchanged.
     *
     * @throws IllegalArgumentException when [reservation] is not positive.
     */
    public fun admit(leaf: GroupId, startedAt: Long, reservation: Long = policy.defaultReservation): AdmitOutcome {
        require(reservation >= 1L) { "reservation must be positive, was $reservation" }
        val base = ledger
        if (leaf == root || !isLeaf(base, leaf)) return AcquireOutcome.NoLiveLineage(leaf)
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
     * @throws IllegalArgumentException when [amount] is not positive.
     */
    public fun extend(lease: LeaseId, amount: Long): LeaseOutcome {
        require(amount >= 1L) { "extend amount must be positive, was $amount" }
        val current = open[lease] ?: return missing(lease)
        val base = ledger
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
     * @throws IllegalArgumentException when [actualCost] is negative.
     */
    public fun complete(lease: LeaseId, actualCost: Long): LeaseOutcome {
        require(actualCost >= 0L) { "actualCost must be non-negative, was $actualCost" }
        val current = open[lease] ?: return missing(lease)
        val reserved = current.lease.reserved
        val overrun = maxOf(0L, actualCost - reserved)
        var local = ledger
        var minted = 0
        if (overrun > 0L) {
            val (funded, forced) = fund(local, overrun)
            minted = forced
            local = delegateOrFail(funded, current.path, overrun)
        }
        if (actualCost > 0L) {
            val spend = checkNotNull(local.spendCaptured(SELF, current.path, actualCost)) {
                "the spend of $actualCost on ${current.lease.leaf.value}'s captured path was refused"
            }
            local = local.piece(spend.delta)
        }
        local = releaseUp(local, current.path, checkedSub(checkedAdd(reserved, overrun), actualCost))
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
     * @throws IllegalArgumentException when a count is negative or a reservation is not positive.
     */
    public fun previewPicks(waiting: Map<GroupId, Int>, eligible: Set<GroupId>, reservations: List<Long>): List<GroupId> {
        require(waiting.values.all { it >= 0 }) { "a queued count must be non-negative" }
        val scratch = LocalHeddle(root, policy, epoch, ledger, FairShareAllocator(SELF, policyConfig(policy), allocator.memory))
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
     * Replace the ledger with [transform] of it: the test seam for a topology change while leases
     * are open, until L3's `apply` is the way one arrives.
     */
    internal fun reshapeForTest(transform: (EntitlementLedger) -> EntitlementLedger) {
        ledger = transform(ledger)
    }

    /** Mint [amount] at the root, as one act. L2's top-up replaces this; tests fund through it now. */
    internal fun mintSupply(amount: Long) {
        ledger = ledger.piece(ledger.mint(nextMintId(), root, SELF, amount).delta)
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
        return start.piece(start.mint(nextMintId(), root, SELF, shortfall).delta) to 1
    }

    /**
     * A demand view for a delegation that is not a pick: each edge as the allocator last observed
     * it. Only seating reads it, to find the front a newborn joins, and it observes nothing.
     */
    private fun observedDemand(amount: Long): (AttachmentId) -> Demand {
        val wants = Demand(targetOutstanding = UNBOUNDED_TARGET, maximumUsefulGrant = amount)
        return { edge -> if (allocator.lastObservedDemanding(edge) == true) wants else Demand.NONE }
    }

    /**
     * A fresh id per mint act. Two acts under one id max-join into one record and the smaller amount
     * is lost (Task C found this the hard way).
     */
    private fun nextMintId(): MintId = MintId("local#${nextMint++}")

    /** An open lease and the path it was delegated down, which every later charge follows. */
    private data class OpenLease(val lease: Lease, val path: List<AttachmentId>)

    public companion object {
        /** The one writer. Internal: a consumer never needs to name it. */
        internal val SELF: ReplicaId = ReplicaId("local")

        /** A target so large that `targetOutstanding − outstanding` never runs out. */
        private const val UNBOUNDED_TARGET: Long = 1L shl 40

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

        /**
         * The leaf predicate, and the only one: the ledger's own ACTIVE-only child view, so a stray
         * PREPARED or CLOSING child does not stop a group being a leaf (#3191). Swap to
         * [EntitlementLedger.isLeaf] once that reads "no ACTIVE child edge" too (#3193).
         */
        private fun isLeaf(ledger: EntitlementLedger, group: GroupId): Boolean = ledger.activeChildren(group).isEmpty()

        /** Each leaf in [leaves] that has a live lineage, with every group on that lineage. */
        private fun withLineage(ledger: EntitlementLedger, leaves: Set<GroupId>): Set<GroupId> {
            val out = HashSet<GroupId>()
            for (leaf in leaves) {
                val lineage = ledger.lineageOf(leaf) ?: continue
                out += leaf
                for (edge in lineage) ledger.record(edge)?.let { out += it.parent }
            }
            return out
        }

        /** [wants] for an edge whose child is in [groups], [Demand.NONE] for every other. */
        private fun demandOver(ledger: EntitlementLedger, groups: Set<GroupId>, wants: Demand): (AttachmentId) -> Demand =
            { edge -> if (ledger.record(edge)?.child in groups) wants else Demand.NONE }
    }
}
