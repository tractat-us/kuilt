package us.tractat.kuilt.demo.workspace

import kotlin.test.Test
import kotlin.test.assertEquals

class OracleTest {
    private val a = ActorId("alex")
    private val closeV1 = InputKey("closeV1")
    private val s3 = Scenarios.all.first { it.name == "S3" }

    private fun result(p: Presentation) = RunResult(
        presentations = listOf(p), finalViews = emptyMap(), agentRuns = 1, reruns = emptyList(),
        humanPrompts = 0, outageActions = 0, outageActionsServed = 0,
    )

    @Test
    fun shownApplicableWhileKnowingRelevantMissingReportIsStale() {
        val p = Presentation(a, "r1", Recommendation(VenueId("v1")), basis = emptySet(), known = setOf(closeV1), shownAsApplicable = true)
        assertEquals(1, Oracle.score(s3, result(p)).staleTreatedAsCurrent)
    }

    @Test
    fun flaggedForReviewIsNotStale() {
        val p = Presentation(a, "r1", Recommendation(VenueId("v1")), basis = emptySet(), known = setOf(closeV1), shownAsApplicable = false)
        assertEquals(0, Oracle.score(s3, result(p)).staleTreatedAsCurrent)
    }

    @Test
    fun reopeningAnotherVenueMakesAnApplicableProposalStale() {
        // S5: basis saw v1 closed and chose v2; the presenter knows v1 reopened. Truth reruns to v1.
        val s5 = Scenarios.all.first { it.name == "S5" }
        val p = Presentation(a, "r1", Recommendation(VenueId("v2")), basis = setOf(closeV1), known = setOf(closeV1, InputKey("reopenV1")), shownAsApplicable = true)
        assertEquals(1, Oracle.score(s5, result(p)).staleTreatedAsCurrent)
    }

    @Test
    fun flaggingAProposalTruthKeepsIsAFalseInvalidation() {
        val s1 = Scenarios.all.first { it.name == "S1" }
        val p = Presentation(a, "r1", Recommendation(VenueId("v1")), basis = emptySet(), known = setOf(InputKey("noteV2")), shownAsApplicable = false)
        assertEquals(1, Oracle.score(s1, result(p)).falseInvalidations)
    }

    @Test
    fun irrelevantMissingInputIsNotStale() {
        val note = InputKey("noteV2")
        val s1 = Scenarios.all.first { it.name == "S1" }
        val p = Presentation(a, "r1", Recommendation(VenueId("v1")), basis = emptySet(), known = setOf(note), shownAsApplicable = true)
        assertEquals(0, Oracle.score(s1, result(p)).staleTreatedAsCurrent)
    }
}
