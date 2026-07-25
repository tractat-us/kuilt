package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece
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

    // ── the structural guarantee: no contended slot exists ───────────────────────────────

    @Test
    fun rehomeWitnessNeverWritesABaseIssuedSlot() {
        val l = strandedNoThroughService(mapOf(p3 to 25L), listOf(p3 to 10L), listOf(p3 to 6L))
        val patch = l.reconcileStranded(g)
        assertNotNull(patch, "there is a strand to reconcile")
        // §6.3 slot-ownership: base counters on a LIVE edge are the data plane's exclusively.
        // The re-home's credit must ride a control-plane-owned relocation counter instead, so
        // the erasure of finding 1 has no slot to happen on.
        assertTrue(
            patch.delta.issuedEdges().isEmpty(),
            "the re-home must not write any base `issued` slot (it writes issuedReloc.in on the live edge); wrote ${patch.delta.issuedEdges()}",
        )
        // It still drains the retired edge through the ordinary (fenced, uncontended) base slot.
        assertEquals(setOf(e1), patch.delta.returnedEdges(), "the drain of the retired edge stays a base `returned` write")
    }

    // ── finding 1, single replica: the concurrent delegate and the re-home both survive ──

    @Test
    fun concurrentDelegateDownTheLiveEdgeDoesNotEraseTheRehome() {
        val l = strandedNoThroughService(mapOf(p3 to 25L), listOf(p3 to 10L), listOf(p3 to 6L))
        val rehome = assertNotNull(l.reconcileStranded(g), "there is a strand to reconcile")
        // Concurrently — on p3's own pre-reconcile view, which is feasible: holdings(root,p3) = 15.
        val delegate = assertNotNull(l.delegate(p3, e3, 12L), "the ordinary data-plane delegate is feasible")

        val converged = l.piece(rehome.delta).piece(delegate.delta)

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
        val rehome = assertNotNull(l.reconcileStranded(g))
        val delegate = assertNotNull(l.delegate(p3, e3, 8L))
        val converged = l.piece(rehome.delta).piece(delegate.delta)
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

        val rehome = assertNotNull(l.reconcileStranded(g), "a two-replica strand is re-homable")
        // Two independent data-plane writers race the re-home on the live edge — one with a
        // delegate SMALLER than its re-home (p3: 3 vs 10), one LARGER (q: 7 vs 6). Under the
        // old base-`issued` witness p3 lost its delegate and q lost its relocation.
        val delegateP3 = assertNotNull(l.delegate(p3, e3, 3L))
        val delegateQ = assertNotNull(l.delegate(q, e3, 7L))

        val converged = l.piece(rehome.delta).piece(delegateP3.delta).piece(delegateQ.delta)

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
        val deltas: List<Patch<EntitlementLedger>> = listOf(
            assertNotNull(l.reconcileStranded(g)),
            assertNotNull(l.delegate(p3, e3, 3L)),
            assertNotNull(l.delegate(q, e3, 7L)),
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
                acc = acc.piece(deltas[i].delta)
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
        for (d in deltas) doubled = doubled.piece(d.delta)
        assertEquals(converged, doubled, "re-delivery must be a no-op (max-join idempotence)")
    }

    // ── slice 1 fails closed: through-service relocation is NOT un-gated here ────────────

    @Test
    fun throughServiceStrandStaysRefusedAndShipsNoRelocation() {
        // Same strand, but service was spent THROUGH e1 before the reparent. Relocating that
        // already-charged spend needs the §6 fence (slices 2–3); until then reconcile must
        // fail closed, leaving the conflicts standing rather than moving the wrong magnitude.
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
        assertNull(l.reconcileStranded(g), "through-service relocation stays REFUSED in slice 1 — the fence is not built")
        assertTrue(
            l.validate().contains(LedgerConflict.PersistentNegativeHoldings(g, p3)),
            "the refusal leaves the pre-existing conflicts standing (recoverable), never a silent break",
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
