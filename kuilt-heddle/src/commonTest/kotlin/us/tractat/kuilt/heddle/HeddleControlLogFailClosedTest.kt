package us.tractat.kuilt.heddle

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.raft.ClusterConfig
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * A node that meets a log entry it cannot read stops there and refuses writes, whether the entry is an
 * act from another version or bytes heddle never wrote (#1738). Nothing is stepped over.
 *
 * Every halt below asserts the entry the codec actually classified as [ControlEntry.Unreadable], so
 * a fixture that merely fails to reach the decoder cannot pass.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HeddleControlLogFailClosedTest {

    @Test
    fun anUnknownVersionStopsTheLogThereAndClosesTheGate() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val raft = FakeRaftNode(initialRole = RaftRole.Leader)
            val governed = boot(raft, "unknown-version")
            assertIs<ControlOutcome.Applied>(governed.enroll(SELF))
            assertIs<ControlOutcome.Applied>(governed.mint(SELF, 5L))
            assertTrue(governed.isWritable, "rig: open before the entry arrives")

            val bytes = ControlLogVectors.taggedAtVersion(99)
            assertIs<ControlEntry.Unreadable>(ControlLogCodec.decode(bytes), "rig: this is a heddle act")
            val unreadable = raft.pushCommitted(bytes)
            // Readable, and past the entry: applying it would mean the log kept going.
            raft.pushCommitted(actBytes("after#0", ControlCommand.Mint(ROOT, SELF, 40L)))
            raft.pushCommitted(actBytes("after#1", ControlCommand.Enroll(OTHER)))
            runCurrent()

            val closed = GateClosed(SELF, GateClosed.Reason.UnreadableControlEntry(unreadable.index, 99))
            assertAll(
                { assertFalse(governed.isWritable) },
                { assertEquals(closed, governed.reserve(ROOT, 1L), "reserve names the entry") },
                { assertEquals(closed, governed.schedule(ROOT), "schedule names the entry") },
                { assertEquals(5L, governed.ledger.value.mintedTotal(), "the later mint must not apply") },
                { assertEquals(setOf(SELF), governed.enrolledReplicas(), "the later enroll must not apply") },
            )
        }

    /** The applied prefix stops short of the entry, so it never reads as fully applied. */
    @Test
    fun theAppliedIndexDoesNotAdvancePastAnUnreadableEntry() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val raft = FakeRaftNode(initialRole = RaftRole.Leader)
            val plane = plane(raft, backgroundScope)
            val readable = plane.submit(ControlCommand.Enroll(SELF))
            val before = plane.rosterSnapshot().appliedIndex
            assertEquals(readable.index, before, "rig: the prefix reached the readable act")

            val unreadable = raft.pushCommitted(ControlLogVectors.taggedAtVersion(99))
            raft.pushCommitted(actBytes("after#0", ControlCommand.Enroll(OTHER)))
            runCurrent()

            assertAll(
                { assertTrue(unreadable.index > before, "rig: the entry lands past the prefix") },
                { assertEquals(before, plane.rosterSnapshot().appliedIndex, "the prefix stops before it") },
            )
        }

    @Test
    fun eachUnreadableShapeLatchesItsIndexAndVersion() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val shapes = listOf(
                "corrupt body" to (ControlLogVectors.CORRUPT_TAGGED.hexToByteArray() to 1),
                "no version byte" to (ControlLogVectors.MAGIC_ONLY.hexToByteArray() to null),
                "v0.7.3 mint" to (ControlLogVectors.V0_7_3_MINT.hexToByteArray() to 0),
                "v0.7.3 prepare" to (ControlLogVectors.V0_7_3_PREPARE.hexToByteArray() to 0),
            )
            for ((name, shape) in shapes) {
                val (bytes, version) = shape
                val raft = FakeRaftNode(initialRole = RaftRole.Leader)
                val governed = boot(raft, "shape")
                val entry = raft.pushCommitted(bytes)
                runCurrent()
                assertEquals(
                    GateClosed(SELF, GateClosed.Reason.UnreadableControlEntry(entry.index, version)),
                    governed.reserve(ROOT, 1L),
                    name,
                )
            }
        }

    /**
     * Bytes heddle never wrote stop the log exactly as an act it cannot read does. The readable mint
     * behind each one is what makes the stop observable: a node that stepped over the entry would apply
     * it, and would answer the later calls as a healthy node.
     */
    @Test
    fun anEntryHeddleNeverWroteStopsTheLogThereAndClosesTheGate() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            for ((name, bytes) in ControlLogVectors.foreign) {
                val raft = FakeRaftNode(initialRole = RaftRole.Leader)
                val governed = boot(raft, "foreign-$name")
                assertIs<ControlOutcome.Applied>(governed.enroll(SELF), name)
                assertIs<ControlOutcome.Applied>(governed.mint(SELF, 5L), name)
                assertTrue(governed.isWritable, "rig: open before the entry arrives: $name")
                assertIs<ControlEntry.Unreadable>(ControlLogCodec.decode(bytes), "rig: the codec has no other answer: $name")

                val entry = raft.pushCommitted(bytes)
                val later = raft.pushCommitted(actBytes("after#0", ControlCommand.Mint(ROOT, SELF, 40L)))
                runCurrent()
                val proposalsBefore = raft.proposals.size
                val refused = assertIs<ControlOutcome.Conflict>(governed.mint(SELF, 1L), name)

                val closed = GateClosed(SELF, GateClosed.Reason.UnreadableControlEntry(entry.index, ControlLogCodec.UNTAGGED_VERSION))
                assertAll(
                    { assertTrue(later.index > entry.index, "rig: a readable act lands past it: $name") },
                    { assertFalse(governed.isWritable, name) },
                    { assertEquals(closed, governed.reserve(ROOT, 1L), "reserve names the entry: $name") },
                    { assertEquals(closed, governed.schedule(ROOT), "schedule names the entry: $name") },
                    { assertEquals(5L, governed.ledger.value.mintedTotal(), "the later mint must not apply: $name") },
                    { assertEquals(ControlOutcome.NOT_COMMITTED, refused.index, "refused before the log: $name") },
                    { assertEquals(proposalsBefore, raft.proposals.size, "the refused call wrote nothing: $name") },
                )
            }
        }

    /**
     * Supported history survives: an untagged log from a build whose shapes still decode replays in
     * full, tagged acts follow it, and the node ends up writable and holding the supply.
     */
    @Test
    fun untaggedHistoryThatStillDecodesReplaysAndTaggedActsFollowIt() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val raft = FakeRaftNode(initialRole = RaftRole.Leader)
            val untagged = ControlLogVectors.bodies.getValue("Mint").hexToByteArray()
            assertIs<ControlEntry.Act>(ControlLogCodec.decode(untagged), "rig: readable")
            assertFalse(untagged.startsWithHeader(), "rig: the replayed act really has no header")
            raft.pushCommitted(untagged)
            raft.pushCommitted(actBytes("tagged#0", ControlCommand.Enroll(OTHER)))

            val governed = boot(raft, "mixed-history")
            runCurrent()
            assertAll(
                { assertEquals(7L, governed.ledger.value.mintedTotal(), "the untagged mint applied") },
                { assertEquals(setOf(OTHER), governed.enrolledReplicas(), "the tagged enroll applied") },
                { assertEquals(GateClosed(SELF, GateClosed.Reason.AwaitingEnrollment), governed.reserve(ROOT, 1L)) },
            )

            assertIs<ControlOutcome.Applied>(governed.enroll(SELF))
            assertTrue(governed.isWritable, "no latch: its own enroll opens the gate as usual")
        }

    /** Enrollment cannot clear the latch, and the refused call writes nothing to the log. */
    @Test
    fun aLaterEnrollIsRefusedAndCannotReopenTheGate() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val raft = FakeRaftNode(initialRole = RaftRole.Leader)
            val governed = boot(raft, "later-enroll")
            assertIs<ControlOutcome.Applied>(governed.enroll(SELF))
            val entry = raft.pushCommitted(ControlLogVectors.taggedAtVersion(99))
            runCurrent()
            val proposalsBefore = raft.proposals.size
            assertFalse(governed.isWritable, "rig: the latch is set")

            val refused = assertIs<ControlOutcome.Conflict>(governed.enroll(SELF))
            assertAll(
                { assertEquals(ControlOutcome.NOT_COMMITTED, refused.index, "refused before reaching the log") },
                { assertIs<ControlConflict.Refused>(refused.conflict) },
                { assertEquals(proposalsBefore, raft.proposals.size, "nothing was proposed") },
                { assertFalse(governed.isWritable, "enrollment must not clear the latch") },
                {
                    assertEquals(
                        GateClosed(SELF, GateClosed.Reason.UnreadableControlEntry(entry.index, 99)),
                        governed.reserve(ROOT, 1L),
                        "the reason still names the entry, not enrollment",
                    )
                },
            )
        }

    /**
     * The harder order: the enroll was proposed before the latch and commits after it. Its entry
     * sits past the unreadable one, so it must not apply, and its caller must not hang.
     */
    @Test
    fun anEnrollInFlightWhenTheLatchSetsCannotReopenTheGate() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val raft = FakeRaftNode(initialRole = RaftRole.Leader)
            val governed = boot(raft, "in-flight-enroll")
            val release = CompletableDeferred<Unit>()
            raft.proposeBehavior = { command ->
                release.await()
                raft.pushCommitted(command)
            }
            val enrolling = async { governed.enroll(SELF) }
            runCurrent()
            assertEquals(1, raft.proposals.size, "rig: the enroll was proposed before the latch")

            val entry = raft.pushCommitted(ControlLogVectors.taggedAtVersion(99))
            runCurrent()
            release.complete(Unit)
            val outcome = assertIs<ControlOutcome.Indeterminate>(enrolling.await(), "the caller is told, not left hanging")
            runCurrent()

            assertAll(
                { assertEquals(GateClosed.Reason.UnreadableControlEntry(entry.index, 99), outcome.halt) },
                { assertEquals(ControlOutcome.COMMIT_UNKNOWN, outcome.index, "not the never-reached-the-log sentinel") },
                { assertTrue(raft.commitIndex.value > entry.index, "rig: its entry committed past the latch") },
                { assertEquals(emptySet(), governed.enrolledReplicas(), "its entry must not apply") },
                { assertFalse(governed.isWritable, "and must not reopen the gate") },
                {
                    assertEquals(
                        GateClosed(SELF, GateClosed.Reason.UnreadableControlEntry(entry.index, 99)),
                        governed.reserve(ROOT, 1L),
                    )
                },
            )
        }

    /**
     * The failure this type exists for: automation that retries a refused mint on another peer would
     * mint twice if this answer read as a refusal, because the original commits. It must not, and the
     * call refused up front must stay distinguishable from it.
     */
    @Test
    fun aMintInFlightAtTheHaltIsNotARefusalButOneAfterItIs() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val raft = FakeRaftNode(initialRole = RaftRole.Leader)
            val governed = boot(raft, "in-flight-mint")
            val release = CompletableDeferred<Unit>()
            raft.proposeBehavior = { command ->
                release.await()
                raft.pushCommitted(command)
            }
            val inFlight = async { governed.mint(SELF, 5L) }
            runCurrent()
            assertEquals(1, raft.proposals.size, "rig: the mint was proposed before the halt")

            raft.pushCommitted(ControlLogVectors.taggedAtVersion(99))
            runCurrent()
            release.complete(Unit)
            val first = inFlight.await()
            runCurrent()
            val committedAfter = raft.commitIndex.value
            val second = governed.mint(SELF, 5L)

            assertAll(
                { assertIs<ControlOutcome.Indeterminate>(first, "the act may still commit: not a refusal") },
                { assertEquals(2L, committedAfter, "rig: and here it did commit, past the halt") },
                { assertIs<ControlOutcome.Conflict>(second, "refused up front: certainly not written") },
                { assertEquals(ControlOutcome.NOT_COMMITTED, second.index) },
                { assertEquals(1, raft.proposals.size, "the refused call wrote nothing") },
            )
        }

    /**
     * `reconcile` opens its barriers one call at a time. An indeterminate barrier has to reach the
     * caller, not be replaced by the definite refusal the `Reconcile` act would then draw: that answer
     * would say nothing was written, while the barrier may yet commit.
     */
    @Test
    fun anIndeterminateBarrierIsWhatReconcileReturns() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val raft = FakeRaftNode(initialRole = RaftRole.Leader)
            val governed = boot(raft, "reconcile")
            val edge = AttachmentId("edge")
            val child = GroupId("child")
            assertIs<ControlOutcome.Applied>(governed.prepare(AttachmentRecord(edge, ROOT, child, Weight.of(1))))
            assertIs<ControlOutcome.Applied>(governed.activate(edge))
            assertIs<ControlOutcome.Applied>(governed.close(edge))
            assertIs<ControlOutcome.Applied>(governed.retire(edge))
            val release = CompletableDeferred<Unit>()
            raft.proposeBehavior = { command ->
                release.await()
                raft.pushCommitted(command)
            }
            val proposalsBefore = raft.proposals.size

            val reconciling = async { governed.reconcile(child) }
            runCurrent()
            assertEquals(proposalsBefore + 1, raft.proposals.size, "rig: the barrier is in flight")
            raft.pushCommitted(ControlLogVectors.taggedAtVersion(99))
            runCurrent()
            release.complete(Unit)

            assertIs<ControlOutcome.Indeterminate>(reconciling.await())
        }

    /** The premise of the witness vector in [ControlLogVectors]: production's retire is never witness-free. */
    @Test
    fun aGovernedRetireProposesAWitness() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val raft = FakeRaftNode(initialRole = RaftRole.Leader)
            val governed = boot(raft, "retire-witness")
            val edge = AttachmentId("edge")
            assertIs<ControlOutcome.Applied>(governed.prepare(AttachmentRecord(edge, ROOT, GroupId("child"), Weight.of(1))))
            assertIs<ControlOutcome.Applied>(governed.activate(edge))
            assertIs<ControlOutcome.Applied>(governed.close(edge))
            assertIs<ControlOutcome.Applied>(governed.retire(edge))

            val act = assertIs<ControlEntry.Act>(ControlLogCodec.decode(raft.proposals.last()))
            assertNotNull(assertIs<ControlCommand.Retire>(act.envelope.command).witness)
        }

    /** A restart replays the same log to the same entry, so restarting cannot clear the latch either. */
    @Test
    fun aRestartReplaysToTheSameActAndClosesAgain() =
        aRestartLatchesAgainAt(ControlLogVectors.V0_7_3_MINT.hexToByteArray())

    /** So a log that picked up another writer's entry under an older build stops at it on upgrade. */
    @Test
    fun aRestartReplaysToTheSameForeignEntryAndClosesAgain() =
        aRestartLatchesAgainAt("set x=1".encodeToByteArray())

    private fun aRestartLatchesAgainAt(bytes: ByteArray) =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val raft = FakeRaftNode(initialRole = RaftRole.Leader)
            val runScope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
            val earlier = runScope.governedOn(this, raft, "before-restart")
            val entry = raft.pushCommitted(bytes)
            runCurrent()
            val closed = GateClosed(SELF, GateClosed.Reason.UnreadableControlEntry(entry.index, 0))
            assertEquals(closed, earlier.reserve(ROOT, 1L), "rig: the earlier run latched")
            runScope.cancel()

            val restarted = boot(raft, "after-restart")
            runCurrent()
            val enroll = restarted.enroll(SELF)

            assertAll(
                { assertEquals(closed, restarted.reserve(ROOT, 1L), "the new run latches at the same entry") },
                { assertIs<ControlOutcome.Conflict>(enroll, "and refuses its enroll") },
                { assertFalse(restarted.isWritable) },
            )
        }

    @Test
    fun everyActThisBuildProposesCarriesTheHeader() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val raft = FakeRaftNode(initialRole = RaftRole.Leader)
            val governed = boot(raft, "writes-tagged")
            assertIs<ControlOutcome.Applied>(governed.enroll(SELF))
            assertIs<ControlOutcome.Applied>(governed.mint(SELF, 5L))
            assertAll(
                { assertEquals(2, raft.proposals.size, "rig: two acts proposed") },
                { assertTrue(raft.proposals.all { it.startsWithHeader() }, "both open with the header") },
            )
        }

    /**
     * Every peer stops at the same index for the same reason, on the real engine: the control log is
     * consensus state, so this has to hold across the cluster and not just on one node.
     */
    @Test
    fun everyNodeOfARealClusterLatchesTheSameUnreadableAct() =
        everyNodeOfARealClusterLatchesAt(ControlLogVectors.taggedAtVersion(99), 99)

    /** One proposal from anything else sharing the Raft node is enough to stop the whole group. */
    @Test
    fun everyNodeOfARealClusterLatchesTheSameEntryHeddleNeverWrote() =
        everyNodeOfARealClusterLatchesAt("set x=1".encodeToByteArray(), ControlLogCodec.UNTAGGED_VERSION)

    private fun everyNodeOfARealClusterLatchesAt(bytes: ByteArray, version: Int) =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val ids = listOf(NodeId("a"), NodeId("b"), NodeId("c"))
            val sim = MultiNodeRaftSim(nodeIds = ids, scope = this, nodeScope = backgroundScope)
            val leader = sim.awaitLeader()
            val governed = ids.associateWith { id ->
                backgroundScope.governedOn(this, sim.nodes.getValue(id), "real-${id.value}", ReplicaId(id.value))
            }

            for ((id, node) in governed) assertIs<ControlOutcome.Applied>(node.enroll(ReplicaId(id.value)))
            sim.awaitTrue("every node applied every enroll") {
                governed.values.all { it.enrolledReplicas().size == ids.size }
            }
            // Without this the wait below passes on a node that never opened.
            assertTrue(governed.values.all { it.isWritable }, "rig: all open before the entry arrives")

            val unreadable = leader.propose(bytes)
            sim.awaitTrue("every node latched") { governed.values.none { it.isWritable } }

            val reason = GateClosed.Reason.UnreadableControlEntry(unreadable.index, version)
            assertEquals(
                ids.associateWith { id -> GateClosed(ReplicaId(id.value), reason) },
                ids.associateWith { id -> governed.getValue(id).reserve(ROOT, 1L) },
                "the same index and version on every node",
            )
        }

    /**
     * What the latch now rests on: raft's own no-ops and membership entries never arrive as log entries
     * to decode. If they did, every election and membership change would stop the group, because neither
     * carries bytes heddle can read. Both happen here on the real engine, and an act after them applies.
     */
    @Test
    fun raftsOwnEntriesNeverReachTheLatch() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val ids = listOf(NodeId("a"), NodeId("b"), NodeId("c"))
            val sim = MultiNodeRaftSim(nodeIds = ids, scope = this, nodeScope = backgroundScope)
            val leader = sim.awaitLeader()
            val governed = ids.associateWith { id ->
                backgroundScope.governedOn(this, sim.nodes.getValue(id), "real-${id.value}", ReplicaId(id.value))
            }
            for ((id, node) in governed) assertIs<ControlOutcome.Applied>(node.enroll(ReplicaId(id.value)))
            sim.awaitTrue("every node applied every enroll") {
                governed.values.all { it.enrolledReplicas().size == ids.size }
            }

            val leaderId = sim.nodes.entries.first { it.value === leader }.key
            val (removed, successor) = ids.filter { it != leaderId }
            val commitBeforeChange = leader.commitIndex.value
            leader.changeMembership(ClusterConfig(voters = setOf(leaderId, successor)))
            val commitAfterChange = leader.commitIndex.value
            leader.transferLeadership(successor)
            val newLeader = sim.awaitLeader(among = setOf(successor))

            val kept = listOf(leaderId, successor).map { governed.getValue(it) }
            assertIs<ControlOutcome.Applied>(governed.getValue(successor).mint(ReplicaId(successor.value), 5L))
            sim.awaitTrue("both remaining nodes applied the mint") {
                kept.all { it.ledger.value.mintedTotal() == 5L }
            }

            assertAll(
                { assertTrue(commitAfterChange > commitBeforeChange, "rig: membership entries committed") },
                { assertTrue(newLeader !== leader, "rig: a new leader took over, and appended its no-op") },
                { assertTrue(kept.all { it.isWritable }, "neither stopped a node: removed ${removed.value}") },
            )
        }

    private fun ByteArray.startsWithHeader(): Boolean =
        size >= 5 && copyOfRange(0, 5).contentEquals(ControlLogVectors.TAGGED_PREFIX.hexToByteArray())

    private fun actBytes(key: String, command: ControlCommand): ByteArray =
        ControlLogCodec.encode(ControlEnvelope(key, command, OTHER))

    private fun plane(raft: RaftNode, scope: CoroutineScope) = HeddleControlPlane(
        raft = raft,
        self = SELF,
        scope = scope,
        sink = ControlLedgerSink { },
        membership = ControlMembershipSink { },
        barrier = ControlBarrierSink { SlotFinals.ZERO },
        initial = EntitlementLedger.ZERO,
        incarnation = "plane",
    )

    private suspend fun TestScope.boot(raft: RaftNode, incarnation: String): GovernedHeddleNode =
        backgroundScope.governedOn(this, raft, incarnation)

    private suspend fun CoroutineScope.governedOn(
        test: TestScope,
        raft: RaftNode,
        incarnation: String,
        self: ReplicaId = SELF,
    ): GovernedHeddleNode {
        val seam = InMemoryLoom().host(Pattern("heddle-fail-closed"))
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
                random = Random(29),
            ),
            incarnation = incarnation,
            epoch = 0L,
        )
    }

    private companion object {
        val ROOT = GroupId("root")
        val SELF = ReplicaId("self")
        val OTHER = ReplicaId("other")
    }
}
