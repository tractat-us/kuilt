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
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

private val RACE_TEST_HEARTBEAT = HeartbeatConfig(
    interval = 50.milliseconds,
    timeout = 100.milliseconds,
    reconnectWindow = 200.milliseconds,
)

private val RACE_TEST_QUILTER_CONFIG = QuilterConfig(
    antiEntropyInterval = 100.milliseconds,
    fullStateRetryInterval = 150.milliseconds,
    expectVirtualTime = true,
)

/**
 * Of two ring rebuilds, the one that read the effective roster later is the one that stands
 * (#3680). Every roster change and every detector event rebuilds A's ring, each from its own
 * coroutine. A rebuild reads `rosterPeers - partitionedPeers`, builds a ring, then publishes it. On
 * a multi-threaded dispatcher a rebuild can stall between the read and the publish while a newer
 * one runs to the end; the older ring must not then overwrite the newer.
 *
 * On one thread the read and the publish run back to back, so nothing gets between them.
 * [WarpNode.parkNextRingRebuildForTest] parks one rebuild in that gap and runs the second inside
 * it, so these tests reach the interleaving on every target, in virtual time.
 *
 * Each test ends with a control: the second event alone, unparked, moves the ring as it should. So
 * a ring left where the newer rebuild put it is the guard choosing the newer read, not a seam that
 * reaches nothing. The wiring is [WarpNodeStaleDetectorEventTest]'s.
 */
class WarpNodeRingRebuildRaceTest {

    /**
     * B is reported unresponsive, then recovered, and the unresponsive rebuild publishes last.
     * Its ring holds B out while `partitionedPeers` has B back, and nothing rebuilds it: A keeps
     * B's share of the work, and B's own ring hands that share to B.
     */
    @Test
    fun aRecoveryRebuiltDuringAStalledPartitionRebuildKeepsTheRecoveredPeerInTheRing() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val mesh = twoNodes()
            val (nodeA, b, current) = mesh

            var heldOutWhenParked = false
            var ringAfterInner: Set<PeerId> = emptySet()
            var parked = false
            nodeA.parkNextRingRebuildForTest {
                parked = true
                heldOutWhenParked = b !in nodeA.eligiblePeers(Affinity.Anywhere)
                nodeA.handlePartitionEventForTest(PartitionEvent.PeerRecovered(b, now()), current)
                ringAfterInner = nodeA.ringPeersForTest()
            }
            nodeA.handlePartitionEventForTest(PartitionEvent.PeerUnresponsive(b, now(), PartitionEvent.Reason.Timeout), current)
            testScheduler.runCurrent()
            val ringAfterRace = nodeA.ringPeersForTest()
            val eligibleAfterRace = nodeA.eligiblePeers(Affinity.Anywhere)

            // Work enqueued after the race: B's share must go to B. The heartbeats keep running
            // throughout, so A's detector sees B alive the whole time and reports nothing.
            val tasks = (1..8).map { TaskId("ring-race-task-$it") }
            val ownedByB = tasks.filterTo(mutableSetOf()) {
                RosterSnapshot(setOf(nodeA.selfId, b)).toTaskRing().owner(it) == b
            }
            tasks.forEach { nodeA.enqueue(it, TaskDescriptor(op = TRACK_OP, args = it.value.encodeToByteArray())) }
            repeat(30) { advanceTimeBy(50.milliseconds) }
            val ranOn = mesh.ranOn()

            nodeA.handlePartitionEventForTest(PartitionEvent.PeerUnresponsive(b, now(), PartitionEvent.Reason.Timeout), current)
            testScheduler.runCurrent()
            val ringAfterControl = nodeA.ringPeersForTest()

            assertAll(
                { assertTrue(parked, "rig: the partition rebuild parked between its read and its publish") },
                { assertTrue(heldOutWhenParked, "rig: the parked rebuild read a roster without B") },
                { assertTrue(b in ringAfterInner, "rig: the recovery rebuild published a ring with B") },
                { assertTrue(b in eligibleAfterRace, "rig: A's roster has B back") },
                { assertTrue(ownedByB.isNotEmpty(), "rig: B owns some of the tasks on the {A, B} ring") },
                { assertTrue(b in ringAfterRace, "the older rebuild publishing last leaves B in A's ring") },
                { assertEquals(tasks.toSet(), ranOn.keys, "every task ran") },
                {
                    assertEquals(
                        ownedByB.associateWith { listOf(b) },
                        ranOn.filterKeys { it in ownedByB },
                        "B's share of the work ran on B, and only there",
                    )
                },
                { assertFalse(b in ringAfterControl, "control: an unparked partition takes B out of A's ring") },
            )
            mesh.close()
        }

    /**
     * B is partitioned, reported recovered, then unresponsive again, and the recovery rebuild
     * publishes last. Its ring puts B back while `partitionedPeers` holds it out, so A hands B's
     * tasks to a peer its detector says is not there.
     */
    @Test
    fun aPartitionRebuiltDuringAStalledRecoveryRebuildKeepsThePartitionedPeerOutOfTheRing() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val mesh = twoNodes()
            val (nodeA, b, current) = mesh

            nodeA.handlePartitionEventForTest(PartitionEvent.PeerUnresponsive(b, now(), PartitionEvent.Reason.Timeout), current)
            testScheduler.runCurrent()
            val heldOutBefore = b !in nodeA.ringPeersForTest()

            var inWhenParked = false
            var ringAfterInner: Set<PeerId> = setOf(b)
            var parked = false
            nodeA.parkNextRingRebuildForTest {
                parked = true
                inWhenParked = b in nodeA.eligiblePeers(Affinity.Anywhere)
                nodeA.handlePartitionEventForTest(PartitionEvent.PeerUnresponsive(b, now(), PartitionEvent.Reason.Timeout), current)
                ringAfterInner = nodeA.ringPeersForTest()
            }
            nodeA.handlePartitionEventForTest(PartitionEvent.PeerRecovered(b, now()), current)
            testScheduler.runCurrent()
            val ringAfterRace = nodeA.ringPeersForTest()
            val eligibleAfterRace = nodeA.eligiblePeers(Affinity.Anywhere)

            nodeA.handlePartitionEventForTest(PartitionEvent.PeerRecovered(b, now()), current)
            testScheduler.runCurrent()
            val ringAfterControl = nodeA.ringPeersForTest()

            assertAll(
                { assertTrue(heldOutBefore, "rig: the first partition took B out of A's ring") },
                { assertTrue(parked, "rig: the recovery rebuild parked between its read and its publish") },
                { assertTrue(inWhenParked, "rig: the parked rebuild read a roster with B") },
                { assertFalse(b in ringAfterInner, "rig: the partition rebuild published a ring without B") },
                { assertFalse(b in eligibleAfterRace, "rig: A's roster holds B out") },
                { assertFalse(b in ringAfterRace, "the older rebuild publishing last leaves B out of A's ring") },
                { assertTrue(b in ringAfterControl, "control: an unparked recovery puts B back in A's ring") },
            )
            mesh.close()
        }

    private data class Mesh(
        val nodeA: WarpNode,
        val b: PeerId,
        val current: Job,
        val nodeB: WarpNode,
        val ranOn: () -> Map<TaskId, List<PeerId>>,
    ) {
        fun close() {
            nodeA.close()
            nodeB.close()
        }
    }

    /** A and B, both running, each with the other in its ring; [Mesh.current] is A's detector for B. */
    private suspend fun TestScope.twoNodes(): Mesh {
        val loom = InMemoryLoom()
        val seamA = loom.host(Pattern("ring-race"))
        val seamB = loom.join(InMemoryTag("b"))
        val a = seamA.selfId
        val b = seamB.selfId

        val ranLock = reentrantLock()
        val ran = mutableMapOf<TaskId, MutableList<PeerId>>()
        fun registry(self: PeerId) = OpRegistry().also { r ->
            r.register(TRACK_OP, Op { args ->
                ranLock.withLock { ran.getOrPut(TaskId(args.decodeToString())) { mutableListOf() }.add(self) }
                args
            })
        }
        fun node(self: PeerId, seam: Seam) = WarpNode(
            selfId = self,
            seam = seam,
            rosterFlow = MutableStateFlow(setOf(a, b)),
            scope = backgroundScope,
            quilterConfig = RACE_TEST_QUILTER_CONFIG,
            clock = { now() },
            heartbeatConfig = RACE_TEST_HEARTBEAT,
            registry = registry(self),
            epoch = 0L,
        )
        val nodeA = node(a, seamA)
        val nodeB = node(b, seamB)

        advanceTimeBy(10.milliseconds)
        val current = assertNotNull(nodeA.detectorJobForTest(b), "rig: A watches B")
        assertAll(
            { assertTrue(current.isActive, "rig: A's detector for B is running") },
            { assertEquals(setOf(a, b), nodeA.ringPeersForTest(), "rig: A's ring holds A and B") },
        )
        return Mesh(nodeA, b, current, nodeB) { ranLock.withLock { ran.mapValues { it.value.toList() } } }
    }

    private fun TestScope.now(): Instant = Instant.fromEpochMilliseconds(testScheduler.currentTime)

    private companion object {
        val TRACK_OP = OpId("ring-race-track")
    }
}
