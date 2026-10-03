@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.demo.workspace

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.RgaId
import us.tractat.kuilt.crdt.RgaOp
import us.tractat.kuilt.quilter.QuiltMessage
import us.tractat.kuilt.quilter.Quilter
import us.tractat.kuilt.test.FaultProfile
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.drainAntiEntropy
import us.tractat.kuilt.test.drainAntiEntropyUntil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Adversarial traces against the kuilt design. Each one checks the same promise under a different
 * attack: **a late agent result keeps its original input history.** Every test also asserts that its
 * rig fired, since a fault that never reached the system under test passes by absence.
 *
 * Two traces need something [KuiltBackend]'s scenario vocabulary does not have: removing an entry
 * (compaction) and choosing how a result is stamped (the leaky stamp). Those run on a [Mesh] whose
 * replicas come from [wireReplica], the factory the backend itself uses.
 */
class AdversarialTraceTest {
    private val alex = ActorId("alex")
    private val sam = ActorId("sam")
    private val remote = ActorId("remote")
    private val actors = listOf(alex, sam, remote)
    private val v1 = VenueId("v1")
    private val v2 = VenueId("v2")
    private val v4 = VenueId("v4")

    // ── Review Focus 3: reorder and duplicates ────────────────────────────────────────────────

    /**
     * Remote's link reorders frames in both directions, and a test decorator above it sends every
     * frame remote's replicator emits twice, so the proposal's delta goes out twice and the copies
     * are shuffled with everything else. The scenario is S5 with a trailing note, which gives the
     * observer a settled view of every replica after the release.
     *
     * The proposal must appear exactly once on every replica, and its wire basis must equal the
     * host's inputs at StartAgent. Quilter drops a duplicate delta by sequence number before it
     * reaches the lattice, so the test also applies the decoded proposal delta a second time to a
     * real replica state, which is the duplicate apply the lattice itself has to absorb.
     *
     * **Unknown stays unreachable here, and not because of `Rga`.** An `Rga` shows an insert whose
     * neighbours it has not received: the last block pieces the wire delta into an empty log and gets
     * the proposal without its basis, which [Assessment] judges Unknown. The backend never presents
     * that state because it checks, after every step and before presenting, that every connected
     * replica has delivered the same dots; a replica that holds the proposal therefore holds every
     * dot the host held when it released it. Reordering cannot open the gap either: Quilter applies
     * each sender's deltas in that sender's order, and the gap needs one sender's frames held back
     * while another's get through. So every presentation below has `unknown == false`, and
     * [heldFramesLeaveTheProposalUnknown] is where an Unknown is reached end to end.
     */
    @Test
    fun reorderedAndDuplicatedProposalKeepsBasis() = runTest(UnconfinedTestDispatcher()) {
        val scenario = Scenario(
            "reorder", actors,
            listOf(
                Step.Edit(InputKey("closeV1"), sam, WorkspaceEntry.Report.Closed(sam, v1)),
                Step.StartAgent("r1", host = remote),
                Step.Edit(InputKey("reopenV1"), sam, WorkspaceEntry.Report.Reopened(sam, v1)),
                Step.ReleaseAgent("r1"),
                Step.Edit(InputKey("noteV2"), alex, WorkspaceEntry.Note(alex, v2, "Quiet back room")),
            ),
        )
        val recorder = StepRecorder()
        lateinit var net: TracingNetwork
        val backend = KuiltBackend(
            scope = backgroundScope,
            advance = { drainAntiEntropy(KuiltBackend.antiEntropyInterval, rounds = 10) },
            network = { scope ->
                val reorder = { actor: ActorId -> if (actor == remote) FaultProfile.ReorderWindow(windowSize = 3, seed = 7) else FaultProfile.Healthy }
                TracingNetwork(FaultyNetwork(scope, reorder), duplicateFrom = remote).also { net = it }
            },
            observer = recorder,
        )
        val r = backend.run(scenario)

        val captured = recorder.released.single()
        val hostAtStart = recorder.inputDotsAt(Step.StartAgent("r1", host = remote), remote)
        val settled = recorder.logsBefore.getValue(scenario.steps.last())

        // Rig 1, reorder: the frames alex received from remote are not in the order remote sent them.
        // Compared against everything remote sent, whatever its address: alex's arrivals are a subset
        // of that stream, so without reordering they would still be a subsequence of remote's send
        // order. (FaultySeam.framesDelayed would also witness the window, but this harness keeps its
        // seams private.)
        val remotePeer = net.seam(remote).selfId
        val sentByRemote = net.seam(remote).sent.map { it.bytes }
        val arrivedAtAlex = net.seam(alex).received.filter { it.sender == remotePeer }.map { it.bytes }
        // Rig 2, duplicates: remote sent a proposal-carrying delta twice, and alex received both copies.
        val duplicatedProposalDeltas = net.seam(remote).duplicated.count { decode(it.bytes).isProposalDelta() }
        val proposalDeltasAtAlex = arrivedAtAlex.count { decode(it).isProposalDelta() }
        val proposalDelta = sentByRemote.mapNotNull { decode(it) as? QuiltMessage.Delta<Rga<WorkspaceEntry>> }
            .first { it.delta.entries().any { (_, e) -> e is WorkspaceEntry.AgentProposal } }.delta

        val alexLog = settled.getValue(alex)
        val appliedTwice = alexLog.piece(proposalDelta)
        val withoutBasis = Rga.empty<WorkspaceEntry>().piece(proposalDelta)

        assertAll(
            { assertTrue(arrivedAtAlex.isNotEmpty(), "alex received frames from remote") },
            { assertFalse(arrivedAtAlex.isSubsequenceOf(sentByRemote), "remote's frames reached alex reordered") },
            { assertTrue(duplicatedProposalDeltas > 0, "remote sent the proposal's delta twice") },
            { assertTrue(proposalDeltasAtAlex >= 2, "alex received the proposal's delta $proposalDeltasAtAlex times") },
            // The promise.
            { assertEquals(hostAtStart, captured.basis.allDots, "the captured basis is the host's inputs at StartAgent") },
            {
                for (actor in actors) {
                    val proposals = settled.getValue(actor).entries().map { it.second }.filterIsInstance<WorkspaceEntry.AgentProposal>()
                    assertEquals(1, proposals.size, "${actor.value} holds the proposal once")
                    assertEquals(captured.basis, proposals.single().toProposal().basis, "${actor.value}'s wire basis")
                }
            },
            { assertEquals(3, r.presentations.size, "one presentation per actor") },
            { assertTrue(r.presentations.none { it.unknown }, "no presentation is Unknown") },
            // The duplicate apply on the lattice: the same patch again changes nothing.
            { assertEquals(alexLog, appliedTwice, "a second apply of the proposal patch is a no-op") },
            // What the convergence check keeps out: Rga alone would show the proposal without its basis.
            { assertEquals(1, withoutBasis.entries().size) },
            { assertEquals(Verdict.Unknown(captured.basis.allDots), Assessment.assess(captured, withoutBasis)) },
        )
    }

    // ── Review Focus 2: compaction ────────────────────────────────────────────────────────────

    /**
     * Sam reports v1 closed, the agent captures it, and sam removes the report. The replicas'
     * compaction coordinators then purge the tombstone everywhere. Only after that does the late
     * result land on the host's log and replicate. Alex judges it against a log that holds no entry,
     * live or tombstoned, for the closure its basis names.
     *
     * Assessment must not throw, the basis must still name the closure's dot, and the verdict must
     * not be Unknown: the dot is still delivered. **Which half of the delivery rule carries it is
     * pinned too.** The coordinator's tombstone collection records an `RgaOp.Compact` naming the
     * purged id, and `Rga.causalDots()` keeps reporting a compacted id's dot; it never raises
     * `causalFloor()`. Only `Rga.dropWindow` raises the floor, and this wiring never calls it.
     */
    @Test
    fun compactedReportLeavesBasisReadable() = runTest(UnconfinedTestDispatcher()) {
        withMesh { mesh ->
            val closure = mesh.append(sam, WorkspaceEntry.Report.Closed(sam, v1))
            drain()
            val basisInputs = mesh.log(remote).entries().map { it.second }
            val captured = InputCapture.capture(RequestId("r1"), mesh.log(remote))
                .complete(ScriptedAgent.recommend(basisInputs))
            val heldLiveBefore = mesh.log(alex).entries().any { it.first == closure }

            mesh.remove(sam, closure)
            drainAntiEntropyUntil(KuiltBackend.antiEntropyInterval, maxRounds = 30, { "the closure was never purged" }) {
                actors.all { a -> mesh.log(a).let { it.tombstones.isEmpty() && it.operations().none { op -> op is RgaOp.Insert && op.id == closure } } }
            }
            mesh.append(remote, captured.toEntry(remote))
            drain()

            val alexLog = mesh.log(alex)
            val arrived = alexLog.entries().map { it.second }.filterIsInstance<WorkspaceEntry.AgentProposal>().single().toProposal()
            val verdict = Assessment.assess(arrived, alexLog)
            assertAll(
                // The rig: the closure was live, then purged everywhere, and a Compact records it.
                { assertTrue(heldLiveBefore, "alex held the closure live before it was removed") },
                { assertEquals(Recommendation(v2), captured.recommendation, "the agent saw v1 closed") },
                {
                    for (a in actors) {
                        val log = mesh.log(a)
                        assertTrue(log.tombstones.isEmpty(), "${a.value}: no tombstones")
                        assertTrue(log.operations().none { it is RgaOp.Insert && it.id == closure }, "${a.value}: the closure's insert is purged")
                        assertTrue(log.operations().any { it is RgaOp.Compact && closure in it.positions }, "${a.value}: a Compact names the closure")
                    }
                },
                { assertTrue(closure.dot in alexLog.causalDots(), "the closure's dot is still delivered, through the Compact record") },
                { assertFalse(alexLog.causalFloor().contains(closure.dot), "the floor did not move: nothing here raises it") },
                // The promise.
                { assertEquals(captured.basis, arrived.basis, "the late result's basis is what was captured") },
                { assertTrue(closure.dot in arrived.basis.allDots, "the basis still names the purged closure") },
                // Nothing live is missing from the basis, and the purged closure is delivered, so: Applicable.
                { assertEquals(Verdict.Applicable, verdict) },
            )
        }
    }

    // ── Review Focus 1: the agent saw more than the phone ─────────────────────────────────────

    /**
     * The one end-to-end Unknown. Sam's frames to alex are held back, so sam's closure of v1 reaches
     * the host but not alex. The host captures it, the agent finishes, and the proposal replicates
     * to alex straight away (deltas are eager; anti-entropy runs only on a timer, and no virtual time
     * passes). Alex now holds an answer built from a report alex never received, and must say so:
     * Unknown, naming the closure, never Applicable. Once the held frames go through, the verdict is
     * definite.
     *
     * [KuiltBackend] cannot reach this state: it cuts whole actors and checks convergence before
     * every presentation. So this runs on the [Mesh], and the backend's presentation rule is unchanged.
     */
    @Test
    fun heldFramesLeaveTheProposalUnknown() = runTest(UnconfinedTestDispatcher()) {
        lateinit var holder: HoldingSeam
        var alexPeer: PeerId? = null
        withMesh(decorate = { actor, seam ->
            when (actor) {
                alex -> seam.also { alexPeer = it.selfId }
                sam -> HoldingSeam(seam) { checkNotNull(alexPeer) { "alex is woven before sam" } }.also { holder = it }
                else -> seam
            }
        }) { mesh ->
            holder.holding = true
            val closure = mesh.append(sam, WorkspaceEntry.Report.Closed(sam, v1))
            val hostLog = mesh.log(remote)
            val captured = InputCapture.capture(RequestId("r1"), hostLog)
                .complete(ScriptedAgent.recommend(hostLog.entries().map { it.second }))
            mesh.append(remote, captured.toEntry(remote))

            val alexBefore = mesh.log(alex)
            val arrivedBefore = alexBefore.entries().map { it.second }.filterIsInstance<WorkspaceEntry.AgentProposal>().singleOrNull()
            val heldCount = holder.held.size
            val verdictBefore = arrivedBefore?.let { Assessment.assess(it.toProposal(), alexBefore) }

            holder.release()
            drain()
            val alexAfter = mesh.log(alex)
            val verdictAfter = Assessment.assess(captured, alexAfter)
            assertAll(
                // The rig: frames to alex were held, and the host did receive the closure.
                { assertTrue(heldCount > 0, "sam's frames to alex were held ($heldCount)") },
                { assertEquals(setOf(closure.dot), captured.basis.allDots, "the host captured sam's closure") },
                { assertFalse(alexBefore.delivers(closure.dot), "alex had not received the closure") },
                // The promise: alex holds the proposal, and cannot judge a basis it does not hold.
                { assertTrue(arrivedBefore != null, "alex holds the proposal") },
                { assertEquals(Verdict.Unknown(setOf(closure.dot)), verdictBefore) },
                // Once the frames go through, the verdict is definite: the closure is in the basis, so nothing is missing.
                { assertTrue(alexAfter.delivers(closure.dot), "alex received the closure after release") },
                { assertEquals(Verdict.Applicable, verdictAfter) },
            )
        }
    }

    /**
     * The same held-frames rig, driven through the backend's own [Presenter] instead of a hand call
     * to [Assessment.assess] (#2880). While Sam's frames are held, Alex is shown r1 unknown and is
     * asked. Once they land, the re-judge on the next settle resolves it: a new row shows r1 as
     * fitting, and nobody is asked again. Control: store the first verdict instead of deriving it,
     * and Alex's standing row stays unknown.
     */
    @Test
    fun heldFramesUnknownResolvesOnReJudge() = runTest(UnconfinedTestDispatcher()) {
        lateinit var holder: HoldingSeam
        var alexPeer: PeerId? = null
        withMesh(decorate = { actor, seam ->
            when (actor) {
                alex -> seam.also { alexPeer = it.selfId }
                sam -> HoldingSeam(seam) { checkNotNull(alexPeer) { "alex is woven before sam" } }.also { holder = it }
                else -> seam
            }
        }) { mesh ->
            holder.holding = true
            val closure = mesh.append(sam, WorkspaceEntry.Report.Closed(sam, v1))
            val hostLog = mesh.log(remote)
            val captured = InputCapture.capture(RequestId("r1"), hostLog)
                .complete(ScriptedAgent.recommend(hostLog.entries().map { it.second }))
            mesh.append(remote, captured.toEntry(remote))
            val closeV1 = InputKey("closeV1")
            val presenter = Presenter(
                isPerson = { it != remote },
                releaseRank = { 0 },
                basisKeys = { proposal -> if (closure.dot in proposal.basis.allDots) setOf(closeV1) else emptySet() },
                known = { _, log -> if (log.delivers(closure.dot)) setOf(closeV1) else emptySet() },
            )
            fun logs() = actors.associateWith { mesh.log(it) }

            presenter.settle(logs())
            val alexBefore = presenter.presentations.filter { it.actor == alex }
            val promptsBefore = presenter.humanPrompts
            holder.release()
            drain()
            presenter.settle(logs())
            val alexAfter = presenter.presentations.filter { it.actor == alex }
            assertAll(
                // The rig: frames to alex were held, so alex was first shown r1 without the closure.
                { assertTrue(holder.held.isNotEmpty(), "sam's frames to alex were held") },
                { assertEquals(listOf(true), alexBefore.map { it.unknown }, "alex was first shown r1 unknown") },
                // Alex is asked about the unknown. Sam and remote hold the closure the agent received, so r1 fits there.
                { assertEquals(1, promptsBefore) },
                { assertTrue(presenter.presentations.filter { it.actor != alex }.all { it.shownAsApplicable }) },
                // The fix: the re-judge resolves the unknown to fitting, and asks nobody.
                { assertEquals(listOf(true to false, false to true), alexAfter.map { it.unknown to it.shownAsApplicable }) },
                { assertEquals(setOf(closeV1), alexAfter.last().known) },
                { assertEquals(promptsBefore, presenter.humanPrompts) },
            )
        }
    }

    // ── Corrections and identities ────────────────────────────────────────────────────────────

    /**
     * A correction is a new report with its own dot, not an edit of the old one. S5: sam reports v1
     * closed, the agent captures that, then sam reports v1 reopened. The basis holds the closure's
     * dot; the reopening's dot is a different identity the agent never received, and it is relevant,
     * so every replica flags the proposal for review, naming exactly that dot.
     */
    @Test
    fun correctedReportIsANewIdentity() = runTest(UnconfinedTestDispatcher()) {
        val recorder = StepRecorder()
        val r = KuiltBackend(
            scope = backgroundScope,
            advance = { drainAntiEntropy(KuiltBackend.antiEntropyInterval, rounds = 10) },
            network = { scope -> FaultyNetwork(scope) },
            observer = recorder,
        ).run(Scenarios.s5)
        val captured = recorder.released.single()
        val beforeRelease = recorder.logsBefore.getValue(Scenarios.s5.steps.last())
        val samLog = beforeRelease.getValue(sam).entries()
        val closeDot = samLog.single { it.second is WorkspaceEntry.Report.Closed }.first.dot
        val reopenDot = samLog.single { it.second is WorkspaceEntry.Report.Reopened }.first.dot
        assertAll(
            { assertTrue(closeDot != reopenDot, "the correction has its own dot") },
            { assertEquals(setOf(closeDot), captured.basis.allDots) },
            {
                for (actor in actors) {
                    assertEquals(Verdict.NeedsReview(setOf(reopenDot)), Assessment.assess(captured, beforeRelease.getValue(actor)), actor.value)
                }
            },
            { assertEquals(3, r.presentations.size) },
            { assertTrue(r.presentations.none { it.shownAsApplicable || it.unknown }, "flagged for review, and never Unknown") },
        )
    }

    /**
     * The same fact under two identities is two receipts. Alex reports v1 reopened, the agent
     * captures that and picks v1, then sam reports v1 reopened too. Sam's report is the same fact
     * as one in the basis and changes nothing about the state, yet the agent never received it, and
     * a reopening is always relevant, so every replica flags the proposal for sam's dot alone.
     *
     * The plan's first form (two closures of v1) expected the same flag, but there the agent picks
     * v2 and the relevance policy ignores a closure of another venue: the policy decides that
     * verdict, not the causal record.
     */
    @Test
    fun sameFactTwoIdentities() = runTest(UnconfinedTestDispatcher()) {
        val scenario = Scenario(
            "same-fact", actors,
            listOf(
                Step.Edit(InputKey("reopenA"), alex, WorkspaceEntry.Report.Reopened(alex, v1)),
                Step.StartAgent("r1", host = remote),
                Step.Edit(InputKey("reopenS"), sam, WorkspaceEntry.Report.Reopened(sam, v1)),
                Step.ReleaseAgent("r1"),
            ),
        )
        val recorder = StepRecorder()
        val r = KuiltBackend(
            scope = backgroundScope,
            advance = { drainAntiEntropy(KuiltBackend.antiEntropyInterval, rounds = 10) },
            network = { scope -> FaultyNetwork(scope) },
            observer = recorder,
        ).run(scenario)
        val captured = recorder.released.single()
        val beforeRelease = recorder.logsBefore.getValue(scenario.steps.last())
        val log = beforeRelease.getValue(sam).entries()
        val alexDot = log.single { it.second.by == alex }.first.dot
        val samDot = log.single { it.second.by == sam }.first.dot
        assertAll(
            { assertTrue(alexDot != samDot, "two identities") },
            { assertEquals(setOf(alexDot), captured.basis.allDots) },
            { assertEquals(Recommendation(v1), captured.recommendation) },
            {
                for (actor in actors) {
                    assertEquals(Verdict.NeedsReview(setOf(samDot)), Assessment.assess(captured, beforeRelease.getValue(actor)), actor.value)
                }
            },
            { assertEquals(3, r.presentations.size) },
            { assertTrue(r.presentations.none { it.shownAsApplicable || it.unknown }) },
        )
    }

    // ── The leaky stamp, over the wire ────────────────────────────────────────────────────────

    /**
     * Task 6's positive control, rerun through replication. Each cell stamps a finished proposal,
     * appends the wire form to the host's replica, and reads it back from **alex's** replica after
     * it crossed Quilter's CBOR wire and merged into another `Rga`. The honest stamp arrives with its
     * captured basis in every cell; [LeakyStamp] arrives with a different one in every cell.
     *
     * Beyond `CaptureProperties.basisUnchangedByLaterUpdates`, which round-trips the stamp in memory,
     * this pins the path a real result takes: the wire codec keeps every step and dot (a step-flattening
     * `toEntry` reds the honest arm in the tool-result cells), a leaky basis is not normalised back
     * into shape in transit, and the receiver reads the stamped basis rather than anything it could
     * derive from its own log, which by then holds the later updates too. Each cell also requires the
     * entry to arrive at all, so a lost frame cannot pass as "caught".
     */
    @Test
    fun leakyStampFailsOverTheWire() = runTest(UnconfinedTestDispatcher()) {
        val honest = basisOverTheWire { proposal, _ -> proposal.toEntry(remote) }
        val leaky = basisOverTheWire(LeakyStamp::stamp)
        assertAll(
            { assertEquals(8, honest.size) },
            { assertEquals(List(8) { WireOutcome.Arrived(basisKept = true) }, honest, "the honest stamp keeps the basis over the wire") },
            { assertEquals(List(8) { WireOutcome.Arrived(basisKept = false) }, leaky, "the leaky stamp is caught in every cell") },
        )
    }

    private sealed interface WireOutcome {
        data object NeverArrived : WireOutcome

        /** Arrived, but `toProposal` refused its shape: a different failure from a basis that differs. */
        data object Rejected : WireOutcome
        data class Arrived(val basisKept: Boolean) : WireOutcome
    }

    /**
     * One cell per (0–1 inputs before capture, an optional tool result, 1–2 host updates after
     * capture), each on a fresh mesh. The later updates reach the host before the stamp, so a stamp
     * that reads the live log is caught in every cell.
     */
    private suspend fun TestScope.basisOverTheWire(
        stamp: (Proposal, Rga<WorkspaceEntry>) -> WorkspaceEntry.AgentProposal,
    ): List<WireOutcome> {
        val before = listOf(WorkspaceEntry.PreferenceSet(sam, 50))
        val later = listOf(WorkspaceEntry.Report.Closed(alex, v2), WorkspaceEntry.PreferenceSet(alex, 20))
        val outcomes = mutableListOf<WireOutcome>()
        for (n in 0..before.size) for (tool in listOf(false, true)) for (m in 1..later.size) {
            outcomes += withMesh { mesh ->
                before.take(n).forEach { mesh.append(sam, it) }
                drain()
                val request = RequestId("r-$n-$tool-$m")
                var pending = InputCapture.capture(request, mesh.log(remote))
                if (tool) {
                    val id = mesh.append(remote, WorkspaceEntry.Report.Full(remote, v4))
                    pending = pending.withToolResult(mesh.log(remote), setOf(id))
                }
                val proposal = pending.complete(Recommendation(v2))
                later.take(m).forEach { mesh.append(alex, it) }
                drain()
                val live = mesh.log(remote)
                val leaked = live.entries().count { it.first.dot !in proposal.basis.allDots }
                check(leaked == m) { "cell $request: the host holds $leaked inputs outside the basis, expected $m" }
                mesh.append(remote, stamp(proposal, live))
                drain()
                val arrived = mesh.log(alex).entries().map { it.second }
                    .filterIsInstance<WorkspaceEntry.AgentProposal>().singleOrNull { it.request == request }
                when (arrived) {
                    null -> WireOutcome.NeverArrived
                    else -> try {
                        WireOutcome.Arrived(basisKept = arrived.toProposal().basis == proposal.basis)
                    } catch (_: IllegalArgumentException) {
                        WireOutcome.Rejected
                    }
                }
            }
        }
        return outcomes
    }

    // ── Harness ───────────────────────────────────────────────────────────────────────────────

    private fun TestScope.drain() = drainAntiEntropy(KuiltBackend.antiEntropyInterval, rounds = 10)

    /** Runs [block] on a fresh mesh whose replicas stop when it returns. [decorate] may wrap an actor's seam. */
    private suspend fun <T> TestScope.withMesh(
        decorate: (ActorId, Seam) -> Seam = { _, seam -> seam },
        block: suspend (Mesh) -> T,
    ): T {
        val job = SupervisorJob(backgroundScope.coroutineContext[Job])
        try {
            val mesh = Mesh(backgroundScope + job, actors, decorate)
            mesh.wire()
            drain()
            return block(mesh)
        } finally {
            job.cancel()
        }
    }

    /** Every actor's log before each step, and every released proposal. */
    private class StepRecorder : KuiltRunObserver {
        val logsBefore = mutableMapOf<Step, Map<ActorId, Rga<WorkspaceEntry>>>()
        val released = mutableListOf<Proposal>()

        override fun beforeStep(step: Step, replicas: Map<ActorId, Rga<WorkspaceEntry>>) {
            logsBefore[step] = replicas
        }

        override fun released(proposal: Proposal) {
            released += proposal
        }

        /** The dots of [actor]'s input entries just before [step]. */
        fun inputDotsAt(step: Step, actor: ActorId): Set<Dot> = logsBefore.getValue(step).getValue(actor).entries()
            .filter { (_, e) -> e !is WorkspaceEntry.AgentProposal && e !is WorkspaceEntry.Accept }
            .mapTo(mutableSetOf()) { it.first.dot }
    }
}

/** Decodes one replicator frame with the backend's own wire serializer. Every frame on these seams is one. */
private fun decode(bytes: List<Byte>): QuiltMessage<Rga<WorkspaceEntry>> =
    Cbor.decodeFromByteArray(messageSerializer, bytes.toByteArray())

private fun QuiltMessage<Rga<WorkspaceEntry>>.isProposalDelta(): Boolean =
    this is QuiltMessage.Delta && delta.entries().any { (_, e) -> e is WorkspaceEntry.AgentProposal }

/** True when every element of this list appears in [other] in the same relative order. */
private fun <T> List<T>.isSubsequenceOf(other: List<T>): Boolean {
    var i = 0
    for (x in other) if (i < size && this[i] == x) i++
    return i == size
}

/** One frame: its bytes, and whom it was sent to (`null` for a broadcast). */
private class SentFrame(val to: PeerId?, val bytes: List<Byte>)

/** One received frame: its bytes and the peer the transport says sent it. */
private class ReceivedFrame(val sender: PeerId?, val bytes: List<Byte>)

/**
 * A test-side decorator over one actor's seam. It records every frame the replicator sends and
 * every frame it receives, and when [duplicate] is set it sends each outbound frame twice.
 */
private class TracingSeam(private val delegate: Seam, private val duplicate: Boolean) : Seam by delegate {
    val sent = mutableListOf<SentFrame>()
    val duplicated = mutableListOf<SentFrame>()
    val received = mutableListOf<ReceivedFrame>()

    override val incoming: Flow<Swatch> = delegate.incoming.onEach { received += ReceivedFrame(it.sender, it.toByteArray().toList()) }

    override suspend fun broadcast(payload: ByteArray) {
        send(SentFrame(null, payload.toList())) { delegate.broadcast(payload.copyOf()) }
    }

    override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
        send(SentFrame(peer, payload.toList())) { delegate.sendTo(peer, payload.copyOf()) }
    }

    private suspend fun send(frame: SentFrame, out: suspend () -> Unit) {
        sent += frame
        out()
        if (duplicate) {
            sent += frame
            duplicated += frame
            out()
        }
    }
}

/**
 * A test-side decorator that turns every broadcast into one `sendTo` per peer, and while [holding]
 * keeps (and counts) every frame addressed to [holdFor] instead of sending it. [release] sends the
 * held frames on, in order, and stops holding.
 */
private class HoldingSeam(private val delegate: Seam, private val holdFor: () -> PeerId) : Seam by delegate {
    var holding = false
    val held = mutableListOf<ByteArray>()

    override suspend fun broadcast(payload: ByteArray) {
        for (peer in delegate.peers.value) if (peer != delegate.selfId) sendTo(peer, payload.copyOf())
    }

    override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
        if (holding && peer == holdFor()) held += payload.copyOf() else delegate.sendTo(peer, payload)
    }

    suspend fun release() {
        holding = false
        val target = holdFor()
        for (frame in held) delegate.sendTo(target, frame)
    }
}

/** Wraps each seam [inner] weaves in a [TracingSeam]; only [duplicateFrom]'s seam duplicates. */
private class TracingNetwork(private val inner: WorkspaceNetwork, private val duplicateFrom: ActorId?) : WorkspaceNetwork {
    private val seams = linkedMapOf<ActorId, TracingSeam>()

    fun seam(actor: ActorId): TracingSeam = seams.getValue(actor)

    override suspend fun weave(actor: ActorId): Seam = TracingSeam(inner.weave(actor), actor == duplicateFrom).also { seams[actor] = it }

    override fun cut(actor: ActorId) = inner.cut(actor)

    override fun restore(actor: ActorId) = inner.restore(actor)
}

/**
 * [KuiltBackend]'s replicas, for the traces its scenario steps cannot express: each one comes from
 * [wireReplica], the factory the backend itself uses.
 */
private class Mesh(
    private val scope: CoroutineScope,
    private val actors: List<ActorId>,
    private val decorate: (ActorId, Seam) -> Seam,
) {
    private val network = FaultyNetwork(scope)
    private val replicas = linkedMapOf<ActorId, Pair<ReplicaId, Quilter<Rga<WorkspaceEntry>>>>()

    suspend fun wire() {
        actors.forEachIndexed { index, actor ->
            val quilter = wireReplica(decorate(actor, network.weave(actor)), index, scope)
            replicas[actor] = quilter.replica to quilter
        }
    }

    fun log(actor: ActorId): Rga<WorkspaceEntry> = replicas.getValue(actor).second.state.value

    /** Appends [entry] at the end of [actor]'s log, as the backend does, and returns its id. */
    fun append(actor: ActorId, entry: WorkspaceEntry): RgaId {
        val (id, quilter) = replicas.getValue(actor)
        var minted: RgaId? = null
        quilter.mutate { state ->
            val (_, op) = state.insertAt(id, state.size, entry)
            minted = op.id
            Patch(Rga.empty<WorkspaceEntry>().apply(op))
        }
        return checkNotNull(minted) { "mutate ran no transform" }
    }

    fun remove(actor: ActorId, target: RgaId) {
        replicas.getValue(actor).second.apply(Patch(Rga.empty<WorkspaceEntry>().apply(RgaOp.Remove(target))))
    }
}
