package us.tractat.kuilt.demo.workspace

import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.RgaId
import us.tractat.kuilt.crdt.RgaOp
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AssessmentTest {
    private val host = ReplicaId("remote")
    private val phone = ReplicaId("sam")
    private val sam = ActorId("sam")
    private val v1 = VenueId("v1")

    private fun Rga<WorkspaceEntry>.append(by: ReplicaId, e: WorkspaceEntry): Pair<Rga<WorkspaceEntry>, RgaId> {
        val (next, op) = insertAt(by, size, e)
        return next to op.id
    }

    /**
     * Review Focus 1: the agent saw a report this replica never received. The replica cannot judge
     * a basis it does not hold, so the verdict is Unknown, never Applicable — even though nothing
     * this replica holds is missing from the basis.
     */
    @Test
    fun unreceivedBasisIsUnknownNotApplicable() {
        val (known, _) = Rga.empty<WorkspaceEntry>().append(host, WorkspaceEntry.PreferenceSet(sam, 50))
        val (hostLog, unseen) = known.append(host, WorkspaceEntry.Report.Full(sam, VenueId("v3")))
        val p = InputCapture.capture(RequestId("r1"), hostLog).complete(Recommendation(v1))
        assertEquals(Verdict.Unknown(setOf(unseen.dot)), Assessment.assess(p, known))
    }

    @Test
    fun relevantMissingClosureNeedsReview() {
        val p = InputCapture.capture(RequestId("r1"), Rga.empty()).complete(Recommendation(v1))
        val (known, closure) = Rga.empty<WorkspaceEntry>().append(phone, WorkspaceEntry.Report.Closed(sam, v1))
        assertEquals(Verdict.NeedsReview(setOf(closure.dot)), Assessment.assess(p, known))
    }

    @Test
    fun irrelevantMissingNoteIsApplicable() {
        val p = InputCapture.capture(RequestId("r1"), Rga.empty()).complete(Recommendation(v1))
        val (known, _) = Rga.empty<WorkspaceEntry>().append(phone, WorkspaceEntry.Note(sam, v1, "Book the window table"))
        assertEquals(Verdict.Applicable, Assessment.assess(p, known))
    }

    /**
     * Review Focus 5: the host had the closure when the agent started, but the agent's selection
     * left it out. It is not in the basis, so judged against the host's own log it is missing.
     */
    @Test
    fun excludedHostInputCountsAsMissing() {
        val (hostLog, closure) = Rga.empty<WorkspaceEntry>().append(host, WorkspaceEntry.Report.Closed(sam, v1))
        val p = InputCapture.capture(RequestId("r1"), hostLog) { it is WorkspaceEntry.Report }.complete(Recommendation(v1))
        assertEquals(Verdict.NeedsReview(setOf(closure.dot)), Assessment.assess(p, hostLog))
    }

    /** Review Focus 2, uncompacted half: a basis entry removed later is still delivered, and nothing throws. */
    @Test
    fun removedBasisEntryIsStillDelivered() {
        val (hostLog, budget) = Rga.empty<WorkspaceEntry>().append(host, WorkspaceEntry.PreferenceSet(sam, 50))
        val p = InputCapture.capture(RequestId("r1"), hostLog).complete(Recommendation(v1))
        val removed = hostLog.apply(RgaOp.Remove(budget))
        assertEquals(Verdict.Applicable, Assessment.assess(p, removed))
    }

    /**
     * The floor half of the delivery rule. `dropWindow` folds the author's own dropped dot into
     * `causalFloor()` and takes it out of `causalDots()`, so only the floor still says it was
     * delivered. No backend run reaches this state: the compaction coordinator records a `Compact`
     * instead, which keeps the dot in `causalDots()`.
     */
    @Test
    fun flooredBasisEntryIsStillDelivered() {
        val (hostLog, budget) = Rga.empty<WorkspaceEntry>().append(host, WorkspaceEntry.PreferenceSet(sam, 50))
        val p = InputCapture.capture(RequestId("r1"), hostLog).complete(Recommendation(v1))
        val floored = checkNotNull(hostLog.dropWindow(host, setOf(budget))).first
        assertAll(
            { assertTrue(floored.causalFloor().contains(budget.dot), "the floor carries the dot") },
            { assertFalse(budget.dot in floored.causalDots(), "the dot left causalDots") },
            { assertEquals(Verdict.Applicable, Assessment.assess(p, floored)) },
        )
    }

    /** Another agent's proposal, and a person's accept, are outputs and choices, never missing inputs. */
    @Test
    fun proposalsAndAcceptsAreNeverMissingInputs() {
        val p = InputCapture.capture(RequestId("r2"), Rga.empty()).complete(Recommendation(v1))
        val other = WorkspaceEntry.AgentProposal(ActorId("remote"), RequestId("r1"), listOf(emptyList()), Recommendation(VenueId("v2")))
        val (withProposal, _) = Rga.empty<WorkspaceEntry>().append(host, other)
        val (known, _) = withProposal.append(phone, WorkspaceEntry.Accept(sam, RequestId("r1")))
        assertEquals(Verdict.Applicable, Assessment.assess(p, known))
    }
}
