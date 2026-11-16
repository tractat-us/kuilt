package us.tractat.kuilt.heddle

import us.tractat.kuilt.math.Int128
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The bridge (#3501, design §7.1 "The position limit"): no rule on the weights keeps a pick inside
 * 128 bits, because a joiner is seated level with siblings that may have run a long way. `fine`
 * (`1/2¹⁴`) is leased [MAX] at a time, `four` (`4`) joins at its front and is leased, `fine`
 * leaves, and `heavy` (`2⁵⁸`) is applied beside `four`. Every set passes the weight budget. Before
 * the position limit, 160 leases put `heavy`'s seat past 128 bits and every pick threw; now
 * [LocalHeddle.apply] refuses `heavy` alone and `four` goes on being leased.
 *
 * The control is 16 leases, which seats `heavy` at about `2¹²⁴` ticks, under the `2¹²⁵` limit: it
 * lands and is picked. 64 leases, the count at which the picks stopped throwing before the limit,
 * seats it near `2¹²⁶`, which the limit refuses two bits sooner.
 */
class W1BridgeProbeTest {

    private val fine = Weight.of(1L, 1L shl 14)
    private val four = Weight.of(4L)
    private val heavy = Weight.of(1L shl 58)
    private val twoHeavy = Weight.of(1L shl 57)

    private fun tree(vararg lanes: Pair<String, Weight>) = draft("p") { for ((name, w) in lanes) lane(name, w) }

    private class Bridge(val h: LocalHeddle, val applies: List<ApplyResult>, val heavyApply: ApplyResult)

    private fun bridge(leases: Int): Bridge {
        val first = tree("fine" to fine)
        val h = LocalHeddle.bootWhole(first, LocalPolicy(defaultReservation = 1L), epoch = 1L)
        val f = first.groupId("fine")
        val m = tree("four" to four).groupId("four")
        h.mintSupply(1L shl 53)
        repeat(leases) { lease(h, setOf(f), f, it.toLong()) }
        val withFour = h.apply(tree("fine" to fine, "four" to four), generation = 1L)
        lease(h, setOf(f, m), m, 1_000L)
        val withoutFine = h.apply(tree("four" to four), generation = 2L)
        val withHeavy = h.apply(tree("four" to four, "heavy" to heavy), generation = 3L)
        return Bridge(h, listOf(withFour, withoutFine), withHeavy)
    }

    /** One lease of [MAX] for [leaf], picked from [waiting], completed in full. */
    private fun lease(h: LocalHeddle, waiting: Set<GroupId>, leaf: GroupId, at: Long) {
        val out = h.acquire(waiting, setOf(leaf), startedAt = at, reservation = MAX)
        val lease = assertIs<AcquireOutcome.Acquired>(out, "the rig: ${leaf.value} leases").lease
        assertIs<LeaseOutcome.Done>(h.complete(lease.id, MAX, waiting = emptySet()))
    }

    @Test
    fun aHeavyJoinerPastThePositionLimitIsRefusedAloneAndItsSiblingIsLeased() {
        val b = bridge(leases = 160)
        val m = tree("four" to four).groupId("four")
        val x = tree("heavy" to heavy).groupId("heavy")
        val afterwards = b.h.acquire(setOf(m, x), setOf(m, x), startedAt = 2_000L, reservation = MAX)
        assertAll(
            { assertTrue(weightsFitTheBudget(listOf(fine, four)) && weightsFitTheBudget(listOf(four, heavy)), "the rig: every set fits the budget") },
            { assertEquals(listOf(emptyList<StepRefusal>(), emptyList()), b.applies.map { it.refusals }, "the rig: four joined and fine left") },
            { assertEquals(listOf(x), b.heavyApply.refusals.map { it.child }, "apply refuses heavy alone") },
            { assertTrue(b.heavyApply.refusals.single() is StepRefusal.Refused, "as a refusal: ${b.heavyApply.refusals}") },
            { assertEquals(m, assertIs<AcquireOutcome.Acquired>(afterwards, "four goes on being leased").lease.leaf) },
        )
    }

    @Test
    fun aHeavyJoinerUnderThePositionLimitLandsAndIsPicked() {
        val b = bridge(leases = 16)
        val m = tree("four" to four).groupId("four")
        val x = tree("heavy" to heavy).groupId("heavy")
        val picked = b.h.acquire(setOf(m, x), setOf(x), startedAt = 2_000L, reservation = MAX)
        assertAll(
            { assertEquals(listOf(emptyList<StepRefusal>(), emptyList()), b.applies.map { it.refusals }, "the rig: four joined and fine left") },
            { assertEquals(emptyList(), b.heavyApply.refusals, "heavy lands") },
            { assertEquals(x, assertIs<AcquireOutcome.Acquired>(picked, "and is picked").lease.leaf) },
        )
    }

    /**
     * The waker (design §7.1): a child already active, seen idle, that asks again at a front its
     * clamp cannot reach inside the limit is never seen by `apply`, so the step refuses it. `light`'s
     * position is planted at `2⁹⁴` ticks, a write any peer could make, so the front is `2⁹⁰`, and
     * `heavy`'s clamp there is `2⁹⁰ · 2⁴⁰ · 16 = 2¹³⁴` ticks. Asking for `heavy` answers
     * `LifetimeExhausted(root)` and changes nothing, the wake memory included. With `heavy` left out
     * of the waiting set, `light` leases.
     */
    @Test
    fun aWakerWhoseClampWouldPassTheLimitIsRefusedAndChangesNothing() {
        val shape = tree("light" to Weight.ONE, "heavy" to Weight.of(1L shl 40))
        val l = shape.groupId("light")
        val x = shape.groupId("heavy")
        val h = LocalHeddle.bootWhole(shape, LocalPolicy(defaultReservation = 1L), epoch = 1L)
        h.mintSupply(1L shl 50)
        // heavy is idle from the start, so it is a waker, never a returner by the clock.
        repeat(2) { lease(h, setOf(l), l, 1L + it) }
        val edge = checkNotNull(h.snapshot().ledger.lineageOf(l)).last()
        h.reshapeForTest { ledger ->
            ledger.piece(EntitlementLedger.of(gauges = mapOf(edge to gaugeAt(Int128.fromTwosComplement(1L shl 30, 0L), folded = ledger.baseIssuance(edge)))))
        }
        val before = h.snapshot()
        val refused = h.acquire(setOf(l, x), setOf(x), startedAt = 10L, reservation = 1L)
        val after = h.snapshot()
        val light = h.acquire(setOf(l), setOf(l), startedAt = 11L, reservation = 1L)
        assertAll(
            { assertEquals(false, before.allocatorMemory.demandingObserved[checkNotNull(before.ledger.lineageOf(x)).last()], "the rig: heavy was seen idle") },
            { assertEquals(AcquireOutcome.LifetimeExhausted(GroupId("p")), refused, "heavy's clamp would pass the limit") },
            { assertEquals(before, after, "and nothing changed, the wake memory included") },
            { assertEquals(l, assertIs<AcquireOutcome.Acquired>(light, "light still leases").lease.leaf) },
        )
    }

    /**
     * Two heavy joiners, the review's rig on #3495: with nothing rebasing, a front sums absolute
     * positions, and two `2⁵⁷` children seated where `fine` ran up the front put `Σ p` past 128 bits,
     * so every acquire threw. Here `apply` reads each joiner's seat beside the other, and no call
     * throws. At 128 leases each seat alone is about `2¹²⁶` ticks, so both are refused and `four`
     * leases. At 48 leases each seat is `1.5 · 2¹²⁴`: one fits, two do not, so the first lands and
     * the second, the one that takes the load past the limit, is refused alone.
     */
    @Test
    fun twoHeavyJoinersThatFitAloneButNotTogetherRefuseTheSecondAndNoCallThrows() {
        fun run(leases: Int): Pair<List<GroupId>, List<GroupId>> {
            val first = tree("fine" to fine)
            val h = LocalHeddle.bootWhole(first, LocalPolicy(defaultReservation = 1L), epoch = 1L)
            val f = first.groupId("fine")
            val m = tree("four" to four).groupId("four")
            h.mintSupply(1L shl 53)
            repeat(leases) { lease(h, setOf(f), f, it.toLong()) }
            check(h.apply(tree("fine" to fine, "four" to four), generation = 1L).refusals.isEmpty()) { "the rig: four joined" }
            lease(h, setOf(f, m), m, 1_000L)
            check(h.apply(tree("four" to four), generation = 2L).refusals.isEmpty()) { "the rig: fine left" }
            val shape = tree("four" to four, "h1" to twoHeavy, "h2" to twoHeavy)
            val refused = h.apply(shape, generation = 3L).refusals.map { it.child }
            val all = listOf("four", "h1", "h2").map(shape::groupId).toSet()
            val leaves = (0 until 6).map { i ->
                val out = h.acquire(all, all, startedAt = 2_000L + i, reservation = MAX)
                val lease = assertIs<AcquireOutcome.Acquired>(out, "acquire $i at $leases leases").lease
                h.complete(lease.id, MAX, waiting = emptySet())
                lease.leaf
            }
            return refused to leaves.distinct().sortedBy { it.value }
        }
        val shape = tree("four" to four, "h1" to twoHeavy, "h2" to twoHeavy)
        val (m, h1, h2) = listOf("four", "h1", "h2").map(shape::groupId)
        val many = run(leases = 128)
        val some = run(leases = 48)
        assertAll(
            { assertTrue(weightsFitTheBudget(listOf(four, twoHeavy, twoHeavy)), "the rig: the set fits the budget") },
            { assertEquals(listOf(h1, h2), many.first, "at 128 leases each heavy joiner is refused") },
            { assertEquals(listOf(m), many.second, "and four goes on being leased") },
            { assertEquals(listOf(h2), some.first, "at 48 leases the second joiner, which passes the load, is refused alone") },
            // h1 lands level with four, and four has run a lease, so h1 takes the next picks.
            { assertEquals(listOf(h1), some.second, "and h1, landed, is picked") },
        )
    }

    /**
     * The bridge through a runtime `attach` rather than a reload (design §7.1): the early check sits
     * in the same step loop, so an attached joiner whose seat would pass the limit is refused alone,
     * like an applied one. `fine` and `four` live under `g`, whose template attaches at `2⁵⁸`. At 160
     * leases the attach is refused and `four` still leases; at 16 it lands and is picked. No call
     * throws.
     */
    @Test
    fun anAttachedHeavyJoinerPastTheLimitIsRefusedAloneAndUnderItLands() {
        fun under(vararg lanes: Pair<String, Weight>) = draft("p") {
            group("g") {
                for ((name, w) in lanes) lane(name, w)
                template(heavy)
            }
        }
        fun run(leases: Int): Triple<ApplyResult, AcquireOutcome, Topology> {
            val first = under("fine" to fine)
            val h = LocalHeddle.bootWhole(first, LocalPolicy(defaultReservation = 1L), epoch = 1L)
            val f = first.groupId("g", "fine")
            h.mintSupply(1L shl 53)
            repeat(leases) { lease(h, setOf(f), f, it.toLong()) }
            val withFour = under("fine" to fine, "four" to four)
            val m = withFour.groupId("g", "four")
            check(h.apply(withFour, generation = 1L).refusals.isEmpty()) { "the rig: four joined" }
            lease(h, setOf(f, m), m, 1_000L)
            check(h.apply(under("four" to four), generation = 2L).refusals.isEmpty()) { "the rig: fine left" }
            val attached = h.attach(listOf("g"), "heavy", generation = 3L)
            // An attached child's id is the engine's to mint; read it off what the attach applied.
            val x = attached.applied.map { it.child }.distinct().singleOrNull()
            val all = setOfNotNull(m, x)
            val next = h.acquire(all, all, startedAt = 2_000L, reservation = MAX)
            return Triple(attached, next, withFour)
        }
        val (refused, afterRefusal, shape) = run(leases = 160)
        val (landed, afterLanding, _) = run(leases = 16)
        val m = shape.groupId("g", "four")
        assertAll(
            { assertEquals(1, refused.refusals.size, "at 160 leases the attach is refused: ${refused.refusals}") },
            { assertTrue(refused.refusals.single() is StepRefusal.Refused, "as a refusal, alone") },
            { assertEquals(m, assertIs<AcquireOutcome.Acquired>(afterRefusal, "four still leases").lease.leaf) },
            { assertEquals(emptyList(), landed.refusals, "at 16 leases the attach lands") },
            { assertTrue(assertIs<AcquireOutcome.Acquired>(afterLanding, "the attached lane leases").lease.leaf.value.endsWith("heavy"), "and it is the one picked: $afterLanding") },
        )
    }

    /**
     * A charging call is never refused at the limit (design §7.1). `heavy`, at `2⁴⁰`, is active and
     * unseated beside `light`, planted at `2⁹⁴` ticks, so no seat can be made for it. `admit` opens
     * its lease at zero rather than refusing, and `complete` reports the cost as unrecorded, since
     * the only group above `heavy`'s edge is the root. Nothing throws, and `light` still leases.
     */
    @Test
    fun aChargingCallAcrossAnEdgeThatCannotBeSeatedOpensAtZeroAndNeverRefuses() {
        val shape = tree("light" to Weight.ONE, "heavy" to Weight.of(1L shl 40))
        val l = shape.groupId("light")
        val x = shape.groupId("heavy")
        val h = LocalHeddle.bootWhole(shape, LocalPolicy(defaultReservation = 1L), epoch = 1L)
        h.mintSupply(1L shl 20)
        val lightEdge = checkNotNull(h.snapshot().ledger.lineageOf(l)).last()
        val heavyEdge = checkNotNull(h.snapshot().ledger.lineageOf(x)).last()
        h.reshapeForTest { ledger ->
            ledger.piece(EntitlementLedger.of(gauges = mapOf(lightEdge to gaugeAt(Int128.fromTwosComplement(1L shl 30, 0L), folded = 0L))))
        }
        val admitted = h.admit(x, waiting = setOf(x), startedAt = 0L)
        val lease = assertIs<AdmitOutcome.Acquired>(admitted, "admit is never refused: $admitted").lease
        val done = h.complete(lease.id, actualCost = 3L, waiting = emptySet())
        val light = h.acquire(setOf(l), setOf(l), startedAt = 1L, reservation = 1L)
        assertAll(
            { assertEquals(null, h.snapshot().ledger.gauge(heavyEdge), "the rig: heavy's edge was never seated") },
            { assertEquals(0L, lease.reserved, "the lease opens at zero") },
            { assertEquals(LeaseOutcome.Done(charged = 0L, overrun = 3L, unrecorded = 3L), done, "and its cost is reported, unrecorded") },
            { assertEquals(l, assertIs<AcquireOutcome.Acquired>(light, "light still leases").lease.leaf) },
        )
    }

    private companion object {
        const val MAX: Long = LocalHeddle.MAX_RESERVATION
    }
}
