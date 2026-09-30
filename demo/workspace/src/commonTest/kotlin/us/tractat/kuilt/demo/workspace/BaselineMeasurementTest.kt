package us.tractat.kuilt.demo.workspace

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The M0 exit evidence: the golden [Metrics] table for the server-owned baseline, every scenario
 * against both variants. `RESULTS.md` carries the same 14 rows with a plain explanation of each
 * non-zero cell. Counts over scripted actors, JVM only; not a user study.
 *
 * Every value was derived by hand from [BaselineBackend]'s documented semantics before the run
 * confirmed it. A change here is a change to the recorded baseline, so update `RESULTS.md` with it.
 */
class BaselineMeasurementTest {
    /** Positional, in [Metrics] field order, so each row reads as one line of the table. */
    private fun m(
        editsMade: Int, editsPreserved: Int, agentRuns: Int, unnecessaryReruns: Int, stale: Int,
        falseInvalidations: Int, humanPrompts: Int, outageActions: Int, outageActionsServed: Int,
    ) = Metrics(
        editsMade, editsPreserved, agentRuns, unnecessaryReruns, stale,
        falseInvalidations, humanPrompts, outageActions, outageActionsServed,
    )

    @Test
    fun baselineMetrics() = runTest {
        //                         edits pres runs unnec stale false prompts outage served
        val expected: Map<Pair<String, Boolean>, Metrics> = mapOf(
            // Two edits to different scopes, no agent, no partition.
            ("S1" to false) to m(2, 2, 0, 0, 0, 0, 0, 0, 0),
            ("S1" to true) to m(2, 2, 0, 0, 0, 0, 0, 0, 0),
            // Alex's offline budget is queued; on reconnect the deferred re-check reruns r1 to v2.
            ("S2" to false) to m(1, 1, 2, 0, 0, 0, 0, 1, 0),
            // The same, but the queued budget shows locally, so the outage action is served.
            ("S2" to true) to m(1, 1, 2, 0, 0, 0, 0, 1, 1),
            // Sam's closure bumps venue:v1 before release; the check reruns r1 to v2.
            ("S3" to false) to m(1, 1, 2, 0, 0, 0, 0, 0, 0),
            ("S3" to true) to m(1, 1, 2, 0, 0, 0, 0, 0, 0),
            // Two runs (r1, r2), no rerun. Two offline accepts; Sam's replay finds `plan` moved: one prompt.
            ("S4" to false) to m(1, 1, 2, 0, 0, 0, 1, 2, 0),
            // The same; both queued accepts count as served under optimistic local display.
            ("S4" to true) to m(1, 1, 2, 0, 0, 0, 1, 2, 2),
            // The reopening is outside r1's scopes {budget, venue:v2}: v2 shown to all three, truth v1.
            ("S5" to false) to m(2, 2, 1, 0, 3, 0, 0, 0, 0),
            ("S5" to true) to m(2, 2, 1, 0, 3, 0, 0, 0, 0),
            // The online budget moves `budget` before release; the check reruns r1 to v2.
            ("S6" to false) to m(1, 1, 2, 0, 0, 0, 0, 0, 0),
            ("S6" to true) to m(1, 1, 2, 0, 0, 0, 0, 0, 0),
            // The note bumps venue:v1; the rerun returns v1 again, an unnecessary rerun.
            ("S7" to false) to m(1, 1, 2, 1, 0, 0, 0, 0, 0),
            ("S7" to true) to m(1, 1, 2, 1, 0, 0, 0, 0, 0),
        )
        assertEquals(Scenarios.all.size * 2, expected.size, "one row per scenario and variant")
        val actual = expected.keys.associateWith { (name, optimisticLocal) ->
            val scenario = Scenarios.all.first { it.name == name }
            Oracle.score(scenario, BaselineBackend(optimisticLocal).run(scenario))
        }
        assertEquals(expected, actual)
    }
}
