package us.tractat.kuilt.heddle

import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Acceptance tests for the pure EEVDF policy (design §7). The behaviours — weight-
 * ratio convergence, hoarder throttling, returner recovery, neutral creation and
 * no-idle-credit — are the normative invariants §10.5–6 and the §7.3 expectations.
 */
class HeddlePolicyTest {

    /**
     * A mutable child edge for the closed-loop simulation. Weight and [seat] are immutable;
     * the counters and wake offset mutate as the harness applies grants, spends, and returns.
     *
     * [seat] is the edge's virtual-time origin, carried the way production carries it since
     * issue #1752 — a [Gauge] floor with the fold at `0`, so the whole of the edge's issuance
     * is advanced over it. `null` models an edge nothing has seated yet, which reads from its
     * own origin. An exact [Rational] rather than the `Long` the retired
     * `AttachmentRecord.initialVirtualTime` was: a gauge floor never has to be rounded.
     */
    private class Sim(
        val id: String,
        val weight: Weight,
        val seat: Rational? = Rational.ZERO,
        var issued: Long = 0L,
        var returned: Long = 0L,
        var spent: Long = 0L,
        var demand: Demand = Demand(targetOutstanding = 1_000_000L, maximumUsefulGrant = 1_000_000L),
        var offset: Rational = Rational.ZERO,
    ) {
        val record get() = AttachmentRecord(AttachmentId(id), GroupId("root"), GroupId(id), weight)
        val summary get() = EdgeSummary(AttachmentId(id), issued, returned, spent)
        fun edge() = PolicyEdge(record, summary, demand, seat?.let { Gauge(it, folded = 0L) }, issued, offset)
        fun virtualService() = HeddlePolicy.virtualService(edge())

        /** `ev` as the policy sees it — the raw virtual service plus the wake clamp. */
        fun effectiveVirtualService() = virtualService() + offset
    }

    private fun pick(children: List<Sim>, config: PolicyConfig, holdings: Long = 1_000_000L): Grant? =
        HeddlePolicy.pick(children.map { it.edge() }, config, holdings)

    /**
     * The parent's current virtual time `V = Σ w·ev / Σ w` (design §7.3 step 2), computed
     * over [children] in exact rational arithmetic — the value a newly created generation
     * must start at (§7.2).
     */
    private fun parentVirtualTime(children: List<Sim>): Rational {
        var weighted = Rational.ZERO
        var total = Rational.ZERO
        for (c in children) {
            val w = Rational.of(c.weight.numerator, c.weight.denominator)
            weighted += w * c.virtualService()
            total += w
        }
        return weighted / total
    }

    /**
     * `V` over [children]'s **effective** virtual service — [parentVirtualTime] with each child's
     * wake clamp folded in, which is what [HeddlePolicy.pick]'s step 2 actually averages.
     */
    private fun effectiveParentVirtualTime(children: List<Sim>): Rational {
        var weighted = Rational.ZERO
        var total = Rational.ZERO
        for (c in children) {
            val w = Rational.of(c.weight.numerator, c.weight.denominator)
            weighted += w * c.effectiveVirtualService()
            total += w
        }
        return weighted / total
    }

    @Test
    fun pickReturnsTheSingleDemandingChild() {
        val a = Sim("a", Weight.ONE)
        val grant = pick(listOf(a), PolicyConfig(quantum = 5L))
        assertEquals(Grant(AttachmentId("a"), 5L), grant)
    }

    @Test
    fun noCandidatesYieldsNull() {
        assertAll(
            // No demand.
            { assertNull(pick(listOf(Sim("a", Weight.ONE, demand = Demand.NONE)), PolicyConfig(quantum = 5L))) },
            // No holdings.
            { assertNull(pick(listOf(Sim("a", Weight.ONE)), PolicyConfig(quantum = 5L), holdings = 0L)) },
            // Empty edge set.
            { assertNull(HeddlePolicy.pick(emptyList(), PolicyConfig(quantum = 5L), 100L)) },
        )
    }

    @Test
    fun quantumIsTrimmedToTheSmallestCap() {
        // configured 100, demand target 30 (need 30), maxUseful 20, holdings 15 -> 15 wins.
        val a = Sim("a", Weight.ONE, demand = Demand(targetOutstanding = 30L, maximumUsefulGrant = 20L))
        assertEquals(Grant(AttachmentId("a"), 15L), pick(listOf(a), PolicyConfig(quantum = 100L), holdings = 15L))
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Step 3's eligible set is never empty (design §7.3 step 3; issue #1737)
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * The tightest boundary for step 3: with a single candidate, `V` is *exactly* that
     * candidate's effective virtual service, so `ev ≤ V` holds only by equality — and holds,
     * because `V` is an exact [Rational] rather than a float. Nothing rounds, which is why
     * §7.3 step 3's old "if rounding ever yields no eligible candidate" fallback describes a
     * state this policy cannot enter (#1737).
     */
    @Test
    fun theLoneCandidateIsEligibleByExactEquality() {
        // ev = 5 + 11/(3/7) = 92/3 — deliberately non-integral, so equality is not free.
        val only = Sim("o", Weight.of(3L, 7L), seat = Rational.of(5L), issued = 11L, spent = 11L)
        assertAll(
            { assertEquals(Rational.of(92L, 3L), only.virtualService()) },
            { assertEquals(only.virtualService(), front(listOf(only))) },
            { assertEquals(Grant(AttachmentId("o"), 4L), pick(listOf(only), PolicyConfig(quantum = 4L))) },
        )
    }

    /**
     * Step 3's eligible set is never empty, so [HeddlePolicy.pick] never reaches the assertion
     * that replaced §7.3's old minimum fallback (#1737). Two things are checked per round, in
     * the test's own exact arithmetic:
     *
     *  - **the theorem** — `min(ev) ≤ V`, which is what makes the empty set unreachable: `V` is
     *    the weighted mean of the *same* candidate set over strictly positive weights;
     *  - **the winner is eligible** — step 4 never selects from outside `ev ≤ V`.
     *
     * If the invariant ever broke, `pick` itself would throw and fail this sweep — that is what
     * the assertion buys over the old silent fallback, which would have absorbed it.
     *
     * Driven over a seeded random walk through adversarial states — skewed weights, non-zero
     * baselines, children parked ahead by wake clamps — with every quantum trim left slack, so
     * the candidate set is the whole edge set and `V` here is the same `V` the policy computes.
     */
    @Test
    fun stepThreeAlwaysHasAnEligibleCandidate() {
        val rng = Random(1737L)
        var rounds = 0
        var roundsWhereEligibilityBound = 0
        repeat(30) {
            val children = List(2 + rng.nextInt(4)) { i ->
                Sim(
                    id = ('a' + i).toString(),
                    weight = Weight.of(1L + rng.nextInt(9), 1L + rng.nextInt(4)),
                    seat = Rational.of(rng.nextInt(40).toLong()),
                    offset = Rational.of(rng.nextInt(30).toLong()),
                )
            }
            val config = PolicyConfig(quantum = 1L + rng.nextInt(7))
            repeat(60) {
                val v = effectiveParentVirtualTime(children)
                val evs = children.map { it.effectiveVirtualService() }
                assertTrue(evs.min() <= v, "min(ev)=${evs.min()} above V=$v — the eligible set is empty")
                // The pick itself throws if its own eligible set came out empty.
                val grant = assertNotNull(pick(children, config), "no trim binds here, so a grant is always due")
                val winner = children.first { it.id == grant.attachment.value }
                assertTrue(
                    winner.effectiveVirtualService() <= v,
                    "winner ${winner.id} at ev=${winner.effectiveVirtualService()} is above V=$v",
                )
                rounds++
                if (evs.any { it > v }) roundsWhereEligibilityBound++
                winner.issued += grant.amount
                winner.spent += grant.amount
            }
        }
        assertAll(
            // Non-vacuity: the sweep must actually reach step 3, and the filter must actually
            // discard someone — a sweep in which every candidate is trivially eligible would say
            // nothing about the branch that fires when one is not.
            { assertEquals(30 * 60, rounds, "every round must produce a grant") },
            {
                assertTrue(
                    roundsWhereEligibilityBound > rounds / 2,
                    "eligibility must genuinely exclude siblings; it bound in only " +
                        "$roundsWhereEligibilityBound of $rounds rounds",
                )
            },
        )
    }

    /**
     * Two continuously-saturated siblings converge to their weight ratio in committed
     * service (design §7.3, §10 fairness). Weights 3:1 → issued ratio → 3:1.
     */
    @Test
    fun saturatedSiblingsConvergeToWeightRatio() {
        val a = Sim("a", Weight.of(3))
        val b = Sim("b", Weight.of(1))
        val config = PolicyConfig(quantum = 1L)
        repeat(4000) {
            val g = pick(listOf(a, b), config) ?: return@repeat
            val child = if (g.attachment.value == "a") a else b
            child.issued += g.amount
            child.spent += g.amount // saturated: spends immediately, outstanding stays 0
        }
        // committedService_a / w_a ≈ committedService_b / w_b  ⇒  issued_a : issued_b ≈ 3 : 1.
        // EEVDF lag is bounded by O(quantum) per child, so the ratio is tight over 4000 rounds.
        val ratio = a.issued.toDouble() / b.issued.toDouble()
        assertTrue(ratio in 2.9..3.1, "expected ~3:1, got a=${a.issued} b=${b.issued} ratio=$ratio")
    }

    /**
     * Three saturated siblings, weights 4:2:1, converge to committed-service 4:2:1.
     */
    @Test
    fun threeSaturatedSiblingsConvergeToWeightRatio() {
        val a = Sim("a", Weight.of(4))
        val b = Sim("b", Weight.of(2))
        val c = Sim("c", Weight.of(1))
        val config = PolicyConfig(quantum = 1L)
        val byId = mapOf("a" to a, "b" to b, "c" to c)
        repeat(7000) {
            val g = pick(listOf(a, b, c), config) ?: return@repeat
            val child = byId.getValue(g.attachment.value)
            child.issued += g.amount
            child.spent += g.amount
        }
        assertAll(
            { assertTrue((a.issued.toDouble() / b.issued) in 1.9..2.1, "a:b a=${a.issued} b=${b.issued}") },
            { assertTrue((b.issued.toDouble() / c.issued) in 1.9..2.1, "b:c b=${b.issued} c=${c.issued}") },
        )
    }

    /**
     * A child that has over-consumed is deprioritized until its virtual time catches up
     * (design §7.3). Equal weights: the over-consumer gets *nothing* while the fresh
     * sibling climbs to parity.
     */
    @Test
    fun hoarderIsThrottledUntilVirtualTimeCatchesUp() {
        val hoarder = Sim("h", Weight.ONE, issued = 50L, spent = 50L) // committed 50 already
        val fresh = Sim("n", Weight.ONE)
        val config = PolicyConfig(quantum = 1L)
        // While the fresh child is behind, the hoarder is never picked.
        repeat(50) {
            val g = pick(listOf(hoarder, fresh), config) ?: return@repeat
            assertEquals("n", g.attachment.value, "hoarder served too early at fresh.issued=${fresh.issued}")
            fresh.issued += g.amount
            fresh.spent += g.amount
        }
        // Now level (both committed 50); the hoarder becomes eligible again.
        assertEquals(50L, fresh.issued)
        assertEquals(50L, hoarder.issued)
    }

    /**
     * A child that returns entitlement walks its virtual time back and recovers its
     * share (design §7.1 "release walks the child back and restores eligibility").
     */
    @Test
    fun returnerRecoversEligibilityAfterReturning() {
        val returner = Sim("r", Weight.ONE, issued = 50L, spent = 50L)
        val other = Sim("o", Weight.ONE)
        val config = PolicyConfig(quantum = 1L)
        // Before returning, the returner is throttled (behind in virtual time).
        assertEquals("o", pick(listOf(returner, other), config)?.attachment?.value)
        // It returns all 50 committed units: committedService = issued − returned = 0.
        returner.returned = 50L
        returner.spent = 0L
        // Back to parity (both vs = 0): the earliest-id tie-break serves "o" first, but
        // the returner is once again eligible rather than throttled.
        val committedR = returner.summary.committedService
        assertEquals(0L, committedR)
        assertEquals(returner.virtualService(), other.virtualService())
    }

    /**
     * No unlimited idle credit (design §7.2, §10.6): a child that idles while a sibling
     * runs does NOT get a burst of catch-up grants when it wakes — the wake clamp levels
     * it to the current front. Compared head-to-head with the un-clamped case, which
     * *would* let it bank the whole idle interval.
     */
    @Test
    fun wakingChildDoesNotBankIdleCredit() {
        val active = Sim("a", Weight.ONE)
        val sleeper = Sim("s", Weight.ONE, demand = Demand.NONE)
        val config = PolicyConfig(quantum = 1L)
        // 'a' runs alone for 20 rounds; 's' is idle the whole time.
        repeat(20) {
            val g = pick(listOf(active, sleeper), config) ?: return@repeat
            assertEquals("a", g.attachment.value)
            active.issued += g.amount
            active.spent += g.amount
        }
        // 's' wakes. The front is where the scheduler is now: the active sibling's vs.
        val front = active.virtualService()
        sleeper.demand = Demand(targetOutstanding = 1_000_000L, maximumUsefulGrant = 1_000_000L)
        sleeper.offset = HeddlePolicy.wakeOffset(front, sleeper.virtualService(), sleeper.weight, config.sleeperCredit)

        // Over the next 20 rounds the woken child gets ~half — NOT a 20-grant burst.
        var sleeperGrants = 0
        repeat(20) {
            val g = pick(listOf(active, sleeper), config) ?: return@repeat
            val child = if (g.attachment.value == "a") active else sleeper.also { sleeperGrants++ }
            child.issued += g.amount
            child.spent += g.amount
        }
        assertTrue(sleeperGrants in 8..12, "expected a fair ~half share after wake, got $sleeperGrants")
    }

    /**
     * Contrast the clamp: WITHOUT the wake offset, the same sleeper banks the full idle
     * interval and monopolises the next rounds — the exact unfairness §10.6 forbids.
     */
    @Test
    fun withoutWakeClampIdleChildBurstsToCatchUp() {
        val active = Sim("a", Weight.ONE)
        val sleeper = Sim("s", Weight.ONE, demand = Demand.NONE)
        val config = PolicyConfig(quantum = 1L)
        repeat(20) {
            val g = pick(listOf(active, sleeper), config) ?: return@repeat
            active.issued += g.amount
            active.spent += g.amount
        }
        sleeper.demand = Demand(targetOutstanding = 1_000_000L, maximumUsefulGrant = 1_000_000L)
        // No offset applied (offset stays ZERO): sleeper is at vs=0, active at vs=20.
        var sleeperGrants = 0
        repeat(20) {
            val g = pick(listOf(active, sleeper), config) ?: return@repeat
            if (g.attachment.value == "s") sleeperGrants++
            val child = if (g.attachment.value == "a") active else sleeper
            child.issued += g.amount
            child.spent += g.amount
        }
        assertTrue(sleeperGrants >= 19, "un-clamped idle child should burst to catch up, got $sleeperGrants")
    }

    /**
     * Neutral creation (design §7.2, §10.5): a newborn seated at the parent's current virtual
     * time starts level with its sibling — no head start for the parent's whole past.
     * Contrasted with a (wrong) zero baseline, which would burst.
     */
    @Test
    fun neutralCreationGivesNoHeadStart() {
        val incumbent = Sim("o", Weight.ONE)
        val config = PolicyConfig(quantum = 1L)
        repeat(30) {
            val g = pick(listOf(incumbent), config) ?: return@repeat
            incumbent.issued += g.amount
            incumbent.spent += g.amount
        }
        val parentVt = incumbent.virtualService() // 30
        // Newborn created neutrally: its seat is the parent's current virtual time.
        val newborn = Sim("n", Weight.ONE, seat = parentVt)
        assertEquals(incumbent.virtualService(), newborn.virtualService(), "newborn should start level")

        var newbornGrants = 0
        repeat(20) {
            val g = pick(listOf(incumbent, newborn), config) ?: return@repeat
            val child = if (g.attachment.value == "o") incumbent else newborn.also { newbornGrants++ }
            child.issued += g.amount
            child.spent += g.amount
        }
        assertTrue(newbornGrants in 8..12, "neutral newborn should get a fair ~half, got $newbornGrants")
    }

    /**
     * Neutral creation at a **genuinely fractional** parent virtual time (design §7.2, §10.5) —
     * and the receipt that moving the seat into the [Gauge] retired a rounding rule rather than
     * merely relocating it (issue #1752).
     *
     * `V = Σ w·ev / Σ w` is a [Rational] and is almost never integral. While the seat lived on
     * `AttachmentRecord` it was a `Long`, so creation had to round, and the direction carried a
     * fairness sign: rounding *down* seated the newborn **behind** the front — the "lifetime
     * credit" §10.5 forbids — so the rule was the ceiling, accepting a bounded sliver of penalty
     * (`0 ≤ ⌈V⌉ − V < 1`) as the conservative side. A [Gauge.floor] is a `Rational`, so there is
     * no side to pick: the newborn lands on the front **exactly**, and both the credit and the
     * sliver of penalty are gone. [neutralCreationGivesNoHeadStart] cannot see any of this — it
     * creates at `V = 30/1`, where every rounding rule and the exact seat all agree.
     *
     * Here two near-converged siblings put the parent at `V = 109/10`, where the old floor (`10`)
     * and ceiling (`11`) sat either side of the answer.
     *
     * **What this test does and does not prove.** Asserting `newborn.virtualService() == v` would
     * be worthless here — the fixture *sets* the seat to `v` and the read is `v + (0−0)/w`, so it
     * restates itself. The seating decision lives in [EntitlementLedger.seat], and
     * `HeddleControlPlaneTest.theSchedulerSeatsAJoinerAtTheFrontAndSchedulesItFromThere` and
     * `HeddleNodeTest.aNewbornAndAWakerInOneRoundAreSeatedOnTheSameFront` are what pin it. What is
     * observable *here*, at the policy, is the consequence the rounding rule used to cost: a
     * newborn seated exactly on the front is **eligible** in the round it was created in, where the
     * old `⌈V⌉ = 11` put it at `11 > V' = 120/11` and therefore outside the eligible set — a
     * penalty, bounded but real. It is eligible and still does not win, which is the whole of what
     * §10.5 asks for in both directions.
     */
    @Test
    fun neutralCreationAtFractionalVirtualTimeLandsExactlyOnTheFront() {
        // ev(l) = 11/1 and ev(h) = 98/9, so V = (1·11 + 9·(98/9)) / (1 + 9) = 109/10.
        val light = Sim("l", Weight.ONE, issued = 11L, spent = 11L)
        val heavy = Sim("h", Weight.of(9), issued = 98L, spent = 98L)
        val config = PolicyConfig(quantum = 1L)
        val v = parentVirtualTime(listOf(light, heavy))
        assertEquals(Rational.of(109, 10), v, "the fixture must put the parent at a fractional V")

        val newborn = Sim("n", Weight.ONE, seat = v)
        val round = listOf(light, heavy, newborn)

        assertAll(
            // No penalty: seated on the front it is eligible immediately. Seating at the retired
            // ceiling (11) would put it above the round's V and out of the eligible set entirely.
            {
                assertTrue(
                    newborn.effectiveVirtualService() <= effectiveParentVirtualTime(round),
                    "a newborn seated on the front must be eligible in its creation round: " +
                        "ev=${newborn.effectiveVirtualService()} vs V=${effectiveParentVirtualTime(round)}",
                )
            },
            // No credit either: it still does not take the round it was created in. The heavier
            // sibling's deadline (98/9 + 1/9 = 11) beats the newborn's (109/10 + 1 = 119/10).
            {
                assertEquals(
                    "h",
                    pick(round, config)?.attachment?.value,
                    "the newborn must not win the round it was created in",
                )
            },
        )
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // The front — the parent's current virtual time, over the demanding candidates
    // (design §7.2/§7.3 step 2; issue #1688).
    // ─────────────────────────────────────────────────────────────────────────────

    private fun front(children: List<Sim>, excluding: Set<String> = emptySet()): Rational? =
        HeddlePolicy.front(children.map { it.edge() }, excluding.mapTo(HashSet()) { AttachmentId(it) })

    /**
     * The front is the weighted mean over the children that are **actually competing**, not
     * over every ACTIVE child. The two differ the moment a sibling idles, and the difference
     * is a fairness sign: with a runner at `ev = 20` and an idler parked at `0`, the
     * all-ACTIVE mean is `10` — seat a newborn there and it starts half a lifetime behind the
     * front, which is exactly the lifetime credit §10.5 forbids.
     */
    @Test
    fun frontIsTheWeightedMeanOverDemandingCandidatesOnly() {
        val runner = Sim("r", Weight.ONE, issued = 20L, spent = 20L)
        val idler = Sim("i", Weight.ONE, demand = Demand.NONE)
        assertAll(
            { assertEquals(Rational.of(20L), front(listOf(runner, idler))) },
            { assertEquals(Rational.of(10L), parentVirtualTime(listOf(runner, idler))) },
        )
    }

    /**
     * A joiner must be excluded **by hand**. A newborn is excluded for free — it is not an
     * edge yet — but a waker is already ACTIVE and already demanding by the time the clamp is
     * computed, so leaving it in drags the front back toward its own stale virtual service and
     * it banks exactly the idle credit the clamp exists to deny.
     */
    @Test
    fun frontExcludesTheNamedEdges() {
        val runner = Sim("r", Weight.ONE, issued = 20L, spent = 20L)
        val waker = Sim("w", Weight.ONE)
        assertAll(
            { assertEquals(Rational.of(10L), front(listOf(runner, waker))) },
            { assertEquals(Rational.of(20L), front(listOf(runner, waker), excluding = setOf("w"))) },
        )
    }

    /**
     * The front drops [HeddlePolicy.pick]'s holdings and useful-grant trims: those decide who
     * can be *served this round* on *this peer*, which is not the same question as who is
     * competing. A peer with nothing to delegate must still be able to name the front — it may
     * be the one creating the generation.
     */
    @Test
    fun frontIgnoresTheHoldingsAndUsefulGrantTrims() {
        val runner = Sim(
            "r",
            Weight.ONE,
            issued = 20L,
            spent = 20L,
            demand = Demand(targetOutstanding = 100L, maximumUsefulGrant = 0L),
        )
        assertAll(
            { assertEquals(Rational.of(20L), front(listOf(runner))) },
            { assertNull(pick(listOf(runner), PolicyConfig(quantum = 1L)), "…while pick() still declines to serve it") },
        )
    }

    /**
     * Nothing is competing, so there is no mean to take. The fallback is the **maximum**
     * effective virtual service, not the mean: §10.5 is one-directional — credit is forbidden,
     * a sliver of penalty merely undesirable — and a max can only ever give up.
     */
    @Test
    fun frontFallsBackToTheMaximumVirtualServiceWhenNothingDemands() {
        val ahead = Sim("a", Weight.ONE, issued = 30L, spent = 30L, demand = Demand.NONE)
        val behind = Sim("b", Weight.ONE, issued = 10L, spent = 10L, demand = Demand.NONE)
        assertEquals(Rational.of(30L), front(listOf(ahead, behind)))
    }

    /** No edge survives the exclusion ⇒ the front is undefined, and says so rather than dividing by zero. */
    @Test
    fun frontIsNullWhenNoEdgeSurvives() {
        val only = Sim("o", Weight.ONE)
        assertAll(
            { assertNull(HeddlePolicy.front(emptyList())) },
            { assertNull(front(listOf(only), excluding = setOf("o"))) },
        )
    }

    /** `ev = virtualService + virtualOffset`: an already-clamped waker counts at the front it was clamped to. */
    @Test
    fun frontCountsTheWakeClampInEachEdgesVirtualService() {
        val clamped = Sim("c", Weight.ONE, offset = Rational.of(20L))
        assertEquals(Rational.of(20L), front(listOf(clamped)))
    }

    /**
     * The decisive case for #1688: one rule — *a joiner is seated at the front of the currently
     * competing set* — puts a **newborn** and a **waking sibling** on exactly the same effective
     * virtual time. Under the "all ACTIVE children" definition the newborn is seated at the
     * mean instead, which hands it precisely the idle credit the §10.6 clamp denies the idler
     * itself — and durably, since a [Gauge] floor only ever moves *up* under the join while the
     * clamp is a recomputed local offset.
     */
    @Test
    fun newbornAndWakerLandOnTheSameFront() {
        val runner = Sim("r", Weight.ONE, issued = 20L, spent = 20L)
        val sleeper = Sim("s", Weight.ONE, demand = Demand.NONE)
        val config = PolicyConfig(quantum = 1L)

        // The sleeper wakes and is clamped to the front of the set it is joining, itself excluded.
        sleeper.demand = Demand(targetOutstanding = 1_000L, maximumUsefulGrant = 1_000L)
        val wakeFront = assertNotNull(front(listOf(runner, sleeper), excluding = setOf("s")))
        sleeper.offset = HeddlePolicy.wakeOffset(wakeFront, sleeper.virtualService(), sleeper.weight, config.sleeperCredit)

        // A newborn arrives under the same parent, seated at the same front.
        val creationFront = assertNotNull(front(listOf(runner, sleeper)))
        val newborn = Sim("n", Weight.ONE, seat = creationFront)

        assertAll(
            { assertEquals(Rational.of(20L), wakeFront, "the waker is clamped to the runner, not to the mean") },
            {
                assertEquals(
                    sleeper.virtualService() + sleeper.offset,
                    newborn.virtualService(),
                    "waker and newborn must be seated on one front",
                )
            },
        )
    }
}
