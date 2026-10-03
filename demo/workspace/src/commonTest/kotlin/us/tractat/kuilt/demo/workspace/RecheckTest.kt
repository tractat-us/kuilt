@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.demo.workspace

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.drainAntiEntropy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The re-check of `RECHECK.md` (#2880): a phone re-judges its standing answer whenever its replica
 * delivers something new, with the same [Assessment] and the same relevance clauses, and charges a
 * prompt only on entering a flagged state. Each test names the mutation that has to turn it red.
 */
class RecheckTest {
    private val alex = ActorId("alex")
    private val sam = ActorId("sam")
    private val remote = ActorId("remote")
    private val v1 = VenueId("v1")
    private val actors = listOf(alex, sam, remote)

    private fun TestScope.kuilt() = KuiltBackend(
        scope = backgroundScope,
        advance = { drainAntiEntropy(KuiltBackend.antiEntropyInterval, rounds = 10) },
        network = { scope -> FaultyNetwork(scope) },
    )

    private fun RunResult.rows(actor: ActorId) = presentations.filter { it.actor == actor }

    /**
     * The fix. Sam and remote are shown r1 (v1, 40) as fitting before Alex's budget of 30 reaches
     * them; at `Reconnect(alex)` it does, and both re-show r1 flagged. Sam is asked; remote hosts
     * the agent and is not. Control: skip the re-judge pass and Sam's and remote's last rows stay
     * fitting, `staleAtEnd` reads 2 and prompts 1.
     */
    @Test
    fun s2StaleStandingAnswersAreReFlagged() = runTest(UnconfinedTestDispatcher()) {
        val r = kuilt().run(Scenarios.s2)
        val m = Oracle.score(Scenarios.s2, r)
        assertAll(
            // The rig: each was first shown r1 as fitting, so there was something to re-check.
            { assertEquals(listOf(true, false), r.rows(sam).map { it.shownAsApplicable }) },
            { assertEquals(listOf(true, false), r.rows(remote).map { it.shownAsApplicable }) },
            { assertTrue(InputKey("budget30") in r.rows(sam).last().known, "the re-shown row carries the budget") },
            { assertEquals(0, m.staleAtEnd) },
            { assertEquals(0, m.falseInvalidations) },
            // Alex on arrival, Sam on the re-check.
            { assertEquals(2, m.humanPrompts) },
        )
    }

    /**
     * A note reaching every phone after r1 was shown changes no verdict: no new row, no prompt.
     * Control: charge a prompt on every re-judge, not only on entering a flagged state.
     */
    @Test
    fun irrelevantNewsAfterShowingAddsNothing() = runTest(UnconfinedTestDispatcher()) {
        val scenario = Scenario(
            "late-note", actors,
            listOf(
                Step.StartAgent("r1", host = remote),
                Step.ReleaseAgent("r1"),
                Step.Edit(InputKey("noteV1"), sam, WorkspaceEntry.Note(sam, v1, "Book the window table")),
            ),
        )
        val r = kuilt().run(scenario)
        assertAll(
            // The rig: the note reached every phone after r1 was shown there.
            { assertTrue(r.finalViews.values.all { view -> view.any { it is WorkspaceEntry.Note } }) },
            { assertEquals(3, r.presentations.size) },
            { assertTrue(r.presentations.all { it.shownAsApplicable }) },
            { assertEquals(0, r.humanPrompts) },
        )
    }

    /**
     * r1 arrives already flagged by Sam's closure of v1, and each person is asked once. Alex's budget
     * of 30 then reaches every phone: a further relevant input (clause (c)) for an answer already
     * flagged. Nobody is asked again, and the screen does not change, so no row is added. Control:
     * charge a prompt whenever a re-judge reads a flagged verdict.
     */
    @Test
    fun reFlaggingAFlaggedAnswerAsksNobodyAgain() = runTest(UnconfinedTestDispatcher()) {
        val budget = WorkspaceEntry.PreferenceSet(alex, budget = 30)
        val scenario = Scenario(
            "reflag", actors,
            listOf(
                Step.StartAgent("r1", host = remote),
                Step.Edit(InputKey("closeV1"), sam, WorkspaceEntry.Report.Closed(sam, v1)),
                Step.ReleaseAgent("r1"),
                Step.Edit(InputKey("budget30"), alex, budget),
            ),
        )
        val r = kuilt().run(scenario)
        assertAll(
            // The rig: the late budget is relevant to v1, so a re-judge does read it as missing.
            { assertTrue(Relevance.isRelevant(budget, Recommendation(v1))) },
            { assertTrue(r.finalViews.values.all { budget in it }) },
            { assertEquals(3, r.presentations.size) },
            { assertTrue(r.presentations.none { it.shownAsApplicable }) },
            { assertEquals(2, r.humanPrompts) },
        )
    }

    /**
     * Order within a step (rule 5). At `Reconnect(sam)` Sam's copy receives Alex's budget and r2 in
     * one step. r2 is presented first and supersedes r1, so the budget never re-flags r1 for Sam.
     * Alex, connected when he lowers the budget, is asked about r1 at once: his own news makes his
     * standing answer over budget. Prompts: Alex's re-check plus the accept conflict. Control:
     * re-judge before presenting new proposals, and Sam gets a flagged r1 row and a third prompt.
     */
    @Test
    fun s4NewAnswerSupersedesBeforeTheOldOneIsReJudged() = runTest(UnconfinedTestDispatcher()) {
        val r = kuilt().run(Scenarios.s4)
        val m = Oracle.score(Scenarios.s4, r)
        assertAll(
            { assertEquals(listOf("r1" to true, "r2" to true), r.rows(sam).map { it.request to it.shownAsApplicable }) },
            { assertEquals(listOf("r1" to true, "r1" to false, "r2" to true), r.rows(alex).map { it.request to it.shownAsApplicable }) },
            { assertEquals(2, m.humanPrompts) },
            { assertEquals(0, m.staleAtEnd) },
            { assertEquals(0, m.falseInvalidations) },
        )
    }
}
