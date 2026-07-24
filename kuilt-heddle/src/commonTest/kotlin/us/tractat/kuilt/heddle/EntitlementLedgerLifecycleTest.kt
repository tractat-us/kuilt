package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * H2 — lifecycle lattice + strict reconfiguration (#1605, design §5.1–5.3, §10.10–10.11).
 */
class EntitlementLedgerLifecycleTest {

    private val root = GroupId("root")
    private val a = GroupId("a")
    private val b = GroupId("b")
    private val c = GroupId("c")
    private val leaf = GroupId("leaf")
    private val alice = ReplicaId("alice")
    private val bob = ReplicaId("bob")

    private fun edge(id: String) = AttachmentId(id)
    private fun record(id: String, parent: GroupId, child: GroupId, weight: Weight = Weight.ONE) =
        AttachmentRecord(edge(id), parent, child, weight, 0L)

    /** Apply a (necessarily non-null) mutator patch onto a ledger. */
    private fun EntitlementLedger.applying(patch: Patch<EntitlementLedger>?): EntitlementLedger {
        assertNotNull(patch, "expected a non-null patch")
        return piece(patch)
    }

    // ── the register itself ───────────────────────────────────────────────────

    @Test
    fun lifecycleChainOrdersByMonotonePromotion() {
        assertEquals(
            listOf(Lifecycle.PREPARED, Lifecycle.ACTIVE, Lifecycle.CLOSING, Lifecycle.RETIRED),
            Lifecycle.entries.sorted(),
        )
    }

    @Test
    fun prepareActivateWalkTheChainAndGateDelegation() {
        val e = edge("e")
        var l: EntitlementLedger = EntitlementLedger.bootstrap(root, mapOf(alice to 100L), "g")
        l = l.applying(l.prepare(record("e", root, leaf)))
        assertEquals(Lifecycle.PREPARED, l.lifecycle(e))
        // No delegation down a prepared edge.
        assertNull(l.delegate(alice, e, 10L))
        assertTrue(l.activeChildren(root).isEmpty(), "prepared edge is not an active child")

        l = l.applying(l.activate(e))
        assertEquals(Lifecycle.ACTIVE, l.lifecycle(e))
        assertNotNull(l.delegate(alice, e, 10L), "active edge admits delegation")
        assertEquals(listOf(e), l.activeChildren(root).map { it.attachment })

        // Re-preparing an existing generation is refused; an unknown edge has no lifecycle.
        assertNull(l.prepare(record("e", root, leaf)))
        assertNull(l.lifecycle(edge("unknown")))
    }

    // ── §10.10 closure dominance under adversarial merge ──────────────────────

    @Test
    fun closingDominatesActivationRegardlessOfMergeOrder() {
        val e = edge("e")
        var base: EntitlementLedger = EntitlementLedger.bootstrap(root, mapOf(alice to 100L), "g")
        base = base.applying(base.prepare(record("e", root, leaf)))
        base = base.applying(base.activate(e))

        val closed = base.applying(base.close(e))
        assertEquals(Lifecycle.CLOSING, closed.lifecycle(e))

        // A concurrent activation delta (a laggard still trying to activate the edge).
        val resurrect = EntitlementLedger.of(lifecycle = mapOf(e to Lifecycle.ACTIVE))

        // Max-register: CLOSING wins in either merge order — activation cannot resurrect it.
        assertEquals(Lifecycle.CLOSING, closed.piece(resurrect).lifecycle(e))
        assertEquals(Lifecycle.CLOSING, resurrect.piece(closed).lifecycle(e))
        // And activate() on the merged closing state is refused.
        assertNull(closed.piece(resurrect).activate(e))
        // Delegation across a closing edge is refused (no new delegation admitted).
        assertNull(closed.delegate(alice, e, 5L))
    }

    @Test
    fun retiredDominatesEveryLowerStateUnderMerge() {
        val e = edge("e")
        var l: EntitlementLedger = EntitlementLedger.bootstrap(root, mapOf(alice to 100L), "g")
        l = l.applying(l.prepare(record("e", root, leaf)))
        l = l.applying(l.activate(e))
        l = l.applying(l.delegate(alice, e, 10L))
        l = l.applying(l.release(alice, e, 10L)) // drain
        l = l.applying(l.close(e))
        val retired = l.applying(l.retire(e))
        assertEquals(Lifecycle.RETIRED, retired.lifecycle(e))

        for (lower in listOf(Lifecycle.PREPARED, Lifecycle.ACTIVE, Lifecycle.CLOSING)) {
            val delta = EntitlementLedger.of(lifecycle = mapOf(e to lower))
            assertEquals(Lifecycle.RETIRED, retired.piece(delta).lifecycle(e))
            assertEquals(Lifecycle.RETIRED, delta.piece(retired).lifecycle(e))
        }
    }

    // ── retire refused until outstanding == 0 ─────────────────────────────────

    @Test
    fun retireRefusedUntilDrainedThenNeverIssuesAgain() {
        val e = edge("e")
        var l: EntitlementLedger = EntitlementLedger.bootstrap(root, mapOf(alice to 100L), "g")
        l = l.applying(l.prepare(record("e", root, leaf)))
        l = l.applying(l.activate(e))
        l = l.applying(l.delegate(alice, e, 10L))
        assertEquals(10L, l.edge(e)?.outstanding)

        l = l.applying(l.close(e))
        // Outstanding entitlement still stands → retire refused.
        assertNull(l.retire(e))
        assertEquals(Lifecycle.CLOSING, l.lifecycle(e))

        // Drain it (return the whole grant up the edge), then retire succeeds.
        l = l.applying(l.release(alice, e, 10L))
        assertEquals(0L, l.edge(e)?.outstanding)
        l = l.applying(l.retire(e))
        assertEquals(Lifecycle.RETIRED, l.lifecycle(e))

        // A retired edge never issues again: delegation is refused.
        assertNull(l.delegate(alice, e, 1L))
        // History remains queryable and unchanged.
        assertEquals(EdgeSummary(e, issued = 10L, returned = 10L, spent = 0L), l.edge(e))
        assertTrue(l.validate().isEmpty(), "a cleanly drained + retired edge is conflict-free")
    }

    @Test
    fun retireRefusedFromActiveWithoutClosing() {
        val e = edge("e")
        var l: EntitlementLedger = EntitlementLedger.bootstrap(root, mapOf(alice to 100L), "g")
        l = l.applying(l.prepare(record("e", root, leaf)))
        l = l.applying(l.activate(e))
        // Even fully-drained, retire requires CLOSING first (strict generation-and-drain).
        assertNull(l.retire(e))
    }

    // ── §10.11 two active inbound generations ─────────────────────────────────

    @Test
    fun twoActiveInboundConvergesToSameReportEverywhereAndRefusesDelegation() {
        val e1 = edge("e1")
        val e2 = edge("e2")
        val base = EntitlementLedger.bootstrap(root, mapOf(alice to 100L), "g")

        // Two replicas each prepare + activate a DIFFERENT inbound edge for the same child c.
        var x = base.applying(base.prepare(record("e1", root, c)))
        x = x.applying(x.activate(e1))
        var y = base.applying(base.prepare(record("e2", root, c)))
        y = y.applying(y.activate(e2))

        val merged = x.piece(y)
        val mergedOther = y.piece(x)

        // Convergence: both merge orders agree, and both fold to the SAME sorted report.
        assertEquals(merged, mergedOther)
        assertEquals(merged.validate(), mergedOther.validate())
        assertTrue(
            LedgerConflict.DualActiveInbound(c) in merged.validate(),
            "two active inbound generations surface DualActiveInbound",
        )

        // The contested lineage is quarantined and delegation across EITHER edge is refused.
        assertEquals(0L, merged.holdings(c, alice))
        assertNull(merged.delegate(alice, e1, 10L))
        assertNull(merged.delegate(alice, e2, 10L))
        // c is not offered as an active child under a single unambiguous parent view either —
        // but both edges are individually active, so activeChildren still lists them; the
        // refusal is the safety property, not the listing.
        assertFalse(c in merged.validate().filterIsInstance<LedgerConflict.PersistentNegativeHoldings>().map { it.group })
    }

    // ── reparent / weight-change end to end ───────────────────────────────────

    @Test
    fun reparentDrainsOldGenerationAndPreservesItsHistory() {
        val eA = edge("eA") // root → a
        val eB = edge("eB") // root → b
        val g1 = edge("g1") // a → c   (old generation)
        val g2 = edge("g2") // b → c   (new generation)
        var l: EntitlementLedger = EntitlementLedger.bootstrap(root, mapOf(alice to 100L), "g")
        l = l.applying(l.prepare(record("eA", root, a)))
        l = l.applying(l.activate(eA))
        l = l.applying(l.prepare(record("eB", root, b)))
        l = l.applying(l.activate(eB))
        l = l.applying(l.prepare(record("g1", a, c, Weight.of(1, 1))))
        l = l.applying(l.activate(g1))

        // Fund a→c, spend some service at leaf c, leaving live entitlement on g1.
        l = l.applying(l.delegate(alice, eA, 30L))
        l = l.applying(l.delegate(alice, eB, 10L))
        l = l.applying(l.delegate(alice, g1, 10L))
        l = l.applying(l.spend(alice, c, 4L))
        val g1RecordBefore = record("g1", a, c, Weight.of(1, 1))
        val g1HistoryDrained = EdgeSummary(g1, issued = 10L, returned = 6L, spent = 4L)

        // ── Reparent c from a to b: prepare new gen, close-drain-retire old, activate new.
        l = l.applying(l.prepare(record("g2", b, c, Weight.of(3, 1)))) // new generation, new weight
        l = l.applying(l.close(g1))
        // Drain: c returns its remaining 6 up g1 (issued 10 − spent 4 = 6 outstanding).
        assertEquals(6L, l.holdings(c, alice))
        l = l.applying(l.release(alice, g1, 6L))
        assertEquals(0L, l.edge(g1)?.outstanding)
        l = l.applying(l.retire(g1))
        l = l.applying(l.activate(g2))

        // Old generation g1: RETIRED, record immutable (still weight 1, parent a), history frozen.
        assertEquals(Lifecycle.RETIRED, l.lifecycle(g1))
        assertEquals(g1HistoryDrained, l.edge(g1))
        assertEquals(setOf(g1RecordBefore), l.recordsOf(g1))

        // New generation g2: ACTIVE, distinct id, its own weight; c reachable under b now.
        assertEquals(Lifecycle.ACTIVE, l.lifecycle(g2))
        assertEquals(Weight.of(3, 1), l.recordsOf(g2).single().weight)
        assertEquals(listOf(g2), l.activeChildren(b).map { it.attachment })
        assertTrue(l.activeChildren(a).isEmpty(), "a no longer has an active child")

        // c starts prospectively neutral under b (holdings 0), but is reachable: delegation works.
        assertEquals(0L, l.holdings(c, alice))
        l = l.applying(l.delegate(alice, g2, 5L))
        assertEquals(5L, l.holdings(c, alice))

        // The whole reparented tree is conflict-free — no DualActiveInbound, no ClosureViolation.
        assertTrue(l.validate().isEmpty(), "clean reparent leaves no conflicts: ${l.validate()}")
    }

    // ── ClosureViolation from a late crossing of a retired generation ─────────

    @Test
    fun lateDelegationAcrossRetiredEdgeSurfacesClosureViolation() {
        val e = edge("e")
        var l: EntitlementLedger = EntitlementLedger.bootstrap(root, mapOf(alice to 100L), "g")
        l = l.applying(l.prepare(record("e", root, leaf)))
        l = l.applying(l.activate(e))
        l = l.applying(l.delegate(alice, e, 10L))
        l = l.applying(l.release(alice, e, 10L))
        l = l.applying(l.close(e))
        val retired = l.applying(l.retire(e))
        assertTrue(retired.validate().isEmpty())

        // A stale replica, having only ever observed the edge ACTIVE, delegates late:
        // its issued delta lands on the edge the cluster already retired.
        val lateDelegation = EntitlementLedger.of(issued = mapOf(e to GCounter.of(bob to 5L)))
        val violated = retired.piece(lateDelegation)

        assertEquals(Lifecycle.RETIRED, violated.lifecycle(e)) // closure still dominates
        assertTrue(
            LedgerConflict.ClosureViolation(e) in violated.validate(),
            "entitlement crossing a retired generation surfaces ClosureViolation: ${violated.validate()}",
        )
    }

    // ── BREAK 1: release must credit only the child's live inbound edge ────────

    @Test
    fun releaseDownNonLiveInboundEdgeMintsNothing() {
        // Repro A: a child c reachable via an ACTIVE e2, plus a stray PREPARED e1 that never
        // carried anything. release() up e1 gates on c's (e2-funded) pocket but would credit
        // returned(e1) — minting holdings from nothing. It must be refused.
        val e1 = edge("e1")
        val e2 = edge("e2")
        var l: EntitlementLedger = EntitlementLedger.bootstrap(root, mapOf(bob to 100L), "g")
        l = l.applying(l.prepare(record("e1", root, c)))
        l = l.applying(l.prepare(record("e2", root, c)))
        l = l.applying(l.activate(e2))
        l = l.applying(l.delegate(bob, e2, 7L))
        assertEquals(93L, l.holdings(root, bob))
        assertEquals(7L, l.holdings(c, bob))

        // e1 is PREPARED and is NOT c's live inbound edge → release across it is refused.
        assertNull(l.release(bob, e1, 7L))
        // Conservation intact: still exactly the 100 minted, split 93 + 7.
        assertEquals(93L, l.holdings(root, bob))
        assertEquals(7L, l.holdings(c, bob))
        // No phantom authority was manufactured — bob cannot delegate the un-held 100.
        assertNull(l.delegate(bob, e2, 100L))
    }

    @Test
    fun releaseDownRetiredInboundEdgeMintsNothing() {
        // Repro B: e1 drained + retired, then c re-funded via a fresh ACTIVE e2. release() up
        // the RETIRED e1 (not c's live inbound) must be refused.
        val e1 = edge("e1")
        val e2 = edge("e2")
        var l: EntitlementLedger = EntitlementLedger.bootstrap(root, mapOf(bob to 100L), "g")
        l = l.applying(l.prepare(record("e1", root, c)))
        l = l.applying(l.activate(e1))
        l = l.applying(l.delegate(bob, e1, 5L))
        l = l.applying(l.release(bob, e1, 5L))
        l = l.applying(l.close(e1))
        l = l.applying(l.retire(e1))
        // Fresh generation carries c now.
        l = l.applying(l.prepare(record("e2", root, c)))
        l = l.applying(l.activate(e2))
        l = l.applying(l.delegate(bob, e2, 7L))

        assertNull(l.release(bob, e1, 7L)) // e1 RETIRED, not live inbound → refused
        assertEquals(93L, l.holdings(root, bob))
        assertEquals(7L, l.holdings(c, bob))
    }

    @Test
    fun releaseDownTheLiveClosingInboundStillDrains() {
        // A CLOSING edge IS still the child's live inbound — release across it must be admitted
        // so the edge can drain (design §5.1).
        val e = edge("e")
        var l: EntitlementLedger = EntitlementLedger.bootstrap(root, mapOf(alice to 100L), "g")
        l = l.applying(l.prepare(record("e", root, leaf)))
        l = l.applying(l.activate(e))
        l = l.applying(l.delegate(alice, e, 10L))
        l = l.applying(l.close(e))
        // Draining across the closing edge is allowed.
        l = l.applying(l.release(alice, e, 10L))
        assertEquals(0L, l.edge(e)?.outstanding)
    }

    // ── BREAK 2: an ACTIVE + CLOSING dual inbound must be reported, not silent ──

    @Test
    fun activePlusClosingDualInboundIsReportedNotSilentlyQuarantined() {
        val e1 = edge("e1")
        val e2 = edge("e2")
        val base = EntitlementLedger.bootstrap(root, mapOf(alice to 100L), "g")

        // Replica X: prepare + activate + close e1 → e1 CLOSING (still live, draining).
        var x = base.applying(base.prepare(record("e1", root, c)))
        x = x.applying(x.activate(e1))
        x = x.applying(x.close(e1))
        // Replica Y: prepare + activate e2 → e2 ACTIVE.
        var y = base.applying(base.prepare(record("e2", root, c)))
        y = y.applying(y.activate(e2))

        val merged = x.piece(y)
        val mergedOther = y.piece(x)
        assertEquals(merged, mergedOther)
        assertEquals(merged.validate(), mergedOther.validate())

        // c has one CLOSING + one ACTIVE inbound = two LIVE inbound → quarantined AND reported.
        assertEquals(0L, merged.holdings(c, alice))
        assertTrue(
            LedgerConflict.DualActiveInbound(c) in merged.validate(),
            "a live (active|closing) dual inbound must surface DualActiveInbound: ${merged.validate()}",
        )
    }

    // ── BREAK 3: retire must witness the drain so laggards don't false-fire ────

    @Test
    fun retirePatchWitnessesDrainSoLaggardSeesNoFalseClosureViolation() {
        val e = edge("e")
        var setup: EntitlementLedger = EntitlementLedger.bootstrap(root, mapOf(alice to 100L), "g")
        setup = setup.applying(setup.prepare(record("e", root, leaf)))
        setup = setup.applying(setup.activate(e))

        val delegateP = setup.delegate(alice, e, 10L)
        assertNotNull(delegateP)
        val afterDelegate = setup.piece(delegateP)
        val releaseP = afterDelegate.release(alice, e, 10L)
        assertNotNull(releaseP)
        val afterRelease = afterDelegate.piece(releaseP)
        val closeP = afterRelease.close(e)
        assertNotNull(closeP)
        val afterClose = afterRelease.piece(closeP)
        val retireP = afterClose.retire(e)
        assertNotNull(retireP)

        // Fully delivered: clean.
        assertTrue(afterClose.piece(retireP).validate().isEmpty())

        // A laggard that has {delegate, close, retire} but NOT the release patch must still not
        // false-fire ClosureViolation — the retire patch carries the drained counters as a witness.
        val laggard = setup.piece(delegateP).piece(closeP).piece(retireP)
        assertEquals(Lifecycle.RETIRED, laggard.lifecycle(e))
        assertFalse(
            LedgerConflict.ClosureViolation(e) in laggard.validate(),
            "retire's drain witness must keep a laggard from false-firing ClosureViolation: ${laggard.validate()}",
        )
    }

    // ── Minor: edge()'s known-check must include the lifecycle register ────────

    @Test
    fun lifecycleOnlyEdgeIsKnownToBothLifecycleAndEdge() {
        // An activate/close patch delivered before its prepare carries only a lifecycle entry.
        val e = edge("e")
        val lifecycleOnly = EntitlementLedger.of(lifecycle = mapOf(e to Lifecycle.ACTIVE))
        assertNotNull(lifecycleOnly.lifecycle(e))
        assertNotNull(lifecycleOnly.edge(e)) // must be consistent with lifecycle()'s known-check
    }
}
