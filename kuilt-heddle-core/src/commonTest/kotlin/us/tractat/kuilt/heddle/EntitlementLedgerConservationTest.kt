package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The load-bearing economics invariants (design §10.1 / §10.7): conservation across
 * randomized mutator sequences AND partial-delivery merges, and overspend refusal.
 *
 * A throwaway Python-style model already validated this math over 400 seeded runs;
 * this reproduces it as Kotlin property tests over the real [EntitlementLedger].
 */
class EntitlementLedgerConservationTest {

    // A fixed static ACTIVE tree:  root → g1 → g3(leaf), root → g2(leaf).
    private val root = GroupId("root")
    private val g1 = GroupId("g1")
    private val g2 = GroupId("g2")
    private val g3 = GroupId("g3")
    private val e1 = AttachmentId("e1") // root → g1
    private val e2 = AttachmentId("e2") // root → g2
    private val e3 = AttachmentId("e3") // g1  → g3
    private val replicas = listOf("alice", "bob", "carol").map(::ReplicaId)
    private val allGroups = listOf(root, g1, g2, g3)

    private fun topology(): EntitlementLedger = EntitlementLedger.of(
        records = mapOf(
            e1 to setOf(AttachmentRecord(e1, root, g1, Weight.ONE)),
            e2 to setOf(AttachmentRecord(e2, root, g2, Weight.ONE)),
            e3 to setOf(AttachmentRecord(e3, g1, g3, Weight.ONE)),
        ),
    )

    private fun seeded(mint: Long): EntitlementLedger =
        topology().piece(EntitlementLedger.bootstrap(root, mapOf(replicas[0] to mint), nonce = "genesis"))

    /** minted == Σ holdings + Σ leafSpent, over every group in [groups] and every replica. */
    private fun assertConservation(ledger: EntitlementLedger, minted: Long, groups: List<GroupId> = allGroups) {
        var sumHoldings = 0L
        for (g in groups) for (r in replicas) sumHoldings += ledger.holdings(g, r)
        assertEquals(minted, sumHoldings + ledger.leafSpentTotal(), "conservation broke")
        assertEquals(minted, ledger.mintedTotal(), "minted total drifted")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Two trees in one ledger (#1751) — the state this suite could not previously construct
    // ─────────────────────────────────────────────────────────────────────────

    private val otherRoot = GroupId("otherRoot")
    private val g4 = GroupId("g4") // otherRoot's only child, a leaf
    private val e4 = AttachmentId("e4") // otherRoot → g4
    private val bothTrees = allGroups + listOf(otherRoot, g4)

    /**
     * Two independently bootstrapped trees merged into one ledger, each with its own supply.
     *
     * Every other fixture here bootstraps at exactly **one** root over a fixed one-tree
     * [allGroups], which makes the #1751 double count *unconstructible* in this suite — the file
     * whose whole subject is the Σ-holdings identity was structurally unable to see the fault, and
     * the guarantee rested instead on two bespoke assertions over in `EntitlementLedgerValidateTest`.
     * This expresses it as the conservation **property**: unbind a [MintRecord] from its root, or
     * bind it to the wrong one, and the sum moves away from `mintedTotal` right here.
     */
    private fun twoTrees(mintA: Long, mintB: Long): EntitlementLedger = topology()
        .piece(EntitlementLedger.of(records = mapOf(e4 to setOf(AttachmentRecord(e4, otherRoot, g4, Weight.ONE)))))
        .piece(EntitlementLedger.bootstrap(root, mapOf(replicas[0] to mintA), nonce = "genesis-a"))
        .piece(EntitlementLedger.bootstrap(otherRoot, mapOf(replicas[1] to mintB), nonce = "genesis-b"))

    @Test
    fun conservationHoldsAcrossTwoMergedBootstrapsAndTheirMutatorSequences() {
        val rnd = Random(0x1751)
        val mintA = 600L
        val mintB = 400L
        var ledger = twoTrees(mintA, mintB)
        assertConservation(ledger, mintA + mintB, bothTrees)
        // Prime both trees down to their leaves. Only replicas[0] is funded at `root` and only
        // replicas[1] at `otherRoot`, so a purely random walk lands few delegates and can finish
        // without ever charging service — which is what the `leafSpentTotal > 0` rig caught on the
        // first run of this test. Priming makes the spend arm reachable by construction rather than
        // by seed luck, and every step is still conservation-checked.
        for ((r, edge, amount) in listOf(
            Triple(replicas[0], e1, 200L),
            Triple(replicas[0], e2, 100L),
            Triple(replicas[0], e3, 100L),
            Triple(replicas[1], e4, 200L),
        )) {
            ledger = ledger.piece(requireNotNull(ledger.delegate(r, edge, amount)) { "priming delegate refused" })
            assertConservation(ledger, mintA + mintB, bothTrees)
        }
        repeat(60) {
            val r = replicas.random(rnd)
            val patch = when (rnd.nextInt(4)) {
                0 -> ledger.delegate(r, listOf(e1, e2, e3, e4).random(rnd), rnd.nextLong(1L, 40L))
                1 -> ledger.release(r, listOf(e1, e2, e3, e4).random(rnd), rnd.nextLong(1L, 40L))
                2 -> {
                    val to = replicas.filter { it != r }.random(rnd)
                    ledger.transfer(bothTrees.random(rnd), r, to, rnd.nextLong(1L, 40L))
                }
                else -> ledger.spend(r, listOf(g2, g3, g4).random(rnd), rnd.nextLong(1L, 40L))
            }
            if (patch != null) ledger = ledger.piece(patch)
            assertConservation(ledger, mintA + mintB, bothTrees)
        }
        assertAll(
            // ── the rig: this really is the two-tree state, and the sequence really did move units.
            { assertEquals(listOf(otherRoot, root), ledger.mintedRoots(), "rig: two roots carry supply") },
            { assertTrue(ledger.leafSpentTotal() > 0L, "rig: the sequence actually charged service") },
            // ── and the diagnostic names it, without the accounting having gone wrong.
            {
                assertTrue(
                    LedgerConflict.MultipleRoots(listOf(otherRoot, root)) in ledger.validate(),
                    "the two-tree state must be reported: ${ledger.validate()}",
                )
            },
        )
    }

    @Test
    fun conservationHoldsAcrossRandomizedMutatorSequences() {
        val rnd = Random(0xC0FFEE)
        repeat(80) {
            val minted = 1_000L
            var ledger = seeded(minted)
            assertConservation(ledger, minted)
            repeat(60) {
                val r = replicas.random(rnd)
                val patch = when (rnd.nextInt(5)) {
                    0 -> ledger.delegate(r, listOf(e1, e2, e3).random(rnd), rnd.nextLong(1L, 40L))
                    1 -> ledger.release(r, listOf(e1, e2, e3).random(rnd), rnd.nextLong(1L, 40L))
                    2 -> {
                        val to = replicas.filter { it != r }.random(rnd)
                        ledger.transfer(allGroups.random(rnd), r, to, rnd.nextLong(1L, 40L))
                    }
                    3 -> ledger.spend(r, listOf(g2, g3).random(rnd), rnd.nextLong(1L, 40L))
                    else -> ledger.spend(r, listOf(g2, g3).random(rnd), rnd.nextLong(1L, 40L))
                }
                if (patch != null) ledger = ledger.piece(patch)
                assertConservation(ledger, minted)
            }
            // No honest sequence produces an integrity conflict.
            assertTrue(ledger.validate().isEmpty(), "honest run flagged a conflict: ${ledger.validate()}")
        }
    }

    @Test
    fun conservationHoldsUnderPartialDeliveryMerges() {
        val rnd = Random(0xBEEF)
        repeat(60) {
            val minted = 500L
            val base = seeded(minted)
            // Two peers each apply an independent, individually-valid mutator to base,
            // then merge in both orders — conservation holds on every resulting state.
            val p1 = base.delegate(replicas[0], e1, rnd.nextLong(1L, 100L))
            val fundedG1 = if (p1 != null) base.piece(p1) else base
            val p2 = fundedG1.spend(replicas[0], g3, 0L) // no-op cancel; still valid
            val a = fundedG1.delegate(replicas[0], e3, 10L)
            val b = fundedG1.transfer(g1, replicas[0], replicas[1], 5L)
            val left = if (a != null) fundedG1.piece(a) else fundedG1
            val leftRight = if (b != null) left.piece(b) else left
            val right = if (b != null) fundedG1.piece(b) else fundedG1
            val rightLeft = if (a != null) right.piece(a) else right
            assertEquals(leftRight, rightLeft, "merge not order-independent")
            assertConservation(leftRight, minted)
            assertConservation(fundedG1, minted)
            if (p2 != null) assertConservation(fundedG1.piece(p2), minted)
        }
    }

    @Test
    fun overspendPastHoldingsRefusesAndLeavesStateUntouched() {
        val ledger = seeded(100L)
        // alice holds exactly 100 at root; nobody else holds anything.
        assertEquals(100L, ledger.holdings(root, replicas[0]))
        assertEquals(0L, ledger.holdings(root, replicas[1]))

        // delegate/transfer/spend beyond holdings all return null, state untouched.
        assertNull(ledger.delegate(replicas[0], e1, 101L))
        assertNull(ledger.transfer(root, replicas[0], replicas[1], 101L))
        assertNull(ledger.delegate(replicas[1], e1, 1L)) // bob holds nothing
        // release past a child's holdings (nothing delegated yet) refuses.
        assertNull(ledger.release(replicas[0], e1, 1L))

        // A leaf with no funding cannot spend.
        val funded = ledger.piece(ledger.delegate(replicas[0], e2, 30L)!!)
        assertNull(funded.spend(replicas[0], g2, 31L))
        // spending exactly holdings succeeds.
        assertTrue(funded.spend(replicas[0], g2, 30L) != null)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Relocation inside the randomized sequence (issue #1894)
    //
    // The four mutators above never move a generation, so the randomized sequence
    // could not reach the one family the module's only *confirmed* conservation
    // break lives in (#1783). These two runs put relocation in the generator: the
    // first with honest acked finals, the second with deliberately under-acked
    // ones — and the second is EXPECTED to break the identity, by exactly the
    // under-acked amount, with the breach attributed to the edge it happened on.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * `g3`'s inbound **generation ladder**. The static topology gives `g3` one inbound edge, and a
     * relocation needs a *retired* one and a *live* one under the same parent — so each re-home in
     * the randomized sequence mints a fresh `g1 → g3` generation. [e3] is rung `0`.
     */
    private fun rung(i: Int): AttachmentId = if (i == 0) e3 else AttachmentId("e3.gen$i")

    /** One deliberately under-acked leaf final — the #1783 shape, and the debt it leaves behind. */
    private data class UnderAck(
        val strand: AttachmentId,
        val replica: ReplicaId,
        val ackedLeafSpent: Long,
        val delta: Long,
    )

    /**
     * What the randomized sequence actually *reached*. Every arm counts its own firings rather than
     * inferring them from a side effect: an arm that is never drawn is green by absence, and a
     * relocation-aware property that never relocates would pass exactly as loudly as one that does.
     */
    private class RelocationRig {
        var moved = 0
        var refused = 0
        var nothingToMove = 0
        var unfunded = 0
        var nested = 0
        var accumulated = 0
        var underAcks = 0

        /**
         * How many under-acked runs saw `PerEdgeSafety(e1)` — the strand's strict prefix — fire.
         *
         * **The frequency floor's counter (#2673), and no longer merely reported.** `e1` is in
         * [assertUnderAckIsAttributed]'s tolerated set, and a tolerance is kept honest two ways at
         * once: a **bound** on the breach's magnitude (there), and a floor on how often the case is
         * reached at all (here). They are orthogonal — the bound cannot notice the arm going
         * silent, and the floor says nothing about the size of what leaks — so both are asserted.
         *
         * The floor was **unavailable** until #2592: the run's trajectory depended on the order the
         * under-ack victim was drawn out of a `HashSet`-backed map, which JVM and Kotlin/Native do
         * not agree on, so this read 0 on one target and non-zero on the other from the same seed.
         * [chargersIn] now imposes the fixture's own order and all four measured targets walk one
         * trajectory.
         *
         * It was still declined at that point, because on that trajectory it read **1** across 80
         * runs, and a `>= 1` floor sitting at exactly 1 reds on any unrelated generator tweak.
         * What made it takeable was raising the count it sits on rather than weakening the floor —
         * see [anUnderAckedLeafFinalBreaksConservationByExactlyTheUnderAckedAmount], which now
         * makes 1,200 runs against a biased under-ack shape and reads 22.
         */
        var prefixSafety = 0

        /**
         * Every draw's outcome, carried in each floor's failure message. A floor that reds then says
         * *where* the draws went rather than only that too few arrived — the non-moving outcomes are
         * how this fixture goes quietly vacuous, and diagnosing that from a bare count already cost
         * it one round (see the funding-chain comment in [randomizedRunWithRelocation]).
         */
        override fun toString(): String =
            "moved=$moved refused=$refused nothingToMove=$nothingToMove unfunded=$unfunded " +
                "nested=$nested accumulated=$accumulated underAcks=$underAcks " +
                "prefixSafety=$prefixSafety"
    }

    private fun EntitlementLedger.applying(patch: Patch<EntitlementLedger>?): EntitlementLedger =
        if (patch == null) this else piece(patch)

    /** `Σ_{edge, replica} storedSlot(family)` — the **base** (stored, grow-only) total of one family. */
    private fun EntitlementLedger.storedTotal(family: CounterFamily): Long {
        val rs = allReplicas()
        var acc = 0L
        for (e in allEdges()) for (r in rs) acc += storedSlot(family, e, r)
        return acc
    }

    /**
     * Conservation in **both** forms, plus the relocation identity that ties them together.
     *
     * `leafSpentTotal()` is the *effective* term (`base + leafRelocIn − leafRelocOut`, #1694), so
     * restating it under a second name would assert nothing. The second form is the **base** one:
     * `Σ storedSlot(LEAF_SPENT)`. The two are required to differ by exactly
     * `Σ LEAF_RELOC_IN − Σ LEAF_RELOC_OUT`, and those two sums are required to be *equal* — which
     * is the claim that a move sends its leaf charge in equal and opposite amounts, and therefore
     * that both forms of the identity hold at once. A move that credited the live edge with a
     * different magnitude than it debited the strand reds here, and the base form reds even where
     * the effective form absorbs the error into `holdings`.
     *
     * [residual] is the debt an under-acked ack has deliberately created (`0` on an honest run);
     * asserting `minted + residual` pins the deviation to the unit rather than merely noticing one.
     */
    private fun assertConservationWithRelocation(l: EntitlementLedger, minted: Long, residual: Long) {
        var sumHoldings = 0L
        for (g in allGroups) for (r in replicas) sumHoldings += l.holdings(g, r)
        val effectiveLeafSpent = l.leafSpentTotal()
        val baseLeafSpent = l.storedTotal(CounterFamily.LEAF_SPENT)
        val relocIn = l.storedTotal(CounterFamily.LEAF_RELOC_IN)
        val relocOut = l.storedTotal(CounterFamily.LEAF_RELOC_OUT)
        assertAll(
            {
                assertEquals(
                    baseLeafSpent + relocIn - relocOut,
                    effectiveLeafSpent,
                    "effective leaf spend is not base + leafRelocIn − leafRelocOut",
                )
            },
            { assertEquals(relocOut, relocIn, "a move must send leaf charge in equal and opposite amounts") },
            { assertEquals(minted + residual, sumHoldings + effectiveLeafSpent, "conservation broke (effective form)") },
            { assertEquals(minted + residual, sumHoldings + baseLeafSpent, "conservation broke (base form)") },
            { assertEquals(minted, l.mintedTotal(), "minted total drifted") },
        )
    }

    /**
     * The acking replicas on one edge that charged something under [charge], in **this fixture's
     * own total order** ([ReplicaId] ascending) rather than in the map's.
     *
     * The sort is the whole point of this function and it is not cosmetic. A seeded generator is
     * only reproducible across targets while its trajectory never depends on walking a hash-ordered
     * collection: `Random(seed)` is portable, `HashSet` bucket order is not.
     * [EntitlementLedger.baseFinalsOn] builds its map from a `HashSet` of the replicas on the edge,
     * so drawing the under-ack victim straight out of `entries` lets the *same seed* pick a
     * *different* victim on each target — and one different victim cascades into a different delta,
     * a different clamp and a different reachable state for the rest of the run.
     *
     * Measured before this sort existed (#2592): the two **honest** arms, which never reach this
     * draw, were byte-identical on JVM and `macosArm64`, while the two **under-acked** arms, which
     * do, disagreed on every counter — `moved` 850 vs 791, `underAcks` 76 vs 67 — from one seed.
     * That is the shape that makes a JVM-only measurement of "this arm never fires" silently false
     * on three of six targets.
     */
    private fun Map<ReplicaId, SlotFinals>.chargersIn(
        charge: (SlotFinals) -> Long,
    ): List<Map.Entry<ReplicaId, SlotFinals>> = entries.filter { charge(it.value) > 0L }.sortedBy { it.key }

    /**
     * The property [chargersIn] exists for, asserted directly: the draw must not move when the
     * *same* finals arrive in a different order.
     *
     * This is what a cross-target run would otherwise be the only witness to. Permuting the map
     * stands in for the JVM/Kotlin-Native bucket-order disagreement, so the regression is reachable
     * on every target from a single-target run. Drop the `sortedBy` in [chargersIn] and this reds.
     */
    @Test
    fun theUnderAckVictimIsDrawnInAnOrderTheFixtureImposesRatherThanTheMapS() {
        val finals = linkedMapOf(
            replicas[0] to SlotFinals(issued = 9L, returned = 0L, leafSpent = 3L, rollupSpent = 1L),
            replicas[1] to SlotFinals(issued = 9L, returned = 0L, leafSpent = 5L, rollupSpent = 2L),
            replicas[2] to SlotFinals(issued = 9L, returned = 0L, leafSpent = 7L, rollupSpent = 4L),
        )
        val permuted: Map<ReplicaId, SlotFinals> =
            finals.entries.reversed().associateTo(LinkedHashMap()) { it.key to it.value }
        assertAll(
            {
                // The precondition: a permutation that cannot be distinguished proves nothing, so
                // assert the rig is live before asserting what it shows.
                assertEquals(
                    replicas.reversed(),
                    permuted.keys.toList(),
                    "the permuted fixture is not actually permuted — this arm would pass unsorted",
                )
            },
            {
                assertEquals(
                    finals.chargersIn { it.leafSpent }.map { it.key },
                    permuted.chargersIn { it.leafSpent }.map { it.key },
                    "the leaf charger order follows the map's iteration order, not the fixture's",
                )
            },
            {
                assertEquals(
                    finals.chargersIn { it.rollupSpent }.map { it.key },
                    permuted.chargersIn { it.rollupSpent }.map { it.key },
                    "the roll-up charger order follows the map's iteration order, not the fixture's",
                )
            },
            {
                // And the draw itself, which is what the generator actually calls.
                for (seed in 0 until 16) {
                    assertEquals(
                        finals.chargersIn { it.leafSpent }.random(Random(seed)).key,
                        permuted.chargersIn { it.leafSpent }.random(Random(seed)).key,
                        "seed $seed drew a different under-ack victim from a permuted map",
                    )
                }
            },
        )
    }

    /**
     * One seeded run of the four base mutators **plus** two relocation shapes, with
     * [assertConservationWithRelocation] after every single step.
     *
     * [allowUnderAck] admits the #1783 shape — an acked leaf final *below* the strand's true base,
     * which moves the credit onto the live edge without moving the charge. That is a real,
     * currently-unfixed break (#1783 is open), so it is carried as an exact, attributed residual
     * rather than tolerated by a weaker assertion.
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun randomizedRunWithRelocation(
        rnd: Random,
        minted: Long,
        allowUnderAck: Boolean,
        rig: RelocationRig,
    ) {
        var ledger = seeded(minted)
        var live = rung(0)
        var generation = 0
        var residual = 0L
        val underAcked = ArrayList<UnderAck>()
        assertConservationWithRelocation(ledger, minted, residual)

        // close → force RETIRED (the raced advisory retire of #1665, which bypasses the drain
        // gate exactly as a gossip-lagged peer does) → prepare + activate the next rung.
        fun reshape(l: EntitlementLedger, from: AttachmentId): Pair<EntitlementLedger, AttachmentId> {
            val fresh = rung(++generation)
            var out = l.applying(l.close(from))
            out = out.piece(EntitlementLedger.of(lifecycle = mapOf(from to Lifecycle.RETIRED)))
            out = out.applying(out.prepare(AttachmentRecord(fresh, g1, g3, Weight.ONE)))
            return out.applying(out.activate(fresh)) to fresh
        }

        // The acked finals for `strand`. Honest = each replica's true base slots; under-acked =
        // one charging replica's LEAF final lowered below its base, which is the #1783 shape.
        fun finalsFor(
            l: EntitlementLedger,
            strand: AttachmentId,
            underAck: Boolean,
        ): Pair<Map<ReplicaId, SlotFinals>, UnderAck?> {
            val honest = l.baseFinalsOn(strand)
            if (!underAck) return honest to null
            val chargers = honest.chargersIn { it.leafSpent }
            if (chargers.isEmpty()) return honest to null // nothing was charged here: nothing to understate
            val victim = chargers.random(rnd)
            val base = victim.value.leafSpent
            // Biased toward `base` — the largest of three uniform draws, mean ≈ ¾`base` rather than
            // ½ — because a bigger lie leaves a bigger residue, and the residue is the only thing
            // that can push `e1` past its cover (see the frequency floor in
            // [anUnderAckedLeafFinalBreaksConservationByExactlyTheUnderAckedAmount]).
            //
            // **Deliberately not `delta = base`, which is stronger on reach and measured better on
            // it (8 vs 5 hits per 400 runs when it was tried).** Understating the whole charge sets
            // `ackedLeafSpent` to `0` on every under-ack, and [assertUnderAckIsAttributed]'s rig
            // assertion — `baseFinalsOn(strand, replica).leafSpent > u.ackedLeafSpent` — then
            // degenerates to `base > 0`, which [chargersIn] has already filtered for. It would buy
            // reach by deleting a live assertion, which is the trade this fixture keeps being caught
            // making. Taking the max of three keeps the whole range `[1, base]` reachable, so a
            // partial under-ack is still drawn, and measured *better* anyway (10 per 400 runs).
            val delta = maxOf(
                rnd.nextLong(1L, base + 1L),
                maxOf(rnd.nextLong(1L, base + 1L), rnd.nextLong(1L, base + 1L)),
            )
            val understated = honest + mapOf(victim.key to victim.value.copy(leafSpent = base - delta))
            return understated to UnderAck(strand, victim.key, base - delta, delta)
        }

        fun record(under: UnderAck?) {
            if (under == null) return
            rig.underAcks++
            underAcked += under
            residual += under.delta
        }

        fun nested(l: EntitlementLedger, strand: AttachmentId): Boolean =
            replicas.any { l.storedSlot(CounterFamily.ISSUED_RELOC_IN, strand, it) > 0L }

        // Two ordinary delegations, composed. Reaching the g3 ladder means funding `root → g1` and
        // then `g1 → g3` with the SAME actor, in that order — a conjunction the four base mutators
        // draw so rarely that the first cut of this test moved a generation 35 times in 4,800
        // steps and hit `Relocation.Nothing` 632 times. An empty strand is a legitimate move (the
        // §5.4 idempotence guard), but a generator that mostly reaches it proves nothing about the
        // magnitudes, so the funding chain is drawn explicitly rather than left to coincide.
        fun fundG1(l: EntitlementLedger, actor: ReplicaId): EntitlementLedger {
            val atRoot = maxOf(0L, minOf(l.holdings(root, actor), 40L))
            if (atRoot < 1L) return l
            return l.applying(l.delegate(actor, e1, rnd.nextLong(1L, atRoot + 1L)))
        }

        fun fundLadder(l: EntitlementLedger, actor: ReplicaId): EntitlementLedger {
            val funded = fundG1(l, actor)
            val atG1 = maxOf(0L, minOf(funded.holdings(g1, actor), 40L))
            if (atG1 < 1L) return funded
            return funded.applying(funded.delegate(actor, live, rnd.nextLong(1L, atG1 + 1L)))
        }

        fun spendAtLeaf(l: EntitlementLedger, actor: ReplicaId, leaf: GroupId): EntitlementLedger {
            val cap = maxOf(0L, minOf(l.holdings(leaf, actor), 40L))
            if (cap < 1L) return l
            return l.applying(l.spend(actor, leaf, rnd.nextLong(1L, cap + 1L)))
        }

        // Shape 1 — one raced retire, one re-home onto the fresh generation. The ladder makes this
        // NESTED for free after the first rung: a strand that itself received an earlier move is
        // drained against `effIssued`, not its base (§12.1).
        fun relocateOnce(l: EntitlementLedger, underAck: Boolean): EntitlementLedger {
            val strand = live
            val (staged, fresh) = reshape(l, strand)
            val wasNested = nested(staged, strand)
            val (finals, under) = finalsFor(staged, strand, underAck)
            return when (val move = staged.relocationPatch(fresh, mapOf(strand to finals))) {
                is Relocation.Moved -> {
                    rig.moved++
                    if (wasNested) rig.nested++
                    record(under)
                    live = fresh
                    staged.piece(move.patch)
                }
                // Both non-moves discard the whole staged reshape, so the ledger the next
                // assertion sees is the one the step started from — never a half-applied fence.
                is Relocation.Refused -> { rig.refused++; l }
                Relocation.Nothing -> { rig.nothingToMove++; l }
            }
        }

        // Shape 2 — §12.3: TWO stranded generations re-homed onto ONE live edge, in two moves, the
        // second derived from a view that merged the first. Their credits must accumulate on the
        // live edge; a max-collide would silently swallow the smaller one.
        fun relocateTwiceOntoOneEdge(l: EntitlementLedger, underAck: Boolean): EntitlementLedger {
            val strandA = live
            val (withB, edgeB) = reshape(l, strandA)
            // Fund B, so the second move carries a magnitude a max-collide could swallow.
            val funder = replicas.random(rnd)
            val topped = fundG1(withB, funder)
            val room = topped.holdings(g1, funder)
            if (room < 1L) { rig.unfunded++; return l }
            var staged = topped.applying(topped.delegate(funder, edgeB, rnd.nextLong(1L, minOf(room, 40L) + 1L)))
            val spendable = maxOf(0L, minOf(staged.holdings(g3, funder), 40L))
            staged = staged.applying(staged.spend(funder, g3, rnd.nextLong(0L, spendable + 1L)))

            val (withC, edgeC) = reshape(staged, edgeB)
            val wasNested = nested(withC, strandA)
            val (finalsA, under) = finalsFor(withC, strandA, underAck)
            val first = withC.relocationPatch(edgeC, mapOf(strandA to finalsA))
            if (first !is Relocation.Moved) { rig.refused++; return l }
            val afterFirst = withC.piece(first.patch)

            val standing = replicas.associateWith { afterFirst.storedSlot(CounterFamily.ISSUED_RELOC_IN, edgeC, it) }
            // `n = effIssued(s)[r] − returned(s)[r]`, the magnitude the KDoc says the second move
            // re-homes — read off the pre-move state rather than recomputed from the patch.
            val moving = replicas.associateWith {
                afterFirst.effectiveIssued(edgeB, it) - afterFirst.storedSlot(CounterFamily.RETURNED, edgeB, it)
            }
            val second = afterFirst.relocationPatch(edgeC, mapOf(edgeB to afterFirst.baseFinalsOn(edgeB)))
            if (second !is Relocation.Moved) { rig.refused++; return l }
            val afterSecond = afterFirst.piece(second.patch)
            for (r in replicas) {
                assertEquals(
                    standing.getValue(r) + moving.getValue(r),
                    afterSecond.storedSlot(CounterFamily.ISSUED_RELOC_IN, edgeC, r),
                    "a second move onto ${edgeC.value} must ACCUMULATE onto ${r.value}'s standing credit (§12.3)",
                )
            }
            // Only count the arm where the two spellings actually differ: with no standing credit,
            // or nothing to add, `max` and `+` agree and the assertion above proves nothing.
            if (replicas.any { standing.getValue(it) > 0L && moving.getValue(it) > 0L }) rig.accumulated++
            rig.moved += 2
            if (wasNested) rig.nested++
            record(under)
            live = edgeC
            return afterSecond
        }

        repeat(60) {
            val actor = replicas.random(rnd)
            val edges = listOf(e1, e2, live)
            ledger = when (rnd.nextInt(8)) {
                0 -> ledger.applying(ledger.delegate(actor, edges.random(rnd), rnd.nextLong(1L, 40L)))
                1 -> ledger.applying(ledger.release(actor, edges.random(rnd), rnd.nextLong(1L, 40L)))
                2 -> {
                    val to = replicas.filter { it != actor }.random(rnd)
                    ledger.applying(ledger.transfer(allGroups.random(rnd), actor, to, rnd.nextLong(1L, 40L)))
                }
                // Clamped to what the actor actually holds. An over-large spend is a no-op the
                // property cannot fail on, and the strand's BASE `leafSpent` is the one thing an
                // under-ack can understate — relocated charge arrives as `leafRelocIn`, never as
                // base — so leaving these to miss starves the #1783 arm rather than testing it.
                3, 4 -> spendAtLeaf(ledger, actor, listOf(g2, g3).random(rnd))
                5 -> fundLadder(ledger, actor)
                // No second coin on top of [allowUnderAck]. The honest/dishonest mix these draws
                // used to buy with `rnd.nextInt(2) == 0` is still there and is not this coin's
                // doing: [finalsFor] returns the honest finals whenever the strand charged nothing,
                // which is most moves — 1,794 under-acks against 11,328 moves (16%) with the coin
                // gone. The coin was a second filter on an already-selective one, and dropping it
                // is what lifts the under-ack rate enough for the frequency floor below to have
                // headroom.
                6 -> relocateOnce(ledger, allowUnderAck)
                else -> relocateTwiceOntoOneEdge(ledger, allowUnderAck)
            }
            assertConservationWithRelocation(ledger, minted, residual)
        }

        // The roll-up half of a move is still structurally zero here — `g3` is a leaf, so no rung is
        // ever a strict prefix — but that gap is no longer this run's to assert: the prefix ladder
        // below (#2367) moves rungs that carry `rollupSpent` and nothing else.
        val conflicts = ledger.validate()
        if (underAcked.isEmpty()) {
            assertAll(
                { assertEquals(0L, residual, "no under-ack was fed, so there is no debt to carry") },
                { assertTrue(conflicts.isEmpty(), "an honest relocation run flagged a conflict: $conflicts") },
            )
        } else {
            assertUnderAckIsAttributed(ledger, conflicts, underAcked, rig)
        }
    }

    /**
     * #1783, asserted as a **named residual** rather than as conservation holding.
     *
     * An under-acked leaf final moves the credit onto the live edge without moving the charge, so
     * `Σ holdings + Σ effLeafSpent` exceeds `minted` by the under-acked amount — a real break, still
     * open, and §6.5.2's claim that a leaf residue is not one is wrong. What the module *does*
     * promise is that it is never silent: the strand reports [LedgerConflict.PerEdgeSafety], its
     * base still exceeds the ack that drained it, and nothing outside that shape is disturbed.
     */
    private fun assertUnderAckIsAttributed(
        l: EntitlementLedger,
        conflicts: List<LedgerConflict>,
        underAcked: List<UnderAck>,
        rig: RelocationRig,
    ) {
        val strands = underAcked.map { it.strand }.toSet()
        if (LedgerConflict.PerEdgeSafety(e1) in conflicts) rig.prefixSafety++
        assertAll(
            {
                for (u in underAcked) {
                    assertTrue(
                        LedgerConflict.PerEdgeSafety(u.strand) in conflicts,
                        "the leaf residue on ${u.strand.value} is not diagnosed: $conflicts",
                    )
                }
            },
            {
                for (u in underAcked) {
                    assertTrue(
                        l.baseFinalsOn(u.strand, u.replica).leafSpent > u.ackedLeafSpent,
                        "base leafSpent on ${u.strand.value} does not exceed the ack that drained it " +
                            "(${u.ackedLeafSpent}) — the under-ack rig never fired",
                    )
                }
            },
            {
                // Nothing beyond the #1783 shape may move: per-edge safety and the closure violation
                // on the STRANDS themselves, the strand's strict prefix `e1` (below), plus the global
                // backstop once the residue is large enough to show in the totals. In particular no
                // negative holdings, no dual inbound, no negative effective spend, and no orphaned
                // transfer path — seven of the nine kinds stay pinned exactly.
                //
                // **`e1` is in the tolerated set, and was not before (#2366).** It is the unique
                // strict prefix of every path ending at `g3`, so a spend of the residue's phantom
                // credit charges `rollupSpent(e1)` with no matching `issued(e1)` behind it. What
                // changed is reach, not soundness: a strand carrying transfer rows can now move
                // instead of being refused, so more under-acked moves land and the residue grows
                // past `e1`'s cover.
                //
                // **The reach used to be platform-dependent, which is why the bound below exists
                // rather than a frequency floor — that is no longer true, and the bound is kept for
                // a different reason (#2592).** `Random(seed)` is portable, but until [chargersIn]
                // imposed an order the under-ack victim was drawn out of a `HashSet`-backed map, so
                // the same seed walked a different trajectory per target: `prefixSafety` read 0 on
                // JVM across 80 runs and 76 under-acks while `iosSimulatorArm64` reached the case,
                // and neither "it always fires" nor "it never fires" was assertable. With the draw
                // ordered, all four measured targets (JVM, `macosArm64`, `iosSimulatorArm64`,
                // `wasmJs`) now walk one trajectory — verified again for this change, byte-identical
                // on all four.
                //
                // **A floor is now taken as well, and it does not replace this (#2673).** The two
                // answer different questions and neither is the stronger: the floor is about
                // *reachability* — it reds if the arm stops reaching `e1` at all, which is the
                // vacuity this tolerance would otherwise hide — and the bound below is about
                // *magnitude*, that when `e1` does break the breach is no larger than the lie that
                // was fed. Converting either into the other would drop a property. The floor lives
                // beside the run count it is derived from, in
                // [anUnderAckedLeafFinalBreaksConservationByExactlyTheUnderAckedAmount].
                val stray = conflicts.filterNot {
                    it is LedgerConflict.PerEdgeSafety && (it.edge in strands || it.edge == e1) ||
                        it is LedgerConflict.ClosureViolation && it.edge in strands ||
                        it is LedgerConflict.ConservationViolation
                }
                assertTrue(stray.isEmpty(), "an under-acked run disturbed something outside the #1783 shape: $stray")
            },
            {
                // The **magnitude** half of what the tolerance above rests on — its reachability
                // half is the frequency floor in
                // [anUnderAckedLeafFinalBreaksConservationByExactlyTheUnderAckedAmount], which is
                // what stops this guard going quietly unentered. It used to be live on
                // Kotlin/Native and vacuous on the JVM, from one seed; since #2592 ordered the
                // under-ack draw it is live on **every** target, and since #2673 raised the run
                // count it is reached 22 times per 1,200 runs rather than once per 80.
                if (LedgerConflict.PerEdgeSafety(e1) in conflicts) {
                    val summary = checkNotNull(l.edge(e1)) { "the prefix edge must be known" }
                    val breach = summary.spent + summary.returned - summary.issued
                    val residual = underAcked.sumOf { it.delta }
                    assertTrue(
                        breach in 1..residual,
                        "the prefix edge's per-edge breach ($breach) is not explained by the " +
                            "under-acked residue ($residual) — the residue is the only thing that " +
                            "may reach `e1`, so a larger breach is a real regression in the move",
                    )
                }
            },
        )
    }

    /**
     * The control arm: relocation is in the generator, every ack is **honest**, and conservation
     * holds exactly — in both forms — after every step, with `validate()` empty at the end.
     */
    @Test
    fun conservationHoldsAcrossRandomizedSequencesThatIncludeRelocation() {
        val rnd = Random(0x1894C0DE)
        val rig = RelocationRig()
        repeat(80) { randomizedRunWithRelocation(rnd, minted = 1_000L, allowUnderAck = false, rig = rig) }
        assertAll(
            { assertTrue(rig.moved >= 250, "too few generations actually moved — $rig") },
            { assertTrue(rig.nested >= 150, "too few strands carried an earlier move's credit (§12.1) — $rig") },
            { assertTrue(rig.accumulated >= 50, "the §12.3 accumulation arm fired too rarely — $rig") },
        )
    }

    /**
     * The treatment arm: the same sequence with **under-acked** leaf finals admitted. The identity
     * is expected to break, by exactly the under-acked amount and no more, with the breach named on
     * the edge it happened on — see [assertUnderAckIsAttributed].
     *
     * **1,200 runs, not the honest arm's 80 (#2673).** The extra runs buy exactly one thing: a
     * [RelocationRig.prefixSafety] count large enough to put a *frequency floor* under the `e1`
     * tolerance. Reaching that case is a tail event — the residue an under-ack strands has to
     * exceed `e1`'s cover **and** be spent through `g3` before the run ends — and it is a
     * **chaotic** one, not a smooth function of the generator's knobs: measured at 400 runs,
     * biasing the coin to ¾ moved the count *down* (3 → 2), so any perturbation re-samples it. The
     * two levers are therefore bias (which multiplies the per-run rate ~2–3×, to ≈1.8%) and sample
     * size (which is what makes the count stable).
     *
     * **1,200 rather than 600 is where the statistic stops swinging, and that is measured, not
     * assumed.** Re-seeding the arm (the honest stand-in for an unrelated generator tweak, since
     * both re-randomise the same chaotic map) gives **3, 7, 13, 18** at 600 runs and **14, 16, 20,
     * 21, 22, 23, 23** at 1,200. At 600 a floor with any headroom would have to sit below 3 — the
     * knife edge #2673 declined in the first place. At 1,200 the configured seed reads 22 and the
     * worst of seven trajectories is 14, so `>= 10` clears every one of them.
     *
     * The honest arm keeps its 80 deliberately: it has no tail statistic to stabilise, and neither
     * knob above reaches it (`&&` short-circuits the coin away, and [finalsFor] returns before the
     * magnitude draw), so its counters are byte-identical to what they were and its floors are
     * unchanged. Raising it would only cost wall-clock.
     *
     * **Not the inner step count.** Doubling `repeat(60)` to 120 was measured and is
     * *counter*productive — `prefixSafety` fell to 0 and the end-of-run margin on `e1` never went
     * negative at all, because the breach is a transient that later honest delegation across `e1`
     * washes out. Longer runs hide the case; more runs find it.
     */
    @Test
    fun anUnderAckedLeafFinalBreaksConservationByExactlyTheUnderAckedAmount() {
        val rnd = Random(0x1783C0DE)
        val rig = RelocationRig()
        repeat(1_200) { randomizedRunWithRelocation(rnd, minted = 1_000L, allowUnderAck = true, rig = rig) }
        assertAll(
            { assertTrue(rig.underAcks >= 600, "the under-ack rig fired too rarely — $rig") },
            { assertTrue(rig.moved >= 3_500, "too few generations actually moved — $rig") },
            { assertTrue(rig.accumulated >= 650, "the §12.3 accumulation arm fired too rarely — $rig") },
            {
                // The frequency floor (#2673) — reachability, asserted **beside** the magnitude
                // bound in [assertUnderAckIsAttributed] rather than in place of it. The two are
                // orthogonal: the bound reds when a move leaks more than the fed lie explains, this
                // reds when the arm stops reaching the tolerated case at all, and neither implies
                // the other. Dropping either loses a property.
                //
                // 10 against a measured 22 — the same ~2–3× headroom the sibling floors above carry.
                // It is not a tautology: reverting **both** bias knobs at this same run count reads
                // 7 and reds it, and `allowUnderAck = false` reads 0. Both were run.
                //
                // To re-derive: `prefixSafety` is printed by [RelocationRig.toString] only on
                // failure, so add a throwaway `{ assertTrue(false, "$rig") }` arm here and read the
                // count out of the failure message — on a native target as well as the JVM, because
                // a claim about what this counter reaches is a claim about every target (#2592).
                assertTrue(
                    rig.prefixSafety >= 10,
                    "the strand's strict prefix `e1` stopped reaching per-edge safety — the " +
                        "tolerance for it in assertUnderAckIsAttributed is now waving through a " +
                        "case nothing exercises — $rig",
                )
            },
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // The ROLL-UP half of a generation move (issue #2367)
    //
    // Everything above moves a LEAF-inbound rung. `g3` is a leaf, so no `g1 → g3` rung is ever a
    // strict prefix of a spend path: `rollupSpent` on one is structurally `0`, every move carries
    // `rsp = 0`, and the ROLLUP_RELOC_* families are never written. This ladder is the complement —
    // rungs on `root → g1`, a strict PREFIX of every `g3` spend path — so a spend at `g3` charges
    // `leafSpent(e3)` AND `rollupSpent(the live g1 rung)`, and retiring that rung moves a non-zero
    // `rsp`.
    //
    // The two halves are mutually exclusive by construction rather than by choice: an edge carries
    // `leafSpent` exactly when it is some path's FINAL edge, and a final edge is never a strict
    // prefix. So one move never carries both, a pure prefix ladder writes no leaf relocation at
    // all, and — the consequence worth naming — the effective and base LEAF conservation forms
    // COINCIDE here. That is asserted below rather than left to be re-derived.
    //
    // The arithmetic is not the leaf case relabelled. The child of a relocated prefix rung is `g1`,
    // which is not the group whose spend was charged: `holdings(g1)` reads its own inbound edge's
    // net inflow minus `netInflow(e3)`, and the move restores the first while touching neither the
    // second nor anything at `g3`. `rollupSpent` is a term of NEITHER `holdings` NOR
    // `leafSpentTotal()`, which is what makes the under-ack arm below come out the other way from
    // #1783 — see [anUnderAckedRollUpFinalStrandsAResidueWithoutBreakingConservation].
    // ─────────────────────────────────────────────────────────────────────────

    /** `g1`'s inbound **generation ladder** — the strict-prefix complement of [rung]. [e1] is rung `0`. */
    private fun prefixRung(i: Int): AttachmentId = if (i == 0) e1 else AttachmentId("e1.gen$i")

    /** One deliberately under-acked ROLL-UP final, and the residue it strands on the dead rung. */
    private data class UnderAckedRollup(
        val strand: AttachmentId,
        val replica: ReplicaId,
        val delta: Long,
    )

    /**
     * What the prefix run reached. [carriedRollup] is the arm this ladder exists for, and it is
     * counted from the move's **inputs** — the acked finals plus the strand's own relocation state,
     * i.e. the same `rsp` the derivation is specified to compute — never from the patch's effect. A
     * counter read off the effect would fall to zero in lockstep with the very write it is supposed
     * to prove fired, and the arm would report itself unreached instead of the assertion reporting a
     * break.
     */
    private class RollupRig {
        var moved = 0
        var carriedRollup = 0
        var refused = 0
        var nothingToMove = 0
        var nested = 0
        var accumulatedRollup = 0
        var underAcks = 0
        var residues = 0

        override fun toString(): String =
            "moved=$moved carriedRollup=$carriedRollup refused=$refused nothingToMove=$nothingToMove " +
                "nested=$nested accumulatedRollup=$accumulatedRollup underAcks=$underAcks residues=$residues"
    }

    /** `Σ_r effRollupSpent(edge)[r]` — base + `rollupRelocIn` − `rollupRelocOut`, off the stored slots. */
    private fun EntitlementLedger.effRollupOn(edge: AttachmentId): Long = replicas.sumOf {
        storedSlot(CounterFamily.ROLLUP_SPENT, edge, it) +
            storedSlot(CounterFamily.ROLLUP_RELOC_IN, edge, it) -
            storedSlot(CounterFamily.ROLLUP_RELOC_OUT, edge, it)
    }

    /** `Σ_r effLeafSpent(edge)[r]`, the same way — the partner term of [effRollupOn]. */
    private fun EntitlementLedger.effLeafOn(edge: AttachmentId): Long = replicas.sumOf {
        storedSlot(CounterFamily.LEAF_SPENT, edge, it) +
            storedSlot(CounterFamily.LEAF_RELOC_IN, edge, it) -
            storedSlot(CounterFamily.LEAF_RELOC_OUT, edge, it)
    }

    /**
     * `Σ_e outstanding(e)` — the one conservation form roll-up charge is actually a term of.
     *
     * `holdings` and `leafSpentTotal()` both ignore `rollupSpent` entirely, so the identity the rest
     * of this file asserts cannot see a roll-up move at all. [EdgeSummary.outstanding] can:
     * `issued − returned − spent`, and `spent` is effective leaf **plus** effective roll-up. It is
     * not invariant under the ordinary mutators (a spend drops it once per edge on the path), so it
     * is asserted across a single move rather than per step.
     */
    private fun EntitlementLedger.outstandingTotal(): Long = allEdges().sumOf {
        checkNotNull(edge(it)) { "allEdges() named ${it.value}, which edge() does not know" }.outstanding
    }

    /**
     * Conservation and the roll-up relocation identity, after every step of the prefix run.
     *
     * There is deliberately **no residual term**: an under-acked *roll-up* final does not move
     * conservation at all, which is this file's answer to §6.5.2 — see
     * [anUnderAckedRollUpFinalStrandsAResidueWithoutBreakingConservation].
     */
    private fun assertPrefixLadderInvariants(l: EntitlementLedger, minted: Long) {
        var sumHoldings = 0L
        for (g in allGroups) for (r in replicas) sumHoldings += l.holdings(g, r)
        val rollIn = l.storedTotal(CounterFamily.ROLLUP_RELOC_IN)
        val rollOut = l.storedTotal(CounterFamily.ROLLUP_RELOC_OUT)
        assertAll(
            { assertEquals(rollOut, rollIn, "a move must send ROLL-UP charge in equal and opposite amounts") },
            { assertEquals(minted, sumHoldings + l.leafSpentTotal(), "conservation broke (effective form)") },
            {
                assertEquals(
                    minted,
                    sumHoldings + l.storedTotal(CounterFamily.LEAF_SPENT),
                    "conservation broke (base form)",
                )
            },
            {
                assertEquals(
                    0L,
                    l.storedTotal(CounterFamily.LEAF_RELOC_IN) + l.storedTotal(CounterFamily.LEAF_RELOC_OUT),
                    "the LEAF half of a move is structurally zero on a PREFIX ladder — g1 is never a leaf, " +
                        "so no rung is ever a path's final edge. Asserted because it is precisely what " +
                        "collapses the two conservation forms above onto each other here: give g1 a " +
                        "spendable shape and they stop being one claim, and this reds rather than letting " +
                        "one silently stand in for two",
                )
            },
            {
                // The test's own slot sums against production's read of the same counters. Without it
                // a broken derivation and a broken test-side sum are indistinguishable — and it is the
                // only assertion here that an `allReplicas()`/`counterValue` regression reddens.
                for (e in l.allEdges()) {
                    assertEquals(
                        l.effLeafOn(e) + l.effRollupOn(e),
                        checkNotNull(l.edge(e)) { "allEdges() named ${e.value}" }.spent,
                        "production's edge(${e.value}).spent disagrees with the test's own slot sum",
                    )
                }
            },
            { assertEquals(minted, l.mintedTotal(), "minted total drifted") },
        )
    }

    /**
     * One seeded run of the base mutators plus a **prefix** re-home, with
     * [assertPrefixLadderInvariants] after every step.
     *
     * [allowUnderAck] understates a charging replica's `rollupSpent` final — the roll-up analogue of
     * the #1783 shape the leaf runs carry.
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun prefixRunWithRelocation(
        rnd: Random,
        minted: Long,
        allowUnderAck: Boolean,
        rig: RollupRig,
    ) {
        var ledger = seeded(minted)
        var live = prefixRung(0)
        var generation = 0
        val underAcked = ArrayList<UnderAckedRollup>()
        assertPrefixLadderInvariants(ledger, minted)

        // Same raced advisory retire as the leaf ladder, one level up: the fresh rung is a
        // `root → g1` generation, so `g3` keeps its single inbound `e3` throughout and the group
        // being re-homed (`g1`) is NOT the group whose spend the moved charge recorded.
        fun reshape(l: EntitlementLedger, from: AttachmentId): Pair<EntitlementLedger, AttachmentId> {
            val fresh = prefixRung(++generation)
            var out = l.applying(l.close(from))
            out = out.piece(EntitlementLedger.of(lifecycle = mapOf(from to Lifecycle.RETIRED)))
            out = out.applying(out.prepare(AttachmentRecord(fresh, root, g1, Weight.ONE)))
            return out.applying(out.activate(fresh)) to fresh
        }

        // Honest = each replica's true base slots. Under-acked = one ROLL-UP-charging replica's
        // final lowered below its base, so `rsp` under-reports what the strand actually carried.
        fun finalsFor(
            l: EntitlementLedger,
            strand: AttachmentId,
            underAck: Boolean,
        ): Pair<Map<ReplicaId, SlotFinals>, UnderAckedRollup?> {
            val honest = l.baseFinalsOn(strand)
            if (!underAck) return honest to null
            val chargers = honest.chargersIn { it.rollupSpent }
            if (chargers.isEmpty()) return honest to null // nothing rolled up here: nothing to understate
            val victim = chargers.random(rnd)
            val base = victim.value.rollupSpent
            val delta = rnd.nextLong(1L, base + 1L)
            val understated = honest + mapOf(victim.key to victim.value.copy(rollupSpent = base - delta))
            return understated to UnderAckedRollup(strand, victim.key, delta)
        }

        // The THREE-step conjunction the roll-up families need: fund `root → g1` across the live
        // rung, fund `g1 → g3` across e3, then spend at g3 — only the third writes `rollupSpent` on
        // the rung, and only the first two make it possible. The leaf ladder needed two steps in
        // order and the base mutators drew it ~35 times in 4,800 (#2365); three is strictly worse,
        // so it is drawn explicitly rather than left to coincide.
        fun fundAndSpendThrough(l: EntitlementLedger, actor: ReplicaId, rung: AttachmentId): EntitlementLedger {
            val atRoot = maxOf(0L, minOf(l.holdings(root, actor), 40L))
            if (atRoot < 1L) return l
            var out = l.applying(l.delegate(actor, rung, rnd.nextLong(1L, atRoot + 1L)))
            val atG1 = maxOf(0L, minOf(out.holdings(g1, actor), 40L))
            if (atG1 < 1L) return out
            out = out.applying(out.delegate(actor, e3, rnd.nextLong(1L, atG1 + 1L)))
            val atG3 = maxOf(0L, minOf(out.holdings(g3, actor), 40L))
            if (atG3 < 1L) return out
            return out.applying(out.spend(actor, g3, rnd.nextLong(1L, atG3 + 1L)))
        }

        /**
         * Give a rung minted **inside a step** both cover and roll-up charge, in that order — the
         * funding [relocateTwiceOntoOnePrefixRung]'s second strand needs, which [fundAndSpendThrough]
         * cannot supply.
         *
         * Two knobs here are the whole difficulty, and each was measured rather than reasoned about:
         *  - **No delegation down to `g3`.** While the old rung is still stranded, `holdings(g1)` is
         *    underwater by exactly what the pending move has yet to restore — and it cannot be topped
         *    up, because the missing credit *is* the stranded credit. So the charge comes from the
         *    actor's existing pocket at `g3` (funded through `e3`, which no reshape of a `root → g1`
         *    rung touches). Delegating down first is the natural-looking spelling and reaches the
         *    ladder ~0% of the time.
         *  - **The spend is capped at the cover just delegated.** A spend at `g3` charges
         *    `rollupSpent` on whichever prefix rung is live *now*, so an uncapped one lands charge on
         *    a rung whose cover sits on its stranded sibling, and `relocationPatch`'s
         *    `cover < charge` precondition refuses the move — 513 refusals per arm, measured.
         *
         * The actor is drawn by trying all three and keeping the first whose chain actually charges
         * the rung; an unreached conjunction is the failure mode this fixture keeps rediscovering.
         */
        fun coverAndCharge(l: EntitlementLedger, rung: AttachmentId): EntitlementLedger {
            for (actor in replicas.shuffled(rnd)) {
                val atRoot = maxOf(0L, minOf(l.holdings(root, actor), 40L))
                if (atRoot < 1L) continue
                val cover = rnd.nextLong(1L, atRoot + 1L)
                val funded = l.applying(l.delegate(actor, rung, cover))
                val cap = maxOf(0L, minOf(funded.holdings(g3, actor), cover))
                if (cap < 1L) continue
                return funded.applying(funded.spend(actor, g3, rnd.nextLong(1L, cap + 1L)))
            }
            return l
        }

        /** `Σ_r rsp` on [strand] under [finals] — the magnitude the derivation is specified to move. */
        fun rollupCarried(l: EntitlementLedger, strand: AttachmentId, finals: Map<ReplicaId, SlotFinals>): Long =
            finals.entries.sumOf { (r, acked) ->
                acked.rollupSpent +
                    l.storedSlot(CounterFamily.ROLLUP_RELOC_IN, strand, r) -
                    l.storedSlot(CounterFamily.ROLLUP_RELOC_OUT, strand, r)
            }

        // Clamped to what the actor holds: an over-large spend is a no-op the property cannot fail
        // on, and a starved spend arm is a starved roll-up arm — the rung's `rollupSpent` has no
        // other source.
        fun spendAtLeaf(l: EntitlementLedger, actor: ReplicaId, leaf: GroupId): EntitlementLedger {
            val cap = maxOf(0L, minOf(l.holdings(leaf, actor), 40L))
            if (cap < 1L) return l
            return l.applying(l.spend(actor, leaf, rnd.nextLong(1L, cap + 1L)))
        }

        fun relocateOnce(l: EntitlementLedger, underAck: Boolean): EntitlementLedger {
            val strand = live
            val (staged, fresh) = reshape(l, strand)
            val wasNested = replicas.any { staged.storedSlot(CounterFamily.ISSUED_RELOC_IN, strand, it) > 0L }
            val (finals, under) = finalsFor(staged, strand, underAck)
            // `rsp = acked.rollupSpent + rollupRelocIn(s) − rollupRelocOut(s)`, summed over the acking
            // replicas — read off the INPUTS the derivation is specified over, so a mutation of the
            // derivation moves the assertion and not the counter that is supposed to police it.
            val rspTotal = rollupCarried(staged, strand, finals)
            val creditBefore = replicas.sumOf { staged.storedSlot(CounterFamily.ROLLUP_RELOC_IN, fresh, it) }
            val debitBefore = replicas.sumOf { staged.storedSlot(CounterFamily.ROLLUP_RELOC_OUT, strand, it) }
            val outstandingBefore = staged.outstandingTotal()
            return when (val move = staged.relocationPatch(fresh, mapOf(strand to finals))) {
                is Relocation.Moved -> {
                    val after = staged.piece(move.patch)
                    rig.moved++
                    if (rspTotal > 0L) rig.carriedRollup++
                    if (wasNested) rig.nested++
                    assertAll(
                        {
                            assertEquals(
                                rspTotal,
                                replicas.sumOf { after.storedSlot(CounterFamily.ROLLUP_RELOC_IN, fresh, it) } -
                                    creditBefore,
                                "a move must credit ${fresh.value} with the strand's WHOLE effective roll-up charge",
                            )
                        },
                        {
                            assertEquals(
                                rspTotal,
                                replicas.sumOf { after.storedSlot(CounterFamily.ROLLUP_RELOC_OUT, strand, it) } -
                                    debitBefore,
                                "…and debit exactly that much off ${strand.value}",
                            )
                        },
                        {
                            assertEquals(
                                outstandingBefore,
                                after.outstandingTotal(),
                                "a move must not create or destroy outstanding entitlement — this is the one " +
                                    "conservation form roll-up charge is a term of",
                            )
                        },
                        {
                            assertEquals(
                                under?.delta ?: 0L,
                                after.effRollupOn(strand),
                                "${strand.value} must be left carrying exactly the roll-up its ack failed to " +
                                    "hand over — 0 on an honest ack",
                            )
                        },
                    )
                    if (under != null) {
                        rig.underAcks++
                        if (after.effRollupOn(strand) > 0L) rig.residues++
                        underAcked += under
                    }
                    live = fresh
                    after
                }
                // Both non-moves discard the whole staged reshape, so the ledger the next assertion
                // sees is the one the step started from — never a half-applied fence.
                is Relocation.Refused -> { rig.refused++; l }
                Relocation.Nothing -> { rig.nothingToMove++; l }
            }
        }

        // §12.3, the ROLL-UP half: two stranded prefix rungs re-homed onto ONE live rung, in two
        // moves, the second derived from a view that merged the first. Their roll-up credits must
        // ACCUMULATE on the live edge; a max-collide would swallow the smaller one.
        //
        // This shape is here because [relocateOnce] cannot reach it and a mutation proved so: every
        // re-home there lands on a rung `reshape` has just minted, where `rollupRelocIn` is `0`, so
        // `max(standing, add)` and `standing + add` agree and rewriting the live-edge accumulation
        // as a max stayed GREEN across both prefix arms. The leaf ladder's §12.3 shape does not
        // cover it either — that one asserts on `ISSUED_RELOC_IN` alone.
        fun relocateTwiceOntoOnePrefixRung(l: EntitlementLedger, underAck: Boolean): EntitlementLedger {
            val strandA = live
            val (withB, edgeB) = reshape(l, strandA)
            // Cover and charge B, so the second move carries a roll-up magnitude a max-collide could
            // swallow. Nothing else writes B: it is minted inside this step.
            val staged = coverAndCharge(withB, edgeB)
            val (withC, edgeC) = reshape(staged, edgeB)
            val wasNested = replicas.any { withC.storedSlot(CounterFamily.ISSUED_RELOC_IN, strandA, it) > 0L }
            val (finalsA, under) = finalsFor(withC, strandA, underAck)
            val rspA = rollupCarried(withC, strandA, finalsA)

            val first = withC.relocationPatch(edgeC, mapOf(strandA to finalsA))
            if (first !is Relocation.Moved) {
                if (first is Relocation.Refused) rig.refused++ else rig.nothingToMove++
                return l
            }
            val afterFirst = withC.piece(first.patch)

            val standing = replicas.associateWith { afterFirst.storedSlot(CounterFamily.ROLLUP_RELOC_IN, edgeC, it) }
            val finalsB = afterFirst.baseFinalsOn(edgeB)
            // Per replica, off the PRE-move state — the same `rsp` the KDoc says the second move
            // re-homes, not a figure recomputed from the patch it is meant to police.
            val moving = replicas.associateWith { r ->
                (finalsB[r]?.rollupSpent ?: 0L) +
                    afterFirst.storedSlot(CounterFamily.ROLLUP_RELOC_IN, edgeB, r) -
                    afterFirst.storedSlot(CounterFamily.ROLLUP_RELOC_OUT, edgeB, r)
            }
            val outstandingBefore = afterFirst.outstandingTotal()
            val second = afterFirst.relocationPatch(edgeC, mapOf(edgeB to finalsB))
            if (second !is Relocation.Moved) {
                if (second is Relocation.Refused) rig.refused++ else rig.nothingToMove++
                return l
            }
            val afterSecond = afterFirst.piece(second.patch)

            assertAll(
                {
                    for (r in replicas) {
                        assertEquals(
                            standing.getValue(r) + moving.getValue(r),
                            afterSecond.storedSlot(CounterFamily.ROLLUP_RELOC_IN, edgeC, r),
                            "a second move onto ${edgeC.value} must ACCUMULATE ${r.value}'s roll-up charge " +
                                "onto the standing credit, not collide with it (§12.3)",
                        )
                    }
                },
                {
                    assertEquals(
                        outstandingBefore,
                        afterSecond.outstandingTotal(),
                        "the second move must not create or destroy outstanding entitlement",
                    )
                },
                {
                    assertEquals(
                        under?.delta ?: 0L,
                        afterSecond.effRollupOn(strandA),
                        "${strandA.value} must be left carrying exactly the roll-up its ack failed to hand over",
                    )
                },
            )

            // Only the arm where `max` and `+` actually differ: with no standing credit, or nothing
            // to add, the assertion above holds under either spelling and proves nothing.
            if (replicas.any { standing.getValue(it) > 0L && moving.getValue(it) > 0L }) rig.accumulatedRollup++
            rig.moved += 2
            if (rspA > 0L) rig.carriedRollup++
            if (moving.values.sum() > 0L) rig.carriedRollup++
            if (wasNested) rig.nested++
            if (under != null) {
                rig.underAcks++
                if (afterSecond.effRollupOn(strandA) > 0L) rig.residues++
                underAcked += under
            }
            live = edgeC
            return afterSecond
        }

        repeat(60) {
            val actor = replicas.random(rnd)
            val edges = listOf(live, e2, e3)
            ledger = when (rnd.nextInt(8)) {
                0 -> ledger.applying(ledger.delegate(actor, edges.random(rnd), rnd.nextLong(1L, 40L)))
                1 -> ledger.applying(ledger.release(actor, edges.random(rnd), rnd.nextLong(1L, 40L)))
                2 -> {
                    // `g1` stays in the draw although a transfer there writes `transfers[PathKey.of(live)]`
                    // and #2366's precondition then refuses every later move off that rung — an absorbing
                    // state for the run that draws it. Kept because it was measured rather than feared:
                    // dropping `g1` bought back only a few percent of the moves, and keeping it is what
                    // puts the transfer-tangle refusal in the sequence. `rig.refused` is the census.
                    val to = replicas.filter { it != actor }.random(rnd)
                    ledger.applying(ledger.transfer(allGroups.random(rnd), actor, to, rnd.nextLong(1L, 40L)))
                }
                3 -> spendAtLeaf(ledger, actor, listOf(g2, g3).random(rnd))
                4, 5 -> fundAndSpendThrough(ledger, actor, live)
                6 -> relocateOnce(ledger, allowUnderAck && rnd.nextInt(2) == 0)
                else -> relocateTwiceOntoOnePrefixRung(ledger, allowUnderAck && rnd.nextInt(2) == 0)
            }
            assertPrefixLadderInvariants(ledger, minted)
        }

        if (underAcked.isEmpty()) {
            assertTrue(
                ledger.validate().isEmpty(),
                "an honest prefix-relocation run flagged a conflict: ${ledger.validate()}",
            )
        } else {
            assertRollupResidueIsAttributed(ledger, underAcked)
        }
    }

    /**
     * The control arm: rungs on the **prefix** edge `root → g1`, honest acks, conservation exact in
     * both forms after every step, `validate()` empty at the end — and every move's roll-up charge
     * credited and debited in equal and opposite amounts.
     */
    @Test
    fun conservationHoldsWhenAMovedRungCarriesRollUpChargeRatherThanLeafCharge() {
        val rnd = Random(0x2367C0DE)
        val rig = RollupRig()
        repeat(80) { prefixRunWithRelocation(rnd, minted = 1_000L, allowUnderAck = false, rig = rig) }
        assertAll(
            { assertEquals(0, rig.underAcks, "the control arm fed an under-ack — $rig") },
            { assertTrue(rig.moved >= 300, "too few generations actually moved — $rig") },
            {
                assertTrue(
                    rig.carriedRollup >= 300,
                    "too few moves carried a NON-ZERO roll-up charge — an empty move is a legitimate " +
                        "§5.4 idempotence case but proves nothing about the roll-up half — $rig",
                )
            },
            { assertTrue(rig.nested >= 200, "too few strands carried an earlier move's credit (§12.1) — $rig") },
            {
                assertTrue(
                    rig.accumulatedRollup >= 90,
                    "the §12.3 roll-up accumulation arm fired too rarely — with no standing credit on the " +
                        "live rung, `max` and `+` agree and the assertion proves nothing — $rig",
                )
            },
        )
    }

    /**
     * The treatment arm, and the answer to §6.5.2 — which claimed a **roll-up** residue is "not a
     * conservation break" with the same words #1783 disproved for a **leaf** one.
     *
     * Here the claim holds, and not by luck: `rollupSpent` is a term of neither [EntitlementLedger.holdings]
     * nor `leafSpentTotal()`, the two sides of the identity, because the conservation identity is
     * stated over *leaf* spend precisely so that it stays topology-independent. An under-acked leaf
     * final leaves charge behind inside a conservation term; an under-acked roll-up final leaves it
     * behind outside one. So conservation is asserted to hold **exactly** — no residual — while the
     * residue is still asserted to be real, to sit on the strand, and to be diagnosed there.
     *
     * What it costs is per-edge safety, permanently and unclearably, on a retired edge: the strand
     * reports [LedgerConflict.PerEdgeSafety] and — because its `outstanding` is now negative by the
     * residue — [LedgerConflict.ClosureViolation]. Nothing else moves; in particular no
     * [LedgerConflict.ConservationViolation], which the leaf arm has to tolerate.
     */
    @Test
    fun anUnderAckedRollUpFinalStrandsAResidueWithoutBreakingConservation() {
        val rnd = Random(0x6552C0DE)
        val rig = RollupRig()
        repeat(80) { prefixRunWithRelocation(rnd, minted = 1_000L, allowUnderAck = true, rig = rig) }
        assertAll(
            { assertTrue(rig.underAcks >= 50, "the under-ack rig fired too rarely — $rig") },
            {
                assertTrue(
                    rig.residues == rig.underAcks,
                    "an under-ack that left NO residue understated nothing — $rig",
                )
            },
            { assertTrue(rig.carriedRollup >= 300, "too few moves carried a non-zero roll-up charge — $rig") },
            { assertTrue(rig.moved >= 300, "too few generations actually moved — $rig") },
            {
                assertTrue(
                    rig.accumulatedRollup >= 90,
                    "the §12.3 roll-up accumulation arm fired too rarely — $rig",
                )
            },
        )
    }

    /**
     * The roll-up residue, asserted as a **named, attributed** per-edge fault — and explicitly *not*
     * as a conservation break.
     */
    private fun assertRollupResidueIsAttributed(l: EntitlementLedger, underAcked: List<UnderAckedRollup>) {
        val conflicts = l.validate()
        val strands = underAcked.map { it.strand }.toSet()
        assertAll(
            {
                for (u in underAcked) {
                    assertTrue(
                        LedgerConflict.PerEdgeSafety(u.strand) in conflicts,
                        "the roll-up residue on ${u.strand.value} is not diagnosed: $conflicts",
                    )
                }
            },
            {
                // Sharper than "a residue exists": it must be exactly what the ack withheld, and it
                // must sit on the withholding replica's OWN slot. An edge-wide `> 0` would be
                // satisfied by a residue the move misplaced onto a bystander.
                for (u in underAcked) {
                    val residue = l.storedSlot(CounterFamily.ROLLUP_SPENT, u.strand, u.replica) +
                        l.storedSlot(CounterFamily.ROLLUP_RELOC_IN, u.strand, u.replica) -
                        l.storedSlot(CounterFamily.ROLLUP_RELOC_OUT, u.strand, u.replica)
                    assertEquals(
                        u.delta,
                        residue,
                        "the residue on ${u.strand.value} must sit on ${u.replica.value}'s own slot and be " +
                            "exactly what the ack withheld — otherwise the under-ack rig never fired",
                    )
                }
            },
            {
                // The whole point of the arm: a roll-up residue must NOT reach the global backstop.
                val conservation = conflicts.filterIsInstance<LedgerConflict.ConservationViolation>()
                assertTrue(
                    conservation.isEmpty(),
                    "a roll-up residue is not supposed to be a conservation break, but one was reported: " +
                        "$conservation",
                )
            },
            {
                val stray = conflicts.filterNot {
                    it is LedgerConflict.PerEdgeSafety && it.edge in strands ||
                        it is LedgerConflict.ClosureViolation && it.edge in strands
                }
                assertTrue(stray.isEmpty(), "an under-acked roll-up run disturbed something else: $stray")
            },
        )
    }
}
