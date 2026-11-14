package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The front when every active child of a parent is joining and one of them is waking (#3329
 * review): the joiner furthest ahead, a waker at its effective position, a newborn at its own
 * origin. Every waker is clamped there and every newborn seated there.
 *
 * A twin engine runs this same rule, so it cannot pin the rule itself; these arms read the seat
 * and the stored offsets directly. The wakers are `w1` at 800 ticks, `w2` at 160 and `w3` at 480,
 * every weight 1, each one observed idle before, so each is a waker on its first pick.
 */
class FairShareAllocatorJoinersFrontTest {

    private val root = GroupId("p")
    private val newborn = edgeOf(root, GroupId("p/n"))
    private val w1 = edgeOf(root, GroupId("p/w1"))
    private val w2 = edgeOf(root, GroupId("p/w2"))
    private val w3 = edgeOf(root, GroupId("p/w3"))
    private val wants = Demand(targetOutstanding = 100L, maximumUsefulGrant = 1L)

    /** A ledger rooted at `p` over [children], holding [supply] at the root, the wakers seated at their positions. */
    private fun ledger(children: List<AttachmentRecord>, supply: Long): EntitlementLedger {
        var l = shapedLedger(root, children)
        l = l.piece(l.mint(MintId("m"), root, LOCAL, supply).delta)
        val seats = mapOf(w1 to 800L, w2 to 160L, w3 to 480L).filterKeys { it in children }
        return l.piece(EntitlementLedger.of(gauges = seats.map { (edge, ticks) -> edge.id to Gauge(ticksToVirtual(ticks, Weight.ONE), 0L) }.toMap()))
    }

    /** An allocator that has seen every one of [wakers] idle. */
    private fun allocator(wakers: List<AttachmentRecord>) = FairShareAllocator(
        LOCAL,
        PolicyConfig(quantum = 1L),
        AllocatorMemory(wakeOffsets = emptyMap(), demandingObserved = wakers.associate { it.id to false }),
    )

    @Test
    fun aNewbornIsSeatedAtTheTopWakerAndTheLowerWakerIsClampedToIt() {
        val allocator = allocator(listOf(w1, w2))
        val pick = checkNotNull(allocator.pickChild(root, ledger(listOf(newborn, w1, w2), supply = 1L), { wants }, { wants })) {
            "a pick with every child wanting a unit and one unit held"
        }
        val newbornFloor = checkNotNull(pick.ledger.gauge(newborn.id)) { "the newborn is seated" }.floor
        assertAll(
            { assertEquals(ticksToVirtual(800L, Weight.ONE), newbornFloor, "the newborn's floor is w1's position") },
            { assertEquals(ticksToVirtual(640L, Weight.ONE), allocator.memory.wakeOffsets[w2.id], "w2's offset is w1 less w2") },
        )
    }

    @Test
    fun scheduleLevelsAnAllWakersParentAndGrantsInIdOrder() {
        val allocator = allocator(listOf(w1, w2, w3))
        val cell = RecordingCell(ledger(listOf(w1, w2, w3), supply = 3L), listOf(w1, w2, w3))
        val grants = allocator.schedule(root, cell, earmarked = 0L) { wants }
        assertAll(
            { assertEquals(3, grants) },
            { assertEquals(ticksToVirtual(640L, Weight.ONE), allocator.memory.wakeOffsets[w2.id], "w2 clamped to w1") },
            { assertEquals(ticksToVirtual(320L, Weight.ONE), allocator.memory.wakeOffsets[w3.id], "w3 clamped to w1") },
            // Level at 800, so the id breaks the tie: w1, then w2 and w3, each behind w1's grant.
            { assertEquals(listOf(w1.id, w2.id, w3.id), cell.granted, "one grant each, in id order") },
        )
    }

    /** A [LedgerCell] over a value that records which of [watched] each applied patch delegated to. */
    private class RecordingCell(override var current: EntitlementLedger, private val watched: List<AttachmentRecord>) : LedgerCell {
        val granted = ArrayList<AttachmentId>()

        override fun mutateOrSkip(transform: (EntitlementLedger) -> Patch<EntitlementLedger>?): Boolean {
            val patch = transform(current) ?: return false
            val before = current
            current = current.piece(patch.delta)
            watched.filter { current.baseIssuance(it.id) > before.baseIssuance(it.id) }.forEach { granted += it.id }
            return true
        }
    }
}
