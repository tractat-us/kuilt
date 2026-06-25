/**
 * Partition-driven failover tests for [WarpNode].
 *
 * Uses [StandardTestDispatcher] (FIFO at each virtual instant) because per-peer
 * [us.tractat.kuilt.liveness.HeartbeatPartitionDetector]s re-arm heartbeat timers forever —
 * [advanceUntilIdle] would spin indefinitely. Virtual time is driven with bounded
 * [advanceTimeBy] steps and tight [runTest] timeouts (5 s).
 *
 * Clock injection: a virtual epoch counter advanced in lockstep with coroutine virtual time.
 * Each [advanceTimeByAndClock] call advances both the coroutine scheduler and the injected
 * [Instant] clock by the same [Duration].
 */
@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    kotlinx.serialization.ExperimentalSerializationApi::class,
)

package us.tractat.kuilt.warp

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Short-cadence [HeartbeatConfig] for partition tests: fast timeouts so virtual-time
 * advances stay short and bounded.
 *
 * interval = 50 ms → sends a ping every 50 ms of virtual time.
 * timeout = 100 ms → peer declared unresponsive after 100 ms of silence.
 * reconnectWindow = 200 ms → peer declared lost after 200 ms in unresponsive state.
 */
private val FAST_HEARTBEAT = HeartbeatConfig(
    interval = 50.milliseconds,
    timeout = 100.milliseconds,
    reconnectWindow = 200.milliseconds,
)

/** Short-cadence [QuilterConfig] for partition tests. */
private val PARTITION_TEST_QUILTER_CONFIG = QuilterConfig(
    antiEntropyInterval = 100.milliseconds,
    fullStateRetryInterval = 150.milliseconds,
    expectVirtualTime = true,
)

/** A virtual clock whose epoch advances in lockstep with [advanceTimeBy] calls. */
private class VirtualClock {
    private val lock = reentrantLock()
    private var epochMs = 0L

    fun now(): Instant = lock.withLock { Instant.fromEpochMilliseconds(epochMs) }

    fun advance(by: Duration) {
        lock.withLock { epochMs += by.inWholeMilliseconds }
    }

    fun asClock(): () -> Instant = ::now
}

class WarpNodePartitionFailoverTest {

    /**
     * A partitioned-but-not-departed peer's tasks re-home to the successor.
     *
     * Scenario:
     * - Three-peer mesh: A, B, C. Roster = {A, B, C} throughout.
     * - B's seam is closed (transport died), but the roster is NOT updated —
     *   this is exactly the case where partition detection is required: the peer
     *   vanished without a formal departure signal.
     * - Virtual time advances past B's heartbeat timeout + reconnect window so the
     *   detector fires [PartitionEvent.PeerUnresponsive] then [PartitionEvent.PeerLost]
     *   on A and C.
     * - Tasks are enqueued after the partition is detected.
     * - Expected: A and C execute all tasks (B is excluded from the effective ring).
     *   Results board converges to exactly one entry per task.
     */
    @Test
    fun partitionedPeerTasksReHomeToSuccessor() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val seamA = loom.host(Pattern("partition-failover"))
            val seamB = loom.join(InMemoryTag("b"))
            val seamC = loom.join(InMemoryTag("c"))

            val clock = VirtualClock()

            // Shared roster: all three peers. B is NOT removed after partition —
            // heartbeat-based detection must trigger the re-homing.
            val rosterFlow = MutableStateFlow(setOf(seamA.selfId, seamB.selfId, seamC.selfId))

            val executedLock = reentrantLock()
            val executedBy = mutableMapOf<TaskId, MutableList<PeerId>>()

            fun trackExecutor(selfId: PeerId): suspend (TaskId) -> String = { taskId ->
                executedLock.withLock { executedBy.getOrPut(taskId) { mutableListOf() }.add(selfId) }
                "done-${selfId.value}-${taskId.value}"
            }

            val nodeA = WarpNode(
                selfId = seamA.selfId,
                seam = seamA,
                rosterFlow = rosterFlow,
                scope = backgroundScope,
                quilterConfig = PARTITION_TEST_QUILTER_CONFIG,
                clock = clock.asClock(),
                heartbeatConfig = FAST_HEARTBEAT,
                executor = trackExecutor(seamA.selfId),
            )
            val nodeB = WarpNode(
                selfId = seamB.selfId,
                seam = seamB,
                rosterFlow = rosterFlow,
                scope = backgroundScope,
                quilterConfig = PARTITION_TEST_QUILTER_CONFIG,
                clock = clock.asClock(),
                heartbeatConfig = FAST_HEARTBEAT,
                executor = trackExecutor(seamB.selfId),
            )
            val nodeC = WarpNode(
                selfId = seamC.selfId,
                seam = seamC,
                rosterFlow = rosterFlow,
                scope = backgroundScope,
                quilterConfig = PARTITION_TEST_QUILTER_CONFIG,
                clock = clock.asClock(),
                heartbeatConfig = FAST_HEARTBEAT,
                executor = trackExecutor(seamC.selfId),
            )

            // Let the mesh stabilise — tick forward so detectors start.
            this.advanceTimeByAndClock(10.milliseconds, clock)

            // Partition B: close its transport. Roster stays as {A, B, C}.
            seamB.close()

            // Advance past timeout (100 ms) + reconnectWindow (200 ms) = 300 ms.
            // Drive in 50 ms steps (the heartbeat interval) so the loop ticks correctly.
            repeat(8) { this.advanceTimeByAndClock(50.milliseconds, clock) }
            // Total: 10 + 8×50 = 410 ms — past the full reconnect window.

            // Enqueue tasks. Effective ring = {A, C} (B is partitioned/lost).
            val tasks = (1..6).map { TaskId("partition-task-$it") }
            tasks.forEach { nodeA.enqueue(it) }

            // Advance to let CRDT replication and task execution complete.
            repeat(10) { this.advanceTimeByAndClock(50.milliseconds, clock) }

            val taskIds = executedLock.withLock { executedBy.keys.toSet() }
            val resultsA = nodeA.results

            assertAll(
                {
                    assertEquals(
                        tasks.toSet(),
                        taskIds,
                        "all tasks must be executed by A or C after B is partition-detected",
                    )
                },
                {
                    assertEquals(
                        tasks.toSet(),
                        resultsA.taskIds,
                        "nodeA results board must converge to all tasks",
                    )
                },
                {
                    tasks.forEach { taskId ->
                        val bExecuted = executedLock.withLock {
                            executedBy[taskId]?.any { it == seamB.selfId } ?: false
                        }
                        assertTrue(
                            !bExecuted,
                            "partitioned peer B must not execute any tasks after detection",
                        )
                    }
                },
            )

            nodeA.close()
            nodeB.close()
            nodeC.close()
        }

    private fun TestScope.advanceTimeByAndClock(by: Duration, clock: VirtualClock) {
        clock.advance(by)
        advanceTimeBy(by)
    }
}
