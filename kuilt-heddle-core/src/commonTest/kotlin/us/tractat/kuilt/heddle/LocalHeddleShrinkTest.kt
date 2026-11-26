package us.tractat.kuilt.heddle

import kotlinx.serialization.json.Json
import us.tractat.kuilt.math.Int128
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `shrink(lease, amount)` straight after a pick leaves the engine as if the lease had been opened
 * at the smaller size (#3648). The oracle builds that engine another way: where one engine runs
 * `acquire(r)` then `shrink(r − h)`, its twin runs `admit(leaf, h)` at the leaf the acquire picked,
 * which observes the waiting set as that pick did (#3320) and delegates only `h`.
 *
 * A small, fixed set of seeds, each walking a `List` the test built, so each seed walks the same
 * trajectory on every target and the whole class runs in well under a second.
 */
class LocalHeddleShrinkTest {

    /** `root → p:2 → {p0:3, p1:1}`, `root → q:1 → {q0:1, q1:2}`: every lease crosses two edges. */
    private val tree = draft("root") {
        group("p", Weight.of(2)) {
            lane("p0", Weight.of(3))
            lane("p1")
        }
        group("q") {
            lane("q0")
            lane("q1", Weight.of(2))
        }
    }

    /** [tree] with `q2` added under `q`, applied part-way through a run so a later observation seats a newborn. */
    private val grown = draft("root") {
        group("p", Weight.of(2)) {
            lane("p0", Weight.of(3))
            lane("p1")
        }
        group("q") {
            lane("q0")
            lane("q1", Weight.of(2))
            lane("q2")
        }
    }

    private val leaves = listOf(
        grown.groupId("p", "p0"), grown.groupId("p", "p1"),
        grown.groupId("q", "q0"), grown.groupId("q", "q1"), grown.groupId("q", "q2"),
    )
    private val policy = LocalPolicy(defaultReservation = 4L)

    /**
     * Supply laid down ahead and no capacity reconciled, so neither twin mints: a top-up is sized by
     * the reservation a call asks for, so an acquire at `r` would mint where an admit at `h` does not.
     */
    private fun engine(): LocalHeddle = LocalHeddle.bootWhole(tree, policy, epoch = 0L).also { it.mintSupply(SUPPLY) }

    private fun subset(rng: Random): List<GroupId> = leaves.filter { rng.nextInt(3) != 0 }

    /** How one engine shrinks a lease it has just acquired: [LocalHeddle.shrink], or the test seam's leaf-only refund. */
    private fun interface Shrink {
        fun of(h: LocalHeddle, lease: LeaseId, amount: Long): LeaseOutcome
    }

    /** What the oracle compares, each read so that two engines reached by different calls can agree. */
    private enum class Aspect { POSITIONS, HOLDINGS, WAKE_MEMORY, LEASES, NEXT_PICKS }

    private class Divergence(val seed: Int, val step: Int, val aspects: Set<Aspect>) {
        override fun toString(): String = "seed $seed step $step: $aspects"
    }

    /** One seed's run, and how often its rigs fired. */
    private class Run(val divergence: Divergence?, val shrinks: Int, val clamped: Int, val seated: Int, val snapshots: Int)

    /** Every edge's raw position: the virtual service a pick reads, net of returns. */
    private fun positions(h: LocalHeddle): Map<AttachmentId, VirtualTime?> {
        val ledger = h.snapshot().ledger
        return ledger.allEdges().sorted().associateWith { ledger.virtualServiceWide(it) }
    }

    /** What every group holds. */
    private fun holdings(h: LocalHeddle): Map<GroupId, Long> {
        val ledger = h.snapshot().ledger
        return groupsOf(ledger).associateWith { ledger.holdings(it, LOCAL) }
    }

    /** The next [NEXT_PICKS] picks with every leaf queued, at mixed reservations: read through the view, so its sums are checked too. */
    private fun nextPicks(h: LocalHeddle): List<GroupId> =
        h.previewPicks(leaves.associateWith { NEXT_PICKS }, leaves.toSet(), List(NEXT_PICKS) { 1L + it % 3 })

    private fun differences(a: LocalHeddle, b: LocalHeddle): Set<Aspect> = buildSet {
        if (positions(a) != positions(b)) add(Aspect.POSITIONS)
        if (holdings(a) != holdings(b)) add(Aspect.HOLDINGS)
        if (a.snapshot().allocatorMemory != b.snapshot().allocatorMemory) add(Aspect.WAKE_MEMORY)
        if (a.snapshot().leases != b.snapshot().leases) add(Aspect.LEASES)
        if (nextPicks(a) != nextPicks(b)) add(Aspect.NEXT_PICKS)
    }

    /** A snapshot taken now decodes, through JSON, under the restore's balance check (#3660). */
    private fun decodes(h: LocalHeddle): Boolean {
        val text = Json.encodeToString(LocalHeddleSnapshot.serializer(), h.persist())
        return LocalHeddle.decode(text, Json) is RestoreResult.Restored
    }

    /**
     * Two engines run the same seeded history of acquires, extends, completes and cancels, except at
     * a shrink step: [shrunk] acquires at `r` and [shrink]s by `r − h`, and its twin admits the
     * leaf that acquire picked at `h`, with the same waiting set. The run stops at the first step
     * after which the two differ in any [Aspect], since they no longer run the same history then.
     */
    private fun twins(seed: Int, shrink: Shrink): Run {
        val rng = Random(seed)
        val shrunk = engine()
        val admitted = engine()
        var shrinks = 0
        var clamped = 0
        var seated = 0
        var snapshots = 0
        for (step in 0 until STEPS) {
            if (step == GROW_AT) {
                shrunk.apply(grown, generation = 1L)
                admitted.apply(grown, generation = 1L)
            }
            val open = shrunk.snapshot().leases
            val kind = rng.nextInt(6)
            when {
                kind == 0 || (kind < 4 && open.isEmpty()) -> {
                    val drawn = subset(rng)
                    val eligible = drawn.filter { rng.nextBoolean() }.toSet()
                    val r = 1L + rng.nextInt(5)
                    shrunk.acquire(drawn.toSet(), eligible, startedAt = step.toLong(), reservation = r)
                    admitted.acquire(drawn.toSet(), eligible, startedAt = step.toLong(), reservation = r)
                }
                kind == 1 -> {
                    val lease = open[rng.nextInt(open.size)].id
                    val amount = 1L + rng.nextInt(4)
                    val waiting = subset(rng).toSet()
                    shrunk.extend(lease, amount, waiting)
                    admitted.extend(lease, amount, waiting)
                }
                kind == 2 -> {
                    val lease = open[rng.nextInt(open.size)].id
                    val cost = rng.nextInt(9).toLong()
                    val waiting = subset(rng).toSet()
                    shrunk.complete(lease, cost, waiting)
                    admitted.complete(lease, cost, waiting)
                }
                kind == 3 -> {
                    val lease = open[rng.nextInt(open.size)].id
                    shrunk.cancel(lease)
                    admitted.cancel(lease)
                }
                else -> {
                    val drawn = subset(rng)
                    val eligible = drawn.filter { rng.nextBoolean() }.toSet()
                    val r = 2L + rng.nextInt(7)
                    val h = 1L + rng.nextInt(r.toInt() - 1)
                    val before = shrunk.snapshot()
                    val picked = shrunk.acquire(drawn.toSet(), eligible, startedAt = step.toLong(), reservation = r)
                    if (picked is AcquireOutcome.Acquired) {
                        val after = shrunk.snapshot()
                        if (before.allocatorMemory.wakeOffsets != after.allocatorMemory.wakeOffsets) clamped++
                        if (after.ledger.allEdges().any { before.ledger.gauge(it) == null && after.ledger.gauge(it) != null }) seated++
                        check(shrink.of(shrunk, picked.lease.id, r - h) is LeaseOutcome.Done) { "the shrink of ${picked.lease.id} landed" }
                        val twin = admitted.admit(picked.lease.leaf, drawn.toSet(), startedAt = step.toLong(), reservation = h)
                        check(twin is AcquireOutcome.Acquired) { "seed $seed step $step: the twin's admit of ${picked.lease.leaf.value} was $twin" }
                        shrinks++
                        if (decodes(shrunk)) snapshots++
                    } else {
                        // Refused: the twin runs the same refused acquire, so it observes what this one did.
                        admitted.acquire(drawn.toSet(), eligible, startedAt = step.toLong(), reservation = r)
                    }
                }
            }
            val aspects = differences(shrunk, admitted)
            if (aspects.isNotEmpty()) return Run(Divergence(seed, step, aspects), shrinks, clamped, seated, snapshots)
        }
        return Run(divergence = null, shrinks, clamped, seated, snapshots)
    }

    @Test
    fun aShrinkStraightAfterAPickLeavesWhatAnAdmitAtTheSmallerSizeLeaves() {
        val runs = SEEDS.map { twins(it, LocalHeddle::shrink) }
        val diverged = runs.mapNotNull { it.divergence }
        assertAll(
            { assertEquals(emptyList(), diverged, "${diverged.size} of ${SEEDS.size} seeds diverged") },
            // The rig: shrinks landed, and some of their picks clamped a waker or seated a newborn.
            { assertTrue(runs.all { it.shrinks >= 5 }, "shrinks per seed: ${runs.map { it.shrinks }}") },
            { assertTrue(runs.sumOf { it.clamped } > 0, "shrinks whose pick clamped a waker: ${runs.map { it.clamped }}") },
            { assertTrue(runs.sumOf { it.seated } > 0, "shrinks whose pick seated a newborn: ${runs.map { it.seated }}") },
        )
    }

    @Test
    fun everySnapshotTakenAfterAShrinkDecodes() {
        val runs = SEEDS.map { twins(it, LocalHeddle::shrink) }
        assertAll(
            { assertTrue(runs.sumOf { it.shrinks } > 0, "the rig: shrinks landed") },
            { assertEquals(runs.map { it.shrinks }, runs.map { it.snapshots }, "snapshots that decoded, against shrinks taken") },
        )
    }

    /**
     * The positive control: a refund across the leaf's own edge only, the parent edge left as it
     * was, must red the oracle, in every seed and at the first shrink. It reds on positions and
     * holdings, the parent edge still carrying the whole reservation and the parent group holding
     * the refund, and never on the wake memory, since neither form of shrink observes anything.
     */
    @Test
    fun aShrinkThatRefundsAtTheLeafOnlyRedsTheOracle() {
        val runs = SEEDS.map { twins(it, LocalHeddle::shrinkAtTheLeafOnlyForTest) }
        val divergences = runs.map { it.divergence }
        assertAll(
            { assertTrue(divergences.all { it != null }, "every seed diverged: $divergences") },
            { assertTrue(runs.all { it.shrinks == 1 }, "each at its first shrink: ${runs.map { it.shrinks }}") },
            {
                assertTrue(
                    divergences.all { it != null && Aspect.POSITIONS in it.aspects && Aspect.HOLDINGS in it.aspects && Aspect.WAKE_MEMORY !in it.aspects },
                    "positions and holdings, never the wake memory: $divergences",
                )
            },
        )
    }

    @Test
    fun aShrinkOutOfRangeThrowsAndChangesNothing() {
        val h = engine()
        val queued = leaves.take(4).toSet()
        val lease = h.lease(queued, reservation = 5L)
        val before = h.persist()
        val revision = h.revision
        assertAll(
            { assertFailsWith<IllegalArgumentException> { h.shrink(lease.id, 0L) } },
            { assertFailsWith<IllegalArgumentException> { h.shrink(lease.id, -1L) } },
            { assertFailsWith<IllegalArgumentException> { h.shrink(lease.id, 6L) } },
            { assertEquals(before, h.persist(), "a throw changes nothing") },
            { assertEquals(revision, h.revision, "nor moves the revision") },
            { assertEquals(LeaseOutcome.Done(0L, 0L, 0L), h.shrink(lease.id, 5L), "the whole reservation may go back") },
            { assertEquals(0L, h.snapshot().leases.single().reserved, "leaving the lease open at zero") },
            { assertFailsWith<IllegalArgumentException> { h.shrink(lease.id, 1L) } },
        )
    }

    @Test
    fun aShrinkOfNoOpenLeaseNamesWhy() {
        val h = engine()
        val queued = leaves.take(4).toSet()
        val lease = h.lease(queued, reservation = 3L)
        h.complete(lease.id, 3L, waiting = emptySet())
        val restored = LocalHeddle.restore(h.persist(), policy, epoch = 1L)
        assertAll(
            { assertEquals(LeaseOutcome.Unknown, h.shrink(lease.id, 1L)) },
            { assertEquals(LeaseOutcome.Stale(currentEpoch = 1L), restored.shrink(lease.id, 1L)) },
        )
    }

    /**
     * A shrink on a path a reload is closing is never refused: the refund goes back up the closing
     * edge, as [LocalHeddle.cancel]'s would, and what is left completes.
     */
    @Test
    fun aShrinkOnADrainingPathLands() {
        val pair = Topology("root", listOf(Topology.Lane("a"), Topology.Lane("b")))
        val a = pair.groupId("a")
        val h = LocalHeddle.bootWhole(pair, policy, epoch = 0L).also { it.reconcileCapacity(totalSlots = 4L) }
        val lease = h.lease(setOf(a), reservation = 6L)
        // The removal drains: a's edge closes, and stays until its lease settles.
        h.apply(Topology("root", listOf(Topology.Lane("b"))), generation = 1L)
        val edge = assertNotNull(h.capturedPath(lease.id)).single()
        assertAll(
            { assertEquals(Lifecycle.CLOSING, h.snapshot().ledger.lifecycle(edge), "the rig: the lease's edge is closing") },
            { assertEquals(LeaseOutcome.Done(0L, 0L, 0L), h.shrink(lease.id, 4L)) },
            { assertTrue(decodes(h), "the snapshot after it decodes") },
            { assertEquals(LeaseOutcome.Done(2L, 0L, 0L), h.complete(lease.id, 2L, waiting = emptySet())) },
        )
    }

    /** `root → {a, b}` with 4 slots, and a lease on `a` reserving [WHOLE], more than the slots: the rig of [aShrinkOnADrainingPathLands]. */
    private fun aLeaseOnA(): Pair<LocalHeddle, Lease> {
        val h = LocalHeddle.bootWhole(pair, policy, epoch = 0L).also { it.reconcileCapacity(totalSlots = 4L) }
        return h to h.lease(setOf(a), reservation = WHOLE)
    }

    private val withoutA = Topology("root", listOf(Topology.Lane("b")))

    /** The removal of `a` waits on [lease], whatever it reserves, and lands once it settles. */
    private fun assertTheRemovalWaitsOn(h: LocalHeddle, lease: Lease, waiting: ApplyResult) {
        val draining = assertIs<StepRefusal.Draining>(waiting.refusals.single(), "a's removal waits: ${waiting.refusals}")
        val edge = assertNotNull(h.capturedPath(lease.id)).single()
        val open = h.snapshot().leases.single()
        val done = h.complete(lease.id, 0L, waiting = emptySet())
        val landed = h.apply(withoutA, generation = 1L)
        assertAll(
            { assertEquals(lease.copy(reserved = 0L), open, "the rig: the lease is open and reserves nothing") },
            { assertEquals(a, draining.child) },
            { assertEquals(edge, draining.edge, "on the lease's own edge") },
            { assertEquals(0L, draining.outstanding, "which carries nothing: the lease itself is the wait") },
            { assertEquals(listOf(open), draining.blockingLeases, "naming the lease") },
            { assertIs<LeaseOutcome.Done>(done) },
            { assertEquals(emptyList(), landed.refusals, "and lands once it settles") },
            { assertEquals(Lifecycle.RETIRED, h.snapshot().ledger.lifecycle(edge)) },
            { assertTrue(decodes(h), "the snapshot after it decodes") },
        )
    }

    /** A whole shrink, then the removal of the lease's lane: the removal waits on the lease, which reserves nothing. */
    @Test
    fun aWholeShrinkThenARemovalWaitsOnTheLease() {
        val (h, lease) = aLeaseOnA()
        assertEquals(LeaseOutcome.Done(0L, 0L, 0L), h.shrink(lease.id, WHOLE))
        val waiting = h.apply(withoutA, generation = 1L)
        assertTrue(decodes(h), "a snapshot taken while it waits decodes")
        assertTheRemovalWaitsOn(h, lease, waiting)
    }

    /**
     * A removal waiting on the lease's 6 units, then a whole shrink, then the same topology again:
     * the shrink takes the edge's outstanding to 0, and the removal still waits on the open lease.
     * [aShrinkOnADrainingPathLands] shrinks 4 of 6, where the edge still carries units.
     */
    @Test
    fun aWholeShrinkOnADrainingPathKeepsTheRemovalWaiting() {
        val (h, lease) = aLeaseOnA()
        val first = assertIs<StepRefusal.Draining>(h.apply(withoutA, generation = 1L).refusals.single())
        assertEquals(LeaseOutcome.Done(0L, 0L, 0L), h.shrink(lease.id, WHOLE))
        val again = h.apply(withoutA, generation = 1L)
        assertEquals(WHOLE, first.outstanding, "the rig: before the shrink the removal waited on the lease's units")
        assertTheRemovalWaitsOn(h, lease, again)
    }

    // ---- The wake clamp reads the lease's current reservation (#3238, #3716) ----

    private val pair = Topology("root", listOf(Topology.Lane("a"), Topology.Lane("b")))
    private val a = pair.groupId("a")
    private val b = pair.groupId("b")
    private val both = setOf(a, b)
    private val unit = LocalPolicy(defaultReservation = 1L)

    /** The rig of `WakeClampOpenLeaseTest`: `a` leases its last job at [reserved] and is seen to stop asking with it open. */
    private fun aWentIdleWithItsLeaseOpen(reserved: Long): Pair<LocalHeddle, Lease> {
        val h = LocalHeddle.bootWhole(pair, unit, epoch = 0L)
        h.reconcileCapacity(totalSlots = 8L)
        repeat(WARM_UP) {
            val lease = h.lease(both, startedAt = it.toLong())
            h.complete(lease.id, 1L, waiting = both)
        }
        val open = assertIs<AcquireOutcome.Acquired>(h.acquire(both, setOf(a), startedAt = WARM_UP.toLong(), reservation = reserved)).lease
        repeat(ALONE) {
            val lease = h.lease(setOf(b), startedAt = WARM_UP + 1L + it)
            h.complete(lease.id, 1L, waiting = setOf(b))
        }
        return h to open
    }

    /** [h] restarted without its wake memory, so the next pick clamps both lanes as wakers. */
    private fun restartedForgetting(h: LocalHeddle): LocalHeddle {
        val s = h.persist()
        return LocalHeddle.restore(LocalHeddleSnapshot(s.epoch, s.ledger, AllocatorMemory.EMPTY, s.totalSlots, s.open, s.lastApplied), unit, epoch = s.epoch + 1)
    }

    /** What the clamp and everything after it reads, on an engine whose `a` has a lease open. */
    private class Woken(val offset: Int128, val picks: List<GroupId>, val aAhead: Rational, val viewAhead: Rational)

    private fun aAheadOfB(line: ShareLine): Rational = assertNotNull(line.lag(b)) - assertNotNull(line.lag(a))

    /** The share line, live and from the snapshot's view; then the first pick, which wakes `a`; then 19 more. */
    private fun woken(h: LocalHeddle, open: Lease): Woken {
        val aAhead = aAheadOfB(h.shareLine(both))
        val viewAhead = aAheadOfB(h.persist().view(unit).shareLine(both))
        val edge = assertNotNull(h.capturedPath(open.id)).last()
        val picks = ArrayList<GroupId>()
        var offset = Int128.ZERO
        repeat(PICKS) {
            val lease = h.lease(both, startedAt = 100L + it)
            picks += lease.leaf
            h.complete(lease.id, 1L, waiting = both)
            if (it == 0) {
                offset = h.persist().allocatorMemory.wakeOffsets[edge] ?: Int128.ZERO
                assertIs<LeaseOutcome.Done>(h.complete(open.id, SHRUNK_TO, waiting = both))
            }
        }
        return Woken(offset, picks, aAhead, viewAhead)
    }

    /**
     * A lease left open across a restart is shrunk on the restored engine, and the clamp that wakes
     * its lane counts what it reserves now. It reads as an engine whose lease was opened at the
     * smaller size: the same wake offset, share line (live and from a snapshot's view) and picks.
     * Unshrunk, the lease counts all 8 past the front, which is the rig: the shrink moved the clamp.
     *
     * [ALONE] is 2, so `a` wakes level with the front it is clamped to: with the clamp at the front
     * alone (before #3716), every offset here reads `0`, and the 32 ticks are the 2 units the lease
     * reserves now and nothing else.
     */
    @Test
    fun aShrinkAcrossARestoreIsWhatTheClampCounts() {
        val (big, bigLease) = aWentIdleWithItsLeaseOpen(reserved = 8L)
        val shrunk = restartedForgetting(big)
        assertEquals(LeaseOutcome.Done(0L, 0L, 0L), shrunk.shrink(bigLease.id, 8L - SHRUNK_TO))
        val (small, smallLease) = aWentIdleWithItsLeaseOpen(reserved = SHRUNK_TO)
        val (unshrunkEngine, unshrunkLease) = aWentIdleWithItsLeaseOpen(reserved = 8L)

        val afterShrink = woken(shrunk, bigLease)
        val openedSmall = woken(restartedForgetting(small), smallLease)
        val unshrunk = woken(restartedForgetting(unshrunkEngine), unshrunkLease)
        assertAll(
            { assertEquals(openedSmall.offset, afterShrink.offset, "the wake offset") },
            { assertEquals(Int128.of(SHRUNK_TO * TICKS_PER_UNIT), afterShrink.offset, "past the front by the 2 the lease reserves now") },
            { assertEquals(openedSmall.aAhead, afterShrink.aAhead, "the live share line") },
            { assertEquals(openedSmall.viewAhead, afterShrink.viewAhead, "the snapshot view's share line") },
            { assertEquals(afterShrink.aAhead, afterShrink.viewAhead, "the view reads what the engine does") },
            { assertEquals(openedSmall.picks, afterShrink.picks, "the 20 picks") },
            { assertEquals(Int128.of(8L * TICKS_PER_UNIT), unshrunk.offset, "the rig: unshrunk, the clamp counts all 8") },
        )
    }

    private companion object {
        val SEEDS = listOf(0, 1, 2, 3, 4, 5)
        const val STEPS = 60
        const val GROW_AT = 25
        const val NEXT_PICKS = 50
        const val WARM_UP = 20
        const val ALONE = 2
        const val PICKS = 20
        const val SHRUNK_TO = 2L
        const val SUPPLY = 100_000L
        const val WHOLE = 6L
    }
}
