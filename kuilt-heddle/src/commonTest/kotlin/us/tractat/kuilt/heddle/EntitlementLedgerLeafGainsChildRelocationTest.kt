package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * #2389 — the one relocation shape neither conservation ladder can reach: a fenced edge carrying a
 * non-zero `leafSpent` **and** a non-zero `rollupSpent` at the same time.
 *
 * ## Why it was unreachable
 *
 * An edge carries `leafSpent` exactly when it is some path's **final** edge, and a final edge is
 * never a strict prefix — so under a *static* topology the two halves of the spend split land on
 * different edges by construction. `EntitlementLedgerConservationTest`'s prefix ladder even asserts
 * the complement (`LEAF_RELOC_IN + LEAF_RELOC_OUT == 0` on a prefix edge), and
 * `EntitlementLedgerReconcileTest`'s cover pins hardcode one of the two to `0` in every fixture.
 * So `sp = checkedAdd(lsp, rsp)` had never been evaluated with both terms live, and neither had the
 * pair of `*_RELOC_OUT` writes on one `(edge, replica)` slot nor the pair of `*_RELOC_IN` writes on
 * one live slot.
 *
 * ## The topology that reaches it is the one the design names
 *
 * `EntitlementLedger.holdings`' KDoc says the split "keeps conservation topology-independent when a
 * former leaf later gains a child". That reshape is the shape:
 *
 * 1. `h2` is a leaf, so a spend there charges `leafSpent(f1)` and nothing else;
 * 2. `f2` (`h2 → h3`) is prepared **and activated**, so `h2` stops being a leaf;
 * 3. a spend at `h3` now charges `leafSpent(f2)` *and* `rollupSpent(f1)`.
 *
 * `f1` then carries both, and re-homing it onto `f1b` moves a non-zero `lsp` and a non-zero `rsp`
 * in one patch. The ordering matters and is not decoration: `spend` `require`s `isLeaf(group)` and
 * `childEdges` ignores lifecycle entirely, so `isLeaf(h2)` flips false the instant `f2`'s *record*
 * lands — preparing the child first turns step 1 into an `IllegalArgumentException`. Activating
 * `f2` is not optional either: `PREPARED` is not a live edge, so `spend(p, h3, ·)` would find no
 * lineage and return `null`.
 *
 * `EntitlementLedgerReconcileTest.bothHalvesOfTheSpendSplitCountTowardTheCoverPreconditionTogether`
 * is the cheap half of this — it pins that the state is *representable* by feeding
 * `relocationPatch` a hand-built `SlotFinals`. This file is the half that matters: the state is
 * **reachable** through the real mutators, which is the difference between pinning arithmetic and
 * pinning a system.
 */
class EntitlementLedgerLeafGainsChildRelocationTest {

    private val root = GroupId("root")
    private val h2 = GroupId("h2") // the former leaf that later gains a child
    private val h3 = GroupId("h3") // the child it gains
    private val p = ReplicaId("p")

    private val f1 = AttachmentId("f1") // root → h2, stranded by the raced retire; carries BOTH charges
    private val f2 = AttachmentId("f2") // h2   → h3, the child edge that ends h2's leaf-hood
    private val f1b = AttachmentId("f1b") // root → h2, the legal reparent generation

    private val minted = 100L
    private val downF1 = 20L
    private val downF2 = 8L
    private val leafCharge = 5L // charged at h2 while it is still a leaf → leafSpent(f1)
    private val rollupCharge = 3L // charged at h3 after the reshape      → rollupSpent(f1)

    private fun rec(id: AttachmentId, parent: GroupId, child: GroupId) =
        AttachmentRecord(id, parent, child, Weight.ONE)

    private fun EntitlementLedger.applying(patch: Patch<EntitlementLedger>?): EntitlementLedger =
        piece(assertNotNull(patch, "the mutator must be feasible here"))

    /**
     * The reshape, end to end. [leaf] and [rollup] are the two charges; passing `0` for either is
     * how the control arm below degenerates this fixture back to a one-term strand — a `0` spend is
     * the contract's no-op cancel, so the *sequence* is unchanged and only the charge disappears.
     */
    private fun formerLeafGainsAChild(leaf: Long = leafCharge, rollup: Long = rollupCharge): EntitlementLedger {
        var l = EntitlementLedger.ZERO.piece(EntitlementLedger.bootstrap(root, mapOf(p to minted), nonce = "genesis"))
        l = l.applying(l.prepare(rec(f1, root, h2)))
        l = l.applying(l.activate(f1))
        l = l.applying(l.delegate(p, f1, downF1))
        // (1) h2 is still a leaf — this charge lands on `leafSpent(f1)` with no prefix to roll up.
        l = l.applying(l.spend(p, h2, leaf))
        // (2) h2 gains a child. Prepared AND activated: a merely-prepared edge is not live, so a
        //     spend at h3 would find no lineage at all.
        l = l.applying(l.prepare(rec(f2, h2, h3)))
        l = l.applying(l.activate(f2))
        l = l.applying(l.delegate(p, f2, downF2))
        // (3) f1 is now a strict prefix of [f1, f2], so this charge rolls up onto it.
        l = l.applying(l.spend(p, h3, rollup))
        // The raced advisory retire, then the legal reparent — the D1 strand, exactly as the other
        // relocation suites build it.
        l = l.applying(l.close(f1))
        l = l.piece(EntitlementLedger.of(lifecycle = mapOf(f1 to Lifecycle.RETIRED)))
        l = l.applying(l.prepare(rec(f1b, root, h2)))
        l = l.applying(l.activate(f1b))
        return l
    }

    /** `Σ_group holdings(group, p) + Σ effLeafSpent` — the conservation identity's left-hand side. */
    private fun conservation(l: EntitlementLedger): Long =
        listOf(root, h2, h3).sumOf { l.holdings(it, p) } + l.leafSpentTotal()

    // ── the rig: prove the fenced edge really carries both halves before asserting anything moves ──

    @Test
    fun theFencedEdgeCarriesBothHalvesOfTheSpendSplitAtOnce() {
        val l = formerLeafGainsAChild()
        assertAll(
            // Counted per family, never inferred from `edge(f1).spent` — that read is the SUM, so
            // it cannot tell 5 leaf + 3 roll-up from 8 leaf + 0 roll-up, which is the very fixture
            // vacuity this whole file exists to rule out.
            { assertEquals(leafCharge, l.storedSlot(CounterFamily.LEAF_SPENT, f1, p), "f1 carries the leaf charge") },
            { assertEquals(rollupCharge, l.storedSlot(CounterFamily.ROLLUP_SPENT, f1, p), "f1 carries the roll-up charge too") },
            { assertTrue(leafCharge > 0L && rollupCharge > 0L, "both halves must be non-zero or the move proves nothing") },
            // The reshape actually happened: h2 is no longer a leaf, and f2 is the child that ended it.
            { assertTrue(l.isLeaf(h3), "h3 is the new leaf") },
            { assertTrue(!l.isLeaf(h2), "h2 stopped being a leaf when f2's record landed") },
            { assertEquals(rollupCharge, l.storedSlot(CounterFamily.LEAF_SPENT, f2, p), "the post-reshape charge is f2's leaf spend") },
            // …and the strand is a real strand: conservation is broken by exactly the stranded
            // holdings, so "restored" below is a claim about the move rather than about nothing.
            // The deficit is `downF1 − leafCharge` and NOT `downF1 − leafCharge − rollupCharge`,
            // which is the split stated as arithmetic: the leaf half is a term of the conservation
            // identity and survives the strand, the roll-up half is outside it entirely.
            {
                assertEquals(
                    minted - (downF1 - leafCharge),
                    conservation(l),
                    "the deficit is exactly the stranded net inflow, less the leaf spend still counted",
                )
            },
        )
    }

    /**
     * The control arm for the rig above: the *fixture* is what puts both halves on one edge, so
     * degenerating either charge to zero must actually move the numbers. Without this, the
     * precondition assertions read as coverage while being satisfiable by a fixture that never
     * reached the state — the failure mode that has landed ten times in this module.
     */
    @Test
    fun droppingEitherChargeDegeneratesTheFixtureBackToAOneTermStrand() {
        val noLeaf = formerLeafGainsAChild(leaf = 0L)
        val noRollup = formerLeafGainsAChild(rollup = 0L)
        assertAll(
            { assertEquals(0L, noLeaf.storedSlot(CounterFamily.LEAF_SPENT, f1, p), "no leaf charge") },
            { assertEquals(rollupCharge, noLeaf.storedSlot(CounterFamily.ROLLUP_SPENT, f1, p), "…but the roll-up half survives") },
            { assertEquals(leafCharge, noRollup.storedSlot(CounterFamily.LEAF_SPENT, f1, p), "the leaf half survives") },
            { assertEquals(0L, noRollup.storedSlot(CounterFamily.ROLLUP_SPENT, f1, p), "…and no roll-up charge") },
        )
    }

    // ── the move itself ───────────────────────────────────────────────────────────────────────────

    @Test
    fun bothRelocationHalvesRideOneFencedSlotAndOneLiveSlot() {
        val l = formerLeafGainsAChild()
        val patch = assertIs<Relocation.Moved>(
            l.relocateFromConvergedView(h2),
            "delegating down f2 bumps issued(f2), never returned(f1), so the cover gate passes by construction",
        ).patch

        assertAll(
            // §6.3: the two OUT halves land on the SAME (edge, replica) slot of the fenced edge…
            { assertEquals(leafCharge, patch.storedSlot(CounterFamily.LEAF_RELOC_OUT, f1, p), "leaf half cancelled on f1") },
            { assertEquals(rollupCharge, patch.storedSlot(CounterFamily.ROLLUP_RELOC_OUT, f1, p), "roll-up half cancelled on f1") },
            // …and the two IN halves on the SAME slot of the live edge, with the issuance that covers them.
            { assertEquals(leafCharge, patch.storedSlot(CounterFamily.LEAF_RELOC_IN, f1b, p), "leaf half re-opened on f1b") },
            { assertEquals(rollupCharge, patch.storedSlot(CounterFamily.ROLLUP_RELOC_IN, f1b, p), "roll-up half re-opened on f1b") },
            { assertEquals(downF1, patch.storedSlot(CounterFamily.ISSUED_RELOC_IN, f1b, p), "the whole net inflow re-homes with them") },
            // §6.4 observer completeness: the conclusion never travels without the base it cancels.
            { assertEquals(leafCharge, patch.storedSlot(CounterFamily.LEAF_SPENT, f1, p), "f1's base leaf spend is republished") },
            { assertEquals(rollupCharge, patch.storedSlot(CounterFamily.ROLLUP_SPENT, f1, p), "f1's base roll-up spend is republished") },
        )
    }

    @Test
    fun relocatingBothHalvesAtOnceRestoresConservationWithoutMovingTheSpendToTheWrongNode() {
        val l = formerLeafGainsAChild()
        val moved = l.piece(assertIs<Relocation.Moved>(l.relocateFromConvergedView(h2)).patch)

        assertAll(
            // The fenced edge reads fully drained — BOTH halves, which is the discriminator: a move
            // that carried only `lsp` leaves `edge(f1).spent == rollupCharge` and a live
            // ClosureViolation behind it.
            { assertEquals(0L, assertNotNull(moved.edge(f1)).spent, "f1's effective spend nets to zero — both halves relocated") },
            { assertEquals(0L, assertNotNull(moved.edge(f1)).outstanding, "f1 is drained") },
            { assertEquals(leafCharge + rollupCharge, assertNotNull(moved.edge(f1b)).spent, "both halves land on the live edge") },
            { assertEquals(downF1, assertNotNull(moved.edge(f1b)).issued, "…covered by the re-homed issuance") },
            // Placement, which conservation is blind to: the units must sit where the topology says.
            { assertEquals(minted - downF1, moved.holdings(root, p), "root delegated 20 of 100 away, once") },
            { assertEquals(downF1 - leafCharge - downF2, moved.holdings(h2, p), "h2 keeps what it neither spent nor handed on") },
            { assertEquals(downF2 - rollupCharge, moved.holdings(h3, p), "h3 keeps 8 − 3 spent") },
            { assertEquals(minted, conservation(moved), "conservation restored across a move carrying both halves") },
            { assertEquals(minted, moved.mintedTotal(), "the move mints nothing") },
            { assertTrue(moved.validate().isEmpty(), "every conflict cleared: ${moved.validate()}") },
            // The leaf term is invariant under the move — it changed edge, not magnitude.
            { assertEquals(l.leafSpentTotal(), moved.leafSpentTotal(), "relocating leaf spend is conservation-neutral") },
            // Idempotence, and a second discriminator for the roll-up half: were `ROLLUP_RELOC_OUT`
            // not written, the drained edge would still read `rsp = 3` against `n = 0` and a second
            // pass would come back Refused rather than Nothing.
            {
                assertEquals(
                    Relocation.Nothing,
                    moved.relocateFromConvergedView(h2),
                    "a second move finds a fully drained edge",
                )
            },
        )
    }
}
