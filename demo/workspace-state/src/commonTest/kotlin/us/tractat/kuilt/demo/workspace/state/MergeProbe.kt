package us.tractat.kuilt.demo.workspace.state

import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.assertAll
import kotlin.test.assertEquals

/** What a probe hands back: the base's venue handles, and the merged shortlist (`x.piece(y)`). */
data class Merged(val base: List<VenueHandle>, val shortlist: List<VenueView>) {
    /** The view still reachable through [handle], or `null` when the handle no longer resolves. */
    fun byHandle(handle: VenueHandle): VenueView? = shortlist.firstOrNull { it.handle == handle }

    /** `name@price`, in shortlist order: the shape a pin compares when identities are new dots. */
    val rows: List<String> get() = shortlist.map { "${it.name}@${it.pricePerHead}" }
}

/**
 * The five merge probes, written once over [DinnerModel] and run by each `*ProbeTest` against its
 * own model with its own pinned expectation. A probe returns what it observed and pins nothing
 * itself — except that every concurrent case must be merge-order independent, which [bothOrders]
 * asserts for every model.
 *
 * Every probe starts from [base] (three venues added on replica [a]) and forks it into `x`
 * (replica [a]) and `y` (replica [b]).
 */
object MergeProbe {
    val a = ReplicaId("a")
    val b = ReplicaId("b")

    fun <S> base(m: DinnerModel<S>): Pair<S, List<VenueHandle>> {
        var s = m.empty(a)
        val hs = mutableListOf<VenueHandle>()
        for ((n, p) in listOf("Uno" to 40, "Due" to 25, "Tre" to 30)) {
            val (s2, h) = m.addVenue(s, a, n, p)
            s = s2
            hs += h
        }
        return s to hs
    }

    private fun <S> bothOrders(m: DinnerModel<S>, x: S, y: S): S {
        val xy = m.merge(x, y)
        val yx = m.merge(y, x)
        assertAll(
            { assertEquals(m.shortlist(xy), m.shortlist(yx), "${m.name}: shortlist order-independent") },
            { assertEquals(m.effectiveBudget(xy), m.effectiveBudget(yx), "${m.name}: budget order-independent") },
            { assertEquals(m.budgetCandidates(xy), m.budgetCandidates(yx), "${m.name}: candidates order-independent") },
        )
        return xy
    }

    /**
     * P1 list identity: x edits Due's price by handle while y concurrently adds a venue.
     *
     * The brief says y "inserts ahead of Due", but [DinnerModel.addVenue] only appends — the
     * interface has no positional insert — so this arm does NOT move Due's index. [identityAfterShift]
     * is the arm that does.
     */
    fun <S> listIdentity(m: DinnerModel<S>): Merged {
        val (s, hs) = base(m)
        val x = m.setPrice(s, a, hs[1], 22)
        val (y, _) = m.addVenue(s, b, "Zero", 10)
        return Merged(hs, m.shortlist(bothOrders(m, x, y)))
    }

    /**
     * P1b list identity under a real index shift: x edits Due by handle while y concurrently removes
     * Uno, the venue ahead of it, so Due's index goes from 1 to 0. Built from existing mutators only.
     */
    fun <S> identityAfterShift(m: DinnerModel<S>): Merged {
        val (s, hs) = base(m)
        val x = m.setPrice(s, a, hs[1], 22)
        val y = m.removeVenue(s, b, hs[0])
        return Merged(hs, m.shortlist(bothOrders(m, x, y)))
    }

    /** P2 path replacement: concurrent price sets on the same venue (Uno: a→35, b→45). */
    fun <S> concurrentFieldEdit(m: DinnerModel<S>): Merged {
        val (s, hs) = base(m)
        return Merged(hs, m.shortlist(bothOrders(m, m.setPrice(s, a, hs[0], 35), m.setPrice(s, b, hs[0], 45))))
    }

    /** P3 delete vs concurrent edit: x removes Tre while y sets Tre's price to 28. */
    fun <S> deleteVersusEdit(m: DinnerModel<S>): Merged {
        val (s, hs) = base(m)
        return Merged(hs, m.shortlist(bothOrders(m, m.removeVenue(s, a, hs[2]), m.setPrice(s, b, hs[2], 28))))
    }

    /** P4 conflicting scalar: concurrent budgets (a→30, b→50). Returns (effective, candidates). */
    fun <S> concurrentBudget(m: DinnerModel<S>): Pair<Int?, List<Int>> {
        val (s, _) = base(m)
        val merged = bothOrders(m, m.setBudget(s, a, 30), m.setBudget(s, b, 50))
        return m.effectiveBudget(merged) to m.budgetCandidates(merged)
    }

    /**
     * P5 delta cost of one field edit on a 3-venue list: the encoded size of the delta that
     * `setPrice` produced (each model's state carries its last mutation's delta), not of the state.
     */
    fun <S> fieldEditSize(m: DinnerModel<S>): Int = fieldEditSizeBy(m, a)

    /**
     * P5 with the edit made by [editor] off the same base. With [b], the editor has contributed
     * nothing yet (the base is all [a]'s), which separates the model's own delta from the editor's
     * history.
     */
    fun <S> fieldEditSizeBy(m: DinnerModel<S>, editor: ReplicaId): Int {
        val (s, hs) = base(m)
        return m.encodedSize(m.setPrice(s, editor, hs[0], 35))
    }
}
