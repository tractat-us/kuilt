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

    /** minted == Σ holdings + Σ leafSpent, over every group and replica. */
    private fun assertConservation(ledger: EntitlementLedger, minted: Long) {
        var sumHoldings = 0L
        for (g in allGroups) for (r in replicas) sumHoldings += ledger.holdings(g, r)
        assertEquals(minted, sumHoldings + ledger.leafSpentTotal(), "conservation broke")
        assertEquals(minted, ledger.mintedTotal(), "minted total drifted")
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
         * Every draw's outcome, carried in each floor's failure message. A floor that reds then says
         * *where* the draws went rather than only that too few arrived — the non-moving outcomes are
         * how this fixture goes quietly vacuous, and diagnosing that from a bare count already cost
         * it one round (see the funding-chain comment in [randomizedRunWithRelocation]).
         */
        override fun toString(): String =
            "moved=$moved refused=$refused nothingToMove=$nothingToMove unfunded=$unfunded " +
                "nested=$nested accumulated=$accumulated underAcks=$underAcks"
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
            val chargers = honest.entries.filter { it.value.leafSpent > 0L }
            if (chargers.isEmpty()) return honest to null // nothing was charged here: nothing to understate
            val victim = chargers.random(rnd)
            val base = victim.value.leafSpent
            val delta = rnd.nextLong(1L, base + 1L)
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
                6 -> relocateOnce(ledger, allowUnderAck && rnd.nextInt(2) == 0)
                else -> relocateTwiceOntoOneEdge(ledger, allowUnderAck && rnd.nextInt(2) == 0)
            }
            assertConservationWithRelocation(ledger, minted, residual)
        }

        val conflicts = ledger.validate()
        assertEquals(
            0L,
            ledger.storedTotal(CounterFamily.ROLLUP_RELOC_IN) + ledger.storedTotal(CounterFamily.ROLLUP_RELOC_OUT),
            "the roll-up half of a move is NOT covered here: g3 is a leaf, so no rung is ever a " +
                "strict prefix. Asserted so that adding a level below g3 reds instead of silently " +
                "leaving this property proving only half the move",
        )
        if (underAcked.isEmpty()) {
            assertAll(
                { assertEquals(0L, residual, "no under-ack was fed, so there is no debt to carry") },
                { assertTrue(conflicts.isEmpty(), "an honest relocation run flagged a conflict: $conflicts") },
            )
        } else {
            assertUnderAckIsAttributed(ledger, conflicts, underAcked)
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
    ) {
        val strands = underAcked.map { it.strand }.toSet()
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
                // on the STRANDS themselves, plus the global backstop once the residue is large
                // enough to show in the totals. In particular no negative holdings, no dual inbound,
                // no negative effective spend, and no per-edge report on an edge that was never
                // under-acked — including the prefix `e1`, which the residue's spendable credit
                // could in principle roll up through and measurably does not.
                val stray = conflicts.filterNot {
                    it is LedgerConflict.PerEdgeSafety && it.edge in strands ||
                        it is LedgerConflict.ClosureViolation && it.edge in strands ||
                        it is LedgerConflict.ConservationViolation
                }
                assertTrue(stray.isEmpty(), "an under-acked run disturbed something outside the #1783 shape: $stray")
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
     */
    @Test
    fun anUnderAckedLeafFinalBreaksConservationByExactlyTheUnderAckedAmount() {
        val rnd = Random(0x1783C0DE)
        val rig = RelocationRig()
        repeat(80) { randomizedRunWithRelocation(rnd, minted = 1_000L, allowUnderAck = true, rig = rig) }
        assertAll(
            { assertTrue(rig.underAcks >= 25, "the under-ack rig fired too rarely — $rig") },
            { assertTrue(rig.moved >= 250, "too few generations actually moved — $rig") },
            { assertTrue(rig.accumulated >= 50, "the §12.3 accumulation arm fired too rarely — $rig") },
        )
    }
}
