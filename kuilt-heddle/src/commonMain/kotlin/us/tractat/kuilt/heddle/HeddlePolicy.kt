package us.tractat.kuilt.heddle

/**
 * One immediate child, as the policy sees it: the immutable attachment metadata
 * ([record] — parent, child and weight), the parent-facing accounting ([summary] —
 * issued/returned/spent), the child's advertised [demand], the replicated virtual-time seat
 * ([gauge] paired with [baseIssued]), and the scheduler-local wake clamp ([virtualOffset]).
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
 * @property virtualOffset scheduler-local forward clamp applied on wake so an idle
 *   child cannot bank a backlog of virtual time (design §7.2). `ZERO` for a child
 *   that never slept; computed by [HeddlePolicy.wakeOffset] on an idle→demand edge —
 *   [HeddleNode] detects that edge and carries the offset here (issue #1695), joining
 *   each new value with the one already stored so a re-wake into a lower front can never
 *   refund an earlier clamp (issue #1714). Deliberately *not* replicated — divergent
 *   offsets reorder locally but never touch conservation.
 */
public data class PolicyEdge(
    public val record: AttachmentRecord,
    public val summary: EdgeSummary,
    public val demand: Demand,
    public val gauge: Gauge?,
    public val baseIssued: Long,
    public val virtualOffset: Rational = Rational.ZERO,
) {
    init {
        require(record.id == summary.attachment) {
            "PolicyEdge record ${record.id} and summary ${summary.attachment} describe different edges"
        }
        require(baseIssued >= 0L) { "PolicyEdge baseIssued must be non-negative, was $baseIssued" }
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
 * @property quantum the largest single grant per pick (`configuredQuantum`); must be `> 0`.
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
     * The steps, all in exact rational arithmetic (design §7.3):
     * 1. **Candidates** — edges whose `additionalNeed = targetOutstanding − outstanding`
     *    is positive, each with a quantum trimmed to demand, holdings, and the caps;
     *    a zero quantum drops the edge.
     * 2. **Parent virtual time** — the weighted mean `V = Σ w·ev / Σ w` over the fixed
     *    candidate set, where `ev` is [effectiveVirtualService].
     * 3. **Eligibility** — keep candidates with `ev ≤ V`. Non-empty *by construction*: `V`
     *    is the weighted mean of these same candidates' `ev` over strictly positive weights,
     *    so `min(ev) ≤ V` always holds, and no rounding can lose the margin — the arithmetic
     *    is exact and an overflow throws rather than returning a wrong order. An empty set
     *    would mean step 2 averaged a *different* set than this filter, or admitted a
     *    non-positive weight; either makes the round's whole ordering untrustworthy, so it
     *    fails loudly instead of scheduling against it (design §7.3 step 3).
     * 4. **Deadline** — among the eligible, the minimum `(ev + q/w, attachmentId)`; the
     *    stable id is the deterministic tie-break.
     *
     * @param edges the parent's immediate children.
     * @param config the round's quantum and caps.
     * @param localHoldings service this peer may itself delegate right now; caps the quantum.
     * @throws IllegalStateException if step 3's eligible set is empty — a policy bug, not an
     *   input the caller can provoke (issue #1737).
     */
    public fun pick(edges: List<PolicyEdge>, config: PolicyConfig, localHoldings: Long): Grant? {
        require(localHoldings >= 0L) { "localHoldings must be non-negative, was $localHoldings" }
        if (localHoldings == 0L) return null

        // 1. Candidates with their trimmed quantum.
        val candidates = edges.mapNotNull { edge ->
            val q = quantumFor(edge, config, localHoldings)
            if (q <= 0L) null else Candidate(edge, q, effectiveVirtualService(edge))
        }
        if (candidates.isEmpty()) return null

        // 2. Weighted-mean parent virtual time V = Σ w·ev / Σ w, over the *trimmed* candidate set
        //    (§7.3 step 2 specifies the candidate set, so this is the correct input here). [front]
        //    shares this same helper but takes it over the **untrimmed** demanding set, so the two
        //    values coincide only when no quantum trim binds — they are not one number.
        val v = weightedMeanVirtualTime(candidates.map { it.edge })

        // 3. Eligible candidates: ev ≤ V. Non-empty is a theorem, not a hope: [v] is the
        //    weighted mean of *these* candidates over strictly positive weights, so it is never
        //    below their minimum, and the comparison is exact (an overflow throws rather than
        //    rounding an order away). Substituting the minimum and carrying on — what design
        //    §7.3 step 3 used to prescribe — would schedule against an ordering just proved
        //    untrustworthy, and do it silently; the assertion names the state instead (#1737).
        val eligible = candidates.filter { it.virtualService <= v }
        check(eligible.isNotEmpty()) {
            val listed = candidates.joinToString { c ->
                "${c.edge.record.id.value} ev=${c.virtualService} w=${c.edge.record.weight}"
            }
            "no eligible candidate at V=$v: min(ev) ≤ V is a theorem of step 2's weighted mean, " +
                "so this is a policy bug (was the mean taken over a different set?). Candidates: $listed"
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
     * grossEv = floor + (baseIssued − folded) / w      (no gauge ⇒ baseIssued / w)
     * ev      = grossEv − returned / w
     * ```
     *
     * `b` is the gauge's [floor][Gauge.floor], carried with the issuance the writer had actually
     * observed when it asserted it, so a stale seat is self-limiting rather than a permanent lie;
     * an edge nothing has seated yet reads from its own origin. The numerator stays *committed*
     * (not merely spent) service, so a grant advances the child the instant it is issued —
     * hoarding is charged — and a return walks it back. Exact rational; never rounded, at either
     * end: the seat is a [Rational] too, which is why the old `⌈V⌉` creation rule is gone.
     *
     * **This is the whole of the policy's virtual-time input.** It shares
     * [grossVirtualServiceAt] with [EntitlementLedger.grossVirtualService] and with the checkpoint
     * `delegate` writes, so the value the writer asserted and the value the scheduler reads are
     * one expression.
     *
     * @throws ArithmeticException if the exact arithmetic would exceed `Long` (§10.12).
     */
    public fun virtualService(edge: PolicyEdge): Rational {
        val weight = edge.record.weight
        return edge.gauge.grossVirtualServiceAt(edge.baseIssued, weight) - perWeight(edge.summary.returned, weight)
    }

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
     * [Gauge] floor, and [wakeOffset] clamps a waking child up to it.
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
     * only ever give up.
     *
     * **Not derivable from replicated state, by design — and no longer required to be.** Demand
     * ages out by *local* receive time and [PolicyEdge.virtualOffset] is deliberately not
     * replicated, so two peers can and do compute different fronts. That used to be safe only by
     * **carriage**: the finished record travelled in the log entry, one proposer's reading became
     * everyone's permanent fact, and a proposer reading a partial view froze a wrong one for good
     * (issue #1713). The seat now goes into the [Gauge], whose join resolves by `max` on the floor
     * rather than preserving the reading — so every peer that still sees the edge unseated may
     * publish its own front and the readings converge instead of racing (issue #1752). A stale
     * peer's low reading is absorbed; a peer that has seen more wins.
     *
     * What that does *not* dissolve is intent divergence: two ungoverned [HeddleNode.prepare]
     * calls supplying different *weights* still union to a divergent record set and starve the
     * child. Drive a generation from one proposer for that reason — not for the seat's sake.
     *
     * @param edges the parent's immediate children.
     * @param excluding edges that must not count toward the front — the joiner, plus any
     *   co-joiners being seated in the same act.
     * @return the front, or `null` when no edge survives [excluding] and there is therefore no
     *   set to take a front of.
     */
    public fun front(edges: List<PolicyEdge>, excluding: Set<AttachmentId> = emptySet()): Rational? {
        val considered = if (excluding.isEmpty()) edges else edges.filterNot { it.record.id in excluding }
        if (considered.isEmpty()) return null
        val demanding = considered.filter { isDemanding(it) }
        // Nothing competing ⇒ no mean to take; fall back to the max, which can only give up.
        if (demanding.isEmpty()) return considered.maxOf { effectiveVirtualService(it) }
        return weightedMeanVirtualTime(demanding)
    }

    /**
     * The forward clamp applied when a child transitions idle→demanding
     * (design §7.2): `max(0, front − vRaw − sleeperCredit / weight)`. Adding this
     * offset to [virtualService] clamps the waker up to the current front (with default
     * `sleeperCredit = 0`, exactly to the front), so it cannot claim a backlog of idle
     * virtual time. The result is the [PolicyEdge.virtualOffset] the caller stores.
     *
     * @param front the parent's virtual time at the moment of waking — [front], with the waker
     *   itself (and any co-waker) excluded.
     * @param vRaw the edge's raw [virtualService].
     * @param weight the edge's weight.
     * @param sleeperCredit bounded credit the waker may keep (default `0`).
     */
    public fun wakeOffset(front: Rational, vRaw: Rational, weight: Weight, sleeperCredit: Long): Rational {
        val creditInVirtual = Rational.of(checkedMul(sleeperCredit, weight.denominator), weight.numerator)
        return Rational.max(Rational.ZERO, front - vRaw - creditInVirtual)
    }

    /** Effective virtual service: [virtualService] plus the scheduler-local wake clamp. */
    private fun effectiveVirtualService(edge: PolicyEdge): Rational =
        virtualService(edge) + edge.virtualOffset

    /**
     * The trimmed quantum for [edge] this round: the smallest of the configured quantum,
     * the child's additional need, its maximum useful grant, this peer's holdings, and
     * the room left under the per-child outstanding cap. A non-positive result means the
     * edge is not a candidate.
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
        )
    }

    /**
     * `V = Σ w·ev / Σ w` over [edges] (design §7.3 step 2), exact rational. The one arithmetic
     * shared by [pick]'s step 2 and [front] — over *different* sets, though: [pick] passes its
     * trimmed candidates, [front] the untrimmed demanding ones. [edges] must be non-empty.
     */
    private fun weightedMeanVirtualTime(edges: List<PolicyEdge>): Rational {
        var weightedSum = Rational.ZERO
        var weightSum = Rational.ZERO
        for (e in edges) {
            val w = Rational.of(e.record.weight.numerator, e.record.weight.denominator)
            weightedSum += w * effectiveVirtualService(e)
            weightSum += w
        }
        return weightedSum / weightSum
    }

    /**
     * Order candidates by virtual deadline `ev + q/w`, then by attachment id. The id
     * tie-break is the deterministic symmetry-breaker: identical deadlines resolve the
     * same way on every replica.
     */
    private val deadlineThenId: Comparator<Candidate> = Comparator { a, b ->
        val da = a.deadline()
        val db = b.deadline()
        val byDeadline = da.compareTo(db)
        if (byDeadline != 0) byDeadline else a.edge.record.id.compareTo(b.edge.record.id)
    }

    private class Candidate(
        val edge: PolicyEdge,
        val quantum: Long,
        val virtualService: Rational,
    ) {
        /** Virtual deadline `ev + q/w = ev + q * den / num`. */
        fun deadline(): Rational {
            val weight = edge.record.weight
            return virtualService + Rational.of(checkedMul(quantum, weight.denominator), weight.numerator)
        }
    }
}
