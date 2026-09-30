package us.tractat.kuilt.demo.workspace.state

import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The five merge probes against [JsonModel] (one `JsonCrdt`; a price edit replaces the venue's
 * `Rga` element). Every probe also asserts merge-order independence inside [MergeProbe].
 *
 * Line numbers below are `kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/` at the commit
 * this test landed on.
 */
class JsonModelProbeTest {
    private val m = JsonModel

    /**
     * P1: the edit is applied — Due shows 22 in the right place — but NOT through the handle.
     *
     * OBSERVED: `byHandle(Due)` is null, because `JsonModel.setPrice` inserts a rebuilt element after
     * Due (id `(4, a, 4)`, so a new dot) and removes the old one; `Rga.piece` unions tombstones
     * (Rga.kt:1042), so the old dot is hidden on every replica. A handle survives a concurrent
     * insert, but not an edit of its own venue.
     */
    @Test fun listIdentityEditLandsButTheHandleNoLongerResolves() {
        val r = MergeProbe.listIdentity(m)
        assertAll(
            { assertEquals(null, r.byHandle(r.base[1]), "the pre-edit handle no longer resolves") },
            { assertEquals(listOf("Uno@40", "Due@22", "Tre@30", "Zero@10"), r.rows, "merged shortlist") },
            { assertEquals(VenueHandle(Dot(MergeProbe.a, 4)), r.shortlist[1].handle, "Due's new identity") },
        )
    }

    /**
     * P1b: same shape under a real index shift: Due moves to index 0 at 22, under a new dot.
     *
     * OBSERVED: as P1 — the replacement, not the shift, is what loses the handle.
     */
    @Test fun listIdentityAfterShiftAlsoLosesTheHandle() {
        val r = MergeProbe.identityAfterShift(m)
        assertAll(
            { assertEquals(null, r.byHandle(r.base[1]), "the pre-edit handle no longer resolves") },
            { assertEquals(listOf("Due@22", "Tre@30"), r.rows, "merged shortlist") },
        )
    }

    /**
     * UNSUPPORTED: a UI still holding Due's pre-edit handle cannot act on Due after merging a
     * concurrent price edit. The handle's element is tombstoned (P1), so `JsonModel.setPrice` finds no
     * visible element for it and throws.
     */
    @Test fun staleHandleActionAfterMergeThrows() {
        val (s, hs) = MergeProbe.base(m)
        val edited = m.setPrice(s, MergeProbe.a, hs[1], 22)
        val merged = m.merge(s, edited)
        assertAll(
            { assertEquals(22, m.shortlist(merged).single { it.name == "Due" }.pricePerHead, "precondition: edit merged") },
            { assertFailsWith<IllegalStateException> { m.setPrice(merged, MergeProbe.b, hs[1], 20) } },
        )
    }

    /**
     * P2: TWO Unos, at 45 then 35, each under a new dot; the original is gone.
     *
     * OBSERVED: each replica replaces Uno with its own element inserted after Uno — `(4, b, 1)` and
     * `(4, a, 4)` — and tombstones Uno. `Rga.piece` keeps both inserts and both removes
     * (Rga.kt:1030-1042), and siblings after one predecessor walk in descending `RgaId` order
     * (Rga.kt:1190), so b's 45 comes first. The two prices never meet in one `MVRegister`: they sit
     * in two different elements, so the conflict surfaces as a duplicated venue, not a multi-value.
     */
    @Test fun concurrentFieldEditDuplicatesTheVenue() {
        val r = MergeProbe.concurrentFieldEdit(m)
        assertAll(
            { assertEquals(null, r.byHandle(r.base[0]), "the original Uno is tombstoned") },
            { assertEquals(listOf("Uno@45", "Uno@35", "Due@25", "Tre@30"), r.rows, "merged shortlist") },
            {
                assertEquals(
                    listOf(VenueHandle(Dot(MergeProbe.b, 1)), VenueHandle(Dot(MergeProbe.a, 4))),
                    r.shortlist.take(2).map { it.handle },
                    "two replacement identities",
                )
            },
        )
    }

    /**
     * P3: the edit wins — Tre survives at 28, under b's new dot.
     *
     * OBSERVED: a's remove tombstones the original Tre only. b's edit inserted a replacement element
     * `(4, b, 1)` that a never saw, so no remove names it, and the tombstone union in `Rga.piece`
     * (Rga.kt:1042) leaves it visible. This is an Rga-element effect, not `JsonCrdt`'s key-level
     * add-wins (ORMap.kt:52-69): both writes land under the one `shortlist` key and its Arrays merge.
     */
    @Test fun deleteVersusEditTheEditSurvivesAsANewVenue() {
        val r = MergeProbe.deleteVersusEdit(m)
        assertAll(
            { assertEquals(null, r.byHandle(r.base[2]), "the original Tre is tombstoned") },
            { assertEquals(listOf("Uno@40", "Due@25", "Tre@28"), r.rows, "merged shortlist") },
            { assertEquals(VenueHandle(Dot(MergeProbe.b, 1)), r.shortlist[2].handle, "Tre's new identity") },
        )
    }

    /**
     * P4: no single answer; both values survive.
     *
     * OBSERVED: the `budget` key gets two contributions, `(a, 4)` and `(b, 1)`, which `ORMapEntry.join`
     * keeps because neither side's context has the other's (ORMap.kt:52-69). The key's value joins them
     * (ORMap.kt:43-50) into one `Leaf` whose `MVRegister` keeps both writes' dots (`DotFun.join`,
     * DotFun.kt:23), so `effectiveBudget` is null and `budgetCandidates` is ascending.
     */
    @Test fun concurrentBudgetHasNoWinnerAndKeepsBoth() {
        assertEquals(null to listOf(30, 50), MergeProbe.concurrentBudget(m))
    }

    /**
     * P5: 1572 bytes, JVM CBOR (the codec `Quilter` defaults to), of the `Patch.delta` from
     * `JsonCrdt.set("shortlist", …)`. `ORMap.put` ships the node it was given (ORMap.kt:206-212), and
     * `JsonModel.setPrice` gives it the whole rebuilt `shortlist` Array: four inserts (three venues plus
     * the replacement) and one remove, each inserted element an `Object` whose `ORMap` holds two
     * `MVRegister` leaves. This is the #2469 whole-subtree write, so it grows with the shortlist.
     */
    @Test fun fieldEditDeltaIs1572Bytes() {
        assertEquals(1572, MergeProbe.fieldEditSize(m))
    }

    /** The direction Task 3 cites: one field edit costs more in the JSON model than in the typed one. */
    @Test fun fieldEditDeltaIsLargerThanTyped() {
        val json = MergeProbe.fieldEditSize(m)
        val typed = MergeProbe.fieldEditSize(TypedModel)
        assertTrue(json > typed, "json $json bytes should exceed typed $typed bytes")
    }
}
