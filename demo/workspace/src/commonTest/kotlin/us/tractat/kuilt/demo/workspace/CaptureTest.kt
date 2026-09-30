package us.tractat.kuilt.demo.workspace

import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.RgaId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CaptureTest {
    private val r = ReplicaId("remote")
    private val alex = ActorId("alex")

    private fun Rga<WorkspaceEntry>.append(e: WorkspaceEntry): Pair<Rga<WorkspaceEntry>, RgaId> {
        val (next, op) = insertAt(r, size, e)
        return next to op.id
    }

    @Test
    fun laterHostUpdatesNeverEnterTheBasis() {
        val (s0, budget) = Rga.empty<WorkspaceEntry>().append(WorkspaceEntry.PreferenceSet(alex, 50))
        val pending = InputCapture.capture(RequestId("r1"), s0)
        val (_, closure) = s0.append(WorkspaceEntry.Report.Closed(alex, VenueId("v1")))
        val proposal = pending.complete(ScriptedAgent.recommend(listOf(WorkspaceEntry.PreferenceSet(alex, 50))))
        assertAll(
            { assertEquals(setOf(budget.dot), proposal.basis.allDots) },
            { assertTrue(closure.dot !in proposal.basis.allDots) },
        )
    }

    @Test
    fun intermediateOutputKeepsItsEarlierBasis() {
        val (s0, budget) = Rga.empty<WorkspaceEntry>().append(WorkspaceEntry.PreferenceSet(alex, 50))
        val p0 = InputCapture.capture(RequestId("r1"), s0).intermediate(Recommendation(VenueId("v1")))
        val (s1, tool) = s0.append(WorkspaceEntry.Report.Full(alex, VenueId("v1")))
        val proposal = p0.withToolResult(s1, setOf(tool)).complete(Recommendation(VenueId("v2")))
        assertAll(
            { assertEquals(setOf(budget.dot), proposal.basis.basisThrough(0)) },
            { assertEquals(setOf(budget.dot, tool.dot), proposal.basis.allDots) },
            { assertEquals(listOf(0 to Recommendation(VenueId("v1"))), proposal.intermediate) },
        )
    }

    @Test
    fun excludedInputIsAbsentFromBasis() {
        val (s0, _) = Rga.empty<WorkspaceEntry>().append(WorkspaceEntry.Note(alex, VenueId("v2"), "quiet"))
        val (s1, budget) = s0.append(WorkspaceEntry.PreferenceSet(alex, 50))
        val p = InputCapture.capture(RequestId("r1"), s1, exclude = { it is WorkspaceEntry.Note }).complete(Recommendation(null))
        assertEquals(setOf(budget.dot), p.basis.allDots)
    }

    @Test
    fun wireRoundTripPreservesBasisExactly() {
        val (s0, _) = Rga.empty<WorkspaceEntry>().append(WorkspaceEntry.PreferenceSet(alex, 50))
        val p = InputCapture.capture(RequestId("r1"), s0).complete(Recommendation(VenueId("v2")))
        assertEquals(p.basis, p.toEntry(alex).toProposal().basis)
    }

    @Test fun honestPathKeepsTheBasis() = assertTrue(basisUnchangedByLaterUpdates { p, _ -> p.toEntry(alex) })

    @Test fun propertyDetectsTheLeakyStamp() = assertFalse(basisUnchangedByLaterUpdates(LeakyStamp::stamp))

    @Test
    fun toolResultIdMustBeInTheGivenLog() {
        val (s0, _) = Rga.empty<WorkspaceEntry>().append(WorkspaceEntry.PreferenceSet(alex, 50))
        val pending = InputCapture.capture(RequestId("r1"), s0)
        // The tool result lands in a log the capture was never shown.
        val (_, stray) = Rga.empty<WorkspaceEntry>().append(WorkspaceEntry.Report.Full(alex, VenueId("v1")))
        assertFailsWith<IllegalArgumentException> { pending.withToolResult(s0, setOf(stray)) }
    }

    @Test
    fun toolResultCannotReRecordACapturedDot() {
        val (s0, budget) = Rga.empty<WorkspaceEntry>().append(WorkspaceEntry.PreferenceSet(alex, 50))
        val pending = InputCapture.capture(RequestId("r1"), s0)
        assertFailsWith<IllegalArgumentException> { pending.withToolResult(s0, setOf(budget)) }
    }

    @Test
    fun toProposalRejectsADotRepeatedAcrossSteps() {
        val d = Dot(r, 1)
        val wire = WorkspaceEntry.AgentProposal(alex, RequestId("r1"), listOf(listOf(d), listOf(d)), Recommendation(null))
        assertFailsWith<IllegalArgumentException> { wire.toProposal() }
    }

    @Test
    fun toProposalRejectsADotRepeatedWithinAStep() {
        val d = Dot(r, 1)
        val wire = WorkspaceEntry.AgentProposal(alex, RequestId("r1"), listOf(listOf(d, d)), Recommendation(null))
        assertFailsWith<IllegalArgumentException> { wire.toProposal() }
    }

    @Test
    fun inputRecordStepsAreIndexedFromZeroInOrder() = assertAll(
        {
            assertFailsWith<IllegalArgumentException> {
                InputRecord(RequestId("r1"), listOf(StepBasis(1, setOf(Dot(r, 1)))))
            }
        },
        {
            assertFailsWith<IllegalArgumentException> {
                InputRecord(RequestId("r1"), listOf(StepBasis(0, setOf(Dot(r, 1))), StepBasis(0, setOf(Dot(r, 2)))))
            }
        },
    )

    @Test
    fun basisThroughAccumulatesSteps() {
        val record = InputRecord(
            RequestId("r1"),
            listOf(StepBasis(0, setOf(Dot(r, 1))), StepBasis(1, setOf(Dot(r, 2))), StepBasis(2, emptySet())),
        )
        assertAll(
            { assertEquals(setOf(Dot(r, 1)), record.basisThrough(0)) },
            { assertEquals(setOf(Dot(r, 1), Dot(r, 2)), record.basisThrough(1)) },
            { assertEquals(record.allDots, record.basisThrough(2)) },
        )
    }
}
