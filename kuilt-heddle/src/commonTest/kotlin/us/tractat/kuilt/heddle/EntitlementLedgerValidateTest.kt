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
    private val e3 = AttachmentId("e3") // g1   → g2, the fresh generation g2 is re-homed onto
    private val alice = ReplicaId("alice")
    private val bob = ReplicaId("bob")

    private fun twoDeepTree(): EntitlementLedger = EntitlementLedger.of(
        records = mapOf(
            e1 to setOf(AttachmentRecord(e1, root, g1, Weight.ONE)),
            e2 to setOf(AttachmentRecord(e2, g1, g2, Weight.ONE)),
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
            records = mapOf(e1 to setOf(AttachmentRecord(e1, root, g1, Weight.ONE))),
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
            records = mapOf(e1 to setOf(AttachmentRecord(e1, root, g1, Weight.ONE))),
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
            records = mapOf(e1 to setOf(AttachmentRecord(e1, root, g1, Weight.ONE))),
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
            records = mapOf(e1 to setOf(AttachmentRecord(e1, root, g1, Weight.ONE))),
            minted = mapOf(MintId("m") to MintRecord(alice, 10L)), // funded, so root is not itself negative
            issued = mapOf(e1 to GCounter.of(alice to 10L)),
        )
        val right = EntitlementLedger.of(
            records = mapOf(e1 to setOf(AttachmentRecord(e1, root, g1, Weight.of(3, 1)))),
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
            records = mapOf(e1 to setOf(AttachmentRecord(e1, root, g1, Weight.ONE))),
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
                e1 to setOf(AttachmentRecord(e1, root, g1, Weight.ONE)),
                e2 to setOf(
                    AttachmentRecord(e2, g1, g2, Weight.ONE),
                    AttachmentRecord(e2, root, g2, Weight.ONE), // divergent
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
                e1 to setOf(AttachmentRecord(e1, root, g1, Weight.ONE)),
                e2 to setOf(AttachmentRecord(e2, g1, g2, Weight.ONE)),
            ),
            minted = mapOf(MintId("m") to MintRecord(alice, 50L)),
            issued = mapOf(e1 to GCounter.of(alice to 50L), e2 to GCounter.of(alice to 20L)),
        )
        assertEquals(30L, healthy.holdings(g1, alice)) // 50 credited − 20 delegated to g2

        // e2 forks (a second, differently-weighted record under the same id).
        val divergent = healthy.piece(
            EntitlementLedger.of(records = mapOf(e2 to setOf(AttachmentRecord(e2, g1, g2, Weight.of(3, 1))))),
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
            records = mapOf(e1 to setOf(AttachmentRecord(e1, root, g1, Weight.ONE))),
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
            records = mapOf(e1 to setOf(AttachmentRecord(e1, root, g1, Weight.ONE))),
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
            records = mapOf(e1 to setOf(AttachmentRecord(e1, root, g1, Weight.ONE))),
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
                up to setOf(AttachmentRecord(up, g1, g2, Weight.ONE)),
                back to setOf(AttachmentRecord(back, g2, g1, Weight.ONE)),
                below to setOf(AttachmentRecord(below, g2, g3, Weight.ONE)),
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
            records = mapOf(e1 to setOf(AttachmentRecord(e1, root, g1, Weight.ONE))),
            issued = mapOf(e1 to GCounter.of(alice to Long.MAX_VALUE, bob to 1L)),
        )
        assertAll(
            { assertFailsWith<ArithmeticException> { wrapping.edge(e1) } },
            { assertFailsWith<ArithmeticException> { wrapping.validate() } },
        )
    }

    /**
     * A [MintRecord] is bound to the root it was minted at (#1751), so [EntitlementLedger.holdings]
     * credits a rootless group **only** the supply minted at *that* group. Merging two
     * independently bootstrapped ledgers therefore leaves each root holding its own mint and
     * Σ holdings equal to `mintedTotal` **once** — the double count is unrepresentable, not
     * merely undetected.
     *
     * Superseded `mergingTwoIndependentBootstrapsDoubleCountsMintAtEveryRoot`, which pinned the
     * pre-#1751 double count as a documented limitation (#1642 item 2).
     */
    @Test
    fun mergingTwoIndependentBootstrapsCreditsEachMintOnlyAtItsOwnRoot() {
        val merged = twoBootstrapsMerged()
        val sumHoldings = merged.allGroups().sumOf { g -> merged.holdings(g, alice) }
        assertAll(
            { assertEquals(20L, merged.mintedTotal()) },
            { assertEquals(10L, merged.holdings(root, alice), "root holds only what was minted at root") },
            { assertEquals(10L, merged.holdings(otherRoot, alice), "…and otherRoot only its own") },
            { assertEquals(merged.mintedTotal(), sumHoldings, "Σ holdings counts the supply once, not twice") },
        )
    }

    private val otherRoot = GroupId("otherRoot")

    /** Two independently bootstrapped one-edge trees, merged — the #1751 hazard state. */
    private fun twoBootstrapsMerged(): EntitlementLedger {
        val g4 = GroupId("g4")
        val e4 = AttachmentId("e4") // otherRoot → g4
        val left = EntitlementLedger
            .of(records = mapOf(e1 to setOf(AttachmentRecord(e1, root, g1, Weight.ONE))))
            .piece(EntitlementLedger.bootstrap(root, mapOf(alice to 10L), nonce = "left"))
        val right = EntitlementLedger
            .of(records = mapOf(e4 to setOf(AttachmentRecord(e4, otherRoot, g4, Weight.ONE))))
            .piece(EntitlementLedger.bootstrap(otherRoot, mapOf(alice to 10L), nonce = "right"))
        return left.piece(right)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // OrphanedTransferPath (#2366) — transfer rows the topology moved out from under
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * The #2366 shape at rest: alice handed bob 40 at `g2` while `e2` was g2's live inbound, then a
     * generation move re-homed the counter families onto `e3` and left `transfers[PathKey.of(e2)]`
     * exactly where it was. Fully drained, so nothing else in `validate` has anything to say.
     *
     * Hand-assembled rather than replayed so each clause of the report gets a control arm differing
     * in **one** component. The same state derived the production way — `relocationPatch` on a real
     * strand — is pinned by `EntitlementLedgerReconcileTest`.
     *
     * @param movedRows what the move carried across to `e3`. Empty is the defect; the whole row is
     *   what a fix that moves the rows with the generation produces.
     * @param successor `e3`'s lifecycle — `ACTIVE` is the completed move, `PREPARED` is the window
     *   between generations where `g2` has no live inbound at all.
     */
    private fun reHomedAwayFromTheRows(
        movedRows: Map<ReplicaId, GCounter> = emptyMap(),
        successor: Lifecycle = Lifecycle.ACTIVE,
    ): EntitlementLedger = EntitlementLedger.of(
        records = mapOf(
            e1 to setOf(AttachmentRecord(e1, root, g1, Weight.ONE)),
            e2 to setOf(AttachmentRecord(e2, g1, g2, Weight.ONE)),
            e3 to setOf(AttachmentRecord(e3, g1, g2, Weight.ONE)),
        ),
        minted = mapOf(MintId("m") to MintRecord(alice, 100L)),
        issued = mapOf(e1 to GCounter.of(alice to 100L), e2 to GCounter.of(alice to 60L)),
        returned = mapOf(e2 to GCounter.of(alice to 60L)), // the move drained the strand…
        issuedRelocIn = mapOf(e3 to GCounter.of(alice to 60L)), // …and credited the successor
        transfers = mapOf(PathKey.of(e2) to mapOf(alice to GCounter.of(bob to 40L))) +
            if (movedRows.isEmpty()) emptyMap() else mapOf(PathKey.of(e3) to movedRows),
        lifecycle = mapOf(e1 to Lifecycle.ACTIVE, e2 to Lifecycle.RETIRED, e3 to successor),
    )

    /**
     * The defect, and the only thing that names it. The donor recovers what it gave away, the
     * recipient's credit is gone, conservation is exactly intact and no pocket is negative — so
     * every pre-existing check is structurally silent and [LedgerConflict.OrphanedTransferPath] is
     * the whole report.
     */
    @Test
    fun rowsLeftBehindByAGenerationMoveAreReported() {
        val orphaned = reHomedAwayFromTheRows()
        assertAll(
            {
                assertEquals(
                    listOf(LedgerConflict.OrphanedTransferPath(PathKey.of(e2))),
                    orphaned.validate(),
                    "the abandoned rows must be named, and be the ONLY thing named",
                )
            },
            // ── the corruption the report is about, so a fixture that stopped corrupting reds here
            // rather than passing quietly on a report that fires for some other reason.
            { assertEquals(60L, orphaned.holdings(g2, alice), "the donor recovered the 40 it gave away") },
            { assertEquals(0L, orphaned.holdings(g2, bob), "…and the recipient's 40 is gone") },
            {
                assertEquals(
                    orphaned.mintedTotal(),
                    listOf(root, g1, g2).sumOf { orphaned.holdings(it, alice) + orphaned.holdings(it, bob) } +
                        orphaned.leafSpentTotal(),
                    "conservation is structurally blind: Σ_r transferNet(k, r) = 0, so only the owner changed",
                )
            },
            {
                assertTrue(
                    orphaned.validate().none { it is LedgerConflict.PersistentNegativeHoldings },
                    "the recipient lands on 0, not below — the loud half of the tangle never fires",
                )
            },
        )
    }

    /**
     * Clause 1's control arm: the identical hand-off while `e2` is still `g2`'s live inbound. The
     * key is read, the numbers are the truth, and nothing is reported — so the report is about the
     * key having gone dead, not about a transfer row existing.
     */
    @Test
    fun rowsAtAGroupsLiveKeyAreNotOrphaned() {
        val live = EntitlementLedger.of(
            records = mapOf(
                e1 to setOf(AttachmentRecord(e1, root, g1, Weight.ONE)),
                e2 to setOf(AttachmentRecord(e2, g1, g2, Weight.ONE)),
            ),
            minted = mapOf(MintId("m") to MintRecord(alice, 100L)),
            issued = mapOf(e1 to GCounter.of(alice to 100L), e2 to GCounter.of(alice to 60L)),
            transfers = mapOf(PathKey.of(e2) to mapOf(alice to GCounter.of(bob to 40L))),
        )
        assertAll(
            { assertEquals(20L, live.holdings(g2, alice), "rig: the hand-off is still being read") },
            { assertEquals(40L, live.holdings(g2, bob), "rig: …by both parties") },
            { assertTrue(live.validate().isEmpty(), "a live key is never an orphan: ${live.validate()}") },
        )
    }

    /**
     * Clause 2's control arm, and the **acceptance signal for the eventual fix** (#2366 option 1):
     * carry the rows across with the generation and the report clears itself. Nothing else in this
     * state changes — the same dead key still carries the same rows — so what clears it is exactly
     * that the hand-off is readable again, which is also visible in the restored holdings.
     */
    @Test
    fun rowsCarriedAcrossToTheLiveKeyClearTheReport() {
        val fixed = reHomedAwayFromTheRows(movedRows = mapOf(alice to GCounter.of(bob to 40L)))
        assertAll(
            { assertEquals(20L, fixed.holdings(g2, alice), "the donor keeps only what it kept") },
            { assertEquals(40L, fixed.holdings(g2, bob), "…and the recipient has its credit back") },
            {
                assertTrue(
                    fixed.transfersAt(PathKey.of(e2)).isNotEmpty(),
                    "rig: the dead key still carries the rows — transfers are grow-only, a fix cannot erase them",
                )
            },
            { assertTrue(fixed.validate().isEmpty(), "a carried-across row is not orphaned: ${fixed.validate()}") },
        )
    }

    /**
     * The **strengthened** acceptance arm, and the fix this oracle exists to reject.
     *
     * A move onto a live key that **already carries its own hand-off** between the same pair must
     * **sum**: 40 abandoned on `e2` plus 15 already standing at `e3` is 55, so the truth after a
     * correct fix is `alice 5 / bob 55`. But `transfers` rows join by per-recipient **max**
     * (`mergeRows`) while the counter families a move re-homes deliberately accumulate — so the
     * natural one-liner `transfers[t] = transfers[t].mergeRows(transfers[s])` yields
     * `max(15, 40) = 40`, silently losing 15.
     *
     * Both arms are asserted because **clause 2 is green either way** — `40 <= 40` reads as covered —
     * so `validate()` alone cannot tell a correct carry from a lossy one. What rejects the lossy fix
     * is the holdings assertion, and that is the whole reason this test states the arithmetic rather
     * than just checking the report is empty.
     */
    @Test
    fun aCarryOntoALiveKeyThatAlreadyHasItsOwnMustSumRatherThanMaxJoin() {
        val summed = reHomedAwayFromTheRows(movedRows = mapOf(alice to GCounter.of(bob to 55L)))
        val maxJoined = reHomedAwayFromTheRows(movedRows = mapOf(alice to GCounter.of(bob to 40L)))
        assertAll(
            // ── the correct carry: 15 already there + 40 abandoned = 55.
            { assertEquals(5L, summed.holdings(g2, alice), "a summing carry leaves the donor 60 − 55") },
            { assertEquals(55L, summed.holdings(g2, bob), "…and the recipient its own 15 plus the moved 40") },
            { assertTrue(summed.validate().isEmpty(), "a correct carry is not orphaned: ${summed.validate()}") },
            // ── the lossy carry: max(15, 40) = 40. Clause 2 cannot see it; the arithmetic can.
            {
                assertTrue(
                    maxJoined.validate().isEmpty(),
                    "clause 2 reads a max-join as covered — this is what makes it insufficient ALONE: " +
                        "${maxJoined.validate()}",
                )
            },
            { assertEquals(20L, maxJoined.holdings(g2, alice), "…yet the donor keeps 15 that is not hers") },
            { assertEquals(40L, maxJoined.holdings(g2, bob), "…and the recipient is 15 short of the truth (55)") },
        )
    }

    /**
     * Clause 2 again, one row short: a move that carried **part** of the hand-off across is still
     * an abandonment of the rest. Without the per-`(donor, recipient)` comparison — comparing nets,
     * say — a partial carry would net out on some other row and read as complete.
     */
    @Test
    fun aPartiallyCarriedRowIsStillReported() {
        val partial = reHomedAwayFromTheRows(movedRows = mapOf(alice to GCounter.of(bob to 25L)))
        assertAll(
            { assertEquals(35L, partial.holdings(g2, alice), "rig: 15 of the hand-off is still missing (truth is 20)") },
            { assertEquals(25L, partial.holdings(g2, bob), "rig: …so the recipient is 15 short (truth is 40)") },
            {
                assertTrue(
                    LedgerConflict.OrphanedTransferPath(PathKey.of(e2)) in partial.validate(),
                    "a partial carry is still an abandonment: ${partial.validate()}",
                )
            },
        )
    }

    /**
     * The **masking hole**, pinned as a documented limitation rather than as desired behaviour.
     *
     * Clause 2 compares magnitudes on a slot that `transfer` *accumulates* onto, so it cannot tell
     * "the move carried these rows across" from "the same pair transferred at least as much again,
     * independently, at the live key" — the two are byte-identical states. Here the corrupt ledger
     * is left to run: alice, apparently holding 60, hands bob 40 through the ordinary mutator. The
     * live key's cumulative reaches 40, clause 2 reads the dead key's 40 as covered, and the report
     * that had been firing **goes quiet — permanently**, because rows are grow-only and the live
     * cumulative never falls back.
     *
     * The numbers even come out looking right, which is the trap: the true history is alice `−20`
     * (she has now given away 40 she never had, on top of the 40 already abandoned) and bob `80`.
     * The abandonment has been laundered into a state that reads as legitimate.
     *
     * Closing this needs provenance the lattice does not carry. If the eventual #2366 fix re-keys
     * `transfers` by group, the clause and its hole disappear together.
     */
    @Test
    fun aLaterTransferBetweenTheSamePairMasksTheReport() {
        val orphaned = reHomedAwayFromTheRows()
        val masked = orphaned.piece(orphaned.transfer(g2, alice, bob, 40L)!!)
        assertAll(
            {
                assertEquals(
                    listOf(LedgerConflict.OrphanedTransferPath(PathKey.of(e2))),
                    orphaned.validate(),
                    "rig: the report really was firing before the masking transfer",
                )
            },
            {
                assertEquals(
                    40L,
                    masked.transfersAt(PathKey.of(e3))[alice]?.count(bob),
                    "rig: the ordinary mutator brought the live key's cumulative up to the abandoned 40",
                )
            },
            { assertTrue(masked.validate().isEmpty(), "…and the report goes quiet: ${masked.validate()}") },
            { assertEquals(20L, masked.holdings(g2, alice), "the numbers even look right — truth is −20") },
            { assertEquals(40L, masked.holdings(g2, bob), "…and 40 rather than the true 80") },
        )
    }

    /**
     * Clause 3's control arm — and the reason the report is not simply "a dead key with rows on it".
     *
     * `e2` here died the honest way: bob spent the 40 he was handed, alice released her remaining 20,
     * the edge drained to `outstanding == 0` and retired, and `g2` was later re-homed onto `e3`. The
     * rows are still on the dead key and always will be (transfers are grow-only), and the key is
     * genuinely no longer read — but its books closed at zero for **both** parties, so there is
     * nothing to report. A rule keyed only on "non-live key with a non-zero transfer net" reports
     * this state, permanently, on every healthy ledger that ever combined a hand-off with a reshape.
     */
    @Test
    fun aStrandWhoseBooksClosedBeforeItDiedIsNotReported() {
        val drained = EntitlementLedger.of(
            records = mapOf(
                e1 to setOf(AttachmentRecord(e1, root, g1, Weight.ONE)),
                e2 to setOf(AttachmentRecord(e2, g1, g2, Weight.ONE)),
                e3 to setOf(AttachmentRecord(e3, g1, g2, Weight.ONE)),
            ),
            minted = mapOf(MintId("m") to MintRecord(alice, 100L)),
            issued = mapOf(e1 to GCounter.of(alice to 100L), e2 to GCounter.of(alice to 60L)),
            returned = mapOf(e2 to GCounter.of(alice to 20L)), // alice released what she still held
            leafSpent = mapOf(e2 to GCounter.of(bob to 40L)), // bob spent what he was handed
            transfers = mapOf(PathKey.of(e2) to mapOf(alice to GCounter.of(bob to 40L))),
            lifecycle = mapOf(e1 to Lifecycle.ACTIVE, e2 to Lifecycle.RETIRED, e3 to Lifecycle.ACTIVE),
        )
        assertAll(
            // ── the rig: every ingredient of the report is present except the harm.
            {
                assertTrue(
                    drained.transfersAt(PathKey.of(e2)).isNotEmpty(),
                    "rig: the dead key really does still carry the hand-off",
                )
            },
            {
                assertEquals(
                    listOf(e1, e3),
                    drained.lineageOf(g2),
                    "rig: …and g2's live lineage really has moved off it, so the key really is unread",
                )
            },
            { assertEquals(0L, drained.edge(e2)!!.outstanding, "rig: the strand drained before it died") },
            { assertTrue(drained.validate().isEmpty(), "an honestly closed strand is not an orphan: ${drained.validate()}") },
        )
    }

    /**
     * The standing silent exception (§10.11), shared with [EntitlementLedger.holdings]: while `g2`
     * has **no** live inbound at all — the window after the old generation retires and before the
     * new one activates — the whole lineage is quarantined and deliberately unreported. Flagging it
     * would fire on the normal middle of an honest reshape.
     */
    @Test
    fun theWindowBetweenGenerationsStaysSilent() {
        val midReshape = reHomedAwayFromTheRows(successor = Lifecycle.PREPARED)
        assertAll(
            { assertEquals(null, midReshape.lineageOf(g2), "rig: g2 is quarantined, with no live inbound") },
            { assertEquals(0L, midReshape.holdings(g2, alice), "rig: …so holdings reads nothing at all there") },
            { assertTrue(midReshape.validate().isEmpty(), "the reshape window must stay silent: ${midReshape.validate()}") },
        )
    }

    /**
     * The root path has no final edge, so no reshape can move it out from under its rows — it is
     * live on every state, and a hand-off made at the root is never orphaned however the tree below
     * is rearranged.
     */
    @Test
    fun aHandOffAtTheRootPathIsNeverOrphaned() {
        val atRoot = EntitlementLedger.of(
            records = mapOf(e1 to setOf(AttachmentRecord(e1, root, g1, Weight.ONE))),
            minted = mapOf(MintId("m") to MintRecord(alice, 100L)),
            transfers = mapOf(PathKey.ROOT to mapOf(alice to GCounter.of(bob to 30L))),
        )
        assertAll(
            { assertEquals(70L, atRoot.holdings(root, alice), "rig: the root hand-off is being read") },
            { assertEquals(30L, atRoot.holdings(root, bob), "rig: …by both parties") },
            { assertTrue(atRoot.validate().isEmpty(), "the root path is live on every state: ${atRoot.validate()}") },
        )
    }

    /**
     * Rows at a key naming no generation this ledger knows are unreadable by construction — there is
     * no edge whose child could ever read them — so they are reported without any liveness question
     * to ask. This is the shape a partially-delivered or hand-built state reaches.
     */
    @Test
    fun rowsAtAKeyNamingNoKnownGenerationAreReported() {
        val dangling = EntitlementLedger.of(
            records = mapOf(e1 to setOf(AttachmentRecord(e1, root, g1, Weight.ONE))),
            minted = mapOf(MintId("m") to MintRecord(alice, 100L)),
            transfers = mapOf(PathKey.of(AttachmentId("nowhere")) to mapOf(alice to GCounter.of(bob to 30L))),
        )
        assertEquals(
            listOf(LedgerConflict.OrphanedTransferPath(PathKey.of(AttachmentId("nowhere")))),
            dangling.validate(),
            "rows keyed on an unknown generation can never be read",
        )
    }

    /**
     * The dangling arm fires on **two** clauses, and this is the second one.
     *
     * A key naming no known generation has no liveness question left to ask and no live key to
     * compare against, so clauses 1 and 2 are vacuous there — but the derivable half of clause 3 is
     * not, and dropping it would make the two arms disagree about the identical fact. These rows
     * cancel (`alice → bob 30`, `bob → alice 30`), costing nobody anything, exactly as in
     * [rowsThatNetToNothingAreNotOrphanedByAStrandedCounterResidue]; a dangling key must not be
     * reported for what a known-but-dead key is deliberately forgiven.
     */
    @Test
    fun aDanglingKeyWhoseRowsCancelIsNotReported() {
        val cancelling = EntitlementLedger.of(
            records = mapOf(e1 to setOf(AttachmentRecord(e1, root, g1, Weight.ONE))),
            minted = mapOf(MintId("m") to MintRecord(alice, 100L)),
            transfers = mapOf(
                PathKey.of(AttachmentId("nowhere")) to mapOf(
                    alice to GCounter.of(bob to 30L),
                    bob to GCounter.of(alice to 30L),
                ),
            ),
        )
        assertAll(
            {
                assertTrue(
                    cancelling.transfersAt(PathKey.of(AttachmentId("nowhere"))).isNotEmpty(),
                    "rig: the dangling key really does carry rows",
                )
            },
            { assertTrue(cancelling.validate().isEmpty(), "cancelling rows lost nobody anything: ${cancelling.validate()}") },
        )
    }

    /**
     * The enumeration hazard, pinned (#2366 trap 2): the parties to a hand-off are read off the
     * **rows**, never off the edge's counter families. A recipient who merely *holds* transferred
     * credit has authored no slot on the strand at all — it is absent from `replicasOnEdge` and from
     * every `SlotFinals` the fence collects — so an enumeration built from those would drop exactly
     * the party the report exists for.
     *
     * Rigged so bob is the **only** qualifying party: alice released 20, leaving her own books on
     * the dead key balanced (`netInflow 40 − transferred 40 = 0`) while bob's 40 is stranded. The
     * raced advisory retire that produced this state (#1665) is separately reported, and asserting
     * both keeps the two voices distinct.
     */
    @Test
    fun theStrandedPartyNeedNotHaveACounterSlotOnTheStrand() {
        val recipientOnly = EntitlementLedger.of(
            records = mapOf(
                e1 to setOf(AttachmentRecord(e1, root, g1, Weight.ONE)),
                e2 to setOf(AttachmentRecord(e2, g1, g2, Weight.ONE)),
                e3 to setOf(AttachmentRecord(e3, g1, g2, Weight.ONE)),
            ),
            minted = mapOf(MintId("m") to MintRecord(alice, 100L)),
            issued = mapOf(e1 to GCounter.of(alice to 100L), e2 to GCounter.of(alice to 60L)),
            returned = mapOf(e2 to GCounter.of(alice to 20L)),
            transfers = mapOf(PathKey.of(e2) to mapOf(alice to GCounter.of(bob to 40L))),
            lifecycle = mapOf(e1 to Lifecycle.ACTIVE, e2 to Lifecycle.RETIRED, e3 to Lifecycle.ACTIVE),
        )
        assertAll(
            {
                assertEquals(
                    setOf(alice),
                    recipientOnly.baseFinalsOn(e2).keys,
                    "rig: bob authored no counter slot on the strand — a slot-walking enumeration cannot see him",
                )
            },
            {
                assertEquals(
                    0L,
                    CounterFamily.entries.sumOf { recipientOnly.storedSlot(it, e2, bob) },
                    "rig: …in ANY of the nine families, base or relocation",
                )
            },
            {
                assertTrue(
                    LedgerConflict.OrphanedTransferPath(PathKey.of(e2)) in recipientOnly.validate(),
                    "bob's stranded 40 must still be named: ${recipientOnly.validate()}",
                )
            },
            {
                assertTrue(
                    LedgerConflict.ClosureViolation(e2) in recipientOnly.validate(),
                    "…alongside the raced retire that stranded it, as a separate voice",
                )
            },
        )
    }

    /**
     * The report is about *transfer* credit going unread, so a party who nets to nothing at the dead
     * key has lost nothing by it dying — however much the dead generation still strands in its
     * **counters**. Here the hand-offs cancel (`alice → bob 40`, `bob → alice 40`) while 60 really is
     * stranded on the retired strand, and the only voice is the one that owns that: the raced retire.
     *
     * Without the "this party's transfer net is non-zero" conjunct the counter residue alone would
     * raise an orphan report, mis-attributing a lifecycle fault to the hand-offs.
     */
    @Test
    fun rowsThatNetToNothingAreNotOrphanedByAStrandedCounterResidue() {
        val cancelling = EntitlementLedger.of(
            records = mapOf(
                e1 to setOf(AttachmentRecord(e1, root, g1, Weight.ONE)),
                e2 to setOf(AttachmentRecord(e2, g1, g2, Weight.ONE)),
                e3 to setOf(AttachmentRecord(e3, g1, g2, Weight.ONE)),
            ),
            minted = mapOf(MintId("m") to MintRecord(alice, 100L)),
            issued = mapOf(e1 to GCounter.of(alice to 100L), e2 to GCounter.of(alice to 60L)),
            transfers = mapOf(
                PathKey.of(e2) to mapOf(
                    alice to GCounter.of(bob to 40L),
                    bob to GCounter.of(alice to 40L),
                ),
            ),
            lifecycle = mapOf(e1 to Lifecycle.ACTIVE, e2 to Lifecycle.RETIRED, e3 to Lifecycle.ACTIVE),
        )
        assertAll(
            { assertEquals(60L, cancelling.edge(e2)!!.outstanding, "rig: 60 really is stranded on the dead strand") },
            {
                assertEquals(
                    listOf(LedgerConflict.ClosureViolation(e2)),
                    cancelling.validate(),
                    "the raced retire is the only fault here — the cancelling rows lost nobody anything",
                )
            },
        )
    }

    /**
     * A key whose generation has a **divergent** record is deliberately left to
     * [LedgerConflict.RecordDivergence]: the topology itself is in dispute, so which group would
     * have read these rows is not a question this state can answer, and naming an orphan on top of
     * it would be speculation dressed as a second finding.
     *
     * Pinned because the deferral is otherwise invisible — the rows here really are unreachable, and
     * a rule that resolved the fork by picking a record would report them.
     */
    @Test
    fun aKeyWhoseGenerationHasADivergentRecordDefersToTheDivergenceReport() {
        val forked = EntitlementLedger.of(
            records = mapOf(
                e1 to setOf(AttachmentRecord(e1, root, g1, Weight.ONE)),
                e2 to setOf(
                    AttachmentRecord(e2, g1, g2, Weight.ONE),
                    AttachmentRecord(e2, g1, g2, Weight.of(3, 1)),
                ),
                e3 to setOf(AttachmentRecord(e3, g1, g2, Weight.ONE)),
            ),
            minted = mapOf(MintId("m") to MintRecord(alice, 100L)),
            issued = mapOf(e1 to GCounter.of(alice to 100L), e2 to GCounter.of(alice to 60L)),
            transfers = mapOf(PathKey.of(e2) to mapOf(alice to GCounter.of(bob to 40L))),
            lifecycle = mapOf(e1 to Lifecycle.ACTIVE, e2 to Lifecycle.RETIRED, e3 to Lifecycle.ACTIVE),
        )
        assertAll(
            { assertEquals(listOf(e1, e3), forked.lineageOf(g2), "rig: the fork is off the live lineage, not on it") },
            {
                assertTrue(
                    LedgerConflict.RecordDivergence(e2) in forked.validate(),
                    "rig: the divergence itself is reported",
                )
            },
            {
                assertTrue(
                    forked.validate().none { it is LedgerConflict.OrphanedTransferPath },
                    "a disputed topology gets one voice, not two: ${forked.validate()}",
                )
            },
        )
    }

    /**
     * The report's canonical order (the [Comparable] contract every peer folds): the new kind takes
     * the last rank, and two orphans sort by path. Asserted directly on the sealed subtypes so the
     * `compareTo` arm is pinned without needing a state that reaches every kind at once.
     */
    @Test
    fun orphanedTransferPathTakesTheLastRankAndSortsByPath() {
        val first = LedgerConflict.OrphanedTransferPath(PathKey.of(e1))
        val second = LedgerConflict.OrphanedTransferPath(PathKey.of(e2))
        val other = LedgerConflict.NegativeEffectiveSpend(e1)
        assertEquals(listOf(other, first, second), listOf(second, first, other).sorted())
    }
}
