package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.math.Int128
import us.tractat.kuilt.math.Rounding
import us.tractat.kuilt.math.mulDiv
import us.tractat.kuilt.math.plusOrNull
import us.tractat.kuilt.math.timesOrNull

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
 * (design §7.2; issue #1695). Both are keyed by attachment id across the whole tree and are
 * **scheduler-local**: never replicated, never in the ledger. A divergent offset reorders one
 * peer's grants but can never create entitlement.
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
    private val wakeOffsets = HashMap<AttachmentId, StoredOffset>()

    /**
     * Clamps taken from an [AllocatorMemory], which still holds them as virtual times, read onto an
     * edge's grid the first time the edge is read, when its weight is known.
     */
    private val importedOffsets = HashMap<AttachmentId, Rational>()
    private val demandingObserved = HashMap<AttachmentId, Boolean>()

    /**
     * Whether [schedule] rebases a busy parent by raising its origin (#3353). On by default. **Test
     * only:** a test switches it off to run a twin that never rebases and compare the picks; a parent
     * with it off reaches its position ceiling.
     */
    @HeddleInternalApi
    public var rebaseEnabled: Boolean = true

    /**
     * How many rebases this allocator has made: the origin raises [schedule] landed, plus the fold
     * raises it [adopt]ed for [LocalHeddle]. The witness a test reads that its rig fired. Not part
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
        importedOffsets.putAll(memory.wakeOffsets)
        demandingObserved.putAll(memory.demandingObserved)
    }

    /**
     * What this allocator remembers right now, as an immutable value with its maps in key order.
     * A copy: later calls on this allocator do not change it. Costs one sort of each map, so read
     * it to persist or to preview, not on every pick.
     */
    public val memory: AllocatorMemory
        get() {
            val offsets = HashMap<AttachmentId, Rational>(importedOffsets)
            for ((id, stored) in wakeOffsets) offsets[id] = stored.asVirtualTime()
            return AllocatorMemory(
                wakeOffsets = offsets.entries.sortedBy { it.key }.associate { it.key to it.value },
                demandingObserved = demandingObserved.entries.sortedBy { it.key }.associate { it.key to it.value },
            )
        }

    /** [id]'s stored wake clamp in ticks on the grid of weight [w], zero when there is none. */
    private fun offsetTicksOf(id: AttachmentId, w: Weight): Int128 =
        wakeOffsets[id]?.ticks ?: importedOffsets[id]?.let { wideTicksAt(it, w) } ?: Int128.ZERO

    /** Store [ticks] as [id]'s wake clamp, on the grid of weight [w]. */
    private fun storeOffset(id: AttachmentId, w: Weight, ticks: Int128) {
        importedOffsets.remove(id)
        wakeOffsets[id] = StoredOffset(ticks, w)
    }

    /** Drop everything remembered about [ids]' wake clamps. */
    private fun dropOffsets(ids: Collection<AttachmentId>) {
        wakeOffsets.keys.removeAll(ids.toSet())
        importedOffsets.keys.removeAll(ids.toSet())
    }

    /**
     * An allocator that remembers exactly what this one does, and that later calls on either leave
     * the other alone: [memory] without the sort, for a caller that copies on every call.
     */
    internal fun fork(): FairShareAllocator = FairShareAllocator(self, policy).also {
        it.wakeOffsets.putAll(wakeOffsets)
        it.importedOffsets.putAll(importedOffsets)
        it.demandingObserved.putAll(demandingObserved)
        it.rebasesForTest = rebasesForTest
        it.rebaseEnabled = rebaseEnabled
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
        dropOffsets(edges)
        demandingObserved.keys.removeAll(edges)
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
     * Two states stop it, and neither throws. A child of [parent] at its position ceiling (#3313)
     * stops it at the pick that would have to order it. Active children whose weights put a pick's
     * products out of `Long` ([weightsFitTheBudget]) stop it before the seat step: the replicated
     * ledger can merge such a set from writes that each fit (#3353).
     *
     * ## A refused or empty call writes nothing
     *
     * The seat step and the first pick run together, on a [fork] of this allocator, inside **one**
     * [LedgerCell.mutateOrSkip] (#3353):
     * - the first pick **refused**, at the ceiling or the budget: no patch, and the fork is dropped,
     *   so no seat, no clamp and no observation is kept;
     * - the first pick found **nothing** to grant: no patch either, so the seats are dropped with
     *   it, but the fork's wake memory is kept, since it observed who was waiting. That is what
     *   [LocalHeddle] does, and it means an idle call writes nothing to a replicated ledger;
     * - the first pick **granted**: the seats and the delegation land as one patch, and the fork is
     *   kept.
     *
     * A first round that grants may also raise [parent]'s origin, in the same patch, once a child
     * there has climbed past its [rebaseThreshold]: see [raiseOriginBeneath]. So may each later
     * round, inside its own write. That rebase is what keeps a busy parent clear of the position
     * ceiling, and it changes no pick (#3353).
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
     *   refusal stopped it after them; otherwise [AllocationOutcome.PositionCeiling] or
     *   [AllocationOutcome.WeightBudget] when [parent] cannot be ordered, and
     *   [AllocationOutcome.NothingToDelegate] when it simply had nothing to grant.
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
            is FirstRound.Refused -> return opened.refusal.at(parent)
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
                try {
                    val grant = pickOne(s, parent, earmarked, demand) ?: return@mutateOrSkip null
                    val cell = ValueCell(s)
                    val raised = raiseOriginBeneath(parent, cell, earmarked, demand, grant)
                    if (!cell.mutateOrSkip { it.delegate(self, grant.attachment, grant.amount) }) return@mutateOrSkip null
                    rebasedThisRound = raised
                    cell.written?.let(::Patch)
                } catch (_: PositionCeilingException) {
                    // The pick would have to order a position at its ceiling (#3313): grant nothing
                    // more here. No patch is returned, so nothing is written.
                    null
                }
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
        importedOffsets.clear()
        importedOffsets.putAll(fork.importedOffsets)
        demandingObserved.clear()
        demandingObserved.putAll(fork.demandingObserved)
        rebasesForTest = fork.rebasesForTest
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
     * - **`Δ` is at most the smallest effective virtual time** among the seated children this peer
     *   sees demanding, so none of them goes below zero;
     * - it runs **after the seat and wake step and only after a grant**, and the pick is checked
     *   unchanged on the raised state before the raise is kept.
     *
     * It rewrites no gauge and no wake clamp. Every child's position reads `Δ · w · G` lower, so the
     * mean over any set comes down by `Δ`, every `⌈V · w · G⌉` by exactly `Δ · w · G`, and every
     * clamp `front − raw` is unchanged. An idle child goes below zero, as far as it must: its next
     * wake clamp, taken wide, carries it back to the front.
     *
     * @throws IllegalStateException if the raised state would pick differently, a bug in the rule.
     */
    private fun raiseOriginBeneath(
        parent: GroupId,
        cell: ValueCell,
        earmarked: Long,
        demand: (AttachmentId) -> Demand,
        grant: Grant,
    ): Boolean {
        if (!rebaseEnabled) return false
        val s = cell.current
        val delta = originStep(policyEdges(s, parent, demand)) ?: return false
        val patch = s.raiseOrigin(parent, s.origin(parent) + delta) ?: return false
        val again = pickOne(s.piece(patch.delta), parent, earmarked, demand)
        check(again == grant) { "the origin rebase at ${parent.value} changed the pick from $grant to $again" }
        return cell.mutateOrSkip { patch }
    }

    /**
     * The `Δ` [raiseOriginBeneath] raises [edges]' parent's origin by, or `null` when it should not
     * move: no child is past its [rebaseThreshold], one is at its ceiling (the pick refused first),
     * the step leaves `Long`, or a demanding child is below one step.
     */
    private fun originStep(edges: List<PolicyEdge>): Int128? {
        if (edges.isEmpty()) return null
        // The front on every child's grid too: a light runner holding the front up would otherwise
        // put a heavy child joining it past its own ceiling (#3353 review, item A).
        val front = HeddlePolicy.frontTime(edges)
        val past = edges.any { edge ->
            val r = Int128.of(rebaseThreshold(edge.record.weight))
            HeddlePolicy.effectiveTicksWide(edge) >= r || (front != null && front.ticksForWide(edge.record.weight) >= r)
        }
        if (!past) return null
        if (edges.any { HeddlePolicy.isAtCeiling(it) }) return null
        val step = gridStep(edges) ?: return null
        val demanding = edges.filter { it.gauge != null && HeddlePolicy.isDemanding(it) }
        if (demanding.isEmpty()) return null
        return wholeSteps(demanding.minOf { unitsOf(it) ?: return null }, step)
    }

    /**
     * The `Δ` to raise [parent]'s origin by **before** the seat and wake step, or `null` (#3353 review,
     * item A). Only when there are joiners: the front they are about to be seated at or clamped to
     * ([joinFront]) is past some child's [rebaseThreshold] on that child's own grid, or a child that
     * is not joining is past its own. Without it a heavy newborn or waker joining a busy light runner
     * lands about `k · R` up its own grid, `k` the weight ratio, which is past its ceiling for `k > 16`.
     *
     * `Δ` is bounded by the smallest effective virtual time among the demanding children that are
     * not joining, or, with none, by the front itself: every joiner lands at the front, at or above
     * that bound, so no demanding child goes below zero. It is a multiple of the same step as the
     * post-grant raise, over every child, unseated ones included, so every seat and clamp lands at
     * exactly the absolute position it would have without the raise.
     */
    private fun preSeatRaise(s: EntitlementLedger, parent: GroupId, demand: (AttachmentId) -> Demand): Int128? {
        if (!rebaseEnabled) return null
        val edges = policyEdges(s, parent, demand)
        val joiners = joinersOf(edges)
        if (joiners.isEmpty()) return null
        val stayers = edges.filter { it.record.id !in joiners }
        if (stayers.any { HeddlePolicy.isAtCeiling(it) }) return null
        val front = joinFront(edges, joiners)
        val past = edges.any { front.ticksForWide(it.record.weight) >= Int128.of(rebaseThreshold(it.record.weight)) } ||
            stayers.any { HeddlePolicy.effectiveTicksWide(it) >= Int128.of(rebaseThreshold(it.record.weight)) }
        if (!past) return null
        val step = gridStep(edges) ?: return null
        val demanding = stayers.filter { it.gauge != null && HeddlePolicy.isDemanding(it) }
        val bound = if (demanding.isEmpty()) front.unitsFloor() else demanding.minOf { unitsOf(it) ?: return null }
        return wholeSteps(bound, step)
    }

    /**
     * That a pre-seat raise changed no pick (#3353 review, item A): the same first round without it,
     * on a fork of this allocator, either picks [grant] too or refuses at the ceiling, which is what
     * the raise is for. A bug detector, not a proof: in the regime the raise exists for, the twin
     * refuses, so the check is vacuous there and constrains only a raise that did not need to land.
     *
     * @throws IllegalStateException if it picks anything else, a bug in the rule.
     */
    private fun checkPreSeatRaiseChangedNoPick(
        s: EntitlementLedger,
        parent: GroupId,
        earmarked: Long,
        demand: (AttachmentId) -> Demand,
        grant: Grant,
    ) {
        val twin = fork()
        val cell = ValueCell(s)
        twin.settleJoiners(parent, cell, demand, raise = null)
        val unraised = try {
            twin.pickOne(cell.current, parent, earmarked, demand)
        } catch (_: PositionCeilingException) {
            return
        }
        check(unraised == grant) { "the pre-seat rebase at ${parent.value} changed the pick from $unraised to $grant" }
    }

    /** The joiners at a parent: its unseated children, and the demanding ones this allocator last saw idle. */
    private fun joinersOf(edges: List<PolicyEdge>): Set<AttachmentId> =
        edges.filter { it.gauge == null || isWaker(it) }.mapTo(HashSet()) { it.record.id }

    /**
     * Whether [edge] wakes: demanding now, and last observed not demanding. Or, never observed at
     * all, seated, demanding and below zero (#3353 review, item B). A rebase never takes a child that
     * kept demanding below zero, so one there has been idle while this allocator was not watching it,
     * a restarted `HeddleNode` say, and clamping it costs it at most turns, never credit (§10.6).
     */
    private fun isWaker(edge: PolicyEdge): Boolean {
        if (!HeddlePolicy.isDemanding(edge)) return false
        return when (demandingObserved[edge.record.id]) {
            false -> true
            true -> false
            null -> edge.gauge != null && HeddlePolicy.effectiveTicksWide(edge) < Int128.ZERO
        }
    }

    /**
     * The least `Δ` that moves every one of [edges] by whole ticks: the lcm of `d / gcd(d, G · n)`.
     *
     * Never `null` under the weight budget: each `d / gcd(d, G · n)` divides `d`, so their lcm
     * divides `L`, the lcm of the denominators, which [weightsFitTheBudget] keeps in `Long`. The
     * `null` guards a set merged past the budget, which [schedule] refuses before it gets here.
     */
    private fun gridStep(edges: List<PolicyEdge>): Long? {
        var step = 1L
        for (edge in edges) {
            val w = edge.record.weight
            val need = w.denominator / gcdOf(w.denominator, checkedMul(TICKS_PER_UNIT, w.numerator))
            step = (step / gcdOf(step, need)).timesOrNull(need) ?: return null
        }
        return step
    }

    /** [edge]'s effective virtual time in whole units, rounded down, or `null` past 128 bits. */
    private fun unitsOf(edge: PolicyEdge): Int128? {
        val w = edge.record.weight
        return mulDiv(HeddlePolicy.effectiveTicksWide(edge), w.denominator, checkedMul(TICKS_PER_UNIT, w.numerator), Rounding.FLOOR)
    }

    /**
     * The largest whole multiple of [step] at or below [bound], or `null` when that is not above zero:
     * the furthest-behind demanding child is less than one step ahead of the origin. A child that is
     * served climbs past it; one that is never served holds it there, the known limit (#3329). So does
     * a weight set so fine-grained that one step, read on some child's grid, is large there. Past that
     * child's [rebaseThreshold] but under 16 of them, it only makes a rebase late. Past 16, its
     * ceiling, it wedges the parent for as long as that child demands. That needs a spread near
     * 10¹², `2⁴²/1023` beside `1/1021` say, and about 2⁴⁹ units of service.
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
        if (!fork.childWeightsFit(s, parent)) return FirstRound.Refused(Refusal.WEIGHT_BUDGET)
        val cell = ValueCell(s)
        // A rebase before the seat and wake step too, so no joiner is seated or clamped past its own
        // ceiling at a front a light runner holds up (#3353 review, item A).
        val preSeat = fork.preSeatRaise(s, parent, demand)
        fork.settleJoiners(parent, cell, demand, preSeat)
        val delegated = try {
            fork.pickOne(cell.current, parent, earmarked, demand)?.let { grant ->
                if (preSeat != null) checkPreSeatRaiseChangedNoPick(s, parent, earmarked, demand, grant)
                // The rebase after the seat and wake step, before the grant, in the same patch (#3353).
                val raised = fork.raiseOriginBeneath(parent, cell, earmarked, demand, grant)
                cell.mutateOrSkip { it.delegate(fork.self, grant.attachment, grant.amount) }
                    .also { if (it && (raised || preSeat != null)) fork.rebasesForTest++ }
            } ?: false
        } catch (_: PositionCeilingException) {
            // The pick would have to order a position at its ceiling (#3313): the seats go too.
            return FirstRound.Refused(Refusal.POSITION_CEILING)
        }
        if (!delegated) return FirstRound.Empty(fork)
        val written = checkNotNull(cell.written) { "a delegation that landed wrote nothing" }
        return FirstRound.Granted(fork, Patch(written))
    }

    /** How [schedule]'s first round ended. Each arm carries exactly what the caller acts on. */
    private sealed interface FirstRound {
        /** The pick refused: write nothing, and drop the fork with everything it observed. */
        class Refused(val refusal: Refusal) : FirstRound

        /** Nothing to grant: write nothing, but keep [fork]'s wake memory, which saw who was waiting. */
        class Empty(val fork: FairShareAllocator) : FirstRound

        /** A grant: land [patch], the seats with the delegation, and keep [fork]. */
        class Granted(val fork: FairShareAllocator, val patch: Patch<EntitlementLedger>) : FirstRound
    }

    /** Why [schedule] stopped at a parent it cannot order: the two refusals, and only those. */
    private enum class Refusal {
        POSITION_CEILING,
        WEIGHT_BUDGET,
        ;

        fun at(parent: GroupId): AllocationOutcome = when (this) {
            POSITION_CEILING -> AllocationOutcome.PositionCeiling(parent)
            WEIGHT_BUDGET -> AllocationOutcome.WeightBudget(parent)
        }
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
     * @throws ArithmeticException when a child of [parent] holds a position at its ceiling, as
     *   [HeddlePolicy.pick] does (#3313). The wake memory has moved by then; a caller that must
     *   change nothing on a refusal keeps a copy, as [LocalHeddle] does.
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
     * [pickChild], and then, only when it grants, [parent] [rebased] on the settled ledger: the form
     * [LocalHeddle] calls (#3329). The grant is the one [pickChild] made; the rebase changes no pick,
     * and that is checked on the rebased state before it is returned.
     *
     * The order is the point. The rebase runs after the seat-and-wake step, so every child already
     * carries a gauge seated at a front that leaves the wakers out, and every waker its clamp: a
     * rebase before that step would count a waker's stale position in a newborn's seat, the lifetime
     * credit #1752 removed. And it runs only once there is a grant, so a pick that finds nothing
     * rebases nothing.
     *
     * Nothing here changes this allocator beyond what [pickChild] observes. The rebase comes back as
     * a value, the raised folds in [RebasingPick.pick]'s ledger and the lowered wake offsets beside
     * them, and the two are adopted together, by [adopt], only when the caller keeps that ledger. A
     * caller that drops the ledger, because a deeper level refused, say, drops the offsets with it:
     * keeping a lowered offset without its raised folds would hand the child its offset back as
     * credit.
     *
     * @throws IllegalStateException if the rebased state would pick differently, a bug in the rebase.
     */
    internal fun pickChildRebasing(
        parent: GroupId,
        ledger: EntitlementLedger,
        waiting: (AttachmentId) -> Demand,
        eligible: (AttachmentId) -> Demand,
    ): RebasingPick? {
        val pick = pickChild(parent, ledger, waiting, eligible) ?: return null
        val rebase = rebased(parent, pick.ledger, waiting) ?: return RebasingPick(settled = pick.ledger, pick = pick, rebase = null)
        val again = pickOne(rebase.ledger, parent, earmarked = 0L, eligible, rebase.offsets.mapValues { it.value.ticks })
        check(again == pick.grant) { "the rebase at ${parent.value} changed the pick from ${pick.grant} to $again" }
        return RebasingPick(settled = pick.ledger, pick = ChildPick(rebase.ledger, pick.grant), rebase = rebase)
    }

    /**
     * Take [rebase]'s lowered wake offsets as this allocator's own: the half of a rebase that is
     * not in the ledger. Call it exactly when the ledger [rebase] raised the folds of is kept.
     */
    internal fun adopt(rebase: Rebase) {
        for ((id, offset) in rebase.offsets) {
            if (offset.ticks == Int128.ZERO) dropOffsets(listOf(id)) else storeOffset(id, offset.weight, offset.ticks)
        }
        rebasesForTest++
    }

    /**
     * The step [pickChild] takes before it picks, and nothing more: seat the newborns and clamp the
     * wakers at [parent], reading [waiting], and observe who was waiting. For a delegation that is
     * not a pick but must leave the wake memory as the pick would have ([LocalHeddle.admit] with a
     * waiting set, #3320). Never throws at a position ceiling, since it orders nothing: a seat or a
     * clamp taken there saturates. Returns the seated ledger; [ledger] is not changed.
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
     * [ledger] with [parent]'s active children moved down by one common virtual time `Δ`, once one
     * of them has climbed past its [rebaseThreshold]; `null` when nothing moved (#3329). For a single
     * writer only: [LocalHeddle] calls it. The replicated [schedule] raises the parent's origin
     * instead ([raiseOriginBeneath], #3353), which rewrites no gauge.
     *
     * ## What moves: the effective position
     *
     * A pick reads an edge's **effective** position, its raw position plus the wake offset this
     * allocator stores for it ([HeddlePolicy.effectiveTicks]), so that is what comes down, and what
     * the threshold and `Δ` are measured on. The two parts come down differently:
     * - **the wake offset first**, in whole units. It is a difference, the front less the raw
     *   position at the last wake, and it only grows while a child idles between picks: a busy lane
     *   that waits half the time carries an offset many times its own service, and left alone it
     *   reaches the ceiling while the raw position is a hundredth of the way there.
     * - **then the gauge's fold**. A raw position reads `max(⌈floor · w · G⌉, ticks) +
     *   (issued − folded) · G` ([exactGrossTicksAt]), so raising the fold by `k` units lowers it by
     *   exactly `k · G` ticks. The gauge joins its fold by `max`, so a raised fold survives every
     *   later checkpoint, where a lowered position would be undone by the next one: a checkpoint
     *   `delegate` writes reads this very gauge, so until issuance passes the raised fold it asserts
     *   a lower position at a lower fold, and the join keeps the pair that reads the same.
     *
     * ## Why it changes no pick
     *
     * `Δ` is a whole multiple of `L`, the least common multiple of the children's weight
     * denominators, so `Δ · w · G` is a whole number of units' worth of ticks on every child's grid.
     * Every demanding child's effective position comes down by exactly that, so the weighted mean
     * over them comes down by `Δ`, and `⌈V · w · G⌉` by exactly `Δ · w · G`: eligibility, deadlines
     * and seats are unchanged. The next wake clamp is unchanged too. It stores
     * `max(carried, front − raw)`, and taking `a` ticks off the carried offset while the raw position
     * comes down `a` fewer takes `a` off both sides of the `max`. The pick sequence is the one an
     * engine with no ceiling would make.
     *
     * ## How far
     *
     * `Δ` is the largest multiple of `L` no greater than the smallest effective virtual time among the
     * children [demand] says are **demanding**, so none of them goes below zero. A child that is not
     * demanding comes down at most to `−`[rebaseThreshold]. Anything further would be credit its wake
     * clamp takes back when it next competes (§10.6), and the clamp does take it all back: a child
     * lifted there is at least `R` behind a front at or above zero, so its next clamp exceeds the
     * offset it carries by more than the lift, for a child this allocator has seen idle and with a
     * sleeper credit below `R`. Otherwise the lift is a penalty, never credit. Left alone, an idle
     * child would be carried toward `Long`'s other end one rebase at a time.
     *
     * It does nothing at a parent with an unseated child, which has no gauge to raise, or with a
     * child at its ceiling: that position may stand for more, and a stored position at the ceiling
     * reads there whatever the fold says. Nor while a demanding child is at or below zero, which
     * holds `Δ` at nothing. The threshold sits far enough below the ceiling that a running engine rebases long
     * before either.
     *
     * The engine never reaches the at-ceiling guard: [pickChildRebasing] rebases only after a
     * grant, and a pick over a child at its ceiling throws before it grants. The guard stays as a
     * precondition for a direct caller, which is what `aParentWithAChildAtItsCeilingIsNotRebased`
     * tests.
     *
     * It changes nothing itself: the result is the rebased ledger and the offsets to adopt with it
     * ([adopt]).
     */
    internal fun rebased(parent: GroupId, ledger: EntitlementLedger, demand: (AttachmentId) -> Demand): Rebase? {
        val edges = policyEdges(ledger, parent, demand)
        if (edges.none { pastRebaseThreshold(it) }) return null
        if (edges.any { it.gauge == null || HeddlePolicy.isAtCeiling(it) }) return null
        val l = lcmOfDenominators(edges) ?: return null
        val steps = edges.filter { HeddlePolicy.isDemanding(it) }.minOfOrNull { stepsOf(it, l) ?: return null } ?: return null
        if (steps <= 0L) return null
        val gauges = LinkedHashMap<AttachmentId, Gauge>()
        val offsets = LinkedHashMap<AttachmentId, StoredOffset>()
        for (edge in edges) {
            val w = edge.record.weight
            val full = steps.timesOrNull(l / w.denominator)?.timesOrNull(w.numerator)?.timesOrNull(TICKS_PER_UNIT) ?: return null
            val move = if (HeddlePolicy.isDemanding(edge)) full else minOf(full, roomAboveFloor(edge))
            if (move == 0L) continue
            // The wake offset first, in whole units: it is a difference that only grows while the
            // child idles, so a rebase that left it would let it reach the ceiling on its own.
            val offset = edge.offsetTicks
            val wholeUnits = offset - Int128.of(offset % TICKS_PER_UNIT)
            val absorbed = if (wholeUnits >= Int128.of(move)) move else checkNotNull(wholeUnits.toLongOrNull()) { "below a Long move" }
            if (absorbed > 0L) offsets[edge.record.id] = StoredOffset(offset - absorbed, w)
            val shift = (move - absorbed) / TICKS_PER_UNIT
            if (shift == 0L) continue
            val gauge = checkNotNull(edge.gauge) { "${edge.record.id.value} was seated above" }
            gauges[edge.record.id] = gauge.copy(folded = gauge.folded.plusOrNull(shift) ?: return null)
        }
        if (gauges.isEmpty() && offsets.isEmpty()) return null
        return Rebase(if (gauges.isEmpty()) ledger else ledger.piece(EntitlementLedger.of(gauges = gauges)), offsets)
    }

    /** Whether [edge]'s gross or effective position has climbed past its [rebaseThreshold]. */
    private fun pastRebaseThreshold(edge: PolicyEdge): Boolean {
        val w = edge.record.weight
        val threshold = Int128.of(rebaseThreshold(w))
        val gross = edge.gauge.exactGrossTicksAt(edge.baseIssued, w) - originTicks(edge.origin, w)
        return gross >= threshold || HeddlePolicy.effectiveTicksWide(edge) >= threshold
    }

    /** The least common multiple of [edges]' weight denominators, or `null` when it leaves `Long`. */
    private fun lcmOfDenominators(edges: List<PolicyEdge>): Long? {
        var l = 1L
        for (edge in edges) {
            val d = edge.record.weight.denominator
            l = (l / gcdOf(l, d)).timesOrNull(d) ?: return null
        }
        return l
    }

    /**
     * [edge]'s effective virtual time in whole steps of `l`: `⌊p / (G · w · l)⌋`, computed as
     * `⌊⌊p · d / (n · l)⌋ / G⌋`, or `null` when `n · l` or the quotient leaves `Long`.
     */
    private fun stepsOf(edge: PolicyEdge, l: Long): Long? {
        val w = edge.record.weight
        val inUnits = mulDiv(HeddlePolicy.effectiveTicks(edge), w.denominator, w.numerator.timesOrNull(l) ?: return null, Rounding.FLOOR)
            ?: return null
        return inUnits.floorDiv(TICKS_PER_UNIT)
    }

    /**
     * How far, in ticks and in whole units, [edge]'s effective position can come down before it
     * passes below `−`[rebaseThreshold]: `⌊(p + R) / G⌋ · G`, and none when it is already there.
     */
    private fun roomAboveFloor(edge: PolicyEdge): Long {
        val above = HeddlePolicy.effectiveTicksWide(edge) + rebaseThreshold(edge.record.weight)
        if (above <= Int128.ZERO) return 0L
        return (above.toLongOrNull() ?: Long.MAX_VALUE).floorDiv(TICKS_PER_UNIT) * TICKS_PER_UNIT
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
        if (childWeightsFit(ledger, parent)) HeddlePolicy.front(policyEdges(ledger, parent, demand)) else null

    /**
     * [edge]'s effective virtual service as a pick at [parent] would read it in [ledger]: its
     * [HeddlePolicy.virtualService] plus this allocator's stored wake clamp. `null` when [edge] is
     * not an active child of [parent]. A diagnostic, like [virtualTime]: it observes nothing.
     */
    internal fun effectiveVirtualService(ledger: EntitlementLedger, parent: GroupId, edge: AttachmentId): Rational? =
        policyEdges(ledger, parent, demand = { Demand.NONE })
            .firstOrNull { it.record.id == edge }
            ?.let { ticksToVirtual(HeddlePolicy.effectiveTicks(it), it.record.weight) }

    /**
     * The highest effective position among [parent]'s active children in [ledger], each as a
     * fraction of its own [positionCeiling]: how close a pick there is to refusing (#3353). Zero
     * when [parent] has no active children. A diagnostic that reads this allocator's wake clamps,
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
        offsets: Map<AttachmentId, Int128> = emptyMap(),
    ): Grant? {
        val localHoldings = s.holdings(parent, self) - earmarked
        if (localHoldings <= 0L) return null
        val edges = policyEdges(s, parent, demand, offsets).filter { it.gauge != null }
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
        offsets: Map<AttachmentId, Int128> = emptyMap(),
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
                offsetTicks = offsets[summary.attachment] ?: offsetTicksOf(summary.attachment, record.weight),
                origin = origin,
            )
        }
    }

    /**
     * Seat the **newborns** and clamp the **wakers** under [parent] — the two kinds of joiner —
     * against a single front, [joinFront].
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
        if (first.isEmpty()) return

        val unseated = first.filter { it.gauge == null }.mapTo(HashSet()) { it.record.id }
        val wakers = first.filter { isWaker(it) }.mapTo(HashSet()) { it.record.id }
        val joiners = HashSet(unseated).apply { addAll(wakers) }
        // A pre-seat rebase ([preSeatRaise]) lands first, and everything after reads through it.
        if (raise != null) ledger.mutateOrSkip { it.raiseOrigin(parent, it.origin(parent) + raise) }
        val edges = if (raise != null) policyEdges(ledger.current, parent, demand) else first
        if (joiners.isNotEmpty()) {
            val front = joinFront(edges, joiners)
            if (unseated.isNotEmpty()) {
                ledger.mutateOrSkip { state -> seatPatch(state, unseated, front) }
            }
            clampWakers(edges, wakers, front)
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
     * The third is gone from the competing case too, so that case did change. With a demanding
     * runner below zero, the mean sits below a newborn's origin; the newborn is seated at its origin
     * (a seat cannot go below it) and waits behind the runner, where the old raise clamped the
     * wakers up to it instead. Nothing but `LocalHeddle`'s fold rebase puts a demanding child below
     * zero, and per-parent origins replace that rebase in a later slice of #3353; until then
     * `aNewbornBehindASubZeroRunnerWaitsUntilOrigins` pins the wait.
     *
     * Either way the front is wide and exact (#3353): a child past its ceiling moves it exactly as
     * far as it should, and the pick that follows refuses.
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
     * One patch seating each of [unseated] at [front], each on its own grid
     * ([VirtualTime.ticksForWide]), written absolute: `⌊O · w · G⌋ + ⌈V · w · G⌉` for the parent's
     * origin `O` in [s], wide and exact (#3353). [EntitlementLedger.seatAtWide] refuses an edge that already carries a
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
            // Absolute: the origin this peer sees, plus the front, both on the newborn's own grid (#3353).
            val absolute = originTicks(s.origin(record.parent), record.weight) + front.ticksForWide(record.weight)
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
        dropOffsets(departed)
    }

    /**
     * Enforce §10.6 — "no unlimited idle credit" — for [edges] (issue #1695).
     *
     * Every child that this peer has previously observed **not** competing and that competes now
     * has crossed the idle→demand edge the design §7.2 clamp is defined on. Each such waker is
     * clamped forward to [HeddlePolicy.front] — the front of the set it is rejoining — so it
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
     * **The stored offset is joined with `max`, never replaced** (issue #1714). [HeddlePolicy.front]
     * is a weighted mean over whoever is competing *right now*, and that mean is not monotone
     * across wake cycles: a child that woke into a busy front, ran, and slept again can re-wake
     * beside a starved sibling and compute a *smaller* offset than the one it is already carrying
     * — handing back credit the earlier clamp forfeited and dropping its effective virtual service.
     * Joining keeps the promise made two paragraphs up (a forward offset can only give a turn up)
     * across every wake, and it is the same invariant CFS/EEVDF hold by materialising vruntime so
     * it never decreases against the siblings'. The join is a high-water mark, not a sum, so a long-lived child cannot
     * accumulate an unbounded penalty; and the one-directional stance of §10.5 — credit forbidden,
     * a sliver of penalty merely undesirable — is what makes keeping the larger offset the safe
     * side when the front genuinely regresses. Two other things lower a stored offset. An edge is
     * forgotten when it leaves the active set, which
     * [EntitlementLedger.activate][EntitlementLedger.activate] makes permanent (closure dominance,
     * §10.10), so a cleared clamp can never come back to an edge that will compete again. And
     * a rebase takes whole units off it ([rebased], [adopt]), by exactly as much as it leaves the
     * edge's raw position where it was: the effective position comes down with its siblings', and the next clamp's
     * `max` loses the same amount on both sides, so no turn moves (#3329). The offset is the front
     * less the raw position at the last wake, so for a child that idles between picks it grows by
     * every stretch sat out; without the rebase it would reach the ceiling on its own.
     *
     * The demanding state is sampled **once per scheduling round, at entry**, before any grant
     * lands. Sampling again after the loop would mark a child that its own grants had just
     * satisfied as "idle", making the next round a spurious wake for it.
     *
     * **§10.6 is therefore enforced only modulo that sampling** (issue #1715). §7.2 defines the
     * clamp on an idle→demand *event* this peer has actually observed, and sampling once per
     * [schedule] entry cannot see a window that opens and closes between two samples.
     * Single-peer that is exactly right: the front moves only on this peer's own grants, all of
     * which land after the entry sample, so an idle interval between rounds banks nothing. The
     * escape is multi-peer — a child may idle and re-demand between this peer's rounds while
     * *another* peer's grants advance its siblings, so this peer sees demanding→demanding and does
     * not clamp. Accepted by design: the resulting catch-up burst is capped by this peer's holdings
     * and the §8.2 caps, any peer that did observe the window clamps independently, and wake
     * offsets are scheduler-local anyway, so divergence between peers is already tolerated.
     */
    private fun clampWakers(edges: List<PolicyEdge>, wakers: Set<AttachmentId>, front: VirtualTime) {
        for (edge in edges) {
            if (edge.record.id !in wakers) continue
            val weight = edge.record.weight
            // Wide, both sides (#3353): a waker idle since long before the origin was raised sits far
            // below its siblings, and a clamp narrowed before it is taken would leave it there.
            val computed = HeddlePolicy.clampTicks(
                frontTicks = front.ticksForWide(weight),
                rawTicks = HeddlePolicy.rawTicks(edge),
                sleeperCredit = policy.sleeperCredit,
            )
            // Join, never replace: the front is not monotone across wake cycles (#1714). Both
            // sides are on the waker's grid, and the result is stored on it, so a stored offset
            // never carries a front's denominator into the next one (#3313).
            storeOffset(edge.record.id, weight, maxOf(edge.offsetTicks, computed))
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
    }
}

/**
 * What one [FairShareAllocator.schedule] call did at a parent: delegated something, found nothing to
 * delegate, or stopped because the parent cannot be ordered at all.
 *
 * The two refusals are separate arms because they clear differently. [NothingToDelegate] is the
 * ordinary answer, and it clears by itself once holdings or demand arrive. [PositionCeiling] and
 * [WeightBudget] are properties of the parent's children, and they last until those change.
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
     * A child of [parent] holds a position at its ceiling, so no pick there orders exactly, and none
     * was made (#3313). Nothing was written for the refused pick.
     */
    public data class PositionCeiling(public val parent: GroupId) : AllocationOutcome

    /**
     * [parent]'s active children carry weights whose products a pick forms leave `Long`, so no pick
     * there can be computed (#3353). Nothing was written. A replicated ledger can hold such a set
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
 * One rebase (#3329): [ledger] with the raised gauge folds, and the wake offsets it lowered,
 * [offsets], each the new stored value, zero for one taken off entirely. The two halves are one
 * change: a caller keeps [ledger] and [FairShareAllocator.adopt]s this together, or neither.
 */
internal class Rebase(val ledger: EntitlementLedger, val offsets: Map<AttachmentId, StoredOffset>)

/**
 * One stored wake clamp: [ticks] on the grid of [weight], the weight kept beside it so it can still
 * be exported as the virtual time [AllocatorMemory] holds it in.
 */
internal class StoredOffset(val ticks: Int128, val weight: Weight) {
    /**
     * The clamp as the virtual time it stands for, held at the edge's [positionCeiling]: the form
     * [AllocatorMemory] still keeps a clamp in, which cannot hold one past `Long`. **That hold is on
     * the credit side**: under an origin a waker's raw position can sit far below zero, so a clamp
     * past the ceiling is not a sign its effective position is there, and a clamp held short of the
     * truth hands it the difference after a restore. A bridge for this slice only: #3409 keeps the
     * clamps wide in [AllocatorMemory] and deletes it, and this slice must not ship without that one.
     */
    fun asVirtualTime(): Rational = ticksToVirtual(saturated(ticks, weight), weight)
}

/**
 * One [FairShareAllocator.pickChildRebasing] result: [pick], whose ledger is rebased when [rebase]
 * is not `null`, and [settled], the same ledger before the rebase, which reads right against the
 * allocator's wake offsets until [rebase] is adopted.
 */
internal class RebasingPick(val settled: EntitlementLedger, val pick: ChildPick, val rebase: Rebase?)
