package us.tractat.kuilt.heddle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * POC — THROWAWAY. Adversarial-review companion to `docs/heddle-seat-design.md` (PR #1740);
 * delete before any fix for #1713 / #1696 lands. Does NOT touch [EntitlementLedger],
 * [HeddleNode] or [HeddlePolicy]; it models candidate B's §5.1–§5.2 register with its **real
 * joins** — per-(edge, replica)-slot max for the counters (mirroring `mergeEdgeCounters`) and
 * componentwise max for the seat register — over the module's real [Rational].
 *
 * Executes the two attacks §13 names but does not run:
 *
 *  1. **§13 angle 2 — the stale-`effIssued` ratchet.** A peer whose view is missing the
 *     newborn's *own* `issued` slots (per-author delta loss the order-free join tolerates by
 *     design) keeps satisfying §5.2's write predicate after the edge has been served, and
 *     bumps the seat to a front that has moved on. Because B reads `ev = seat + committed/w`,
 *     a bump landing after `committed > 0` **double-counts the committed service**: the merged
 *     overshoot is `(front movement during the writer's staleness) + committed/w`, which is
 *     unbounded in service — not §5.3's `q/w_min`, and not either proviso (no demand churn, no
 *     `release`, zero ev-spread at the write). CFS avoids this because its max is applied to
 *     the *sum* (`vruntime = max(vruntime, min_vruntime)`, once, atomically); B's max is on
 *     the seat *addend* alone.
 *
 *  2. **§13 angle 4 — a relocation-receiving edge is unseatable.** `reconcileStranded`
 *     re-homes a strand onto a *fresh* live edge by writing `issuedRelocIn(t)`, and
 *     `effIssued = issued + issuedRelocIn` (`EntitlementLedger.kt:95`). Wherever the
 *     reconcile delta arrives before any peer has both seen `t` demanding and bumped it,
 *     §5.2's predicate `effIssued(t) == 0` is false — and since `effIssued` is monotone it
 *     stays false forever, on every peer, in every merge order. No seat is ever written, and
 *     both possible S2 read-path treatments of a missing seat are broken: *drop as candidate*
 *     starves the re-homed child permanently; *default to 0* hands it lifetime credit sized
 *     by the relocation magnitude (§10.5).
 */
class SeatDesignAdversarialPocTest {

    // ── the model: real joins, not mutable Longs ─────────────────────────────────────────

    /** Per-(edge, replica)-slot grow-only counters; join is per-slot max (`mergeEdgeCounters`). */
    private class SlotCounters {
        val slots = HashMap<String, HashMap<String, Long>>()
        fun add(edge: String, replica: String, delta: Long) {
            val row = slots.getOrPut(edge) { HashMap() }
            row[replica] = (row[replica] ?: 0L) + delta
        }
        fun total(edge: String): Long = slots[edge]?.values?.sum() ?: 0L
        fun merged(other: SlotCounters): SlotCounters {
            val out = SlotCounters()
            for (src in listOf(this, other)) {
                for ((edge, row) in src.slots) {
                    val dst = out.slots.getOrPut(edge) { HashMap() }
                    for ((r, v) in row) dst[r] = maxOf(dst[r] ?: Long.MIN_VALUE, v)
                }
            }
            return out
        }
        /** A view missing every slot of [edge] authored by [replica] — per-author delta loss. */
        fun withoutSlot(edge: String, replica: String): SlotCounters {
            val out = merged(SlotCounters())
            out.slots[edge]?.remove(replica)
            return out
        }
    }

    /** Candidate B's §5.1 register: `Map<edge, Long>`, join componentwise max. */
    private class SeatRegister {
        val slots = HashMap<String, Long>()
        fun bump(edge: String, seat: Long) {
            slots[edge] = maxOf(slots[edge] ?: Long.MIN_VALUE, seat)
        }
        fun read(edge: String): Long? = slots[edge]
        fun merged(other: SeatRegister): SeatRegister {
            val out = SeatRegister()
            for (src in listOf(this, other)) for ((k, v) in src.slots) out.bump(k, v)
            return out
        }
    }

    /** One peer's view: base issued + control-plane relocIn + the seat register. */
    private class View(
        val issued: SlotCounters = SlotCounters(),
        val relocIn: SlotCounters = SlotCounters(),
        val seats: SeatRegister = SeatRegister(),
    ) {
        /** `effIssued(e) = issued(e) + issuedRelocIn(e)` — `EntitlementLedger.kt:95`. */
        fun effIssued(edge: String): Long = issued.total(edge) + relocIn.total(edge)
        fun merged(other: View): View =
            View(issued.merged(other.issued), relocIn.merged(other.relocIn), seats.merged(other.seats))
    }

    private data class Edge(val id: String, val w: Long = 1L)

    /** `ev = seat + committed/w` — §5.1's read (`virtualService` with the register supplying the seat). */
    private fun ev(view: View, e: Edge, seatDefault: Long? = null): Rational {
        val seat = view.seats.read(e.id) ?: seatDefault ?: error("unseated edge ${e.id} has no ev")
        return Rational.of(seat) + Rational.of(view.effIssued(e.id), e.w)
    }

    private fun weightedMean(view: View, set: List<Edge>, seatDefault: Long? = null): Rational {
        var weighted = Rational.ZERO
        var weights = Rational.ZERO
        for (e in set) {
            weighted += Rational.of(e.w) * ev(view, e, seatDefault)
            weights += Rational.of(e.w)
        }
        return weighted / weights
    }

    /** §7.3 steps 1–4 at quantum [q], everyone demanding; grants recorded as [grantor]'s slots. */
    private fun run(
        view: View,
        candidates: List<Edge>,
        rounds: Int,
        grantor: String,
        q: Long = 1L,
        seatDefault: Long? = null,
    ): Map<String, Int> {
        val tally = HashMap<String, Int>()
        repeat(rounds) {
            val v = weightedMean(view, candidates, seatDefault)
            val eligible = candidates.filter { ev(view, it, seatDefault) <= v }
                .ifEmpty { listOf(candidates.minBy { ev(view, it, seatDefault) }) }
            val winner = eligible.reduce { a, b ->
                val da = ev(view, a, seatDefault) + Rational.of(q, a.w)
                val db = ev(view, b, seatDefault) + Rational.of(q, b.w)
                val cmp = da.compareTo(db)
                if (cmp < 0 || (cmp == 0 && a.id <= b.id)) a else b
            }
            view.issued.add(winner.id, grantor, q)
            tally[winner.id] = (tally[winner.id] ?: 0) + 1
        }
        return tally
    }

    // ── attack 1: §13 angle 2, executed ──────────────────────────────────────────────────

    /**
     * Grantor G runs two incumbents to committed 50 each, seats newborn `e` correctly at the
     * front (50), then serves all three fairly for 60 rounds — everyone level at ev 70,
     * `committed(e) = 20`. Peer P has merged G's slots for the *siblings* but not for `e`
     * (per-author, per-edge delta loss — legal under an order-free join before anti-entropy).
     * P sees `e` ACTIVE, demanding, `effIssued = 0`, so §5.2 **obliges** it to bump:
     * `seats[e] ← max(50, ⌈front_P⌉) = 70`.
     *
     * Merged: `ev(e) = 70 + 20 = 90` against siblings at 70 — an overshoot of 20 where §5.3
     * claims `q/w_min = 1`, with **zero** ev-spread at the write, a fixed demanding set, and no
     * `release`. The double-count is structural: the bump was computed as a *seat* (committed
     * assumed 0) but lands on an edge whose committed is 20, and the read path adds them.
     */
    @Test
    fun staleEffIssuedWriterOvershootsTheClaimedBoundTwentyfold() {
        val a = Edge("a")
        val c = Edge("c")
        val e = Edge("e")
        val g = View()
        g.seats.bump("a", 0L)
        g.seats.bump("c", 0L)

        // Incumbents run; front reaches 50.
        run(g, listOf(a, c), rounds = 100, grantor = "G")
        assertEquals(Rational.of(50L), weightedMean(g, listOf(a, c)))

        // G seats e at the true front — the correct, best-informed write. (effIssued(e) == 0 on G.)
        assertEquals(0L, g.effIssued("e"))
        g.seats.bump("e", weightedMean(g, listOf(a, c)).ceil())

        // G serves all three fairly; everyone lands level at ev 70. e has now been served.
        run(g, listOf(a, c, e), rounds = 60, grantor = "G")
        assertEquals(20L, g.effIssued("e"))
        assertEquals(Rational.of(70L), ev(g, e))
        assertEquals(Rational.of(70L), ev(g, a))

        // P's view: G's stream minus the issued(e)[G] slot — siblings fresh, e's service unseen.
        val p = View(g.issued.withoutSlot("e", "G"), SlotCounters(), g.seats.merged(SeatRegister()))
        assertEquals(0L, p.effIssued("e"), "P still reads effIssued(e) == 0 — §5.2's predicate holds")

        // §5.2 on P: e is ACTIVE, demanding, effIssued == 0 ⇒ bump to ⌈front⌉ excluding e.
        val frontP = weightedMean(p, listOf(a, c))
        val spreadAtWrite = maxOf(ev(p, a), ev(p, c)) - frontP
        assertEquals(Rational.ZERO, spreadAtWrite, "zero ev-spread: neither §5.3 proviso is in play")
        p.seats.bump("e", frontP.ceil())

        // Converge. The register keeps P's later, higher write; the read path re-adds committed.
        val merged = g.merged(p)
        assertEquals(70L, merged.seats.read("e"))
        assertEquals(Rational.of(90L), ev(merged, e), "seat 70 + committed 20 — the double count")

        val overshoot = ev(merged, e) - ev(merged, a)
        assertEquals(Rational.of(20L), overshoot, "claimed bound is q/w_min = 1; actual is 20")

        // The fairness cost is real: e is ineligible for the next 40 grants.
        val tally = run(merged, listOf(a, c, e), rounds = 40, grantor = "G")
        assertNull(tally["e"], "e is starved while the siblings climb 20 units to its inflated ev")
    }

    /**
     * The overshoot grows with the staleness window. Writer P is one-way partitioned: it keeps
     * *receiving* the siblings' slots (its front stays fresh) but its own writes — and its view
     * of `e`'s slots — don't circulate, so G keeps serving `e` fairly the whole time. Every
     * §5.2 round P re-bumps its local register to the advancing front. At heal, the merged seat
     * is the front at heal-time, and the read path re-adds *all* the service `e` received in
     * the window: the overshoot equals `committed(e)` at heal — linear in the window length.
     * The §5.3 bound is per-view and per-*time*; the register maxes over both axes, and only
     * the view axis was bounded.
     */
    @Test
    fun theOvershootGrowsWithTheStalenessWindow() {
        fun overshootAfterWindow(windowRounds: Int): Long {
            val a = Edge("a")
            val c = Edge("c")
            val e = Edge("e")
            val g = View()
            g.seats.bump("a", 0L)
            g.seats.bump("c", 0L)
            run(g, listOf(a, c), rounds = 100, grantor = "G")
            g.seats.bump("e", 50L) // correctly seated at the front

            // The window: G serves all three fairly; P re-bumps its local register each round
            // from a view with fresh siblings and a permanently-stale effIssued(e) = 0.
            val pSeats = SeatRegister()
            repeat(windowRounds) {
                run(g, listOf(a, c, e), rounds = 1, grantor = "G")
                val p = View(g.issued.withoutSlot("e", "G"), SlotCounters(), g.seats.merged(SeatRegister()))
                pSeats.bump("e", weightedMean(p, listOf(a, c)).ceil())
            }

            // Heal: P's accumulated register merges in.
            val healed = g.merged(View(SlotCounters(), SlotCounters(), pSeats))
            return (ev(healed, e) - ev(healed, a)).ceil()
        }

        val short = overshootAfterWindow(30)
        val long = overshootAfterWindow(120)
        assertTrue(short >= 9L, "a 30-round window already overshoots ~e's committed (~10), got $short")
        assertTrue(long >= 3 * short, "4x the window ⇒ ~4x the overshoot: $short → $long — unbounded, not q/w_min")
    }

    // ── attack 2: §13 angle 4, executed ──────────────────────────────────────────────────

    /**
     * The #1665 flow: child X's retired edge strands 300 units; X is re-parented onto a fresh
     * edge `t` (`issued(t) = 0` — `EntitlementLedger.kt:497`), and `reconcileStranded` writes
     * `issuedRelocIn(t) = 300`. The reconcile is a control-plane act published synchronously at
     * apply; `t`'s *demand* rides the separate demand-board seam. Wherever the reconcile delta
     * wins that race (the common case — the child cannot usefully demand before its credit
     * arrives), every peer's first chance to bump `t` already reads `effIssued(t) = 300 > 0`:
     * §5.2's predicate is false, and being monotone it is false **forever, everywhere, in every
     * merge order**. `seats[t]` is never written. Both S2 readings of an absent seat then fail:
     * dropping `t` as a candidate starves the re-homed child permanently; defaulting the seat
     * to 0 turns the relocation magnitude into committed service at the origin — lifetime
     * credit sized by the strand (§10.5).
     */
    @Test
    fun aRelocationReceivingEdgeIsNeverSeatedAndBothReadPathsBreak() {
        val a = Edge("a")
        val c = Edge("c")
        val t = Edge("t") // the fresh live edge X was re-parented onto
        val view = View()
        view.seats.bump("a", 0L)
        view.seats.bump("c", 0L)
        run(view, listOf(a, c), rounds = 800, grantor = "G") // mature parent: front = 400

        // The reconcile lands: issuedRelocIn(t) = 300, before any peer saw t demanding.
        view.relocIn.add("t", "L", 300L)

        // §5.2's predicate is closed on every peer from its first opportunity, permanently.
        assertEquals(300L, view.effIssued("t"))
        assertNull(view.seats.read("t"), "no peer ever wrote a seat — the window never existed")

        // Read path (i): unseated ⇒ dropped as a candidate. The child #1665 exists to make
        // whole can never be scheduled again — and no future write can fix it.
        val tallyDropped = run(view, listOf(a, c), rounds = 60, grantor = "G")
        assertNull(tallyDropped["t"], "t is not schedulable, while demanding, forever")

        // Read path (ii): absent seat reads 0 ⇒ ev(t) = 0 + 300/1 = 300 against a front of 400+.
        // t reads ~100 units *behind* — §10.5 lifetime credit sized by the relocated strand —
        // and takes every next grant outright.
        val evT = ev(view, t, seatDefault = 0L)
        assertEquals(Rational.of(300L), evT)
        assertTrue(evT < weightedMean(view, listOf(a, c)), "t reads deep behind the front")
        val tallyDefaulted = run(view, listOf(a, c, t), rounds = 30, grantor = "G", seatDefault = 0L)
        assertEquals(30, tallyDefaulted["t"], "t takes every grant — the §10.5 violation, relocation-sized")
    }

    // ── positive control: the clean-regime bound is real ─────────────────────────────────

    /**
     * Fairness to §5.3: when every writer's view of the *newborn's own* counters is fresh
     * (`committed(e) = 0` is true, not stale), each bump is a weighted mean over sibling evs
     * and the merged register stays within `q/w_min` of the converged front — the design's
     * bound holds in the regime it was derived for. The discriminator between this test and
     * the two above is exactly one thing: whether the writer's `effIssued(e) == 0` reading is
     * *true* or merely *stale*. The predicate cannot tell the difference, and that is the hole.
     */
    @Test
    fun freshWritersStayWithinTheClaimedBound() {
        val a = Edge("a")
        val c = Edge("c", w = 3L)
        val e = Edge("e")
        val g = View()
        g.seats.bump("a", 0L)
        g.seats.bump("c", 0L)
        run(g, listOf(a, c), rounds = 200, grantor = "G")

        // Several writers bump before e is ever served; each sees a (possibly partial) sibling
        // view but a *true* effIssued(e) == 0. Partial-sibling views are bounded by max ev.
        val register = SeatRegister()
        register.bump("e", weightedMean(g, listOf(a, c)).ceil()) // full view
        register.bump("e", ev(g, a).ceil()) // degenerate view: one sibling only
        register.bump("e", ev(g, c).ceil())

        val v = weightedMean(g, listOf(a, c))
        val bound = Rational.of(1L, minOf(a.w, c.w)) + Rational.of(1L) // q/w_min + ceil rounding
        val stored = Rational.of(register.read("e")!!)
        assertTrue(stored - v <= bound, "clean-regime overshoot ${stored - v} within q/w_min + 1")
    }
}
