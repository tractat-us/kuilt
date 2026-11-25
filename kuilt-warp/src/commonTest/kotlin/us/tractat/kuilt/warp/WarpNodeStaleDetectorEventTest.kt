@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.warp

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.liveness.PartitionEvent
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

private val STALE_TEST_HEARTBEAT = HeartbeatConfig(
    interval = 50.milliseconds,
    timeout = 100.milliseconds,
    reconnectWindow = 200.milliseconds,
)

private val STALE_TEST_QUILTER_CONFIG = QuilterConfig(
    antiEntropyInterval = 100.milliseconds,
    fullStateRetryInterval = 150.milliseconds,
    expectVirtualTime = true,
)

/**
 * A node acts on what a peer's detector reports only while that detector is still the peer's
 * (#3672). B leaves A's roster and comes back, so A cancels B's detector and starts another; an
 * event the old detector reported then reaches A.
 *
 * On one thread that event is never handled: the old detector's collector is cancelled with it, and
 * it suspends nowhere between taking an event and acting on it. On a multi-threaded dispatcher a
 * collector that loses its thread in that gap hands the event over late, with its own detector's
 * job. [WarpNode.handlePartitionEventForTest] hands over that pair, so these tests reach the gap on
 * every target, in virtual time.
 *
 * Each test ends with a control: the same event, from the detector that is current, does what an
 * event does. So a stale event that does nothing is the check refusing it, not a seam that reaches
 * nothing.
 *
 * Why not `MultiNodeWarpSim`: it lives in `:kuilt-warp-test`, which depends on this module, so it
 * can neither be used here nor reach the `internal` seam from there. The wiring below is the one
 * [WarpNodePartitionFailoverTest] uses.
 */
class WarpNodeStaleDetectorEventTest {

    /**
     * B's old detector called it unresponsive. Acted on, that holds B out of A's ring behind a new
     * detector that never saw B go quiet, so it never reports B recovered, and A takes B's work for
     * good.
     */
    @Test
    fun aStaleUnresponsiveLeavesTheReturnedPeerInTheRing() =
        staleExclusionKeepsBInTheRing { b, at -> PartitionEvent.PeerUnresponsive(b, at, PartitionEvent.Reason.Timeout) }

    /** The same, for a stale loss: B stays out of A's ring for good. */
    @Test
    fun aStaleLossLeavesTheReturnedPeerInTheRing() =
        staleExclusionKeepsBInTheRing { b, at -> PartitionEvent.PeerLost(b, at) }

    /**
     * A's current detector holds B partitioned, and B's old one reports it recovered. Acted on, that
     * puts B back in A's ring while the link the current detector watches is still silent, so B's
     * tasks are handed to a peer that is not there.
     */
    @Test
    fun aStaleRecoveryLeavesAPartitionedPeerOutOfTheRing() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val mesh = readmittedB()
            val (nodeA, b, old, current) = mesh

            nodeA.handlePartitionEventForTest(PartitionEvent.PeerUnresponsive(b, now(), PartitionEvent.Reason.Timeout), current)
            testScheduler.runCurrent()
            val heldOut = b !in nodeA.eligiblePeers(Affinity.Anywhere)

            nodeA.handlePartitionEventForTest(PartitionEvent.PeerRecovered(b, now()), old)
            testScheduler.runCurrent()
            val afterStale = nodeA.eligiblePeers(Affinity.Anywhere)

            nodeA.handlePartitionEventForTest(PartitionEvent.PeerRecovered(b, now()), current)
            testScheduler.runCurrent()
            val afterControl = nodeA.eligiblePeers(Affinity.Anywhere)

            assertAll(
                { assertTrue(heldOut, "rig: the current detector's unresponsive holds B out of A's ring") },
                { assertFalse(b in afterStale, "a stale recovery leaves B out of A's ring") },
                { assertTrue(b in afterControl, "control: from the current detector, B is back in A's ring") },
            )
            mesh.close()
        }

    private fun staleExclusionKeepsBInTheRing(event: (PeerId, Instant) -> PartitionEvent) =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val mesh = readmittedB()
            val (nodeA, b, old, current) = mesh

            nodeA.handlePartitionEventForTest(event(b, now()), old)
            testScheduler.runCurrent()
            val ringAfterStale = nodeA.eligiblePeers(Affinity.Anywhere)

            // Work enqueued after the stale event: B's share must go to B. The heartbeats keep
            // running throughout, so A's current detector sees B alive the whole time.
            val tasks = (1..8).map { TaskId("stale-detector-task-$it") }
            val ownedByB = tasks.filterTo(mutableSetOf()) {
                RosterSnapshot(setOf(nodeA.selfId, b)).toTaskRing().owner(it) == b
            }
            tasks.forEach { nodeA.enqueue(it, TaskDescriptor(op = TRACK_OP, args = it.value.encodeToByteArray())) }
            repeat(30) { advanceTimeBy(50.milliseconds) }
            val ranOn = mesh.ranOn()

            nodeA.handlePartitionEventForTest(event(b, now()), current)
            testScheduler.runCurrent()
            val ringAfterControl = nodeA.eligiblePeers(Affinity.Anywhere)

            assertAll(
                { assertTrue(ownedByB.isNotEmpty(), "rig: B owns some of the tasks on the {A, B} ring") },
                { assertTrue(b in ringAfterStale, "a stale event leaves B in A's ring") },
                { assertEquals(tasks.toSet(), ranOn.keys, "every task ran") },
                {
                    assertEquals(
                        ownedByB.associateWith { listOf(b) },
                        ranOn.filterKeys { it in ownedByB },
                        "B's share of the work ran on B, and only there",
                    )
                },
                { assertFalse(b in ringAfterControl, "control: from the current detector, B leaves A's ring") },
            )
            mesh.close()
        }

    private class Mesh(
        val nodeA: WarpNode,
        val b: PeerId,
        val old: Job,
        val current: Job,
        private val nodeB: WarpNode,
        val ranOn: () -> Map<TaskId, List<PeerId>>,
    ) {
        operator fun component1() = nodeA
        operator fun component2() = b
        operator fun component3() = old
        operator fun component4() = current

        fun close() {
            nodeA.close()
            nodeB.close()
        }
    }

    /**
     * A and B, both running; then B leaves A's roster and comes back, so A has cancelled B's first
     * detector ([Mesh.old]) and started a second ([Mesh.current]). B's own roster never changes.
     */
    private suspend fun TestScope.readmittedB(): Mesh {
        val loom = InMemoryLoom()
        val seamA = loom.host(Pattern("stale-detector"))
        val seamB = loom.join(InMemoryTag("b"))
        val a = seamA.selfId
        val b = seamB.selfId
        val rosterA = MutableStateFlow(setOf(a, b))
        val rosterB = MutableStateFlow(setOf(a, b))

        val ranLock = reentrantLock()
        val ran = mutableMapOf<TaskId, MutableList<PeerId>>()
        fun registry(self: PeerId) = OpRegistry().also { r ->
            r.register(TRACK_OP, Op { args ->
                ranLock.withLock { ran.getOrPut(TaskId(args.decodeToString())) { mutableListOf() }.add(self) }
                args
            })
        }
        fun node(self: PeerId, seam: Seam, roster: MutableStateFlow<Set<PeerId>>) = WarpNode(
            selfId = self,
            seam = seam,
            rosterFlow = roster,
            scope = backgroundScope,
            quilterConfig = STALE_TEST_QUILTER_CONFIG,
            clock = { now() },
            heartbeatConfig = STALE_TEST_HEARTBEAT,
            registry = registry(self),
            epoch = 0L,
        )
        val nodeA = node(a, seamA, rosterA)
        val nodeB = node(b, seamB, rosterB)

        advanceTimeBy(10.milliseconds)
        val old = assertNotNull(nodeA.detectorJobForTest(b), "rig: A watches B")
        rosterA.value = setOf(a)
        advanceTimeBy(10.milliseconds)
        rosterA.value = setOf(a, b)
        advanceTimeBy(10.milliseconds)
        val current = assertNotNull(nodeA.detectorJobForTest(b), "rig: A watches the returned B")

        assertAll(
            { assertTrue(old.isCancelled, "rig: B's old detector was cancelled when B left") },
            { assertNotSame(old, current, "rig: the returned B is watched by a new detector") },
            { assertTrue(current.isActive, "rig: and that detector is running") },
            { assertTrue(b in nodeA.eligiblePeers(Affinity.Anywhere), "rig: B is in A's ring") },
        )
        return Mesh(nodeA, b, old, current, nodeB) { ranLock.withLock { ran.mapValues { it.value.toList() } } }
    }

    private fun TestScope.now(): Instant = Instant.fromEpochMilliseconds(testScheduler.currentTime)

    private companion object {
        val TRACK_OP = OpId("stale-detector-track")
    }
}
