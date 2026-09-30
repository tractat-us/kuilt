package us.tractat.kuilt.demo.workspace

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

class DomainTest {
    private val a = ActorId("alex")

    @Test
    fun agentPicksNearestOpenVenueWithinBudget() {
        val inputs = listOf(
            WorkspaceEntry.PreferenceSet(a, budget = 35),
            WorkspaceEntry.Report.Closed(a, VenueId("v2")),
        )
        assertEquals(Recommendation(VenueId("v3")), ScriptedAgent.recommend(inputs))
    }

    @Test
    fun reopenedReportCancelsEarlierClosure() {
        val inputs = listOf(
            WorkspaceEntry.Report.Closed(a, VenueId("v1")),
            WorkspaceEntry.Report.Reopened(a, VenueId("v1")),
        )
        assertEquals(Recommendation(VenueId("v1")), ScriptedAgent.recommend(inputs))
    }

    @Test
    fun noVenueFitsYieldsNone() = assertAll(
        { assertEquals(Recommendation(null), ScriptedAgent.recommend(listOf(WorkspaceEntry.PreferenceSet(a, budget = 5)))) },
        { assertEquals(Recommendation(VenueId("v1")), ScriptedAgent.recommend(emptyList())) },
    )
}
