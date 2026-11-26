package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A wanted edge's demand is lifted to a target `UNBOUNDED_TARGET` above what it already holds, so
 * an edge that competes also reads as demanding, however much it has outstanding (#3707).
 *
 * Each rig loads lane `a` with outstanding from **another writer**: its own mint, delegated down
 * `a`'s edge. That keeps this engine's own mint small, so its root can still fund a pick when `a`
 * holds close to `Long.MAX_VALUE`, which a merged ledger can reach and one engine's own counters
 * cannot. Every acquire is for `a` alone, so the pick at the root has `a` as its one candidate.
 */
class LocalHeddleRelativeTargetTest {

    private val policy = LocalPolicy(defaultReservation = R)
    private val tree = draft("root") {
        lane("a")
        lane("b")
        lane("c")
    }
    private val a = tree.groupId("a")
    private val b = tree.groupId("b")
    private val c = tree.groupId("c")
    private val all = setOf(a, b, c)

    /**
     * The positive control. Within one reservation of the old constant target, the constant lift
     * trimmed `a`'s grant to what was left below it, and the descent threw on a grant that was not
     * the reservation. Lifted from what `a` holds, the grant is the whole reservation.
     */
    @Test
    fun withinOneReservationOfTheOldTargetAnAcquireIsGranted() {
        val (h, edge) = loaded(UNBOUNDED_TARGET - R / 2)
        val before = outstandingOf(h, edge)
        val why = h.explain(a, all, setOf(a))
        val out = h.acquire(all, setOf(a), startedAt = 0L)
        assertAll(
            { assertEquals(UNBOUNDED_TARGET - R / 2, before, "the rig: a holds less than one reservation below the old target") },
            { assertEquals(WaitExplanation.WouldBePickedNext, why) },
            { assertTrue(out is AcquireOutcome.Acquired && out.lease.leaf == a, "a is leased its whole reservation, $out") },
            { assertEquals(UNBOUNDED_TARGET - R / 2 + R, outstandingOf(h, edge), "the reservation crossed a's edge whole") },
        )
    }

    /**
     * Just past where the lift saturates, `a` still has room below the lifetime watermark, so the
     * pick serves it and `explain` says so. The constant lift read it as idle: the acquire found no
     * candidate and `explain` threw.
     */
    @Test
    fun whereTheLiftSaturatesBelowTheWatermarkTheLaneIsStillServed() {
        val (h, edge) = loaded(Long.MAX_VALUE - UNBOUNDED_TARGET + 1L)
        val before = outstandingOf(h, edge)
        val why = h.explain(a, all, setOf(a))
        val out = h.acquire(all, setOf(a), startedAt = 0L)
        assertAll(
            { assertTrue(before > Long.MAX_VALUE - UNBOUNDED_TARGET, "the rig: a's lift saturates, at $before") },
            { assertEquals(WaitExplanation.WouldBePickedNext, why) },
            { assertTrue(out is AcquireOutcome.Acquired && out.lease.leaf == a, "a is leased, $out") },
        )
    }

    /** Past the lifetime watermark, `acquire` and `explain` both answer `LifetimeExhausted` at the root. */
    @Test
    fun pastTheWatermarkAcquireAndExplainAnswerLifetimeExhausted() {
        val (h, _) = loaded(LIFETIME_WATERMARK)
        assertAll(
            { assertEquals(WaitExplanation.LifetimeExhausted(h.root), h.explain(a, all, setOf(a))) },
            { assertEquals(AcquireOutcome.LifetimeExhausted(h.root), h.acquire(all, setOf(a), startedAt = 0L)) },
        )
    }

    /**
     * Within one reservation of `Long.MAX_VALUE` the saturated target leaves `a` less than the
     * reservation to ask for, so its grant is trimmed. The lifetime check comes first, so the pick
     * answers `LifetimeExhausted` rather than throw on the trimmed grant.
     */
    @Test
    fun withinOneReservationOfTheTopAnAcquireAnswersLifetimeExhaustedAndDoesNotThrow() {
        val (h, edge) = loaded(Long.MAX_VALUE - R / 2)
        assertAll(
            { assertTrue(Long.MAX_VALUE - outstandingOf(h, edge) in 1L until R, "the rig: a's saturated grant is below the reservation") },
            { assertEquals(WaitExplanation.LifetimeExhausted(h.root), h.explain(a, all, setOf(a))) },
            { assertEquals(AcquireOutcome.LifetimeExhausted(h.root), h.acquire(all, setOf(a), startedAt = 0L)) },
        )
    }

    /**
     * The one value where the two predicates still part, ruled on #3707: at exactly
     * `Long.MAX_VALUE` outstanding the saturated target equals it, so `a` competes, with a share,
     * but does not demand. The pick leaves it out and finds no candidate, while `explain` reads its
     * full lineage first.
     */
    @Test
    fun atExactlyLongMaxValueALaneCompetesButDoesNotDemand() {
        val (h, edge) = loaded(Long.MAX_VALUE)
        assertAll(
            { assertEquals(Long.MAX_VALUE, outstandingOf(h, edge), "the rig") },
            { assertTrue(h.shareLine(all).share(a) > Rational.ZERO, "a still competes") },
            { assertEquals(AcquireOutcome.NoLiveLineage(h.root), h.acquire(all, setOf(a), startedAt = 0L), "the pick counts a as idle") },
            { assertEquals(WaitExplanation.LifetimeExhausted(h.root), h.explain(a, all, setOf(a))) },
        )
    }

    /** The lift saturates exactly where `outstanding + UNBOUNDED_TARGET` leaves `Long`, and only `Long.MAX_VALUE` reads idle. */
    @Test
    fun theLiftSaturatesAtLongMaxValueAndOnlyThereReadsIdle() {
        val top = Long.MAX_VALUE - UNBOUNDED_TARGET
        assertAll(
            { assertEquals(Long.MAX_VALUE - 1L, LocalHeddle.wanted(top - 1L, R).targetOutstanding) },
            { assertEquals(Long.MAX_VALUE, LocalHeddle.wanted(top, R).targetOutstanding) },
            { assertEquals(Long.MAX_VALUE, LocalHeddle.wanted(top + 1L, R).targetOutstanding, "saturated") },
            { assertTrue(HeddlePolicy.isDemanding(policyEdge(Long.MAX_VALUE - 1L)), "one below the top still demands") },
            { assertFalse(HeddlePolicy.isDemanding(policyEdge(Long.MAX_VALUE)), "the ruled exception: the top competes and does not demand") },
            { assertEquals(R, LocalHeddle.wanted(Long.MAX_VALUE, R).maximumUsefulGrant) },
        )
    }

    /**
     * A negative outstanding, which only a damaged ledger holds, lifts from zero: a raw lift below
     * `−UNBOUNDED_TARGET` would be a negative target, which [Demand] refuses. Down to
     * `−3 × UNBOUNDED_TARGET` the edge reads demanding, the policy's unchecked subtraction in range.
     */
    @Test
    fun aNegativeOutstandingLiftsFromZeroAndReadsDemanding() {
        val values = listOf(-1L, -UNBOUNDED_TARGET, -UNBOUNDED_TARGET - 1L, -2L * UNBOUNDED_TARGET, -3L * UNBOUNDED_TARGET)
        assertAll(
            { assertEquals(values.map { UNBOUNDED_TARGET }, values.map { LocalHeddle.wanted(it, R).targetOutstanding }) },
            { assertEquals(values.map { true }, values.map { HeddlePolicy.isDemanding(policyEdge(it)) }) },
        )
    }

    /**
     * A reload that moves two leases under one parent reads, for the second, the ledger the first
     * left. Every lane waits throughout, and `a` has run ahead of `c`. The reload gives `p` and `b`
     * new edges and keeps `a`'s and `c`'s. `a`'s lease moves first, across `p`'s new edge, and
     * leaves its reservation outstanding on `a`'s edge; `b`'s moves second, and seats `b`'s new
     * edge, the one unseated child of `p`, at `p`'s front: the mean of `a` and `c`, both demanding.
     * Read as idle, `a` would leave the front at `c` alone.
     */
    @Test
    fun aReloadThatMovesTwoLeasesSeatsTheSecondAtAFrontThatCountsTheFirst() {
        fun topology(pW: Long, bW: Long) = draft("root") {
            group("p", Weight.of(pW)) {
                lane("a")
                lane("b", Weight.of(bW))
                lane("c")
            }
            lane("s")
        }
        val before = topology(1L, 1L)
        val p = before.groupId("p")
        val pa = before.groupId("p", "a")
        val pb = before.groupId("p", "b")
        val pc = before.groupId("p", "c")
        val s = before.groupId("s")
        val everyone = setOf(pa, pb, pc, s)
        val h = LocalHeddle.bootWhole(before, policy, epoch = 0L).also { it.mintSupply(10_000L) }
        repeat(4) { h.complete(h.lease(everyone, reservation = R).id, R, waiting = everyone) }
        repeat(3) { h.complete(checkNotNull(h.acquire(everyone, setOf(pa), startedAt = 0L, reservation = R) as? AcquireOutcome.Acquired).lease.id, R, waiting = everyone) }
        val first = checkNotNull(h.acquire(everyone, setOf(pa), startedAt = 0L, reservation = R) as? AcquireOutcome.Acquired).lease
        val second = checkNotNull(h.acquire(everyone, setOf(pb), startedAt = 0L, reservation = R) as? AcquireOutcome.Acquired).lease
        val booted = h.snapshot().ledger
        val edgeOf = booted.activeChildren(p).associate { checkNotNull(booted.record(it.attachment)).child to it.attachment }
        val result = h.apply(topology(2L, 2L), generation = 1L)
        val l = h.snapshot().ledger
        val bEdge = checkNotNull(l.lineageOf(pb)).last()
        val aAt = checkNotNull(h.allocator.effectiveVirtualService(l, p, edgeOf.getValue(pa)))
        val cAt = checkNotNull(h.allocator.effectiveVirtualService(l, p, edgeOf.getValue(pc)))
        val seat = checkNotNull(l.virtualService(bEdge)) - Rational.of(R, 2L)
        assertAll(
            { assertEquals(emptyList(), result.refusals) },
            { assertTrue(first.id < second.id, "the rig: a's lease moves first") },
            { assertEquals(edgeOf.getValue(pa), checkNotNull(h.capturedPath(first.id)).last(), "the rig: a keeps its edge") },
            { assertEquals(R, checkNotNull(l.edge(edgeOf.getValue(pa))).outstanding, "the rig: a's moved reservation is outstanding on its edge") },
            { assertTrue(bEdge != edgeOf.getValue(pb), "the rig: b has a new edge") },
            { assertEquals(bEdge, checkNotNull(h.capturedPath(second.id)).last(), "the rig: b's lease moved onto it") },
            { assertEquals(true, h.allocator.lastObservedDemanding(edgeOf.getValue(pa)), "the rig: a was last seen asking") },
            { assertEquals(true, h.allocator.lastObservedDemanding(edgeOf.getValue(pc)), "the rig: c was last seen asking") },
            { assertTrue(aAt > cAt, "the rig: a, at $aAt, is ahead of c, at $cAt") },
            { assertEquals((aAt + cAt) / Rational.of(2L), seat, "b's new edge is seated at the mean of a and c, then advanced by its own lease") },
        )
    }

    /**
     * Up to `UNBOUNDED_TARGET − MAX_RESERVATION` outstanding, the relative lift and the old constant
     * one both ask every wanted edge for more than a reservation, so they pick alike. Over a fixed
     * set of `a`'s outstanding, from zero to that bound (both ends, the boundaries around one
     * reservation, some powers of two and a few seeded points), each walked through eight picks with
     * varied eligible sets: the same leaf, levels, ledger, fronts and wake memory, exactly. The
     * constant view is the relative one with only the target replaced, so the two agree on who is
     * wanted and differ only in what they ask.
     */
    @Test
    fun belowTheOldTargetTheLiftPicksExactlyAsTheConstantOneDid() {
        val high = UNBOUNDED_TARGET - LocalHeddle.MAX_RESERVATION
        val rng = Random(3707)
        val points = listOf(0L, 1L, R - 1L, R, R + 1L, 1L shl 20, 1L shl 44, 1L shl 50, 1L shl 58, 1L shl 60, high / 2, high - 1L, high) +
            List(5) { rng.nextLong(0L, high + 1L) }
        val eligibles = listOf(all, setOf(a), setOf(b), setOf(a, c), setOf(b, c), all, setOf(c), setOf(a, b))
        var steps = 0
        for (outstanding in points) {
            val (h, edge) = loaded(outstanding)
            check(outstandingOf(h, edge) == outstanding) { "the rig at $outstanding" }
            val relative = trial(h)
            val constant = trial(h)
            var onRelative = h.snapshot().ledger
            var onConstant = onRelative
            for ((i, eligible) in eligibles.withIndex()) {
                val at = "a at $outstanding, step $i"
                val relativeView = relative.demands(onRelative, all, eligible, R)
                val constantView = constantOf(constant.demands(onConstant, all, eligible, R))
                val rel = relative.descend(onRelative, relativeView, eligible, R)
                val con = constant.descend(onConstant, constantView, eligible, R)
                check(rel is LocalHeddle.Descent.Reached && con is LocalHeddle.Descent.Reached) { "$at: $rel, $con" }
                assertEquals(con.leaf, rel.leaf, "$at: the leaf")
                assertEquals(con.levels.map { it.parent to it.edge }, rel.levels.map { it.parent to it.edge }, "$at: the levels")
                assertEquals(con.ledger, rel.ledger, "$at: the ledger, seats included")
                for ((level, other) in rel.levels.zip(con.levels)) {
                    assertEquals(
                        constant.allocator.virtualTimeWide(other.seated, other.parent, constantView.waiting),
                        relative.allocator.virtualTimeWide(level.seated, level.parent, relativeView.waiting),
                        "$at: the front at ${level.parent.value}",
                    )
                }
                assertEquals(constant.allocator.memory, relative.allocator.memory, "$at: the wake memory")
                onRelative = rel.ledger
                onConstant = con.ledger
                steps++
            }
        }
        assertEquals(points.size * eligibles.size, steps, "every point walked every step")
    }

    // ── Fixtures ────────────────────────────────────────────────────────────────────────────────

    /** A throwaway engine over [h]'s ledger with a fork of its wake memory and no capacity: one side of a differential. */
    private fun trial(h: LocalHeddle): LocalHeddle =
        LocalHeddle.scratch(h.root, policy, 0L, h.snapshot().ledger, h.allocator.fork(), Leasable.ByTopology(tree), Capacity.NONE, emptyMap(), emptyMap())

    /** [demands] with every wanted edge's target put back to the constant `UNBOUNDED_TARGET`, as before #3707. */
    private fun constantOf(demands: LocalHeddle.Demands): LocalHeddle.Demands {
        fun constant(view: (AttachmentId) -> Demand): (AttachmentId) -> Demand = { edge ->
            val d = view(edge)
            if (d == Demand.NONE) d else Demand(UNBOUNDED_TARGET, d.maximumUsefulGrant)
        }
        return LocalHeddle.Demands(constant(demands.waiting), constant(demands.eligible))
    }

    /** `root → a` holding [outstanding], asking for the target [LocalHeddle.wanted] lifts it to. */
    private fun policyEdge(outstanding: Long): PolicyEdge {
        val id = AttachmentId("root>a")
        // issued − returned − spent = outstanding, with nothing issued for a negative one.
        val summary = if (outstanding >= 0L) EdgeSummary(id, outstanding, 0L, 0L) else EdgeSummary(id, 0L, -outstanding, 0L)
        return PolicyEdge(
            record = AttachmentRecord(id, GroupId("root"), GroupId("root/a"), Weight.ONE),
            summary = summary,
            demand = LocalHeddle.wanted(outstanding, R),
            gauge = null,
            baseIssued = summary.issued,
        )
    }

    /**
     * An engine over [tree] whose lane `a` holds exactly [outstanding] units: one lease on each
     * lane, all waiting, so every edge is seated and seen asking, then another writer's delegation
     * down `a`'s edge for the rest. Below one reservation `a`'s own lease is cancelled first. Its
     * own root keeps enough to fund a few dozen more reservations.
     */
    private fun loaded(outstanding: Long): Pair<LocalHeddle, AttachmentId> {
        val h = LocalHeddle.bootWhole(tree, policy, epoch = 0L).also { it.mintSupply(100L * R) }
        val leases = listOf(a, b, c).map { lane ->
            checkNotNull(h.acquire(all, setOf(lane), startedAt = 0L) as? AcquireOutcome.Acquired) { "the rig: lease ${lane.value}" }.lease
        }
        if (outstanding < R) h.cancel(leases.first().id)
        val edge = checkNotNull(h.snapshot().ledger.lineageOf(a)).last()
        val rest = outstanding - outstandingOf(h, edge)
        if (rest > 0L) h.reshapeForTest { l ->
            val minted = l.piece(l.mint(MintId("other#0"), h.root, OTHER, rest).delta)
            minted.piece(checkNotNull(minted.delegate(OTHER, edge, rest)) { "the other writer's delegation down a" }.delta)
        }
        return h to edge
    }

    private fun outstandingOf(h: LocalHeddle, edge: AttachmentId): Long = checkNotNull(h.snapshot().ledger.edge(edge)).outstanding

    private companion object {
        const val R = 4L

        /** The constant a wanted edge's target was before #3707, and how far above what it holds the target sits now. */
        const val UNBOUNDED_TARGET: Long = LocalHeddle.UNBOUNDED_TARGET

        val OTHER = ReplicaId("other")
    }
}
