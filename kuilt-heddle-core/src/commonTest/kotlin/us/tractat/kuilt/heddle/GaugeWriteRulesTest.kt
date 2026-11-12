package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two [Gauge] write rules under adversarial delivery (issue #1752): a checkpoint written
 * **inside [EntitlementLedger.delegate]'s own patch**, and a seat bump keyed on **gauge absence**.
 * These are the regression vectors for F1 and F2, ported from the design model
 * (`model/1752-gauge-seat-poc`, commit `ff8c79e2`) onto the real ledger.
 *
 * ## What makes these tests adversarial rather than decorative
 *
 * Delivery here is **patch-granular**, mirroring `Patch<EntitlementLedger>` / `Quilter`'s per-patch
 * deltas: every mutation publishes one lattice element into a [World], and a peer's view is the
 * join of an arbitrary *subset* of what has been published. That is what lets a test construct the
 * exact partial view F1 needs — a peer that has seen an edge's siblings but none of the edge's own
 * service — without inventing a transport story.
 *
 * The topology (`prepare`/`activate`) and the mint are published as `base` and included in every
 * view: they arrive through consensus, not gossip, and #1700's fence already covers the stale-record
 * case. What can go missing is the per-edge counter and gauge traffic, which is exactly the
 * unfenced partial-view case this issue tracks.
 *
 * ## The scheduler here is the real one
 *
 * [Peer.serve] drives [HeddlePolicy.pick] and [HeddlePolicy.front] against the peer's own view,
 * with `HeddleNode.seatUnseated`'s bump-before-grant rule in front of them. It used to be a
 * stand-in that reimplemented §7.3's front/eligibility/deadline arithmetic over
 * [EntitlementLedger.grossVirtualService], because the policy still read the record's retired
 * `initialVirtualTime` and could not see a gauge at all; the debt was recorded here and discharged
 * when the read path was rewired (#1752). What remains modelled is only the *transport* — [World]'s
 * patch-granular delivery — which is the point of the suite.
 */
class GaugeWriteRulesTest {

    // ── F1: the stale seat bump is deflated by the folded checkpoint ──────────────────────

    /**
     * **The F1 replay, on the numbers the refuted design failed.** `G` runs two incumbents to a
     * front of 50, seats newborn `e` there, then serves all three fairly for 60 rounds — everyone
     * level at 70, with `baseIssued(e) = 20`.
     *
     * Stale peer `P`'s view is missing every patch touching `e`. Because the checkpoint rides the
     * delegate patch, that loss removes `e`'s counters **and** `e`'s gauge together — the
     * atomicity invariant, expressed as a property of what a view can even be. `P` therefore reads
     * a gauge-absent `e` and re-bumps it to its own front of 70, the §5.2 misfire. But its bump
     * folds its own observed issuance (`0`), so at heal the componentwise join pairs that floor
     * with `G`'s honest fold of `20` and the bump vanishes.
     *
     * Refuted design measured **20** here. This measures **0**.
     */
    @Test
    fun f1StaleBumpIsDeflatedToZeroOvershoot() {
        val sim = Sim("a", "c", "e")
        val g = sim.peer("G")

        g.serve(listOf("a", "c"), rounds = 100)
        assertEquals(Rational.of(50L), g.front(listOf("a", "c")), "incumbents converge to a front of 50")

        g.serve(listOf("a", "c", "e"), rounds = 60)
        assertAll(
            { assertEquals(20L, g.baseIssued("e"), "e has been served 20 units") },
            { assertEquals(Rational.of(70L), g.grossEv("e")) },
            { assertEquals(Rational.of(70L), g.grossEv("a"), "…level with its siblings") },
        )

        // P: siblings fresh, e's own traffic entirely unseen — counters AND gauge, together.
        val p = sim.peer("P", seeing = { it != "e" })
        assertAll(
            { assertEquals(0L, p.baseIssued("e"), "P has no view of e's service") },
            { assertNull(p.gauge("e"), "and no gauge — the loss is patch-atomic") },
        )

        // P bumps e to ITS front. Under the refuted design this double-counted.
        assertTrue(p.seatIfAbsent("e", p.front(listOf("a", "c"))), "the misfire does fire")

        val healed = sim.world.full()
        assertAll(
            {
                assertEquals(
                    Rational.of(70L),
                    healed.grossVirtualService(sim.id("e")),
                    "the folded checkpoint deflates the stale bump",
                )
            },
            {
                assertEquals(
                    Rational.ZERO,
                    sim.overshoot(healed, "e", "a"),
                    "refuted design measured 20 here; the gauge measures 0",
                )
            },
        )

        // And e is not starved by the deflation: the next 30 grants split three ways again.
        val healedPeer = sim.peer("G2")
        val tally = healedPeer.serve(listOf("a", "c", "e"), rounds = 30)
        assertEquals(10, tally["e"], "e takes its fair third after the heal")
    }

    /**
     * **The window-scaling attack.** A fresh stale writer every round, each missing every patch
     * touching `e` and each bumping to the *advancing* front. Under the refuted design the merged
     * overshoot was linear in the staleness window — roughly 10 at 30 rounds and 40 at 120. Here
     * the grantor's per-grant checkpoints advance the floor alongside the front, so every stale
     * bump is dominated or deflated.
     *
     * The assertion is **flatness**, not a magnitude: a bound that merely held at both ends would
     * pass for a design that scaled between them.
     */
    @Test
    fun overshootDoesNotScaleWithTheStalenessWindow() {
        fun overshootAfterWindow(windowRounds: Int): Rational {
            val sim = Sim("a", "c", "e")
            val g = sim.peer("G")
            g.serve(listOf("a", "c"), rounds = 100)
            g.serve(listOf("a", "c", "e"), rounds = 1) // seat, then the first grant
            repeat(windowRounds) { i ->
                g.serve(listOf("a", "c", "e"), rounds = 1)
                val stale = sim.peer("P$i", seeing = { it != "e" })
                stale.seatIfAbsent("e", stale.front(listOf("a", "c")))
            }
            return sim.overshoot(sim.world.full(), "e", "a")
        }

        val short = overshootAfterWindow(30)
        val long = overshootAfterWindow(120)
        assertAll(
            { assertTrue(short <= Rational.ONE, "30-round window: overshoot $short ≤ 1 (refuted design: ~10)") },
            { assertTrue(long <= Rational.ONE, "120-round window: overshoot $long ≤ 1 (refuted design: ~40)") },
            { assertEquals(short, long, "flat in the window, not linear — a 4× window must not scale it") },
        )
    }

    // ── the negative control: split the patch and F1 comes straight back ─────────────────

    /**
     * **The experiment that proves the same-patch rule is load-bearing.** Republish `G`'s counter
     * bumps for `e` *without* their checkpoint halves — the patch split that
     * [EntitlementLedger.delegate] forbids by writing the checkpoint into its own patch. A view can
     * now hold `baseIssued(e) = 20` against a gauge whose best fold is `0`, and `P`'s stale bump
     * double-counts.
     *
     * This must reproduce the refuted design's **exact 20**. If it ever goes green at 0, the
     * atomicity rule has stopped being enforced and this control is the only thing that would
     * notice — every other test in this file passes either way, because they never construct the
     * split state.
     */
    @Test
    fun atomicityViolationControlReintroducesTheDoubleCount() {
        val sim = Sim("a", "c", "e")
        val g = sim.peer("G")
        g.serve(listOf("a", "c"), rounds = 100)
        g.serve(listOf("a", "c", "e"), rounds = 60)

        // Violate atomicity: strip the gauge from every patch touching e; counters survive.
        val split = sim.world.mapping { touches, delta ->
            if (touches == "e") delta.withoutGauges() else delta
        }
        val p = Peer("P", split, sim, seeing = { it != "e" })
        assertAll(
            { assertEquals(0L, p.baseIssued("e"), "P still sees none of e's service") },
            {
                assertEquals(
                    20L,
                    split.full().edge(sim.id("e"))?.issued,
                    "…while the split world's counters survived the strip",
                )
            },
            { assertNull(split.full().gauge(sim.id("e")), "and its checkpoints did not") },
        )

        p.seatIfAbsent("e", p.front(listOf("a", "c")))
        assertEquals(
            Rational.of(20L),
            sim.overshoot(split.full(), "e", "a"),
            "without same-patch checkpoints, F1's exact 20 returns",
        )

        // The half-fallback that still holds: a bumper which HAS the counters folds them itself,
        // so even in the split world that bump is self-deflating. The rule protects the *receiver*
        // of a split patch, not the writer.
        val split2 = sim.world.mapping { touches, delta ->
            if (touches == "e") delta.withoutGauges() else delta
        }
        val informed = Peer("P2", split2, sim, seeing = { true })
        informed.seatIfAbsent("e", informed.front(listOf("a", "c")))
        assertEquals(
            Rational.ZERO,
            sim.overshoot(split2.full(), "e", "a"),
            "a bump always folds the writer's own observed issuance, so this one self-deflates",
        )
    }

    // ── F2: the relocation-receiving edge, both arrival orders ───────────────────────────

    /**
     * **F2, on the #1665 flow.** `reconcileStranded` re-homes a strand onto fresh edge `t` by
     * writing `issuedRelocIn(t)`. Two things make `t` seatable and stable where the refuted design
     * left it permanently starved: the gauge's fold axis is **base** issuance, so the relocated
     * magnitude is not a term of the read at all; and the seat bump keys on gauge *absence*, not on
     * `effIssued == 0` — which `issuedRelocIn` falsifies forever, in every merge order.
     *
     * Both arrival orders must produce a **byte-identical register**, which is the structural
     * statement: a reconcile patch touches no term of the seat, so its position cannot matter.
     */
    @Test
    fun relocationReceivingEdgeSeatsIdenticallyInBothArrivalOrders() {
        fun scenario(reconcileFirst: Boolean): Triple<Gauge, Rational, Map<String, Int>> {
            val sim = Sim("a", "c", "t")
            val g = sim.peer("G")
            g.serve(listOf("a", "c"), rounds = 800) // mature parent: front 400
            val reconcile = sim.relocationInto("t", units = 300L)
            if (reconcileFirst) g.absorbPublished(reconcile)
            g.serve(listOf("a", "c", "t"), rounds = 1) // seats t, then schedules
            if (!reconcileFirst) g.absorbPublished(reconcile)
            val register = assertNotNull(sim.world.full().gauge(sim.id("t")))
            val ev = assertNotNull(sim.world.full().grossVirtualService(sim.id("t")))
            return Triple(register, ev, g.serve(listOf("a", "c", "t"), rounds = 30))
        }

        val (registerA, evA, tallyA) = scenario(reconcileFirst = true)
        val (registerB, evB, tallyB) = scenario(reconcileFirst = false)

        assertAll(
            { assertEquals(registerA, registerB, "the register is arrival-order independent") },
            { assertEquals(evA, evB, "and so is the read") },
            { assertEquals(tallyA["t"], tallyB["t"], "and the schedule") },
            // Level entry: t sits at the front it joined, within one quantum — neither starved
            // (ev ≫ front, the refuted drop read) nor credited (ev ≪ front, the refuted default-0).
            { assertTrue(evA >= Rational.of(399L) && evA <= Rational.of(402L), "t enters level, ev=$evA") },
            { assertTrue((tallyA["t"] ?: 0) in 8..12, "t then takes about a third: ${tallyA["t"]}") },
        )
    }

    /**
     * The predicate change itself, isolated. An edge carrying a relocated magnitude has
     * `effIssued > 0` — so the refuted `effIssued == 0` gate is false for it **forever** — yet it
     * is still unseated and must still be seatable. Gauge absence answers the question that was
     * actually being asked.
     */
    @Test
    fun theSeatBumpKeysOnGaugeAbsenceNotOnEffectiveIssuance() {
        val sim = Sim("a", "t")
        val g = sim.peer("G")
        g.absorbPublished(sim.relocationInto("t", units = 300L))
        val t = sim.id("t")

        assertAll(
            // The refuted gate would read this and refuse.
            { assertEquals(300L, g.state.edge(t)?.issued, "effIssued is 300, so `effIssued == 0` is false") },
            { assertEquals(0L, g.baseIssued("t"), "…while base issuance, the gauge's axis, is still 0") },
            { assertNull(g.gauge("t"), "and the edge is genuinely unseated") },
        )

        assertTrue(g.seatIfAbsent("t", Rational.of(400L)), "gauge absence admits the seat")
        assertAll(
            { assertEquals(Gauge(Rational.of(400L), 0L), g.gauge("t"), "seated at the front, folding 0") },
            // And it is now idempotent: a second bump is refused, so a repeated round cannot
            // ratchet the edge forward.
            { assertTrue(!g.seatIfAbsent("t", Rational.of(900L)), "a seated edge is never re-seated") },
            { assertEquals(Gauge(Rational.of(400L), 0L), g.gauge("t"), "…and the register is unchanged") },
        )
    }

    // ── cross-writer pairing, restart, and the reachable-view sandwich ───────────────────

    /**
     * Componentwise max can pair one writer's floor with another writer's fold. Two grantors serve
     * `e` concurrently, each blind to the other, each checkpoint folding only its own observed
     * issuance. The merged read must equal `seat + totalIssued / w` **exactly** — no service lost
     * (which would be lifetime credit) and none double-counted (which would be starvation) — and a
     * third grant computed *from* the merged register must stay exact.
     */
    @Test
    fun concurrentGrantorsMergeToTheExactSum() {
        val sim = Sim("e")
        sim.peer("G1").seatIfAbsent("e", Rational.of(50L))

        // Both grantors snapshot the world HERE: each sees the seat, neither will see the other's
        // grants. That is the cross-writer pairing hazard, not a contrived one.
        val g1 = sim.peer("G1")
        val g2 = sim.peer("G2")
        repeat(10) { g1.grant("e") } // G1 folds its own view
        repeat(8) { g2.grant("e") } // G2, blind to G1, folds its own

        val healed = sim.world.full()
        assertAll(
            { assertEquals(18L, healed.edge(sim.id("e"))?.issued, "18 units delivered in total") },
            {
                assertEquals(
                    Rational.of(68L),
                    healed.grossVirtualService(sim.id("e")),
                    "seat 50 + 18 units, exact — neither writer's fold erased the other's service",
                )
            },
        )

        val g3 = sim.peer("G3")
        g3.grant("e")
        assertEquals(
            Rational.of(69L),
            sim.world.full().grossVirtualService(sim.id("e")),
            "a grant computed from the merged register stays exact",
        )
    }

    /**
     * The scenario that killed fix A, replayed. `old` runs to 1000; `recent` is seated at that
     * front and served a little. A **late joiner** reads both correctly, because the register
     * travels with the ledger rather than living in a per-boot `HashMap`. A peer **restarted onto an
     * empty ledger** reads zeros, but its seat bumps are `(0, 0)` — the max-join absorbs them with
     * no damage at all — and one anti-entropy round restores exact reads.
     *
     * Under fix A this same scenario produced full lifetime credit on every restart.
     */
    @Test
    fun restartAndLateJoinReadTheCorrectSeats() {
        val sim = Sim("old", "recent")
        val g = sim.peer("G")
        g.serve(listOf("old"), rounds = 1000)
        assertEquals(Rational.of(1000L), g.grossEv("old"))
        g.serve(listOf("old", "recent"), rounds = 10)

        val late = sim.world.full()
        val recentSeat = assertNotNull(late.grossVirtualService(sim.id("recent")))
        assertTrue(recentSeat >= Rational.of(1000L), "the late joiner sees recent's seat — no lifetime credit")

        // A restarted peer: topology only, no accounting. Its bumps fold 0 against a floor of 0.
        val restarted = Peer("R", sim.world, sim, seeing = { it == "base" })
        restarted.seatIfAbsent("old", Rational.ZERO)
        restarted.seatIfAbsent("recent", Rational.ZERO)
        val healed = sim.world.full()
        assertAll(
            {
                assertTrue(
                    assertNotNull(healed.grossVirtualService(sim.id("recent"))) >= Rational.of(1000L),
                    "the (0, 0) bumps were absorbed with no effect",
                )
            },
            { assertEquals(recentSeat, healed.grossVirtualService(sim.id("recent")), "…exactly none") },
        )

        // After anti-entropy the restarted peer schedules like everyone else.
        val recovered = sim.peer("R2")
        val share = recovered.serve(listOf("old", "recent"), rounds = 30)["recent"] ?: 0
        assertTrue(share in 10..20, "a fair-ish split, not 30/30: recent took $share")
    }

    /**
     * The property that replaces monotonicity. Over 400 random **reachable** views — joins of
     * arbitrary subsets of the published per-edge patches, on a history containing honest
     * checkpoints *and* three stale front-bumps — every view must satisfy
     *
     * ```
     * seat + C_view/w  ≤  read  ≤  seat + C_view/w + maxBumpExcess
     * ```
     *
     * The left inequality is §10.5's no-lifetime-credit direction: no subset of patches can make
     * `e` read *behind* the service that same subset contains. The right is the bounded overshoot:
     * the only inflation any view can manufacture is the largest stale bump's excess over the
     * honest floor at its fold — never `e`'s own service re-counted, which is the difference from
     * F1.
     */
    @Test
    fun randomizedPatchSubsetsObeyTheNoCreditAndBoundedOvershootSandwich() {
        val sim = Sim("a", "c", "e")
        val g = sim.peer("G")
        g.serve(listOf("a", "c"), rounds = 100)
        g.serve(listOf("a", "c", "e"), rounds = 60) // seat at 50, serve to (70, 20)

        val seatTrue = Rational.of(50L)
        var maxBumpExcess = Rational.ZERO
        repeat(3) { i ->
            val stale = sim.peer("P$i", seeing = { it != "e" })
            val front = stale.front(listOf("a", "c"))
            // The bump folds 0, so its excess over the true seat at fold 0 is `front − seat`.
            maxBumpExcess = Rational.max(maxBumpExcess, front - seatTrue)
            stale.seatIfAbsent("e", front)
            g.serve(listOf("a", "c", "e"), rounds = 5)
        }

        val rnd = Random(42)
        var checked = 0
        repeat(400) {
            val view = sim.world.view { touches -> touches != "e" || rnd.nextBoolean() }
            val stored = view.gauge(sim.id("e")) ?: return@repeat // unseated views read raw C/w
            val read = assertNotNull(view.grossVirtualService(sim.id("e")))
            val floorOracle = seatTrue + Rational.of(view.baseIssuance(sim.id("e")))
            assertAll(
                { assertTrue(read >= floorOracle, "no credit: $read ≥ $floorOracle (register $stored)") },
                {
                    assertTrue(
                        read <= floorOracle + maxBumpExcess,
                        "bounded: $read ≤ $floorOracle + $maxBumpExcess (register $stored)",
                    )
                },
            )
            checked++
        }
        assertTrue(checked > 100, "the sweep must actually reach seated views; only $checked of 400 were")
    }

    /**
     * The one other place the ledger republishes an edge's base `issued` — [retire]'s drain witness
     * — must co-carry that edge's gauge, or it *is* the split the negative control above shows
     * reintroducing the double count. Harmless today only because a witnessed edge is RETIRED and
     * therefore never a scheduling candidate; pinned so it stays true if that changes, and as the
     * in-tree statement of the constraint #1783 has to honour.
     */
    @Test
    fun theDrainWitnessCoCarriesTheEdgesGauge() {
        val sim = Sim("e")
        val g = sim.peer("G")
        g.seatIfAbsent("e", Rational.of(50L))
        repeat(4) { g.grant("e") }
        val e = sim.id("e")
        val witnessed = g.state.drainWitnessFor(e)
        assertAll(
            { assertNotNull(witnessed.gauge(e), "the witness carries the gauge") },
            { assertEquals(g.gauge("e"), witnessed.gauge(e), "…at the value the republished counters imply") },
            {
                // The premise: the witness genuinely does republish base issuance, so the pairing
                // is required rather than incidental.
                assertEquals(4L, witnessed.edge(e)?.issued, "the witness republishes base issued")
            },
        )
    }

    // ── the harness ─────────────────────────────────────────────────────────────────────

    /**
     * A patch-granular world: an ordered list of published lattice elements, each tagged with the
     * edge it is *about*, so a test can construct a view that has lost exactly one edge's traffic.
     */
    private class World {
        private val published = ArrayList<Pair<String, EntitlementLedger>>()

        fun publish(touches: String, delta: EntitlementLedger) {
            published += touches to delta
        }

        fun view(include: (String) -> Boolean): EntitlementLedger =
            published.fold(EntitlementLedger.ZERO) { acc, (touches, delta) ->
                if (include(touches)) acc.piece(delta) else acc
            }

        fun full(): EntitlementLedger = view { true }

        /** A copy of this world with every published delta rewritten — the atomicity control. */
        fun mapping(f: (String, EntitlementLedger) -> EntitlementLedger): World {
            val out = World()
            for ((touches, delta) in published) out.publish(touches, f(touches, delta))
            return out
        }
    }

    /**
     * One parent with a fixed set of unit-weight child edges, bootstrapped and activated. The
     * topology and the mint go out as `base` and are in every view — they arrive by consensus, and
     * #1700's fence already covers the stale-record case.
     */
    private class Sim(vararg edgeNames: String) {
        val root = GroupId("root")
        val edges = edgeNames.toList()
        val world = World()

        init {
            // Only granting peers need supply — `seat` consumes no holdings, so the stale bumpers
            // need no mint at all.
            var base = EntitlementLedger.bootstrap(
                root,
                GRANTORS.associate { ReplicaId(it) to MINT },
                nonce = "1752",
            )
            for (name in edges) {
                val record = AttachmentRecord(id(name), root, GroupId(name), Weight.ONE)
                base = base.piece(checkNotNull(base.prepare(record)).delta)
                base = base.piece(checkNotNull(base.activate(id(name))).delta)
            }
            world.publish("base", base)
        }

        fun id(name: String): AttachmentId = AttachmentId(name)

        fun peer(name: String, seeing: (String) -> Boolean = { true }): Peer = Peer(name, world, this, seeing)

        /** `reconcileStranded`'s effect on a live edge: `issuedRelocIn(t) += units`, published. */
        fun relocationInto(edge: String, units: Long): EntitlementLedger {
            val delta = EntitlementLedger.of(
                issuedRelocIn = mapOf(id(edge) to GCounter.of(ReplicaId("G") to units)),
            )
            world.publish("$edge-reloc", delta)
            return delta
        }

        fun overshoot(state: EntitlementLedger, edge: String, against: String): Rational =
            checkNotNull(state.grossVirtualService(id(edge))) - checkNotNull(state.grossVirtualService(id(against)))
    }

    /** One peer: a view of the world, and the two write rules driven against it. */
    private class Peer(
        val name: String,
        private val world: World,
        private val sim: Sim,
        seeing: (String) -> Boolean,
    ) {
        var state: EntitlementLedger = world.view(seeing)
            private set

        fun baseIssued(edge: String): Long = state.baseIssuance(sim.id(edge))

        fun gauge(edge: String): Gauge? = state.gauge(sim.id(edge))

        fun grossEv(edge: String): Rational = checkNotNull(state.grossVirtualService(sim.id(edge)))

        fun absorbPublished(delta: EntitlementLedger) {
            state = state.piece(delta)
        }

        /** One grant of the unit quantum down [edge] — counter bump and checkpoint, one patch. */
        fun grant(edge: String) {
            val patch = checkNotNull(state.delegate(ReplicaId(name), sim.id(edge), QUANTUM)) {
                "delegate refused for $name on $edge"
            }
            world.publish(edge, patch.delta)
            state = state.piece(patch.delta)
        }

        /** The seat bump. Returns false when this peer's view already carries a gauge for [edge]. */
        fun seatIfAbsent(edge: String, front: Rational): Boolean {
            val patch = state.seat(sim.id(edge), front) ?: return false
            world.publish(edge, patch.delta)
            state = state.piece(patch.delta)
            return true
        }

        /**
         * This peer's view assembled as [HeddlePolicy]'s own input — one [PolicyEdge] per name,
         * everyone demanding, carrying the gauge and the base issuance the real policy reads.
         */
        private fun policyEdges(edges: List<String>): List<PolicyEdge> = edges.map { name ->
            val id = sim.id(name)
            PolicyEdge(
                record = checkNotNull(state.record(id)) { "$name has no single record in $this's view" },
                summary = checkNotNull(state.edge(id)) { "$name is unknown to $this's view" },
                demand = DEMANDING,
                gauge = state.gauge(id),
                baseIssued = state.baseIssuance(id),
            )
        }

        /** [HeddlePolicy.front] over [edges] as this peer sees them — §7.3 step 2. */
        fun front(edges: List<String>): Rational =
            checkNotNull(HeddlePolicy.front(policyEdges(edges))) { "front over a non-empty set is never null" }

        /**
         * [rounds] rounds of the **real** [HeddlePolicy.pick] against this peer's view, preceded by
         * the design's bump-before-grant rule: every gauge-absent edge is seated from this peer's
         * front before the round's arithmetic runs — all of them excluded from that front together,
         * exactly as `HeddleNode.seatUnseated` does at the top of `schedule`.
         *
         * This used to be a stand-in that reimplemented §7.3 over
         * [EntitlementLedger.grossVirtualService], because the policy could not yet see a gauge.
         * It can now (#1752), so these attacks run against the production selection path rather
         * than a model of it.
         */
        fun serve(edges: List<String>, rounds: Int): Map<String, Int> {
            val tally = HashMap<String, Int>()
            repeat(rounds) {
                val unseated = edges.filter { gauge(it) == null }
                if (unseated.isNotEmpty()) {
                    val joining = edges.filterNot { it in unseated }
                    val front = if (joining.isEmpty()) Rational.ZERO else front(joining)
                    for (e in unseated) seatIfAbsent(e, front)
                }
                val picked = checkNotNull(HeddlePolicy.pick(policyEdges(edges), POLICY, holdings())) {
                    "$name has supply and every edge demands, so a grant is always due"
                }
                val winner = picked.attachment.value
                grant(winner)
                tally[winner] = (tally[winner] ?: 0) + 1
            }
            return tally
        }

        /** This peer's own delegable supply at the root — [HeddlePolicy.pick]'s quantum trim. */
        private fun holdings(): Long = state.holdings(sim.root, ReplicaId(name))

        override fun toString(): String = name
    }

    private companion object {
        const val QUANTUM = 1L

        val POLICY = PolicyConfig(quantum = QUANTUM)

        /**
         * Everyone always competes. [Demand.maximumUsefulGrant] is the quantum, so a grant is
         * exactly one unit; [Demand.targetOutstanding] is far past anything [serve]'s round counts
         * can reach, so the candidate gate never closes on its own.
         */
        val DEMANDING = Demand(targetOutstanding = 1_000_000L, maximumUsefulGrant = QUANTUM)

        /** Ample supply — these tests probe fairness arithmetic, never the holdings gate. */
        const val MINT = 1_000_000L

        /** The peers that ever call `delegate`, and therefore the ones needing supply. */
        val GRANTORS = listOf("G", "G1", "G2", "G3", "R2")
    }
}
