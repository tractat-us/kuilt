package us.tractat.kuilt.heddle

import us.tractat.kuilt.math.Int128
import us.tractat.kuilt.math.Rounding
import us.tractat.kuilt.math.timesWide

/**
 * One immediate child, as the policy sees it: the immutable attachment metadata
 * ([record] — parent, child and weight), the parent-facing accounting ([summary] —
 * issued/returned/spent), the child's advertised [demand], the replicated virtual-time seat
 * ([gauge] paired with [baseIssued]), its parent's replicated [origin], and the scheduler-local
 * wake clamp ([offsetTicks]).
 *
 * The record and summary must describe the **same** edge; [HeddlePolicy] rejects a
 * mismatch rather than schedule against inconsistent inputs.
 *
 * @property record the edge's parent, child and weight (design §7.1).
 * @property summary the edge's cumulative issued/returned/spent (design §4.5).
 * @property demand how much more the child could usefully take (design §6).
 * @property gauge the edge's replicated virtual-time seat, or `null` when nothing has seated it
 *   yet — in which case the edge reads from its own origin, exactly as an unseated edge should.
 *   Absence is meaningful (it is the seat-bump predicate, see [EntitlementLedger.seat]), so it is
 *   a required parameter: a caller assembling a policy input has to say which it holds.
 * @property baseIssued the edge's **base** issuance — the [Gauge]'s fold axis, and deliberately
 *   *not* [EdgeSummary.issued], which is the effective value and adds relocation credit. Reading
 *   the effective value here would make a relocation-receiving edge appear to have consumed its
 *   whole re-homed strand the instant it arrived (issue #1752, F2); it is also what makes the seat
 *   arrival-order independent. Necessarily `0 ≤ baseIssued ≤ summary.issued`.
 * @property offsetTicks scheduler-local forward clamp applied on wake so an idle child cannot
 *   bank a backlog of virtual time (design §7.2), in ticks of the edge's own service and wide
 *   (#3353). Zero for a child that never slept; computed on an idle→demand edge —
 *   [FairShareAllocator] detects that edge and carries the offset here (issue #1695), joining
 *   each new value with the one already stored so a re-wake into a lower front can never
 *   refund an earlier clamp (issue #1714). Deliberately *not* replicated — divergent
 *   offsets reorder locally but never touch conservation. Non-negative.
 * @property origin the parent's origin in whole virtual-time units ([EntitlementLedger.origin]):
 *   every child of the parent reads its position less `⌊origin · w · G⌋` (#3353). Zero for a parent
 *   no rebase has moved. Non-negative.
 */
public data class PolicyEdge(
    public val record: AttachmentRecord,
    public val summary: EdgeSummary,
    public val demand: Demand,
    public val gauge: Gauge?,
    public val baseIssued: Long,
    public val offsetTicks: Int128 = Int128.ZERO,
    public val origin: Int128 = Int128.ZERO,
) {
    init {
        require(record.id == summary.attachment) {
            "PolicyEdge record ${record.id} and summary ${summary.attachment} describe different edges"
        }
        require(baseIssued >= 0L) { "PolicyEdge baseIssued must be non-negative, was $baseIssued" }
        require(offsetTicks >= Int128.ZERO) { "PolicyEdge offsetTicks must be non-negative, was $offsetTicks" }
        require(origin >= Int128.ZERO) { "PolicyEdge origin must be non-negative, was $origin" }
        require(baseIssued <= summary.issued) {
            "PolicyEdge baseIssued $baseIssued exceeds effective issued ${summary.issued} on ${record.id} — " +
                "the effective value is the base plus non-negative relocation credit, so this pair cannot " +
                "have come from one ledger view"
        }
    }
}

/**
 * Tuning for one allocation round (design §7.3). Every field is a hard cap in
 * service units; the round grants the *smallest* of all the applicable caps.
 *
 * @property quantum the largest single grant per pick (`configuredQuantum`); must be `> 0`. A
 *   grant is never larger than [LocalHeddle.MAX_RESERVATION] whatever this says (#3353): the
 *   weight budget is sized for one lease that large, so a larger grant could carry a child
 *   whose weight fits the budget past its position ceiling.
 * @property perChildOutstandingCap ceiling on a child's outstanding entitlement; a
 *   grant is trimmed so `outstanding` never crosses it. Defaults to unbounded.
 * @property sleeperCredit bounded virtual-time credit a waking child may keep, in the
 *   [wakeOffset][HeddlePolicy.wakeOffset] clamp. Defaults to `0` — the design default,
 *   "no unlimited idle credit" (§10.6). Non-negative.
 */
public data class PolicyConfig(
    public val quantum: Long,
    public val perChildOutstandingCap: Long = Long.MAX_VALUE,
    public val sleeperCredit: Long = 0L,
) {
    init {
        require(quantum > 0L) { "quantum must be positive, was $quantum" }
        require(perChildOutstandingCap >= 0L) { "perChildOutstandingCap must be non-negative, was $perChildOutstandingCap" }
        require(sleeperCredit >= 0L) { "sleeperCredit must be non-negative, was $sleeperCredit" }
    }
}

/**
 * The outcome of one [HeddlePolicy.pick]: delegate [amount] service units down the
 * edge named by [attachment]. The caller applies the patch to its ledger *before*
 * the next pick (design §7.3 step 5).
 *
 * @property attachment which child edge wins this round.
 * @property amount the quantum to delegate; always `> 0`.
 */
public data class Grant(
    public val attachment: AttachmentId,
    public val amount: Long,
)

/**
 * The reference **EEVDF** allocation policy — a pure function from edge summaries,
 * demand, and immutable attachment policy to a single delegation choice
 * (design §7). It inspects nothing else: no global queues, no descendants, no wall
 * clock, no randomness, no floating point. Purity is what makes it testable at
 * virtual time and safe to run divergently on partitioned peers — a bad local
 * decision only *misplaces* entitlement, it can never create any.
 *
 * "EEVDF" = **E**arliest **E**ligible **V**irtual **D**eadline **F**irst: among the
 * children that both want service and are not running ahead of their fair share
 * (*eligible*), serve the one whose next grant would finish soonest in virtual time
 * (*earliest virtual deadline*), breaking ties by a stable identity so every replica
 * picks the same winner.
 *
 * @sample us.tractat.kuilt.heddle.samplePolicyPick
 */
public object HeddlePolicy {

    /**
     * Pick the single child to delegate the next quantum to, or `null` when no child
     * is both eligible and demanding.
     *
     * The steps, on each edge's position `p` — whole ticks of its own service, its virtual time
     * being `p / (G · w)` (design §7.1, [TICKS_PER_UNIT]):
     * 1. **Candidates** — edges whose `additionalNeed = targetOutstanding − outstanding`
     *    is positive, each with a quantum trimmed to demand, holdings, and the caps;
     *    a zero quantum drops the edge.
     * 2. **Parent virtual time** — the weighted mean `V = Σ w·ev / Σ w` over the fixed
     *    candidate set, kept unreduced as `Σ p / (G · Σ w)` ([VirtualTime.mean]).
     * 3. **Eligibility** — keep candidates with `p ≤ ⌈V · w · G⌉`: `ev ≤ V` read on the edge's
     *    own grid, `V` rounded the way a seat rounds it. Non-empty *by construction*: `V` is
     *    the weighted mean of these same candidates over strictly positive weights, so the
     *    candidate with the least `ev` has `p ≤ V · w · G`, and the ceiling only widens that.
     *    An empty set would mean step 2 averaged a *different* set than this filter, or
     *    admitted a non-positive weight; either makes the round's whole ordering
     *    untrustworthy, so it fails loudly instead of scheduling against it (design §7.3
     *    step 3).
     * 4. **Deadline** — among the eligible, the minimum `(ev + q/w, attachmentId)`, compared by
     *    128-bit cross-multiplication; the stable id is the deterministic tie-break.
     *
     * @param edges the parent's immediate children.
     * @param config the round's quantum and caps.
     * @param localHoldings service this peer may itself delegate right now; caps the quantum.
     * @throws IllegalStateException if step 3's eligible set is empty — a policy bug, not an
     *   input the caller can provoke (issue #1737).
     * @throws ArithmeticException if any of [edges] holds a position at its ceiling, which no longer
     *   orders exactly (#3313): [LocalHeddle] answers that with [AcquireOutcome.PositionCeiling], and
     *   [FairShareAllocator.schedule] answers [AllocationOutcome.PositionCeiling]. A deadline
     *   `p + q · G` stays in `Long`: a grant is capped at [LocalHeddle.MAX_RESERVATION], which is
     *   the headroom a position below its ceiling keeps.
     */
    public fun pick(edges: List<PolicyEdge>, config: PolicyConfig, localHoldings: Long): Grant? {
        require(localHoldings >= 0L) { "localHoldings must be non-negative, was $localHoldings" }
        if (localHoldings == 0L) return null
        // Every child, not only the candidates: a saturated sibling moves the mean the others are
        // judged against, so the whole parent stops ordering, not just that child.
        edges.firstOrNull { isAtCeiling(it) }?.let {
            throw PositionCeilingException("${it.record.id.value} is at its position ceiling")
        }

        // 1. Candidates with their trimmed quantum.
        val candidates = edges.mapNotNull { edge ->
            val q = quantumFor(edge, config, localHoldings)
            if (q <= 0L) null else Candidate(edge, q, effectiveTicks(edge))
        }
        if (candidates.isEmpty()) return null

        // 2. Weighted-mean parent virtual time V = Σ w·ev / Σ w, over the *trimmed* candidate set
        //    (§7.3 step 2 specifies the candidate set, so this is the correct input here). [front]
        //    shares this same mean but takes it over the **untrimmed** demanding set, so the two
        //    values coincide only when no quantum trim binds — they are not one number.
        val v = meanOf(candidates.map { it.edge })

        // 3. Eligible candidates: p ≤ ⌈V·w·G⌉. Non-empty is a theorem, not a hope: [v] is the
        //    weighted mean of *these* candidates over strictly positive weights, so the least `ev`
        //    is never above it, and reading V up to the grid can only admit more. Substituting the
        //    minimum and carrying on — what design §7.3 step 3 used to prescribe — would schedule
        //    against an ordering just proved untrustworthy, and do it silently; the assertion
        //    names the state instead (#1737).
        val eligible = candidates.filter { it.ticks <= v.ticksFor(it.edge.record.weight) }
        check(eligible.isNotEmpty()) {
            val listed = candidates.joinToString { c ->
                "${c.edge.record.id.value} p=${c.ticks} w=${c.edge.record.weight}"
            }
            "no eligible candidate at V=${v.toRational()}: min(ev) ≤ V is a theorem of step 2's weighted " +
                "mean, so this is a policy bug (was the mean taken over a different set?). Candidates: $listed"
        }

        // 4. Earliest virtual deadline first, stable id tie-break.
        val winner = eligible.minWith(deadlineThenId)
        return Grant(winner.edge.record.id, winner.quantum)
    }

    /**
     * Raw virtual service of an edge, design §7.1's `b + committedService / weight` — read off the
     * replicated [Gauge] rather than off any field of the record (issue #1752):
     *
     * ```
     * grossEv = (P + (baseIssued − folded) · G) / (G · w)      (no gauge ⇒ baseIssued / w)
     * ev      = grossEv − returned / w
     * ```
     *
     * `P` is the gauge's position, `max(⌈floor · w · G⌉, ticks)`, carried with the issuance the
     * writer had actually observed when it asserted it, so a stale seat is self-limiting rather than a permanent lie;
     * an edge nothing has seated yet reads from its own origin. The numerator stays *committed*
     * (not merely spent) service, so a grant advances the child the instant it is issued —
     * hoarding is charged — and a return walks it back. On the edge's own grid: whole ticks of
     * its service ([TICKS_PER_UNIT]), returned as the virtual time they stand for. Only a seat or
     * a wake clamp rounds (design §7.2); this read is exact for every position this module writes.
     *
     * **This is the whole of the policy's virtual-time input.** It shares
     * [grossVirtualServiceAt] with [EntitlementLedger.grossVirtualService] and with the checkpoint
     * `delegate` writes, so the value the writer asserted and the value the scheduler reads are
     * one expression.
     *
     * @throws ArithmeticException if the exact arithmetic would exceed `Long` (§10.12).
     */
    public fun virtualService(edge: PolicyEdge): Rational =
        ticksToVirtual(saturated(rawTicks(edge), edge.record.weight), edge.record.weight)

    /**
     * [virtualService] as a position: whole ticks of the edge's own service, relative to the
     * parent's origin, wide and never narrowed ([rawTicksAt], #3353).
     */
    internal fun rawTicks(edge: PolicyEdge): Int128 =
        edge.gauge.rawTicksAt(edge.baseIssued, edge.summary.returned, edge.record.weight, edge.origin)

    /** The effective position, wide: [rawTicks] plus the scheduler-local wake clamp, in ticks. */
    internal fun effectiveTicksWide(edge: PolicyEdge): Int128 = rawTicks(edge) + edge.offsetTicks

    /**
     * The position a pick orders: [effectiveTicksWide] narrowed to a `Long`, held at the edge's
     * [positionCeiling] above and at `Long.MIN_VALUE` below. The one narrowing on the way from a
     * gauge to a pick (#3353).
     *
     * The low end saturates on the **penalty** side, raising the position, and never refuses. A
     * demanding child a pick saw is never below zero: no pick's rebase takes it there, and a wake
     * clamps it to the front. One the allocator never saw, seated and below zero, whether idle or
     * carried down by a `LocalHeddle` charging call's rebase, is clamped as a waker
     * ([FairShareAllocator]). So only a child that is not demanding, which no pick orders, can be down
     * there, and a position raised for a child that cannot win costs nothing.
     */
    internal fun effectiveTicks(edge: PolicyEdge): Long {
        val wide = effectiveTicksWide(edge)
        val weight = edge.record.weight
        if (wide >= Int128.of(positionCeiling(weight))) return positionCeiling(weight)
        return wide.toLongOrNull() ?: Long.MIN_VALUE
    }

    /**
     * Whether a pick over [edge] would have to order a position at its ceiling: its effective
     * position, raw plus clamp and narrowed once, is there (#3313). A wake clamp is a difference,
     * not a position, so it has no ceiling of its own (#3353).
     */
    internal fun isAtCeiling(edge: PolicyEdge): Boolean =
        effectiveTicksWide(edge) >= Int128.of(positionCeiling(edge.record.weight))

    /**
     * Is [edge] **currently competing** — does its child want more service than it already
     * holds? `additionalNeed = targetOutstanding − outstanding > 0`, which is [pick]'s step-1
     * candidate predicate with the quantum trims (holdings, `maximumUsefulGrant`, the caps)
     * dropped.
     *
     * Those trims decide who can be *served this round on this peer*; that is a different
     * question from who is competing, and a peer with nothing to delegate must still be able to
     * answer the second one — it may be the one creating a generation. This is the single
     * definition of the set [front] takes its mean over (issue #1688).
     */
    public fun isDemanding(edge: PolicyEdge): Boolean =
        edge.demand.targetOutstanding - edge.summary.outstanding > 0L

    /**
     * The parent's **current virtual time** — the front of the set of children competing under
     * it right now (design §7.2, §7.3 step 2). This is the value a *joiner* is seated at, under
     * one rule for both kinds of joiner: [EntitlementLedger.seat] writes it into a newborn's
     * [Gauge] as its position, and [wakeOffset] clamps a waking child up to it.
     *
     * The set is the **demanding candidates** ([isDemanding]), not every ACTIVE child. The two
     * differ the moment a sibling idles, and the difference has a fairness sign in *both*
     * directions: with a runner at `ev = 20` and an idler parked at `0` the all-ACTIVE mean is
     * `10`, so a newborn seated there starts half the runner's lifetime behind the front — the
     * lifetime credit §10.5 forbids, and precisely the idle credit the §10.6 clamp denies the
     * idler itself. Symmetrically, a sibling that is *satisfied and ahead* pulls the all-ACTIVE
     * mean past the real front and the newborn takes an arbitrary penalty. The mean over the set
     * the joiner will actually compete in is neutral by construction.
     *
     * [excluding] names edges that must not count toward the front. **Both** kinds of joiner have
     * to be named now (issue #1752): a waker is already ACTIVE and already demanding by the time
     * the clamp is computed, and since the seat moved out of the record into the [Gauge] an
     * unseated newborn is an ordinary ACTIVE edge too — reading from its own origin, which is the
     * furthest back anything can read, so leaving it in would drag the front down to meet it and
     * hand it exactly the lifetime credit the seat exists to deny. Co-joiners must be named for
     * the same reason: two siblings joining together would otherwise average each other's
     * origin-or-stale reading into the front and both keep it.
     *
     * When nothing in the surviving set is demanding, the fallback is the **maximum** effective
     * virtual service rather than the mean. §10.5 is one-directional — credit is forbidden, a
     * sliver of penalty is merely undesirable — so the conservative choice is the bound that can
     * only ever give up. [FairShareAllocator] seats and clamps its joiners against a front of its
     * own in that case, the furthest-ahead child counting the joiners too (design §7.2, #3353), so
     * this fallback, over the survivors alone, is what this function reports and not what a joiner
     * is given.
     *
     * **Not derivable from replicated state, by design — and no longer required to be.** Demand
     * ages out by *local* receive time and [PolicyEdge.offsetTicks] is deliberately not
     * replicated, so two peers can and do compute different fronts. That used to be safe only by
     * **carriage**: the finished record travelled in the log entry, one proposer's reading became
     * everyone's permanent fact, and a proposer reading a partial view froze a wrong one for good
     * (issue #1713). The seat now goes into the [Gauge], whose join resolves by `max` on the position
     * rather than preserving the reading — so every peer that grants while it still sees the edge
     * unseated may publish its own front and the readings converge instead of racing (issue #1752). A stale
     * peer's low reading is absorbed; a peer that has seen more wins.
     *
     * What that does *not* dissolve is intent divergence: two ungoverned `HeddleNode.prepare`
     * calls supplying different *weights* still union to a divergent record set and starve the
     * child. Drive a generation from one proposer for that reason — not for the seat's sake.
     *
     * @param edges the parent's immediate children.
     * @param excluding edges that must not count toward the front — the joiner, plus any
     *   co-joiners being seated in the same act.
     * @return the front, or `null` when no edge survives [excluding] and there is therefore no
     *   set to take a front of.
     */
    public fun front(edges: List<PolicyEdge>, excluding: Set<AttachmentId> = emptySet()): Rational? =
        frontTime(edges, excluding)?.toRational()

    /**
     * [front] kept unreduced, for the two writers that use it: a joiner of weight `w` is seated, or
     * clamped, at [VirtualTime.ticksFor] on its own grid. The [Rational] form is for diagnostics;
     * dividing it out is what used to grow every stored denominator (#3313).
     */
    internal fun frontTime(edges: List<PolicyEdge>, excluding: Set<AttachmentId> = emptySet()): VirtualTime? {
        val considered = if (excluding.isEmpty()) edges else edges.filterNot { it.record.id in excluding }
        if (considered.isEmpty()) return null
        val demanding = considered.filter { isDemanding(it) }
        // Nothing competing ⇒ no mean to take; fall back to the max, which can only give up.
        if (demanding.isEmpty()) {
            val top = considered.maxWith { a, b ->
                compareVirtualWide(effectiveTicksWide(a), a.record.weight, effectiveTicksWide(b), b.record.weight)
            }
            return VirtualTime.of(effectiveTicksWide(top), top.record.weight)
        }
        // Wide, so a front over a position past the ceiling is still the exact front (#3353): a
        // joiner seated or clamped against it lands where it belongs, and the pick refuses.
        return meanOf(demanding)
    }

    /**
     * The forward clamp applied when a child transitions idle→demanding
     * (design §7.2): `max(0, front − vRaw − sleeperCredit / weight)`, on the waker's own grid:
     * `max(0, ⌈front · w · G⌉ − p − sleeperCredit · G)` ticks. Adding this offset to
     * [virtualService] clamps the waker up to the current front (with default
     * `sleeperCredit = 0`, to the front rounded up to its grid), so it cannot claim a backlog of
     * idle virtual time. The result is the clamp a caller stores, [PolicyEdge.offsetTicks]: ticks on
     * the waker's grid, wide and never saturated (#3353), so it feeds back into a [PolicyEdge] as it is.
     *
     * @param front the parent's virtual time at the moment of waking — [front], with the waker
     *   itself (and any co-waker) excluded.
     * @param vRaw the edge's raw [virtualService].
     * @param weight the edge's weight.
     * @param sleeperCredit bounded credit the waker may keep (default `0`).
     */
    public fun wakeOffset(front: Rational, vRaw: Rational, weight: Weight, sleeperCredit: Long): Int128 =
        // The front rounds up and the waker's own position down, so an off-grid caller's offset
        // can only overshoot the front, never land short of it (§10.5).
        clampTicks(wideTicksAt(front, weight), wideTicksAt(vRaw, weight, Rounding.FLOOR), sleeperCredit)

    /**
     * [wakeOffset] in ticks, wide: how far a waker at [rawTicks] moves up to reach [frontTicks], less
     * [sleeperCredit] whole units it may keep, and never below zero. Sleeper credit is in service
     * units, not virtual time, so it scales onto the grid by `G` like any other service.
     *
     * Nothing here is narrowed (#3353): a waker idle since long before the parent's origin was
     * raised sits far below its siblings, and the clamp has to carry it the whole way up, or it
     * would keep the difference as credit.
     */
    internal fun clampTicks(frontTicks: Int128, rawTicks: Int128, sleeperCredit: Long): Int128 =
        maxOf(Int128.ZERO, frontTicks - rawTicks - sleeperCredit.timesWide(TICKS_PER_UNIT))

    /**
     * The trimmed quantum for [edge] this round: the smallest of the configured quantum,
     * the child's additional need, its maximum useful grant, this peer's holdings, the room
     * left under the per-child outstanding cap, and [LocalHeddle.MAX_RESERVATION]. A
     * non-positive result means the edge is not a candidate.
     */
    private fun quantumFor(edge: PolicyEdge, config: PolicyConfig, localHoldings: Long): Long {
        val outstanding = edge.summary.outstanding
        val additionalNeed = edge.demand.targetOutstanding - outstanding
        if (additionalNeed <= 0L) return 0L
        // Room under the per-child outstanding cap (design §7.3 lists the cap in the min;
        // interpreted as headroom so a grant never pushes outstanding past the cap).
        val capRoom = if (config.perChildOutstandingCap == Long.MAX_VALUE) {
            Long.MAX_VALUE
        } else {
            config.perChildOutstandingCap - outstanding
        }
        return minOf(
            config.quantum,
            additionalNeed,
            edge.demand.maximumUsefulGrant,
            localHoldings,
            capRoom,
            // The largest lease (#3353): the weight budget's P1′ is sized for one grant this large,
            // so no grant may be larger, whatever [PolicyConfig.quantum] says.
            LocalHeddle.MAX_RESERVATION,
        )
    }

    /**
     * `V = Σ w·ev / Σ w` over [edges] (design §7.3 step 2), kept unreduced as `Σ p / (G · Σ w)`.
     * The one arithmetic shared by [pick]'s step 2 and [front] — over *different* sets, though:
     * [pick] passes its trimmed candidates, [front] the untrimmed demanding ones. [edges] must be
     * non-empty.
     */
    private fun meanOf(edges: List<PolicyEdge>): VirtualTime =
        VirtualTime.mean(edges.map { effectiveTicksWide(it) }, edges.map { it.record.weight })

    /**
     * Order candidates by virtual deadline `ev + q/w`, then by attachment id. On the grid the
     * deadline is the position `p + q · G` at weight `w`, and two are compared by 128-bit
     * cross-multiplication. The id tie-break is the deterministic symmetry-breaker: identical
     * deadlines resolve the same way on every replica.
     */
    private val deadlineThenId: Comparator<Candidate> = Comparator { a, b ->
        val byDeadline = compareVirtual(a.deadline(), a.edge.record.weight, b.deadline(), b.edge.record.weight)
        if (byDeadline != 0) byDeadline else a.edge.record.id.compareTo(b.edge.record.id)
    }

    private class Candidate(
        val edge: PolicyEdge,
        val quantum: Long,
        /** The candidate's effective position, in ticks of its own service. */
        val ticks: Long,
    ) {
        /** The deadline as a position on this edge's grid: `p + q · G`. */
        fun deadline(): Long = checkedAdd(ticks, ticksOf(quantum))
    }
}
