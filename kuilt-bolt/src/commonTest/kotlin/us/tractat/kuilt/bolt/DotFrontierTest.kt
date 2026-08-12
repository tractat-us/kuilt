package us.tractat.kuilt.bolt

import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [DotFrontier] on its own — the memory claim, and the two things that would quietly break it.
 *
 * The claim is *O(authors) + O(unfilled holes)*, and every test here is really about that number
 * rather than about membership: membership is easy to get right and useless if it costs an entry
 * per archived operation, which is the bound this structure exists to escape.
 */
class DotFrontierTest {

    private val alice = ReplicaId("alice")
    private val bob = ReplicaId("bob")

    private fun frontier(maxRuns: Int = 64) = DotFrontier(maxRuns)

    /**
     * A peer's whole log costs **one** entry, not one per operation.
     *
     * The dot count here (10,000) is deliberately larger than a phone's `DEFAULT_MAX_LOG_RECORDS`
     * live log, so `runCount == 1` is a statement about the structure rather than about the
     * fixture's size. Assert the run count, not just membership — a set of dots would satisfy every
     * `contains` here and satisfy nothing this class is for.
     */
    @Test
    fun aDenseRunOfSeqsCostsOneEntryHoweverManyDotsItCovers() {
        val frontier = frontier()
        (1L..10_000L).forEach { seq -> frontier.add(Dot(alice, seq)) }

        assertAll(
            { assertEquals(1, frontier.runCount, "ten thousand archived inserts, one entry") },
            { assertEquals(1, frontier.authorCount) },
            { assertTrue(frontier.contains(Dot(alice, 1L)), "the bottom of the run") },
            { assertTrue(frontier.contains(Dot(alice, 10_000L)), "and the top") },
            { assertFalse(frontier.contains(Dot(alice, 10_001L)), "and nothing above it") },
        )
    }

    /**
     * Out-of-order arrival fragments the frontier and then **un**-fragments it as the holes close.
     *
     * A merge hands the decorator a peer's operations in no guaranteed order, so this is the normal
     * path rather than an edge case. The middle assertion is the load-bearing one: without the
     * `joinsBelow && joinsAbove` arm of `DotFrontier.add`, closing a hole leaves two runs touching
     * instead of one, and a frontier that never coalesces grows an entry per gap it ever had.
     */
    @Test
    fun dotsArrivingOutOfOrderMergeIntoOneRunAsTheHoleCloses() {
        val frontier = frontier()
        frontier.add(Dot(alice, 3L))
        frontier.add(Dot(alice, 1L))
        val whileTheHoleIsOpen = frontier.runCount
        frontier.add(Dot(alice, 2L))

        assertAll(
            { assertEquals(2, whileTheHoleIsOpen, "1 and 3 are not adjacent, so they are two runs") },
            { assertEquals(1, frontier.runCount, "and closing the hole between them merges the two") },
            { assertTrue((1L..3L).all { frontier.contains(Dot(alice, it)) }, "all three are held") },
        )
    }

    /**
     * **A hole that never fills costs one entry, not one per dot above it.**
     *
     * This is the case a high-water-plus-gap-set gets wrong, and it is not exotic: `BoltDecorator`
     * rebuilds nothing on open, so a restarted archive meets every peer part-way through that
     * peer's sequence and never sees what came before. Under a high-water pinned below the hole,
     * each of the 5,000 dots above it would be its own entry — the O(archived operations) growth
     * this structure exists to escape.
     */
    @Test
    fun aHoleThatNeverFillsCostsOneEntryRatherThanOnePerDotAboveIt() {
        val frontier = frontier()
        (5_000L..10_000L).forEach { seq -> frontier.add(Dot(alice, seq)) }

        assertAll(
            { assertEquals(1, frontier.runCount, "the hole below 5,000 is the space between runs, not an entry") },
            { assertFalse(frontier.contains(Dot(alice, 4_999L)), "and nothing below it is claimed as archived") },
            { assertTrue(frontier.contains(Dot(alice, 5_000L))) },
        )
    }

    /**
     * Re-adding a held dot answers `false` and costs nothing — the anti-entropy path, run every
     * round for every dot in every peer's log.
     */
    @Test
    fun aDotAlreadyHeldIsNotNewAndAddsNoRun() {
        val frontier = frontier()
        (1L..5L).forEach { seq -> frontier.add(Dot(alice, seq)) }

        val reAdded = frontier.add(Dot(alice, 3L))

        assertAll(
            { assertFalse(reAdded, "already archived") },
            { assertEquals(1, frontier.runCount, "and the frontier is unchanged") },
        )
    }

    /**
     * Authors are namespaced by their [ReplicaId] — `alice`'s seq 7 says nothing about `bob`'s.
     *
     * Trivial to state and the whole reason a dot is a pair: a frontier keyed on `seq` alone would
     * suppress one peer's inserts because another peer had reached that number.
     */
    @Test
    fun authorsAreIndependent() {
        val frontier = frontier()
        (1L..7L).forEach { seq -> frontier.add(Dot(alice, seq)) }
        frontier.add(Dot(bob, 1L))

        assertAll(
            { assertEquals(2, frontier.runCount, "one run each") },
            { assertEquals(2, frontier.authorCount) },
            { assertTrue(frontier.contains(Dot(alice, 7L))) },
            { assertFalse(frontier.contains(Dot(bob, 7L)), "bob has only reached 1") },
        )
    }

    /**
     * [DotFrontier.trim] evicts the **shortest** run, and the dots it covered stop being held.
     *
     * Shortest rather than oldest or least-recently-offered: under gossip every run is re-offered
     * every round, so recency separates nothing, while length is exactly how many re-archived
     * inserts the entry is buying.
     *
     * The eviction is a *bytes* cost — those inserts are archived a second time. It must never be a
     * *merge* across the hole between two runs, which would claim dots that were never archived and
     * suppress those inserts forever. Hence the last assertion: the survivors are the two long runs,
     * and nothing has grown to cover the gap the evicted one left.
     *
     * **Mutation receipt, measured:** sorting ascending by length rather than descending — evict the
     * *longest* — reds the third and fifth assertions: `alice`'s 4-dot run survives and `bob`'s
     * 20-dot run is the one that goes. The run-count assertions stay green under it, which is the
     * point of having both: a count alone cannot tell you *which* run was evicted.
     */
    @Test
    fun trimEvictsTheShortestRunAndTheDotsItCoveredAreNoLongerHeld() {
        val frontier = frontier(maxRuns = 2)
        (1L..10L).forEach { seq -> frontier.add(Dot(alice, seq)) }
        (100L..103L).forEach { seq -> frontier.add(Dot(alice, seq)) }
        (1L..20L).forEach { seq -> frontier.add(Dot(bob, seq)) }
        val beforeTrim = frontier.runCount

        frontier.trim()

        assertAll(
            { assertEquals(3, beforeTrim, "three runs, and room for two") },
            { assertEquals(2, frontier.runCount, "so one goes") },
            { assertFalse(frontier.contains(Dot(alice, 100L)), "the shortest run is the one evicted") },
            { assertTrue(frontier.contains(Dot(alice, 10L)), "alice's ten-dot run survives") },
            { assertTrue(frontier.contains(Dot(bob, 20L)), "and so does bob's twenty-dot one") },
            { assertFalse(frontier.contains(Dot(alice, 50L)), "and nothing was merged across the hole") },
        )
    }

    /**
     * `maxRuns = 0` holds nothing — the frontier's counterpart of a zero-sized dedup window, for an
     * owner that never merges and so never needs suppression at all.
     */
    @Test
    fun aFrontierWithNoRoomHoldsNothing() {
        val frontier = frontier(maxRuns = 0)
        (1L..10L).forEach { seq -> frontier.add(Dot(alice, seq)) }

        frontier.trim()

        assertAll(
            { assertEquals(0, frontier.runCount) },
            { assertEquals(0, frontier.authorCount, "and the author is dropped with its last run") },
            { assertFalse(frontier.contains(Dot(alice, 1L))) },
        )
    }
}
