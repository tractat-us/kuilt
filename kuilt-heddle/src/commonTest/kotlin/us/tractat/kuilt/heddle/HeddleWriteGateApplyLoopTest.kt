package us.tractat.kuilt.heddle

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftNode
import us.tractat.kuilt.raft.RaftRole
import us.tractat.kuilt.raft.test.FakeRaftNode
import us.tractat.kuilt.raft.test.MultiNodeRaftSim
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The write gate moves when the apply loop applies this incarnation's own `enroll(self)` or `depart()`,
 * not when the caller's continuation runs (#2921). A caller that is cancelled, or that times out,
 * after its entry applied never resumes, and `submit`'s KDoc tells it not to resubmit once a read
 * shows the act landed. So nothing but the apply loop can be relied on to move the gate.
 *
 * Every rig below asserts that the entry really did apply before it asserts anything about the
 * gate. Without that, "the gate did not move" is also what a rig that never reached the apply loop
 * would report.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalSerializationApi::class)
class HeddleWriteGateApplyLoopTest {

    @Test
    fun cancelledDepartThatAlreadyAppliedClosesTheGate() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val raft = FakeRaftNode(initialRole = RaftRole.Leader)
            val governed = boot(raft, "cancelled-depart")
            assertIs<ControlOutcome.Applied>(governed.enroll(SELF))
            assertTrue(governed.isWritable, "rig: enrolled before the depart under test")

            raft.proposeBehavior = commitThenNeverReturn(raft)
            val departing = async { governed.depart() }
            runCurrent()
            assertEquals(emptySet(), governed.enrolledReplicas(), "rig: the Depart entry already applied")
            assertFalse(departing.isCompleted, "rig: the caller has not resumed")
            departing.cancel()
            runCurrent()

            assertAll(
                { assertTrue(departing.isCancelled, "rig: the caller was cancelled, not resumed") },
                { assertFalse(governed.isWritable, "a departed node must not be writable") },
                { assertEquals(GateClosed(SELF, GateClosed.Reason.Departed), governed.reserve(ROOT, 1L)) },
                { assertEquals(GateClosed(SELF, GateClosed.Reason.Departed), governed.schedule(ROOT)) },
            )
        }

    @Test
    fun timedOutDepartThatAlreadyAppliedClosesTheGate() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val raft = FakeRaftNode(initialRole = RaftRole.Leader)
            val governed = boot(raft, "timed-out-depart")
            assertIs<ControlOutcome.Applied>(governed.enroll(SELF))

            raft.proposeBehavior = commitThenNeverReturn(raft)
            assertFailsWith<TimeoutCancellationException> { governed.depart(timeout = 1.seconds) }
            runCurrent()

            assertAll(
                { assertEquals(emptySet(), governed.enrolledReplicas(), "rig: the Depart entry applied") },
                { assertFalse(governed.isWritable, "a departed node must not be writable") },
                { assertEquals(GateClosed(SELF, GateClosed.Reason.Departed), governed.reserve(ROOT, 1L)) },
            )
        }

    @Test
    fun timedOutSelfEnrollThatAlreadyAppliedOpensTheGate() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val raft = FakeRaftNode(initialRole = RaftRole.Leader)
            val governed = boot(raft, "timed-out-enroll")
            assertFalse(governed.isWritable, "rig: boots closed")

            raft.proposeBehavior = commitThenNeverReturn(raft)
            assertFailsWith<TimeoutCancellationException> { governed.enroll(SELF, timeout = 1.seconds) }
            runCurrent()

            assertAll(
                { assertEquals(setOf(SELF), governed.enrolledReplicas(), "rig: the Enroll entry applied") },
                { assertTrue(governed.isWritable, "the applied self-enroll opens the gate") },
                { assertEquals(ReserveOutcome.NoHoldings, governed.reserve(ROOT, 1L)) },
            )
        }

    /** The act outlives the caller: the gate moves at the entry's apply, and not one step sooner. */
    @Test
    fun selfEnrollThatCommitsAfterTheCallerGaveUpOpensTheGateWhenItApplies() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val raft = FakeRaftNode(initialRole = RaftRole.Leader)
            val governed = boot(raft, "late-commit")

            raft.proposeBehavior = { awaitCancellation() }
            assertFailsWith<TimeoutCancellationException> { governed.enroll(SELF, timeout = 1.seconds) }
            runCurrent()
            assertAll(
                { assertEquals(emptySet(), governed.enrolledReplicas(), "rig: nothing has committed yet") },
                { assertFalse(governed.isWritable, "a proposal that has not applied opens nothing") },
            )

            raft.pushCommitted(raft.proposals.single())
            runCurrent()
            assertAll(
                { assertEquals(setOf(SELF), governed.enrolledReplicas(), "rig: the late entry applied") },
                { assertTrue(governed.isWritable, "its apply opens the gate, though nobody is waiting on it") },
            )
        }

    /**
     * `incarnation` is a caller-supplied `String` and nothing stops a caller reusing one, so replay
     * must not read an older run's `Enroll(self)` as this run's. A prefix match on
     * `self#incarnation#` would, and would open the gate of a node that never enrolled.
     */
    @Test
    fun replayedEnrollFromAReusedIncarnationDoesNotOpenTheGate() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val raft = FakeRaftNode(initialRole = RaftRole.Leader)
            val (earlierRun, earlierScope) = bootOnOwnScope(raft, REUSED)
            assertIs<ControlOutcome.Applied>(earlierRun.enroll(SELF))
            earlierScope.cancel()

            val governed = boot(raft, REUSED)
            runCurrent()

            assertAll(
                { assertReplayedKeyIsThisIncarnations(raft) },
                { assertEquals(setOf(SELF), governed.enrolledReplicas(), "rig: replay applied the old enroll") },
                { assertFalse(governed.isWritable, "this run has not enrolled, whatever an earlier one did") },
                { assertEquals(GateClosed(SELF, GateClosed.Reason.AwaitingEnrollment), governed.reserve(ROOT, 1L)) },
            )
        }

    /**
     * The normal boot under a reused incarnation: the fresh `enroll(self)` is issued before replay
     * reaches the old one, and both draw sequence number 0. If they shared a requestKey the old
     * entry would resolve the new call and open the gate at the old entry's index, with every
     * barrier after it not yet replayed. That is the §6.5.3 hole the gate exists to close.
     */
    @Test
    fun enrollIssuedBeforeReplayUnderAReusedIncarnationWaitsForItsOwnEntry() =
        reusedIncarnationEnrollWaitsForItsOwnEntry(issueBeforeReplay = true)

    /** The same, when replay has already passed the old entry by the time `enroll(self)` is called. */
    @Test
    fun enrollIssuedAfterReplayUnderAReusedIncarnationWaitsForItsOwnEntry() =
        reusedIncarnationEnrollWaitsForItsOwnEntry(issueBeforeReplay = false)

    private fun reusedIncarnationEnrollWaitsForItsOwnEntry(issueBeforeReplay: Boolean) =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val raft = FakeRaftNode(initialRole = RaftRole.Leader)
            val (earlierRun, earlierScope) = bootOnOwnScope(raft, REUSED)
            val oldIndex = assertIs<ControlOutcome.Applied>(earlierRun.enroll(SELF)).index
            earlierScope.cancel()

            val release = CompletableDeferred<Unit>()
            raft.proposeBehavior = { command ->
                release.await()
                raft.pushCommitted(command)
            }
            val governed = boot(raft, REUSED)
            if (!issueBeforeReplay) runCurrent()
            // UNDISPATCHED so the key is drawn before the replay coroutine has run at all.
            val enrolling = async(start = CoroutineStart.UNDISPATCHED) { governed.enroll(SELF) }
            runCurrent()

            assertAll(
                { assertReplayedKeyIsThisIncarnations(raft) },
                { assertEquals(setOf(SELF), governed.enrolledReplicas(), "rig: replay applied the old enroll") },
                { assertEquals(2, raft.proposals.size, "rig: the fresh enroll really proposed") },
                { assertFalse(enrolling.isCompleted, "the old entry must not resolve the fresh call") },
                { assertFalse(governed.isWritable, "the old entry must not open the gate") },
            )

            release.complete(Unit)
            val applied = assertIs<ControlOutcome.Applied>(enrolling.await())
            assertAll(
                { assertTrue(applied.index > oldIndex, "the call resolves at its own entry, past the old one") },
                { assertTrue(governed.isWritable, "its own applied enroll opens the gate") },
            )
        }

    /**
     * Another peer enrolling this replica proves nothing about what this incarnation has applied, and
     * the gate has to tell the two apart inside the apply loop now that the caller no longer does.
     */
    @Test
    fun thirdPartyEnrollOfThisReplicaDoesNotOpenTheGate() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val raft = FakeRaftNode(initialRole = RaftRole.Leader)
            val governed = boot(raft, "third-party")
            val other = ReplicaId("other")
            raft.pushCommitted(
                Cbor.encodeToByteArray(
                    ControlEnvelope.serializer(),
                    ControlEnvelope("other#boot#0", ControlCommand.Enroll(SELF), other),
                ),
            )
            runCurrent()

            assertAll(
                { assertEquals(setOf(SELF), governed.enrolledReplicas(), "rig: the third party's enroll applied") },
                { assertFalse(governed.isWritable, "only this incarnation's own enroll opens the gate") },
            )
        }

    /**
     * The same bug on the real engine: a follower's `enroll(self)` times out while its forwarded
     * entry is still on the way, the proposal outlives the await, and the entry then applies with
     * no caller left to move the gate. The timeout is one virtual millisecond, shorter than any
     * replication round the simulated cluster can complete.
     */
    @Test
    fun timedOutEnrollThatCommitsOnTheRealEngineOpensTheGate() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val ids = listOf(NodeId("a"), NodeId("b"), NodeId("c"))
            val sim = MultiNodeRaftSim(nodeIds = ids, scope = this, nodeScope = backgroundScope)
            val leader = sim.awaitLeader()
            val followerId = ids.first { sim.nodes.getValue(it) !== leader }
            val raft = sim.nodes.getValue(followerId)
            val self = ReplicaId(followerId.value)
            val governed = boot(raft, "real-engine", self = self)

            assertFailsWith<TimeoutCancellationException> { governed.enroll(self, timeout = 1.milliseconds) }
            assertFalse(self in governed.enrolledReplicas(), "rig: the call timed out before the entry applied")
            assertFalse(governed.isWritable, "rig: nothing has opened the gate yet")

            sim.awaitTrue("the abandoned enroll commits and applies") { self in governed.enrolledReplicas() }
            assertTrue(governed.isWritable, "the applied self-enroll opens the gate with no caller waiting")
        }

    private fun commitThenNeverReturn(raft: FakeRaftNode): suspend (ByteArray) -> Nothing = { command ->
        raft.pushCommitted(command)
        awaitCancellation()
    }

    private fun assertReplayedKeyIsThisIncarnations(raft: FakeRaftNode) {
        val replayed = Cbor.decodeFromByteArray(ControlEnvelope.serializer(), raft.proposals.first())
        assertEquals(ControlCommand.Enroll(SELF), replayed.command, "rig: the replayed act is a self-enroll")
        assertEquals(SELF, replayed.proposer, "rig: proposed by this replica")
        assertTrue(
            replayed.requestKey.startsWith("${SELF.value}#$REUSED#"),
            "rig: the replayed key carries the reused incarnation, was ${replayed.requestKey}",
        )
    }

    /** A run that can be ended by cancelling its scope, the way a process exit would end it. */
    private suspend fun TestScope.bootOnOwnScope(
        raft: RaftNode,
        incarnation: String,
    ): Pair<GovernedHeddleNode, CoroutineScope> {
        val scope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        return scope.governedOn(this, raft, incarnation, SELF) to scope
    }

    private suspend fun TestScope.boot(
        raft: RaftNode,
        incarnation: String,
        self: ReplicaId = SELF,
    ): GovernedHeddleNode = backgroundScope.governedOn(this, raft, incarnation, self)

    private suspend fun CoroutineScope.governedOn(
        test: TestScope,
        raft: RaftNode,
        incarnation: String,
        self: ReplicaId,
    ): GovernedHeddleNode {
        // One seam per run: a restart gets a fresh fabric, and only the Raft log carries over.
        val seam = InMemoryLoom().host(Pattern("heddle-gate-apply-loop"))
        return heddleGoverned(
            seam = seam,
            self = self,
            raft = raft,
            root = ROOT,
            clock = { Instant.fromEpochMilliseconds(test.testScheduler.currentTime) },
            config = HeddleConfig(
                policy = PolicyConfig(quantum = 10L),
                maxHoldingsPerPeer = 100_000L,
                demandTtl = 30.seconds,
                quilter = QuilterConfig(
                    antiEntropyInterval = 100.milliseconds,
                    fullStateRetryLimit = 0,
                    expectVirtualTime = true,
                ),
                heartbeat = HeartbeatConfig(),
                random = Random(23),
            ),
            incarnation = incarnation,
            epoch = 0L,
        )
    }

    private companion object {
        val ROOT = GroupId("root")
        val SELF = ReplicaId("self")
        const val REUSED = "reused"
    }
}
