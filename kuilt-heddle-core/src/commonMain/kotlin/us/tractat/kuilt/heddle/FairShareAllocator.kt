package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.math.Int128
import us.tractat.kuilt.math.Rounding
import us.tractat.kuilt.math.mulDiv
import us.tractat.kuilt.math.plusOrNull
import us.tractat.kuilt.math.timesOrNull
import us.tractat.kuilt.math.timesWide

/**
 * Where a [FairShareAllocator] reads the ledger and lands its writes.
 *
 * In `:kuilt-heddle` this is the replicator that keeps the ledger converged across peers. For a
 * single process it can be as small as a `var` holding the latest [EntitlementLedger].
 */
public interface LedgerCell {
    /** The ledger as it stands now. */
    public val current: EntitlementLedger

    /**
     * Run [transform] against the ledger **as it stands at the moment of writing** and apply the
     * patch it returns, or change nothing when it returns `null`. Reading and writing are one step:
     * no other write may land between them, because the allocator decides each grant on exactly
     * the state that grant lands on.
     *
     * @return whether a patch was applied.
     */
    public fun mutateOrSkip(transform: (EntitlementLedger) -> Patch<EntitlementLedger>?): Boolean
}

/**
 * One peer's fair-share scheduler: it hands this peer's holdings at a parent down to the children
 * that want them, one quantum at a time, in the order [HeddlePolicy] picks (design §7.3).
 *
 * It needs no network. Give it a [LedgerCell] and the current demand, and [schedule] runs
 * allocation rounds until nothing more can move. `HeddleNode` is this plus replication, a demand
 * board and reservations.
 *
 * ## What it remembers between calls
 *
 * The §10.6 wake clamps this peer has computed, and the demanding state each was derived from
 * (design §7.2; issue #1695). Beside them, each parent's **net-service clock**, and where it stood
 * when this peer saw each child stop asking, which is what moves a child that comes back (#3376).
 * All of it is **scheduler-local**: never replicated, never in the ledger. A divergent offset
 * reorders one peer's grants but can never create entitlement.
 *
 * The demanding state is what makes the clamp an *edge* detector rather than a level one: `false`
 * means this peer has seen the child not competing, so the next round in which it competes is a
 * wake. An id that is *absent* has never been observed, and a seated one found competing is a wake
 * too, the restart rule (design §7.2, #3472): a first sighting says nothing about how long the
 * child idled, so this peer gives it no credit for a gap it did not watch, at the cost of its lag,
 * once. That is every child of a restarted `HeddleNode`, and a child another peer seated and ran.
 * Seating a genuinely new generation is the seat bump's job ([schedule]), not the clamp's.
 *
 * That memory is a value too: [memory] exports it as an [AllocatorMemory] and the secondary
 * constructor takes one back, so a single-process caller can persist it beside its ledger, and can
 * ask "who would go next" of a throwaway allocator built over a copy without moving this one.
 *
 * ## Two ways to drive it
 *
 * - [schedule] runs grants until nothing more can move, against one [Demand] view, and writes them
 *   through a [LedgerCell]. That is the replicated node's shape: it tops children up to a target.
 * - [pickChild] makes **one** pick on a ledger value, with the seat and wake step reading one view
 *   (who is *waiting*) and the pick reading another (who is *eligible* for this slot). That is a
 *   lease's shape: a slot that only some children can run on must not make the others look idle,
 *   or the next time they are eligible they would be clamped as wakers they never were.
 *
 * ## Thread-safety
 *
 * **Not thread-safe.** That state is plain mutable maps, so one allocator belongs to one peer and
 * is called by one thread at a time. `HeddleNode` calls it only under its own lock.
 *
 * @param self the replica whose holdings this allocator delegates.
 * @param policy the quantum and sleeper credit the policy runs with.
 * @sample us.tractat.kuilt.heddle.sampleFairShareAllocator
 */
public class FairShareAllocator(
    private val self: ReplicaId,
    private val policy: PolicyConfig,
) {
    /**
     * The wake clamps this peer computed, wide, in ticks of each edge's own grid (#3353). A zero
     * offset is absent: an edge reads an absent one as zero, so storing it would spell one state
     * two ways. [AllocatorMemory] refuses one, so a loaded memory holds none, and every write
     * goes through [writeWakeOffset].
     */
    private val wakeOffsets = HashMap<AttachmentId, Int128>()
    private val demandingObserved = HashMap<AttachmentId, Boolean>()

    /**
     * Each parent's net-service clock (#3376): how far its asking children's kept service has moved
     * them, in [CLOCK_TICKS_PER_UNIT] ticks to a unit of virtual time. Signed, since a round whose
     * refunds outweigh its grants runs it backward. Only ever read as a difference against
     * [departures].
     */
    private val clocks = HashMap<GroupId, Int128>()

    /** Each child's `baseIssued − returned` when its parent's clock last ran: what the next run measures from. */
    private val served = HashMap<AttachmentId, Long>()

    /** Where its parent's clock stood when this peer saw a child stop asking, until it asks again. */
    private val departures = HashMap<AttachmentId, Int128>()

    /** How far each child's shifts so far have run past its parent's clock, under one tick ([shiftOf]). */
    private val shiftRemainders = HashMap<AttachmentId, ShiftRemainder>()

    /**
     * Once switched a twin that never rebased. Nothing rebases now (design §7.1, step B), so it does
     * nothing: setting it changes no pick, and it reads what was last set. Kept because 0.7.5
     * published it.
     */
    @HeddleInternalApi
    @Deprecated("Nothing rebases (design §7.1, step B); this switch does nothing.")
    public var rebaseEnabled: Boolean = true

    /** Once the count of rebases this allocator made. Nothing rebases now, so it reads zero. */
    @HeddleInternalApi
    @Deprecated("Nothing rebases (design §7.1, step B); this always reads zero.")
    public val rebases: Int get() = 0

    /**
     * An allocator that resumes from [memory], as if it were the allocator [memory] was exported
     * from. Building one over a copy of a live allocator's [FairShareAllocator.memory] gives a
     * throwaway that picks exactly as the live one would next.
     */
    public constructor(self: ReplicaId, policy: PolicyConfig, memory: AllocatorMemory) : this(self, policy) {
        wakeOffsets.putAll(memory.wakeOffsets)
        demandingObserved.putAll(memory.demandingObserved)
        clocks.putAll(memory.clocks)
        served.putAll(memory.served)
        departures.putAll(memory.departures)
        shiftRemainders.putAll(memory.shiftRemainders)
    }

    /**
     * What this allocator remembers right now, as an immutable value with its maps in key order.
     * A copy: later calls on this allocator do not change it. Costs one sort of each map, so read
     * it to persist or to preview, not on every pick.
     */
    public val memory: AllocatorMemory
        get() = AllocatorMemory(
            wakeOffsets = wakeOffsets.entries.sortedBy { it.key }.associate { it.key to it.value },
            demandingObserved = demandingObserved.entries.sortedBy { it.key }.associate { it.key to it.value },
            clocks = clocks.entries.sortedBy { it.key }.associate { it.key to it.value },
            served = served.entries.sortedBy { it.key }.associate { it.key to it.value },
            departures = departures.entries.sortedBy { it.key }.associate { it.key to it.value },
            shiftRemainders = shiftRemainders.entries.sortedBy { it.key }.associate { it.key to it.value },
        )

    /**
     * An allocator that remembers exactly what this one does, and that later calls on either leave
     * the other alone: [memory] without the sort, for a caller that copies on every call.
     */
    internal fun fork(): FairShareAllocator = FairShareAllocator(self, policy).also {
        it.wakeOffsets.putAll(wakeOffsets)
        it.demandingObserved.putAll(demandingObserved)
        it.clocks.putAll(clocks)
        it.served.putAll(served)
        it.departures.putAll(departures)
        it.shiftRemainders.putAll(shiftRemainders)
    }

    /**
     * Drop everything this allocator remembers about [edges]: the ones its ledger has just
     * forgotten ([EntitlementLedger.forget], design §5.5). [forgetDepartedChildren] cannot do it
     * later, since it finds an edge's parent through the edge's record, which forgetting removes,
     * so without this a forgotten edge's entries would stay for good.
     */
    internal fun forget(edges: Set<AttachmentId>) {
        wakeOffsets.keys.removeAll(edges)
        demandingObserved.keys.removeAll(edges)
        served.keys.removeAll(edges)
        departures.keys.removeAll(edges)
        shiftRemainders.keys.removeAll(edges)
    }

    /** The demanding state [edge] was last observed in, or `null` when it has never been observed. */
    internal fun lastObservedDemanding(edge: AttachmentId): Boolean? = demandingObserved[edge]

    /**
     * Run allocation rounds at [parent] until nothing more can be delegated: build the policy
     * input from the active children, their [EdgeSummary]s and the current [demand], then apply
     * each [HeddlePolicy.pick] grant to the ledger before the next round (design §7.3). A bad local
     * decision only misplaces entitlement — it can never create any, so partitioned peers may
     * schedule divergently and still converge on heal.
     *
     * Before the first pick it seats the newborns and clamps the wakers against one front over the
     * set neither of them is in (§7.2, §10.6, #1752), so a joiner meets the front it is rejoining
     * rather than one it helped set.
     *
     * Two states stop it, and neither throws. Active children whose weights fail
     * [weightsFitTheBudget] stop it before the seat step, since the replicated ledger can merge such
     * a set from writes that each fit (#3353). And a grant that would carry the effective issuance
     * of the edge it picked past the lifetime watermark, `Long.MAX_VALUE` less
     * [LocalHeddle.LIFETIME_HEADROOM], stops it after the pick, on this peer's view (#3475). A full
     * edge that is picked stalls its parent rather than being skipped for a sibling, as in
     * [LocalHeddle.acquire]. No position stops it: a pick orders the full 128-bit position, and a
     * position only ever counts up from the edge's start (#3472).
     *
     * Peers that grant one edge before their writes merge each check their own view, so the merged
     * issuance can pass the watermark: the headroom absorbs unmerged grants on one edge totalling at
     * most [LocalHeddle.LIFETIME_HEADROOM], 16,384 of [LocalHeddle.MAX_RESERVATION]. A grant is
     * bounded by the quantum and holdings, not by that, so the bound is the total. Past `Long` the
     * sum throws when read, never wraps.
     *
     * ## A refused or empty call writes nothing
     *
     * The seat step and the first pick run together, on a [fork] of this allocator, inside **one**
     * [LedgerCell.mutateOrSkip] (#3353):
     * - the first round **refused** at the weight budget or the lifetime limit: no patch, and the
     *   fork is dropped, so no seat, no clamp and no observation is kept;
     * - the first pick found **nothing** to grant: no patch either, so the seats are dropped with
     *   it, but the fork's wake memory is kept, since it observed who was waiting. That is what
     *   [LocalHeddle] does, and it means an idle call writes nothing to a replicated ledger;
     * - the first pick **granted**: the seats and the delegation land as one patch, and the fork is
     *   kept.
     *
     * The fork is adopted only once the write step has returned, so a cell that throws while writing
     * leaves this allocator as it was. Each later round is its own write, as before: a refusal there,
     * or a throw from the cell or the policy in round `k > 1`, keeps rounds `1..k−1`, which have
     * already landed.
     *
     * @param earmarked units this peer has reserved at [parent] as a leaf. They are subtracted from
     *   the holdings it may delegate: a later completion charges its captured path without a gate,
     *   relying on the earmark to have kept the units, so delegating them would overspend (design
     *   §10.1, §10.7).
     * @param demand the current appetite of one child edge, [Demand.NONE] for an edge that wants
     *   nothing. Asked once per active child each time the policy input is built, so it should
     *   answer from live state rather than a copy taken before the call.
     * @return [AllocationOutcome.Delegated] with the count of grants applied this call, even when a
     *   refusal stopped it after them; otherwise [AllocationOutcome.WeightBudget] when [parent]'s
     *   children cannot be ordered, [AllocationOutcome.LifetimeExhausted] when the first grant would
     *   pass the lifetime watermark, and [AllocationOutcome.NothingToDelegate] when it simply had
     *   nothing to grant.
     * @throws IllegalStateException when this allocator was built from an [AllocatorMemory] no
     *   allocator wrote, one holding a child's shift remainder at a denominator other than its
     *   weight's (#3416 review), and that child returns: a remainder is always on its edge's own
     *   grid, since a reweight is a new edge.
     */
    public fun schedule(
        parent: GroupId,
        ledger: LedgerCell,
        earmarked: Long,
        demand: (AttachmentId) -> Demand,
    ): AllocationOutcome {
        var first: FirstRound? = null
        ledger.mutateOrSkip { s ->
            val round = firstRound(fork(), s, parent, earmarked, demand)
            first = round
            (round as? FirstRound.Granted)?.patch
        }
        when (val opened = checkNotNull(first) { "the cell's write step never ran its transform" }) {
            FirstRound.OverBudget -> return AllocationOutcome.WeightBudget(parent)
            FirstRound.LifetimeExhausted -> return AllocationOutcome.LifetimeExhausted(parent)
            is FirstRound.Empty -> {
                adoptMemoryOf(opened.fork)
                return AllocationOutcome.NothingToDelegate
            }
            is FirstRound.Granted -> adoptMemoryOf(opened.fork)
        }
        var grants = 1
        while (grants < MAX_ROUNDS_PER_CALL) {
            // One pick, inside the cell's write step, on the state the grant lands on. Nothing
            // delegable ⇒ mutateOrSkip writes nothing and answers false (#2090). Peeking on
            // `ledger.current` first would run the policy twice per grant, the peek deciding
            // against a state an inbound write may already have moved past.
            val applied = ledger.mutateOrSkip { s ->
                // Again on each round's state: a merge between two rounds can bring the set over.
                if (!childWeightsFit(s, parent)) return@mutateOrSkip null
                // Before each pick, on the state it lands on: the clock over the last grant, and any
                // child that grant filled seen leaving at it, not at the next call (#3416 review).
                markDepartures(parent, policyEdges(s, parent, demand))
                // A merge between rounds can carry the load past the limit; the next call's first round names it.
                if (!stepFits(parent, s, demand)) return@mutateOrSkip null
                val grant = pickOne(s, parent, earmarked, demand) ?: return@mutateOrSkip null
                // A full edge stops the call here; the next call's first round names it (#3475).
                if (!grantFits(s, grant)) return@mutateOrSkip null
                s.delegate(self, grant.attachment, grant.amount)
            }
            if (!applied) break
            grants++
        }
        // Progress first: a refusal in a later round follows a grant, so this call reports the
        // grants, and the next call meets the refusal in its first round and names it.
        return AllocationOutcome.Delegated(grants)
    }

    /** Whether [grant] fits the lifetime limit on [s]: its edge's effective issuance stays at or below the watermark (#3475). */
    private fun grantFits(s: EntitlementLedger, grant: Grant): Boolean = pickFits(s.effectiveIssuance(grant.attachment), grant.amount)

    /** Whether [parent]'s active children in [s] fit [weightsFitTheBudget], so a pick there can be computed. */
    private fun childWeightsFit(s: EntitlementLedger, parent: GroupId): Boolean =
        weightsFitTheBudget(s.activeChildren(parent).mapNotNull { s.record(it.attachment)?.weight })

    /** Take [fork]'s wake memory as this allocator's own: the half of a first round that is not in the ledger. */
    private fun adoptMemoryOf(fork: FairShareAllocator) {
        wakeOffsets.clear()
        wakeOffsets.putAll(fork.wakeOffsets)
        demandingObserved.clear()
        demandingObserved.putAll(fork.demandingObserved)
        clocks.clear()
        clocks.putAll(fork.clocks)
        served.clear()
        served.putAll(fork.served)
        departures.clear()
        departures.putAll(fork.departures)
        shiftRemainders.clear()
        shiftRemainders.putAll(fork.shiftRemainders)
    }

    /**
     * Whether [edge] wakes: demanding now, and last observed not demanding. Or, never observed at
     * all, seated and demanding: the restart rule (design §7.2, #3472). This allocator did not
     * watch the gap before it first saw the child, so it gives it no credit for it, wherever the
     * child stands. Clamping it costs it at most its lag, once, the penalty side: clamping costs
     * turns, never credit (§10.6).
     */
    private fun isWaker(edge: PolicyEdge): Boolean {
        if (!HeddlePolicy.isDemanding(edge)) return false
        return when (demandingObserved[edge.record.id]) {
            false -> true
            true -> false
            null -> edge.gauge != null
        }
    }

    /**
     * [schedule]'s first round on [fork], against the one ledger value [s]: the seat step and the
     * first pick. Nothing is written; the caller lands [FirstRound.Granted.patch] and adopts the fork.
     */
    private fun firstRound(
        fork: FairShareAllocator,
        s: EntitlementLedger,
        parent: GroupId,
        earmarked: Long,
        demand: (AttachmentId) -> Demand,
    ): FirstRound {
        // Before the seat step, which already multiplies by a weight: a replicated ledger can hold a
        // set no single write would have admitted (#3353), and a pick over it throws.
        if (!fork.childWeightsFit(s, parent)) return FirstRound.OverBudget
        val cell = ValueCell(s)
        // A step past the position limit refuses the whole call, as a full lifetime counter does (design §7.1).
        if (fork.settleJoiners(parent, cell, demand) == Landing.PastLimit) return FirstRound.LifetimeExhausted
        val picked = fork.pickOne(cell.current, parent, earmarked, demand)
        // After the pick, on this peer's view, before anything is written (#3475).
        if (picked != null && !grantFits(cell.current, picked)) return FirstRound.LifetimeExhausted
        val delegated = picked?.let { grant ->
            cell.mutateOrSkip { it.delegate(fork.self, grant.attachment, grant.amount) }
        } ?: false
        if (!delegated) return FirstRound.Empty(fork)
        val written = checkNotNull(cell.written) { "a delegation that landed wrote nothing" }
        return FirstRound.Granted(fork, Patch(written))
    }

    /** How [schedule]'s first round ended. Each arm carries exactly what the caller acts on. */
    private sealed interface FirstRound {
        /**
         * [parent]'s children fail [weightsFitTheBudget], the one refusal: write nothing, and drop the
         * fork with everything it observed.
         */
        data object OverBudget : FirstRound

        /**
         * The first grant would carry its edge's effective issuance past the lifetime watermark
         * (#3475): write nothing, and drop the fork, as for [OverBudget].
         */
        data object LifetimeExhausted : FirstRound

        /** Nothing to grant: write nothing, but keep [fork]'s wake memory, which saw who was waiting. */
        class Empty(val fork: FairShareAllocator) : FirstRound

        /** A grant: land [patch], the seats with the delegation, and keep [fork]. */
        class Granted(val fork: FairShareAllocator, val patch: Patch<EntitlementLedger>) : FirstRound
    }

    /**
     * Make **one** pick at [parent]: seat the newborns and clamp the wakers exactly as [schedule]
     * does, reading [waiting], then choose one child among those [eligible] wants, on the seated
     * state. Nothing is delegated: the caller delegates [ChildPick.grant] on [ChildPick.ledger],
     * which every child of [parent] is already seated on, so that delegation cannot hand a newborn
     * the lifetime credit an unseated delegation would (#1752).
     *
     * The two views are separate because a lease is for one slot. A child that is waiting but
     * cannot run on this slot is still competing: it must count toward the front a newborn is
     * seated at, and it must be observed as demanding, so that when a slot it *can* run on comes
     * up it is not clamped as a waker. Feeding [eligible] to the wake step instead would forfeit
     * the turn of every child that merely could not run on the last few slots.
     *
     * The size of the grant is whatever [eligible]'s [Demand.maximumUsefulGrant] says, trimmed as
     * [HeddlePolicy.pick] trims it, so a lease's reservation rides there and the allocator's own
     * [PolicyConfig.quantum] can be left unbounded. The deadline is then `ev + reservation / w`.
     *
     * The wake memory advances whether or not a child is picked: a pick that finds nothing still
     * observed who was waiting. The returned ledger is a new value; [ledger] is not changed.
     *
     * @param waiting each child edge's appetite for the seat and wake step: [Demand.NONE] for a
     *   child that wants no service at all.
     * @param eligible each child edge's appetite for this pick: [Demand.NONE] for a child that
     *   cannot take this grant. A child [eligible] wants should also be one [waiting] wants.
     * @return the seated ledger and the grant, or `null` when no child can be granted anything:
     *   none is eligible, none is seated, [parent] holds nothing, or the seat and wake step there
     *   would pass the position limit ([pickChildStep] tells that one apart).
     */
    public fun pickChild(
        parent: GroupId,
        ledger: EntitlementLedger,
        waiting: (AttachmentId) -> Demand,
        eligible: (AttachmentId) -> Demand,
    ): ChildPick? = (pickChildStep(parent, ledger, waiting, eligible) as? ChildStep.Picked)?.pick

    /**
     * [pickChild], with the third answer it folds into `null`: the seat and wake step at [parent]
     * did not fit the [POSITION_LIMIT] (design §7.1), so nothing was seated, clamped or observed
     * there. [LocalHeddle] answers that as `LifetimeExhausted(parent)`.
     */
    internal fun pickChildStep(
        parent: GroupId,
        ledger: EntitlementLedger,
        waiting: (AttachmentId) -> Demand,
        eligible: (AttachmentId) -> Demand,
    ): ChildStep {
        val cell = ValueCell(ledger)
        if (settleJoiners(parent, cell, waiting) == Landing.PastLimit) return ChildStep.PastLimit
        val seated = cell.current
        val grant = pickOne(seated, parent, earmarked = 0L, eligible) ?: return ChildStep.NoPick
        return ChildStep.Picked(ChildPick(seated, grant))
    }

    /** What [pickChildStep] found at a parent. */
    internal sealed interface ChildStep {
        /** The step landed and a child was picked. */
        class Picked(val pick: ChildPick) : ChildStep

        /** The step landed and no child could be granted anything. */
        data object NoPick : ChildStep

        /** The step did not fit the [POSITION_LIMIT]: nothing was written. */
        data object PastLimit : ChildStep
    }

    /**
     * The step [pickChild] takes before it picks, and nothing more: seat the newborns and clamp the
     * wakers at [parent], reading [waiting], and observe who was waiting. For a delegation that is
     * not a pick but must leave the wake memory as the pick would have ([LocalHeddle.admit] with a
     * waiting set, #3320). A seat or a clamp is exact and wide wherever it lands. Returns the seated
     * ledger; [ledger] is not changed. A step past the [POSITION_LIMIT] observes nothing and returns
     * [ledger] as it was: a charging call is never refused for it (design §7.1).
     */
    internal fun settle(parent: GroupId, ledger: EntitlementLedger, waiting: (AttachmentId) -> Demand): EntitlementLedger {
        val cell = ValueCell(ledger)
        settleJoiners(parent, cell, waiting)
        return cell.current
    }

    /**
     * Where the step a pick arriving at [parent] takes ([settle]) would leave each of [parent]'s
     * active children in [ledger]: its effective position, wake clamp included, in ticks on its own
     * grid. Changes nothing, on this allocator or in [ledger]. The read [ShareLine] takes at every
     * competing parent of a tree at once.
     *
     * The step at a parent reads and writes only its children's entries and its own clock, so it
     * runs here on a copy of just those ([scopedTo]): a line over a tree of `n` edges copies each
     * entry once, where a copy of the whole memory per parent cost `n²` (on the JVM, 2,000 groups
     * of three lanes took 1.6 s that way and 24 ms this one). Leaving out the entries of children
     * that have left changes nothing a present child reads; the step would only forget them.
     *
     * [Placement.Refused] when the children's weights fail [weightsFitTheBudget], so no pick there
     * can be computed, or the step would pass the [POSITION_LIMIT].
     */
    internal fun place(parent: GroupId, ledger: EntitlementLedger, waiting: (AttachmentId) -> Demand): Placement {
        if (!childWeightsFit(ledger, parent)) return Placement.Refused
        val step = scopedTo(parent, ledger.activeChildren(parent).map { it.attachment })
        val cell = ValueCell(ledger)
        if (step.settleJoiners(parent, cell, waiting) == Landing.PastLimit) return Placement.Refused
        return Placement.Placed(step.policyEdges(cell.current, parent, waiting).associate { it.record.id to HeddlePolicy.effectiveTicksWide(it) })
    }

    /**
     * An allocator that remembers what this one does about [children], [parent]'s active children,
     * and [parent]'s clock, and nothing else: all the step at [parent] reads.
     */
    private fun scopedTo(parent: GroupId, children: List<AttachmentId>): FairShareAllocator = FairShareAllocator(self, policy).also { s ->
        for (edge in children) {
            wakeOffsets[edge]?.let { s.wakeOffsets[edge] = it }
            demandingObserved[edge]?.let { s.demandingObserved[edge] = it }
            served[edge]?.let { s.served[edge] = it }
            departures[edge]?.let { s.departures[edge] = it }
            shiftRemainders[edge]?.let { s.shiftRemainders[edge] = it }
        }
        clocks[parent]?.let { s.clocks[parent] = it }
    }

    /** What [place] found at a parent: each active child's position after the step, or that no step could be taken. */
    internal sealed interface Placement {
        /** The step landed: each active child's effective position, by its edge, in ticks on its own grid. */
        class Placed(val positions: Map<AttachmentId, Int128>) : Placement

        /** The weights fail the budget, or the step would pass the [POSITION_LIMIT]. */
        data object Refused : Placement
    }

    /**
     * Seat every unseated active child of [parent] in [ledger], at the joiners' front ([joinFront])
     * with the unseated as the joiners, **without** observing anything: the wake memory is left
     * exactly as it was. For a delegation that is not a pick, which must still never cross an
     * unseated edge, and which has no fresh view of who is waiting to observe. Returns [ledger]
     * itself when nothing under [parent] is unseated.
     */
    internal fun seatUnseated(
        parent: GroupId,
        ledger: EntitlementLedger,
        demand: (AttachmentId) -> Demand,
    ): EntitlementLedger {
        val edges = policyEdges(ledger, parent, demand)
        val unseated = edges.filter { it.gauge == null }.mapTo(HashSet()) { it.record.id }
        if (unseated.isEmpty()) return ledger
        val front = joinFront(edges, joiners = unseated)
        val seats = seatPatch(ledger, unseated, front)
        if (seats !is Seats.Fit) return ledger
        val patch = seats.patch ?: return ledger
        val seated = ledger.piece(patch.delta)
        // The seats are landings too: kept only if the parent's load still fits (design §7.1).
        return if (loadFitsAt(seated, parent, demand)) seated else ledger
    }

    /**
     * [parent]'s **current virtual time** `V` in [ledger] — this allocator's front (design §7.2;
     * issue #1688). `null` when [parent] has no active children in [ledger], and `null` too when
     * their weights fail [weightsFitTheBudget], where no front can be computed and [schedule]
     * answers [AllocationOutcome.WeightBudget] (#3353).
     *
     * A **diagnostic**: it depends on [demand] and on this allocator's non-replicated wake clamps,
     * so two peers legitimately read different values at the same instant.
     */
    @Deprecated("A Rational read sticks at the ceiling once nothing rebases (design §7.1); read virtualTimeWide(ledger, parent, demand), a VirtualTime, instead.", ReplaceWith("virtualTimeWide(ledger, parent, demand)"))
    public fun virtualTime(
        ledger: EntitlementLedger,
        parent: GroupId,
        demand: (AttachmentId) -> Demand,
    ): Rational? =
        if (childWeightsFit(ledger, parent)) heldFront(policyEdges(ledger, parent, demand)) else null

    /**
     * [virtualTime] as a [VirtualTime], exact and never held (design §7.1): the demanding
     * children's weighted mean, or the furthest-ahead child when none is demanding. `null` as
     * [virtualTime] answers it, and over a merged load whose sum leaves 128 bits.
     */
    public fun virtualTimeWide(
        ledger: EntitlementLedger,
        parent: GroupId,
        demand: (AttachmentId) -> Demand,
    ): VirtualTime? =
        if (childWeightsFit(ledger, parent)) HeddlePolicy.frontWide(policyEdges(ledger, parent, demand)) else null

    /**
     * [HeddlePolicy.front] over [edges], each position read inside its window, for a diagnostic
     * (#3353), as the public reads hold it ([inWindow]): a child a `cancel` walked back past its
     * floor reads at the floor, the penalty side, and one past its ceiling at the ceiling, so the
     * front cannot throw on how far a child has climbed. `null` when [edges] is empty.
     *
     * It reads the held positions directly. Holding an edge by lowering its wake offset, as this
     * once did, makes a negative offset, which [PolicyEdge] refuses: a child past its ceiling then
     * threw out of every read of its parent's front, and with no rebase that is every busy parent.
     */
    private fun heldFront(edges: List<PolicyEdge>): Rational? {
        if (edges.isEmpty()) return null
        val demanding = edges.filter { HeddlePolicy.isDemanding(it) }
        if (demanding.isEmpty()) return edges.maxOf { readVirtual(HeddlePolicy.effectiveTicksWide(it), it.record.weight) }
        val held = demanding.map { Int128.of(inWindow(HeddlePolicy.effectiveTicksWide(it), it.record.weight)) }
        // The exact mean of children held at their ceilings need not reduce into a `Long` Rational
        // (#3523 review): the deprecated read then saturates on the penalty side, at the furthest
        // held child, rather than throw. The wide read, virtualTimeWide, stays exact.
        return try {
            VirtualTime.mean(held, demanding.map { it.record.weight }).toRational()
        } catch (_: ArithmeticException) {
            demanding.maxOf { readVirtual(HeddlePolicy.effectiveTicksWide(it), it.record.weight) }
        }
    }

    /**
     * [edge]'s effective virtual service as a pick at [parent] would read it in [ledger]: its
     * [HeddlePolicy.virtualService] plus this allocator's stored wake clamp. `null` when [edge] is
     * not an active child of [parent]. A diagnostic, like [virtualTime]: it observes nothing.
     */
    /** [effectiveVirtualService] as a [VirtualTime], exact and never held. */
    internal fun effectiveVirtualTime(ledger: EntitlementLedger, parent: GroupId, edge: AttachmentId): VirtualTime? =
        policyEdges(ledger, parent, demand = { Demand.NONE })
            .firstOrNull { it.record.id == edge }
            ?.let { VirtualTime.of(HeddlePolicy.effectiveTicksWide(it), it.record.weight) }

    internal fun effectiveVirtualService(ledger: EntitlementLedger, parent: GroupId, edge: AttachmentId): Rational? =
        policyEdges(ledger, parent, demand = { Demand.NONE })
            .firstOrNull { it.record.id == edge }
            ?.let { readVirtual(HeddlePolicy.effectiveTicksWide(it), it.record.weight) }

    /**
     * The highest effective position among [parent]'s active children in [ledger], each as a
     * fraction of its own [positionCeiling]: how far the parent has climbed. Nothing rebases it
     * back down (#3472), so a busy parent passes `1` in time, and the public virtual-time reads
     * there saturate at the ceiling; no pick is affected, since a pick orders the wide position.
     * Zero when [parent] has no active children. A diagnostic that reads this allocator's wake clamps,
     * so a caller sharing the allocator must hold whatever lock guards it.
     */
    @HeddleInternalApi
    public fun peakPositionFraction(
        ledger: EntitlementLedger,
        parent: GroupId,
        demand: (AttachmentId) -> Demand,
    ): Double = policyEdges(ledger, parent, demand).maxOfOrNull { edge ->
        HeddlePolicy.effectiveTicksWide(edge).toDouble() / positionCeiling(edge.record.weight).toDouble()
    } ?: 0.0

    /**
     * Build the policy input at [parent] from [s] and run one [HeddlePolicy.pick].
     *
     * **An unseated edge is not a candidate** (issue #1752). [settleJoiners] seats every active
     * child before the first pick, but it and each pick are separate writes, and nothing stops a
     * remote `Activate` from putting a fresh, gauge-absent edge into [s] partway through the loop.
     * Such an edge reads `ev = baseIssued / w = 0`, which makes it maximally eligible; and if it
     * won, [EntitlementLedger.delegate] would write its checkpoint at that origin,
     * [EntitlementLedger.seat] would then refuse it forever (a gauge exists), and the componentwise
     * join could never lift a position that is already the minimum. One merge landing at the wrong
     * instant would hand a child permanent lifetime credit. Deferring it to the next [schedule]
     * costs one round and cannot.
     */
    private fun pickOne(
        s: EntitlementLedger,
        parent: GroupId,
        earmarked: Long,
        demand: (AttachmentId) -> Demand,
    ): Grant? {
        val localHoldings = s.holdings(parent, self) - earmarked
        if (localHoldings <= 0L) return null
        val edges = policyEdges(s, parent, demand).filter { it.gauge != null }
        if (edges.isEmpty()) return null
        return HeddlePolicy.pick(edges, policy, localHoldings)
    }

    /**
     * The policy's view of [parent]'s active children in [s]: each edge's immutable record, its
     * parent-facing summary, its demand, and this peer's stored §10.6 wake clamp. An edge whose
     * record has diverged is dropped — the policy refuses inconsistent inputs rather than schedule
     * against them.
     *
     * Its [PolicyEdge.baseIssued] is the **base** issuance, the [Gauge]'s fold axis, and not the
     * effective [EdgeSummary.issued] that relocation credit raises: reading the effective value
     * would make a relocation-receiving edge appear to have consumed its whole re-homed strand the
     * instant it arrived (issue #1752, F2).
     */
    private fun policyEdges(
        s: EntitlementLedger,
        parent: GroupId,
        demand: (AttachmentId) -> Demand,
    ): List<PolicyEdge> {
        return s.activeChildren(parent).mapNotNull { summary ->
            val record = s.record(summary.attachment) ?: return@mapNotNull null
            PolicyEdge(
                record = record,
                summary = summary,
                demand = demand(summary.attachment),
                gauge = s.gauge(summary.attachment),
                baseIssued = s.baseIssuance(summary.attachment),
                offsetTicks = wakeOffsets[summary.attachment] ?: Int128.ZERO,
            )
        }
    }

    /**
     * Seat the **newborns** and clamp the **wakers** under [parent] — the two kinds of joiner —
     * against a single front, [joinFront]. A waker this peer saw stop asking is a **returner**: it
     * is moved by how far its parent's net-service clock ran while it was away ([runClock],
     * [moveReturners], #3376) rather than clamped. It is counted in the front the others are seated
     * at or clamped to, where it lands ([landed], #3446): that is where it competes from, so it is
     * not reading from further back the way the joiners below are.
     *
     * **One front, one exclusion set, and that is the whole point** (issue #1752). Both joiners are
     * measured against "where the competing set is right now", and each is, in its own way, reading
     * from further back than that: a newborn carries no [Gauge] and so reads from its own origin,
     * the furthest back anything can read, while a waker is still carrying the stale virtual service
     * it slept on and this round's clamp has not been computed yet. Leave *either* in the mean and
     * it drags the front down to meet itself; leave both in and they average each other's staleness
     * and both bank the difference. Seating them in two passes over two fronts is the same defect
     * wearing a schedule: with a runner at `ev = 200` and a waker at `0`, a seat-then-clamp order
     * seats a newborn at `100` and then clamps the waker to `mean(200, 100) = 150`, when both belong
     * at `200`. The newborn's share of that is **permanent** — it is frozen into the [Gauge]'s
     * position, which the join only ever ratchets up — which is exactly the §10.5 lifetime credit
     * the seat exists to deny.
     *
     * A cohort of newborns with nobody else at the parent is seated level, at the furthest-ahead
     * origin among them, which for fresh edges is zero. That is not a guess:
     * [EntitlementLedger.seat] stores `max(front, baseIssued / w)`, so a front at an edge's own
     * origin seats it exactly where it already reads, pinning it without moving it. Pinning is the
     * point — it stops a sibling that happens to be served first from becoming the front the
     * *others* are then seated behind, which is how a cohort of newborns would otherwise be split by
     * nothing but pick order. And if this view is merely stale rather than genuinely empty, the
     * componentwise join repairs it: a better-informed peer's higher position wins the `max`, so the
     * low reading is absorbed rather than frozen.
     *
     * Publishes at most one patch.
     *
     * **It is kept only if it fits the [POSITION_LIMIT]** (design §7.1). The step is worked out on a
     * [fork] and a copy of the ledger value, and adopted only when every value it formed stayed in
     * 128 bits, the parent's load after it is at most the limit, and its clock is within the limit
     * of zero. Otherwise it answers [Landing.PastLimit] and writes nothing at [parent], the wake
     * memory included.
     */
    private fun settleJoiners(parent: GroupId, ledger: LedgerCell, demand: (AttachmentId) -> Demand): Landing {
        val trial = fork()
        val cell = ValueCell(ledger.current)
        val landed = try {
            trial.landJoiners(parent, cell, demand) && trial.stepFits(parent, cell.current, demand)
        } catch (_: ArithmeticException) {
            // A value the step formed left 128 bits: past the limit, refused rather than thrown.
            false
        }
        if (!landed) return Landing.PastLimit
        cell.written?.let { delta -> ledger.mutateOrSkip { Patch(delta) } }
        adoptMemoryOf(trial)
        return Landing.Kept
    }

    /** Where a seat and wake step at a parent stands against the [POSITION_LIMIT]. */
    internal enum class Landing {
        /** Kept: it fits, and it was written. */
        Kept,

        /** Past the limit: nothing was written. */
        PastLimit,
    }

    /**
     * Whether [parent]'s state in [s], as this allocator reads it, is one a pick may take its step
     * in: the load of its active children's effective positions at most the [POSITION_LIMIT], and
     * its clock within the limit of zero. A load that cannot be formed at all, which only a merged
     * or restored state can hold, does not fit.
     */
    private fun stepFits(parent: GroupId, s: EntitlementLedger, demand: (AttachmentId) -> Demand): Boolean {
        val clock = clocks[parent] ?: Int128.ZERO
        if (clock > POSITION_LIMIT || clock < Int128.ZERO - POSITION_LIMIT) return false
        return loadFitsAt(s, parent, demand)
    }

    /**
     * Whether [edge], active in [s], could be seated level with the furthest-ahead of its seated
     * siblings and leave its parent's load within the [POSITION_LIMIT] (design §7.1). Every sibling
     * not yet seated is counted where it will be seated too, at that same front on its own grid, so
     * two joiners that each fit alone but not together are told apart: the second is refused. The
     * same test a pick's step runs, asked early, for [LocalHeddle.apply]; it is not the guarantee,
     * which is the step's own.
     */
    internal fun seatFitsBesideFurthest(s: EntitlementLedger, edge: AttachmentId): Boolean = try {
        val record = checkNotNull(s.record(edge)) { "an activated edge ${edge.value} has no record" }
        val siblings = policyEdges(s, record.parent) { Demand.NONE }.filter { it.record.id != edge }
        val (seated, joining) = siblings.partition { it.gauge != null }
        if (seated.isEmpty()) {
            true
        } else {
            val top = seated.maxWith { x, y ->
                compareVirtualWide(HeddlePolicy.effectiveTicksWide(x), x.record.weight, HeddlePolicy.effectiveTicksWide(y), y.record.weight)
            }
            val front = VirtualTime.of(HeddlePolicy.effectiveTicksWide(top), top.record.weight)
            val landings = (joining.map { it.record.weight } + record.weight).map { front.ticksForWide(it) }
            landings.all { it is Ticks.Fits } &&
                loadFits(seated.asSequence().map(HeddlePolicy::effectiveTicksWide) + landings.asSequence().map { (it as Ticks.Fits).value })
        }
    } catch (_: ArithmeticException) {
        false
    }

    /** Whether the load of [parent]'s active children in [s] fits the [POSITION_LIMIT]. */
    private fun loadFitsAt(s: EntitlementLedger, parent: GroupId, demand: (AttachmentId) -> Demand): Boolean =
        try {
            loadFits(policyEdges(s, parent, demand).asSequence().map(HeddlePolicy::effectiveTicksWide))
        } catch (_: ArithmeticException) {
            false
        }

    /** [settleJoiners]' step itself, on this allocator: `false` when a landing it formed was past the limit. */
    private fun landJoiners(parent: GroupId, ledger: LedgerCell, demand: (AttachmentId) -> Demand): Boolean {
        val edges = policyEdges(ledger.current, parent, demand)
        forgetDepartedChildren(edges, ledger.current, parent)
        if (edges.isEmpty()) {
            clocks.remove(parent)
            return true
        }

        val clock = markDepartures(parent, edges)
        val unseated = edges.filter { it.gauge == null }.mapTo(HashSet()) { it.record.id }
        val wakers = edges.filter { isWaker(it) }.mapTo(HashSet()) { it.record.id }
        // A waker this peer saw leave comes back by the clock; one it never saw leave is clamped.
        val returners = edges.filter { isReturner(it) }.mapTo(HashSet()) { it.record.id }
        val joiners = HashSet(unseated).apply { addAll(wakers) }
        if (joiners.isNotEmpty()) {
            val shifts = edges.filter { it.record.id in returners }.associate { it.record.id to shiftOf(it, clock) }
            // A returner is counted in the front where it lands, not left out as a joiner (#3446).
            val front = joinFront(landed(edges, shifts), joiners - returners)
            if (unseated.isNotEmpty()) {
                val seats = seatPatch(ledger.current, unseated, front)
                if (seats !is Seats.Fit) return false
                seats.patch?.let { patch -> ledger.mutateOrSkip { patch } }
            }
            // An unseated waker is a newborn: its seat puts it at the front, and a clamp would count
            // the front twice (#3454 review).
            if (!clampWakers(edges, wakers - returners - unseated, front)) return false
            moveReturners(edges, shifts)
        }
        for (edge in edges) demandingObserved[edge.record.id] = HeddlePolicy.isDemanding(edge)
        return true
    }

    /**
     * The front every one of [joiners] is seated at or clamped to (design §7.2, #3353).
     *
     * - **Some child that is not joining is demanding:** the weighted mean over those children
     *   ([HeddlePolicy.frontTime] over the demanding non-joiners). The joiners come level with the
     *   set they are about to compete in, and none of them is averaged into it.
     * - **Otherwise**, the UNIFIED rule: the furthest-ahead child at the parent, counting idle
     *   children, the joiners themselves, and each newborn at its own origin. There is no competing
     *   set to come level with, so everyone joining starts level with whoever has had the most.
     *   This can cost a joiner a turn; it can never give one.
     *
     * It replaces three older rules: the maximum over the idle non-joiners alone, a `null` front
     * when only joiners were left, and raising a front to the newborns' origins whenever newborns
     * joined beside wakers. The first let a waker come back level with an idle bystander far behind
     * its sibling: with `a` at 100 and `b` at 0 both waking beside `c` idle at 10, `b` was clamped
     * to 10 and took 95 of the next 100.
     *
     * The third is gone from the competing case too: a newborn beside demanding runners is seated
     * level with them, never raised to its own origin.
     *
     * Either way the front is wide and exact (#3353): a child past its ceiling moves it exactly as
     * far as it should, and the pick that follows orders it exactly.
     */
    private fun joinFront(edges: List<PolicyEdge>, joiners: Set<AttachmentId>): VirtualTime {
        val competing = edges.filter { it.record.id !in joiners && HeddlePolicy.isDemanding(it) }
        if (competing.isNotEmpty()) {
            return checkNotNull(HeddlePolicy.frontTime(competing)) { "a non-empty demanding set has a front" }
        }
        val top = edges.maxWith { a, b ->
            compareVirtualWide(HeddlePolicy.effectiveTicksWide(a), a.record.weight, HeddlePolicy.effectiveTicksWide(b), b.record.weight)
        }
        return VirtualTime.of(HeddlePolicy.effectiveTicksWide(top), top.record.weight)
    }

    /**
     * One patch seating each of [unseated] at [front], each on its own grid, `⌈V · w · G⌉`, wide
     * and exact (#3353). [EntitlementLedger.seatAtWide] refuses an edge that already carries a
     * gauge, so an id whose seat arrived between the front being read and this patch being built is
     * simply skipped rather than re-seated.
     *
     * The seats are gathered into one gauge map and assembled once. Joining them one at a time
     * copied the growing patch on every seat, which made a cohort of `n` newborns cost `n²` (5,000
     * of them took 4.9 s on a Kotlin/Native debug build, #3173).
     */
    private fun seatPatch(
        s: EntitlementLedger,
        unseated: Set<AttachmentId>,
        front: VirtualTime,
    ): Seats {
        val gauges = LinkedHashMap<AttachmentId, Gauge>()
        for (id in unseated.sorted()) {
            val record = s.record(id) ?: continue
            // On the newborn's own grid (#3353); a seat past the position limit is not made (design §7.1).
            val absolute = when (val ticks = front.ticksForWide(record.weight)) {
                is Ticks.Fits -> ticks.value
                Ticks.PastLimit -> return Seats.PastLimit
            }
            val seat = s.seatAtWide(id, absolute) ?: continue
            gauges[id] = checkNotNull(seat.delta.gauge(id)) { "a seat patch for ${id.value} carries no gauge" }
        }
        return Seats.Fit(if (gauges.isEmpty()) null else Patch(EntitlementLedger.of(gauges = gauges)))
    }

    /** [seatPatch]'s answer: the seats, `null` when none was needed, or a seat past the limit. */
    private sealed interface Seats {
        class Fit(val patch: Patch<EntitlementLedger>?) : Seats

        data object PastLimit : Seats
    }

    /**
     * Forget the clamp state of edges that have left [parent]'s active set. Entries for other
     * parents are untouched — both maps are keyed by attachment id across the whole tree.
     */
    private fun forgetDepartedChildren(edges: List<PolicyEdge>, s: EntitlementLedger, parent: GroupId) {
        val present = edges.mapTo(HashSet()) { it.record.id }
        val departed = demandingObserved.keys.filterTo(HashSet()) { id ->
            id !in present && s.record(id)?.parent == parent
        }
        demandingObserved.keys.removeAll(departed)
        wakeOffsets.keys.removeAll(departed)
        served.keys.removeAll(departed)
        departures.keys.removeAll(departed)
        shiftRemainders.keys.removeAll(departed)
    }

    /** Whether this peer last saw [edge] asking, and it is seated: a child its parent's clock counts. */
    private fun wasAsking(edge: PolicyEdge): Boolean = demandingObserved[edge.record.id] == true && edge.gauge != null

    /**
     * Whether [edge] is a **returner**: a waker ([isWaker]) this peer saw stop asking, so it holds a
     * departure against its parent's clock. A returner comes back moved by that clock ([moveReturners]);
     * every other waker is clamped ([clampWakers]) (#3376).
     */
    private fun isReturner(edge: PolicyEdge): Boolean = isWaker(edge) && edge.record.id in departures

    /**
     * Run [parent]'s clock over [edges] ([runClock]), then mark every child this peer last saw asking
     * that no longer is as gone at the clock it returns: a departure, and an observation that it is
     * not asking (#3376). [settleJoiners] calls it at a call's entry and [schedule] before each later
     * pick, so a child that a grant fills is seen leaving at that grant, and its siblings' grants
     * after it run the clock at their weight alone (#3416 review). A departure is the clock's reading
     * when this peer saw the child stop, so seeing it sooner changes only what it is measured from.
     *
     * Only the demand→idle edge is observed here. A child that asks again partway through a call is
     * left for the next call's entry, where [settleJoiners] moves it.
     */
    private fun markDepartures(parent: GroupId, edges: List<PolicyEdge>): Int128 {
        val clock = runClock(parent, edges)
        for (edge in edges) {
            if (!wasAsking(edge) || HeddlePolicy.isDemanding(edge)) continue
            departures[edge.record.id] = clock
            demandingObserved[edge.record.id] = false
        }
        return clock
    }

    /**
     * Run [parent]'s net-service clock over what its children kept since it last ran, and return
     * where it stands now (design §7.2, #3376): [clockStep] over the children this peer last saw
     * asking ([wasAsking]), and then each child's `baseIssued − returned` recorded in [served] as
     * what the next run measures from. A child that was not asking adds nothing, its own refunds
     * included: those already lower its own position, and counting them here too would credit it
     * twice.
     *
     * Each step is rounded up, so the clock runs fast by under one of its ticks a run, and a
     * returner is moved that sliver further: the penalty side. A shift is a distance, so no position
     * enters it.
     */
    private fun runClock(parent: GroupId, edges: List<PolicyEdge>): Int128 {
        val step = clockStep(parent, edges.filter(::wasAsking), served)
        for (edge in edges) served[edge.record.id] = checkedSub(edge.baseIssued, edge.summary.returned)
        val before = clocks[parent] ?: Int128.ZERO
        if (step == Int128.ZERO) return before
        return (before + step).also { clocks[parent] = it }
    }

    /**
     * A returner's shift ([shiftOf]): the [ticks] it moves forward on its own grid, and the
     * [remainder] those ticks ran past its parent's clock, for its next return to take off; `null`
     * when the shift landed exactly.
     */
    private class Shift(val ticks: Int128, val remainder: ShiftRemainder?)

    /**
     * [edges] with each child that [shifts] names moved by its shift: where every returner will
     * stand once [moveReturners] lands, for [joinFront] to count it there (#3446). A shifted
     * position is not stale in the sense of #1752, which is about a joiner reading from further back
     * than it will compete: a returner competes from exactly here, as far ahead of or behind its
     * siblings as it left, so leaving it out of the front seated a newborn or clamped a waker
     * against a set that did not include a child it was about to compete with.
     */
    private fun landed(edges: List<PolicyEdge>, shifts: Map<AttachmentId, Shift>): List<PolicyEdge> =
        if (shifts.isEmpty()) {
            edges
        } else {
            edges.map { edge -> shifts[edge.record.id]?.let { edge.copy(offsetTicks = edge.offsetTicks + it.ticks) } ?: edge }
        }

    /**
     * How far [edge] comes back moved (#3376, #3416 review): the clock's run from its departure to
     * [clock] on its own grid, less what its earlier shifts already ran past the clock, rounded up
     * to a whole tick and never below zero.
     *
     * On a grid of weight `n / d` the run is `(clock − left) · n / (CLOCK_GRID · d)` ticks, which is
     * a fraction in general. The carried remainder `r` comes off it, the result rounds up, and what
     * the rounding added, `⌈run − r⌉ − (run − r)`, is carried to the next return. So a child's
     * shifts add up to its runs plus its last remainder, which is in `[0, 1)` tick: never credit, and
     * nothing builds up however often it returns.
     *
     * Two simpler rules each drift. The difference of the two ends, each read on the grid rounded
     * up, errs by where those ends fall between ticks, and that does not telescope: a repeating
     * pattern of bursts locks the ends into one phase, so the error comes back the same every
     * return and adds up, toward credit as readily as penalty. Up to a seventh of a tick a return
     * was found toward credit, which §10.5 forbids. One ceiling of each run never credits, but it
     * rounds every return up, about half a tick a return, measured on `BurstLagTest`'s 3:1 rigs as
     * 66 units of a fair 75 to the light child over 300 bursts.
     *
     * A run that went backward, refunds outweighing grants while the child was away, moves it by
     * nothing, since a shift only ever gives a turn up. Its remainder is still carried, so what that
     * drops is the backward run alone, the penalty side.
     *
     * A carried remainder is always on [edge]'s own grid: an edge's weight is part of its record,
     * so a reweight is a new edge, which starts with no remainder. The denominator a remainder
     * carries is therefore checked here, never compared, and one taken at another denominator is a
     * memory no allocator wrote.
     */
    private fun shiftOf(edge: PolicyEdge, clock: Int128): Shift {
        val id = edge.record.id
        val left = checkNotNull(departures[id]) { "a returner with no departure: ${id.value}" }
        val w = edge.record.weight
        val d = w.denominator
        val carried = shiftRemainders[id]?.also { r ->
            check(r.denominator == d) { "a shift remainder on ${id.value} was taken at denominator ${r.denominator}, not its weight's $d" }
        }?.numerator ?: Int128.ZERO
        // The run times the weight's numerator, in units of 1 / K of a tick with K = CLOCK_GRID · d,
        // less the carry. It is never formed whole: run · n can leave 128 bits long before the shift
        // it stands for nears the position limit, which refused a return sooner than design §7.1
        // allows. So it is split as q · K + r, q by one 192-bit mulDiv and r by modular arithmetic,
        // and only the shift and its remainder, each a fraction of the run, are formed.
        // K fits a Long for every weight the budget accepts (denominators at most 30,719).
        val k = checkedMul(CLOCK_GRID, d)
        val run = clock - left
        val q = wide(mulDiv(run, w.numerator, k, Rounding.FLOOR), clock, w)
        val r = productMod(run, w.numerator, k)
        val c = checkNotNull(carried.toLongOrNull()) { "a shift remainder on ${id.value} past its grid: $carried" }
        // run · n − c = q · K + (r − c), with r − c in (−K, K): the shift is ⌈that / K⌉.
        val extra = r - c
        val ticks = if (extra > 0L) q + Int128.of(1L) else q
        // What the shift ran past the clock, `ticks · K − (run · n − c)`, in [0, K).
        val past = if (extra > 0L) k - extra else -extra
        val remainder = if (past == 0L) null else ShiftRemainder(Int128.of(past), d)
        return Shift(maxOf(Int128.ZERO, ticks), remainder)
    }

    /** [value], or an [ArithmeticException] naming the clock read when it overflowed 128 bits. */
    private fun wide(value: Int128?, clock: Int128, w: Weight): Int128 =
        value ?: throw ArithmeticException("Int128 overflow: a clock of $clock read at weight $w")

    /**
     * Move each returner [shifts] names forward by its shift, carry its remainder, and clear its
     * departure (#3376). The shift adds to the wake offset it already carries, so it only ever gives
     * a turn up.
     */
    private fun moveReturners(edges: List<PolicyEdge>, shifts: Map<AttachmentId, Shift>) {
        for (edge in edges) {
            val id = edge.record.id
            val shift = shifts[id] ?: continue
            writeWakeOffset(id, edge.offsetTicks + shift.ticks)
            departures.remove(id)
            val remainder = shift.remainder
            if (remainder == null) shiftRemainders.remove(id) else shiftRemainders[id] = remainder
        }
    }

    /**
     * Enforce §10.6 — "no unlimited idle credit" — for [edges] (issue #1695).
     *
     * Every child that this peer has previously observed **not** competing and that competes now
     * has crossed the idle→demand edge the design §7.2 clamp is defined on. [settleJoiners] hands
     * this only the **seated** wakers it holds no departure for, the ones it never saw stop asking: a
     * child it first saw idle. An unseated waker is a newborn, and its seat is what puts it at the
     * front; clamped as well, it would count the front twice, and land near double it once an empty
     * first round had dropped the seat that call wrote and kept the clamp (#3454 review). A waker it
     * saw stop comes back by its parent's clock instead ([moveReturners],
     * #3376), which keeps the lag a short burst leaves where the clamp would forgive it. Each waker
     * here is clamped forward to [HeddlePolicy.front] — the front of the set it is rejoining — so it
     * re-enters level with the children that kept running instead of spending the whole idle
     * interval in one burst. The clamp is a *forward* offset only: it can never advance a child's
     * turn, only give one up.
     *
     * All wakers are excluded from the front together, not just each from its own: two siblings
     * waking in the same round would otherwise average each other's stale virtual service into
     * the front and both keep the credit. When no child outside the joiners is demanding, the front
     * is the furthest-ahead child at the parent, the joiners and the idle children included
     * ([joinFront], §7.2), so the wakers are clamped level with each other, with any newborn, and
     * with the child that has had the most: the bound that can only ever give a turn up.
     *
     * **The clamp is written, not joined with an offset already stored, because there is none**
     * (#1714, #3416 review). #1714 was a second wake refunding the first one's clamp: the front is a
     * weighted mean over whoever competes *now*, so a child clamped once, run and asleep again could
     * re-wake beside a starved sibling into a lower front, and a replaced offset handed back the
     * credit the first clamp took. A `max` join with the stored offset fixed it. The invariant that
     * now makes the join `max(0, computed) = computed` is: **no waker handed here holds an offset.**
     * It rests on three facts. An offset is only ever written on a seated child: a clamp skips an
     * unseated one, and a return needs a departure, which [markDepartures] stamps only on a child
     * with a gauge. A gauge, once a child has one, stays until the edge is forgotten. And the call
     * that writes an offset ends seeing the child asking, a memory an empty first round keeps along
     * with the offset. So that child's next stop stamps a departure, and its next wake is a return by
     * the clock ([moveReturners]), which only adds to the offset. A departure clears only on that
     * return, which sees the child asking again, or with the edge forgotten, which drops the offset
     * too. A refused first round keeps none of it. A waker holding an offset and no departure is a
     * memory no allocator writes: the snapshots v0.7.4 wrote carried one, and this release does not
     * read them (#3353).
     *
     * Two things lower a stored offset, and neither returns a turn. An edge is forgotten when it
     * leaves the active set, which [EntitlementLedger.activate][EntitlementLedger.activate] makes
     * permanent (closure dominance, §10.10), so a cleared clamp can never come back to an edge that
     * will compete again. Nothing else rewrites one: no rebase moves a parent's positions (#3472).
     * A clamp is the front less the raw position at the wake, so for a child that idled long it is
     * large; it is wide, so it can be, without nearing any ceiling.
     *
     * The two edges are sampled at different moments. **Idle→demand** is seen at a [schedule]
     * call's entry, before any grant lands, and only there. **Demand→idle** is seen there and again
     * before each later pick of the call ([markDepartures]), so a child that a grant fills is seen
     * leaving at that grant. That second sample makes no spurious clamp: it stamps a departure, so
     * the child comes back by the clock, and a child that stops and asks again with no grant
     * between moves by exactly nothing (#3416 review).
     *
     * **§10.6 is therefore enforced only modulo that sampling** (issue #1715). §7.2 defines the
     * clamp on an idle→demand *event* this peer has actually observed, and a window that opens and
     * closes between two samples is not seen. Single-peer, a child that stops partway through a
     * call is seen at the grant that stopped it, and an idle interval between calls is seen at the
     * next entry that observes: a pick, or a `LocalHeddle` charging call, which observes its waiting
     * set, with the leaf it charges counted waiting, before its charge lands (#3471). So a sibling
     * that idled through the charge is seen leaving before the charge moves its parent's clock, and
     * does not bank it; that holds when the charge is an overrun on the charged leaf's last job too.
     * Two windows escape. One is multi-peer: a child may
     * idle and re-demand between this peer's rounds while *another* peer's grants advance its
     * siblings, so this peer sees demanding→demanding and moves nothing. Accepted by design: the
     * resulting catch-up burst is capped by this peer's holdings and the §8.2 caps, any peer that
     * did observe the window acts on it independently, and wake memory is scheduler-local anyway,
     * so divergence between peers is already tolerated. The other is a child that stops and asks
     * again within one call, which needs a merge to lower its outstanding mid-call: it competes
     * from where it stands, and at the next entry it is moved by the clock's whole run since it
     * stopped on top of the grants it took meanwhile, which is the penalty side.
     */
    private fun clampWakers(edges: List<PolicyEdge>, wakers: Set<AttachmentId>, front: VirtualTime): Boolean {
        for (edge in edges) {
            if (edge.record.id !in wakers) continue
            val weight = edge.record.weight
            // Wide, both sides (#3353): a waker idle for long sits far below its siblings, and a
            // clamp narrowed before it is taken would leave it there.
            val raw = HeddlePolicy.rawTicks(edge)
            val frontTicks = when (val ticks = front.ticksForWide(weight)) {
                is Ticks.Fits -> ticks.value
                Ticks.PastLimit -> return false
            }
            val computed = HeddlePolicy.clampTicks(frontTicks = frontTicks, rawTicks = raw, sleeperCredit = policy.sleeperCredit)
            // Written, not joined: a seated waker with no departure holds no offset (see above).
            // Both sides are on the waker's grid, and the result is stored on it, so a stored offset
            // never carries a front's denominator into the next one (#3313).
            writeWakeOffset(edge.record.id, computed)
        }
        return true
    }

    /** Store [offset] as [edge]'s wake offset, or drop the entry when it is zero, the value an absent one reads as. */
    private fun writeWakeOffset(edge: AttachmentId, offset: Int128) {
        if (offset == Int128.ZERO) wakeOffsets.remove(edge) else wakeOffsets[edge] = offset
    }

    /**
     * A [LedgerCell] over a value, for [pickChild] and [schedule]'s first round: one caller, no other
     * writer. [written] joins every delta it applied, `null` until the first, so the writes land
     * elsewhere as one patch.
     */
    private class ValueCell(override var current: EntitlementLedger) : LedgerCell {
        var written: EntitlementLedger? = null
            private set

        override fun mutateOrSkip(transform: (EntitlementLedger) -> Patch<EntitlementLedger>?): Boolean {
            val patch = transform(current) ?: return false
            current = current.piece(patch.delta)
            written = written?.piece(patch.delta) ?: patch.delta
            return true
        }
    }

    private companion object {
        /** A backstop against a policy that never runs dry; one call never applies more grants. */
        const val MAX_ROUNDS_PER_CALL: Int = 100_000

        /** Clock ticks to a unit of virtual time: a child's [TICKS_PER_UNIT], times [CLOCK_GRID]. */
        const val CLOCK_TICKS_PER_UNIT: Long = TICKS_PER_UNIT * CLOCK_GRID

        /**
         * How far [parent]'s clock runs over what [asking] kept since [served] recorded it: the sum of
         * their `baseIssued − returned` since then over their total weight, in clock ticks, rounded
         * up. Zero when none was asking or none kept anything. A pure function of its inputs; the
         * caller decides who was asking ([wasAsking]) and records the new [served].
         */
        fun clockStep(parent: GroupId, asking: List<PolicyEdge>, served: Map<AttachmentId, Long>): Int128 {
            if (asking.isEmpty()) return Int128.ZERO
            var kept = 0L
            for (edge in asking) {
                val last = served[edge.record.id] ?: continue
                kept = checkedAdd(kept, checkedSub(checkedSub(edge.baseIssued, edge.summary.returned), last))
            }
            if (kept == 0L) return Int128.ZERO
            // kept / Σw = kept · L / S, with Σw = S / L over the common denominator L.
            val weights = asking.map { it.record.weight }
            val l = weights.fold(1L) { acc, w -> checkedMul(acc / gcdOf(acc, w.denominator), w.denominator) }
            val sum = weights.fold(0L) { acc, w -> checkedAdd(acc, checkedMul(w.numerator, l / w.denominator)) }
            return mulDiv(kept.timesWide(CLOCK_TICKS_PER_UNIT), l, sum, Rounding.CEILING)
                ?: throw ArithmeticException("Int128 overflow: a clock step of $kept units at ${parent.value}")
        }
    }
}

/**
 * What one [FairShareAllocator.schedule] call did at a parent: delegated something, found nothing to
 * delegate, or stopped because the parent cannot be ordered at all or its pick met the lifetime limit.
 *
 * The empty answers are separate arms because they clear differently. [NothingToDelegate] is
 * the ordinary answer, and it clears by itself once holdings or demand arrive. [WeightBudget] is a
 * property of the parent's children, and it lasts until those change. [LifetimeExhausted] lasts
 * while the full edge is picked.
 */
public sealed interface AllocationOutcome {

    /**
     * [grants] allocation rounds landed, each a delegation down the tree. Always positive: zero
     * grants is [NothingToDelegate], never `Delegated(0)`. A call that granted and then met a
     * refusal answers this, and the next call answers the refusal.
     */
    public data class Delegated(public val grants: Int) : AllocationOutcome {
        init {
            require(grants > 0) { "Delegated needs at least one grant, was $grants; zero is NothingToDelegate" }
        }
    }

    /**
     * The allocator ran and delegated nothing: it found no holdings to move toward any demand, no
     * child wants anything, or no child is seated yet.
     */
    public data object NothingToDelegate : AllocationOutcome

    /**
     * The pick at [parent] chose an edge whose effective issuance the grant would carry past the
     * lifetime watermark, `Long.MAX_VALUE` less [LocalHeddle.LIFETIME_HEADROOM], on this peer's view
     * (#3475). Nothing was written. It lasts while that edge is picked: a refusal moves no
     * position, so a full edge that is next in line stalls its parent, as in [LocalHeddle.acquire].
     */
    public data class LifetimeExhausted(public val parent: GroupId) : AllocationOutcome

    /**
     * [parent]'s active children carry weights that fail [weightsFitTheBudget]: a product a pick
     * forms leaves `Long`, so no pick there can be computed, or the set is too fine for one full
     * lease (#3353). Nothing was written. A replicated ledger can hold such a set
     * however carefully each write was checked: two peers can each activate a child that fits on its
     * own view, and the merge of the two does not fit.
     */
    public data class WeightBudget(public val parent: GroupId) : AllocationOutcome
}

/**
 * One [FairShareAllocator.pickChild] result: the [grant] to delegate, and the [ledger] it must be
 * delegated on, in which every active child of the parent is seated.
 *
 * @property ledger the input ledger with the newborns seated; nothing has been delegated yet.
 * @property grant the child edge picked and the trimmed amount the policy would grant it.
 */
public data class ChildPick(
    public val ledger: EntitlementLedger,
    public val grant: Grant,
)

/**
 * Clock ticks to one tick of a weight-1 child's grid: the least common multiple of 1 to 16. A clock
 * step divides by the asking children's total weight, so this makes a step exact for every total
 * whose factors divide it. Not every total: one with a prime factor above 13, or a prime power past
 * the clock's own grid's, `16 · CLOCK_GRID` (27, say), rounds each step up by under one clock tick,
 * the penalty side. A shift read on a
 * child's grid is a whole number of its ticks, and the fraction it rounds up by is carried to its
 * next return ([ShiftRemainder]), so a child's shifts never run more than one tick past the clock.
 */
internal const val CLOCK_GRID: Long = 720_720L
