@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.demo.workspace

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.drainAntiEntropy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KuiltBackendTest {
    private val alex = ActorId("alex")
    private val sam = ActorId("sam")
    private val remote = ActorId("remote")
    private val v1 = VenueId("v1")
    private val v2 = VenueId("v2")

    /**
     * Each step is followed by bounded anti-entropy rounds, not `advanceUntilIdle`: the replicas'
     * timers live on `backgroundScope`, which `advanceUntilIdle` does not wait for, and a replica
     * that reconnects with nothing new to send converges only through anti-entropy.
     */
    private fun TestScope.backend(observer: KuiltRunObserver = KuiltRunObserver.None) = KuiltBackend(
        scope = backgroundScope,
        advance = { drainAntiEntropy(KuiltBackend.antiEntropyInterval, rounds = 10) },
        observer = observer,
    )

    /** Records the host's log at each StartAgent, independently of the backend's own capture, and every released proposal. */
    private class Recorder(private val scenario: Scenario) : KuiltRunObserver {
        val atStart = mutableMapOf<String, Set<Dot>>()
        val released = mutableListOf<Proposal>()

        override fun beforeStep(step: Step, replicas: Map<ActorId, Rga<WorkspaceEntry>>) {
            if (step !is Step.StartAgent) return
            val excluded = step.selectExcluding.map { scenario.inputs.getValue(it) }.toSet()
            atStart[step.request] = replicas.getValue(step.host).entries()
                .filterNot { (_, entry) -> entry in excluded }
                .mapTo(mutableSetOf()) { it.first.dot }
        }

        override fun released(proposal: Proposal) {
            released += proposal
        }
    }

    @Test
    fun s3ClosureDuringInferenceIsFlaggedNotStale() = runTest(UnconfinedTestDispatcher()) {
        val r = backend().run(Scenarios.s3)
        val m = Oracle.score(Scenarios.s3, r)
        assertAll(
            { assertEquals(0, m.staleTreatedAsCurrent) },
            // Alex and Sam are each asked; remote is shown the flag too, but hosts the agent and is no person.
            { assertEquals(2, m.humanPrompts) },
            { assertEquals(setOf(alex, sam, remote), r.presentations.map { it.actor }.toSet()) },
            { assertTrue(r.presentations.none { it.shownAsApplicable }) },
            { assertEquals(0, m.falseInvalidations) },
            { assertEquals(1, m.agentRuns) },
        )
    }

    @Test
    fun s2OfflineBudgetEditIsServedLocally() = runTest(UnconfinedTestDispatcher()) {
        val r = backend().run(Scenarios.s2)
        val m = Oracle.score(Scenarios.s2, r)
        val toAlex = r.presentations.filter { it.actor == alex }
        assertAll(
            { assertEquals(1, m.outageActions) },
            { assertEquals(m.outageActions, m.outageActionsServed) },
            { assertEquals(1, m.editsPreserved) },
            // Alex sees r1 only after reconnecting, holding his own budget the agent never received.
            { assertEquals(1, toAlex.size) },
            { assertTrue(toAlex.none { it.shownAsApplicable }) },
            { assertEquals(0, m.staleTreatedAsCurrent) },
        )
    }

    @Test
    fun s1IndependentEditsMergeWithoutPrompt() = runTest(UnconfinedTestDispatcher()) {
        val m = Oracle.score(Scenarios.s1, backend().run(Scenarios.s1))
        assertAll(
            { assertEquals(2, m.editsMade) },
            { assertEquals(2, m.editsPreserved) },
            { assertEquals(0, m.humanPrompts) },
            { assertEquals(0, m.agentRuns) },
        )
    }

    /** A harmless note is irrelevant, so the proposal is shown as applicable and nothing reruns. */
    @Test
    fun s7NoteLeavesTheProposalApplicable() = runTest(UnconfinedTestDispatcher()) {
        val r = backend().run(Scenarios.s7)
        val m = Oracle.score(Scenarios.s7, r)
        assertAll(
            { assertEquals(3, r.presentations.size) },
            { assertTrue(r.presentations.all { it.shownAsApplicable }) },
            { assertEquals(0, m.humanPrompts) },
            { assertEquals(0, m.unnecessaryReruns) },
        )
    }

    /**
     * S4's two offline accepts are real entries, each served on its chooser's phone. When the phones
     * meet, each holds both accepts, for different requests by different people: one conflict, one
     * prompt, counted once across the run. A backend that never noticed would score 0 and pass H4
     * vacuously.
     */
    @Test
    fun s4AcceptConflictIsSurfacedOnce() = runTest(UnconfinedTestDispatcher()) {
        val m = Oracle.score(Scenarios.s4, backend().run(Scenarios.s4))
        assertAll(
            { assertEquals(1, m.humanPrompts) },
            { assertEquals(2, m.outageActions) },
            { assertEquals(2, m.outageActionsServed) },
            { assertEquals(0, m.staleTreatedAsCurrent) },
        )
    }

    /** The control for the conflict rule: two people accepting the same request agree, and nobody is asked. */
    @Test
    fun agreeingAcceptsAreNoConflict() = runTest(UnconfinedTestDispatcher()) {
        val agreeing = Scenario(
            "agree", listOf(alex, sam, remote),
            listOf(
                Step.StartAgent("r1", host = remote),
                Step.ReleaseAgent("r1"),
                Step.Partition(alex),
                Step.Partition(sam),
                Step.Accept(alex, "r1"),
                Step.Accept(sam, "r1"),
                Step.Reconnect(alex),
                Step.Reconnect(sam),
            ),
        )
        val r = backend().run(agreeing)
        assertAll(
            { assertEquals(0, r.humanPrompts) },
            { assertEquals(2, r.outageActionsServed) },
        )
    }

    /**
     * Review Focus 4: a reply for a request that never started, or that already returned, is not
     * turned into a proposal. Nothing is appended, so no replica ever presents it.
     */
    @Test
    fun unknownRequestReplyIsRejected() = runTest(UnconfinedTestDispatcher()) {
        val scenario = Scenario(
            "reject", listOf(alex, sam, remote),
            listOf(
                Step.ReleaseAgent("ghost"),
                Step.StartAgent("r1", host = remote),
                Step.ReleaseAgent("r1"),
                Step.ReleaseAgent("r1"),
            ),
        )
        val recorder = Recorder(scenario)
        val r = backend(recorder).run(scenario)
        assertAll(
            { assertEquals(2, r.rejectedReplies) },
            { assertEquals(listOf(RequestId("r1")), recorder.released.map { it.request }) },
            { assertTrue(r.presentations.none { it.request == "ghost" }) },
            { assertEquals(3, r.presentations.count { it.request == "r1" }) },
        )
    }

    /**
     * Capture timing is the backend's obligation, and the proposal type cannot enforce it: the basis
     * is fixed once captured, but nothing stops a backend capturing late. Each scenario here has an
     * edit reaching the host between StartAgent and ReleaseAgent and no tool result, so the whole
     * basis must be exactly the host's log at StartAgent, minus the agent's exclusions.
     */
    @Test
    fun capturedBasisIsTheHostStateAtStart() = runTest(UnconfinedTestDispatcher()) {
        val excluding = Scenario(
            "excluding", listOf(alex, sam, remote),
            listOf(
                Step.Edit(InputKey("closeV1"), sam, WorkspaceEntry.Report.Closed(sam, v1)),
                Step.Edit(InputKey("noteV2"), alex, WorkspaceEntry.Note(alex, v2, "Quiet back room")),
                Step.StartAgent("r1", host = remote, selectExcluding = setOf(InputKey("noteV2"))),
                Step.Edit(InputKey("budget30"), alex, WorkspaceEntry.PreferenceSet(alex, budget = 30)),
                Step.ReleaseAgent("r1"),
            ),
        )
        for (scenario in listOf(Scenarios.s3, Scenarios.s5, excluding)) {
            val recorder = Recorder(scenario)
            backend(recorder).run(scenario)
            val proposal = recorder.released.single()
            val expected = recorder.atStart.getValue("r1")
            assertAll(
                { assertEquals(expected, proposal.basis.basisThrough(0), "${scenario.name}: step-0 basis") },
                { assertEquals(expected, proposal.basis.allDots, "${scenario.name}: whole basis") },
            )
        }
    }

    /** A tool result is an input with a dot of its own: it joins the basis as step 1, and only there. */
    @Test
    fun toolResultJoinsTheBasisAsItsOwnStep() = runTest(UnconfinedTestDispatcher()) {
        val scenario = Scenario(
            "tool", listOf(alex, sam, remote),
            listOf(
                Step.StartAgent("r1", host = remote),
                Step.ToolResult("r1", InputKey("closeV1"), WorkspaceEntry.Report.Closed(remote, v1)),
                Step.ReleaseAgent("r1"),
            ),
        )
        val recorder = Recorder(scenario)
        val r = backend(recorder).run(scenario)
        val proposal = recorder.released.single()
        assertAll(
            { assertEquals(emptySet(), proposal.basis.basisThrough(0)) },
            { assertEquals(1, proposal.basis.allDots.size) },
            // The agent received the closure, so it picks v2, and every presentation names the tool result.
            { assertEquals(Recommendation(v2), proposal.recommendation) },
            { assertTrue(r.presentations.all { it.basis == setOf(InputKey("closeV1")) && it.shownAsApplicable }) },
        )
    }
}
