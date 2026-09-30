package us.tractat.kuilt.demo.workspace.state

import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.LatticeProduct
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Why [TypedModel] is one log rather than the planned `LatticeProduct` of two.
 *
 * OBSERVED: one insert into each of two `Rga`s from the same replica mints `Dot(a, 1)` in both,
 * so the product's `causalDots()` union holds ONE dot for TWO inputs — because `Rga.nextSeqFor`
 * reads the log's own `maxSeqByReplica`, making every `Rga` an independent dot space.
 */
class TwoLogProductTest {
    private val a = ReplicaId("a")

    @Test fun twoRgaLogsMintTheSameDot() {
        val venues = Rga.empty<String>().insertAt(a, 0, "Uno").first
        val edits = Rga.empty<String>().insertAt(a, 0, "budget=30").first
        val product = LatticeProduct(venues, edits)
        val expected = setOf(Dot(a, 1))
        assertAll(
            { assertEquals(expected, venues.causalDots(), "venue log mints (a, 1)") },
            { assertEquals(expected, edits.causalDots(), "edit log also mints (a, 1)") },
            { assertEquals(1, product.causalDots().size, "product: two inputs, one dot") },
        )
    }
}
