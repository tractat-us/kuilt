@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.demo.workspace

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.drainAntiEntropy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * End-of-run scoring (HYPOTHESES.md § Metrics, amended 2026-10-01): each actor's **standing answer**,
 * the latest proposal it has been shown, is scored against everything it knows when the run ends.
 * `Metrics.staleAtEnd` counts the standing answers shown as applicable that ground truth then calls
 * stale. The synthetic cases pin the definition; the backend cases pin what S2 measures.
 */
class EndOfRunScoringTest {
    private val alex = ActorId("alex")
    private val sam = ActorId("sam")
    private val remote = ActorId("remote")
    private val v1 = Recommendation(VenueId("v1"))
    private val v2 = Recommendation(VenueId("v2"))
    private val budget30 = InputKey("budget30")

    private fun shown(actor: ActorId, request: String, rec: Recommendation, basis: Set<InputKey>, applicable: Boolean = true) =
        Presentation(actor, request, rec, basis = basis, known = basis, shownAsApplicable = applicable)

    /** Every actor's final view holds every input, unless [views] says otherwise. */
    private fun result(s: Scenario, vararg p: Presentation, views: Map<ActorId, List<WorkspaceEntry>> = emptyMap()) = RunResult(
        presentations = p.toList(),
        finalViews = s.actors.associateWith { s.inputs.values.toList() } + views,
        agentRuns = 1, reruns = emptyList(), humanPrompts = 0, outageActions = 0, outageActionsServed = 0,
    )

    /**
     * The positive control. Sam is shown r1 (v1) as applicable before Alex's budget of 30 reaches
     * him, so it is fresh when shown: 0 per presentation. At the end he holds the budget and r1 still
     * stands, so the metric must count it.
     */
    @Test
    fun staleStandingAnswerCountsAtTheEnd() {
        val m = Oracle.score(Scenarios.s2, result(Scenarios.s2, shown(sam, "r1", v1, basis = emptySet())))
        assertAll(
            { assertEquals(0, m.staleTreatedAsCurrent) },
            { assertEquals(1, m.staleAtEnd) },
        )
    }

    /** The control on the end-of-run `known`: without the budget in Sam's final view, r1 is still right for him. */
    @Test
    fun answerStillRightForWhatTheActorHoldsDoesNotCount() {
        val m = Oracle.score(
            Scenarios.s2,
            result(Scenarios.s2, shown(sam, "r1", v1, basis = emptySet()), views = mapOf(sam to emptyList())),
        )
        assertEquals(0, m.staleAtEnd)
    }

    /** An actor knows what it created, served or not: Alex's own offline budget counts though his final view lacks it. */
    @Test
    fun ownUnservedInputCountsAtTheEnd() {
        val m = Oracle.score(
            Scenarios.s2,
            result(Scenarios.s2, shown(alex, "r1", v1, basis = emptySet()), views = mapOf(alex to emptyList())),
        )
        assertEquals(1, m.staleAtEnd)
    }

    /** A standing answer flagged for review is not treated as current, so it is never stale at the end. */
    @Test
    fun flaggedStandingAnswerDoesNotCount() {
        val m = Oracle.score(Scenarios.s2, result(Scenarios.s2, shown(sam, "r1", v1, basis = emptySet(), applicable = false)))
        assertEquals(0, m.staleAtEnd)
    }

    /**
     * The definition Iain chose: the latest proposal stands, so a superseded r1 is not re-scored. The
     * "every answer ever shown" variant would count r1 here and score 1. The r1-only arm is the
     * control that r1 alone is stale at the end.
     */
    @Test
    fun supersededAnswerDoesNotStand() {
        val r1 = shown(alex, "r1", v1, basis = emptySet())
        val r2 = shown(alex, "r2", v2, basis = setOf(budget30))
        val both = result(Scenarios.s4, r1, r2)
        assertAll(
            { assertEquals(0, Oracle.score(Scenarios.s4, both).staleAtEnd) },
            { assertEquals(r2, Oracle.standingAnswers(Scenarios.s4, both).getValue(alex)) },
            { assertEquals(1, Oracle.score(Scenarios.s4, result(Scenarios.s4, r1)).staleAtEnd) },
        )
    }

    /** "Latest" is by release, not by delivery: r2 stands over an r1 that reached Sam after it. */
    @Test
    fun latestIsByReleaseOrder() {
        val r2 = shown(sam, "r2", v2, basis = setOf(budget30))
        val r1 = shown(sam, "r1", v1, basis = emptySet())
        val res = result(Scenarios.s4, r2, r1)
        assertAll(
            { assertEquals(r2, Oracle.standingAnswers(Scenarios.s4, res).getValue(sam)) },
            { assertEquals(0, Oracle.score(Scenarios.s4, res).staleAtEnd) },
        )
    }

    /** Within one request the last presentation stands: the baseline's rerun replaces the answer it showed. */
    @Test
    fun rerunOfTheSameRequestStands() {
        val first = shown(sam, "r1", v1, basis = emptySet())
        val rerun = shown(sam, "r1", v2, basis = setOf(budget30))
        val res = result(Scenarios.s2, first, rerun)
        assertAll(
            { assertEquals(rerun, Oracle.standingAnswers(Scenarios.s2, res).getValue(sam)) },
            { assertEquals(0, Oracle.score(Scenarios.s2, res).staleAtEnd) },
        )
    }

    /**
     * The verdict that stands is the one the proposal was **last shown with**, not the first and not
     * a fresh re-assessment. A re-check that later shows Sam the same r1 flagged adds a row, and that
     * row stands, so the stale count drops to 0. The one-row arm is the control: shown once as
     * fitting, the same r1 counts. Re-assessing at the end instead would score what a screen would
     * say, and pass without any re-check.
     */
    @Test
    fun laterFlaggedRowForTheSameProposalStands() {
        val fits = shown(sam, "r1", v1, basis = emptySet())
        val flaggedLater = Presentation(sam, "r1", v1, basis = emptySet(), known = setOf(budget30), shownAsApplicable = false)
        val rechecked = result(Scenarios.s2, fits, flaggedLater)
        assertAll(
            { assertEquals(flaggedLater, Oracle.standingAnswers(Scenarios.s2, rechecked).getValue(sam)) },
            { assertEquals(0, Oracle.score(Scenarios.s2, rechecked).staleAtEnd) },
            { assertEquals(1, Oracle.score(Scenarios.s2, result(Scenarios.s2, fits)).staleAtEnd) },
        )
    }

    @Test
    fun presentationOfAnUnreleasedRequestThrows() {
        assertFailsWith<IllegalArgumentException> {
            Oracle.standingAnswers(Scenarios.s2, result(Scenarios.s2, shown(sam, "r9", v1, basis = emptySet())))
        }
    }

    private fun TestScope.kuilt() = KuiltBackend(
        scope = backgroundScope,
        advance = { drainAntiEntropy(KuiltBackend.antiEntropyInterval, rounds = 10) },
        network = { scope -> FaultyNetwork(scope) },
    )

    /**
     * What S2 measures, by actor. kuilt shows Sam and remote r1 (v1) as fitting before the budget
     * reaches them and never re-checks it, so theirs are the two stale standing answers; Alex's is
     * flagged. Both baseline variants push the rerun (v2) to everyone, so v2 stands everywhere.
     */
    @Test
    fun s2StandingAnswersByBackend() = runTest(UnconfinedTestDispatcher()) {
        val k = kuilt().run(Scenarios.s2)
        val kStanding = Oracle.standingAnswers(Scenarios.s2, k)
        val baselines = listOf(false, true).map { BaselineBackend(it).run(Scenarios.s2) }
        assertAll(
            { assertEquals(setOf(alex, sam, remote), kStanding.keys) },
            { assertEquals(listOf(true, true), listOf(sam, remote).map { kStanding.getValue(it).shownAsApplicable }) },
            { assertEquals(listOf(v1, v1), listOf(sam, remote).map { kStanding.getValue(it).recommendation }) },
            { assertEquals(false, kStanding.getValue(alex).shownAsApplicable) },
            { assertEquals(2, Oracle.score(Scenarios.s2, k).staleAtEnd) },
            {
                baselines.forEach { b ->
                    val standing = Oracle.standingAnswers(Scenarios.s2, b)
                    assertEquals(mapOf(alex to v2, sam to v2, remote to v2), standing.mapValues { it.value.recommendation })
                    assertEquals(0, Oracle.score(Scenarios.s2, b).staleAtEnd)
                }
            },
        )
    }

    /**
     * RESULTS.md says that in S1–S7 "latest by release" and "last shown" pick the same answer, for
     * every backend. This pins that claim, so the choice of order is not what moves any number.
     */
    @Test
    fun releaseOrderAndShownOrderAgreeInEveryScenario() = runTest(UnconfinedTestDispatcher()) {
        val backends: List<Pair<String, WorkspaceBackend>> =
            listOf("kuilt" to kuilt(), "false" to BaselineBackend(false), "true" to BaselineBackend(true))
        for ((name, backend) in backends) {
            for (s in Scenarios.all) {
                val r = backend.run(s)
                val lastShown = r.presentations.groupBy { it.actor }.mapValues { it.value.last() }
                assertEquals(lastShown, Oracle.standingAnswers(s, r), "$name ${s.name}")
            }
        }
    }
}
