package us.tractat.kuilt.heddle

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins every row of the hand-worked table in the guide's "How the next turn is chosen" page
 * (`Writerside/topics/heddle-local-turns.md`), on the farm `sampleBuildFarm` builds: web at
 * weight 3, mobile at weight 1, a 3-unit reservation, every build finishing on its estimate.
 *
 * Before each build, the lane that loses is explained: its `leafSideVirtualService` is its own
 * `ev` column and `eligibleFront` the line `V`, and the lane that wins is `WouldBePickedNext`.
 * The winner's `ev` then follows from `V = (3·ev(web) + ev(mobile)) / 4`, so the table holds no
 * number this test does not reach.
 */
class LocalHeddleGuideTraceTest {

    private class Row(val build: Int, val winner: GroupId, val loser: GroupId, val loserEv: Rational, val line: Rational)

    @Test
    fun theFirstNineBuildsMatchTheGuidesTable() {
        val farm = draft("farm") {
            group("web", Weight.of(3)) { lane("builds") }
            group("mobile") { lane("builds") }
        }
        val web = farm.groupId("web", "builds")
        val mobile = farm.groupId("mobile", "builds")
        val heddle = LocalHeddle.fromTopology(farm, LocalPolicy(defaultReservation = 3L), epoch = 0L)
        heddle.reconcileCapacity(totalSlots = 4L)
        val both = setOf(web, mobile)

        val table = listOf(
            Row(1, web, mobile, Rational.ZERO, Rational.ZERO),
            Row(2, mobile, web, Rational.of(1L), Rational.of(3L, 4L)),
            Row(3, web, mobile, Rational.of(3L), Rational.of(3L, 2L)),
            Row(4, web, mobile, Rational.of(3L), Rational.of(9L, 4L)),
            Row(5, web, mobile, Rational.of(3L), Rational.of(3L)),
            Row(6, mobile, web, Rational.of(4L), Rational.of(15L, 4L)),
            Row(7, web, mobile, Rational.of(6L), Rational.of(9L, 2L)),
            Row(8, web, mobile, Rational.of(6L), Rational.of(21L, 4L)),
            Row(9, web, mobile, Rational.of(6L), Rational.of(6L)),
        )
        val checks = ArrayList<() -> Unit>()
        for (row in table) {
            val why = heddle.explain(row.loser, waiting = both, eligible = both)
            val picked = heddle.explain(row.winner, waiting = both, eligible = both)
            val lease = heddle.acquire(both, both, startedAt = row.build.toLong())
            val leaf = (lease as? AcquireOutcome.Acquired)?.lease
            if (leaf != null) heddle.complete(leaf.id, actualCost = 3L)
            checks += { assertEquals(row.winner, leaf?.leaf, "build ${row.build} goes to ${row.winner.value}") }
            checks += { assertEquals(WaitExplanation.WouldBePickedNext, picked, "build ${row.build}: the winner is picked next") }
            checks += {
                val behind = why as? WaitExplanation.BehindAtLevel
                assertEquals(row.loserEv, behind?.leafSideVirtualService, "build ${row.build}: ${row.loser.value}'s ev ($why)")
            }
            checks += {
                val behind = why as? WaitExplanation.BehindAtLevel
                assertEquals(row.line, behind?.eligibleFront, "build ${row.build}: the line V ($why)")
            }
        }
        assertEquals(36, checks.size, "four checks per row, nine rows")
        assertAll(*checks.toTypedArray())
    }
}
