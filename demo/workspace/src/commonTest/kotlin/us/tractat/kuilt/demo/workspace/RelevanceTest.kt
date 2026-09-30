package us.tractat.kuilt.demo.workspace

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** One test per clause of the relevance policy (HYPOTHESES.md, "Relevance policy"). */
class RelevanceTest {
    private val sam = ActorId("sam")
    private val v1 = VenueId("v1")
    private val v2 = VenueId("v2")
    private val v3 = VenueId("v3")

    @Test
    fun clauseA_reportAboutTheRecommendedVenueIsRelevant() = assertAll(
        { assertTrue(Relevance.isRelevant(WorkspaceEntry.Report.Closed(sam, v1), Recommendation(v1))) },
        { assertTrue(Relevance.isRelevant(WorkspaceEntry.Report.Full(sam, v1), Recommendation(v1))) },
        { assertFalse(Relevance.isRelevant(WorkspaceEntry.Report.Full(sam, v3), Recommendation(v1))) },
    )

    @Test
    fun clauseB_reopeningOfAnyVenueIsRelevant() = assertAll(
        { assertTrue(Relevance.isRelevant(WorkspaceEntry.Report.Reopened(sam, v1), Recommendation(v2))) },
        { assertTrue(Relevance.isRelevant(WorkspaceEntry.Report.Reopened(sam, v3), Recommendation(v2))) },
    )

    @Test
    fun clauseC_budgetBelowTheRecommendedPriceIsRelevant() = assertAll(
        // v2 costs 25. The only nearer venue, v1 at 40, does not fit 20, so only clause (c) can fire.
        { assertTrue(Relevance.isRelevant(WorkspaceEntry.PreferenceSet(sam, budget = 20), Recommendation(v2))) },
        // 30 covers v2 and still does not reach v1: neither (c) nor (d).
        { assertFalse(Relevance.isRelevant(WorkspaceEntry.PreferenceSet(sam, budget = 30), Recommendation(v2))) },
    )

    @Test
    fun clauseD_budgetThatMakesANearerVenueAffordableIsRelevant() = assertAll(
        // 45 is not below v2's 25, so (c) is silent; it does make v1 (40, nearer) affordable.
        { assertTrue(Relevance.isRelevant(WorkspaceEntry.PreferenceSet(sam, budget = 45), Recommendation(v2))) },
        // v1 is the nearest venue: nothing nearer exists to become affordable.
        { assertFalse(Relevance.isRelevant(WorkspaceEntry.PreferenceSet(sam, budget = 100), Recommendation(v1))) },
    )

    @Test
    fun noteIsNeverRelevant() = assertAll(
        { assertFalse(Relevance.isRelevant(WorkspaceEntry.Note(sam, v1, "Quiet back room"), Recommendation(v1))) },
        { assertFalse(Relevance.isRelevant(WorkspaceEntry.Note(sam, v2, "Quiet back room"), Recommendation(null))) },
    )

    @Test
    fun agentProposalIsNeverRelevant() {
        val proposal = WorkspaceEntry.AgentProposal(sam, RequestId("r1"), listOf(emptyList()), Recommendation(v2))
        assertAll(
            { assertFalse(Relevance.isRelevant(proposal, Recommendation(v1))) },
            { assertFalse(Relevance.isRelevant(proposal, Recommendation(null))) },
        )
    }
}
