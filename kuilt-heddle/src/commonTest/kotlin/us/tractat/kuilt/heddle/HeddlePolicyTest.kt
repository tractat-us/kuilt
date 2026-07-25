package us.tractat.kuilt.heddle

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Acceptance tests for the pure EEVDF policy (design §7). The behaviours — weight-
 * ratio convergence, hoarder throttling, returner recovery, neutral creation and
 * no-idle-credit — are the normative invariants §10.5–6 and the §7.3 expectations.
 */
class HeddlePolicyTest {

    /**
     * A mutable child edge for the closed-loop simulation. Weight and virtual-time
     * origin are immutable; the counters and wake offset mutate as the harness applies
     * grants, spends, and returns.
     */
    private class Sim(
        val id: String,
        val weight: Weight,
        val initialVirtualTime: Long = 0L,
        var issued: Long = 0L,
        var returned: Long = 0L,
        var spent: Long = 0L,
        var demand: Demand = Demand(targetOutstanding = 1_000_000L, maximumUsefulGrant = 1_000_000L),
        var offset: Rational = Rational.ZERO,
    ) {
        val record get() = AttachmentRecord(AttachmentId(id), GroupId("root"), GroupId(id), weight, initialVirtualTime)
        val summary get() = EdgeSummary(AttachmentId(id), issued, returned, spent)
        fun edge() = PolicyEdge(record, summary, demand, offset)
        fun virtualService() = HeddlePolicy.virtualService(record, summary)
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
     * Neutral creation (design §7.2, §10.5): a newborn whose `initialVirtualTime` is the
     * parent's current virtual time starts level with its sibling — no head start for the
     * parent's whole past. Contrasted with a (wrong) zero baseline, which would burst.
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
        // Newborn created neutrally: its baseline is the parent's current virtual time.
        val newborn = Sim("n", Weight.ONE, initialVirtualTime = parentVt.numerator / parentVt.denominator)
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
     * Neutral creation at a **genuinely fractional** parent virtual time (design §7.2, §10.5).
     *
     * `V = Σ w·ev / Σ w` is a [Rational] and is almost never integral, while
     * [AttachmentRecord.initialVirtualTime] is a `Long` — so creation must round, and the
     * direction carries a fairness sign. Rounding *down* seats the newborn **behind** the
     * front, which is exactly the "lifetime credit" §10.5 forbids; rounding *up* is
     * conservative. [neutralCreationGivesNoHeadStart] cannot see this: it creates at `V = 30/1`,
     * where every rounding rule agrees.
     *
     * Here two near-converged siblings put the parent at `V = 109/10`, and the floor (`10`)
     * versus the ceiling (`11`) is the whole difference between the newborn taking the very
     * next grant and waiting its turn.
     */
    @Test
    fun neutralCreationAtFractionalVirtualTimeGivesNoHeadStart() {
        // ev(l) = 11/1 and ev(h) = 98/9, so V = (1·11 + 9·(98/9)) / (1 + 9) = 109/10.
        val light = Sim("l", Weight.ONE, issued = 11L, spent = 11L)
        val heavy = Sim("h", Weight.of(9), issued = 98L, spent = 98L)
        val config = PolicyConfig(quantum = 1L)
        val v = parentVirtualTime(listOf(light, heavy))
        assertEquals(Rational.of(109, 10), v, "the fixture must put the parent at a fractional V")

        val newborn = Sim("n", Weight.ONE, initialVirtualTime = v.numerator / v.denominator)

        assertAll(
            // §10.5: a newborn never starts behind the front — that is lifetime credit.
            {
                assertTrue(
                    newborn.virtualService() >= v,
                    "newborn at ${newborn.virtualService()} starts behind the front $v — a head start",
                )
            },
            // ...and the rounding is a bounded sliver forward, never an arbitrary penalty.
            {
                assertTrue(
                    newborn.virtualService() - v < Rational.ONE,
                    "newborn at ${newborn.virtualService()} is more than one virtual unit past the front $v",
                )
            },
            // Behaviourally: it does not take the round it was created in. Seated at the floor
            // it is the only eligible candidate and wins the grant outright.
            {
                assertEquals(
                    "h",
                    pick(listOf(light, heavy, newborn), config)?.attachment?.value,
                    "the newborn must not win the round it was created in",
                )
            },
        )
    }
}
