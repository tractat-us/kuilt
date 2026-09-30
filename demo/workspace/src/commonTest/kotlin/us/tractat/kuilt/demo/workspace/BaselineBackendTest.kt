package us.tractat.kuilt.demo.workspace

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BaselineBackendTest {
    private val alex = ActorId("alex")
    private val sam = ActorId("sam")
    private val remote = ActorId("remote")
    private val v1 = VenueId("v1")
    private val v2 = VenueId("v2")

    private fun scenario(name: String) = Scenarios.all.first { it.name == name }
    private suspend fun run(name: String, optimisticLocal: Boolean = false) =
        BaselineBackend(optimisticLocal).run(scenario(name))

    @Test
    fun closureDuringInferenceForcesRerunAndIsNotStale() = runTest {
        val r = run("S3")
        assertAll(
            { assertEquals(2, r.agentRuns) },
            { assertEquals(listOf(Recommendation(v1) to Recommendation(v2)), r.reruns) },
            { assertEquals(0, Oracle.score(scenario("S3"), r).staleTreatedAsCurrent) },
        )
    }

    @Test
    fun budgetChangedOfflineIsNotVisibleUntilReconnect() = runTest {
        val r = run("S2")
        assertAll(
            { assertEquals(1, r.outageActions) },
            { assertEquals(0, r.outageActionsServed) },
        )
    }

    @Test
    fun conflictingAcceptsPromptAHuman() = runTest {
        assertEquals(1, run("S4").humanPrompts)
    }

    @Test
    fun independentEditsBothSurvive() = runTest {
        val s1 = scenario("S1")
        val m = Oracle.score(s1, BaselineBackend().run(s1))
        assertAll(
            { assertEquals(2, m.editsMade) },
            { assertEquals(m.editsMade, m.editsPreserved) },
        )
    }

    /** Ruling H: what Alex knows when r1 reaches him after reconnect includes the budget he typed offline. */
    @Test
    fun presentationToReconnectedAlexKnowsHisOwnOfflineBudget() = runTest {
        val r = run("S2")
        val toAlex = r.presentations.filter { it.actor == alex && it.request == "r1" }
        assertAll(
            { assertEquals(1, toAlex.size) },
            { assertTrue(toAlex.all { InputKey("budget30") in it.known }) },
        )
    }

    /**
     * S2's deferred presentation re-runs the version check after Alex's queue replays: the budget scope
     * has moved since capture, so the server reruns r1 (v1 → v2) rather than showing Alex a stale v1.
     */
    @Test
    fun offlineBudgetFlushedOnReconnectForcesRerunBeforeAlexSeesR1() = runTest {
        val r = run("S2")
        val m = Oracle.score(scenario("S2"), r)
        assertAll(
            { assertEquals(2, r.agentRuns) },
            { assertEquals(listOf(Recommendation(v1) to Recommendation(v2)), r.reruns) },
            { assertEquals(0, m.staleTreatedAsCurrent) },
            { assertEquals(0, m.humanPrompts) },
            { assertEquals(Recommendation(v2), r.presentations.single { it.actor == alex }.recommendation) },
        )
    }

    /**
     * S6 is S2 with Alex connected: `budget30` reaches the server before r1 returns, so the release-time
     * check sees `budget` moved and reruns r1 (v1 → v2). Every actor is connected at release, so all
     * three get the rerun at once, and nothing is queued or prompted.
     */
    @Test
    fun budgetChangedWhileConnectedForcesRerunAtRelease() = runTest {
        val r = run("S6")
        val m = Oracle.score(scenario("S6"), r)
        assertAll(
            { assertEquals(2, r.agentRuns) },
            { assertEquals(listOf(Recommendation(v1) to Recommendation(v2)), r.reruns) },
            { assertEquals(listOf(alex, sam, remote), r.presentations.map { it.actor }) },
            { assertTrue(r.presentations.all { it.recommendation == Recommendation(v2) }) },
            { assertEquals(0, m.staleTreatedAsCurrent) },
            { assertEquals(0, r.outageActions) },
            { assertEquals(0, r.humanPrompts) },
        )
    }

    /**
     * S7: Sam's note bumps `venue:v1`, which is in r1's scopes {budget, venue:v1}. The check cannot tell a
     * note from a closure, so it reruns, and the rerun returns the same v1: one unnecessary rerun.
     */
    @Test
    fun harmlessNoteOnTheRecommendedVenueCostsAnUnnecessaryRerun() = runTest {
        val r = run("S7")
        val m = Oracle.score(scenario("S7"), r)
        assertAll(
            { assertEquals(2, r.agentRuns) },
            { assertEquals(listOf(Recommendation(v1) to Recommendation(v1)), r.reruns) },
            { assertEquals(1, m.unnecessaryReruns) },
            { assertEquals(0, m.staleTreatedAsCurrent) },
            { assertEquals(0, r.humanPrompts) },
        )
    }

    /** S5: the reopening touches `venue:v1`, outside r1's scopes {budget, venue:v2}. The check cannot see it. */
    @Test
    fun reopeningAnotherVenueSlipsPastTheVersionCheck() = runTest {
        val r = run("S5")
        val m = Oracle.score(scenario("S5"), r)
        assertAll(
            { assertEquals(1, r.agentRuns) },
            { assertEquals(3, r.presentations.size) },
            { assertEquals(3, m.staleTreatedAsCurrent) },
        )
    }

    /**
     * Ruling K: both offline accepts are outage actions. Ruling N: under optimistic local display each
     * counts as served, because an optimistic client would show "you chose X" locally. No accept entry
     * exists, so this credit is declared by the ruling, not observable in a final view; HYPOTHESES.md
     * Q2 is where Iain decides whether it stands.
     */
    @Test
    fun offlineAcceptsAreOutageActionsAndServedOptimisticallyByRulingN() = runTest {
        val strict = run("S4")
        val optimistic = run("S4", optimisticLocal = true)
        assertAll(
            { assertEquals(2, strict.outageActions) },
            { assertEquals(0, strict.outageActionsServed) },
            { assertEquals(2, optimistic.outageActions) },
            { assertEquals(2, optimistic.outageActionsServed) },
            { assertEquals(1, optimistic.humanPrompts) },
        )
    }

    @Test
    fun optimisticLocalServesTheOfflineBudgetEdit() = runTest {
        val r = run("S2", optimisticLocal = true)
        assertAll(
            { assertEquals(1, r.outageActions) },
            { assertEquals(1, r.outageActionsServed) },
        )
    }

    @Test
    fun everyScenarioReportsAFinalViewForEveryActor() = runTest {
        val checks = listOf(false, true).flatMap { optimistic ->
            Scenarios.all.map { s -> Triple(s, optimistic, BaselineBackend(optimistic).run(s)) }
        }.map { (s, optimistic, r) ->
            { assertEquals(s.actors.toSet(), r.finalViews.keys, "${s.name} optimisticLocal=$optimistic") }
        }
        assertAll(*checks.toTypedArray())
    }

    /** A partitioned edit is not served: until reconnect it is in nobody's view, not even its author's. */
    @Test
    fun queuedEditIsInvisibleUntilReconnectUnlessOptimistic() = runTest {
        val budget = WorkspaceEntry.PreferenceSet(alex, budget = 30)
        val s = Scenario(
            "offline-tail", listOf(alex, sam, remote),
            listOf(Step.Partition(alex), Step.Edit(InputKey("budget30"), alex, budget)),
        )
        val strict = BaselineBackend().run(s)
        val optimistic = BaselineBackend(optimisticLocal = true).run(s)
        assertAll(
            { assertFalse(budget in strict.finalViews.getValue(alex)) },
            { assertFalse(budget in strict.finalViews.getValue(remote)) },
            { assertTrue(budget in optimistic.finalViews.getValue(alex)) },
            { assertFalse(budget in optimistic.finalViews.getValue(remote)) },
        )
    }

    @Test
    fun queuedEditOnAMovedScopePromptsAndStillApplies() = runTest {
        val b30 = WorkspaceEntry.PreferenceSet(alex, budget = 30)
        val b20 = WorkspaceEntry.PreferenceSet(sam, budget = 20)
        val s = Scenario(
            "budget-race", listOf(alex, sam, remote),
            listOf(
                Step.Partition(alex),
                Step.Edit(InputKey("b30"), alex, b30),
                Step.Edit(InputKey("b20"), sam, b20),
                Step.Reconnect(alex),
            ),
        )
        val r = BaselineBackend().run(s)
        assertAll(
            { assertEquals(1, r.humanPrompts) },
            { assertEquals(2, Oracle.score(s, r).editsPreserved) },
        )
    }

    /** Two offline edits by one actor to one scope are ordered by that actor; the second is no conflict. */
    @Test
    fun ownSuccessiveQueuedEditsDoNotConflict() = runTest {
        val s = Scenario(
            "own-queue", listOf(alex, sam, remote),
            listOf(
                Step.Partition(alex),
                Step.Edit(InputKey("b30"), alex, WorkspaceEntry.PreferenceSet(alex, budget = 30)),
                Step.Edit(InputKey("b20"), alex, WorkspaceEntry.PreferenceSet(alex, budget = 20)),
                Step.Reconnect(alex),
            ),
        )
        assertEquals(0, BaselineBackend().run(s).humanPrompts)
    }

    /**
     * The agent's selection left out a closure it could have seen. Its captured `venue:v1` version counts
     * only what it received, so the check catches the gap and reruns on the full current set.
     */
    @Test
    fun excludedInputInADependentScopeForcesRerun() = runTest {
        val closeV1 = InputKey("closeV1")
        val s = Scenario(
            "excluded", listOf(alex, sam, remote),
            listOf(
                Step.Edit(closeV1, sam, WorkspaceEntry.Report.Closed(sam, v1)),
                Step.StartAgent("r1", host = remote, selectExcluding = setOf(closeV1)),
                Step.ReleaseAgent("r1"),
            ),
        )
        val r = BaselineBackend().run(s)
        assertAll(
            { assertEquals(listOf(Recommendation(v1) to Recommendation(v2)), r.reruns) },
            { assertEquals(0, Oracle.score(s, r).staleTreatedAsCurrent) },
        )
    }

    /**
     * Sam saw r1's v1 and went offline; Alex's flushed budget then replaced r1 with v2. Sam must get the
     * replacement when he reconnects, not keep v1 as his last word on r1.
     */
    @Test
    fun rerunReplacementReachesAnOfflineActorWhoSawTheOriginal() = runTest {
        val s = Scenario(
            "replacement-offline", listOf(alex, sam, remote),
            listOf(
                Step.Partition(alex),
                Step.StartAgent("r1", host = remote),
                Step.ReleaseAgent("r1"),
                Step.Partition(sam),
                Step.Edit(InputKey("budget30"), alex, WorkspaceEntry.PreferenceSet(alex, budget = 30)),
                Step.Reconnect(alex),
                Step.Reconnect(sam),
            ),
        )
        val r = BaselineBackend().run(s)
        val toSam = r.presentations.filter { it.actor == sam }
        assertAll(
            { assertEquals(listOf(Recommendation(v1), Recommendation(v2)), toSam.map { it.recommendation }) },
            { assertTrue(InputKey("budget30") in toSam.last().known) },
            { assertEquals(1, r.reruns.size) },
            { assertEquals(0, Oracle.score(s, r).staleTreatedAsCurrent) },
        )
    }
}
