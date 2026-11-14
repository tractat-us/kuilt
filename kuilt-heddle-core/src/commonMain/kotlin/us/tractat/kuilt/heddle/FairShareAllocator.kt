package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId

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
    private val wakeOffsets = HashMap<AttachmentId, Rational>()
    private val demandingObserved = HashMap<AttachmentId, Boolean>()

    /**
     * An allocator that resumes from [memory], as if it were the allocator [memory] was exported
     * from. Building one over a copy of a live allocator's [FairShareAllocator.memory] gives a
     * throwaway that picks exactly as the live one would next.
     */
    public constructor(self: ReplicaId, policy: PolicyConfig, memory: AllocatorMemory) : this(self, policy) {
        wakeOffsets.putAll(memory.wakeOffsets)
        demandingObserved.putAll(memory.demandingObserved)
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
        )

    /**
     * An allocator that remembers exactly what this one does, and that later calls on either leave
     * the other alone: [memory] without the sort, for a caller that copies on every call.
     */
    internal fun fork(): FairShareAllocator = FairShareAllocator(self, policy).also {
        it.wakeOffsets.putAll(wakeOffsets)
        it.demandingObserved.putAll(demandingObserved)
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
     * @param earmarked units this peer has reserved at [parent] as a leaf. They are subtracted from
     *   the holdings it may delegate: a later completion charges its captured path without a gate,
     *   relying on the earmark to have kept the units, so delegating them would overspend (design
     *   §10.1, §10.7).
     * @param demand the current appetite of one child edge, [Demand.NONE] for an edge that wants
     *   nothing. Asked once per active child each time the policy input is built, so it should
     *   answer from live state rather than a copy taken before the call.
     * @return the count of grants applied this call. It stops early, with nothing written for the
     *   pick it stops at, when a child of [parent] reaches its position ceiling (#3313).
     */
    public fun schedule(
        parent: GroupId,
        ledger: LedgerCell,
        earmarked: Long,
        demand: (AttachmentId) -> Demand,
    ): Int {
        settleJoiners(parent, ledger, demand)
        var grants = 0
        while (grants < MAX_ROUNDS_PER_CALL) {
            // One pick, inside the cell's write step, on the state the grant lands on. Nothing
            // delegable ⇒ mutateOrSkip writes nothing and answers false (#2090). Peeking on
            // `ledger.current` first would run the policy twice per grant, the peek deciding
            // against a state an inbound write may already have moved past.
            val applied = try {
                ledger.mutateOrSkip { s ->
                    pickOne(s, parent, earmarked, demand)?.let { grant -> s.delegate(self, grant.attachment, grant.amount) }
                }
            } catch (_: PositionCeilingException) {
                // The pick would have to order a position at its ceiling (#3313): grant nothing more
                // here. The throw left the transform before it returned a patch, so nothing was written.
                false
            }
            if (!applied) break
            grants++
        }
        return grants
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
     * Seat every unseated active child of [parent] in [ledger], at the front of the seated ones as
     * [demand] sees them, **without** observing anything: the wake memory is left exactly as it
     * was. For a delegation that is not a pick, which must still never cross an unseated edge, and
     * which has no fresh view of who is waiting to observe. Returns [ledger] itself when nothing
     * under [parent] is unseated.
     */
    internal fun seatUnseated(
        parent: GroupId,
        ledger: EntitlementLedger,
        demand: (AttachmentId) -> Demand,
    ): EntitlementLedger {
        val edges = policyEdges(ledger, parent, demand)
        val unseated = edges.filter { it.gauge == null }.mapTo(HashSet()) { it.record.id }
        if (unseated.isEmpty()) return ledger
        val front = HeddlePolicy.frontTime(edges, excluding = unseated)
        val patch = seatPatch(ledger, unseated, front) ?: return ledger
        return ledger.piece(patch.delta)
    }

    /**
     * [parent]'s **current virtual time** `V` in [ledger] — this allocator's front (design §7.2;
     * issue #1688). `null` when [parent] has no active children in [ledger].
     *
     * A **diagnostic**: it depends on [demand] and on this allocator's non-replicated wake clamps,
     * so two peers legitimately read different values at the same instant.
     */
    public fun virtualTime(
        ledger: EntitlementLedger,
        parent: GroupId,
        demand: (AttachmentId) -> Demand,
    ): Rational? = HeddlePolicy.front(policyEdges(ledger, parent, demand))

    /**
     * [edge]'s effective virtual service as a pick at [parent] would read it in [ledger]: its
     * [HeddlePolicy.virtualService] plus this allocator's stored wake clamp. `null` when [edge] is
     * not an active child of [parent]. A diagnostic, like [virtualTime]: it observes nothing.
     */
    internal fun effectiveVirtualService(ledger: EntitlementLedger, parent: GroupId, edge: AttachmentId): Rational? =
        policyEdges(ledger, parent) { Demand.NONE }
            .firstOrNull { it.record.id == edge }
            ?.let { ticksToVirtual(HeddlePolicy.effectiveTicks(it), it.record.weight) }

    /**
     * Build the policy input at [parent] from [s] and run one [HeddlePolicy.pick].
     *
     * **An unseated edge is not a candidate** (issue #1752). [settleJoiners] seats every active
     * child before the first pick, but it and each pick are separate writes, and nothing stops a
     * remote `Activate` from putting a fresh, gauge-absent edge into [s] partway through the loop.
     * Such an edge reads `ev = baseIssued / w = 0`, which makes it maximally eligible; and if it
     * won, [EntitlementLedger.delegate] would write its checkpoint at that origin,
     * [EntitlementLedger.seat] would then refuse it forever (a gauge exists), and the componentwise
     * join could never lift a floor that is already the minimum. One merge landing at the wrong
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
                virtualOffset = wakeOffsets[summary.attachment] ?: Rational.ZERO,
            )
        }
    }

    /**
     * Seat the **newborns** and clamp the **wakers** under [parent] — the two kinds of joiner —
     * against a single front taken over the set neither of them is in.
     *
     * **One front, one exclusion set, and that is the whole point** (issue #1752). Both joiners are
     * measured against "where the competing set is right now", and each is, in its own way, reading
     * from further back than that: a newborn carries no [Gauge] and so reads from its own origin,
     * the furthest back anything can read, while a waker is still carrying the stale virtual service
     * it slept on and this round's clamp has not been computed yet. Leave *either* in and it drags
     * the front down to meet itself; leave both in and they average each other's staleness and both
     * bank the difference. Seating them in two passes over two fronts is the same defect wearing a
     * schedule: with a runner at `ev = 200` and a waker at `0`, a seat-then-clamp order seats a
     * newborn at `100` and then clamps the waker to `mean(200, 100) = 150`, when both belong at
     * `200`. The newborn's share of that is **permanent** — it is frozen into a [Gauge.floor] the
     * join only ever ratchets up — which is exactly the §10.5 lifetime credit the seat exists to
     * deny. [HeddlePolicy.front]'s KDoc states the rule; this is the one place that has to honour it.
     *
     * **A `null` front means every active child is a joiner**, i.e. nobody is left to be level with,
     * and the seat bump then writes [Rational.ZERO]. That is not a guess: [EntitlementLedger.seat]
     * stores `max(front, baseIssued / w)`, so a zero front seats an edge exactly where it already
     * reads, pinning it without moving it. Pinning is the point — it stops a sibling that happens to
     * be served first from becoming the front the *others* are then seated behind, which is how a
     * cohort of newborns would otherwise be split by nothing but pick order. And if this view is
     * merely stale rather than genuinely empty, the componentwise join repairs it: a better-informed
     * peer's higher floor wins the `max`, so the low reading is absorbed rather than frozen. A
     * `null` front leaves the wakers unclamped, which is the same judgement in the other direction —
     * there is no one to be clamped *to*.
     *
     * Publishes at most one patch.
     */
    private fun settleJoiners(parent: GroupId, ledger: LedgerCell, demand: (AttachmentId) -> Demand) {
        val s = ledger.current
        val edges = policyEdges(s, parent, demand)
        forgetDepartedChildren(edges, s, parent)
        if (edges.isEmpty()) return

        val unseated = edges.filter { it.gauge == null }.mapTo(HashSet()) { it.record.id }
        val wakers = edges
            .filter { HeddlePolicy.isDemanding(it) && demandingObserved[it.record.id] == false }
            .mapTo(HashSet()) { it.record.id }
        val joiners = HashSet(unseated).apply { addAll(wakers) }
        val front = if (joiners.isEmpty()) null else HeddlePolicy.frontTime(edges, excluding = joiners)

        if (unseated.isNotEmpty()) {
            ledger.mutateOrSkip { state -> seatPatch(state, unseated, front) }
        }
        if (front != null) clampWakers(edges, wakers, front)
        for (edge in edges) demandingObserved[edge.record.id] = HeddlePolicy.isDemanding(edge)
    }

    /**
     * One patch seating each of [unseated] at [front], each on its own grid
     * ([VirtualTime.ticksFor]), or at its own origin when [front] is `null`.
     * [EntitlementLedger.seatAt] refuses an edge that already carries a gauge, so an id whose seat
     * arrived between the front being read and this patch being built is simply skipped rather
     * than re-seated.
     *
     * The seats are gathered into one gauge map and assembled once. Joining them one at a time
     * copied the growing patch on every seat, which made a cohort of `n` newborns cost `n²` (5,000
     * of them took 4.9 s on a Kotlin/Native debug build, #3173).
     */
    private fun seatPatch(
        s: EntitlementLedger,
        unseated: Set<AttachmentId>,
        front: VirtualTime?,
    ): Patch<EntitlementLedger>? {
        val gauges = LinkedHashMap<AttachmentId, Gauge>()
        for (id in unseated.sorted()) {
            val weight = s.record(id)?.weight ?: continue
            val seat = s.seatAt(id, front?.ticksFor(weight) ?: 0L) ?: continue
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
     * the front and both keep the credit. A `null` front means nothing survived the exclusion —
     * every **active** child under the parent is a waker, so nobody ran ahead of anybody and there
     * is nothing to clamp to. Surviving children that are merely *idle* do not produce a `null`:
     * [HeddlePolicy.front] falls back to the maximum effective virtual service across them, the
     * bound that can only ever give a turn up (§7.2).
     *
     * **The stored offset is joined with `max`, never replaced** (issue #1714). [HeddlePolicy.front]
     * is a weighted mean over whoever is competing *right now*, and that mean is not monotone
     * across wake cycles: a child that woke into a busy front, ran, and slept again can re-wake
     * beside a starved sibling and compute a *smaller* offset than the one it is already carrying
     * — handing back credit the earlier clamp forfeited and dropping its effective virtual service.
     * Joining keeps the promise made two paragraphs up (a forward offset can only give a turn up)
     * across every wake, and it is the same invariant CFS/EEVDF hold by materialising vruntime so
     * it never decreases. The join is a high-water mark, not a sum, so a long-lived child cannot
     * accumulate an unbounded penalty; and the one-directional stance of §10.5 — credit forbidden,
     * a sliver of penalty merely undesirable — is what makes keeping the larger offset the safe
     * side when the front genuinely regresses. Nothing else lowers a stored offset: an edge is
     * forgotten only when it leaves the active set, which
     * [EntitlementLedger.activate][EntitlementLedger.activate] makes permanent (closure dominance,
     * §10.10), so a cleared clamp can never come back to an edge that will compete again.
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
            val computed = HeddlePolicy.clampTicks(
                frontTicks = front.ticksFor(weight),
                rawTicks = HeddlePolicy.rawTicks(edge),
                sleeperCredit = policy.sleeperCredit,
                weight = weight,
            )
            // Join, never replace: the front is not monotone across wake cycles (#1714). Both
            // sides are on the waker's grid, and the result is stored on it, so a stored offset
            // never carries a front's denominator into the next one (#3313).
            val carried = ticksAt(wakeOffsets[edge.record.id] ?: Rational.ZERO, weight)
            // Held at the ceiling, like any position, so it stays representable; a pick over a clamp
            // there refuses ([HeddlePolicy.isAtCeiling]).
            wakeOffsets[edge.record.id] = ticksToVirtual(minOf(maxOf(carried, computed), positionCeiling(weight)), weight)
        }
    }

    /** A [LedgerCell] over a value, for [pickChild]: one caller, no other writer. */
    private class ValueCell(override var current: EntitlementLedger) : LedgerCell {
        override fun mutateOrSkip(transform: (EntitlementLedger) -> Patch<EntitlementLedger>?): Boolean {
            val patch = transform(current) ?: return false
            current = current.piece(patch.delta)
            return true
        }
    }

    private companion object {
        /** A backstop against a policy that never runs dry; one call never applies more grants. */
        const val MAX_ROUNDS_PER_CALL: Int = 100_000
    }
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
