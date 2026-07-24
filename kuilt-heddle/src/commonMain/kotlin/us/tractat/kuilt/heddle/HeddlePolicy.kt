package us.tractat.kuilt.heddle

/**
 * One immediate child, as the policy sees it: the immutable attachment metadata
 * ([record] — weight and virtual-time origin), the parent-facing accounting
 * ([summary] — issued/returned/spent), the child's advertised [demand], and the
 * scheduler-local wake clamp ([virtualOffset]).
 *
 * The record and summary must describe the **same** edge; [HeddlePolicy] rejects a
 * mismatch rather than schedule against inconsistent inputs.
 *
 * @property record the edge's weight and `initialVirtualTime` (design §7.1).
 * @property summary the edge's cumulative issued/returned/spent (design §4.5).
 * @property demand how much more the child could usefully take (design §6).
 * @property virtualOffset scheduler-local forward clamp applied on wake so an idle
 *   child cannot bank a backlog of virtual time (design §7.2). `ZERO` for a child
 *   that never slept; computed by [HeddlePolicy.wakeOffset] on an idle→demand edge.
 *   Deliberately *not* replicated — divergent offsets reorder locally but never touch
 *   conservation.
 */
public data class PolicyEdge(
    public val record: AttachmentRecord,
    public val summary: EdgeSummary,
    public val demand: Demand,
    public val virtualOffset: Rational = Rational.ZERO,
) {
    init {
        require(record.id == summary.attachment) {
            "PolicyEdge record ${record.id} and summary ${summary.attachment} describe different edges"
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
     * 3. **Eligibility** — keep candidates with `ev ≤ V` (the min `ev` always qualifies,
     *    so with candidates present this set is non-empty).
     * 4. **Deadline** — among the eligible, the minimum `(ev + q/w, attachmentId)`; the
     *    stable id is the deterministic tie-break.
     *
     * @param edges the parent's immediate children.
     * @param config the round's quantum and caps.
     * @param localHoldings service this peer may itself delegate right now; caps the quantum.
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

        // 2. Weighted-mean parent virtual time V = Σ w·ev / Σ w.
        val v = weightedMeanVirtualTime(candidates)

        // 3. Eligible candidates: ev ≤ V. The minimum ev is always ≤ the mean, so the
        //    eligible set is non-empty; the min-ev fallback is defensive only.
        val eligible = candidates.filter { it.virtualService <= v }
            .ifEmpty { listOf(candidates.minBy { it.virtualService }) }

        // 4. Earliest virtual deadline first, stable id tie-break.
        val winner = eligible.minWith(deadlineThenId)
        return Grant(winner.edge.record.id, winner.quantum)
    }

    /**
     * Raw virtual service of an edge, `b + committedService / weight` (design §7.1),
     * where `b = initialVirtualTime` and `committedService = issued − returned`.
     * Because the numerator is *committed* (not merely spent) service, a grant advances
     * the child the instant it is issued — hoarding is charged — and a return walks it
     * back. Exact rational; never rounded.
     */
    public fun virtualService(record: AttachmentRecord, summary: EdgeSummary): Rational {
        val committed = summary.committedService
        val weight = record.weight
        // committed / (num/den) = committed * den / num
        return Rational.of(record.initialVirtualTime) +
            Rational.of(checkedMul(committed, weight.denominator), weight.numerator)
    }

    /**
     * The forward clamp applied when a child transitions idle→demanding
     * (design §7.2): `max(0, front − vRaw − sleeperCredit / weight)`. Adding this
     * offset to [virtualService] clamps the waker up to the current front (with default
     * `sleeperCredit = 0`, exactly to the front), so it cannot claim a backlog of idle
     * virtual time. The result is the [PolicyEdge.virtualOffset] the caller stores.
     *
     * @param front the parent's virtual time at the moment of waking.
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
        virtualService(edge.record, edge.summary) + edge.virtualOffset

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

    /** `V = Σ w·ev / Σ w` over the candidate set (design §7.3 step 2), exact rational. */
    private fun weightedMeanVirtualTime(candidates: List<Candidate>): Rational {
        var weightedSum = Rational.ZERO
        var weightSum = Rational.ZERO
        for (c in candidates) {
            val w = Rational.of(c.edge.record.weight.numerator, c.edge.record.weight.denominator)
            weightedSum += w * c.virtualService
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
