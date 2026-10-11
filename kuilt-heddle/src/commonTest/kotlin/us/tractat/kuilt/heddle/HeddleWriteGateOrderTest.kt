package us.tractat.kuilt.heddle

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.raft.RaftRole
import us.tractat.kuilt.raft.test.FakeRaftNode
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HeddleWriteGateOrderTest {
    @Test
    fun olderEnrollmentCannotReopenLaterDeparture() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            reverseContinuations(departLast = true)
        }

    @Test
    fun olderDepartureCannotCloseLaterEnrollment() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            reverseContinuations(departLast = false)
        }

    private suspend fun TestScope.reverseContinuations(departLast: Boolean) {
        val raft = FakeRaftNode(initialRole = RaftRole.Leader)
        val seam = InMemoryLoom().host(Pattern("heddle-gate-order"))
        val self = ReplicaId(seam.selfId.value)
        val root = GroupId("root")
        val governed = backgroundScope.heddleGoverned(
            seam = seam, self = self, raft = raft, root = root,
            clock = { Instant.fromEpochMilliseconds(testScheduler.currentTime) },
            config = HeddleConfig(
                policy = PolicyConfig(quantum = 10L),
                maxHoldingsPerPeer = 100_000L,
                demandTtl = 30.seconds,
                quilter = QuilterConfig(
                    antiEntropyInterval = 100.milliseconds, fullStateRetryLimit = 0, expectVirtualTime = true,
                ),
                heartbeat = HeartbeatConfig(), random = Random(23),
            ),
            incarnation = "gate-order", epoch = 0L,
        )
        if (!departLast) assertIs<ControlOutcome.Applied>(governed.enroll(self))

        // Inject the resume order at the existing Raft boundary. The first entry is committed
        // and applied normally, but its propose caller cannot resume until the second verb returns.
        // No production hook or real-time scheduling is needed to reproduce the continuation race.
        val releaseFirst = CompletableDeferred<Unit>()
        var proposals = 0
        raft.proposeBehavior = { command ->
            val first = ++proposals == 1
            val entry = raft.pushCommitted(command)
            if (first) releaseFirst.await()
            entry
        }
        val first = async {
            if (departLast) governed.enroll(self) else governed.depart()
        }
        runCurrent()
        assertAll(
            { assertEquals(1, proposals, "rig: first proposal reached the suspended return") },
            { assertFalse(first.isCompleted, "rig: first continuation is still held") },
            {
                assertEquals(
                    if (departLast) setOf(self) else emptySet(), governed.enrolledReplicas(),
                    "rig: the first command has already applied",
                )
            },
        )
        val second = assertIs<ControlOutcome.Applied>(
            if (departLast) governed.depart() else governed.enroll(self),
        )
        assertFalse(first.isCompleted, "rig: later verb returned before the earlier one")
        releaseFirst.complete(Unit)
        val earlier = assertIs<ControlOutcome.Applied>(first.await())
        assertAll(
            { assertEquals(2, proposals, "rig: both proposals ran") },
            { assertTrue(earlier.index < second.index, "rig: applied indices oppose continuation order") },
            { assertEquals(!departLast, governed.isWritable, "gate must follow applied order, not resume order") },
            {
                assertEquals(
                    if (departLast) emptySet() else setOf(self), governed.enrolledReplicas(),
                    "the roster follows the later applied command",
                )
            },
            {
                assertEquals(
                    if (departLast) GateClosed(self, GateClosed.Reason.Departed) else ReserveOutcome.NoHoldings,
                    governed.reserve(root, 1L), "reserve must read the latest gate reason",
                )
            },
            {
                assertEquals(
                    if (departLast) GateClosed(self, GateClosed.Reason.Departed) else ScheduleOutcome.NothingToDelegate,
                    governed.schedule(root), "schedule must read the latest gate reason",
                )
            },
        )
    }
}
