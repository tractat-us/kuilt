package us.tractat.kuilt.heddle

import kotlinx.serialization.json.Json
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A lease that is open when its edge is clamped at a wake hands back no idle credit when it later
 * releases part of its reservation (#3238). Each test builds one clamp by hand and checks the edge's
 * effective virtual service against where the clamp put it, which is where it would stand had the
 * lease reserved its actual cost from the start.
 *
 * The rig fires in every test: the clamp is asserted to have bound (or, for the slack test, to
 * have left a lead), and the lease to be open at it.
 */
class LocalHeddleWakeExposureTest {

    private val root = GroupId("root")
    private val a = GroupId("a")
    private val b = GroupId("b")
    private val g = GroupId("g")
    private val a1 = GroupId("a1")
    private val a2 = GroupId("a2")
    private val aEdge = AttachmentId("root>a")
    private val gEdge = AttachmentId("root>g")
    private val a1Edge = AttachmentId("g>a1")

    private fun oneLevel(): LocalHeddle = engineOver(shapedLedger(root, listOf(edgeOf(root, a), edgeOf(root, b))), root, supply = 1_000_000)

    private fun twoLevel(): LocalHeddle = engineOver(
        shapedLedger(root, listOf(edgeOf(root, g), edgeOf(root, b), edgeOf(g, a1), edgeOf(g, a2))),
        root,
        supply = 1_000_000,
    )

    /** [edge]'s effective virtual service: raw, from the ledger, plus the allocator's wake offset. */
    private fun ev(h: LocalHeddle, edge: AttachmentId): Rational {
        val s = h.snapshot()
        val l = s.ledger
        val weight = checkNotNull(l.record(edge)).weight
        val raw = checkNotNull(l.grossVirtualService(edge)) - Rational.of(checkNotNull(l.edge(edge)).returned * weight.denominator, weight.numerator)
        return raw + (s.allocatorMemory.wakeOffsets[edge] ?: Rational.ZERO)
    }

    private fun offset(h: LocalHeddle, edge: AttachmentId): Rational = h.snapshot().allocatorMemory.wakeOffsets[edge] ?: Rational.ZERO

    /** Lease [n] slots to the only eligible leaf [leaf], with [waiting] waiting, each completed at its reservation of 1. */
    private fun serve(h: LocalHeddle, waiting: Set<GroupId>, leaf: GroupId, n: Int) {
        repeat(n) {
            val lease = (h.acquire(waiting, setOf(leaf), startedAt = 0L, reservation = 1L) as AcquireOutcome.Acquired).lease
            assertEquals(leaf, lease.leaf)
            h.complete(lease.id, 1L)
        }
    }

    /** Open a lease of [reservation] for [leaf], the only eligible leaf. */
    private fun open(h: LocalHeddle, waiting: Set<GroupId>, leaf: GroupId, reservation: Long): Lease =
        (h.acquire(waiting, setOf(leaf), startedAt = 0L, reservation = reservation) as AcquireOutcome.Acquired).lease
            .also { assertEquals(leaf, it.leaf) }

    /**
     * a opens an 8-unit lease, goes idle while b is served, and wakes with the lease still open: the
     * clamp lifts a to b's front reading the lease at 8. Returns the lease.
     */
    private fun straddle(h: LocalHeddle, reservation: Long = 8L, idleSlots: Int = 20): Lease {
        val lease = open(h, setOf(a, b), a, reservation)
        serve(h, setOf(b), b, idleSlots)
        val before = offset(h, aEdge)
        serve(h, setOf(a, b), b, 1)
        assertTrue(offset(h, aEdge) > before, "the rig: a's wake clamp bound, ${offset(h, aEdge)} over $before")
        return lease
    }

    @Test
    fun aCompleteAfterAWakeHandsBackNothing() {
        val h = oneLevel()
        val lease = straddle(h)
        val clamped = ev(h, aEdge)
        h.complete(lease.id, 2L)
        assertEquals(clamped, ev(h, aEdge), "releasing 6 of 8 left a where the clamp put it")
    }

    @Test
    fun aCancelAfterAWakeHandsBackNothing() {
        val h = oneLevel()
        val lease = straddle(h)
        val clamped = ev(h, aEdge)
        h.cancel(lease.id)
        assertEquals(clamped, ev(h, aEdge), "cancelling all 8 left a where the clamp put it")
    }

    /**
     * a is 30 ahead when b has been served only 10: the clamp does not bind, and a sits 20 ahead of
     * its floor. A release that uses up only that lead is not added back; one that goes past it is,
     * down to the floor. Had the lease reserved its actual cost, the clamp would have put a exactly
     * there.
     */
    @Test
    fun aReleaseFirstUsesUpTheLeadTheClampFound() {
        fun ahead(): Pair<LocalHeddle, Lease> {
            val h = oneLevel()
            val lease = open(h, setOf(a, b), a, 30L)
            serve(h, setOf(b), b, 10)
            val before = offset(h, aEdge)
            serve(h, setOf(a, b), b, 1)
            assertEquals(before, offset(h, aEdge), "the rig: the clamp did not bind, a was ahead")
            return h to lease
        }
        val (within, small) = ahead()
        val beforeSmall = ev(within, aEdge)
        within.complete(small.id, 15L)
        val (past, large) = ahead()
        val floor = Rational.of(10L)
        past.complete(large.id, 2L)
        assertAll(
            { assertEquals(beforeSmall - Rational.of(15L), ev(within, aEdge), "a release of 15, inside a 20-unit lead, is not added back") },
            { assertEquals(floor, ev(past, aEdge), "a release of 28 past a 20-unit lead is added back down to b's front, 10") },
        )
    }

    @Test
    fun aLeaseOpenedAfterTheClampIsNotCounted() {
        val h = oneLevel()
        straddle(h)
        val later = open(h, setOf(a, b), a, 8L)
        val exposed = h.snapshot().allocatorMemory.wakeExposures.containsKey(aEdge)
        val before = ev(h, aEdge)
        h.complete(later.id, 2L)
        assertAll(
            { assertTrue(exposed, "the rig: the clamp's exposure was still held when the later lease completed") },
            { assertEquals(before - Rational.of(6L), ev(h, aEdge), "a later lease's release of 6 corrects its own charge") },
        )
    }

    /**
     * An extension after the clamp is released again at `complete`; the clamp never read it, so only
     * the 8 the clamp saw is added back.
     */
    @Test
    fun aReleaseCountsNoMoreThanTheClampSaw() {
        val h = oneLevel()
        val lease = straddle(h)
        val clamped = ev(h, aEdge)
        assertEquals(LeaseOutcome.Done(0L, 0L), h.extend(lease.id, 20L))
        h.complete(lease.id, 0L)
        assertEquals(clamped, ev(h, aEdge), "28 released, 8 of it read by the clamp: a ends where the clamp put it")
    }

    /**
     * A second clamp that does not bind must not write off the first one's risk. a wakes with an
     * 8-unit lease open (the clamp binds), then runs a 30-unit job and goes idle again, so at the
     * second wake it is well ahead of the front. The first lease is still open, and its release is
     * still the first clamp's to cover.
     */
    @Test
    fun aSecondClampThatDoesNotBindKeepsTheFirstClampsRisk() {
        val h = oneLevel()
        val lease = straddle(h)
        val big = open(h, setOf(a, b), a, 30L)
        h.complete(big.id, 30L)
        serve(h, setOf(b), b, 10)
        val before = offset(h, aEdge)
        serve(h, setOf(a, b), b, 1)
        val evBefore = ev(h, aEdge)
        h.complete(lease.id, 0L)
        assertAll(
            { assertEquals(before, offset(h, aEdge) - Rational.of(8L), "the rig: the second clamp did not bind, and the release was added back") },
            { assertEquals(evBefore, ev(h, aEdge), "releasing all 8 handed back nothing") },
        )
    }

    /** a1 is idle while a2 runs under g: only g's edge to a1 is clamped, and only it is compensated. */
    @Test
    fun aReleaseHandsBackNothingBelowAGroup() {
        val h = twoLevel()
        val lease = open(h, setOf(a1, a2, b), a1, 8L)
        serve(h, setOf(a2, b), a2, 20)
        val before = offset(h, a1Edge)
        serve(h, setOf(a1, a2, b), a2, 1)
        val clamped = ev(h, a1Edge)
        val group = ev(h, gEdge)
        h.complete(lease.id, 2L)
        assertAll(
            { assertTrue(offset(h, a1Edge) > before, "the rig: a1's clamp at g bound") },
            { assertEquals(clamped, ev(h, a1Edge), "a1 is left where g's clamp put it") },
            { assertEquals(group - Rational.of(6L), ev(h, gEdge), "g, never clamped, takes the release as a correction") },
        )
    }

    /** g goes idle as a whole and wakes at the root with a1's lease open: the root's edge to g is compensated. */
    @Test
    fun aReleaseHandsBackNothingAtAGroupsEdge() {
        val h = twoLevel()
        val lease = open(h, setOf(a1, a2, b), a1, 8L)
        serve(h, setOf(b), b, 20)
        val before = offset(h, gEdge)
        serve(h, setOf(a1, a2, b), b, 1)
        val clamped = ev(h, gEdge)
        h.complete(lease.id, 2L)
        assertAll(
            { assertTrue(offset(h, gEdge) > before, "the rig: g's clamp at the root bound") },
            { assertEquals(clamped, ev(h, gEdge), "g is left where the root's clamp put it") },
        )
    }

    /** Random lease traffic: no operation ever lowers a stored wake offset (#1714), compensation included. */
    @Test
    fun offsetsNeverDecrease() {
        var compensated = 0
        var steps = 0
        for (seed in 0 until 40) {
            val rng = Random(seed)
            val h = twoLevel()
            val leaves = listOf(a1, a2, b)
            repeat(300) {
                val before = h.snapshot().allocatorMemory.wakeOffsets
                val open = h.snapshot().leases
                val draw = rng.nextInt(10)
                var settled = false
                when {
                    draw < 5 || open.isEmpty() -> {
                        val waiting = leaves.filter { rng.nextInt(3) != 0 }.toSet()
                        h.acquire(waiting, waiting.filter { rng.nextBoolean() }.toSet(), startedAt = 0L, reservation = 1L + rng.nextInt(8))
                    }
                    draw < 8 -> { h.complete(open[rng.nextInt(open.size)].id, rng.nextInt(10).toLong()); settled = true }
                    draw < 9 -> { h.cancel(open[rng.nextInt(open.size)].id); settled = true }
                    else -> h.extend(open[rng.nextInt(open.size)].id, 1L + rng.nextInt(4))
                }
                val after = h.snapshot().allocatorMemory.wakeOffsets
                for ((edge, value) in before) {
                    val now = after[edge]
                    if (now != null && now < value) throw AssertionError("seed $seed: the offset of ${edge.value} fell from $value to $now")
                }
                if (settled && after.any { (edge, value) -> value > (before[edge] ?: Rational.ZERO) }) compensated++
                steps++
            }
        }
        assertAll(
            { assertEquals(40 * 300, steps, "every step ran") },
            { assertTrue(compensated > 0, "the rig: some release was added back: $compensated") },
        )
    }

    @Test
    fun wakeExposuresSurviveTheMemoryRoundTrip() {
        val h = oneLevel()
        straddle(h)
        val memory = h.snapshot().allocatorMemory
        val bytes = Json.encodeToString(AllocatorMemory.serializer(), memory)
        val reordered = AllocatorMemory(memory.wakeOffsets, memory.demandingObserved, memory.wakeExposures.entries.reversed().associate { it.key to it.value })
        assertAll(
            { assertTrue(memory.wakeExposures.containsKey(aEdge), "the rig: the clamp left an exposure") },
            { assertEquals(memory, Json.decodeFromString(AllocatorMemory.serializer(), bytes), "decodes to the same memory") },
            { assertEquals(bytes, Json.encodeToString(AllocatorMemory.serializer(), reordered), "equal memories, equal bytes") },
            { assertEquals(memory, FairShareAllocator(LOCAL, PolicyConfig(quantum = Long.MAX_VALUE), memory).memory, "an allocator resumes it") },
            {
                assertEquals(
                    """{"wakeOffsets":{},"demandingObserved":{}}""",
                    Json.encodeToString(AllocatorMemory.serializer(), AllocatorMemory.EMPTY),
                    "a memory with no exposure encodes as it did before",
                )
            },
        )
    }

    @Test
    fun aWakeExposureRefusesASlackThatCoversItsBudget() {
        assertAll(
            { assertFailsWith<IllegalArgumentException> { WakeExposure(slack = 8L, budget = 8L, openedBeforeEpoch = 0L, openedBeforeSeq = 1L) } },
            { assertFailsWith<IllegalArgumentException> { WakeExposure(slack = -1L, budget = 8L, openedBeforeEpoch = 0L, openedBeforeSeq = 1L) } },
        )
    }
}
