package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * #1691 (slice 1 of #1665) — **finding 1 of the relocation adversarial review**: the
 * reconciliation witness must never write a *base* `issued` slot on the child's **live**
 * inbound edge.
 *
 * The defect this pins is inherited from the shipped release-up-then-redelegate path, not
 * introduced by the relocation representation. The re-home used to ship an **absolute**
 * `issued(t)[r]` target computed from the proposer's snapshot:
 *
 * ```
 * issuedTarget = ... GCounter.of(r to checkedAdd(slot(issued, liveEdge, r), add))
 * ```
 *
 * `t` is the **live** edge, so replica `r` writes that very slot concurrently through an
 * ordinary [EntitlementLedger.delegate]. Two independent writers on one `GCounter` slot
 * under per-slot max-join means **one side is silently erased** — and the erasure is
 * invisible: conservation still balances and every edge still passes per-edge safety, so
 * `validate()` stays empty while the units sit at the **wrong node**.
 *
 * The fix is the slot-ownership discipline of the relocation design §6.3: additions to a
 * live edge go to `issuedReloc.in(t)[r]`, a counter family the control plane owns
 * exclusively, so the contended slot **ceases to exist**. These tests therefore assert
 * *placement* (holdings per group) and the structural property that the witness carries no
 * base `issued` slot at all — never merely conservation, which is blind to the fault.
 */
class EntitlementLedgerRelocationTest {

    private val root = GroupId("root")
    private val g = GroupId("g")
    private val h = GroupId("h")
    private val p3 = ReplicaId("p3")
    private val q = ReplicaId("q")
    private val e1 = AttachmentId("e1") // root → g  (stranded by the raced retire)
    private val e2 = AttachmentId("e2") // g    → h
    private val e3 = AttachmentId("e3") // root → g  (the legal reparent generation)

    private fun rec(id: AttachmentId, parent: GroupId, child: GroupId) =
        AttachmentRecord(id, parent, child, Weight.ONE, 0L)

    /**
     * The D1 strand **without** through-service: `e1` retired by a lagged proposer while
     * `p3`'s (and optionally `q`'s) delegation was still outstanding, then `g` legally
     * reparented onto the fresh `e3`. `spent(e1) == 0`, so the shipped re-home applies.
     */
    private fun strandedNoThroughService(mint: Map<ReplicaId, Long>, downE1: List<Pair<ReplicaId, Long>>, downE2: List<Pair<ReplicaId, Long>>): EntitlementLedger {
        var l = EntitlementLedger.ZERO.piece(EntitlementLedger.bootstrap(root, mint, nonce = "genesis"))
        l = l.piece(l.prepare(rec(e1, root, g))!!.delta)
        l = l.piece(l.activate(e1)!!.delta)
        l = l.piece(l.prepare(rec(e2, g, h))!!.delta)
        l = l.piece(l.activate(e2)!!.delta)
        for ((r, amount) in downE1) l = l.piece(l.delegate(r, e1, amount)!!.delta)
        for ((r, amount) in downE2) l = l.piece(l.delegate(r, e2, amount)!!.delta)
        l = l.piece(l.close(e1)!!.delta)
        // The LAGGED retire: a peer that had not merged the delegate saw outstanding(e1)=0.
        l = l.piece(EntitlementLedger.of(lifecycle = mapOf(e1 to Lifecycle.RETIRED)))
        // Legal reparent: e1 is RETIRED (not live), so activating e3 passes the dual-inbound gate.
        l = l.piece(l.prepare(rec(e3, root, g))!!.delta)
        l = l.piece(l.activate(e3)!!.delta)
        return l
    }

    private fun conservation(l: EntitlementLedger, replicas: List<ReplicaId>): Long {
        var acc = 0L
        for (grp in listOf(root, g, h)) for (r in replicas) acc = acc + l.holdings(grp, r)
        return acc + l.leafSpentTotal()
    }

    // ── the representation: a net decrease with no decrement anywhere ───────────────────

    @Test
    fun effectiveValuesAreBasePlusInMinusOutAndEveryStoredSlotOnlyGrows() {
        val base = EntitlementLedger.of(
            records = mapOf(e1 to setOf(rec(e1, root, g))),
            issued = mapOf(e1 to GCounter.of(p3 to 10L)),
            leafSpent = mapOf(e1 to GCounter.of(p3 to 4L)),
            rollupSpent = mapOf(e1 to GCounter.of(p3 to 3L)),
        )
        // A relocation moves 4 leaf-spend and 3 roll-up-spend units off e1 and onto e3, and
        // re-homes the 10 units of issuance with it — all through grow-only counters.
        val move = EntitlementLedger.of(
            leafRelocOut = mapOf(e1 to GCounter.of(p3 to 4L)),
            leafRelocIn = mapOf(e3 to GCounter.of(p3 to 4L)),
            rollupRelocOut = mapOf(e1 to GCounter.of(p3 to 3L)),
            rollupRelocIn = mapOf(e3 to GCounter.of(p3 to 3L)),
            issuedRelocIn = mapOf(e3 to GCounter.of(p3 to 10L)),
        )
        val moved = base.piece(move)

        // e1 reads DRAINED of spend, e3 reads charged — with no counter ever decremented.
        assertEquals(0L, moved.edge(e1)!!.spent, "e1's effective spend nets to zero")
        assertEquals(10L, moved.edge(e1)!!.issued, "e1's issuance is untouched by the spend move")
        assertEquals(7L, moved.edge(e3)!!.spent, "the 4 + 3 units land on e3")
        assertEquals(10L, moved.edge(e3)!!.issued, "e3's effective issuance is 0 base + 10 relocated")
        assertEquals(10L, moved.effectiveIssued(e3, p3))
        // Σ effective leaf spend is invariant under the move — the conservation term does not drift.
        assertEquals(base.leafSpentTotal(), moved.leafSpentTotal(), "leaf-spend relocation is conservation-neutral")

        // Every stored slot in every family only ever grew.
        for (family in CounterFamily.entries) {
            for (e in listOf(e1, e3)) {
                assertTrue(
                    moved.storedSlot(family, e, p3) >= base.storedSlot(family, e, p3),
                    "$family slot on $e fell — a decrement leaked into the representation",
                )
            }
        }
        // …and re-delivering the move is a no-op.
        assertEquals(moved, moved.piece(move))
    }

    // ── the structural guarantee: no contended slot exists ───────────────────────────────

    @Test
    fun rehomeWitnessNeverWritesABaseIssuedSlot() {
        val l = strandedNoThroughService(mapOf(p3 to 25L), listOf(p3 to 10L), listOf(p3 to 6L))
        val patch = l.relocationOrNull(g)
        assertNotNull(patch, "there is a strand to reconcile")
        // §6.3 slot-ownership: base counters on a LIVE edge are the data plane's exclusively.
        // The re-home's credit must ride a control-plane-owned relocation counter instead, so
        // the erasure of finding 1 has no slot to happen on.
        assertTrue(
            e3 !in patch.issuedEdges(),
            "the re-home must not write the LIVE edge's base `issued` slot (it writes issuedReloc.in there); wrote ${patch.issuedEdges()}",
        )
        assertEquals(setOf(e3), patch.issuedRelocInEdges(), "the credit lands on the live edge's relocation counter")
        // It still drains the retired edge through the ordinary (fenced, uncontended) base slots —
        // and republishes that edge's own base at its acked final, so no observer can hold the
        // conclusion without its premises (§6.4 observer completeness).
        assertEquals(setOf(e1), patch.returnedEdges(), "the drain of the retired edge stays a base `returned` write")
        assertEquals(setOf(e1), patch.issuedEdges(), "the only base `issued` slot written is the FENCED edge's republish")
    }

    // ── finding 1, single replica: the concurrent delegate and the re-home both survive ──

    @Test
    fun concurrentDelegateDownTheLiveEdgeDoesNotEraseTheRehome() {
        val l = strandedNoThroughService(mapOf(p3 to 25L), listOf(p3 to 10L), listOf(p3 to 6L))
        val rehome = assertNotNull(l.relocationOrNull(g), "there is a strand to reconcile")
        // Concurrently — on p3's own pre-reconcile view, which is feasible: holdings(root,p3) = 15.
        val delegate = assertNotNull(l.delegate(p3, e3, 12L), "the ordinary data-plane delegate is feasible")

        val converged = l.piece(rehome).piece(delegate.delta)

        // BOTH writes must survive: 12 delegated + 10 re-homed. (Before the migration the
        // max-join collapsed these two absolutes on one slot to 12 — the delegate won, the
        // re-home vanished, and nothing surfaced.)
        assertEquals(22L, converged.edge(e3)!!.issued, "effective issued on the live edge = base delegate 12 + re-homed 10")
        // Placement is the fault the diagnostics are blind to — assert it directly.
        assertEquals(16L, converged.holdings(g, p3), "the re-homed 10 must land at the CHILD (22 − 6 delegated onward)")
        assertEquals(3L, converged.holdings(root, p3), "root keeps only what it never delegated (25 − 22)")
        assertEquals(6L, converged.holdings(h, p3))
        assertEquals(25L, conservation(converged, listOf(p3)), "conservation holds (it also held under the erasure — that is the point)")
        assertTrue(converged.validate().isEmpty(), "no conflict remains: ${converged.validate()}")
    }

    @Test
    fun aSmallerConcurrentDelegateIsNotSwallowedByTheRehome() {
        val l = strandedNoThroughService(mapOf(p3 to 25L), listOf(p3 to 10L), listOf(p3 to 6L))
        val rehome = assertNotNull(l.relocationOrNull(g))
        val delegate = assertNotNull(l.delegate(p3, e3, 8L))
        val converged = l.piece(rehome).piece(delegate.delta)
        // The other direction of the same erasure: a delegate SMALLER than the re-home used to
        // be swallowed whole by max(10, 8) = 10.
        assertEquals(18L, converged.edge(e3)!!.issued, "8 delegated + 10 re-homed")
        assertEquals(12L, converged.holdings(g, p3))
        assertEquals(25L, conservation(converged, listOf(p3)))
    }

    // ── finding 1, genuine multi-replica merge: each replica loses a different half ──────

    @Test
    fun multiReplicaRehomeAndConcurrentDelegatesAllSurvive() {
        val replicas = listOf(p3, q)
        val l = strandedNoThroughService(
            mint = mapOf(p3 to 40L, q to 40L),
            downE1 = listOf(p3 to 10L, q to 6L),
            downE2 = listOf(p3 to 4L),
        )
        assertEquals(16L, l.edge(e1)!!.outstanding, "both replicas' delegations are stranded on e1")

        val rehome = assertNotNull(l.relocationOrNull(g), "a two-replica strand is re-homable")
        // Two independent data-plane writers race the re-home on the live edge — one with a
        // delegate SMALLER than its re-home (p3: 3 vs 10), one LARGER (q: 7 vs 6). Under the
        // old base-`issued` witness p3 lost its delegate and q lost its relocation.
        val delegateP3 = assertNotNull(l.delegate(p3, e3, 3L))
        val delegateQ = assertNotNull(l.delegate(q, e3, 7L))

        val converged = l.piece(rehome).piece(delegateP3.delta).piece(delegateQ.delta)

        assertEquals(26L, converged.edge(e3)!!.issued, "effective issued = (3+7) delegated + (10+6) re-homed")
        assertEquals(9L, converged.holdings(g, p3), "p3 at g: re-homed 10 + delegated 3 − 4 handed onward")
        assertEquals(13L, converged.holdings(g, q), "q at g: re-homed 6 + delegated 7")
        assertEquals(27L, converged.holdings(root, p3))
        assertEquals(27L, converged.holdings(root, q))
        assertEquals(80L, conservation(converged, replicas), "conservation holds across both replicas")
        assertTrue(converged.validate().isEmpty(), "no conflict remains: ${converged.validate()}")
    }

    @Test
    fun rehomeAndConcurrentDelegatesConvergeInEveryMergeOrderWithConservationAtEveryStep() {
        val replicas = listOf(p3, q)
        val l = strandedNoThroughService(
            mint = mapOf(p3 to 40L, q to 40L),
            downE1 = listOf(p3 to 10L, q to 6L),
            downE2 = listOf(p3 to 4L),
        )
        val deltas: List<EntitlementLedger> = listOf(
            assertNotNull(l.relocationOrNull(g)),
            assertNotNull(l.delegate(p3, e3, 3L)).delta,
            assertNotNull(l.delegate(q, e3, 7L)).delta,
        )
        val rehomeIndex = 0
        // The strand's deficit is EXACTLY the stranded net inflow (16) and nothing else, at
        // every interleaving: a concurrent delegate never widens it, and the re-home closes it
        // to zero the instant it is delivered — whatever else has already merged.
        assertEquals(64L, conservation(l, replicas), "the strand's deficit is exactly the stranded 16")
        var canonical: EntitlementLedger? = null
        for (order in permutations(deltas.indices.toList())) {
            var acc = l
            val delivered = HashSet<Int>()
            for (i in order) {
                acc = acc.piece(deltas[i])
                delivered += i
                val expected = if (rehomeIndex in delivered) 80L else 64L
                assertEquals(expected, conservation(acc, replicas), "conservation drifted mid-delivery on order $order after $delivered")
            }
            val previous = canonical
            if (previous == null) canonical = acc else assertEquals(previous, acc, "merge order $order diverged")
        }
        val converged = canonical ?: fail("no permutations ran")
        // Order-independence alone is blind to the erasure — every order agrees on the WRONG
        // placement just as happily. Pin the placement too.
        assertEquals(9L, converged.holdings(g, p3), "p3's re-home and delegate both land at g")
        assertEquals(13L, converged.holdings(g, q), "q's re-home and delegate both land at g")
        // Duplicate delivery of every delta, in every order, is absorbed idempotently.
        var doubled = converged
        for (d in deltas) doubled = doubled.piece(d)
        assertEquals(converged, doubled, "re-delivery must be a no-op (max-join idempotence)")
    }

    @Test
    fun randomizedStrandsRehomeExactlyOnceWithConservationAndPlacementHolding() {
        val rnd = Random(0x1665)
        val replicas = listOf(p3, q)
        repeat(120) {
            val mintP3 = rnd.nextLong(60L, 200L)
            val mintQ = rnd.nextLong(60L, 200L)
            val strandP3 = rnd.nextLong(1L, 40L)
            val strandQ = rnd.nextLong(1L, 40L)
            val onward = rnd.nextLong(1L, strandP3 + 1L) // p3 hands some of its strand down to h
            val l = strandedNoThroughService(
                mint = mapOf(p3 to mintP3, q to mintQ),
                downE1 = listOf(p3 to strandP3, q to strandQ),
                downE2 = listOf(p3 to onward),
            )
            val minted = mintP3 + mintQ
            val stranded = strandP3 + strandQ
            assertEquals(minted - stranded, conservation(l, replicas), "the deficit is exactly the strand")

            val rehome = assertNotNull(l.relocationOrNull(g))
            // Concurrent, independently-feasible delegations down the live edge by both replicas.
            val dP3 = rnd.nextLong(1L, 30L)
            val dQ = rnd.nextLong(1L, 30L)
            var converged = l.piece(rehome)
            for ((r, amount) in listOf(p3 to dP3, q to dQ)) {
                val d = l.delegate(r, e3, amount) ?: continue
                converged = converged.piece(d.delta)
            }
            assertEquals(minted, conservation(converged, replicas), "conservation restored exactly")
            // Placement: nothing the re-home moved was erased by a concurrent delegate.
            assertEquals(
                strandP3 + dP3 - onward,
                converged.holdings(g, p3),
                "p3's re-homed strand and its concurrent delegate both landed at g",
            )
            assertEquals(strandQ + dQ, converged.holdings(g, q), "q's re-homed strand and delegate both landed at g")
            assertTrue(converged.validate().isEmpty(), "no conflict remains: ${converged.validate()}")
            // Re-homed exactly once: the strand is cleared, so a second pass finds nothing.
            assertNull(converged.relocationOrNull(g), "a second reconcile must find nothing to move")
        }
    }

    // ── the un-gate (#1693): a through-service strand now moves, and the D1 target table lands ──

    @Test
    fun throughServiceStrandRelocatesToTheDesignTargetTable() {
        // The design's §2 worked example, end to end. Service was spent THROUGH e1 before the
        // reparent (rollupSpent(e1) = 3), which slice 1 refused because relocating an already-charged
        // spend needs the fence. With the fence, the move lands on the design's target table exactly.
        var l = EntitlementLedger.ZERO.piece(EntitlementLedger.bootstrap(root, mapOf(p3 to 10L), nonce = "genesis"))
        l = l.piece(l.prepare(rec(e1, root, g))!!.delta)
        l = l.piece(l.activate(e1)!!.delta)
        l = l.piece(l.prepare(rec(e2, g, h))!!.delta)
        l = l.piece(l.activate(e2)!!.delta)
        l = l.piece(l.delegate(p3, e1, 10L)!!.delta)
        l = l.piece(l.delegate(p3, e2, 6L)!!.delta)
        l = l.piece(l.spend(p3, h, 3L)!!.delta) // through the OLD lineage [e1,e2] → rollupSpent(e1)=3
        l = l.piece(l.close(e1)!!.delta)
        l = l.piece(EntitlementLedger.of(lifecycle = mapOf(e1 to Lifecycle.RETIRED)))
        l = l.piece(l.prepare(rec(e3, root, g))!!.delta)
        l = l.piece(l.activate(e3)!!.delta)

        assertEquals(3L, l.edge(e1)!!.spent, "service was spent through the stranded edge")
        assertTrue(
            l.validate().contains(LedgerConflict.PersistentNegativeHoldings(g, p3)),
            "pre-condition: the strand's conflicts stand before the move",
        )
        val moved = l.piece(assertNotNull(l.relocationOrNull(g), "the fenced move clears a through-service strand"))

        // Design §2's target table: e1 drained (issued 10, returned 10, eff spend 0); e3 carries the
        // re-homed generation (eff issued 10) AND the 3 units of service charged through it.
        assertEquals(10L, moved.edge(e1)!!.issued)
        assertEquals(10L, moved.edge(e1)!!.returned)
        assertEquals(0L, moved.edge(e1)!!.spent, "e1's effective spend nets to zero — relocated, not decremented")
        assertEquals(0L, moved.edge(e1)!!.outstanding, "e1 is drained, clearing its ClosureViolation")
        assertEquals(10L, moved.edge(e3)!!.issued, "the full net inflow re-homes onto the live edge")
        assertEquals(3L, moved.edge(e3)!!.spent, "the through-service charge re-homes with it")
        assertEquals(4L, moved.holdings(g, p3), "g holds the un-spent remainder (10 − 6 handed onward)")
        assertEquals(10L, moved.mintedTotal(), "the move mints nothing")
        assertEquals(10L, conservation(moved, listOf(p3)), "conservation restored: Σ holdings + Σ effLeafSpent = minted")
        assertTrue(moved.validate().isEmpty(), "every conflict cleared: ${moved.validate()}")
        // Idempotent: a second move finds a drained edge and does nothing.
        assertNull(moved.relocationOrNull(g), "the fenced edge reads drained — a second move is refused")
    }

    // ── §6.4 observer completeness: the conclusion never travels without its premises ────

    @Test
    fun thePublishedMoveCarriesTheBaseItCancelsSoNoObserverReadsNegativeSpend() {
        // Attack 1 of the adversarial review (§11 finding 3): an observer that receives the
        // log-published move BEFORE it gossips in the base spend it cancels used to read
        // effRollup(s) < 0 and false-fire PerEdgeSafety. The move now republishes s's base slots
        // at their acked finals in the SAME delta (the drain-witness idiom `retire` already uses),
        // so that state is unobservable.
        var truth = EntitlementLedger.ZERO.piece(EntitlementLedger.bootstrap(root, mapOf(p3 to 10L), nonce = "genesis"))
        truth = truth.piece(truth.prepare(rec(e1, root, g))!!.delta)
        truth = truth.piece(truth.activate(e1)!!.delta)
        truth = truth.piece(truth.prepare(rec(e2, g, h))!!.delta)
        truth = truth.piece(truth.activate(e2)!!.delta)
        truth = truth.piece(truth.delegate(p3, e1, 10L)!!.delta)
        truth = truth.piece(truth.delegate(p3, e2, 6L)!!.delta)
        truth = truth.piece(truth.spend(p3, h, 3L)!!.delta)
        truth = truth.piece(truth.close(e1)!!.delta)
        truth = truth.piece(EntitlementLedger.of(lifecycle = mapOf(e1 to Lifecycle.RETIRED)))
        truth = truth.piece(truth.prepare(rec(e3, root, g))!!.delta)
        truth = truth.piece(truth.activate(e3)!!.delta)
        val move = assertNotNull(truth.relocationOrNull(g))

        // A laggard holding the topology but NOT the through-spend delta, merging the move alone.
        var observer = EntitlementLedger.ZERO.piece(EntitlementLedger.bootstrap(root, mapOf(p3 to 10L), nonce = "genesis"))
        observer = observer.piece(observer.prepare(rec(e1, root, g))!!.delta)
        observer = observer.piece(observer.activate(e1)!!.delta)
        observer = observer.piece(observer.prepare(rec(e3, root, g))!!.delta)
        observer = observer.piece(observer.activate(e3)!!.delta)
        val merged = observer.piece(move)

        assertEquals(0L, merged.edge(e1)!!.spent, "e1's effective spend is zero, never negative, on the laggard")
        assertTrue(
            merged.validate().none { it is LedgerConflict.NegativeEffectiveSpend },
            "no negative effective spend is observable: ${merged.validate()}",
        )
        // And the check itself is non-vacuous: strip the covering base and it fires.
        val stripped = observer.piece(
            EntitlementLedger.of(rollupRelocOut = mapOf(e1 to GCounter.of(p3 to 3L))),
        )
        assertTrue(
            stripped.validate().contains(LedgerConflict.NegativeEffectiveSpend(e1)),
            "fixture non-vacuity: a relocOut without its base DOES report, was ${stripped.validate()}",
        )
    }

    private fun <T> permutations(items: List<T>): List<List<T>> {
        if (items.size <= 1) return listOf(items)
        val out = ArrayList<List<T>>()
        for (i in items.indices) {
            val rest = items.toMutableList().also { it.removeAt(i) }
            for (tail in permutations(rest)) out += listOf(items[i]) + tail
        }
        return out
    }
}
