package us.tractat.kuilt.heddle

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The wide reads beside the deprecated `Rational` ones (design §7.1, step B slice 6). A lane past its
 * ceiling reads the ceiling through the `Rational` read, the penalty side, and its exact virtual
 * time through the `VirtualTime` one, which goes on growing. `explain` carries both.
 */
@Suppress("DEPRECATION")
class WideReadsTest {

    private val root = GroupId("root")

    @Test
    fun aLanePastItsCeilingReadsItExactlyThroughTheWideReads() {
        val x = GroupId("x")
        val y = GroupId("y")
        val fine = Weight.of(1L, 30_000L)
        val xEdge = AttachmentRecord(AttachmentId("root>x"), root, x, fine)
        val yEdge = AttachmentRecord(AttachmentId("root>y"), root, y, fine)
        val h = engineOver(shapedLedger(root, listOf(xEdge, yEdge)), root, supply = 0L).apply { reconcileCapacity(1L) }
        val both = setOf(x, y)
        repeat(3) { i ->
            val lease = assertIs<AcquireOutcome.Acquired>(h.acquire(both, setOf(x), startedAt = i.toLong(), reservation = LocalHeddle.MAX_RESERVATION)).lease
            assertIs<LeaseOutcome.Done>(h.complete(lease.id, LocalHeddle.MAX_RESERVATION, waiting = both))
        }
        val ledger = h.snapshot().ledger
        val ceiling = ticksToVirtual(positionCeiling(fine), fine)
        val held = assertNotNull(ledger.virtualService(xEdge.id))
        val wide = assertNotNull(ledger.virtualServiceWide(xEdge.id))
        val why = assertIs<WaitExplanation.BehindAtLevel>(h.explain(x, both, both))
        val wideFront = assertNotNull(why.frontWide)
        assertAll(
            { assertEquals(ceiling, held, "the rig: the Rational read is held at the ceiling") },
            { assertTrue(wide > VirtualTime.of(us.tractat.kuilt.math.Int128.of(positionCeiling(fine)), fine), "the wide read is exact, past it: ${wide.toDouble()}") },
            { assertEquals(wide, VirtualTime.of(assertIs<Ticks.Fits>(wide.ticksForWide(fine)).value, fine), "and round-trips on its own grid") },
            { assertEquals(wide, why.leafSideVirtualServiceWide, "explain carries x's wide side") },
            { assertTrue(wideFront > assertNotNull(VirtualTime.of(us.tractat.kuilt.math.Int128.ZERO, fine)), "and a wide front past zero") },
            { assertEquals(h.allocator.virtualTimeWide(ledger, root) { Demand.NONE }?.let { it >= wideFront }, true, "no wide front exceeds the furthest-ahead child") },
            { assertEquals(WaitExplanation.BehindAtLevel(why.parent, why.nextChild, why.leafSideVirtualService, why.front, why.eligibleFront), why, "equality reads the Rationals alone, as before") },
        )
    }

    /**
     * Every deprecated read answers (#3523 review): a held front sums children held at the ceiling,
     * an odd number of ticks, and over three of them the exact mean does not reduce into a `Long`
     * [Rational]. The review's rig: `fine` (`1/2¹⁴`) runs 20 leases, `four` joins, `fine` leaves,
     * and two `2⁵⁷` lanes land. `explain` used to throw `ArithmeticException` for `four` and `h2`;
     * now each answers, its `Rational` front held on the penalty side and its wide front exact.
     */
    @Test
    fun everyExplainAnswersOverChildrenHeldAtTheCeiling() {
        val fine = Weight.of(1L, 1L shl 14)
        val four = Weight.of(4L)
        val heavy = Weight.of(1L shl 57)
        fun tree(vararg lanes: Pair<String, Weight>) = draft("p") { for ((name, w) in lanes) lane(name, w) }
        val max = LocalHeddle.MAX_RESERVATION
        fun lease(h: LocalHeddle, waiting: Set<GroupId>, leaf: GroupId, at: Long) {
            val lease = assertIs<AcquireOutcome.Acquired>(h.acquire(waiting, setOf(leaf), startedAt = at, reservation = max)).lease
            assertIs<LeaseOutcome.Done>(h.complete(lease.id, max, waiting = emptySet()))
        }
        val first = tree("fine" to fine)
        val h = LocalHeddle.bootWhole(first, LocalPolicy(defaultReservation = 1L), epoch = 1L)
        val f = first.groupId("fine")
        val m = tree("four" to four).groupId("four")
        h.mintSupply(1L shl 53)
        repeat(20) { lease(h, setOf(f), f, it.toLong()) }
        check(h.apply(tree("fine" to fine, "four" to four), generation = 1L).refusals.isEmpty())
        lease(h, setOf(f, m), m, 1_000L)
        check(h.apply(tree("four" to four), generation = 2L).refusals.isEmpty())
        val shape = tree("four" to four, "h1" to heavy, "h2" to heavy)
        val landed = h.apply(shape, generation = 3L)
        val ids = listOf("four", "h1", "h2").map(shape::groupId).toSet()
        repeat(4) { i ->
            val out = assertIs<AcquireOutcome.Acquired>(h.acquire(ids, ids, startedAt = 2_000L + i, reservation = max)).lease
            h.complete(out.id, max, waiting = emptySet())
        }
        val answers = ids.sortedBy { it.value }.map { id ->
            try {
                h.explain(id, ids, ids)
            } catch (e: ArithmeticException) {
                e
            }
        }
        val behind = answers.filterIsInstance<WaitExplanation.BehindAtLevel>()
        assertAll(
            { assertEquals(emptyList(), landed.refusals, "the rig: both heavy lanes landed") },
            { assertEquals(emptyList<Any>(), answers.filterIsInstance<ArithmeticException>(), "no explain throws") },
            { assertTrue(behind.isNotEmpty(), "the rig: some lane is behind at a level: $answers") },
            { assertTrue(behind.all { it.frontWide != null && it.eligibleFrontWide != null }, "and carries its wide fronts") },
        )
    }
}
