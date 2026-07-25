package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.ReplicaId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADVERSARIAL POC — THROWAWAY (design-review artifact for #1677, never to be merged).
 *
 * The shipped POC (`LedgerRelocationPocTest`) models the effective-counter arithmetic with
 * mutable `Long`s — i.e. it assumes every delta lands exactly once, in order, with no
 * concurrent writer. That hides the join. This file re-models the same representation with
 * the REAL `GCounter` per-slot max-join and replays the interleavings the design defers as
 * "mechanical" (§5 status note) or claims to fence (§6).
 *
 * Slot model: one replica p3; each named counter is a GCounter keyed by p3, joined with
 * `piece` (elementwise max) — exactly `EntitlementLedger.mergeEdgeCounters`.
 */
class LedgerRelocationAdversarialPocTest {

    private val p3 = ReplicaId("p3")

    /** A replicated per-edge account under real max-join semantics. */
    private data class Acct(
        val issued: GCounter = GCounter.ZERO,
        val returned: GCounter = GCounter.ZERO,
        val leafBase: GCounter = GCounter.ZERO,
        val rollupBase: GCounter = GCounter.ZERO,
        val leafIn: GCounter = GCounter.ZERO,
        val leafOut: GCounter = GCounter.ZERO,
        val rollupIn: GCounter = GCounter.ZERO,
        val rollupOut: GCounter = GCounter.ZERO,
    ) {
        fun piece(o: Acct) = Acct(
            issued.piece(o.issued), returned.piece(o.returned),
            leafBase.piece(o.leafBase), rollupBase.piece(o.rollupBase),
            leafIn.piece(o.leafIn), leafOut.piece(o.leafOut),
            rollupIn.piece(o.rollupIn), rollupOut.piece(o.rollupOut),
        )
        val effLeaf: Long get() = leafBase.value + leafIn.value - leafOut.value
        val effRollup: Long get() = rollupBase.value + rollupIn.value - rollupOut.value
        val net: Long get() = issued.value - returned.value
        val outstanding: Long get() = net - effLeaf - effRollup
        val perEdgeSafe: Boolean get() =
            effLeaf >= 0 && effRollup >= 0 &&
                (effLeaf + effRollup + returned.value) in 0..issued.value
    }

    private fun ledgerJoin(a: Map<String, Acct>, b: Map<String, Acct>): Map<String, Acct> =
        (a.keys + b.keys).associateWith { k ->
            val x = a[k] ?: Acct(); val y = b[k] ?: Acct(); x.piece(y)
        }

    // ────────────────────────────────────────────────────────────────────────────
    // ATTACK 2 — the Reconcile witness writes an ABSOLUTE value into issued(t)[r],
    // a slot replica r also writes (delegate down the live edge t is unfenced by the
    // quiesce, which freezes only s). Two writers + max-join = one side silently
    // erased. Modeled: minted 20 so root has spare; the D1-through strand on e1
    // (net 10, rollup-through 3); reconcile n=10 races delegate d=12 down e3.
    // ────────────────────────────────────────────────────────────────────────────
    @Test
    fun concurrentDelegateDownLiveEdgeErasesHalfTheReconcile() {
        val minted = 20L
        // Converged pre-reconcile state (proposer's frozen snapshot of e1 is causally complete):
        val snapshot = mapOf(
            "e1" to Acct(issued = GCounter.of(p3 to 10L), rollupBase = GCounter.of(p3 to 3L)),
            "e2" to Acct(issued = GCounter.of(p3 to 6L), leafBase = GCounter.of(p3 to 3L)),
            "e3" to Acct(), // fresh reparent edge, root spare = 20 - 10 = 10
        )

        // Delta A — the Reconcile witness, absolute targets computed FROM THE SNAPSHOT (design §4):
        //   returned(e1) -> 10 ; issued(e3) -> 0 + 10 ; rollupOut(e1) -> 3 ; rollupIn(e3) -> 3
        val reconcile = mapOf(
            "e1" to Acct(returned = GCounter.of(p3 to 10L), rollupOut = GCounter.of(p3 to 3L)),
            "e3" to Acct(issued = GCounter.of(p3 to 10L), rollupIn = GCounter.of(p3 to 3L)),
        )

        // Delta B — CONCURRENT data-plane delegate by p3 of d=12 down the ACTIVE e3, feasible on
        // p3's own pre-reconcile view (root holdings = 20 - net(e1)=10 - net(e3)=0 = 10)... make it
        // d=8 to be feasible; then also try d=12 with minted 25. First: the d < n direction.
        val delegateSmall = mapOf("e3" to Acct(issued = GCounter.of(p3 to 8L))) // absolute 0+8

        val convergedSmall = ledgerJoin(ledgerJoin(snapshot, reconcile), delegateSmall)
        val e3s = convergedSmall.getValue("e3")
        // Intended: issued(e3) = 10 (re-home) + 8 (delegate) = 18. Actual: max(10, 8) = 10.
        assertEquals(10L, e3s.issued.value, "max-join swallowed the committed delegate of 8 entirely")

        // The d > n direction: delegate 12 (feasible with minted 25 — spare 15).
        val delegateBig = mapOf("e3" to Acct(issued = GCounter.of(p3 to 12L)))
        val convergedBig = ledgerJoin(ledgerJoin(snapshot, reconcile), delegateBig)
        val e1b = convergedBig.getValue("e1")
        val e3b = convergedBig.getValue("e3")
        // Intended: issued(e3) = 22. Actual: max(10, 12) = 12 — the ENTIRE re-home of 10 is erased...
        assertEquals(12L, e3b.issued.value)
        // ...while the OTHER half of the reconcile (returned(e1) -> 10, uncontested slot) LANDS:
        assertEquals(10L, e1b.returned.value, "the release-up half of the reconcile survives")
        // Net effect: the 10 stranded units teleport back to ROOT instead of the child —
        //   root holdings = 25 - net(e1)=0 - net(e3)=12 = 13   (should be 3)
        //   g holdings    = net(e3)=12 - net(e2)=6           = 6    (should be 16)
        val root = 25L - e1b.net - e3b.net
        val g = e3b.net - convergedBig.getValue("e2").net - e3b.effLeaf
        val h = convergedBig.getValue("e2").net - convergedBig.getValue("e2").effLeaf
        assertEquals(13L, root, "10 units the reconcile committed to the child sit at root instead")
        assertEquals(6L, g)
        // And EVERY diagnostic is clean — conservation holds, per-edge safety holds:
        val leafTotal = convergedBig.values.sumOf { it.effLeaf }
        assertEquals(25L, root + g + h + leafTotal, "conservation identity is blind to the erasure")
        assertTrue(convergedBig.values.all { it.perEdgeSafe }, "per-edge safety is blind to it too")
        // A committed, Applied reconcile was silently half-undone. Nothing surfaces. §10.11 violated.
    }

    // ────────────────────────────────────────────────────────────────────────────
    // ATTACK 3 — the quiesce leak. The causal-stability wait covers writes that EXIST
    // at wait time. A peer that has not yet APPLIED Quiesce(s) locally (log apply is
    // async per peer) — or that holds an uncompleted local reservation whose captured
    // path crosses s (reservations are LOCAL, unreplicated, design §4.4) — charges s
    // AFTER the wait passes. The relocated magnitude is then wrong forever.
    // ────────────────────────────────────────────────────────────────────────────
    @Test
    fun postStabilityStragglerChargeRecreatesTheDiseaseOnTheRelocatedEdge() {
        // Converged POST-reconcile state (the design's §2 target table, achieved):
        val reconciled = mapOf(
            "e1" to Acct(
                issued = GCounter.of(p3 to 10L), returned = GCounter.of(p3 to 10L),
                rollupBase = GCounter.of(p3 to 3L), rollupOut = GCounter.of(p3 to 3L),
            ),
            "e2" to Acct(issued = GCounter.of(p3 to 6L), leafBase = GCounter.of(p3 to 3L)),
            "e3" to Acct(issued = GCounter.of(p3 to 10L), rollupIn = GCounter.of(p3 to 3L)),
        )
        assertTrue(reconciled.values.all { it.perEdgeSafe })
        assertEquals(0L, reconciled.getValue("e1").outstanding)

        // The straggler: a reservation captured [e1,e2] BEFORE the retire; its completion runs on a
        // peer that hasn't applied Quiesce(e1). The charge is a perfectly legal spendCaptured delta:
        //   rollupSpent(e1)[p3] -> 3+2 = 5 (absolute), leafSpent(e2)[p3] -> 3+2 = 5.
        val stragglerCharge = mapOf(
            "e1" to Acct(rollupBase = GCounter.of(p3 to 5L)),
            "e2" to Acct(leafBase = GCounter.of(p3 to 5L)),
        )
        val poisoned = ledgerJoin(reconciled, stragglerCharge)
        val e1 = poisoned.getValue("e1")

        // The retired edge is now PERMANENTLY unsafe — the exact conflict the design exists to clear:
        //   effRollup(e1) = 5 + 0 - 3 = 2;  effLeaf 0 + effRollup 2 + returned 10 = 12 > issued 10.
        assertEquals(2L, e1.effRollup)
        assertTrue(!e1.perEdgeSafe, "PerEdgeSafety(e1) re-created by the straggler, permanently")
        assertTrue(e1.outstanding != 0L, "ClosureViolation(e1) re-created (outstanding = -2)")
        // No later reconcile can clear it: outstanding(e1) = -2 < 0 fails the n >= sp precondition.
    }

    // ────────────────────────────────────────────────────────────────────────────
    // ATTACK 1 — §5.3's lower-bound claim ("a move never sends reloc.out beyond
    // base + reloc.in") is a PROPOSER-VIEW claim. An observer that receives the log-
    // published Reconcile before gossiping in the base spend reads effRollup < 0.
    // Transient and self-healing — but the §5.3 proof as stated is wrong, and the fix
    // (carry the base spend slots in the witness, drain-witness idiom) is absent.
    // ────────────────────────────────────────────────────────────────────────────
    @Test
    fun observerWithoutBaseSpendReadsNegativeEffectiveAfterTheLogDelivery() {
        // Observer's view: has the topology + issuance, never gossiped the through-spend delta.
        val observer = mapOf(
            "e1" to Acct(issued = GCounter.of(p3 to 10L)), // rollupBase ABSENT
            "e3" to Acct(),
        )
        val reconcile = mapOf(
            "e1" to Acct(returned = GCounter.of(p3 to 10L), rollupOut = GCounter.of(p3 to 3L)),
            "e3" to Acct(issued = GCounter.of(p3 to 10L), rollupIn = GCounter.of(p3 to 3L)),
        )
        val merged = ledgerJoin(observer, reconcile)
        assertEquals(-3L, merged.getValue("e1").effRollup, "effective rollup reads NEGATIVE")
        assertTrue(!merged.getValue("e1").perEdgeSafe, "transient per-edge false-fire on the observer")
        // Self-heals when the base delta arrives:
        val healed = ledgerJoin(merged, mapOf("e1" to Acct(rollupBase = GCounter.of(p3 to 3L))))
        assertEquals(0L, healed.getValue("e1").effRollup)
        assertTrue(healed.getValue("e1").perEdgeSafe)
    }
}
