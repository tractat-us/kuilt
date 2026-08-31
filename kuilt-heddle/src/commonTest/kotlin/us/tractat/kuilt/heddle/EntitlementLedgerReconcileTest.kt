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

    // #2366 — the transfer tangle's QUIET half: a recipient who merely *holds* transferred credit.
    private val bob = ReplicaId("bob") // the transfer RECIPIENT; p3 is the donor
    private val e4 = AttachmentId("e4") // g → h, the legal reparent generation for `h`

    // #2366 second hop — a hand-off CHAIN across two consecutive moves, with the middle peer gone.
    private val carol = ReplicaId("carol") // the original holder: mints, delegates, hands off to alice
    private val alice = ReplicaId("alice") // the middle of the chain; departs the roster after move 1
    private val e8 = AttachmentId("e8") // g → h, the SECOND legal reparent generation for `h`

    // #1916 — the cross-parent reparent: `g` moves out from under `a` and in under `b`.
    private val a = GroupId("a")
    private val b = GroupId("b")
    private val la = GroupId("la")
    private val ea = AttachmentId("ea") // root → a
    private val eb = AttachmentId("eb") // root → b
    private val e5 = AttachmentId("e5") // a → g,  stranded by the raced retire
    private val e6 = AttachmentId("e6") // b → g,  the CROSS-PARENT reparent generation
    private val e7 = AttachmentId("e7") // a → la, a leaf for `a` to spend its phantom credit through

    private fun rec(id: AttachmentId, parent: GroupId, child: GroupId) =
        AttachmentRecord(id, parent, child, Weight.ONE)

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

    // ── #1916 — the live edge and the retired edge must share a parent ───────────────────────────

    /**
     * The #1916 shape. `g`'s raced-retired inbound `e5` hangs off **`a`**; the legal reparent `e6`
     * hangs off **`b`**. Nothing here is dishonest and nothing has crashed: the retire lost the
     * ordinary advisory race of #1665, and reparenting a child under a *different* parent is a legal
     * reshape — `activate` only refuses a second *live* inbound, and `e5` is RETIRED by then.
     */
    private fun crossParentReparent(): EntitlementLedger {
        var l = EntitlementLedger.ZERO.piece(EntitlementLedger.bootstrap(root, mapOf(p3 to 10L), nonce = "genesis"))
        l = l.piece(l.prepare(rec(ea, root, a))!!.delta)
        l = l.piece(l.activate(ea)!!.delta)
        l = l.piece(l.prepare(rec(eb, root, b))!!.delta)
        l = l.piece(l.activate(eb)!!.delta)
        l = l.piece(l.prepare(rec(e5, a, g))!!.delta)
        l = l.piece(l.activate(e5)!!.delta)
        l = l.piece(l.delegate(p3, ea, 10L)!!.delta) // the whole mint moves root → a…
        l = l.piece(l.delegate(p3, e5, 10L)!!.delta) // …and on a → g
        // The raced advisory retire: a gossip-lagged peer read outstanding(e5) = 0 and retired it.
        l = l.piece(l.close(e5)!!.delta)
        l = l.piece(EntitlementLedger.of(lifecycle = mapOf(e5 to Lifecycle.RETIRED)))
        // The legal reparent — onto a DIFFERENT parent.
        l = l.piece(l.prepare(rec(e6, b, g))!!.delta)
        l = l.piece(l.activate(e6)!!.delta)
        return l
    }

    /**
     * `Σ max(0, holdings) + Σ effLeafSpent` — the supply this state can actually be made to serve.
     *
     * The conservation *identity* (`Σ holdings + Σ effLeafSpent == minted`) is **not** a safety
     * invariant: a negative pocket is unenforceable (its owner simply never spends again), so it can
     * arithmetically offset spendable credit manufactured somewhere else and keep the identity true
     * while real chargeable service exceeds the mint. Clamping each pocket at zero is what makes the
     * quantity mean "what can be spent", and the safety statement is the inequality `≤ minted`.
     */
    private fun enforceableSupply(l: EntitlementLedger): Long =
        l.allGroups().sumOf { maxOf(0L, l.holdings(it, p3)) } + l.leafSpentTotal()

    /**
     * #1916 — the §5.2 telescoping that makes a generation move conserving assumes the retired edge
     * `s` and the live edge `t` **share a parent**: the `−n` the parent of `t` takes on
     * `issuedRelocIn(t)` is supposed to be the same `+n` the parent of `s` recovers when
     * `netInflow(s)` drains to zero. Across a reparent those two terms land on *different* groups, so
     * they no longer cancel — one parent is credited spendable authority it does not own and the
     * other is left an unenforceable debt.
     *
     * This is the arithmetic the gate exists to refuse, derived by bypassing the gate — so it stays
     * true either side of the fix and documents exactly what the refusal is buying.
     */
    @Test
    fun aCrossParentMoveDoublesTheEnforceableSupplyWhileTheIdentityStaysIntact() {
        val l = crossParentReparent()
        // Derived the way `ControlPlane.reconcile` derives it: `relocationPatch` on the CONTROL
        // PLANE's own relocation accumulator (`FenceState.relocations` — empty, nothing has moved
        // yet), never on the data-plane view. The acks are honest: each replica's real base slots.
        val move = EntitlementLedger.ZERO.relocationPatch(e6, mapOf(e5 to l.baseFinalsOn(e5)))
        val moved = l.piece(assertIs<Relocation.Moved>(move).patch)

        assertAll(
            { assertEquals(10L, moved.holdings(a, p3), "a's netInflow(e5) subtraction vanishes — it recovers the whole mint") },
            { assertEquals(-10L, moved.holdings(b, p3), "…while issuedRelocIn(e6) debits b, which never held it") },
            { assertEquals(10L, moved.holdings(g, p3), "…and g is credited it as well") },
            {
                assertEquals(
                    moved.mintedTotal(),
                    moved.allGroups().sumOf { moved.holdings(it, p3) } + moved.leafSpentTotal(),
                    "the conservation IDENTITY stays intact — b's unenforceable −10 offsets a's phantom credit",
                )
            },
            {
                assertTrue(
                    moved.validate().none { it is LedgerConflict.ConservationViolation },
                    "…so the global conservation backstop does not fire: ${moved.validate()}",
                )
            },
            {
                assertEquals(
                    20L,
                    enforceableSupply(moved),
                    "…yet twice the mint is enforceable — an identity-based assertion proves nothing here",
                )
            },
        )
    }

    /**
     * …and the phantom credit is not a bookkeeping curiosity: it buys real service. Both `a` and `g`
     * spend their apparent 10 with the ordinary mutator — every holdings check passes — and the
     * cluster has served 20 units against a 10-unit mint.
     */
    @Test
    fun theCrossParentMovesPhantomCreditBuysRealService() {
        val l = crossParentReparent()
        val move = EntitlementLedger.ZERO.relocationPatch(e6, mapOf(e5 to l.baseFinalsOn(e5)))
        var spent = l.piece(assertIs<Relocation.Moved>(move).patch)

        // `g` is a leaf, so its phantom 10 spends directly. `a` is not (the retired `e5` is still a
        // child edge of it), so it delegates its phantom 10 to a leaf of its own and spends there.
        spent = spent.piece(assertNotNull(spent.spend(p3, g, 10L), "g's re-homed 10 is spendable").delta)
        spent = spent.piece(spent.prepare(rec(e7, a, la))!!.delta)
        spent = spent.piece(spent.activate(e7)!!.delta)
        spent = spent.piece(assertNotNull(spent.delegate(p3, e7, 10L), "a's recovered 10 is delegable").delta)
        spent = spent.piece(assertNotNull(spent.spend(p3, la, 10L), "…and spendable").delta)

        assertAll(
            { assertEquals(10L, spent.mintedTotal(), "the mint was never raised") },
            { assertEquals(20L, spent.leafSpentTotal(), "…yet 20 units of real service were charged against it") },
            {
                assertTrue(
                    spent.validate().contains(LedgerConflict.ConservationViolation(leafSpentTotal = 20L, mintedTotal = 10L)),
                    "the global backstop fires only now, long after the move that manufactured the supply: ${spent.validate()}",
                )
            },
        )
    }

    /**
     * The gate itself, at the ledger level: the one-line fence model refuses a strand whose retired
     * and live edges do not share a parent, naming both. The production gate it mirrors lives in
     * `HeddleControlPlane.reconcile` and is pinned by
     * `HeddleFenceTest.aReconcileAcrossACrossParentReparentIsRefused`.
     */
    @Test
    fun reconcileRefusesWhenTheLiveAndRetiredEdgesDoNotShareAParent() {
        val refused = crossParentReparent().relocateFromConvergedView(g)
        assertIs<Relocation.Refused>(refused, "a strand may only be re-homed within one parent (§5.2)")
        assertAll(
            { assertTrue(refused.reason.contains(e5.value), "the refusal names the retired edge: ${refused.reason}") },
            { assertTrue(refused.reason.contains(e6.value), "…and the live edge: ${refused.reason}") },
        )
    }

    /**
     * Non-vacuity for the gate: the ordinary #1665 reparent — same parent, new generation — still
     * reconciles. The refusal above must be about the *parent change*, not about reparenting at all.
     */
    @Test
    fun aSameParentReparentStillReconciles() {
        assertIs<Relocation.Moved>(d1Converged().relocateFromConvergedView(g))
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

    // ── #2366 — the transfer tangle's QUIET half ─────────────────────────────────────────────────

    /**
     * The #2366 strand, verbatim from the issue (`root →e1→ g →e2→ h`, `h` a leaf; 100 minted to the
     * donor `p3`). `p3` delegates 100 down `e1` and 60 down `e2`, then hands 40 of what it holds at
     * `h` to `bob`. The transfer row lands on `PathKey.of(e2)` — the **generation's** id — so the
     * pre-race pockets read `p3@h = 20`, `bob@h = 40`.
     *
     * Then the ordinary #1665 race: `e2` is retired with entitlement still outstanding, and `h` is
     * legally reparented onto a fresh `e4`. `holdings` now keys transfers off `PathKey.of(e4)`, where
     * there are no rows at all — so `bob`'s 40 is already unread, and the generation move is about to
     * re-home the whole 60 onto `p3` and make that permanent.
     *
     * @param withTransfer `false` builds the identical strand with the hand-off omitted — the control
     *   arm for every refusal assertion below, so "refuse everything" cannot pass this group.
     */
    private fun transferTangledLeafStrand(withTransfer: Boolean = true): EntitlementLedger {
        var l = EntitlementLedger.ZERO.piece(EntitlementLedger.bootstrap(root, mapOf(p3 to 100L), nonce = "genesis"))
        l = l.piece(l.prepare(rec(e1, root, g))!!.delta)
        l = l.piece(l.activate(e1)!!.delta)
        l = l.piece(l.prepare(rec(e2, g, h))!!.delta)
        l = l.piece(l.activate(e2)!!.delta)
        l = l.piece(l.delegate(p3, e1, 100L)!!.delta)
        l = l.piece(l.delegate(p3, e2, 60L)!!.delta)
        if (withTransfer) {
            // The hand-off, at `h`, while `e2` is still `h`'s live inbound: transfers[PathKey.of(e2)].
            l = l.piece(l.transfer(h, p3, bob, 40L)!!.delta)
            assertEquals(20L, l.holdings(h, p3), "fixture: the donor keeps 60 − 40")
            assertEquals(40L, l.holdings(h, bob), "fixture: the recipient holds the handed-off 40")
        }
        // The raced advisory retire, then the legal reparent onto a fresh generation.
        l = l.piece(l.close(e2)!!.delta)
        l = l.piece(EntitlementLedger.of(lifecycle = mapOf(e2 to Lifecycle.RETIRED)))
        l = l.piece(l.prepare(rec(e4, g, h))!!.delta)
        l = l.piece(l.activate(e4)!!.delta)
        return l
    }

    /**
     * The ledger-level path agrees with the log-pure one: a strand whose path key carries transfer
     * rows **moves**, and the rows move with it. This is the test #2376's blanket refusal used to
     * own; the refusal is retired because the abandonment it was containing no longer happens.
     *
     * Where it earns its keep now is the *donor* side, which the log-pure arm above cannot see: the
     * carry lands in `transferRelocIn` at the live key, and the dead key's rows are cancelled by
     * `transferRelocOut` rather than erased — `transfers` is grow-only and no fix can remove a row.
     */
    @Test
    fun aStrandWhosePathKeyCarriesTransferRowsMovesAndCarriesThem() {
        val l = transferTangledLeafStrand()
        val moved = assertIs<Relocation.Moved>(
            l.relocateFromConvergedView(h),
            "a strand carrying transfer rows must move, and take them along",
        )
        val after = l.piece(moved.patch)
        assertAll(
            // ── the rig: the row really is on the dead key and not on the live one, so what the
            // assertions below observe is a carry and not a coincidence.
            {
                assertEquals(
                    setOf(p3),
                    l.transferDonorsOn(e2),
                    "rig: the strand's path key really does carry a transfer row",
                )
            },
            {
                assertEquals(
                    emptySet(),
                    l.transferDonorsOn(e4),
                    "rig: …and the live edge's path key carries none of its own",
                )
            },
            {
                assertTrue(
                    l.baseFinalsOn(e2).values.all { it.issued >= it.returned },
                    "rig: nobody is net-negative on the strand, so the `n < 0` guard is not in play",
                )
            },
            { assertEquals(20L, after.holdings(h, p3), "the donor keeps only what it kept") },
            { assertEquals(40L, after.holdings(h, bob), "…and the recipient keeps what it was given") },
            {
                assertEquals(
                    setOf(p3),
                    after.transferDonorsOn(e2),
                    "the dead key's rows are CANCELLED, never erased — `transfers` is grow-only",
                )
            },
            { assertTrue(after.validate().isEmpty(), "…and nothing is left orphaned: ${after.validate()}") },
        )
    }

    /**
     * Non-vacuity for the carry above: the **identical** strand with the hand-off omitted still
     * moves, and lands the whole 60 on the donor because that is now the truth. Without this arm a
     * carry that credited the donor either way would pass the test above unremarked.
     */
    @Test
    fun aStrandWithNoTransferRowsStillMoves() {
        val l = transferTangledLeafStrand(withTransfer = false)
        val moved = assertIs<Relocation.Moved>(
            l.relocateFromConvergedView(h),
            "the guard must be about the transfer rows, not about this topology",
        )
        val reconciled = l.piece(moved.patch)
        assertAll(
            { assertEquals(60L, reconciled.holdings(h, p3), "the full net inflow re-homes onto the live generation") },
            { assertEquals(0L, reconciled.holdings(h, bob), "…and there was never a recipient here") },
            { assertTrue(reconciled.validate().isEmpty(), "the strand's conflicts clear: ${reconciled.validate()}") },
        )
    }

    /**
     * The **negative control** for the whole fix, and the corruption it removes: strip the rows back
     * out of the acks — the shape a `QuiesceAck` had before #2377 gave [SlotFinals] a `transfers`
     * field — and the abandonment returns exactly as reported.
     *
     * This is what proves the carry rides on the *ack*, not on something the deriving peer happened
     * to hold. Everything else is identical: the same strand, the same log-pure receiver, the same
     * derived counter families. Only the declaration is gone, and with it `bob`'s 40.
     *
     * The two properties that made this defect survive review are asserted here rather than
     * described, because both stay true after the fix and neither can ever be the thing that
     * catches a regression:
     *
     *  - **conservation is structurally blind.** `Σ_r transferNet(k, r) = 0` on every key, so
     *    abandoning a whole key is sum-preserving — `mintedTotal = Σ holdings + Σ effLeafSpent`
     *    still holds exactly, and only the owner changed.
     *  - **no pocket goes negative.** The recipient lands on `0`, not below, so
     *    [LedgerConflict.PersistentNegativeHoldings] never fires.
     *
     * What does catch it is [LedgerConflict.OrphanedTransferPath], and it is asserted to be the sole
     * report — so a future change that silences the diagnostic reds here even while the arithmetic
     * assertions stay green.
     */
    @Test
    fun anAckThatDeclaresNoTransferRowsAbandonsThemExactlyAsBefore() {
        val l = transferTangledLeafStrand()
        val undeclared = l.baseFinalsOn(e2).mapValues { (_, f) -> f.copy(transfers = emptyMap()) }
        val move = assertIs<Relocation.Moved>(
            EntitlementLedger.ZERO.relocationPatch(e4, mapOf(e2 to undeclared)),
            "the log-pure derivation still moves — undeclared rows are invisible to it",
        )
        val moved = l.piece(move.patch)
        assertAll(
            {
                assertEquals(
                    mapOf(bob to 40L),
                    l.baseFinalsOn(e2)[p3]?.transfers,
                    "rig: the honest ack really does declare the row this arm withholds",
                )
            },
            { assertEquals(60L, moved.holdings(h, p3), "the donor recovers the 40 it gave away") },
            { assertEquals(0L, moved.holdings(h, bob), "…and the recipient's credit is gone") },
            {
                assertEquals(
                    listOf(LedgerConflict.OrphanedTransferPath(PathKey.of(e2))),
                    moved.validate(),
                    "the abandoned rows are diagnosed, and are the ONLY thing diagnosed",
                )
            },
            {
                assertEquals(
                    moved.mintedTotal(),
                    moved.allGroups().sumOf { moved.holdings(it, p3) + moved.holdings(it, bob) } + moved.leafSpentTotal(),
                    "…and conservation is structurally blind to it: the sum is preserved, only the owner changed",
                )
            },
        )
    }

    /**
     * The fix: the **whole inbound half** travels with the generation — net inflow, already-charged
     * service, *and* the hand-offs — so the recipient's credit survives the move it never authored a
     * slot on.
     *
     * Derived the production way, on `EntitlementLedger.ZERO`: the rows reach the derivation through
     * the acked [SlotFinals] (a donor's row at a fenced edge's path key is single-writer at that
     * donor, so it is declarable final on exactly the argument that makes the four base counters
     * declarable), never off the deriving peer's gossip view. That is what lets the carry land on the
     * H5 control-plane path, whose receiver is log-pure.
     *
     * The arms deliberately outlive the move itself. A merge-based structure heals a dropped row on
     * the next write, so "correct immediately after the patch" is not the property — the property is
     * that the credit survives re-delivery in any order and is genuinely **spendable** afterwards.
     */
    @Test
    fun aGenerationMoveCarriesTheStrandsTransferRowsOntoTheLiveKey() {
        val l = transferTangledLeafStrand()
        val move = assertIs<Relocation.Moved>(
            EntitlementLedger.ZERO.relocationPatch(e4, mapOf(e2 to l.baseFinalsOn(e2))),
            "the log-pure control-plane derivation must still move the strand",
        )
        val moved = l.piece(move.patch)
        val remerged = moved.piece(move.patch) // idempotence: re-delivery of the same patch
        val reordered = move.patch.piece(l) // commutativity: the patch merged the other way round
        val spent = moved.spend(bob, h, 40L)?.let { moved.piece(it.delta) }
        assertAll(
            { assertEquals(20L, moved.holdings(h, p3), "the donor keeps only the 20 it did not hand over") },
            { assertEquals(40L, moved.holdings(h, bob), "…and the recipient's hand-off survives the move") },
            { assertEquals(20L, remerged.holdings(h, p3), "re-delivering the patch changes nothing") },
            { assertEquals(40L, remerged.holdings(h, bob), "…for either party") },
            { assertEquals(20L, reordered.holdings(h, p3), "nor does the merge order") },
            { assertEquals(40L, reordered.holdings(h, bob), "…for either party") },
            { assertTrue(moved.validate().isEmpty(), "a carried strand leaves no conflict: ${moved.validate()}") },
            // ── the recovery: the credit is real, not merely reported. bob spends the whole 40.
            { assertNotNull(spent, "the carried credit must be spendable") },
            { assertEquals(0L, spent?.holdings(h, bob), "the recipient spends what it was carried") },
            { assertEquals(20L, spent?.holdings(h, p3), "…and the donor is untouched by it") },
            { assertTrue(spent != null && spent.validate().isEmpty(), "…leaving a clean ledger: ${spent?.validate()}") },
            // ── and the move is not repeatable: a second Reconcile off the same strand carries
            // nothing more. A double-move is exactly as sum-preserving as an abandonment, so
            // conservation cannot be what rejects it.
            {
                assertIs<Relocation.Nothing>(
                    move.patch.relocationPatch(e4, mapOf(e2 to moved.baseFinalsOn(e2))),
                    "a second move off the drained strand must carry nothing",
                )
            },
        )
    }

    // ── #2366 second hop — a carried row whose donor is no longer on the roster ───────────────────

    /**
     * A hand-off **chain** carried across two consecutive generation moves, with the middle peer
     * gone by the second.
     *
     * `carol` mints 100, delegates it down to `h`, and hands the whole 100 to `alice`; `alice`
     * hands 40 of it to `bob`. Both rows sit on `PathKey.of(e2)`. The advisory-retire race then
     * re-homes `h` onto `e4`, and the move carries both rows — into `transferRelocIn`, because the
     * carry may never write the donor-owned base slot (#1691). So after move 1 the key
     * `holdings` reads carries its entire credit in the **relocation** matrix and nothing at all in
     * `transfers`.
     *
     * Returns the state as it stands after move 1 with `e4` still live (where the chain's pockets
     * are readable), the staged ledger with `e4` retired and `e8` live, and the first move's patch
     * — the control plane's own relocation state, which is `this` for the second derivation exactly
     * as `FenceState.relocations` is in production.
     */
    private class ChainAcrossTwoMoves(
        val afterMove1: EntitlementLedger,
        val staged: EntitlementLedger,
        val move1: Relocation.Moved,
    )

    private fun handOffChainAcrossTwoMoves(): ChainAcrossTwoMoves {
        var l = EntitlementLedger.ZERO.piece(
            EntitlementLedger.bootstrap(root, mapOf(carol to 100L), nonce = "genesis"),
        )
        l = l.piece(l.prepare(rec(e1, root, g))!!.delta)
        l = l.piece(l.activate(e1)!!.delta)
        l = l.piece(l.prepare(rec(e2, g, h))!!.delta)
        l = l.piece(l.activate(e2)!!.delta)
        l = l.piece(l.delegate(carol, e1, 100L)!!.delta)
        l = l.piece(l.delegate(carol, e2, 100L)!!.delta)
        l = l.piece(l.transfer(h, carol, alice, 100L)!!.delta) // hop 1
        l = l.piece(l.transfer(h, alice, bob, 40L)!!.delta) // hop 2
        // Race 1: retire `e2` with the chain still outstanding, reparent `h` onto `e4`.
        l = l.piece(l.close(e2)!!.delta)
        l = l.piece(EntitlementLedger.of(lifecycle = mapOf(e2 to Lifecycle.RETIRED)))
        l = l.piece(l.prepare(rec(e4, g, h))!!.delta)
        l = l.piece(l.activate(e4)!!.delta)
        val move1 = assertIs<Relocation.Moved>(
            EntitlementLedger.ZERO.relocationPatch(e4, mapOf(e2 to l.baseFinalsOn(e2))),
            "fixture: the first move must carry the chain onto e4",
        )
        val afterMove1 = l.piece(move1.patch)
        // Race 2: the same thing again, one generation on.
        var staged = afterMove1.piece(afterMove1.close(e4)!!.delta)
        staged = staged.piece(EntitlementLedger.of(lifecycle = mapOf(e4 to Lifecycle.RETIRED)))
        staged = staged.piece(staged.prepare(rec(e8, g, h))!!.delta)
        staged = staged.piece(staged.activate(e8)!!.delta)
        return ChainAcrossTwoMoves(afterMove1, staged, move1)
    }

    /**
     * **The defect, and the reason `Refused` is the right answer.** Donors are enumerated out of the
     * acks, so a donor whose only mark on the dead key is a row an *earlier* move carried there is
     * invisible to the derivation. Left that way it is not merely skipped: `carol`'s row moves onto
     * the live key while `alice`'s stays behind, so `alice` — who has **departed** — is credited the
     * 100 she was handed and `bob`'s 40 evaporates. Measured before the fix: `carol=0, alice=100,
     * bob=0`, `validate() == []`. Conservation is intact and nothing goes negative, exactly as in
     * the single-hop case; only the owner changed, and this time in favour of a peer that is gone.
     *
     * Carrying the row anyway would be worse than refusing. A row is declarable final because its
     * writer marked the edge locally unwritable at barrier time; a peer that never acked has
     * promised nothing, and moving on its behalf is the fabricate-beyond-owner violation the fence
     * exists to prevent. So the derivation refuses, names the donor, and leaves a state that
     * [LedgerConflict.OrphanedTransferPath] now reports.
     */
    @Test
    fun aCarriedRowWhoseDonorNeverAckedRefusesInsteadOfReassigningItsRecipient() {
        val chain = handOffChainAcrossTwoMoves()
        val staged = chain.staged
        val fullAcks = staged.baseFinalsOn(e4)
        val departedAcks = fullAcks.filterKeys { it != alice }
        val refused = chain.move1.patch.relocationPatch(e8, mapOf(e4 to departedAcks))
        assertAll(
            // ── the rig: the credit under test really does live where only the widened
            // enumeration can see it, and it really is live at the key about to be retired.
            {
                assertEquals(
                    emptySet(),
                    staged.transferDonorsOn(e4),
                    "rig: the dead key carries NO base transfer row — its whole credit arrived by " +
                        "carry, so an enumeration over `transfers` alone cannot reach it",
                )
            },
            {
                assertEquals(
                    40L,
                    chain.afterMove1.holdings(h, bob),
                    "rig: bob's hand-off survived move 1 and is live at the key about to be retired",
                )
            },
            {
                assertEquals(
                    60L,
                    chain.afterMove1.holdings(h, alice),
                    "rig: …and alice still holds the rest of the chain there",
                )
            },
            {
                assertTrue(
                    alice in fullAcks.keys,
                    "rig: alice IS reachable from the honest fence — the arm withholds her, the ledger does not",
                )
            },
            // ── the fix.
            {
                val reason = assertIs<Relocation.Refused>(
                    refused,
                    "a carried row whose donor never acked must refuse, not move the rest of the key",
                ).reason
                assertTrue(
                    "alice" in reason && "e4" in reason,
                    "the refusal must name the donor and the key so it is actionable: $reason",
                )
            },
            // ── and the state the refusal leaves is diagnosable rather than silent.
            {
                assertTrue(
                    LedgerConflict.OrphanedTransferPath(PathKey.of(e4)) in staged.validate(),
                    "the un-reconciled key must be reported — it holds credit no group's live " +
                        "lineage reads: ${staged.validate()}",
                )
            },
        )
    }

    /**
     * The **control arm**, and the non-vacuity of the refusal above: the identical chain with
     * `alice` still on the roster moves, and every pocket survives both hops. Without this arm
     * "refuse whenever `transferRelocIn` names a donor" — which would wedge the second `Reconcile`
     * of every strand that has ever been re-homed — passes the test above unremarked.
     */
    @Test
    fun theSameChainMovesWhenEveryCarriedDonorStillAcks() {
        val chain = handOffChainAcrossTwoMoves()
        val staged = chain.staged
        val move2 = assertIs<Relocation.Moved>(
            chain.move1.patch.relocationPatch(e8, mapOf(e4 to staged.baseFinalsOn(e4))),
            "with every carried donor acking, the second hop must move",
        )
        val moved = staged.piece(move2.patch)
        assertAll(
            { assertEquals(0L, moved.holdings(h, carol), "carol handed the whole 100 away, twice re-homed") },
            { assertEquals(60L, moved.holdings(h, alice), "alice keeps 100 − 40 across the second move") },
            { assertEquals(40L, moved.holdings(h, bob), "…and the end of the chain keeps its hand-off") },
            { assertTrue(moved.validate().isEmpty(), "a fully carried chain leaves no conflict: ${moved.validate()}") },
            // The carried credit is real, not merely reported: bob spends all of it.
            {
                val spent = moved.spend(bob, h, 40L)?.let { moved.piece(it.delta) }
                assertNotNull(spent, "the twice-carried credit must be spendable")
                assertEquals(0L, spent.holdings(h, bob), "…and spending it lands bob on zero")
            },
            // §5.4 (iii) idempotence survives the new refusal: a drained carried row must not
            // re-refuse merely because its donor appears in `transferRelocIn`.
            {
                assertIs<Relocation.Nothing>(
                    chain.move1.patch.piece(move2.patch)
                        .relocationPatch(e8, mapOf(e4 to moved.baseFinalsOn(e4))),
                    "a second move off the drained key must carry nothing, not refuse",
                )
            },
        )
    }
}
