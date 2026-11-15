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
 * wake. An id that is *absent* has never been observed and is therefore never treated as a wake: a
 * first observation carries no evidence the child was ever idle, and forfeiting a deficit accrued
 * under some other peer's scheduling would be a penalty this peer has no standing to impose.
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
    /** The wake clamps this peer computed, wide, in ticks of each edge's own grid (#3353). */
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
     * Whether [schedule] rebases a busy parent by raising its origin (#3353). On by default. **Test
     * only:** a test switches it off to run a twin that never rebases and compare the picks; a parent
     * with it off climbs past its position ceiling and picks exactly as the rebasing one does.
     */
    @HeddleInternalApi
    public var rebaseEnabled: Boolean = true

    /**
     * Whether a rebase keeps every child inside its window (#3353): on by default. **Test only:**
     * off, the rebase runs the rule before it, `Δ` at most the least-served demanding child's
     * position, a landing never held at its floor, and the front read on every child's grid. A test
     * runs a twin with it off to show the two rules pick alike wherever the old one never refused.
     */
    internal var windowRuleForTest: Boolean = true

    /**
     * How many joiners this allocator has landed at their floor rather than at the front (#3353):
     * seated or clamped; a returner held at its floor is not counted. The witness a test reads that its floor-landing rig fired. Not
     * part of [memory].
     */
    internal var floorLandingsForTest: Int = 0
        private set

    /**
     * How many rebases this allocator has made: the origin raises [schedule] landed, plus those it
     * [adopt]ed for [LocalHeddle]. The witness a test reads that its rig fired. Not part
     * of [memory], so an allocator built from one starts at zero.
     */
    @HeddleInternalApi
    public val rebases: Int get() = rebasesForTest

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
        it.rebasesForTest = rebasesForTest
        it.rebaseEnabled = rebaseEnabled
        it.windowRuleForTest = windowRuleForTest
        it.floorLandingsForTest = floorLandingsForTest
    }

    /**
     * How many rebases this allocator has [adopt]ed: the witness a test reads that its rig fired. Not
     * part of [memory], so an allocator built from one starts at zero.
     */
    internal var rebasesForTest: Int = 0
        private set

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
     * One state stops it, and it does not throw: active children whose weights fail
     * [weightsFitTheBudget] stop it before the seat step, since the replicated ledger can merge such
     * a set from writes that each fit (#3353). No position stops it: a pick orders the full 128-bit
     * position, however far a child stands from its parent's origin.
     *
     * ## A refused or empty call writes nothing
     *
     * The seat step and the first pick run together, on a [fork] of this allocator, inside **one**
     * [LedgerCell.mutateOrSkip] (#3353):
     * - the first round **refused** at the weight budget: no patch, and the fork is dropped, so no
     *   seat, no clamp and no observation is kept;
     * - the first pick found **nothing** to grant: no patch either, so the seats are dropped with
     *   it, but the fork's wake memory is kept, since it observed who was waiting. That is what
     *   [LocalHeddle] does, and it means an idle call writes nothing to a replicated ledger;
     * - the first pick **granted**: the seats and the delegation land as one patch, and the fork is
     *   kept.
     *
     * A first round may also raise [parent]'s origin, in the same patch, once a child there has
     * climbed past its [rebaseThreshold]: before its pick ([preSeatRaise] with joiners,
     * [raiseBeforePick] without), and after it ([raiseOriginBeneath]). So may each later round,
     * inside its own write. That rebase keeps a busy parent's positions near zero, where the public
     * virtual-time reads are exact, and it changes no pick (#3353): it moves every child alike, and no joiner seated,
     * clamped or returned under it is held at its floor, which every raise before a pick checks. A
     * joiner held at its floor with no raise is what the window rule changes ([landing]).
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
     *   children cannot be ordered, and
     *   [AllocationOutcome.NothingToDelegate] when it simply had nothing to grant.
     * @throws ArithmeticException when a grant would carry an edge's issuance past `Long`, the
     *   lifetime limit (#3475). In the first round that writes nothing, and the fork is dropped. In a
     *   round `k > 1` it keeps rounds `1..k−1`, which have already landed, unlike [LocalHeddle],
     *   whose calls change nothing when they throw.
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
            is FirstRound.Empty -> {
                adoptMemoryOf(opened.fork)
                return AllocationOutcome.NothingToDelegate
            }
            is FirstRound.Granted -> adoptMemoryOf(opened.fork)
        }
        var grants = 1
        while (grants < MAX_ROUNDS_PER_CALL) {
            var rebasedThisRound = false
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
                val cell = ValueCell(s)
                // Before the pick, so a child a merge carried far from its origin is brought back
                // down as soon as it is seen (#3353).
                val preRaised = raiseBeforePick(parent, cell, earmarked, demand)
                val grant = pickOne(cell.current, parent, earmarked, demand) ?: return@mutateOrSkip null
                if (preRaised) checkRaiseChangedNoPick(s, parent, earmarked, demand, grant)
                val raised = raiseOriginBeneath(parent, cell, earmarked, demand, grant)
                if (!cell.mutateOrSkip { it.delegate(self, grant.attachment, grant.amount) }) return@mutateOrSkip null
                rebasedThisRound = raised || preRaised
                cell.written?.let(::Patch)
            }
            if (!applied) break
            if (rebasedThisRound) rebasesForTest++
            grants++
        }
        // Progress first: a refusal in a later round follows a grant, so this call reports the
        // grants, and the next call meets the refusal in its first round and names it.
        return AllocationOutcome.Delegated(grants)
    }

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
        rebasesForTest = fork.rebasesForTest
        floorLandingsForTest = fork.floorLandingsForTest
    }

    /**
     * After [grant] was picked at [parent] on [cell]'s state, and before it is delegated: raise
     * [parent]'s origin if a child there has climbed past its [rebaseThreshold], and say whether it
     * did (#3353). The raise lands in [cell], so it travels in the same patch as the grant.
     *
     * The rule is #3329's, as amended, moved from raising folds to raising the origin:
     * - **measured on effective positions**, raw plus wake clamp, since that is what a pick orders;
     * - **any child past `R`** triggers it, idle ones included;
     * - **`Δ` is a whole multiple** of the least common multiple of `d / gcd(d, 16n)` over the children
     *   this peer sees, so `Δ · w · G` is whole ticks on every one of their grids and each comes down
     *   by exactly that;
     * - **`Δ` aims at the front** of the seated children this peer sees demanding, their weighted
     *   mean, and stops short wherever it would carry one of them below its [positionFloor]
     *   ([windowBound], #3353): a child behind the front goes below zero, inside its window;
     * - it runs **after the seat and wake step and only after a grant**, and the pick is checked
     *   unchanged on the raised state before the raise is kept.
     *
     * It rewrites no gauge and no wake clamp. Every child's position reads `Δ · w · G` lower, so the
     * mean over any set comes down by `Δ`, every `⌈V · w · G⌉` by exactly `Δ · w · G`, and every
     * clamp `front − raw` is unchanged. An idle child goes below zero, as far as it must, past its
     * floor too: its next wake clamp, taken wide, carries it back to the front.
     *
     * @throws IllegalStateException if the raised state would pick differently, a bug in the rule.
     */
    private fun raiseOriginBeneath(
        parent: GroupId,
        cell: ValueCell,
        earmarked: Long,
        demand: (AttachmentId) -> Demand,
        grant: Grant,
        eligible: (AttachmentId) -> Demand = demand,
    ): Boolean {
        if (!rebaseEnabled) return false
        val s = cell.current
        val delta = originStep(policyEdges(s, parent, demand)) ?: return false
        val patch = s.raiseOrigin(parent, s.origin(parent) + delta) ?: return false
        val again = pickOne(s.piece(patch.delta), parent, earmarked, eligible)
        check(again == grant) { "the origin rebase at ${parent.value} changed the pick from $grant to $again" }
        return cell.mutateOrSkip { patch }
    }

    /**
     * Raise [parent]'s origin in [cell] **before** a pick, when a child there is past its
     * [rebaseThreshold], and say whether it did (#3353). [schedule] runs it ahead of each pick with
     * no joiners, the seat-and-wake step's own raise ([preSeatRaise]) covering a round with them.
     * So a child that a merge or a delegation carried far from its origin is brought back down before
     * the pick reads it, rather than one grant later. It changes no pick, which
     * [checkRaiseChangedNoPick] checks.
     */
    private fun raiseBeforePick(parent: GroupId, cell: ValueCell, earmarked: Long, demand: (AttachmentId) -> Demand): Boolean {
        val delta = prePickRaise(cell.current, parent, demand) ?: return false
        val s = cell.current
        val patch = s.raiseOrigin(parent, s.origin(parent) + delta) ?: return false
        return cell.mutateOrSkip { patch }
    }

    /**
     * The `Δ` to raise [parent]'s origin by before a pick in [s] that seats and clamps nobody, or
     * `null`: [originStep] over [parent]'s children. A round with joiners leaves it to [preSeatRaise],
     * which reads the front they will land at.
     */
    private fun prePickRaise(s: EntitlementLedger, parent: GroupId, demand: (AttachmentId) -> Demand): Int128? {
        if (!rebaseEnabled) return null
        val edges = policyEdges(s, parent, demand)
        if (joinersOf(edges).isNotEmpty()) return null
        return originStep(edges)
    }

    /**
     * That a raise before the pick changed no pick: the pick on [s], unraised, is [grant] too. A bug
     * detector, as [checkPreSeatRaiseChangedNoPick] is, and never vacuous: the unraised pick orders
     * the same wide positions, so it always answers.
     *
     * @throws IllegalStateException if it picks anything else, a bug in the rule.
     */
    private fun checkRaiseChangedNoPick(
        s: EntitlementLedger,
        parent: GroupId,
        earmarked: Long,
        demand: (AttachmentId) -> Demand,
        grant: Grant,
    ) {
        val unraised = pickOne(s, parent, earmarked, demand)
        check(unraised == grant) { "the pre-pick rebase at ${parent.value} changed the pick from $unraised to $grant" }
    }

    /**
     * The `Δ` [raiseOriginBeneath] raises [edges]' parent's origin by, or `null` when it should not
     * move: no child is past its [rebaseThreshold], the step leaves `Long`, or a child in [boundBy] is
     * below one step.
     */
    private fun originStep(
        edges: List<PolicyEdge>,
        boundBy: List<PolicyEdge> = edges.filter { it.gauge != null && HeddlePolicy.isDemanding(it) },
    ): Int128? {
        if (edges.isEmpty() || boundBy.isEmpty()) return null
        // The front on the grid of every child it binds too: a light runner holding the front up
        // would otherwise put a heavy child joining it past its own ceiling (#3353 review, item A).
        // Only those: an idle child's grid is read again when it wakes, as a joiner (window rule).
        // An id set, so the test is linear in the children.
        val bound = if (windowRuleForTest) boundBy.mapTo(HashSet()) { it.record.id } else null
        val front = HeddlePolicy.frontTime(edges)
        val past = edges.any { edge ->
            val r = Int128.of(rebaseThreshold(edge.record.weight))
            HeddlePolicy.effectiveTicksWide(edge) >= r ||
                (front != null && (bound == null || edge.record.id in bound) && front.ticksForWide(edge.record.weight) >= r)
        }
        if (!past) return null
        // No guard at a child's ceiling: every read up to the effective position is wide and exact,
        // so a raise there brings the child down like any other, and is the one repair for a heavy
        // joiner seated past it by a delegation that is not a pick (#3353 round 2).
        val step = gridStep(edges) ?: return null
        if (!windowRuleForTest) return wholeSteps(boundBy.minOf { unitsOf(it) ?: return null }, step)
        val anchor = VirtualTime.mean(boundBy.map { HeddlePolicy.effectiveTicksWide(it) }, boundBy.map { it.record.weight })
        return wholeSteps(windowBound(anchor, boundBy) ?: return null, step)
    }

    /**
     * How far, in whole units, the origin may rise beneath [bound] aiming at [anchor] (#3353, the
     * window rule): `min(⌊A⌋, min over i of ⌊(p_i + C_i) / (w_i · G)⌋)`, with `A` the [anchor]. The
     * second term is each child's **room**, how far it can come down before it reaches its
     * [positionFloor]. So the origin goes to where the bound children stand on average, which is
     * where a joiner is seated, unless that would carry one of them out of its window. `null` past
     * 128 bits.
     *
     * The rule before it raised the origin only as far as the least-served child, `min ⌊p_i / (w_i · G)⌋`.
     * Two light siblings a lease apart then held the front half a lease above the origin, and a heavy
     * child joining it landed `k` times that far up its own grid: past its ceiling for `k` near
     * `2¹⁶`. Aiming at the front leaves every child behind it below zero, inside its window, and it
     * is the light ones whose windows are wide.
     */
    private fun windowBound(anchor: VirtualTime, bound: List<PolicyEdge>): Int128? {
        var best = anchor.unitsFloor()
        for (edge in bound) {
            val w = edge.record.weight
            val room = mulDiv(
                HeddlePolicy.effectiveTicksWide(edge) + Int128.of(positionCeiling(w)),
                w.denominator,
                checkedMul(TICKS_PER_UNIT, w.numerator),
                Rounding.FLOOR,
            ) ?: return null
            if (room < best) best = room
        }
        return best
    }

    /**
     * The origin step after a **charging** call lands at [parent] in [ledger]: `admit`, `extend` or a
     * `complete` that overran (#3353 review, item I). A parent a consumer drives only through those
     * is never picked at, so without this it never rebases. The rule is the pick's, `Δ` aimed at the
     * front of the demanding children [waiting] names and held inside each one's window. [waiting] is
     * the call's own, observed on the way down, so the set is who is demanding now, never who was
     * last seen demanding (#3471). An origin raise moves every child alike, so it changes no pick.
     *
     * A charging call never refuses, so this never throws: `null` when no step is possible, or when
     * the arithmetic would leave 128 bits.
     */
    internal fun chargeRebase(
        parent: GroupId,
        ledger: EntitlementLedger,
        waiting: (AttachmentId) -> Demand,
    ): Rebase? {
        if (!rebaseEnabled) return null
        return try {
            val edges = policyEdges(ledger, parent, waiting)
            val bound = edges.filter { it.gauge != null && HeddlePolicy.isDemanding(it) }
            val delta = originStep(edges, bound) ?: return null
            val patch = ledger.raiseOrigin(parent, ledger.origin(parent) + delta) ?: return null
            Rebase(ledger.piece(patch.delta))
        } catch (_: ArithmeticException) {
            // Only a 128-bit result can leave here, a front or a time read on a far-flung grid, not a
            // bug: every other step returns null. A charging call must land, so it lands unrebased.
            null
        }
    }

    /**
     * The `Δ` to raise [parent]'s origin by **before** the seat and wake step, or `null` (#3353 review,
     * item A). Only when there are joiners: the front they are about to be seated at or clamped to
     * ([joinFront]) is past the [rebaseThreshold] of a joiner or a demanding stayer on that child's
     * own grid, a seated child is past its own, or a returner is about to land past its own
     * ([previewShifts]): it comes back where its parent's clock puts it, not at the front (#3376),
     * so one that left near its ceiling can come back past it. The front counts each returner where
     * it lands, as [settleJoiners] will (#3446). Without it a heavy newborn or waker joining a busy
     * light runner lands about `k · R` up its own grid, `k` the weight ratio, which is past its
     * ceiling for `k > 16`.
     *
     * `Δ` aims at that front ([windowBound]): at most it, and short of carrying any demanding child
     * that is not joining below its [positionFloor]. With none, it is the front itself. Every newborn
     * and clamped waker then lands at the front, at or above zero. A returner moved by its parent's
     * clock (#3376) lands as far behind its siblings as it left, so one that left owed can land below
     * zero by that lag; it is served first. Each lands no lower than its own floor ([landing]).
     * It is a multiple of the same step as the post-grant raise, over every child, unseated ones
     * included, so every seat, clamp and return lands at exactly the absolute position it would have
     * without the raise, unless the floor holds it higher.
     */
    private fun preSeatRaise(s: EntitlementLedger, parent: GroupId, demand: (AttachmentId) -> Demand): Int128? {
        if (!rebaseEnabled) return null
        val edges = policyEdges(s, parent, demand)
        val joiners = joinersOf(edges)
        if (joiners.isEmpty()) return null
        val stayers = edges.filter { it.record.id !in joiners }
        // A returner lands where its parent's clock puts it, not at the front (#3376), and counts in
        // the front there (#3446).
        val shifts = previewShifts(parent, edges)
        val landedEdges = landed(edges, shifts)
        val front = joinFront(landedEdges, joiners - shifts.keys)
        val demanding = stayers.filter { it.gauge != null && HeddlePolicy.isDemanding(it) }
        // The front is read on the grid of each child it will seat, clamp or order: the joiners and
        // the demanding stayers (window rule). An idle stayer's grid is read again when it wakes.
        val frontGrids = if (windowRuleForTest) {
            val reads = HashSet(joiners).apply { demanding.mapTo(this) { it.record.id } }
            edges.filter { it.record.id in reads }
        } else {
            edges
        }
        // A child's own position past `R` triggers it for every seated child, a waker included: a
        // clamp only ever raises one, so a waker already past `R` stays there (#3353).
        val past = frontGrids.any { front.ticksForWide(it.record.weight) >= Int128.of(rebaseThreshold(it.record.weight)) } ||
            edges.any { it.gauge != null && HeddlePolicy.effectiveTicksWide(it) >= Int128.of(rebaseThreshold(it.record.weight)) } ||
            edges.any { edge ->
                shifts[edge.record.id]?.let { HeddlePolicy.effectiveTicksWide(edge) + it.ticks >= Int128.of(rebaseThreshold(edge.record.weight)) } == true
            }
        if (!past) return null
        val step = gridStep(edges) ?: return null
        // A returner is demanding and counts in the front where it lands (#3446), so its room bounds
        // `Δ` as a demanding stayer's does: no landing is then held at its floor under this raise,
        // which is what keeps the raise a pure translation (#3353 review).
        val bounded = if (windowRuleForTest) demanding + landedEdges.filter { it.record.id in shifts } else demanding
        val bound = when {
            bounded.isEmpty() -> front.unitsFloor()
            windowRuleForTest -> windowBound(front, bounded) ?: return null
            else -> demanding.minOf { unitsOf(it) ?: return null }
        }
        return wholeSteps(bound, step)
    }

    /**
     * That a pre-seat raise changed no pick (#3353 review, item A): the same seat-and-wake step and
     * pick without it, on this allocator, which the caller forked before the raised step ran, picks
     * [grant] too. A bug detector, not a proof, but no longer a vacuous one: the twin orders the same
     * wide positions, so it answers wherever the raised pick does. It runs whatever the raise: [preSeatRaise]
     * bounds `Δ` by the room of every demanding child that is not a newborn or a clamped waker,
     * returners included, so no landing under the raise is held at its floor, and the raise moves
     * every child alike. A twin without it holds no landing at its floor either, since its floor sits
     * lower beneath the same front.
     *
     * @throws IllegalStateException if it picks anything else, a bug in the rule.
     */
    private fun checkPreSeatRaiseChangedNoPick(
        s: EntitlementLedger,
        parent: GroupId,
        earmarked: Long,
        waiting: (AttachmentId) -> Demand,
        eligible: (AttachmentId) -> Demand,
        grant: Grant,
    ) {
        val cell = ValueCell(s)
        settleJoiners(parent, cell, waiting, raise = null)
        val unraised = pickOne(cell.current, parent, earmarked, eligible)
        check(unraised == grant) { "the pre-seat rebase at ${parent.value} changed the pick from $unraised to $grant" }
    }

    /** The joiners at a parent: its unseated children, and the demanding ones this allocator last saw idle. */
    private fun joinersOf(edges: List<PolicyEdge>): Set<AttachmentId> =
        edges.filter { it.gauge == null || isWaker(it) }.mapTo(HashSet()) { it.record.id }

    /**
     * Whether [edge] wakes: demanding now, and last observed not demanding. Or, never observed at
     * all, seated, demanding and below zero (#3353 review, item B). The threshold stays at zero
     * under the window rule, whose rebase leaves a watched demanding child anywhere in `[−C, 0)`:
     * one this allocator never saw there may be such a child, a restarted `HeddleNode`'s say, and
     * clamping it costs it at most its lag, once, the penalty side. A threshold at the floor would
     * leave a child idle while nobody watched below zero with up to a window of credit, which is the
     * side §10.5 forbids. Clamping costs turns, never credit (§10.6).
     */
    private fun isWaker(edge: PolicyEdge): Boolean {
        if (!HeddlePolicy.isDemanding(edge)) return false
        return when (demandingObserved[edge.record.id]) {
            false -> true
            true -> false
            null -> edge.gauge != null && HeddlePolicy.effectiveTicksWide(edge) < Int128.ZERO
        }
    }

    /** The least `Δ` that moves every one of [edges] by whole ticks: [gridStepOf] over their weights. */
    private fun gridStep(edges: List<PolicyEdge>): Long? = gridStepOf(edges.map { it.record.weight })

    /** [edge]'s effective virtual time in whole units, rounded down, or `null` past 128 bits. */
    private fun unitsOf(edge: PolicyEdge): Int128? {
        val w = edge.record.weight
        return mulDiv(HeddlePolicy.effectiveTicksWide(edge), w.denominator, checkedMul(TICKS_PER_UNIT, w.numerator), Rounding.FLOOR)
    }

    /**
     * The largest whole multiple of [step] at or below [bound], or `null` when that is not above zero:
     * the front is less than one step above the origin, or a demanding child is less than one step
     * above its floor. A child that is served climbs away from its floor. One that is never served
     * is carried further below zero by each rebase and drags the demanding children's mean down
     * with it, until the mean is less than one step above the origin and the rebase stops (#3329,
     * #3353); its siblings then climb on, and a pick orders them exactly. A
     * weight set so fine-grained that one step, read on some child's grid, would carry it from its
     * [rebaseThreshold] past its ceiling (`2⁴²/1023` beside `1/1021`, say) is refused when it is
     * applied ([weightsFitTheBudget], P1′), so a step that is merely large only makes a rebase late.
     */
    private fun wholeSteps(bound: Int128, step: Long): Int128? {
        val steps = mulDiv(bound, 1L, step, Rounding.FLOOR) ?: return null
        if (steps <= Int128.ZERO) return null
        return mulDiv(steps, step, 1L, Rounding.FLOOR)
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
        // A rebase before the seat and wake step too, so no joiner is seated or clamped far up its own
        // grid at a front a light runner holds up (#3353 review, item A).
        // With none, the raise still runs before the pick (#3353).
        val preSeat = fork.preSeatRaise(s, parent, demand) ?: fork.prePickRaise(s, parent, demand)
        fork.settleJoiners(parent, cell, demand, preSeat)
        val delegated = fork.pickOne(cell.current, parent, earmarked, demand)?.let { grant ->
            if (preSeat != null) fork().checkPreSeatRaiseChangedNoPick(s, parent, earmarked, demand, demand, grant)
            // The rebase after the seat and wake step, before the grant, in the same patch (#3353).
            val raised = fork.raiseOriginBeneath(parent, cell, earmarked, demand, grant)
            cell.mutateOrSkip { it.delegate(fork.self, grant.attachment, grant.amount) }
                .also { if (it && (raised || preSeat != null)) fork.rebasesForTest++ }
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
     *   none is eligible, none is seated, or [parent] holds nothing.
     */
    public fun pickChild(
        parent: GroupId,
        ledger: EntitlementLedger,
        waiting: (AttachmentId) -> Demand,
        eligible: (AttachmentId) -> Demand,
    ): ChildPick? {
        val seated = settle(parent, ledger, waiting)
        val grant = pickOne(seated, parent, earmarked = 0L, eligible) ?: return null
        return ChildPick(seated, grant)
    }

    /**
     * [pickChild], and then, only when it grants, [parent]'s origin raised on the settled ledger:
     * the form [LocalHeddle] calls (#3329, #3353). The rule is [schedule]'s ([raiseOriginBeneath]),
     * measured on who [waiting] says is demanding. The grant is the one [pickChild] made; the raise
     * changes no pick, and that is checked, against [eligible], on the raised state before it is
     * returned.
     *
     * The order is the point. The rebase runs after the seat-and-wake step, so every child already
     * carries a gauge seated at a front that leaves the wakers out, and every waker its clamp: a
     * rebase before that step would count a waker's stale position in a newborn's seat, the lifetime
     * credit #1752 removed. And it runs only once there is a grant, so a pick that finds nothing
     * rebases nothing.
     *
     * Nothing here changes this allocator beyond what [pickChild] observes. The raise is in
     * [RebasingPick.pick]'s ledger alone, so a caller that drops that ledger, because a deeper level
     * refused, say, drops the rebase with it. It rewrites no gauge and no wake clamp.
     *
     * @throws IllegalStateException if the rebased state would pick differently, a bug in the rebase.
     */
    internal fun pickChildRebasing(
        parent: GroupId,
        ledger: EntitlementLedger,
        waiting: (AttachmentId) -> Demand,
        eligible: (AttachmentId) -> Demand,
    ): RebasingPick? {
        if (!rebaseEnabled) return pickChild(parent, ledger, waiting, eligible)?.let { RebasingPick(it.ledger, it, rebase = null) }
        // [schedule]'s two raises, before the seat and wake step and after the pick (#3353).
        val preSeat = preSeatRaise(ledger, parent, waiting)
        val unraised = if (preSeat != null) fork() else null
        val cell = ValueCell(ledger)
        settleJoiners(parent, cell, waiting, preSeat)
        val grant = pickOne(cell.current, parent, earmarked = 0L, eligible) ?: return null
        unraised?.checkPreSeatRaiseChangedNoPick(ledger, parent, earmarked = 0L, waiting, eligible, grant)
        val settled = cell.current
        val raised = raiseOriginBeneath(parent, cell, earmarked = 0L, waiting, grant, eligible)
        val rebase = if (raised || preSeat != null) Rebase(cell.current) else null
        return RebasingPick(settled = settled, pick = ChildPick(cell.current, grant), rebase = rebase)
    }

    /**
     * Count [rebase] as one this allocator made. A rebase is all in its ledger, so there is nothing
     * else to take: call it when the caller keeps that ledger, so the count is of rebases kept.
     */
    internal fun adopt(rebase: Rebase) {
        rebasesForTest++
    }

    /**
     * [parent]'s origin step in [ledger], measured on who [demand] says is demanding, or `null` when it
     * would not move: [raiseOriginBeneath]'s rule, for a test that asks it directly.
     */
    internal fun originRebase(parent: GroupId, ledger: EntitlementLedger, demand: (AttachmentId) -> Demand): Int128? =
        originStep(policyEdges(ledger, parent, demand))

    /**
     * The step [pickChild] takes before it picks, and nothing more: seat the newborns and clamp the
     * wakers at [parent], reading [waiting], and observe who was waiting. For a delegation that is
     * not a pick but must leave the wake memory as the pick would have ([LocalHeddle.admit] with a
     * waiting set, #3320). A seat or a clamp is exact and wide wherever it lands. Returns the seated
     * ledger; [ledger] is not changed.
     */
    internal fun settle(parent: GroupId, ledger: EntitlementLedger, waiting: (AttachmentId) -> Demand): EntitlementLedger {
        val cell = ValueCell(ledger)
        settleJoiners(parent, cell, waiting)
        return cell.current
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
        val patch = seatPatch(ledger, unseated, front) ?: return ledger
        return ledger.piece(patch.delta)
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
    public fun virtualTime(
        ledger: EntitlementLedger,
        parent: GroupId,
        demand: (AttachmentId) -> Demand,
    ): Rational? =
        if (childWeightsFit(ledger, parent)) HeddlePolicy.front(policyEdges(ledger, parent, demand).map(::heldInWindow)) else null

    /**
     * [edge] read inside its window, for a diagnostic (#3353), as the public reads hold it
     * ([inWindow]): a child a `cancel` walked back past its floor reads at the floor, the penalty
     * side, and one past its ceiling at the ceiling, so the front over them cannot throw on how far
     * a child is from its origin.
     */
    private fun heldInWindow(edge: PolicyEdge): PolicyEdge {
        val effective = HeddlePolicy.effectiveTicksWide(edge)
        val held = Int128.of(inWindow(effective, edge.record.weight))
        return if (held == effective) edge else edge.copy(offsetTicks = edge.offsetTicks + (held - effective))
    }

    /**
     * [edge]'s effective virtual service as a pick at [parent] would read it in [ledger]: its
     * [HeddlePolicy.virtualService] plus this allocator's stored wake clamp. `null` when [edge] is
     * not an active child of [parent]. A diagnostic, like [virtualTime]: it observes nothing.
     */
    internal fun effectiveVirtualService(ledger: EntitlementLedger, parent: GroupId, edge: AttachmentId): Rational? =
        policyEdges(ledger, parent, demand = { Demand.NONE })
            .firstOrNull { it.record.id == edge }
            ?.let { readVirtual(HeddlePolicy.effectiveTicksWide(it), it.record.weight) }

    /**
     * The highest effective position among [parent]'s active children in [ledger], each as a
     * fraction of its own [positionCeiling]: how far the rebase has let the parent climb (#3353).
     * Past `1` the public virtual-time reads there saturate at the ceiling; no pick is affected,
     * since a pick orders the wide position. Zero when [parent] has no active children. A diagnostic that reads this allocator's wake clamps,
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
        val origin = s.origin(parent)
        return s.activeChildren(parent).mapNotNull { summary ->
            val record = s.record(summary.attachment) ?: return@mapNotNull null
            PolicyEdge(
                record = record,
                summary = summary,
                demand = demand(summary.attachment),
                gauge = s.gauge(summary.attachment),
                baseIssued = s.baseIssuance(summary.attachment),
                offsetTicks = wakeOffsets[summary.attachment] ?: Int128.ZERO,
                origin = origin,
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
     */
    private fun settleJoiners(parent: GroupId, ledger: LedgerCell, demand: (AttachmentId) -> Demand, raise: Int128? = null) {
        val first = policyEdges(ledger.current, parent, demand)
        forgetDepartedChildren(first, ledger.current, parent)
        if (first.isEmpty()) {
            clocks.remove(parent)
            return
        }

        val clock = markDepartures(parent, first)
        val unseated = first.filter { it.gauge == null }.mapTo(HashSet()) { it.record.id }
        val wakers = first.filter { isWaker(it) }.mapTo(HashSet()) { it.record.id }
        // A waker this peer saw leave comes back by the clock; one it never saw leave is clamped.
        val returners = first.filter { isReturner(it) }.mapTo(HashSet()) { it.record.id }
        val joiners = HashSet(unseated).apply { addAll(wakers) }
        // A pre-seat rebase ([preSeatRaise]) lands first, and everything after reads through it.
        if (raise != null) ledger.mutateOrSkip { it.raiseOrigin(parent, it.origin(parent) + raise) }
        val edges = if (raise != null) policyEdges(ledger.current, parent, demand) else first
        if (joiners.isNotEmpty()) {
            val shifts = edges.filter { it.record.id in returners }.associate { it.record.id to shiftOf(it, clock) }
            // A returner is counted in the front where it lands, not left out as a joiner (#3446).
            val front = joinFront(landed(edges, shifts), joiners - returners)
            if (unseated.isNotEmpty()) {
                ledger.mutateOrSkip { state -> seatPatch(state, unseated, front) }
            }
            // An unseated waker is a newborn: its seat puts it at the front, and a clamp would count
            // the front twice (#3454 review).
            clampWakers(edges, wakers - returners - unseated, front)
            moveReturners(edges, shifts)
        }
        for (edge in edges) demandingObserved[edge.record.id] = HeddlePolicy.isDemanding(edge)
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
     * The third is gone from the competing case too. With a demanding runner below zero, which only
     * a rebase leaves there, the newborn is seated level with it: the seat is absolute, the parent's
     * origin added back, so it follows the runner below zero (#3353), but no lower than its own
     * floor ([landing]).
     * `aNewbornBesideASubZeroRunnerAndAWakerStartsLevel` pins it.
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
     * Where a joiner of weight [w] lands, relative to its parent's origin: [front] on its own grid,
     * `⌈V · w · G⌉`, or its [positionFloor] when the front is below that (#3353, the window rule).
     * The floor arm is never below the front, so it can only cost the joiner a turn. It is reached
     * when the demanding children stand below the origin on average, which a rebase that aims at
     * their front leaves behind once the one ahead stops asking; on a heavy child's grid the front
     * is then far below zero.
     * The penalty is how far the front stood below the joiner's floor, `max(0, −C/(w · G) − F)` in
     * virtual time relative to the origin: a lost turn, never credit. No raise the joiner lands
     * under causes one, since that raise is bounded by every demanding child's room, returners
     * included, and a sleeper credit is at most one lease ([PolicyConfig.sleeperCredit]). It comes
     * from siblings that stood below the origin when the joiner arrived, which in a parent driven by
     * picks is within about a lease of it, the spread a fair pick leaves between them; charging calls
     * can leave siblings about `R` apart.
     */
    private fun landing(front: VirtualTime, w: Weight): Int128 {
        val at = front.ticksForWide(w)
        if (!windowRuleForTest) return at
        val floor = Int128.of(positionFloor(w))
        if (at >= floor) return at
        floorLandingsForTest++
        return floor
    }

    /**
     * One patch seating each of [unseated] at [front], each on its own grid ([landing]), written
     * absolute: `⌊O · w · G⌋ + ⌈V · w · G⌉` for the parent's origin `O` in [s], wide and exact (#3353). [EntitlementLedger.seatAtWide] refuses an edge that already carries a
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
    ): Patch<EntitlementLedger>? {
        val gauges = LinkedHashMap<AttachmentId, Gauge>()
        for (id in unseated.sorted()) {
            val record = s.record(id) ?: continue
            // Absolute: the origin this peer sees, plus the front, both on the newborn's own grid (#3353),
            // the front held at the newborn's floor ([landing]).
            val absolute = originTicks(s.origin(record.parent), record.weight) + landing(front, record.weight)
            val seat = s.seatAtWide(id, absolute) ?: continue
            gauges[id] = checkNotNull(seat.delta.gauge(id)) { "a seat patch for ${id.value} carries no gauge" }
        }
        if (gauges.isEmpty()) return null
        return Patch(EntitlementLedger.of(gauges = gauges))
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
     * every other waker is clamped ([clampWakers]). The one definition [settleJoiners] and
     * [previewShifts] both read (#3376).
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
     * returner is moved that sliver further: the penalty side. An origin rebase changes no issuance,
     * so it moves no clock. A shift is a distance, and the origin moves a returner's raw position
     * and its siblings' alike.
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
     * How far each child at [parent] that would come back by the clock this round would move
     * ([shiftOf]), at the clock [markDepartures] would run it to. Changes nothing; [preSeatRaise]
     * reads it, because a returner lands where its shift puts it, not at the front.
     */
    private fun previewShifts(parent: GroupId, edges: List<PolicyEdge>): Map<AttachmentId, Shift> {
        val returners = edges.filter { isReturner(it) }
        if (returners.isEmpty()) return emptyMap()
        val clock = (clocks[parent] ?: Int128.ZERO) + clockStep(parent, edges.filter(::wasAsking), served)
        return returners.associate { it.record.id to shiftOf(it, clock) }
    }

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
        // The run less the carry, in units of 1 / (CLOCK_GRID · d) of a tick, so it is a whole number.
        val owed = wide(mulDiv(clock - left, w.numerator, 1L, Rounding.FLOOR), clock, w) - carried
        // ⌈owed / (CLOCK_GRID · d)⌉ in two exact steps, since CLOCK_GRID · d can pass a Long.
        val ticks = wide(mulDiv(wide(mulDiv(owed, 1L, CLOCK_GRID, Rounding.CEILING), clock, w), 1L, d, Rounding.CEILING), clock, w)
        val past = wide(mulDiv(wide(mulDiv(ticks, CLOCK_GRID, 1L, Rounding.FLOOR), clock, w), d, 1L, Rounding.FLOOR), clock, w) - owed
        val remainder = if (past == Int128.ZERO) null else ShiftRemainder(past, d)
        val clocked = maxOf(Int128.ZERO, ticks)
        // A returner lands no lower than its floor, as a seat or a clamp does (#3353, the window rule).
        // The remainder is the clock's alone: what the floor added is penalty, and is not carried.
        if (windowRuleForTest) {
            val toFloor = Int128.of(positionFloor(w)) - HeddlePolicy.effectiveTicksWide(edge)
            if (toFloor > clocked) return Shift(toFloor, remainder)
        }
        return Shift(clocked, remainder)
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
            wakeOffsets[id] = edge.offsetTicks + shift.ticks
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
     * memory no allocator writes: the format 1 v0.7.4 wrote carried one, and this release does not
     * read it (#3353).
     *
     * Two things lower a stored offset, and neither returns a turn. An edge is forgotten when it
     * leaves the active set, which [EntitlementLedger.activate][EntitlementLedger.activate] makes
     * permanent (closure dominance, §10.10), so a cleared clamp can never come back to an edge that
     * will compete again. A rebase does not touch it (#3353): it raises the parent's origin, which
     * lowers the front and the raw position alike, so no turn moves. A clamp is the front less the
     * raw position at the wake, so for a child that idles while the origin rises it is larger by
     * every stretch sat out; it is wide, so it can be, without nearing any ceiling.
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
    private fun clampWakers(edges: List<PolicyEdge>, wakers: Set<AttachmentId>, front: VirtualTime) {
        for (edge in edges) {
            if (edge.record.id !in wakers) continue
            val weight = edge.record.weight
            // Wide, both sides (#3353): a waker idle since long before the origin was raised sits far
            // below its siblings, and a clamp narrowed before it is taken would leave it there.
            val raw = HeddlePolicy.rawTicks(edge)
            val credited = HeddlePolicy.clampTicks(frontTicks = front.ticksForWide(weight), rawTicks = raw, sleeperCredit = policy.sleeperCredit)
            // Held at the waker's floor after its sleeper credit, not before (#3353, the window rule):
            // the credit is kept down to the floor and no further.
            val toFloor = Int128.of(positionFloor(weight)) - raw
            val computed = if (windowRuleForTest && toFloor > credited) toFloor.also { floorLandingsForTest++ } else credited
            // Written, not joined: a seated waker with no departure holds no offset (see above).
            // Both sides are on the waker's grid, and the result is stored on it, so a stored offset
            // never carries a front's denominator into the next one (#3313).
            wakeOffsets[edge.record.id] = computed
        }
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
 * delegate, or stopped because the parent cannot be ordered at all.
 *
 * The two empty answers are separate arms because they clear differently. [NothingToDelegate] is
 * the ordinary answer, and it clears by itself once holdings or demand arrive. [WeightBudget] is a
 * property of the parent's children, and it lasts until those change.
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
     * Not returned today. A pick orders the full 128-bit position, so no position at [parent]
     * stops a round. Kept for the ledger's `Long` lifetime counters, which throw there for now.
     */
    public data class PositionCeiling(public val parent: GroupId) : AllocationOutcome

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
 * One rebase (#3329, #3353): [ledger] with the parent's origin raised. The whole change is in the
 * ledger, so a caller keeps it by keeping that ledger, and [FairShareAllocator.adopt] only counts it.
 */
internal class Rebase(val ledger: EntitlementLedger)

/**
 * One [FairShareAllocator.pickChildRebasing] result: [pick], whose ledger is rebased when [rebase]
 * is not `null`, and [settled], the same ledger before the rebase, which reads right against the
 * allocator's wake offsets until [rebase] is adopted.
 */
internal class RebasingPick(val settled: EntitlementLedger, val pick: ChildPick, val rebase: Rebase?)

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
