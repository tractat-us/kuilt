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
 *
 * How two concurrent shortlist writes meet (the chain behind P1, P1b, P2 and P3). Every venue
 * change rewrites the one top-level `shortlist` key. `x` (replica `a`) supersedes `a`'s own earlier
 * tag on it and `y` (replica `b`) mints a fresh one, so after the merge the key holds both
 * contributions, because neither side's context has seen the other's tag (`ORMapEntry.join`,
 * ORMap.kt:52-69). The key's value joins them (`ORMapEntry.value`, ORMap.kt:43-50), which for two
 * Arrays is `Array(rga.piece(other.rga))` (JsonNode.kt:92). Only then do the `Rga` rules apply:
 * tombstones union (Rga.kt:1042), and siblings after one predecessor walk in descending `RgaId`
 * (Rga.kt:1190).
 */
class JsonModelProbeTest {
    private val m = JsonModel

    /**
     * P1: the edit is applied — Due shows 22 in the right place — but NOT through the handle.
     *
     * OBSERVED: `byHandle(Due)` is null, because `JsonModel.setPrice` inserts a rebuilt element after
     * Due (id `(4, a, 4)`, so a new dot) and removes the old one; `Rga.piece` unions tombstones
     * (Rga.kt:1042), so the old dot is hidden on every replica (see the class KDoc for the chain). The
     * untouched venues' handles survive the concurrent insert; the edited venue's handle does not.
     */
    @Test fun listIdentityEditLandsButTheHandleNoLongerResolves() {
        val r = MergeProbe.listIdentity(m)
        assertAll(
            { assertEquals(null, r.byHandle(r.base[1]), "the pre-edit handle no longer resolves") },
            { assertEquals(VenueView(r.base[0], "Uno", 40), r.byHandle(r.base[0]), "Uno's handle survives") },
            { assertEquals(VenueView(r.base[2], "Tre", 30), r.byHandle(r.base[2]), "Tre's handle survives") },
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
     * UNSUPPORTED: a UI still holding Due's pre-edit handle cannot act on Due after merging a remote
     * price edit. The handle's element is tombstoned (P1), so `JsonModel.setPrice` finds no visible
     * element for it and throws the stale-handle refusal.
     */
    @Test fun staleHandleActionAfterMergeThrows() {
        val (s, hs) = MergeProbe.base(m)
        val edited = m.setPrice(s, MergeProbe.a, hs[1], 22)
        val merged = m.merge(s, edited)
        val refusal = assertFailsWith<IllegalStateException> { m.setPrice(merged, MergeProbe.b, hs[1], 20) }
        assertAll(
            { assertEquals(22, m.shortlist(merged).single { it.name == "Due" }.pricePerHead, "precondition: edit merged") },
            {
                assertTrue(
                    refusal.message.orEmpty().contains("is not visible (never added here, removed, or replaced by a setPrice)"),
                    "stale-handle refusal, not some other failure: ${refusal.message}",
                )
            },
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
     * `JsonCrdt.set("shortlist", …)`: the whole `shortlist` Array, four inserts (three venues plus the
     * replacement) and one remove, each inserted element an `Object` whose `ORMap` holds two
     * `MVRegister` leaves. Two causes both produce that here, and this arm cannot separate them:
     * - the model: `JsonModel.setPrice` rebuilds the whole Array and passes it to `set` (the #2469
     *   whole-subtree write);
     * - the map: `ORMap.put` ships `foldOwn(existing, replica, value)`, the given node joined with
     *   everything this replica already contributed to the key (ORMap.kt:137-145, 210, 226-228), and
     *   replica `a` authored all three venues.
     * [fieldEditDeltaByANonAuthor] takes the second cause away.
     */
    @Test fun fieldEditDeltaIs1572Bytes() {
        assertEquals(1572, MergeProbe.fieldEditSize(m))
    }

    /**
     * P5 again, with the edit made by replica `b`, which has contributed nothing to `shortlist`, off
     * the same base. `foldOwn` then has nothing of `b`'s to fold in, so the delta carries only the node
     * `JsonModel.setPrice` passed. What this arm isolates is the model's rebuild.
     *
     * OBSERVED: 1656 bytes, JVM CBOR, and the delta's `shortlist` is still the whole Array (the
     * structural pin below). So the whole-subtree size is the MODEL's rebuild. `foldOwn` adds no ops
     * for `a` either: `a`'s earlier contribution is the base Array, whose ops are a subset of the
     * rebuilt Array's, and `Rga.piece` is an op union. `b`'s delta is 84 bytes LARGER, not smaller, for
     * a reason inside the replacement element. `JsonModel.setPrice` puts the new price leaf into the
     * element's own `ORMap` as `b`, and `put` supersedes only the caller's own tags (ORMap.kt:209). So
     * `a`'s original price contribution stays beside `b`'s, and the element encodes 115 bytes larger
     * (395 against 280, measured once, not pinned). The top-level context names one dot instead of
     * two, which takes back 31.
     */
    @Test fun fieldEditDeltaByANonAuthor() {
        val (s, hs) = MergeProbe.base(m)
        val edited = m.setPrice(s, MergeProbe.b, hs[0], 35)
        assertAll(
            { assertEquals(1656, MergeProbe.fieldEditSizeBy(m, MergeProbe.b), "JVM CBOR bytes") },
            {
                assertEquals(
                    edited.doc["shortlist"],
                    edited.lastDelta?.get("shortlist"),
                    "a non-author's delta still carries the whole shortlist Array",
                )
            },
        )
    }

    /** The direction Task 3 cites: one field edit costs more in the JSON model than in the typed one. */
    @Test fun fieldEditDeltaIsLargerThanTyped() {
        val json = MergeProbe.fieldEditSize(m)
        val typed = MergeProbe.fieldEditSize(TypedModel)
        assertTrue(json > typed, "json $json bytes should exceed typed $typed bytes")
    }
}
