package us.tractat.kuilt.demo.workspace.state

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The five merge probes against [TypedModel] (one `Rga<Entry>` log, explicit last-writer-wins by
 * `RgaId`). Every probe also asserts merge-order independence inside [MergeProbe].
 */
class TypedModelProbeTest {
    private val m = TypedModel

    /**
     * P1: the price edit lands on Due through its handle; y's concurrent venue is appended after Tre.
     * The index pin is the fixture's own precondition: an append does not move Due, so this arm
     * cannot tell a handle from an index — [listIdentityHoldsWhenTheVenueAheadIsRemoved] can.
     */
    @Test fun listIdentityEditLandsOnDue() {
        val r = MergeProbe.listIdentity(m)
        assertAll(
            { assertEquals(VenueView(r.base[1], "Due", 22), r.byHandle(r.base[1]), "edit reaches Due by handle") },
            { assertEquals(listOf("Uno@40", "Due@22", "Tre@30", "Zero@10"), r.rows, "merged shortlist") },
            { assertEquals(1, r.shortlist.indexOfFirst { it.handle == r.base[1] }, "precondition: Due did not move") },
        )
    }

    /** P1b: Uno is concurrently removed, so Due moves from index 1 to 0; the handle still reaches it. */
    @Test fun listIdentityHoldsWhenTheVenueAheadIsRemoved() {
        val r = MergeProbe.identityAfterShift(m)
        assertAll(
            { assertEquals(VenueView(r.base[1], "Due", 22), r.byHandle(r.base[1]), "edit reaches Due by handle") },
            { assertEquals(listOf("Due@22", "Tre@30"), r.rows, "merged shortlist") },
            { assertEquals(0, r.shortlist.indexOfFirst { it.handle == r.base[1] }, "precondition: Due moved to 0") },
        )
    }

    /**
     * Review Focus 1 as an action: after the P1b merge (Uno removed on `b`, Due priced 22 on `a`),
     * a UI on `b` still holding Due's base handle sets its price, and the edit lands on Due. The
     * handle is the venue's insert dot, which a `PriceSet` never replaces. JSON's counterpart throws
     * (`JsonModelProbeTest.staleHandleActionOnTheShiftedMergeThrows`).
     */
    @Test fun actionThroughHandleAfterShiftLandsOnDue() {
        val (merged, hs) = MergeProbe.afterShift(m)
        val acted = m.setPrice(merged, MergeProbe.b, hs[1], 20)
        assertAll(
            { assertEquals(0, m.shortlist(merged).indexOfFirst { it.handle == hs[1] }, "precondition: Due moved to 0") },
            { assertEquals(listOf(VenueView(hs[1], "Due", 20), VenueView(hs[2], "Tre", 30)), m.shortlist(acted)) },
        )
    }

    /**
     * P2: one Uno, at b's price. Both `PriceSet`s get Lamport 4, so the ids are `(4, a)` < `(4, b)`
     * and `TypedModel.shortlist` keeps the greater (`RgaId.compareTo`, lamport then replica). The
     * loser's `PriceSet` stays in the log, but `shortlist` exposes no trace of it.
     */
    @Test fun concurrentFieldEditKeepsOneVenueAtTheGreaterRgaIdPrice() {
        val r = MergeProbe.concurrentFieldEdit(m)
        val (s, hs) = MergeProbe.base(m)
        val merged = m.merge(m.setPrice(s, MergeProbe.a, hs[0], 35), m.setPrice(s, MergeProbe.b, hs[0], 45))
        val priceSets = merged.log.entries()
            .mapNotNull { (id, e) -> (e as? Entry.PriceSet)?.let { Triple(id.lamport, id.replicaId, it.price) } }
            .sortedBy { it.second.value }
        assertAll(
            { assertEquals(VenueView(r.base[0], "Uno", 45), r.byHandle(r.base[0]), "b's (4, b) beats a's (4, a)") },
            { assertEquals(listOf("Uno@45", "Due@25", "Tre@30"), r.rows, "merged shortlist") },
            {
                assertEquals(
                    listOf(Triple(4L, MergeProbe.a, 35), Triple(4L, MergeProbe.b, 45)),
                    priceSets,
                    "both PriceSets stay in the log, both at Lamport 4",
                )
            },
        )
    }

    /** P3: remove wins. `TypedModel.shortlist` hides any target with a `Removed` entry, whatever its edits. */
    @Test fun deleteVersusEditRemoveWins() {
        val r = MergeProbe.deleteVersusEdit(m)
        assertAll(
            { assertEquals(null, r.byHandle(r.base[2]), "Tre gone") },
            { assertEquals(listOf("Uno@40", "Due@25"), r.rows, "merged shortlist") },
        )
    }

    /** P4: b's `(4, b)` wins; the loser stays visible in `budgetCandidates`, winner first. */
    @Test fun concurrentBudgetPicksTheGreaterRgaIdAndKeepsTheLoser() {
        assertEquals(50 to listOf(50, 30), MergeProbe.concurrentBudget(m))
    }

    /**
     * P5: 135 bytes, JVM CBOR (the codec `Quilter` defaults to), of the one-op delta
     * `Rga.empty().apply(op)`. It holds one `RgaOp.Insert` — its own `RgaId` (`id`) and its
     * predecessor's (`a`), each a lamport/replica/seq triple — whose value (`v`) is
     * `PriceSet(target = Dot(a, 1), price = 35)` under the short discriminator `"price"`, inside the
     * `Rga` envelope (`ops`, `compactedBelow`). CBOR spells every field name as a text key, so most of
     * the 135 is names. The delta never carries the list; only its two ids' integers widen as the
     * log's history grows (a byte per field past 23 entries, and history counts every edit, not
     * just venues) — see [fieldEditDeltaCarriesOnlyItsIdsWidening].
     */
    @Test fun fieldEditDeltaIs135Bytes() {
        assertEquals(135, MergeProbe.fieldEditSize(m))
    }

    /**
     * P5 against log size: one price edit on a 1-, 10- and 30-venue shortlist. The delta never
     * carries the list; only its two ids' integers widen as the log's history grows (a byte per
     * field past 23 entries, and history counts every edit, not just venues). CBOR writes an
     * integer 0–23 in one byte and 24–255 in two, so 1 and 10 venues are equal by construction
     * (every lamport and seq is at most 11), and the 30-venue arm is what shows the widening.
     */
    @Test fun fieldEditDeltaCarriesOnlyItsIdsWidening() {
        val one = MergeProbe.fieldEditSizeAt(m, 1)
        val ten = MergeProbe.fieldEditSizeAt(m, 10)
        val thirty = MergeProbe.fieldEditSizeAt(m, 30)
        assertAll(
            { assertEquals(135, one, "JVM CBOR bytes at 1 venue") },
            { assertEquals(135, ten, "JVM CBOR bytes at 10 venues: every id integer still fits one byte") },
            { assertEquals(139, thirty, "JVM CBOR bytes at 30 venues: the id integers widen") },
            { assertTrue(thirty - one in 1 until 10, "growth from 1 to 30 venues is small and bounded: ${thirty - one} B") },
        )
    }
}
