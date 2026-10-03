@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.demo.workspace

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.drainAntiEntropy
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The M1 exit evidence: the golden [Metrics] table for the kuilt backend, one row per scenario.
 * kuilt has no `optimisticLocal` variant, so there are seven rows, not fourteen. `RESULTS.md`
 * carries the same rows with a plain explanation of each non-zero cell. Counts over scripted
 * actors, JVM only; not a user study.
 *
 * Every value was derived by hand from [KuiltBackend]'s documented semantics before the run
 * confirmed it. A change here is a change to the recorded kuilt result, so update `RESULTS.md`
 * with it.
 *
 * Each step is followed by bounded anti-entropy rounds, as `KuiltBackendTest` drives them. The
 * backend checks convergence after every step and throws if the rounds were too few, so a short
 * drain fails this test rather than scoring low.
 */
class KuiltMeasurementTest {
    /** Positional, in [Metrics] field order, so each row reads as one line of the table. */
    private fun m(
        editsMade: Int, editsPreserved: Int, agentRuns: Int, unnecessaryReruns: Int, stale: Int, staleAtEnd: Int,
        falseInvalidations: Int, humanPrompts: Int, outageActions: Int, outageActionsServed: Int,
        unknownPresentations: Int,
    ) = Metrics(
        editsMade, editsPreserved, agentRuns, unnecessaryReruns, stale, staleAtEnd,
        falseInvalidations, humanPrompts, outageActions, outageActionsServed, unknownPresentations,
    )

    @Test
    fun kuiltMetrics() = runTest(UnconfinedTestDispatcher()) {
        //                  edits pres runs unnec stale atEnd false prompts outage served unknown
        val expected: Map<String, Metrics> = mapOf(
            // Two edits merge on every replica. No agent, no accept, nobody asked.
            "S1" to m(2, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            // r1 captures remote's empty log and returns v1. Alex's offline budget is in his own
            // replica at once (served). On reconnect he holds a budget r1 never received, clause (c):
            // flagged, one prompt. Truth for him is v2, so the flag is not a false invalidation.
            // Sam and remote were shown r1 as applicable before the budget reached them. When it
            // does, each re-judges its standing r1, flags it, and Sam is asked (#2880): staleAtEnd
            // is 0 and prompts 2. Without the re-check this row reads staleAtEnd 2, prompts 1.
            "S2" to m(1, 1, 1, 0, 0, 0, 0, 2, 1, 1, 0),
            // Every replica holds Sam's closure of v1 when r1 lands, clause (a): three flags, and
            // alex and sam are the two people asked. Truth is v2 for all three.
            "S3" to m(1, 1, 1, 0, 0, 0, 0, 2, 0, 0, 0),
            // r1 (v1) and r2 (v2) each run once and are applicable to whoever first holds them.
            // Alex's own budget of 30 makes his standing r1 over budget, so his phone re-flags it
            // and he is asked (#2880). Sam is partitioned; r2 reaches him with the budget and
            // supersedes r1 before r1 is re-judged. The two offline accepts are served locally;
            // when the phones meet, the pair conflicts once. Prompts: 1 + 1.
            "S4" to m(1, 1, 2, 0, 0, 0, 0, 2, 2, 2, 0),
            // The reopening of v1 is missing from r1's basis, clause (b): three flags, two people
            // asked. Truth is v1, so none of the flags is a false invalidation.
            "S5" to m(2, 2, 1, 0, 0, 0, 0, 2, 0, 0, 0),
            // Alex's online budget is missing from r1's basis, clause (c): three flags, two people.
            "S6" to m(1, 1, 1, 0, 0, 0, 0, 2, 0, 0, 0),
            // A note is never relevant: v1 is shown as applicable everywhere, and nothing reruns.
            "S7" to m(1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0),
        )
        assertEquals(Scenarios.all.size, expected.size, "one row per scenario")
        val backend = KuiltBackend(
            scope = backgroundScope,
            advance = { drainAntiEntropy(KuiltBackend.antiEntropyInterval, rounds = 10) },
            network = { scope -> FaultyNetwork(scope) },
        )
        val actual = expected.keys.associateWith { name ->
            val scenario = Scenarios.all.first { it.name == name }
            Oracle.score(scenario, backend.run(scenario))
        }
        assertEquals(expected, actual)
    }
}
