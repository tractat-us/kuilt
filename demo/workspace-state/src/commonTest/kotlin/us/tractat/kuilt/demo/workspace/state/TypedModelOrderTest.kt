package us.tractat.kuilt.demo.workspace.state

import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which concurrent write the typed model's "last writer wins" picks.
 *
 * OBSERVED: `a` (30) and `b` (50) each append a budget after the same base element. Both inserts
 * get Lamport 2, so the ids are `(2, a)` < `(2, b)`, and `b` wins by [us.tractat.kuilt.crdt.RgaId].
 * Raw `Rga.entries()` puts `(2, b)` BEFORE `(2, a)` — siblings are walked in descending id order —
 * so "last in `entries()`" would have picked `a`. The precondition arm pins that raw order, which
 * proves the fixture reaches the case the model's explicit sort exists for.
 */
class TypedModelOrderTest {
    private val a = ReplicaId("a")
    private val b = ReplicaId("b")

    @Test fun concurrentBudgetsGoToTheGreatestRgaIdInBothMergeOrders() {
        val (base, _) = TypedModel.addVenue(TypedModel.empty(a), a, "Uno", 40)
        val fromA = TypedModel.setBudget(base, a, 30)
        val fromB = TypedModel.setBudget(base, b, 50)
        val ab = TypedModel.merge(fromA, fromB)
        val ba = TypedModel.merge(fromB, fromA)
        val rawBudgetOrder = ab.log.entries().mapNotNull { (id, e) -> (e as? Entry.BudgetSet)?.let { id.replicaId } }
        assertAll(
            { assertEquals(listOf(b, a), rawBudgetOrder, "precondition: entries() puts the lower id (a) last") },
            { assertEquals(ab, ba, "merge commutes") },
            { assertEquals(50, TypedModel.effectiveBudget(ab), "a.piece(b): b's greater RgaId wins") },
            { assertEquals(50, TypedModel.effectiveBudget(ba), "b.piece(a): b's greater RgaId wins") },
            { assertEquals(listOf(50, 30), TypedModel.budgetCandidates(ab), "winner first, loser kept") },
        )
    }

    @Test fun concurrentPriceSetsGoToTheGreatestRgaIdInBothMergeOrders() {
        val (base, venue) = TypedModel.addVenue(TypedModel.empty(a), a, "Uno", 40)
        val fromA = TypedModel.setPrice(base, a, venue, 45)
        val fromB = TypedModel.setPrice(base, b, venue, 55)
        val ab = TypedModel.merge(fromA, fromB)
        val ba = TypedModel.merge(fromB, fromA)
        val rawPriceOrder = ab.log.entries().mapNotNull { (id, e) -> (e as? Entry.PriceSet)?.let { id.replicaId } }
        assertAll(
            { assertEquals(listOf(b, a), rawPriceOrder, "precondition: entries() puts the lower id (a) last") },
            { assertEquals(listOf(55), TypedModel.shortlist(ab).map { it.pricePerHead }, "a.piece(b): b wins") },
            { assertEquals(listOf(55), TypedModel.shortlist(ba).map { it.pricePerHead }, "b.piece(a): b wins") },
        )
    }
}
