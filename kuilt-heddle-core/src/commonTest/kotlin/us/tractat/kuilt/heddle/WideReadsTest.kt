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
}
