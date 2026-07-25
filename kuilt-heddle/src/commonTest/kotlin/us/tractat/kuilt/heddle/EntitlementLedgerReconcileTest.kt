package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun reconcileStrandedClearsConflictsAndRestoresConservation() {
        val l = d1Converged()
        val patch = l.reconcileStranded(g)
        assertNotNull(patch, "there is a strand to reconcile")
        val reconciled = l.piece(patch.delta)

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
        val reconciled = l.piece(l.reconcileStranded(g)!!.delta)
        // Never changes minted supply.
        assertEquals(l.mintedTotal(), reconciled.mintedTotal())
        // Nothing left to re-home: a second pass is a no-op (no new supply, no re-inflation).
        assertNull(reconciled.reconcileStranded(g), "the strand is cleared — a second reconcile finds nothing")
        // Re-applying the same patch (duplicate delivery) changes nothing — max-merge idempotence.
        assertEquals(reconciled, reconciled.piece(l.reconcileStranded(g)?.delta ?: EntitlementLedger.ZERO))
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
        assertNull(l.reconcileStranded(g), "no live inbound → nothing to re-home onto → refuse")
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
    fun break1and3_spendThroughStrandIsRefusedNotSilentlyDestroyed() {
        // BREAK 1/3 (Wall B): re-homing `outstanding(s)` under-credits by `spent(s)` (silent destruction),
        // and re-homing the full net inflow would need `returned(s)=issued(s)` while `rollupSpent(s)=3`
        // stays grow-only → per-edge safety `spent+returned>issued` (a CREATED PerEdgeSafety(e1)). No
        // conserving patch exists under the current representation, so reconcile must FAIL CLOSED.
        val l = spendThroughStrand()
        assertEquals(3L, l.edge(e1)!!.spent, "service was spent through the stranded edge")
        assertNull(l.reconcileStranded(g), "through-service strand cannot be conservingly cleared → refuse")
        // Fail-closed leaves the pre-existing conflicts standing (recoverable) — NEVER a silent break.
        assertTrue(
            l.validate().contains(LedgerConflict.PersistentNegativeHoldings(g, p3)),
            "the strand's conflicts remain after the refusal, not silently destroyed",
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
        assertNull(l.reconcileStranded(g), "transfer-tangled strand (a replica net-negative on s) → refuse")
    }

    @Test
    fun break2_staleMagnitudeIsUnsound_documentsWallA() {
        // BREAK 2 (Wall A, THE RESIDUAL): the witness magnitude — and even the `spent(s)==0` carve-out —
        // is computed on the proposer's data-plane view, which is NOT consensus-fenced. A gossip-lagged
        // proposer that has not merged a `leafSpent(e1)=5` delta sees `spent(e1)=0`, passes the carve-out,
        // and re-homes the full `issued−returned=10`, shipping `returned(e1)→10`. Committed to the log and
        // merged with the TRUTH, that manufactures phantom supply. This test PINS the residual break so the
        // fix (a causal-stability quiesce of the stranded edge's counters, §9 #3) is not forgotten — it is
        // deliberately NOT closed in this PR (representation/quiesce work).
        var truth = EntitlementLedger.ZERO.piece(EntitlementLedger.bootstrap(root, mapOf(p3 to 10L), nonce = "genesis"))
        truth = truth.piece(truth.prepare(rec(e1, root, g))!!.delta) // g is a leaf under e1
        truth = truth.piece(truth.activate(e1)!!.delta)
        truth = truth.piece(truth.delegate(p3, e1, 10L)!!.delta)
        truth = truth.piece(truth.spend(p3, g, 5L)!!.delta) // 5 spent AT g → leafSpent(e1)=5
        truth = truth.piece(truth.close(e1)!!.delta)
        truth = truth.piece(EntitlementLedger.of(lifecycle = mapOf(e1 to Lifecycle.RETIRED)))
        truth = truth.piece(truth.prepare(rec(e3, root, g))!!.delta)
        truth = truth.piece(truth.activate(e3)!!.delta)

        // The proposer's STALE view: identical, minus the leafSpent(e1)=5 delta it hasn't gossiped in yet.
        var stale = EntitlementLedger.ZERO.piece(EntitlementLedger.bootstrap(root, mapOf(p3 to 10L), nonce = "genesis"))
        stale = stale.piece(stale.prepare(rec(e1, root, g))!!.delta)
        stale = stale.piece(stale.activate(e1)!!.delta)
        stale = stale.piece(stale.delegate(p3, e1, 10L)!!.delta)
        stale = stale.piece(stale.close(e1)!!.delta)
        stale = stale.piece(EntitlementLedger.of(lifecycle = mapOf(e1 to Lifecycle.RETIRED)))
        stale = stale.piece(stale.prepare(rec(e3, root, g))!!.delta)
        stale = stale.piece(stale.activate(e3)!!.delta)

        assertEquals(0L, stale.edge(e1)!!.spent, "the stale proposer wrongly sees no through-service")
        val staleWitness = stale.reconcileStranded(g)
        assertNotNull(staleWitness, "the stale carve-out check passes, so the proposer WOULD propose")

        // Apply the stale witness to the TRUTH (what every peer converges to): phantom supply appears.
        val poisoned = truth.piece(staleWitness.delta)
        var sum = 0L
        for (grp in listOf(root, g, h)) sum += poisoned.holdings(grp, p3)
        assertTrue(
            sum + poisoned.leafSpentTotal() > poisoned.mintedTotal(),
            "WALL A residual: stale-magnitude reconcile manufactures phantom supply (Σ holdings + leafSpent > minted)",
        )
    }
}
