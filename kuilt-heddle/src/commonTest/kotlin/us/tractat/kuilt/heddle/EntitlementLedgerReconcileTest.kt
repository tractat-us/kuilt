package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * D1 (issue #1665) — the **advisory-retire race + legal reparent** defect and its conserving
 * reconciliation (design §5.4 reparent / §9 #3 recovery, §10.1 conservation).
 *
 * The race, restated (`GovernedHeddleNode.retire` KDoc): a gossip-lagged peer proposes
 * `retire(e1)` while its view still shows `outstanding(e1) = 0`; the committed retire gates only on
 * the log-order lifecycle being CLOSING, so `e1` → RETIRED with entitlement still outstanding. A
 * **legal reparent** then activates `e3 (root→g)` (allowed — `e1` is RETIRED, not live). On the
 * converged state `g`'s only live lineage is `[e3]` with `issued(e3) = 0`, yet `g` already delegated
 * `6` onward to `h`, so `holdings(g, p3)` derives **permanently negative** and the surviving budget at
 * `h` is spendable through `[e3, e2]` charging `rollupSpent(e3)` against `issued(e3) = 0`. Both faults
 * are permanent on every peer with **zero actual overspend** — they poison `validate()`'s "real
 * overspend" semantics until the stranded budget is re-homed.
 */
class EntitlementLedgerReconcileTest {

    private val root = GroupId("root")
    private val g = GroupId("g")
    private val h = GroupId("h")
    private val p3 = ReplicaId("p3")
    private val e1 = AttachmentId("e1") // root → g  (stranded by the raced retire)
    private val e2 = AttachmentId("e2") // g    → h
    private val e3 = AttachmentId("e3") // root → g  (the legal reparent generation)

    private fun rec(id: AttachmentId, parent: GroupId, child: GroupId) =
        AttachmentRecord(id, parent, child, Weight.ONE, 0L)

    /**
     * The converged post-race, post-reparent, post-spend state, built with the real mutators plus the
     * one merge the race produces: a RETIRED lifecycle on `e1` (carried by the lagged proposer, whose
     * drain witness was empty) joined with the real delegated counters that gossiped in afterwards.
     */
    private fun d1Converged(): EntitlementLedger {
        var l = EntitlementLedger.ZERO.piece(
            EntitlementLedger.bootstrap(root, mapOf(p3 to 10L), nonce = "genesis"),
        )
        l = l.piece(l.prepare(rec(e1, root, g))!!.delta)
        l = l.piece(l.activate(e1)!!.delta)
        l = l.piece(l.prepare(rec(e2, g, h))!!.delta)
        l = l.piece(l.activate(e2)!!.delta)
        // p3 delegates 10 down e1 (into g), then 6 down e2 (into h).
        l = l.piece(l.delegate(p3, e1, 10L)!!.delta)
        l = l.piece(l.delegate(p3, e2, 6L)!!.delta)
        // close(e1); then the LAGGED retire — a peer that had not merged the delegate saw
        // outstanding(e1)=0 and retired e1. The converged merge is RETIRED joined onto the real
        // (non-zero) delegated counters already present.
        l = l.piece(l.close(e1)!!.delta)
        l = l.piece(EntitlementLedger.of(lifecycle = mapOf(e1 to Lifecycle.RETIRED)))
        // Legal reparent: e1 is RETIRED (not live), so activating e3 passes the dual-inbound gate.
        l = l.piece(l.prepare(rec(e3, root, g))!!.delta)
        l = l.piece(l.activate(e3)!!.delta)
        // The surviving +6 at h is spent through [e3, e2] — charges rollupSpent(e3) against issued(e3)=0.
        l = l.piece(l.spend(p3, h, 6L)!!.delta)
        return l
    }

    private fun sumHoldings(l: EntitlementLedger): Long {
        var acc = 0L
        for (grp in listOf(root, g, h)) acc += l.holdings(grp, p3)
        return acc
    }

    @Test
    fun d1RaceLeavesPermanentConflicts() {
        val l = d1Converged()
        // The permanent, unhealable conflicts — identical on every peer.
        assertEquals(
            listOf(
                LedgerConflict.PerEdgeSafety(e3),
                LedgerConflict.PersistentNegativeHoldings(g, p3),
                LedgerConflict.ClosureViolation(e1),
            ),
            l.validate(),
            "D1 must surface the three permanent conflicts",
        )
        assertEquals(-6L, l.holdings(g, p3), "g's holdings derive permanently negative")
        // No inflation — mintedTotal is untouched — but the accounting identity is BROKEN: 10 units
        // of authority are stranded on the retired edge, so Σ holdings + leafSpent ≠ minted.
        assertEquals(10L, l.mintedTotal())
        assertTrue(
            sumHoldings(l) + l.leafSpentTotal() != l.mintedTotal(),
            "the strand breaks conservation: Σ holdings + leafSpent (=${sumHoldings(l) + l.leafSpentTotal()}) ≠ minted (10)",
        )
    }

    @Test
    fun relocationClearsConflictsAndRestoresConservation() {
        val l = d1Converged()
        val patch = l.relocationOrNull(g)
        assertNotNull(patch, "there is a strand to reconcile")
        val reconciled = l.piece(patch)

        assertEquals(4L, reconciled.holdings(g, p3), "g's holdings re-home to the un-spent remainder (10 − 6)")
        assertTrue(reconciled.holdings(g, p3) >= 0L)
        assertTrue(reconciled.validate().isEmpty(), "reconciliation clears every conflict: ${reconciled.validate()}")
        // Conservation restored, supply unchanged.
        assertEquals(reconciled.mintedTotal(), sumHoldings(reconciled) + reconciled.leafSpentTotal(), "conservation restored")
        assertEquals(10L, reconciled.mintedTotal(), "reconciliation mints nothing")
    }

    @Test
    fun reconciliationIsConservingAndIdempotent() {
        val l = d1Converged()
        val reconciled = l.piece(l.relocationOrNull(g)!!)
        // Never changes minted supply.
        assertEquals(l.mintedTotal(), reconciled.mintedTotal())
        // Nothing left to re-home: a second pass is a no-op (no new supply, no re-inflation).
        assertNull(reconciled.relocationOrNull(g), "the strand is cleared — a second reconcile finds nothing")
        // Re-applying the same patch (duplicate delivery) changes nothing — max-merge idempotence.
        assertEquals(reconciled, reconciled.piece(l.relocationOrNull(g) ?: EntitlementLedger.ZERO))
    }

    @Test
    fun reconcileRefusesWhenChildHasNoLiveInbound() {
        // Same race WITHOUT the reparent: g has only a RETIRED inbound, no live edge to re-home onto.
        var l = EntitlementLedger.ZERO.piece(
            EntitlementLedger.bootstrap(root, mapOf(p3 to 10L), nonce = "genesis"),
        )
        l = l.piece(l.prepare(rec(e1, root, g))!!.delta)
        l = l.piece(l.activate(e1)!!.delta)
        l = l.piece(l.prepare(rec(e2, g, h))!!.delta)
        l = l.piece(l.activate(e2)!!.delta)
        l = l.piece(l.delegate(p3, e1, 10L)!!.delta)
        l = l.piece(l.close(e1)!!.delta)
        l = l.piece(EntitlementLedger.of(lifecycle = mapOf(e1 to Lifecycle.RETIRED)))
        assertNull(l.relocationOrNull(g), "no live inbound → nothing to re-home onto → refuse")
    }

    // ── #1665 review — the reproduced conservation breaks (Wall A + Wall B) ──────────────────────────

    /** A stranded edge with **service spent through it** before the reparent (`rollupSpent(s) > 0`). */
    private fun spendThroughStrand(): EntitlementLedger {
        var l = EntitlementLedger.ZERO.piece(EntitlementLedger.bootstrap(root, mapOf(p3 to 10L), nonce = "genesis"))
        l = l.piece(l.prepare(rec(e1, root, g))!!.delta)
        l = l.piece(l.activate(e1)!!.delta)
        l = l.piece(l.prepare(rec(e2, g, h))!!.delta)
        l = l.piece(l.activate(e2)!!.delta)
        l = l.piece(l.delegate(p3, e1, 10L)!!.delta)
        l = l.piece(l.delegate(p3, e2, 6L)!!.delta)
        l = l.piece(l.spend(p3, h, 3L)!!.delta) // spend THROUGH the old lineage [e1,e2] → rollupSpent(e1)=3
        l = l.piece(l.close(e1)!!.delta)
        l = l.piece(EntitlementLedger.of(lifecycle = mapOf(e1 to Lifecycle.RETIRED)))
        l = l.piece(l.prepare(rec(e3, root, g))!!.delta)
        l = l.piece(l.activate(e3)!!.delta)
        return l
    }

    @Test
    fun break1and3_spendThroughStrandNowClearsWithoutDestroyingOrCreatingAnything() {
        // BREAK 1/3 (Wall B), inverted by #1693. Re-homing `outstanding(s)` would under-credit by
        // `spent(s)` (silent destruction); re-homing the full net inflow while `rollupSpent(s)=3`
        // stays put would create a PerEdgeSafety(e1). The relocation counters express the third
        // option — move the spend too — and the fence makes it safe to compute. Neither failure mode
        // may appear: assert the exact holdings, not merely that conflicts cleared.
        val l = spendThroughStrand()
        assertEquals(3L, l.edge(e1)!!.spent, "service was spent through the stranded edge")
        val reconciled = l.piece(assertNotNull(l.relocationOrNull(g), "the fenced move clears a through-service strand"))

        assertEquals(4L, reconciled.holdings(g, p3), "g is credited 10 − 6 handed onward: nothing destroyed")
        assertEquals(3L, reconciled.holdings(h, p3), "h keeps 6 − 3 spent")
        assertEquals(0L, reconciled.holdings(root, p3), "root delegated all 10 away")
        assertTrue(reconciled.validate().isEmpty(), "no conflict created and none left: ${reconciled.validate()}")
        assertEquals(10L, reconciled.mintedTotal(), "the move mints nothing")
        assertEquals(
            reconciled.mintedTotal(),
            sumHoldings(reconciled) + reconciled.leafSpentTotal(),
            "conservation restored on a through-service strand — the case #1669 had to refuse",
        )
    }

    @Test
    fun break4_transferTangledStrandIsRefused() {
        // BREAK 4: a peer other than the delegator ends up net-negative on the stranded edge via a
        // transfer AT the child then a release — the strand can't be re-homed without moving transfer rows.
        val q = ReplicaId("q")
        var l = EntitlementLedger.ZERO.piece(EntitlementLedger.bootstrap(root, mapOf(p3 to 10L), nonce = "genesis"))
        l = l.piece(l.prepare(rec(e1, root, g))!!.delta)
        l = l.piece(l.activate(e1)!!.delta)
        l = l.piece(l.prepare(rec(e2, g, h))!!.delta)
        l = l.piece(l.activate(e2)!!.delta)
        l = l.piece(l.delegate(p3, e1, 10L)!!.delta) // p3: issued(e1)=10
        l = l.piece(l.transfer(g, p3, q, 4L)!!.delta) // p3 → q at g: q now holds 4 at g
        l = l.piece(l.release(q, e1, 4L)!!.delta) // q releases up e1: returned(e1)[q]=4, q net-negative on e1
        l = l.piece(l.delegate(p3, e2, 2L)!!.delta)
        l = l.piece(l.close(e1)!!.delta)
        l = l.piece(EntitlementLedger.of(lifecycle = mapOf(e1 to Lifecycle.RETIRED)))
        l = l.piece(l.prepare(rec(e3, root, g))!!.delta)
        l = l.piece(l.activate(e3)!!.delta)
        assertNull(l.relocationOrNull(g), "transfer-tangled strand (a replica net-negative on s) → refuse")
    }

    /**
     * #1895 widened the `n ≥ sp` precondition from per-edge to per-`(child, replica)`. These three pin
     * the widening **and its two limits** — the old per-edge form had no test of its own, so the
     * relaxation would otherwise be unguarded.
     */
    @Test
    fun aChargeOnOneFencedEdgeIsFundedByASiblingFencedEdgesSurplus() {
        // e1 carries the cover, e2 carries the charge — the shape the re-home produces when a charge
        // lands on a live edge that is then itself fenced before Reconcile runs.
        val finals = mapOf(
            e1 to mapOf(p3 to SlotFinals(issued = 10L, returned = 0L, leafSpent = 0L, rollupSpent = 0L)),
            e2 to mapOf(p3 to SlotFinals(issued = 0L, returned = 0L, leafSpent = 3L, rollupSpent = 0L)),
        )
        // Per-edge, e2 reads n=0 < sp=3 and the whole child was refused forever. Together, 10 ≥ 3.
        assertIs<Relocation.Moved>(EntitlementLedger.ZERO.relocationPatch(e3, finals))
    }

    @Test
    fun aChargeExceedingEveryFencedEdgesCoverTogetherStillRefuses() {
        // The widening must not become a way to fund a spend from entitlement that does not exist.
        val finals = mapOf(
            e1 to mapOf(p3 to SlotFinals(issued = 2L, returned = 0L, leafSpent = 5L, rollupSpent = 0L)),
        )
        assertIs<Relocation.Refused>(EntitlementLedger.ZERO.relocationPatch(e3, finals))
    }

    @Test
    fun oneReplicasSurplusNeverFundsAnotherReplicasCharge() {
        val q = ReplicaId("q")
        // p3 holds all the cover and owes nothing; q owes everything and holds no cover.
        val finals = mapOf(
            e1 to mapOf(
                p3 to SlotFinals(issued = 10L, returned = 0L, leafSpent = 0L, rollupSpent = 0L),
                q to SlotFinals(issued = 0L, returned = 0L, leafSpent = 5L, rollupSpent = 0L),
            ),
        )
        // Aggregating across replicas would read cover=10 ≥ charge=5 and MOVE — a real conservation
        // break, since entitlement is per-replica. The quantifier is per (child, replica), so: refuse.
        assertIs<Relocation.Refused>(EntitlementLedger.ZERO.relocationPatch(e3, finals))
    }

    /**
     * The charge is `leafSpent + rollupSpent`. Every other test in this group puts the charge in
     * `leafSpent`, so the roll-up half of the sum was unpinned — accumulating only `lsp` passed the
     * whole suite while letting a roll-up overspend through onto the live edge.
     */
    @Test
    fun aRollUpChargeCountsTowardTheCoverPreconditionToo() {
        val finals = mapOf(
            e1 to mapOf(p3 to SlotFinals(issued = 2L, returned = 0L, leafSpent = 0L, rollupSpent = 5L)),
        )
        assertIs<Relocation.Refused>(EntitlementLedger.ZERO.relocationPatch(e3, finals))
    }

    /**
     * The `n < 0` net-negative guard used to be double-covered: pre-#1895 the per-edge `n ≥ sp` test
     * refused every net-negative shape anyway. It is now the **sole** protection, so it needs a pin
     * the aggregate cannot supply — a replica whose net-negative edge sits beside a sibling with
     * enough surplus to satisfy `Σcover ≥ Σcharge`. `break4` does not distinguish it: with the guard
     * deleted, the aggregate happens to catch break4's shape and its assertion still passes.
     */
    @Test
    fun aNetNegativeFencedEdgeIsRefusedEvenWhenASiblingCoversIt() {
        val finals = mapOf(
            e1 to mapOf(p3 to SlotFinals(issued = 10L, returned = 0L, leafSpent = 0L, rollupSpent = 0L)),
            e2 to mapOf(p3 to SlotFinals(issued = 0L, returned = 4L, leafSpent = 0L, rollupSpent = 0L)),
        )
        // Σcover = 10 + (−4) = 6 ≥ Σcharge = 0, so the aggregate is satisfied — only the per-edge
        // net-negative guard stands between this and a drain that cannot write a negative RETURNED.
        assertIs<Relocation.Refused>(EntitlementLedger.ZERO.relocationPatch(e3, finals))
    }

    @Test
    fun theCoverPreconditionIsExactAtItsBoundary() {
        fun move(cover: Long, charge: Long) = EntitlementLedger.ZERO.relocationPatch(
            e3,
            mapOf(e1 to mapOf(p3 to SlotFinals(issued = cover, returned = 0L, leafSpent = charge, rollupSpent = 0L))),
        )
        assertAll(
            // Exactly covered is fundable; one unit short is not. Without both, `cover + 1 < charge`
            // — permitting a single unit of overspend — passes the entire suite.
            { assertIs<Relocation.Moved>(move(cover = 5L, charge = 5L), "cover == charge is fundable") },
            { assertIs<Relocation.Refused>(move(cover = 4L, charge = 5L), "one unit short refuses") },
        )
    }

    /**
     * The §6.5.2 residual, reproduced on the **roll-up** family: an ack that understates a replica's
     * spend on the fenced edge (the cross-incarnation gap — charge, delta escapes to one other peer,
     * crash before ack, restart re-acks lower). Deliberately **not closed** in v1: closing it needs
     * durable per-peer authored-slot storage, and the residue sweep is specified and refused.
     *
     * What this pins is the *shape* of the residue the design promises: attributable, on the edge, and
     * outside the conservation identity — because `rollupSpent` is not a term in it.
     */
    @Test
    fun namedResidual_anUnderAckedRollupSpendSurfacesOnTheEdgeAndNotAsPhantomSupply() {
        val l = spendThroughStrand() // rollupSpent(e1)[p3] = 3, leafSpent(e2)[p3] = 3
        val understated = mapOf(e1 to mapOf(p3 to l.baseFinalsOn(e1, p3).copy(rollupSpent = 0L)))
        val move = l.relocationPatch(e3, understated)
        assertIs<Relocation.Moved>(move)
        val residual = l.piece(move.patch)

        assertAll(
            {
                assertEquals(
                    10L,
                    sumHoldings(residual) + residual.leafSpentTotal(),
                    "a roll-up residue is OUTSIDE the conservation identity — no phantom supply",
                )
            },
            {
                assertTrue(
                    residual.validate().contains(LedgerConflict.PerEdgeSafety(e1)),
                    "the residue surfaces as a diagnosed conflict naming the edge: ${residual.validate()}",
                )
            },
            {
                assertTrue(
                    residual.baseFinalsOn(e1, p3).rollupSpent > 0L,
                    "machine-attributable: base(e1)[p3] still exceeds what the ack declared",
                )
            },
        )
    }

    /**
     * The same residual on the **leaf** family — and the design's characterisation of it is wrong.
     *
     * `docs/heddle-ledger-relocation-design.md` §6.5.2 says the residue is "not a conservation break
     * (`rollupSpent` is outside the identity; a leaf-edge residue keeps the identity true because the
     * spend was real)". The second clause does not hold. `holdings` subtracts `effLeafSpent(f)` at the
     * child's **live inbound** edge, so an under-acked leaf spend moves the *credit* onto the live edge
     * without moving the *charge* with it — and `Σ holdings + Σ effLeafSpent` exceeds `minted` by
     * exactly the under-acked amount.
     *
     * This test pins the true behaviour rather than the documented claim, and it is *not* a regression
     * of this slice: the residual is out of scope by design, and the residue stays diagnosed and
     * attributable either way. It is recorded so the §6.5.2 text can be corrected and so a future
     * residue sweep knows the leaf case is the one that must run.
     */
    @Test
    fun namedResidual_anUnderAckedLEAFSpendDoesBreakTheIdentityContraDesign652() {
        var l = EntitlementLedger.ZERO.piece(EntitlementLedger.bootstrap(root, mapOf(p3 to 10L), nonce = "genesis"))
        l = l.piece(l.prepare(rec(e1, root, g))!!.delta) // g is a LEAF under e1
        l = l.piece(l.activate(e1)!!.delta)
        l = l.piece(l.delegate(p3, e1, 10L)!!.delta)
        l = l.piece(l.spend(p3, g, 5L)!!.delta) // leafSpent(e1)[p3] = 5
        l = l.piece(l.close(e1)!!.delta)
        l = l.piece(EntitlementLedger.of(lifecycle = mapOf(e1 to Lifecycle.RETIRED)))
        l = l.piece(l.prepare(rec(e3, root, g))!!.delta)
        l = l.piece(l.activate(e3)!!.delta)

        val understated = mapOf(e1 to mapOf(p3 to l.baseFinalsOn(e1, p3).copy(leafSpent = 0L)))
        val move = l.relocationPatch(e3, understated)
        assertIs<Relocation.Moved>(move)
        val residual = l.piece(move.patch)
        val identity = l.holdings(root, p3) // 0 — placeholder to keep the sum below explicit
        assertEquals(0L, identity)

        assertEquals(
            15L,
            residual.holdings(root, p3) + residual.holdings(g, p3) + residual.leafSpentTotal(),
            "a LEAF residue breaks the identity by the under-acked 5 — §6.5.2's claim that it does not is wrong",
        )
        // It is still surfaced, never silent: the edge reports and the gap is machine-attributable.
        assertTrue(
            residual.validate().contains(LedgerConflict.PerEdgeSafety(e1)),
            "the leaf residue is diagnosed on the edge: ${residual.validate()}",
        )
        assertTrue(residual.baseFinalsOn(e1, p3).leafSpent > 0L, "base(e1)[p3] still exceeds the ack")
    }

    @Test
    fun break2_theMagnitudeIsNoLongerReadFromAProposerViewAtAll() {
        // BREAK 2 (Wall A) is retired STRUCTURALLY by #1693, not patched. Under #1669 the magnitude —
        // and even the `spent(s)==0` carve-out — was computed on the proposer's data-plane view, so a
        // gossip-lagged proposer that had not merged `leafSpent(e1)=5` re-homed the full `issued −
        // returned = 10` and manufactured phantom supply on the converged state. The magnitude is now
        // derived from the log-recorded acked finals, so the proposer's view is not an input.
        //
        // The test that captures that: derive from the TRUTH's finals and from a STALE view's finals,
        // and show the stale derivation is not merely different-and-wrong but arithmetically incapable
        // of manufacturing supply — because what it drains is exactly what the acks declared.
        var truth = EntitlementLedger.ZERO.piece(EntitlementLedger.bootstrap(root, mapOf(p3 to 10L), nonce = "genesis"))
        truth = truth.piece(truth.prepare(rec(e1, root, g))!!.delta) // g is a leaf under e1
        truth = truth.piece(truth.activate(e1)!!.delta)
        truth = truth.piece(truth.delegate(p3, e1, 10L)!!.delta)
        truth = truth.piece(truth.spend(p3, g, 5L)!!.delta) // 5 spent AT g → leafSpent(e1)=5
        truth = truth.piece(truth.close(e1)!!.delta)
        truth = truth.piece(EntitlementLedger.of(lifecycle = mapOf(e1 to Lifecycle.RETIRED)))
        truth = truth.piece(truth.prepare(rec(e3, root, g))!!.delta)
        truth = truth.piece(truth.activate(e3)!!.delta)

        // A causally-complete ack (what the barrier guarantees: p3 marked e1 unwritable, then read its
        // own slots) relocates the spend along with the issuance and restores conservation exactly.
        // (`h` is deliberately excluded — it is not in this topology, so it reads as a second rootless
        // group credited the whole mint; see the ledger's one-root-per-ledger invariant.)
        val fenced = truth.piece(assertNotNull(truth.relocationOrNull(g), "the fenced move clears it"))
        assertTrue(fenced.validate().isEmpty(), "conflicts cleared: ${fenced.validate()}")
        assertEquals(
            fenced.mintedTotal(),
            fenced.holdings(root, p3) + fenced.holdings(g, p3) + fenced.leafSpentTotal(),
            "conservation restored — the case the stale-magnitude path used to break",
        )

        // The structural claim: the proposer's view is not an input. Two proposers with wildly
        // different data-plane views derive the SAME patch from the SAME acked finals, because the
        // derivation reads only the acks and the control plane's own relocation state.
        val finals = mapOf(e1 to truth.baseFinalsOn(e1))
        val fromCompleteView = truth.relocationPatch(e3, finals)
        val fromEmptyView = EntitlementLedger.ZERO.relocationPatch(e3, finals)
        assertIs<Relocation.Moved>(fromCompleteView)
        assertIs<Relocation.Moved>(fromEmptyView)
        assertEquals(
            fromCompleteView.patch,
            fromEmptyView.patch,
            "the derived patch must not depend on the deriving peer's data-plane view at all",
        )
    }
}
