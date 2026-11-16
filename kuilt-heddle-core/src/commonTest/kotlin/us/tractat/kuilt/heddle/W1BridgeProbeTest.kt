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
        lease(h, setOf(l, x), x, 0L)
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

    private companion object {
        const val MAX: Long = LocalHeddle.MAX_RESERVATION
    }
}
