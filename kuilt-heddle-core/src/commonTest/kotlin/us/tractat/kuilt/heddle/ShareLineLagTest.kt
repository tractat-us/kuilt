package us.tractat.kuilt.heddle

import us.tractat.kuilt.math.Int128
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [ShareLine.lag] (#3551): `weight × (front − position)`, in units of service, read at a group's
 * parent as a pick arriving there now would read it.
 *
 * The oracle counts the units each lease reserved and splits them by the weights the test chose,
 * never reading the ledger. The properties (the lags at a parent add up to zero, a grant moves
 * them by its share, the order of `waiting` changes nothing, asking moves no memory) run over
 * [ViewHistories]' random histories, which wake, return and seat children as they go. Every
 * seeded draw walks a `List` the test built, never a `Set` or a map view.
 */
class ShareLineLagTest {

    private val policy = LocalPolicy(defaultReservation = 1L)

    /**
     * Integer weights, every lane waiting from boot, each lease completed at exactly what it
     * reserved: then a group's lag is its weight's part of everything its parent's competing
     * children were handed, less what it got itself. The test counts both from its own leases.
     */
    @Test
    fun aLagIsWhatItIsOwedOfTheLeasesCounted() {
        val tree = draft("root") {
            group("p", Weight.of(2)) {
                lane("a", Weight.of(3))
                lane("b")
            }
            group("q") { lane("c") }
            lane("s", Weight.of(3))
        }
        val root = tree.groupId()
        val p = tree.groupId("p")
        val q = tree.groupId("q")
        val a = tree.groupId("p", "a")
        val b = tree.groupId("p", "b")
        val c = tree.groupId("q", "c")
        val s = tree.groupId("s")
        val lanes = listOf(a, b, c, s)
        val everyone = lanes.toSet()
        val beneath = mapOf(p to listOf(a, b), q to listOf(c), a to listOf(a), b to listOf(b), c to listOf(c), s to listOf(s))
        val families = listOf(root to listOf(p to 2L, q to 1L, s to 3L), p to listOf(a to 3L, b to 1L), q to listOf(c to 1L))
        val h = LocalHeddle.bootWhole(tree, policy, epoch = 0L).also { it.mintSupply(100_000L) }
        val units = HashMap<GroupId, Long>()
        val rng = Random(3551)
        var checked = 0
        var owing = 0
        repeat(300) { step ->
            val eligible = lanes.filter { rng.nextInt(3) != 0 }.ifEmpty { listOf(lanes[rng.nextInt(lanes.size)]) }.toSet()
            val reservation = 1L + rng.nextInt(4)
            val out = h.acquire(everyone, eligible, startedAt = 0L, reservation = reservation)
            check(out is AcquireOutcome.Acquired) { "step $step: $out" }
            h.complete(out.lease.id, reservation, waiting = everyone)
            units[out.lease.leaf] = (units[out.lease.leaf] ?: 0L) + reservation
            if (step % 10 != 9) return@repeat
            val line = h.shareLine(everyone)
            assertNull(line.lag(root), "step $step: the root has no parent to stand behind")
            for ((parent, children) in families) {
                val got = children.map { (g, _) -> beneath.getValue(g).sumOf { units[it] ?: 0L } }
                val handedOut = got.sum()
                val weights = children.sumOf { it.second }
                for ((i, child) in children.withIndex()) {
                    val (g, w) = child
                    val owed = Rational.of(w * handedOut, weights) - Rational.of(got[i])
                    assertEquals(owed, line.lag(g), "step $step: ${g.value} beneath ${parent.value}")
                    checked++
                    if (owed > Rational.ZERO) owing++
                }
            }
        }
        assertAll(
            { assertEquals(30 * 6, checked, "every row of every line read") },
            { assertTrue(owing > 30, "rows owed something: $owing") },
        )
    }

    /** Over one competing parent's competing children the lags add up to zero, and only competing groups have one. */
    @Test
    fun theLagsBeneathAParentAddUpToZero() {
        var families = 0
        var unequal = 0
        forEachState { h, ledger, line, _ ->
            val groups = groupsOf(ledger)
            for (parent in groups) {
                val children = ledger.activeChildren(parent).mapNotNull { ledger.record(it.attachment)?.child }
                val competing = children.filter { line.share(it) > Rational.ZERO }
                for (child in children - competing.toSet()) assertNull(line.lag(child), "${child.value} does not compete")
                if (competing.isEmpty()) continue
                val lags = competing.map { checkNotNull(line.lag(it)) { "${it.value} competes" } }
                assertEquals(Rational.ZERO, lags.fold(Rational.ZERO, Rational::plus), "beneath ${parent.value}: $lags")
                families++
                if (lags.any { it != Rational.ZERO }) unequal++
            }
            assertNull(line.lag(h.root))
        }
        assertAll(
            { assertTrue(families > SEEDS, "competing parents: $families") },
            { assertTrue(unequal > SEEDS, "parents whose children stand apart: $unequal") },
        )
    }

    /**
     * A grant of `r` to a child `s` moves `s` by `−r · (1 − w_s/Σw)` and each competing sibling `t`
     * by `+r · w_t/Σw`, at every level the lease went down, and moves nothing anywhere else.
     */
    @Test
    fun aGrantMovesTheLagsByItsShare() {
        var levels = 0
        var stillParents = 0
        forEachState(questions = 4) { h, ledger, _, rng ->
            val waiting = ViewHistories.names.filter { rng.nextInt(3) != 0 }.toSet()
            val eligible = waiting.filter { rng.nextInt(4) != 0 }.toSet()
            val reservation = 1L + rng.nextInt(4)
            val before = h.shareLine(waiting)
            val out = h.acquire(waiting, eligible, startedAt = 0L, reservation = reservation)
            if (out !is AcquireOutcome.Acquired) return@forEachState
            val after = h.shareLine(waiting)
            val path = checkNotNull(ledger.lineageOf(out.lease.leaf)).map { checkNotNull(ledger.record(it)) }
            val pathParents = path.map { it.parent }.toSet()
            for (record in path) {
                val children = ledger.activeChildren(record.parent).mapNotNull { ledger.record(it.attachment) }.filter { before.lag(it.child) != null }
                val total = children.fold(Rational.ZERO) { sum, it -> sum + weightOf(it.weight) }
                for (child in children) {
                    val part = Rational.of(reservation) * weightOf(child.weight) / total
                    val moved = if (child.child == record.child) part - Rational.of(reservation) else part
                    assertEquals(checkNotNull(before.lag(child.child)) + moved, after.lag(child.child), "${child.child.value} beneath ${record.parent.value}")
                }
                levels++
            }
            for (parent in groupsOf(ledger).filter { it !in pathParents }) {
                for (child in ledger.activeChildren(parent).mapNotNull { ledger.record(it.attachment)?.child }) {
                    assertEquals(before.lag(child), after.lag(child), "${child.value}, off the lease's path")
                    stillParents++
                }
            }
        }
        assertAll(
            { assertTrue(levels > SEEDS, "levels a grant went down: $levels") },
            { assertTrue(stillParents > SEEDS, "rows off its path: $stillParents") },
        )
    }

    @Test
    fun theOrderWaitingIsGivenInChangesNothing() {
        forEachState { h, ledger, _, rng ->
            val waiting = ViewHistories.names.filter { rng.nextInt(3) != 0 }
            val sorted = h.shareLine(LinkedHashSet(waiting.sorted()))
            val reversed = h.shareLine(LinkedHashSet(waiting.sortedDescending()))
            for (g in groupsOf(ledger)) assertEquals(sorted.lag(g), reversed.lag(g), g.value)
        }
    }

    /**
     * Taking a line leaves the engine's wake memory as it found it, though the step it takes at
     * each parent moves the memory it runs on: counted on a fork, so the rig is not vacuous.
     */
    @Test
    fun askingMovesNoMemory() {
        var stepsThatMove = 0
        forEachState { h, ledger, _, rng ->
            val waiting = ViewHistories.names.filter { rng.nextInt(3) != 0 }.toSet()
            val before = h.allocator.memory
            h.shareLine(waiting)
            assertEquals(before, h.allocator.memory)
            val competing = ledger.reachable(waiting, h.root, Leasable.ByTopology(h.persist().lastApplied))
            val fork = h.allocator.fork()
            for (parent in parentsOf(ledger, competing)) fork.settle(parent, ledger, LocalHeddle.waitingOver(ledger, competing))
            if (fork.memory != before) stepsThatMove++
        }
        assertTrue(stepsThatMove > SEEDS, "lines whose steps move the memory they run on: $stepsThatMove")
    }

    /**
     * At every [WaitExplanation.BehindAtLevel] explain reports, the leaf's side reads
     * `weight × (frontWide − leafSideVirtualServiceWide)`.
     */
    @Test
    fun aLagAgreesWithBehindAtLevel() {
        var behind = 0
        var apart = 0
        forEachState { h, ledger, _, rng ->
            val waiting = ViewHistories.names.filter { rng.nextInt(3) != 0 }.toSet()
            val eligible = waiting.filter { rng.nextInt(4) != 0 }.toSet()
            val asked = h.shareLine(waiting)
            for (leaf in ViewHistories.leaves) {
                val why = h.explain(leaf, waiting, eligible) as? WaitExplanation.BehindAtLevel ?: continue
                val edge = checkNotNull(ledger.lineageOf(leaf)).map { checkNotNull(ledger.record(it)) }.single { it.parent == why.parent }
                val front = checkNotNull(why.frontWide).toRational()
                val side = checkNotNull(why.leafSideVirtualServiceWide).toRational()
                val expected = weightOf(edge.weight) * (front - side)
                assertEquals(expected, asked.lag(edge.child), "${leaf.value}'s side, ${edge.child.value}, beneath ${why.parent.value}")
                behind++
                if (expected != Rational.ZERO) apart++
            }
        }
        assertAll(
            { assertTrue(behind > SEEDS, "BehindAtLevel answers checked: $behind") },
            { assertTrue(apart > SEEDS, "and with the side off the front: $apart") },
        )
    }

    /**
     * The property the line rests on: placing a parent, on a copy of only its own children's
     * memory, reads what the whole step a pick takes there reads, whether every other parent's
     * step was taken first on the same memory, in any order, or none was. Measured on the whole
     * step before [ShareLine.lag] was built on it: 3,503 parents compared, 0 differed, 1,549 moved
     * by their own step.
     */
    @Test
    fun eachParentsStepIsIndependentOfTheOthers() {
        var compared = 0
        var movedByItsStep = 0
        forEachState { h, ledger, _, rng ->
            val waiting = ViewHistories.names.filter { rng.nextInt(3) != 0 }.toSet()
            val competing = ledger.reachable(waiting, h.root, Leasable.ByTopology(h.persist().lastApplied))
            val view = LocalHeddle.waitingOver(ledger, competing)
            val parents = parentsOf(ledger, competing)
            for (parent in parents) {
                val placed = positionsOf(h.allocator.place(parent, ledger, view))
                val afterOthers = h.allocator.fork().also { f -> parents.filter { it != parent }.shuffled(rng).forEach { f.settle(it, ledger, view) } }
                val settled = afterOthers.settle(parent, ledger, view)
                assertEquals(positionsOf(settled, parent, afterOthers), placed, parent.value)
                compared++
                if (placed != positionsOf(ledger, parent, h.allocator)) movedByItsStep++
            }
        }
        assertAll(
            { assertTrue(compared > SEEDS, "parents compared: $compared") },
            { assertTrue(movedByItsStep > SEEDS, "parents their own step moved: $movedByItsStep") },
        )
    }

    // ── The three ways back into competing ─────────────────────────────────────────────────────

    /**
     * A child the engine saw only idle wakes clamped to the front. On grid, that is the front
     * exactly, and it reads `0`; so does the sibling that ran, which stood at that front alone.
     */
    @Test
    fun aClampedWakerOnGridReadsZero() {
        val tree = draft("root") {
            lane("a")
            lane("b")
        }
        val h = LocalHeddle.bootWhole(tree, policy, epoch = 0L).also { it.mintSupply(100L) }
        val a = tree.groupId("a")
        val b = tree.groupId("b")
        repeat(5) { h.lease(setOf(b)) }
        val aEdge = edgeTo(h, a)
        val line = h.shareLine(setOf(a, b))
        assertAll(
            { assertEquals(false, h.allocator.lastObservedDemanding(aEdge), "the rig: a was seen idle") },
            { assertTrue(aEdge !in h.allocator.memory.departures, "the rig: and never seen leaving, so it is clamped") },
            { assertEquals(VirtualTime.of(Int128.ZERO, Weight.ONE), h.allocator.effectiveVirtualTime(h.snapshot().ledger, h.root, aEdge), "the rig: a stands five units behind b") },
            { assertEquals(Rational.ZERO, line.lag(a)) },
            { assertEquals(Rational.ZERO, line.lag(b)) },
        )
    }

    /**
     * Off grid the clamp rounds up, the penalty side: `a` (weight 1/3) wakes beside `b` at 5 and
     * lands at `⌈5 · 16 / 3⌉ = 27` ticks, past the 26⅔ the front is on its grid. The front over
     * both is then `107 / (16 · 4/3)`, and `a` reads `−1/64`, under one tick (`1/16`) of owing.
     */
    @Test
    fun aClampedWakerOffGridReadsUnderOneTickBelowZero() {
        val tree = draft("root") {
            lane("a", Weight.of(1, 3))
            lane("b")
        }
        val h = LocalHeddle.bootWhole(tree, policy, epoch = 0L).also { it.mintSupply(100L) }
        val a = tree.groupId("a")
        val b = tree.groupId("b")
        repeat(5) { h.lease(setOf(b)) }
        val aEdge = edgeTo(h, a)
        val line = h.shareLine(setOf(a, b))
        val lag = checkNotNull(line.lag(a))
        assertAll(
            { assertEquals(false, h.allocator.lastObservedDemanding(aEdge), "the rig: a was seen idle") },
            { assertTrue(aEdge !in h.allocator.memory.departures, "the rig: and never seen leaving, so it is clamped") },
            { assertNotEquals(0L, 5L * TICKS_PER_UNIT % 3L, "the rig: the front is off a's grid") },
            { assertEquals(Rational.of(-1L, 64L), lag) },
            { assertTrue(lag > Rational.of(-1L, TICKS_PER_UNIT), "within one tick") },
            { assertEquals(Rational.of(1L, 64L), line.lag(b)) },
        )
    }

    /** A child the engine saw stop asking comes back moved by its parent's clock, and keeps the lag it left with. */
    @Test
    fun aReturnerKeepsTheLagItLeftWith() {
        val tree = draft("root") {
            lane("a")
            lane("b")
        }
        val h = LocalHeddle.bootWhole(tree, policy, epoch = 0L).also { it.mintSupply(100L) }
        val a = tree.groupId("a")
        val b = tree.groupId("b")
        val both = setOf(a, b)
        repeat(2) { h.lease(both) }
        repeat(3) { check(h.acquire(both, eligible = setOf(a), startedAt = 0L) is AcquireOutcome.Acquired) }
        val leftWith = h.shareLine(both).lag(a)
        repeat(4) { h.lease(setOf(b)) }
        val aEdge = edgeTo(h, a)
        val back = h.shareLine(both).lag(a)
        assertAll(
            { assertTrue(aEdge in h.allocator.memory.departures, "the rig: a was seen leaving") },
            { assertEquals(false, h.allocator.lastObservedDemanding(aEdge)) },
            { assertEquals(Rational.of(-3L, 2L), leftWith, "a left a unit and a half ahead") },
            { assertEquals(leftWith, back, "and comes back as far ahead, not at the front") },
        )
    }

    // ── Past the Long window, and past the limit ───────────────────────────────────────────────

    /**
     * A parent whose children have climbed past `Long` reads the lags it read before the climb:
     * `a` and `b` are moved by the same virtual time, `2⁷⁰` ticks on `a`'s grid and twice that on
     * `b`'s, so nothing between them changes.
     */
    @Test
    fun aParentPastTheLongWindowReadsTheSameLags() {
        val (snapshot, ids) = climbable()
        val climbed = snapshot.withOffsets(mapOf(ids.aEdge to TWO_TO_THE_70, ids.bEdge to TWO_TO_THE_70 + TWO_TO_THE_70))
        val plain = snapshot.view(policy).shareLine(ids.waiting)
        val line = climbed.view(policy).shareLine(ids.waiting)
        val peak = FairShareAllocator(LocalHeddle.SELF, LocalHeddle.policyConfig(policy), climbed.allocatorMemory)
            .peakPositionFraction(snapshot.ledger, ids.p) { Demand.NONE }
        assertAll(
            { assertTrue(peak > 1.0, "the rig: p's children are past their Long ceilings: $peak") },
            { assertNotEquals(Rational.ZERO, plain.lag(ids.a), "the rig: a stands off the front") },
            *ids.all.map { g -> { assertEquals(plain.lag(g), line.lag(g), g.value) } }.toTypedArray(),
        )
    }

    /**
     * A parent whose step would pass the position limit gives each of its children `null`, and
     * only them: the rows above and beside it read, and so do the shares beneath it.
     */
    @Test
    fun aParentPastThePositionLimitReadsNullForItsChildrenAlone() {
        val (snapshot, ids) = climbable()
        val line = snapshot.withOffsets(mapOf(ids.aEdge to TWO_TO_THE_124, ids.bEdge to TWO_TO_THE_124 + TWO_TO_THE_124)).view(policy).shareLine(ids.waiting)
        assertAll(
            { assertNull(line.lag(ids.a)) },
            { assertNull(line.lag(ids.b)) },
            { assertEquals(Rational.ZERO, checkNotNull(line.lag(ids.p)) + checkNotNull(line.lag(ids.x)), "the root's row still reads") },
            { assertEquals(Rational.of(1L, 6L), line.share(ids.a)) },
        )
    }

    /**
     * A parent whose children's weights fail the weight budget, `{1/30,721, 1/30,722}` (a merged
     * set no single apply admits), gives its children `null` and only them; their shares still read.
     */
    @Test
    fun aParentOverTheWeightBudgetReadsNullForItsChildrenAlone() {
        val root = GroupId("root")
        val p = GroupId("p")
        val x = GroupId("x")
        val a = GroupId("a")
        val b = GroupId("b")
        val ledger = shapedLedger(
            root,
            listOf(
                edgeOf(root, p), edgeOf(root, x),
                AttachmentRecord(AttachmentId("p>a"), p, a, Weight.of(1L, 30_721L)),
                AttachmentRecord(AttachmentId("p>b"), p, b, Weight.of(1L, 30_722L)),
            ),
        )
        val line = engineOver(ledger, root, supply = 0).shareLine(setOf(a, b, x))
        assertAll(
            { assertTrue(!weightsFitTheBudget(listOf(Weight.of(1L, 30_721L), Weight.of(1L, 30_722L))), "the rig: p's weights fail the budget") },
            { assertNull(line.lag(a)) },
            { assertNull(line.lag(b)) },
            { assertEquals(Rational.ZERO, checkNotNull(line.lag(p)), "p and x, alone and unserved, stand at the front") },
            { assertEquals(Rational.ZERO, checkNotNull(line.lag(x))) },
            { assertEquals(Rational.of(30_722L, 2L * 61_443L), line.share(a)) },
        )
    }

    /** A lag that does not fit `Long` throws for its own row, and the rows beside the parent still read. */
    @Test
    fun aLagThatDoesNotFitThrowsForItsRowAlone() {
        val (snapshot, ids) = climbable()
        val line = snapshot.withOffsets(mapOf(ids.aEdge to TWO_TO_THE_70)).view(policy).shareLine(ids.waiting)
        assertAll(
            { assertFailsWith<ArithmeticException> { line.lag(ids.a) } },
            { assertFailsWith<ArithmeticException> { line.lag(ids.b) } },
            { assertEquals(Rational.ZERO, checkNotNull(line.lag(ids.p)) + checkNotNull(line.lag(ids.x))) },
        )
    }

    /**
     * Pins today's behaviour where "competing" and "demanding" part (#3707). Lane `a` holds `2⁶¹`
     * units outstanding, so it still competes, since its lane waits, but the step and `explain`
     * count it as idle. The line's front counts `a`, far ahead, and so reads `b` as owed. `explain`'s
     * front leaves it out and reads `b` half a unit ahead. The public API reaches the same state
     * with one `MAX_RESERVATION` lease and 131,072 `extend`s; here one delegation down `a`'s edge,
     * made outside the engine, stands in for them.
     */
    @Test
    fun pastTheUnboundedTargetALagAndExplainDisagree() {
        val tree = draft("root") {
            lane("a")
            lane("b")
            lane("c")
        }
        val a = tree.groupId("a")
        val b = tree.groupId("b")
        val c = tree.groupId("c")
        val all = setOf(a, b, c)
        val h = LocalHeddle.bootWhole(tree, policy, epoch = 0L).also { it.mintSupply(Long.MAX_VALUE / 2) }
        repeat(3) { h.lease(all) }
        val aEdge = edgeTo(h, a)
        val huge = 1L shl 61
        val booked = h.snapshot().ledger
        val ledger = booked.piece(checkNotNull(booked.delegate(LocalHeddle.SELF, aEdge, huge)) { "the delegation down a" }.delta)
        val engine = LocalHeddle.scratch(h.root, policy, 0L, ledger, h.allocator.fork(), Leasable.ByTopology(tree), Capacity.NONE, emptyMap())
        repeat(2) { check(engine.acquire(all, eligible = setOf(b), startedAt = 0L) is AcquireOutcome.Acquired) }
        check(engine.acquire(all, eligible = setOf(c), startedAt = 0L) is AcquireOutcome.Acquired)
        val outstanding = engine.snapshot().ledger.activeChildren(h.root).single { it.attachment == aEdge }.outstanding
        val line = engine.shareLine(all)
        val why = engine.explain(b, all, all)
        val lags = listOf(a, b, c).map { checkNotNull(line.lag(it)) }
        assertAll(
            { assertTrue(outstanding >= huge, "the rig: a's outstanding is past UNBOUNDED_TARGET, $outstanding") },
            { assertTrue(line.share(a) > Rational.ZERO, "the rig: a still competes") },
            { assertTrue(why is WaitExplanation.BehindAtLevel && why.parent == h.root, "the rig: explain answers at the root, $why") },
            {
                val behind = why as WaitExplanation.BehindAtLevel
                assertEquals(Rational.of(-1L, 2L), checkNotNull(behind.frontWide).toRational() - checkNotNull(behind.leafSideVirtualServiceWide).toRational(), "explain: b half a unit ahead")
            },
            { assertTrue(lags[1] > Rational.of(1L shl 40), "the line: b owed a share of a's lead, ${lags[1]}") },
            { assertEquals(Rational.ZERO, lags.fold(Rational.ZERO, Rational::plus), "the lags still add up to zero") },
        )
    }

    /** [ShareLine.lagOf] against plain [Rational] arithmetic, over weights with denominators and positions of both signs. */
    @Test
    fun lagOfIsTheExactDifference() {
        val rng = Random(3551)
        repeat(2_000) {
            val weights = List(1 + rng.nextInt(4)) { Weight.of(1L + rng.nextInt(40), 1L + rng.nextInt(12)) }
            val positions = weights.map { Int128.of(rng.nextLong(-(1L shl 30), 1L shl 30)) }
            val sum = ShareLine.WeightSum.of(weights) as ShareLine.WeightSum.Fits
            val total = positions.fold(Int128.ZERO) { acc, p -> acc + p }
            val g = Rational.of(TICKS_PER_UNIT)
            val front = positions.fold(Rational.ZERO) { acc, p -> acc + Rational.of(checkNotNull(p.toLongOrNull())) } /
                (g * weights.fold(Rational.ZERO) { acc, w -> acc + weightOf(w) })
            for (i in weights.indices) {
                val w = weightOf(weights[i])
                val expected = w * (front - Rational.of(checkNotNull(positions[i].toLongOrNull())) / (g * w))
                assertEquals(expected, ShareLine.lagOf(weights[i], positions[i], total, sum), "$weights at $positions, child $i")
            }
        }
    }

    // ── Fixtures ────────────────────────────────────────────────────────────────────────────────

    /** Each of [SEEDS] histories, asked [questions] times, with a line over a drawn waiting set. */
    private fun forEachState(questions: Int = 3, check: (LocalHeddle, EntitlementLedger, ShareLine, Random) -> Unit) {
        for (seed in 0 until SEEDS) {
            val rng = Random(seed + 7_000)
            repeat(questions) {
                val h = ViewHistories.history(seed, policy)
                repeat(rng.nextInt(3)) { h.acquire(ViewHistories.leaves.toSet(), ViewHistories.leaves.toSet(), 0L, 1L) }
                val waiting = ViewHistories.names.filter { rng.nextInt(3) != 0 }.toSet()
                check(h, h.snapshot().ledger, h.shareLine(waiting), rng)
            }
        }
    }

    private fun weightOf(w: Weight): Rational = Rational.of(w.numerator, w.denominator)

    /** Every group of [ledger] with an active child in [competing]: the parents a line places, sorted. */
    private fun parentsOf(ledger: EntitlementLedger, competing: Set<GroupId>): List<GroupId> =
        groupsOf(ledger).filter { g -> ledger.activeChildren(g).any { ledger.record(it.attachment)?.child in competing } }

    private fun edgeTo(h: LocalHeddle, child: GroupId): AttachmentId = checkNotNull(h.snapshot().ledger.lineageOf(child)).last()

    private fun positionsOf(placement: FairShareAllocator.Placement): Map<AttachmentId, Int128>? =
        (placement as? FairShareAllocator.Placement.Placed)?.positions

    private fun positionsOf(ledger: EntitlementLedger, parent: GroupId, allocator: FairShareAllocator): Map<AttachmentId, Int128> =
        ledger.activeChildren(parent).associate { summary ->
            val record = checkNotNull(ledger.record(summary.attachment))
            val at = checkNotNull(allocator.effectiveVirtualTime(ledger, parent, summary.attachment))
            summary.attachment to at.floorTicksFor(record.weight)
        }

    private class Climbable(val p: GroupId, val x: GroupId, val a: GroupId, val b: GroupId, val aEdge: AttachmentId, val bEdge: AttachmentId) {
        val all = listOf(p, x, a, b)
        val waiting = setOf(a, b, x)
    }

    /** `root → {p → {a, b (2)}, x}`, after a few leases with everything waiting, so every edge is seated and seen asking. */
    private fun climbable(): Pair<LocalHeddleSnapshot, Climbable> {
        val tree = draft("root") {
            group("p") {
                lane("a")
                lane("b", Weight.of(2))
            }
            lane("x")
        }
        val h = LocalHeddle.bootWhole(tree, policy, epoch = 0L).also { it.mintSupply(100L) }
        val a = tree.groupId("p", "a")
        val b = tree.groupId("p", "b")
        val x = tree.groupId("x")
        val all = setOf(a, b, x)
        repeat(3) { h.lease(all) }
        check(h.acquire(all, eligible = setOf(a), startedAt = 0L) is AcquireOutcome.Acquired)
        return h.persist() to Climbable(tree.groupId("p"), x, a, b, edgeTo(h, a), edgeTo(h, b))
    }

    /** This snapshot with [offsets] added to the wake offsets its memory holds. */
    private fun LocalHeddleSnapshot.withOffsets(offsets: Map<AttachmentId, Int128>): LocalHeddleSnapshot {
        val m = allocatorMemory
        val raised = HashMap(m.wakeOffsets)
        for ((edge, offset) in offsets) raised[edge] = (raised[edge] ?: Int128.ZERO) + offset
        val memory = AllocatorMemory(raised, m.demandingObserved, m.clocks, m.served, m.departures, m.shiftRemainders)
        return LocalHeddleSnapshot(epoch, ledger, memory, totalSlots, open, lastApplied)
    }

    private companion object {
        const val SEEDS = 40
        val TWO_TO_THE_70: Int128 = Int128.fromTwosComplement(1L shl 6, 0L)
        val TWO_TO_THE_124: Int128 = Int128.fromTwosComplement(1L shl 60, 0L)
    }
}
