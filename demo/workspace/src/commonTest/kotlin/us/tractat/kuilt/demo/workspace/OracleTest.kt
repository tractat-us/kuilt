package us.tractat.kuilt.demo.workspace

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OracleTest {
    private val a = ActorId("alex")
    private val closeV1 = InputKey("closeV1")
    private val s1 = Scenarios.all.first { it.name == "S1" }
    private val s3 = Scenarios.all.first { it.name == "S3" }

    /**
     * A presentation needs a request the scenario releases (the standing-answer order depends on it),
     * so the note cases use S7's note on v1, which never changes the pick, rather than S1, which runs
     * no agent.
     */
    private val s7 = Scenarios.all.first { it.name == "S7" }

    /** Every actor's final view holds every input the scenario created. */
    private fun fullViews(s: Scenario): Map<ActorId, List<WorkspaceEntry>> =
        s.actors.associateWith { s.inputs.values.toList() }

    private fun result(
        s: Scenario,
        vararg p: Presentation,
        finalViews: Map<ActorId, List<WorkspaceEntry>> = fullViews(s),
    ) = RunResult(
        presentations = p.toList(), finalViews = finalViews, agentRuns = 1, reruns = emptyList(),
        humanPrompts = 0, outageActions = 0, outageActionsServed = 0,
    )

    @Test
    fun shownApplicableWhileKnowingRelevantMissingReportIsStale() {
        val p = Presentation(a, "r1", Recommendation(VenueId("v1")), basis = emptySet(), known = setOf(closeV1), shownAsApplicable = true)
        assertEquals(1, Oracle.score(s3, result(s3, p)).staleTreatedAsCurrent)
    }

    @Test
    fun flaggedForReviewIsNotStale() {
        val p = Presentation(a, "r1", Recommendation(VenueId("v1")), basis = emptySet(), known = setOf(closeV1), shownAsApplicable = false)
        assertEquals(0, Oracle.score(s3, result(s3, p)).staleTreatedAsCurrent)
    }

    @Test
    fun reopeningAnotherVenueMakesAnApplicableProposalStale() {
        // S5: basis saw v1 closed and chose v2; the presenter knows v1 reopened. Truth reruns to v1.
        val s5 = Scenarios.all.first { it.name == "S5" }
        val p = Presentation(a, "r1", Recommendation(VenueId("v2")), basis = setOf(closeV1), known = setOf(closeV1, InputKey("reopenV1")), shownAsApplicable = true)
        assertEquals(1, Oracle.score(s5, result(s5, p)).staleTreatedAsCurrent)
    }

    @Test
    fun flaggingAProposalTruthKeepsIsAFalseInvalidation() {
        val p = Presentation(a, "r1", Recommendation(VenueId("v1")), basis = emptySet(), known = setOf(InputKey("noteV1")), shownAsApplicable = false)
        assertEquals(1, Oracle.score(s7, result(s7, p)).falseInvalidations)
    }

    @Test
    fun irrelevantMissingInputIsNotStale() {
        val note = InputKey("noteV1")
        val p = Presentation(a, "r1", Recommendation(VenueId("v1")), basis = emptySet(), known = setOf(note), shownAsApplicable = true)
        assertEquals(0, Oracle.score(s7, result(s7, p)).staleTreatedAsCurrent)
    }

    @Test
    fun editMissingFromOneActorsViewIsNotPreserved() {
        // Sam's view lacks Alex's note; every other view has both edits.
        val sam = ActorId("sam")
        val views = fullViews(s1) + (sam to listOf<WorkspaceEntry>(WorkspaceEntry.Report.Full(sam, VenueId("v3"))))
        val metrics = Oracle.score(s1, result(s1, finalViews = views))
        assertAll(
            { assertEquals(2, metrics.editsMade) },
            { assertEquals(1, metrics.editsPreserved) },
        )
    }

    @Test
    fun missingActorViewThrows() {
        val views = fullViews(s1) - ActorId("remote")
        assertFailsWith<IllegalArgumentException> { Oracle.score(s1, result(s1, finalViews = views)) }
    }

    @Test
    fun presentationNamingAnUnknownInputThrows() {
        val p = Presentation(a, "r1", Recommendation(VenueId("v1")), basis = emptySet(), known = setOf(InputKey("nope")), shownAsApplicable = true)
        assertFailsWith<IllegalArgumentException> { Oracle.score(s3, result(s3, p)) }
    }

    /**
     * H1b scores needs-review flags only (HYPOTHESES.md, amended 2026-10-01). An Unknown verdict is
     * network lag, not a relevance call, so a flagged Unknown whose rerun agrees is reported in
     * `unknownPresentations` and is **not** a false invalidation. The needs-review twin beside it is
     * the control: the same presentation without `unknown` still counts.
     */
    @Test
    fun unknownPresentationIsReportedButIsNotAFalseInvalidation() {
        fun flagged(unknown: Boolean) = Presentation(
            a, "r1", Recommendation(VenueId("v1")), basis = emptySet(), known = setOf(InputKey("noteV1")),
            shownAsApplicable = false, unknown = unknown,
        )
        val asUnknown = Oracle.score(s7, result(s7, flagged(unknown = true)))
        val asNeedsReview = Oracle.score(s7, result(s7, flagged(unknown = false)))
        assertAll(
            { assertEquals(1, asUnknown.unknownPresentations) },
            { assertEquals(0, asUnknown.falseInvalidations) },
            { assertEquals(0, asUnknown.staleTreatedAsCurrent) },
            { assertEquals(0, asNeedsReview.unknownPresentations) },
            { assertEquals(1, asNeedsReview.falseInvalidations) },
        )
    }
}
