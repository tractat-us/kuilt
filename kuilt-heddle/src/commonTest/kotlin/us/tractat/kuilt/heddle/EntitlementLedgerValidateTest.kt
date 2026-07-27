package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The integrity checks (design §4.6 / `heddle-ledger-design.md` §`validate`): the
 * self-justifying witness (fix 2), the projection homomorphism (§10.8), and each
 * [LedgerConflict] kind — [LedgerConflict.PerEdgeSafety],
 * [LedgerConflict.PersistentNegativeHoldings], [LedgerConflict.RecordDivergence],
 * [LedgerConflict.LineageCycle] and the global [LedgerConflict.ConservationViolation]
 * backstop (plus the overflow-checked aggregate reads that feed them).
 */
class EntitlementLedgerValidateTest {

    private val root = GroupId("root")
    private val g1 = GroupId("g1")
    private val g2 = GroupId("g2")
    private val e1 = AttachmentId("e1") // root → g1
    private val e2 = AttachmentId("e2") // g1   → g2 (leaf, two edges deep)
    private val alice = ReplicaId("alice")
    private val bob = ReplicaId("bob")

    private fun twoDeepTree(): EntitlementLedger = EntitlementLedger.of(
        records = mapOf(
            e1 to setOf(AttachmentRecord(e1, root, g1, Weight.ONE, 0L)),
            e2 to setOf(AttachmentRecord(e2, g1, g2, Weight.ONE, 0L)),
        ),
    )

    /**
     * Self-justifying witness (fix 2): a spend patch delivered to a replica MISSING
     * the funding delegate must NOT false-fire PerEdgeSafety or quarantine. The spend
     * carries the observed `issued` credit it read along the whole path, so the two
     * charged edges (leaf `e2`, roll-up `e1`) stay balanced on the receiver.
     */
    @Test
    fun spendPatchCarriesItsOwnJustificationUnderPartialDelivery() {
        // Fund alice at g2 two levels down: mint at root, delegate e1, delegate e2.
        var funded = twoDeepTree().piece(EntitlementLedger.bootstrap(root, mapOf(alice to 100L), nonce = "g"))
        funded = funded.piece(funded.delegate(alice, e1, 50L)!!)
        funded = funded.piece(funded.delegate(alice, e2, 20L)!!)
        assertEquals(20L, funded.holdings(g2, alice))

        // The spend patch alone, delivered onto the bare topology (no mint, no delegates).
        val spendPatch = funded.spend(alice, g2, 20L)!!
        val onlyTopology = twoDeepTree()
        val received = onlyTopology.piece(spendPatch)

        // No false conflict: the witness brought issued(e1)/issued(e2) = 20 alongside
        // leafSpent(e2)=20 and rollupSpent(e1)=20.
        assertTrue(received.validate().isEmpty(), "witness failed — false conflict: ${received.validate()}")
        // And the same patch delivered onto ZERO (no topology at all) is also clean.
        assertTrue(EntitlementLedger.ZERO.piece(spendPatch).validate().isEmpty())
    }

    /** Delivering the same patch N times raises history exactly once (lattice idempotence). */
    @Test
    fun duplicateDeliveryIsIdempotent() {
        var funded = twoDeepTree().piece(EntitlementLedger.bootstrap(root, mapOf(alice to 100L), nonce = "g"))
        funded = funded.piece(funded.delegate(alice, e1, 40L)!!)
        funded = funded.piece(funded.delegate(alice, e2, 30L)!!)
        val spend = funded.spend(alice, g2, 10L)!! // g2 is the leaf
        val once = funded.piece(spend)
        var many = funded
        repeat(5) { many = many.piece(spend) }
        assertEquals(once, many, "duplicate delivery was not idempotent")
        assertEquals(10L, once.edge(e2)?.spent) // history rose once, not 5×
    }

    /** PerEdgeSafety fires sum-wise when charged-plus-returned exceeds issued. */
    @Test
    fun perEdgeSafetyFiresWhenChargeExceedsIssued() {
        // Manually construct an edge charged beyond what was issued (a bug/equivocation).
        val broken = EntitlementLedger.of(
            records = mapOf(e1 to setOf(AttachmentRecord(e1, root, g1, Weight.ONE, 0L))),
            issued = mapOf(e1 to GCounter.of(alice to 5L)),
            leafSpent = mapOf(e1 to GCounter.of(alice to 8L)), // 8 > 5
        )
        // Charging 8 against 5 issued trips PerEdgeSafety; the same overcharge also
        // strands holdings(g1, alice) = 5 − 8 = −3, so the negative fires too.
        val report = broken.validate()
        assertTrue(LedgerConflict.PerEdgeSafety(e1) in report)
        assertTrue(LedgerConflict.PersistentNegativeHoldings(g1, alice) in report)
    }

    /** A per-slot returned > issued is legitimate (returned entitlement received by transfer). */
    @Test
    fun perSlotReturnedAboveIssuedIsNotAConflict() {
        // bob returns across e1 what he received by transfer; alice issued it. Sum-wise safe.
        val ledger = EntitlementLedger.of(
            records = mapOf(e1 to setOf(AttachmentRecord(e1, root, g1, Weight.ONE, 0L))),
            issued = mapOf(e1 to GCounter.of(alice to 10L)),
            returned = mapOf(e1 to GCounter.of(bob to 4L)), // per-slot bob 4 > issued bob 0, but sum 4 <= 10
        )
        assertTrue(ledger.validate().none { it is LedgerConflict.PerEdgeSafety })
    }

    /** PersistentNegativeHoldings catches an overspend within the edge sum but beyond a pocket. */
    @Test
    fun persistentNegativeHoldingsCatchesOverspendWithinEdgeSum() {
        // alice issued 10 down e1, but bob spent 6 at g1 while holding nothing there:
        // edge sum 6 <= 10 passes PerEdgeSafety, yet holdings(g1, bob) = -6.
        val leaf = EntitlementLedger.of(
            records = mapOf(e1 to setOf(AttachmentRecord(e1, root, g1, Weight.ONE, 0L))),
            issued = mapOf(e1 to GCounter.of(alice to 10L)),
            leafSpent = mapOf(e1 to GCounter.of(bob to 6L)),
        )
        assertTrue(leaf.validate().none { it is LedgerConflict.PerEdgeSafety }, "edge sum is within bound")
        assertEquals(-6L, leaf.holdings(g1, bob))
        assertTrue(LedgerConflict.PersistentNegativeHoldings(g1, bob) in leaf.validate())
    }

    /** Two divergent records under one id merge to a reported divergence, quarantining the lineage. */
    @Test
    fun recordDivergenceIsReportedAndQuarantinesHoldings() {
        val left = EntitlementLedger.of(
            records = mapOf(e1 to setOf(AttachmentRecord(e1, root, g1, Weight.ONE, 0L))),
            minted = mapOf(MintId("m") to MintRecord(alice, 10L)), // funded, so root is not itself negative
            issued = mapOf(e1 to GCounter.of(alice to 10L)),
        )
        val right = EntitlementLedger.of(
            records = mapOf(e1 to setOf(AttachmentRecord(e1, root, g1, Weight.of(3, 1), 0L))),
        )
        val merged = left.piece(right)
        assertTrue(LedgerConflict.RecordDivergence(e1) in merged.validate())
        // Quarantine transitive: g1 (below the divergent edge) derives zero holdings,
        // so it does NOT also report a spurious negative.
        assertEquals(0L, merged.holdings(g1, alice))
        assertTrue(merged.validate().none { it is LedgerConflict.PersistentNegativeHoldings })
    }

    /** Projection to an edge's components commutes with the join (design §10.8). */
    @Test
    fun projectionIsAMergeHomomorphism() {
        val a = EntitlementLedger.of(
            records = mapOf(e1 to setOf(AttachmentRecord(e1, root, g1, Weight.ONE, 0L))),
            issued = mapOf(e1 to GCounter.of(alice to 10L), e2 to GCounter.of(alice to 3L)),
            leafSpent = mapOf(e1 to GCounter.of(alice to 2L)),
            transfers = mapOf(PathKey.of(e1) to mapOf(alice to GCounter.of(bob to 4L))),
        )
        val b = EntitlementLedger.of(
            issued = mapOf(e1 to GCounter.of(bob to 5L)),
            returned = mapOf(e1 to GCounter.of(alice to 1L)),
            transfers = mapOf(PathKey.of(e1) to mapOf(alice to GCounter.of(bob to 7L))),
        )
        val mergedThenProjected = a.piece(b).projectEdge(e1)
        val projectedThenMerged = a.projectEdge(e1).piece(b.projectEdge(e1))
        assertEquals(projectedThenMerged, mergedThenProjected)
    }

    /** The conflict report is deterministically ordered regardless of merge order. */
    @Test
    fun validateReportIsCanonicallyOrdered() {
        val ledger = EntitlementLedger.of(
            records = mapOf(
                e1 to setOf(AttachmentRecord(e1, root, g1, Weight.ONE, 0L)),
                e2 to setOf(
                    AttachmentRecord(e2, g1, g2, Weight.ONE, 0L),
                    AttachmentRecord(e2, root, g2, Weight.ONE, 0L), // divergent
                ),
            ),
            issued = mapOf(e1 to GCounter.of(alice to 2L)),
            leafSpent = mapOf(e1 to GCounter.of(alice to 9L)), // PerEdgeSafety(e1)
        )
        val report = ledger.validate()
        assertEquals(report, report.sorted(), "report not in canonical order")
        // PerEdgeSafety (order 0) sorts before RecordDivergence (order 2).
        assertTrue(report.indexOf(LedgerConflict.PerEdgeSafety(e1)) < report.indexOf(LedgerConflict.RecordDivergence(e2)))
    }

    /**
     * A divergent CHILD edge must **deflate**, never inflate, the parent's holdings.
     * `childEdges` uses `any` (matching `activeChildren`/lineage), so when `e2` forks
     * it stays in `g1`'s delegated-out subtraction — the authority is frozen, not
     * re-manufactured. Were it dropped (an earlier `singleOrNull`), `g1`'s holdings
     * would jump by the delegated-out amount: a double-spend the sum check would miss.
     */
    @Test
    fun divergentChildEdgeDeflatesNeverInflatesParentHoldings() {
        val healthy = EntitlementLedger.of(
            records = mapOf(
                e1 to setOf(AttachmentRecord(e1, root, g1, Weight.ONE, 0L)),
                e2 to setOf(AttachmentRecord(e2, g1, g2, Weight.ONE, 0L)),
            ),
            minted = mapOf(MintId("m") to MintRecord(alice, 50L)),
            issued = mapOf(e1 to GCounter.of(alice to 50L), e2 to GCounter.of(alice to 20L)),
        )
        assertEquals(30L, healthy.holdings(g1, alice)) // 50 credited − 20 delegated to g2

        // e2 forks (a second, differently-weighted record under the same id).
        val divergent = healthy.piece(
            EntitlementLedger.of(records = mapOf(e2 to setOf(AttachmentRecord(e2, g1, g2, Weight.of(3, 1), 0L)))),
        )
        assertTrue(LedgerConflict.RecordDivergence(e2) in divergent.validate())
        assertEquals(30L, divergent.holdings(g1, alice), "divergent child must NOT inflate the parent")
        assertEquals(0L, divergent.holdings(g2, alice), "the divergent child's subtree quarantines")

        // Conservation never inflates: Σ holdings + Σ leafSpent ≤ minted (safe deflation).
        val total = listOf(root, g1, g2).sumOf { g -> divergent.holdings(g, alice) } + divergent.leafSpentTotal()
        assertTrue(total <= divergent.mintedTotal(), "divergence inflated conservation: $total > ${divergent.mintedTotal()}")
    }

    /**
     * Depth-1 donor backing (design fix 2, narrowed): a **single-hop** transfer-funded
     * spend, delivered to a replica that has genesis funding but is MISSING the donor's
     * delegate AND the transfer, must not false-fire. The spend patch carries the
     * transfer slot it consumed plus the donor's own `issued` backing at that edge.
     */
    @Test
    fun singleHopTransferFundedSpendDoesNotFalseFireOnLaggingReplica() {
        val leafTree = EntitlementLedger.of(
            records = mapOf(e1 to setOf(AttachmentRecord(e1, root, g1, Weight.ONE, 0L))),
        ) // root → g1, g1 is a leaf (no prefix edges → no roll-up)
        var full = leafTree.piece(EntitlementLedger.bootstrap(root, mapOf(alice to 100L), nonce = "g"))
        full = full.piece(full.delegate(alice, e1, 50L)!!) // alice funds g1
        full = full.piece(full.transfer(g1, alice, bob, 20L)!!) // single hop: alice → bob at g1
        val spendPatch = full.spend(bob, g1, 20L)!! // bob spends his transferred 20

        // A lagging replica: genesis mint + topology only — no delegate, no transfer.
        val lagging = leafTree.piece(EntitlementLedger.bootstrap(root, mapOf(alice to 100L), nonce = "g"))
        val received = lagging.piece(spendPatch)

        // The depth-1 backing brought issued(e1)[alice] and the transfer alongside the
        // leafSpent, so neither PerEdgeSafety nor a spurious negative fires.
        assertTrue(received.validate().isEmpty(), "single-hop witness failed: ${received.validate()}")
    }

    /**
     * A **multi-hop** transfer-funded charge is the accepted transient: on a
     * fully-delivered state `validate` is clean (it self-heals), so we pin only that —
     * a partially-delivered replica MAY show a transient false conflict, which we do
     * NOT assert against (the witness stops at depth 1, by design).
     */
    @Test
    fun multiHopTransferFundedChargeIsCleanOnceFullyDelivered() {
        val carol = ReplicaId("carol")
        val leafTree = EntitlementLedger.of(
            records = mapOf(e1 to setOf(AttachmentRecord(e1, root, g1, Weight.ONE, 0L))),
        )
        var full = leafTree.piece(EntitlementLedger.bootstrap(root, mapOf(alice to 100L), nonce = "g"))
        full = full.piece(full.delegate(alice, e1, 60L)!!)
        full = full.piece(full.transfer(g1, alice, bob, 40L)!!) // hop 1: alice → bob
        full = full.piece(full.transfer(g1, bob, carol, 25L)!!) // hop 2: bob → carol
        full = full.piece(full.spend(carol, g1, 25L)!!) // carol spends
        // Local safety held throughout (every mutator returned non-null on sufficient
        // holdings); the converged report is empty.
        assertTrue(full.validate().isEmpty(), "converged multi-hop state must be clean: ${full.validate()}")
        assertEquals(0L, full.holdings(g1, carol))
    }

    /**
     * The global supply backstop (#1642 item 1): more service charged than was ever minted is
     * a fault whatever the per-lineage derivation says, because it is read straight off the
     * totals. Here the edge sum itself is legal (`spent == issued`), so [LedgerConflict.PerEdgeSafety]
     * stays silent — only the global check names the manufactured supply.
     */
    @Test
    fun conservationViolationBackstopsServiceChargedBeyondMintedSupply() {
        val overcharged = EntitlementLedger.of(
            records = mapOf(e1 to setOf(AttachmentRecord(e1, root, g1, Weight.ONE, 0L))),
            minted = mapOf(MintId("m") to MintRecord(alice, 10L)), // only 10 ever minted
            issued = mapOf(e1 to GCounter.of(alice to 25L)),
            leafSpent = mapOf(e1 to GCounter.of(alice to 25L)), // yet 25 charged
        )
        val report = overcharged.validate()
        assertAll(
            { assertTrue(LedgerConflict.ConservationViolation(25L, 10L) in report, "backstop missed: $report") },
            { assertTrue(report.none { it is LedgerConflict.PerEdgeSafety }, "the edge sum is legal: $report") },
        )
    }

    /**
     * The backstop must not fire on honest partial delivery: a charge always travels with the
     * witness that funded it, and that witness carries the actor's own minted supply — so no
     * state can observe the debit without observing at least the supply backing it.
     */
    @Test
    fun globalBackstopDoesNotFalseFireOnAChargeDeliveredWithoutItsFunding() {
        var funded = twoDeepTree().piece(EntitlementLedger.bootstrap(root, mapOf(alice to 100L), nonce = "g"))
        funded = funded.piece(funded.delegate(alice, e1, 50L)!!)
        funded = funded.piece(funded.delegate(alice, e2, 20L)!!)
        val spendPatch = funded.spend(alice, g2, 20L)!!

        val received = EntitlementLedger.ZERO.piece(spendPatch) // no topology, no mint, no delegates
        assertAll(
            { assertEquals(100L, received.mintedTotal(), "the witness carried the actor's supply") },
            { assertEquals(20L, received.leafSpentTotal()) },
            { assertTrue(received.validate().none { it is LedgerConflict.ConservationViolation }) },
        )
    }

    /**
     * A topology cycle is quarantined *and* reported (#1642 item 5, §10.11): once per loop
     * member, never for a group merely hanging below the loop.
     */
    @Test
    fun lineageCycleIsReportedOncePerLoopMemberAndNotForDescendants() {
        val g3 = GroupId("g3")
        val up = AttachmentId("up") // g1 → g2
        val back = AttachmentId("back") // g2 → g1, closing the loop
        val below = AttachmentId("below") // g2 → g3, hanging under the loop
        val looped = EntitlementLedger.of(
            records = mapOf(
                up to setOf(AttachmentRecord(up, g1, g2, Weight.ONE, 0L)),
                back to setOf(AttachmentRecord(back, g2, g1, Weight.ONE, 0L)),
                below to setOf(AttachmentRecord(below, g2, g3, Weight.ONE, 0L)),
            ),
        )
        assertAll(
            {
                assertEquals(
                    listOf(LedgerConflict.LineageCycle(g1), LedgerConflict.LineageCycle(g2)),
                    looped.validate(),
                    "exactly the two loop members, in canonical order",
                )
            },
            { assertEquals(0L, looped.holdings(g1, alice), "a loop quarantines its members") },
            { assertEquals(0L, looped.holdings(g3, alice), "quarantine is transitive below the loop") },
        )
    }

    /**
     * An adversarial or corrupted **deserialized** state whose slots sum past [Long.MAX_VALUE]
     * (#1642 item 3): `GCounter.value` is a plain `sum()` and would wrap to a negative
     * aggregate that reads as *less* charged than nothing. §10.12 says arithmetic that would
     * exceed `Long` fails deterministically, so every aggregate read this module makes is
     * overflow-checked.
     */
    @Test
    fun anAggregateThatWouldWrapThrowsRatherThanReadingNegative() {
        val wrapping = EntitlementLedger.of(
            records = mapOf(e1 to setOf(AttachmentRecord(e1, root, g1, Weight.ONE, 0L))),
            issued = mapOf(e1 to GCounter.of(alice to Long.MAX_VALUE, bob to 1L)),
        )
        assertAll(
            { assertFailsWith<ArithmeticException> { wrapping.edge(e1) } },
            { assertFailsWith<ArithmeticException> { wrapping.validate() } },
        )
    }

    /**
     * Pins the **documented limitation** of the one-root-per-ledger invariant (#1642 item 2),
     * not desired behaviour. A [MintRecord] carries a holder and an amount but is not bound to
     * a root, and [EntitlementLedger.holdings] credits the full minted supply to *any* group
     * with no inbound edge — so merging two independently bootstrapped ledgers double-counts
     * every mint, silently. Binding the record to its root is a wire-format change and was
     * deliberately not taken here; when it lands, this test should flip.
     */
    @Test
    fun mergingTwoIndependentBootstrapsDoubleCountsMintAtEveryRoot() {
        val otherRoot = GroupId("otherRoot")
        val g4 = GroupId("g4")
        val e4 = AttachmentId("e4") // otherRoot → g4
        val left = EntitlementLedger
            .of(records = mapOf(e1 to setOf(AttachmentRecord(e1, root, g1, Weight.ONE, 0L))))
            .piece(EntitlementLedger.bootstrap(root, mapOf(alice to 10L), nonce = "left"))
        val right = EntitlementLedger
            .of(records = mapOf(e4 to setOf(AttachmentRecord(e4, otherRoot, g4, Weight.ONE, 0L))))
            .piece(EntitlementLedger.bootstrap(otherRoot, mapOf(alice to 10L), nonce = "right"))
        val merged = left.piece(right)
        assertAll(
            { assertEquals(20L, merged.mintedTotal()) },
            { assertEquals(20L, merged.holdings(root, alice), "each root is credited the WHOLE supply") },
            { assertEquals(20L, merged.holdings(otherRoot, alice), "…so Σ holdings is 40 against 20 minted") },
            { assertTrue(merged.validate().isEmpty(), "and the double-count is silent — the hazard this pins") },
        )
    }
}
