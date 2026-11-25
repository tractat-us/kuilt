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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

private val RECOVERY_TEST_HEARTBEAT = HeartbeatConfig(
    interval = 50.milliseconds,
    timeout = 100.milliseconds,
    reconnectWindow = 200.milliseconds,
)

private val RECOVERY_TEST_QUILTER_CONFIG = QuilterConfig(
    antiEntropyInterval = 100.milliseconds,
    fullStateRetryInterval = 150.milliseconds,
    expectVirtualTime = true,
)

/**
 * A peer that recovers takes its share of the work back (#3680). A's detector for B reports B
 * unresponsive, then recovered; each event rebuilds A's ring. Work enqueued while B is out runs on
 * A; work enqueued after B is back runs on B, and only there.
 *
 * The events are handed to A through [WarpNode.handlePartitionEventForTest] from A's current
 * detector, so they arrive in a known order while the heartbeats keep running and B stays alive.
 * The wiring is [WarpNodeStaleDetectorEventTest]'s.
 *
 * #3680 was two such rebuilds overlapping on two threads, the older ring publishing last; this
 * test runs them one after the other and cannot reach that. What closes the race is that
 * `rebuildRingAndClaim` reads the roster and publishes the ring in one critical section.
 */
class WarpNodePartitionRecoveryTest {

    @Test
    fun aPeerThatRecoversRunsItsShareOfTheWorkAgain() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val mesh = twoNodes()
            val (nodeA, b, current) = mesh
            val bothRing = RosterSnapshot(setOf(nodeA.selfId, b)).toTaskRing()

            nodeA.handlePartitionEventForTest(PartitionEvent.PeerUnresponsive(b, now(), PartitionEvent.Reason.Timeout), current)
            testScheduler.runCurrent()
            val whileOut = (1..8).map { TaskId("recovery-out-$it") }
            val whileOutOwnedByB = whileOut.filterTo(mutableSetOf()) { bothRing.owner(it) == b }
            whileOut.forEach { nodeA.enqueue(it, it.descriptor()) }
            repeat(30) { advanceTimeBy(50.milliseconds) }

            nodeA.handlePartitionEventForTest(PartitionEvent.PeerRecovered(b, now()), current)
            testScheduler.runCurrent()
            val afterBack = (1..8).map { TaskId("recovery-back-$it") }
            val afterBackOwnedByB = afterBack.filterTo(mutableSetOf()) { bothRing.owner(it) == b }
            afterBack.forEach { nodeA.enqueue(it, it.descriptor()) }
            repeat(30) { advanceTimeBy(50.milliseconds) }
            val ranOn = mesh.ranOn()

            assertAll(
                { assertTrue(whileOutOwnedByB.isNotEmpty(), "rig: B owns some of the first batch on the {A, B} ring") },
                { assertTrue(afterBackOwnedByB.isNotEmpty(), "rig: B owns some of the second batch on the {A, B} ring") },
                { assertEquals((whileOut + afterBack).toSet(), ranOn.keys, "every task ran") },
                {
                    assertEquals(
                        whileOutOwnedByB.associateWith { listOf(nodeA.selfId) },
                        ranOn.filterKeys { it in whileOutOwnedByB },
                        "rig: while B is out, its share of the work runs on A",
                    )
                },
                {
                    assertEquals(
                        afterBackOwnedByB.associateWith { listOf(b) },
                        ranOn.filterKeys { it in afterBackOwnedByB },
                        "once B is back, its share of the work runs on B, and only there",
                    )
                },
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

    /** A and B, both running, each with the other on its roster; [Mesh.current] is A's detector for B. */
    private suspend fun TestScope.twoNodes(): Mesh {
        val loom = InMemoryLoom()
        val seamA = loom.host(Pattern("partition-recovery"))
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
            quilterConfig = RECOVERY_TEST_QUILTER_CONFIG,
            clock = { now() },
            heartbeatConfig = RECOVERY_TEST_HEARTBEAT,
            registry = registry(self),
            epoch = 0L,
        )
        val nodeA = node(a, seamA)
        val nodeB = node(b, seamB)

        advanceTimeBy(10.milliseconds)
        val current = assertNotNull(nodeA.detectorJobForTest(b), "rig: A watches B")
        assertTrue(current.isActive, "rig: A's detector for B is running")
        return Mesh(nodeA, b, current, nodeB) { ranLock.withLock { ran.mapValues { it.value.toList() } } }
    }

    private fun TaskId.descriptor() = TaskDescriptor(op = TRACK_OP, args = value.encodeToByteArray())

    private fun TestScope.now(): Instant = Instant.fromEpochMilliseconds(testScheduler.currentTime)

    private companion object {
        val TRACK_OP = OpId("partition-recovery-track")
    }
}
