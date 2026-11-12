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
 * former leaf later gains a child". That reshape is the shape — and the ordering is load-bearing,
 * not decoration. `spend` `require`s `isLeaf(group)` and `childEdges` ignores lifecycle entirely,
 * so `isLeaf(h2)` flips false the instant `f2`'s **record** lands: preparing the child before the
 * leaf spend turns step 3 into an `IllegalArgumentException`. Activating `f2` is not optional
 * either — `PREPARED` is not a live edge, so `spend(p, h3, ·)` would find no lineage and return
 * `null`.
 *
 * ```
 *  1. mint 100 at root                          minted            = 100
 *  2. delegate 20 down f1 (root → h2)           issued(f1)        =  20
 *  3. spend 5 at h2, still a LEAF               leafSpent(f1)     =   5   ← no prefix to roll up
 *  4. prepare + activate f2 (h2 → h3)           h2 stops being a leaf
 *  5. delegate 8 down f2                        issued(f2)        =   8
 *  6. spend 3 at h3                             leafSpent(f2)     =   3
 *                                               rollupSpent(f1)   =   3   ← f1 is now a strict prefix
 *  7. close(f1) + a raced RETIRED merge, then the legal reparent onto f1b (root → h2)
 * ```
 *
 * `f1` therefore carries `leafSpent 5` **and** `rollupSpent 3`, and re-homing it moves a non-zero
 * `lsp` and a non-zero `rsp` in one patch:
 *
 * ```
 *  f1   returned 20, leafRelocOut 5, rollupRelocOut 3  →  eff spend 0, outstanding 0
 *  f1b  issuedRelocIn 20, leafRelocIn 5, rollupRelocIn 3  →  eff issued 20, eff spend 5 + 3 = 8
 *  holdings   root 100 − 20 = 80    h2 20 − 5 − 8 = 7    h3 8 − 3 = 5
 *  Σ holdings 92  +  Σ effLeafSpent (0 + 5 + 3) = 100 = minted
 * ```
 *
 * ## Every expected value below is a literal from that table, deliberately
 *
 * The first cut of this file spelled each expectation in terms of the fixture's own constants
 * (`assertEquals(leafCharge, …)`). That is **self-consistency, not verification**: it reds when
 * production computes the wrong thing, but it stays green when the *fixture* degenerates, because
 * both sides move together. Measured, not reasoned: zeroing either charge on that cut reddened
 * **1 test of 4**, and the one red came from a hand-written `assertTrue(… > 0)` guard rather than
 * from any value assertion — so without that guard the file would have been fully vacuous. The
 * same two mutations against the literals below red **4 of 4**, on 3 / 1 / 4 / 3 assertions
 * respectively. A literal cannot co-vary with the fixture, which is the whole of the difference.
 *
 * `EntitlementLedgerReconcileTest.bothHalvesOfTheSpendSplitCountTowardTheCoverPreconditionTogether`
 * is the cheap half of this — it pins that the state is *representable*, by feeding
 * `relocationPatch` a hand-built `SlotFinals`, and it is the only one of the two that can see the
 * `cover ≥ charge` gate: here the cover is `n = 20` against a charge of `8` by construction (both
 * charges were funded *through* this very edge), so the gate cannot be the thing that fails. This
 * file is the other half: the state is **reachable** through the real mutators, and the two
 * `*_RELOC_OUT` / `*_RELOC_IN` write pairs land where the design says.
 */
class EntitlementLedgerLeafGainsChildRelocationTest {

    private val root = GroupId("root")
    private val h2 = GroupId("h2") // the former leaf that later gains a child
    private val h3 = GroupId("h3") // the child it gains
    private val p = ReplicaId("p")

    private val f1 = AttachmentId("f1") // root → h2, stranded by the raced retire; carries BOTH charges
    private val f2 = AttachmentId("f2") // h2   → h3, the child edge that ends h2's leaf-hood
    private val f1b = AttachmentId("f1b") // root → h2, the legal reparent generation

    private fun rec(id: AttachmentId, parent: GroupId, child: GroupId) =
        AttachmentRecord(id, parent, child, Weight.ONE)

    private fun EntitlementLedger.applying(patch: Patch<EntitlementLedger>?): EntitlementLedger =
        piece(assertNotNull(patch, "the mutator must be feasible here"))

    /**
     * The reshape, exactly as the class KDoc's table spells it. [leaf] and [rollup] are the two
     * charges; passing `0` for either is how [droppingEitherChargeDegeneratesTheFixtureBackToAOneTermStrand]
     * degenerates this fixture back to a one-term strand — a `0` spend is the contract's no-op
     * cancel, so the *sequence* is unchanged and only the charge disappears.
     */
    private fun formerLeafGainsAChild(leaf: Long = 5L, rollup: Long = 3L): EntitlementLedger {
        var l = EntitlementLedger.ZERO.piece(EntitlementLedger.bootstrap(root, mapOf(p to 100L), nonce = "genesis"))
        l = l.applying(l.prepare(rec(f1, root, h2)))
        l = l.applying(l.activate(f1))
        l = l.applying(l.delegate(p, f1, 20L))
        // h2 is still a leaf — this charge lands on `leafSpent(f1)` with no prefix to roll up.
        l = l.applying(l.spend(p, h2, leaf))
        // h2 gains a child. Prepared AND activated: a merely-prepared edge is not live, so a spend
        // at h3 would find no lineage at all.
        l = l.applying(l.prepare(rec(f2, h2, h3)))
        l = l.applying(l.activate(f2))
        l = l.applying(l.delegate(p, f2, 8L))
        // f1 is now a strict prefix of [f1, f2], so this charge rolls up onto it.
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
            { assertEquals(5L, l.storedSlot(CounterFamily.LEAF_SPENT, f1, p), "f1 carries the leaf charge") },
            { assertEquals(3L, l.storedSlot(CounterFamily.ROLLUP_SPENT, f1, p), "f1 carries the roll-up charge too") },
            // The property, named rather than only valued, and read off the LEDGER rather than off
            // the fixture's inputs: this is the state #2389 says nothing has ever reached.
            {
                assertTrue(
                    l.storedSlot(CounterFamily.LEAF_SPENT, f1, p) > 0L &&
                        l.storedSlot(CounterFamily.ROLLUP_SPENT, f1, p) > 0L,
                    "both halves must be live on ONE edge or the move below proves nothing",
                )
            },
            // The reshape actually happened: h2 is no longer a leaf, and f2 is the child that ended it.
            { assertTrue(l.isLeaf(h3), "h3 is the new leaf") },
            { assertTrue(!l.isLeaf(h2), "h2 stopped being a leaf when f2's record landed") },
            { assertEquals(3L, l.storedSlot(CounterFamily.LEAF_SPENT, f2, p), "the post-reshape charge is f2's leaf spend") },
            // …and the strand is a real strand: conservation is broken by exactly the stranded
            // holdings, so "restored" below is a claim about the move rather than about nothing.
            // 85, not 92: the deficit is the stranded net inflow (20) less the leaf spend still
            // counted (5). The roll-up charge is NOT deducted — the split stated as arithmetic, the
            // leaf half being a term of the identity and the roll-up half outside it entirely.
            { assertEquals(85L, conservation(l), "the deficit is exactly the stranded net inflow, less the leaf spend still counted") },
        )
    }

    /**
     * The control arm for the rig above: the *fixture* is what puts both halves on one edge, so
     * degenerating either charge to zero must actually move the numbers. Without this, "the fixture
     * reaches the state" rests on reading the sequence rather than on anything that fails.
     */
    @Test
    fun droppingEitherChargeDegeneratesTheFixtureBackToAOneTermStrand() {
        val noLeaf = formerLeafGainsAChild(leaf = 0L)
        val noRollup = formerLeafGainsAChild(rollup = 0L)
        assertAll(
            { assertEquals(0L, noLeaf.storedSlot(CounterFamily.LEAF_SPENT, f1, p), "no leaf charge") },
            { assertEquals(3L, noLeaf.storedSlot(CounterFamily.ROLLUP_SPENT, f1, p), "…but the roll-up half survives") },
            { assertEquals(5L, noRollup.storedSlot(CounterFamily.LEAF_SPENT, f1, p), "the leaf half survives") },
            { assertEquals(0L, noRollup.storedSlot(CounterFamily.ROLLUP_SPENT, f1, p), "…and no roll-up charge") },
        )
    }

    // ── the move itself ───────────────────────────────────────────────────────────────────────────

    @Test
    fun bothRelocationHalvesRideOneFencedSlotAndOneLiveSlot() {
        val l = formerLeafGainsAChild()
        val patch = assertIs<Relocation.Moved>(
            l.relocateFromConvergedView(h2),
            "delegating down f2 bumps issued(f2), never returned(f1), so cover (20) ≥ charge (5 + 3) holds by construction",
        ).patch

        assertAll(
            // §6.3: the two OUT halves land on the SAME (edge, replica) slot of the fenced edge…
            { assertEquals(5L, patch.storedSlot(CounterFamily.LEAF_RELOC_OUT, f1, p), "leaf half cancelled on f1") },
            { assertEquals(3L, patch.storedSlot(CounterFamily.ROLLUP_RELOC_OUT, f1, p), "roll-up half cancelled on f1") },
            // …and the two IN halves on the SAME slot of the live edge, with the issuance that covers them.
            { assertEquals(5L, patch.storedSlot(CounterFamily.LEAF_RELOC_IN, f1b, p), "leaf half re-opened on f1b") },
            { assertEquals(3L, patch.storedSlot(CounterFamily.ROLLUP_RELOC_IN, f1b, p), "roll-up half re-opened on f1b") },
            { assertEquals(20L, patch.storedSlot(CounterFamily.ISSUED_RELOC_IN, f1b, p), "the whole net inflow re-homes with them") },
            // §6.4 observer completeness: the conclusion never travels without the base it cancels.
            { assertEquals(5L, patch.storedSlot(CounterFamily.LEAF_SPENT, f1, p), "f1's base leaf spend is republished") },
            { assertEquals(3L, patch.storedSlot(CounterFamily.ROLLUP_SPENT, f1, p), "f1's base roll-up spend is republished") },
        )
    }

    @Test
    fun relocatingBothHalvesAtOnceRestoresConservationWithoutMovingTheSpendToTheWrongNode() {
        val l = formerLeafGainsAChild()
        val moved = l.piece(assertIs<Relocation.Moved>(l.relocateFromConvergedView(h2)).patch)

        assertAll(
            // The fenced edge reads fully drained — BOTH halves, which is the discriminator: a move
            // that carried only `lsp` leaves `edge(f1).spent == 3` and a live ClosureViolation behind it.
            { assertEquals(0L, assertNotNull(moved.edge(f1)).spent, "f1's effective spend nets to zero — both halves relocated") },
            { assertEquals(0L, assertNotNull(moved.edge(f1)).outstanding, "f1 is drained") },
            { assertEquals(8L, assertNotNull(moved.edge(f1b)).spent, "both halves land on the live edge: 5 + 3") },
            { assertEquals(20L, assertNotNull(moved.edge(f1b)).issued, "…covered by the re-homed issuance") },
            // Placement, which conservation is blind to: the units must sit where the topology says.
            { assertEquals(80L, moved.holdings(root, p), "root delegated 20 of 100 away, once") },
            { assertEquals(7L, moved.holdings(h2, p), "h2 keeps 20 − 5 spent − 8 handed on") },
            { assertEquals(5L, moved.holdings(h3, p), "h3 keeps 8 − 3 spent") },
            { assertEquals(100L, conservation(moved), "conservation restored across a move carrying both halves") },
            { assertEquals(100L, moved.mintedTotal(), "the move mints nothing") },
            { assertTrue(moved.validate().isEmpty(), "every conflict cleared: ${moved.validate()}") },
            // The leaf term is invariant under the move — it changed edge, not magnitude.
            { assertEquals(8L, moved.leafSpentTotal(), "Σ effLeafSpent is unchanged: relocating leaf spend is conservation-neutral") },
            { assertEquals(8L, l.leafSpentTotal(), "…and it was 8 before the move too, which is what makes that a claim") },
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
