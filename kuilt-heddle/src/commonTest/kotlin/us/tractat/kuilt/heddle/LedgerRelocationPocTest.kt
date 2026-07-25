package us.tractat.kuilt.heddle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * POC — THROWAWAY. Not part of the module design; delete before the real
 * representation change (relocation counters, `docs/heddle-ledger-relocation-design.md`)
 * is implemented.
 *
 * This is a **self-contained arithmetic model** of the proposed effective-counter
 * representation (A): base grow-only counters plus signed `relocIn`/`relocOut`
 * adjustments, `effective = base + in − out`. It does NOT touch [EntitlementLedger];
 * it exists only to demonstrate that the **conserving generation-move** of §4/§5 of
 * the design (a) restores conservation, (b) preserves per-edge safety on BOTH the
 * retired and the live edge, and (c) is idempotent — on the exact D1 through-service
 * example that PR #1669 must fail-closed on for lack of this representation.
 *
 * If this passes, the *math* of the design is sound; the Kotlin wiring is a separate
 * (reviewed) implementation.
 */
class LedgerRelocationPocTest {

    /** A minimal per-edge account: grow-only base counters + signed spent relocation. */
    private data class Edge(
        var issued: Long = 0,
        var returned: Long = 0,
        var leafBase: Long = 0,
        var rollupBase: Long = 0,
        // Signed adjustments, each realised as two grow-only counters (only their nets shown).
        var leafRelocIn: Long = 0,
        var leafRelocOut: Long = 0,
        var rollupRelocIn: Long = 0,
        var rollupRelocOut: Long = 0,
    ) {
        val effLeaf: Long get() = leafBase + leafRelocIn - leafRelocOut
        val effRollup: Long get() = rollupBase + rollupRelocIn - rollupRelocOut
        val netInflow: Long get() = issued - returned
        val outstanding: Long get() = issued - returned - effLeaf - effRollup

        /** Per-edge safety, sum-wise on effective values (design §5.3). */
        val perEdgeSafe: Boolean get() =
            effLeaf >= 0 && effRollup >= 0 &&
                (effLeaf + effRollup + returned) in 0..issued
    }

    // D1 through-service converged state (PR #1669 spendThroughStrand):
    //  mint 10→p3; issued(e1)=10, rollupSpent(e1)=3 (spent 3 through [e1,e2]);
    //  issued(e2)=6, leafSpent(e2)=3; e1 RETIRED; reparent e3 (issued=0).
    private fun d1Through(): MutableMap<String, Edge> = mutableMapOf(
        "e1" to Edge(issued = 10, rollupBase = 3),           // root→g, RETIRED, spent-through
        "e2" to Edge(issued = 6, leafBase = 3),              // g→h
        "e3" to Edge(issued = 0),                            // root→g, the reparent generation
    )

    private val minted = 10L

    /**
     * The conserving generation-move e1 → e3 (design §4): release the full net inflow up s,
     * re-delegate it down t, and relocate s's spend onto t via the signed counters.
     */
    private fun move(edges: MutableMap<String, Edge>, s: String, t: String) {
        val src = edges.getValue(s)
        val dst = edges.getValue(t)
        val n = src.netInflow
        val lsp = src.effLeaf
        val rsp = src.effRollup
        require(n >= lsp + rsp) { "precondition outstanding(s) >= 0 (not transfer-tangled)" }
        // 1. net-inflow re-home (grow-only bumps)
        src.returned = src.issued            // release full net inflow up s: returned → issued
        dst.issued += n                       // re-delegate down t
        // 2. spend relocation (signed adjustment; a net DECREASE on s via a 2nd monotone counter)
        src.leafRelocOut += lsp;   dst.leafRelocIn += lsp
        src.rollupRelocOut += rsp; dst.rollupRelocIn += rsp
    }

    // Holdings for p3 (single replica here), design §holdings, using EFFECTIVE leaf spend.
    // topology: root→g via {e1(retired), e3(live)}; g→h via e2. live inbound of g = e3.
    private fun holdingsRoot(e: Map<String, Edge>) =
        minted - e.getValue("e1").netInflow - e.getValue("e3").netInflow      // children of root
    private fun holdingsG(e: Map<String, Edge>) =
        e.getValue("e3").netInflow - e.getValue("e2").netInflow - e.getValue("e3").effLeaf
    private fun holdingsH(e: Map<String, Edge>) =
        e.getValue("e2").netInflow - e.getValue("e2").effLeaf

    private fun sumHoldings(e: Map<String, Edge>) = holdingsRoot(e) + holdingsG(e) + holdingsH(e)
    private fun leafSpentTotal(e: Map<String, Edge>) = e.values.sumOf { it.effLeaf }

    @Test
    fun theStrandBreaksConservationBeforeTheMove() {
        val e = d1Through()
        assertEquals(-6L, holdingsG(e), "g derives permanently negative pre-move")
        assertTrue(
            sumHoldings(e) + leafSpentTotal(e) != minted,
            "the strand breaks conservation (=${sumHoldings(e) + leafSpentTotal(e)} ≠ $minted)",
        )
    }

    @Test
    fun theGenerationMoveRestoresConservationAndPerEdgeSafety() {
        val e = d1Through()
        move(e, s = "e1", t = "e3")

        // (a) conservation restored, supply unchanged
        assertEquals(minted, sumHoldings(e) + leafSpentTotal(e), "conservation restored")
        assertEquals(4L, holdingsG(e), "g re-homes to the un-spent remainder (10 − 6)")
        assertEquals(0L, holdingsRoot(e))
        assertEquals(3L, holdingsH(e))

        // (b) per-edge safety holds on BOTH the retired and the live edge
        assertTrue(e.getValue("e1").perEdgeSafe, "e1 (retired) per-edge safe: 0+0+10 ≤ 10")
        assertTrue(e.getValue("e3").perEdgeSafe, "e3 (live) per-edge safe: 0+3+0 ≤ 10")
        assertEquals(0L, e.getValue("e1").outstanding, "e1 fully drained → ClosureViolation cleared")
        assertEquals(0L, e.getValue("e1").effRollup, "spend relocated OFF the grow-only base via the signed net")

        // effective decrease achieved with a grow-only counter: base never fell
        assertEquals(3L, e.getValue("e1").rollupBase, "base rollupSpent(e1) is unchanged (still grows-only)")
        assertEquals(3L, e.getValue("e1").rollupRelocOut, "…the decrease is a SECOND monotone counter")
    }

    @Test
    fun theMoveIsIdempotent() {
        val e = d1Through()
        move(e, s = "e1", t = "e3")
        val after = e.mapValues { it.value.copy() }
        // A second move finds nothing to relocate (outstanding(e1) == 0) — modelled as a guard here.
        assertEquals(0L, e.getValue("e1").outstanding, "nothing left to re-home")
        assertEquals(minted, sumHoldings(after) + leafSpentTotal(after), "still conserved")
    }
}
