package us.tractat.kuilt.heddle

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every [LocalHeddle] call that charges observes the waiting set it is given before its charge
 * lands (#3471), and so a returning lane gets its share and no more.
 *
 * **The overrun window.** Weights are 1:1. `b`'s last job starts, and then `a`'s lease overruns by
 * 100. That overrun arrives through [LocalHeddle.extend] or through an overrunning
 * [LocalHeddle.complete], with `b` not waiting. Then both lanes ask for 40 one-unit slots. `b` was
 * idle while `a` took the 100, so it is owed nothing for them, and the 40 split 20/20. When the
 * charge inferred demand from history, `b` still counted as "last seen demanding". It was seen
 * leaving only after the overrun, so it banked the 100 and took all 40.
 *
 * The overrun can also be `a`'s last job, so `a` is not in the waiting set either. The overrun is
 * still the running work's own charge, so `a` counts as waiting for it, as in `extend`. Counted
 * idle, `a` was seen leaving before its own overrun landed, the clock did not move for it, and `b`
 * banked the 100 all the same.
 *
 * **The burst rows.** These are [BurstLagTest]'s S7, S7b, S7c and 3:1 rows, played through
 * [LocalHeddle], each by [LocalHeddle.acquire] and by [LocalHeddle.previewPicks] then
 * [LocalHeddle.admit]. Each lands on the exact split the allocator's own rig lands on, and each
 * counts the wakes it drove, so a rig that never woke a lane fails. S1c, S1w and S8 are not
 * mirrored. Their allocator rigs observe a round where nobody waits. Before #3471 no
 * `LocalHeddle` call could observe that: `acquire` returns `NothingEligible` before it observes,
 * and `admit` needs its leaf in the set. A `complete` past its reservation with an empty waiting
 * set now observes it, but that is not the rig those rows pin, so they are left to the allocator.
 */
class LocalHeddleChargeObservesWaitingTest {
    private val root = GroupId("p")

    private enum class Overrun { EXTEND, COMPLETE, LAST_JOB_EXTEND, LAST_JOB_COMPLETE }

    /**
     * The overrun window's `a / b` split of the next 40 slots, with the 100 arriving via [via]. In
     * the `LAST_JOB` arms `a`'s overrunning lease is its last job, so no charge names a waiting leaf.
     */
    private fun overrunWindow(via: Overrun): Pair<Int, Int> {
        val kids = listOf(edgeOf(root, GroupId("p/a")), edgeOf(root, GroupId("p/b")))
        val engine = engineOver(shapedLedger(root, kids), root, supply = 100_000L)
        val (a, b) = kids[0].child to kids[1].child
        val both = setOf(a, b)
        fun lease(waiting: Set<GroupId>, eligible: Set<GroupId>): Lease =
            (engine.acquire(waiting, eligible, startedAt = 0L, reservation = 1L) as AcquireOutcome.Acquired).lease
        repeat(10) { engine.complete(lease(both, both).id, 1L, waiting = both) }
        val la = lease(both, setOf(a))
        // b's last job starts: from here on only a has work queued.
        val lb = lease(both, setOf(b))
        when (via) {
            Overrun.EXTEND -> {
                engine.extend(la.id, 100L, waiting = setOf(a))
                engine.complete(la.id, 101L, waiting = setOf(a))
            }
            Overrun.COMPLETE -> engine.complete(la.id, 101L, waiting = setOf(a))
            Overrun.LAST_JOB_EXTEND -> {
                engine.extend(la.id, 100L, waiting = emptySet())
                engine.complete(la.id, 101L, waiting = emptySet())
            }
            Overrun.LAST_JOB_COMPLETE -> engine.complete(la.id, 101L, waiting = emptySet())
        }
        val lastJob = via == Overrun.LAST_JOB_EXTEND || via == Overrun.LAST_JOB_COMPLETE
        engine.complete(lb.id, 1L, waiting = if (lastJob) emptySet() else setOf(a))
        var aTake = 0
        var bTake = 0
        repeat(40) {
            val next = lease(both, both)
            if (next.leaf == a) aTake++ else bTake++
            engine.complete(next.id, 1L, waiting = both)
        }
        return aTake to bTake
    }

    @Test
    fun anOverrunWhileASiblingIdlesIsNotBankedByIt() {
        assertAll(
            { assertEquals(20 to 20, overrunWindow(Overrun.EXTEND), "through extend") },
            { assertEquals(20 to 20, overrunWindow(Overrun.COMPLETE), "through an overrunning complete") },
            { assertEquals(20 to 20, overrunWindow(Overrun.LAST_JOB_EXTEND), "through extend, on a's last job") },
            { assertEquals(20 to 20, overrunWindow(Overrun.LAST_JOB_COMPLETE), "through an overrunning complete, on a's last job") },
        )
    }

    private enum class Via { ACQUIRE, ADMIT }

    private class Rig(weights: List<Long>, val via: Via) {
        val kids = weights.mapIndexed { i, w -> edgeOf(GroupId("p"), GroupId("p/b$i"), w) }
        val engine = engineOver(shapedLedger(GroupId("p"), kids), GroupId("p"), supply = 100_000_000L)
        val granted = LinkedHashMap<GroupId, Long>().apply { kids.forEach { put(it.child, 0L) } }
        var wakes = 0

        /** One unit to whichever of [asking] the engine picks. */
        fun unit(asking: Set<Int>) {
            val waiting = asking.mapTo(LinkedHashSet()) { kids[it].child }
            wakes += kids.count { engine.allocator.lastObservedDemanding(it.id) == false && it.child in waiting }
            val outcome = when (via) {
                Via.ACQUIRE -> engine.acquire(waiting, waiting, startedAt = 0L, reservation = 1L)
                Via.ADMIT -> {
                    val leaf = engine.previewPicks(waiting.associateWith { 1 }, waiting, listOf(1L)).single()
                    engine.admit(leaf, waiting, startedAt = 0L, reservation = 1L)
                }
            }
            val lease = (outcome as AcquireOutcome.Acquired).lease
            granted[lease.leaf] = granted.getValue(lease.leaf) + 1L
            engine.complete(lease.id, 1L, waiting = waiting - lease.leaf)
        }
    }

    /** S7's shape, [BURSTS] times: one unit only child [first] wants, then [rest] every child wants. */
    private fun staggered(weights: List<Long>, first: Int, rest: Int, via: Via): Rig {
        val rig = Rig(weights, via)
        repeat(BURSTS) {
            rig.unit(setOf(first))
            repeat(rest) { rig.unit(weights.indices.toSet()) }
        }
        return rig
    }

    private fun row(label: String, fair: List<Long>, weights: List<Long>, first: Int, rest: Int): () -> Unit = {
        val runs = Via.entries.map { it to staggered(weights, first, rest, it) }
        assertAll(
            *runs.flatMap { (via, rig) ->
                listOf(
                    { assertEquals(fair, rig.granted.values.toList(), "$label via $via: the allocator's split") },
                    { assertTrue(rig.wakes >= BURSTS, "$label via $via: the rig drove ${rig.wakes} wakes, wanted at least $BURSTS") },
                )
            }.toTypedArray(),
        )
    }

    @Test
    fun theBurstRowsLandOnTheAllocatorsSplitThroughBothEntryPoints() {
        assertAll(
            row("S7 2:1 heavy first", listOf(500, 100), listOf(2, 1), first = 0, rest = 1),
            row("S7b 2:1 light first", listOf(200, 400), listOf(2, 1), first = 1, rest = 1),
            row("S7 3:1 heavy first", listOf(525, 75), listOf(3, 1), first = 0, rest = 1),
            row("S7 3:1 light first", listOf(225, 375), listOf(3, 1), first = 1, rest = 1),
            row("S7c 1:1, 1 then 2", listOf(600, 300), listOf(1, 1), first = 0, rest = 2),
        )
    }

    private companion object {
        /** [BurstLagTest]'s burst count, so the fair splits are its own. */
        const val BURSTS = 300
    }
}
