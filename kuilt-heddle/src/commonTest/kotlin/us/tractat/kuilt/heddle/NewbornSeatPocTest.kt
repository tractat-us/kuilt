package us.tractat.kuilt.heddle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * POC — THROWAWAY. Not part of the module design; delete before any fix for #1713 / #1696
 * lands. Companion to `docs/heddle-seat-design.md`.
 *
 * A **self-contained EEVDF model** — it does NOT touch [EntitlementLedger], [HeddleNode] or
 * [HeddlePolicy]; it re-implements §7.3's four steps over the module's real [Rational] so the
 * arithmetic is exact and the numbers are checkable by hand. It exists to settle four
 * questions the two issues leave open:
 *
 *  1. **Re-derive #1696's measurement** without its (never-landed) `Inv1688Test` harness:
 *     at consensus lag 25 the newborn's deficit is exactly `12.5` virtual units and it takes
 *     **18/30** grants instead of a fair 10/30.
 *  2. **Candidate A is unsound.** Making `initialVirtualTime` a constant and materialising the
 *     seat as a scheduler-local wake offset loses the seat on restart (the offset map is
 *     per-boot, in-memory) and on late join. The model shows a restarted peer then reads a
 *     long-running sibling and a recent newborn in the *wrong order* and hands the newborn
 *     every grant — the §10.5 lifetime-credit violation, now guaranteed rather than rare.
 *  3. **Candidate B is sound and self-correcting.** A per-edge seat held as a replicated
 *     max-register, written by every peer that still sees `issued == 0`, converges to the
 *     best-informed reading, survives restart, and is monotone in the safe direction.
 *  4. **The front-gap is bounded.** A max over peers' locally-computed weighted means can
 *     only exceed the true front by the ev-spread of the competing set, which EEVDF's own
 *     eligibility rule bounds by one quantum at the smallest weight — the same order as
 *     #1687's `ceil` bias.
 *
 * **Revision 2 — read this before trusting point 4.** The adversarial pass recorded in
 * §11 of the design refuted §5.2's write gate, and with it the *inference* from point 4 to
 * "B's seat overshoot is bounded". The bound above is real but is a statement about the
 * **front**, not about the stored **seat**: once the `effIssued == 0` write gate misfires
 * on an already-served edge, the additive read path `ev = seat + committed/w` double-counts
 * and the overshoot is linear in the staleness window. See
 * `review/1713-seat-design-adversarial` → `SeatDesignAdversarialPocTest.kt`. Points 1–3 are
 * unaffected — they concern *where* the seat must live, which survived review.
 */
class NewbornSeatPocTest {

    // ── the model ────────────────────────────────────────────────────────────────────────

    /**
     * One child edge as a scheduler sees it. [seat] is whatever mechanism supplies the
     * virtual-time origin (today: the record's `initialVirtualTime`; under A: a local offset;
     * under B: the replicated max-register), [committed] is `issued − returned`, [w] the weight.
     */
    private data class Child(val id: String, var seat: Rational, var committed: Long = 0L, val w: Long = 1L) {
        val ev: Rational get() = seat + Rational.of(committed, w)
    }

    /** `V = Σ w·ev / Σ w` over [set] — §7.3 step 2 / `HeddlePolicy.front`'s shared helper. */
    private fun weightedMean(set: List<Child>): Rational {
        var weighted = Rational.ZERO
        var weights = Rational.ZERO
        for (c in set) {
            val w = Rational.of(c.w)
            weighted += w * c.ev
            weights += w
        }
        return weighted / weights
    }

    /** §7.3 steps 1–4, quantum [q] flat, every child demanding. Returns the winner. */
    private fun pick(children: List<Child>, q: Long): Child {
        val v = weightedMean(children)
        val eligible = children.filter { it.ev <= v }.ifEmpty { listOf(children.minBy { it.ev }) }
        return eligible.reduce { a, b ->
            val da = a.ev + Rational.of(q, a.w)
            val db = b.ev + Rational.of(q, b.w)
            val byDeadline = da.compareTo(db)
            if (byDeadline < 0 || (byDeadline == 0 && a.id <= b.id)) a else b
        }
    }

    /** Run [rounds] grants of [q] over [children], returning the grant count per child id. */
    private fun run(children: List<Child>, rounds: Int, q: Long = 1L): Map<String, Int> {
        val tally = HashMap<String, Int>()
        repeat(rounds) {
            val winner = pick(children, q)
            winner.committed += q
            tally[winner.id] = (tally[winner.id] ?: 0) + 1
        }
        return tally
    }

    // ── 1. #1696's measurement, re-derived ───────────────────────────────────────────────

    /**
     * The #1696 scenario: a parent with **two** incumbent children, unit weights, quantum 1.
     * `V` is sampled at propose; the newborn only competes after `activate` commits. During the
     * `lag` rounds in between, the parent renders `lag` units of service split between the two
     * incumbents, so the real front advances by `lag / 2` while the frozen seat does not.
     *
     * At `lag = 25`: deficit `= 12.5`, and the newborn then takes **18** of the next 30 grants.
     * That reproduces #1696's headline numbers exactly, from first principles.
     */
    @Test
    fun consensusLagDeficitAndGrantShareMatchIssue1696() {
        val lag = 25
        val a = Child("a", Rational.ZERO)
        val c = Child("c", Rational.ZERO)

        // Propose: sample the front over the two incumbents.
        val sampledFront = weightedMean(listOf(a, c))

        // propose → activate: the parent keeps serving. The newborn is not an edge yet.
        run(listOf(a, c), rounds = lag)
        val realFrontAtActivate = weightedMean(listOf(a, c))

        val deficit = realFrontAtActivate - sampledFront
        assertEquals(Rational.of(25L, 2L), deficit, "lag 25 over two incumbents ⇒ deficit 12.5")

        // The newborn activates seated at the STALE sample (this is the bug).
        val newborn = Child("newborn", sampledFront)
        val tally = run(listOf(a, c, newborn), rounds = 30)

        assertEquals(18, tally["newborn"], "#1696's measured 18/30 (fair share is 10/30)")
        assertEquals(6, tally["a"])
        assertEquals(6, tally["c"])
    }

    /** The same scenario at zero lag is fair — the error term really is the lag, not the rounding. */
    @Test
    fun zeroLagIsFair() {
        val a = Child("a", Rational.ZERO)
        val c = Child("c", Rational.ZERO)
        val newborn = Child("newborn", weightedMean(listOf(a, c)))
        val tally = run(listOf(a, c, newborn), rounds = 30)
        assertEquals(10, tally["newborn"], "at lag 0 the newborn takes its fair 10/30")
    }

    /**
     * The **correct** seat — the front as it stands when the newborn actually starts competing —
     * is fair regardless of how long propose→activate took. This is the target every candidate
     * is measured against.
     */
    @Test
    fun seatingAtTheFrontOfTheSetItActuallyJoinsIsFairAtAnyLag() {
        for (lag in listOf(0, 5, 25, 200)) {
            val a = Child("a", Rational.ZERO)
            val c = Child("c", Rational.ZERO)
            run(listOf(a, c), rounds = lag)
            val newborn = Child("newborn", weightedMean(listOf(a, c))) // seated at activate, not propose
            val tally = run(listOf(a, c, newborn), rounds = 30)
            assertEquals(10, tally["newborn"], "lag $lag: seating at the live front is fair")
        }
    }

    // ── 2. Candidate A: the seat is not recomputable ─────────────────────────────────────

    /**
     * **The decisive finding against A.** Under A, `initialVirtualTime` is a constant `0` and the
     * effective seat lives in `HeddleNode.wakeOffsets` — a per-boot, in-memory `HashMap`, never
     * replicated and never persisted. A peer that restarts (or joins late) therefore has *no*
     * seat for any edge and reads `ev = committed / w` for all of them.
     *
     * Set up a parent with a long-running child seated at the origin and a much later newborn
     * legitimately seated at the front. Both are correct on a peer that witnessed the seating.
     * On a restarted peer under A, the newborn's 1000 units of seat evaporate and it reads as
     * 995 units *behind* — so it takes every one of the next 30 grants. That is precisely the
     * lifetime credit §10.5 forbids, now certain on every restart rather than rare on an
     * unconverged proposer.
     */
    @Test
    fun candidateALosesTheSeatOnRestartAndHandsTheNewbornLifetimeCredit() {
        // Ground truth: `old` was seated at the origin and has run a long time; `recent` was
        // seated at the front (1000) and has barely run. They are level-ish; `old` is ahead.
        fun witnessed() = listOf(Child("old", Rational.ZERO, committed = 1000L), Child("recent", Rational.of(1000L), committed = 5L))
        // Under A after a restart: seat ≡ 0 for everything, offsets gone.
        fun restartedUnderA() = listOf(Child("old", Rational.ZERO, committed = 1000L), Child("recent", Rational.ZERO, committed = 5L))

        val fair = run(witnessed(), rounds = 30)
        val scrambled = run(restartedUnderA(), rounds = 30)

        // Ground truth: `recent` is 5 units AHEAD (ev 1005 vs 1000), so `old` takes the first 6
        // grants to draw level and the remaining 24 alternate — 18/12, near the fair 15/15.
        assertEquals(12, fair["recent"], "a peer that witnessed the seating splits near-evenly")
        assertEquals(18, fair["old"])
        assertEquals(
            30,
            scrambled["recent"],
            "under A a restarted peer gives the newer child EVERY grant — the seat is unreconstructible",
        )
    }

    /**
     * The same failure without a restart: a peer that joins the mesh after the seating has
     * never held the offset either. Under A the seat is knowledge only the witnessing peer has;
     * under today's representation (and under B) it is a replicated fact anyone can read.
     */
    @Test
    fun candidateAsSeatIsKnowledgeALateJoinerCanNeverObtain() {
        val lateJoinerView = listOf(Child("old", Rational.ZERO, committed = 1000L), Child("recent", Rational.ZERO, committed = 5L))
        val v = weightedMean(lateJoinerView)
        assertTrue(
            lateJoinerView.first { it.id == "recent" }.ev < v,
            "the newborn reads as eligible-and-behind forever, with nothing in the replicated " +
                "state that could ever correct it",
        )
    }

    // ── 3. Candidate B: a replicated max-register seat ───────────────────────────────────

    /**
     * B's seat register modelled with its real join: a per-edge `Map<AttachmentId, Long>` whose
     * merge is `maxOf` — structurally identical to the ledger's existing `lifecycle` register.
     * Every peer that still observes `issued(e) == 0` bumps the slot with its own `⌈front⌉`
     * (#1687's rounding rule, which B keeps), so the value the edge finally carries is the
     * **best-informed** reading, not the proposer's.
     */
    private class SeatRegister {
        private val slots = HashMap<String, Long>()
        fun bump(edge: String, seat: Long) {
            slots[edge] = maxOf(slots[edge] ?: Long.MIN_VALUE, seat)
        }
        fun read(edge: String): Long? = slots[edge]
        /** Componentwise max-join of two independently-merged replicas. */
        fun mergedWith(other: SeatRegister): SeatRegister {
            val out = SeatRegister()
            for ((k, v) in slots) out.bump(k, v)
            for ((k, v) in other.slots) out.bump(k, v)
            return out
        }
    }

    /**
     * **B self-corrects a stale proposer.** A proposer that has merged three of five siblings
     * computes a plausible-but-low front and freezes it — the case #1713 names as *worse* than a
     * null, because nothing looks anomalous. Under B that reading is not authoritative: it is one
     * bump into a max-register, and any better-informed peer's bump dominates it. The seat the
     * newborn ends up carrying is the converged front, and the error is transient rather than
     * permanent.
     */
    @Test
    fun candidateBLetsABetterInformedPeerDominateAStaleProposersSeat() {
        // Five siblings, unevenly advanced — s3/s4 have been served an order of magnitude more.
        // The dangerous partial view is the one missing the AHEAD siblings: it reads a low front
        // and hands the newborn lifetime credit (§10.5). That is the direction max-join fixes.
        val siblings = listOf(
            Child("s0", Rational.ZERO, committed = 10L),
            Child("s1", Rational.ZERO, committed = 10L),
            Child("s2", Rational.ZERO, committed = 10L),
            Child("s3", Rational.ZERO, committed = 100L),
            Child("s4", Rational.ZERO, committed = 100L),
        )
        val convergedFront = weightedMean(siblings)

        // The stale proposer sees only s0..s2 — a plausible front over three of five.
        val staleFront = weightedMean(siblings.take(3))
        val proposer = SeatRegister().apply { bump("newborn", staleFront.ceil()) }
        // A converged peer, still seeing issued(newborn) == 0, bumps its own reading.
        val converged = SeatRegister().apply { bump("newborn", convergedFront.ceil()) }

        val merged = proposer.mergedWith(converged)
        assertEquals(
            convergedFront.ceil(),
            merged.read("newborn"),
            "max-join keeps the best-informed reading; the stale carry is dominated, not frozen",
        )
        assertTrue(staleFront < convergedFront, "the stale view really was low — a live error, not a strawman")
        // Order of merge is irrelevant (the join is commutative), which is the whole point.
        assertEquals(merged.read("newborn"), converged.mergedWith(proposer).read("newborn"))
    }

    /** B's seat survives a restart, because it lives in the replicated ledger, not in a HashMap. */
    @Test
    fun candidateBsSeatSurvivesRestartAndLateJoin() {
        val register = SeatRegister().apply {
            bump("old", 0L)
            bump("recent", 1000L)
        }
        // A restarted / freshly-joined peer merges the ledger and reads both seats back.
        val rebuilt = SeatRegister().mergedWith(register)
        val children = listOf(
            Child("old", Rational.of(rebuilt.read("old")!!), committed = 1000L),
            Child("recent", Rational.of(rebuilt.read("recent")!!), committed = 5L),
        )
        val tally = run(children, rounds = 30)
        assertEquals(12, tally["recent"], "restart-safe: the same 12/30 a witnessing peer computes, not 30/30")
    }

    /**
     * **The residual B does NOT close, stated honestly.** Max-join only dominates a *low* stale
     * reading. A view that is stale in the other direction — missing the siblings that are
     * *behind* — reads a front that is too high, and the join keeps it. That is the §10.5-safe
     * direction (the newborn gives up a share, it never claims one), and it is the same
     * one-directional trade `ceil` (#1687) and `HeddlePolicy.front`'s max fallback already make.
     * It is a residual, not a hole — but it must be written down, not discovered later.
     */
    @Test
    fun candidateBDoesNotCorrectAStaleHighSeatAndThatIsTheSafeDirection() {
        val siblings = listOf(
            Child("s0", Rational.ZERO, committed = 100L),
            Child("s1", Rational.ZERO, committed = 100L),
            Child("s2", Rational.ZERO, committed = 10L), // a laggard the stale view has not merged
        )
        val convergedFront = weightedMean(siblings)
        val staleHighFront = weightedMean(siblings.take(2))
        assertTrue(staleHighFront > convergedFront, "the partial view really does read high")

        val merged = SeatRegister().apply { bump("newborn", staleHighFront.ceil()) }
            .mergedWith(SeatRegister().apply { bump("newborn", convergedFront.ceil()) })
        assertEquals(
            staleHighFront.ceil(),
            merged.read("newborn"),
            "max-join keeps the HIGH reading — a bounded penalty on the newborn, never credit",
        )
    }

    // ── 4. B's ratchet bias is bounded ───────────────────────────────────────────────────

    /**
     * **The front-gap bound — true, and narrower than revision 1 claimed.** Any peer's front is a
     * *weighted mean* over that peer's demanding set. It can sit above the converged mean —
     * penalising a newborn — but never above the largest `ev` in the competing set, because a
     * weighted mean is bounded by its maximum element.
     *
     * EEVDF then bounds that spread for free: eligibility serves only children with `ev ≤ V`, and
     * a grant advances the winner by `q / w`, so no child ever sits more than `q / w` above the
     * mean in a converged steady state. Hence `0 ≤ anyPeersFront − V ≤ q / w_min` — the same order
     * as #1687's `0 ≤ ⌈V⌉ − V < 1`, and in the §10.5-safe direction.
     *
     * **It does NOT bound the stored seat's error** — revision 1 inferred that and the adversarial
     * pass falsified it. The seat's error is `front_at_write − seat_correct`, and §5.2's write gate
     * lets that grow with the edge's own served history. Renamed accordingly; the assertion below
     * is unchanged and still correct.
     */
    @Test
    fun anyPeersFrontIsWithinOneQuantumAtTheSmallestWeightOfTheTrueFront() {
        val q = 1L
        val children = listOf(
            Child("a", Rational.ZERO, w = 1L),
            Child("b", Rational.ZERO, w = 2L),
            Child("c", Rational.ZERO, w = 3L),
        )
        run(children, rounds = 200, q = q)

        val v = weightedMean(children)
        val maxEv = children.maxOf { it.ev }
        val smallestWeight = children.minOf { it.w }
        val bound = Rational.of(q, smallestWeight)

        // Every peer's reading is a weighted mean over some subset ⇒ never above maxEv.
        for (subset in children.indices.map { i -> children.filterIndexed { j, _ -> j != i } }) {
            assertTrue(weightedMean(subset) <= maxEv, "a weighted mean never exceeds its maximum element")
        }
        assertTrue(maxEv - v <= bound, "converged ev-spread ${maxEv - v} exceeds the q/w_min bound $bound")
    }

    /**
     * The ratchet is monotone and one-directional: a seat can only ever be raised, so a newborn
     * can only ever give up a share, never claim one. That is the same sign discipline #1687
     * established for `ceil` and `HeddlePolicy.front`'s max fallback, and it is why B is safe to
     * let every peer write.
     */
    @Test
    fun candidateBsSeatOnlyEverRises() {
        val register = SeatRegister()
        val readings = listOf(40L, 12L, 37L, 5L, 41L, 41L, 9L)
        var previous = Long.MIN_VALUE
        for (r in readings) {
            register.bump("e", r)
            val now = register.read("e")!!
            assertTrue(now >= previous, "the seat regressed: $previous → $now")
            previous = now
        }
        assertEquals(41L, previous, "the seat settles at the highest reading any peer ever had")
    }
}
