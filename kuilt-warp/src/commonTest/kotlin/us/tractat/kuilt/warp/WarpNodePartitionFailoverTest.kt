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
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.liveness.HeartbeatPartitionDetector
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

            val partitionOpId = OpId("partition-track")

            fun trackRegistry(selfId: PeerId): OpRegistry = OpRegistry().also { r ->
                r.register(partitionOpId, Op { args ->
                    val taskId = TaskId(args.decodeToString())
                    executedLock.withLock { executedBy.getOrPut(taskId) { mutableListOf() }.add(selfId) }
                    args
                })
            }

            fun TaskId.descriptor() = TaskDescriptor(op = partitionOpId, args = value.encodeToByteArray())

            val nodeA = WarpNode(
                selfId = seamA.selfId,
                seam = seamA,
                rosterFlow = rosterFlow,
                scope = backgroundScope,
                quilterConfig = PARTITION_TEST_QUILTER_CONFIG,
                clock = clock.asClock(),
                heartbeatConfig = FAST_HEARTBEAT,
                registry = trackRegistry(seamA.selfId),
            )
            val nodeB = WarpNode(
                selfId = seamB.selfId,
                seam = seamB,
                rosterFlow = rosterFlow,
                scope = backgroundScope,
                quilterConfig = PARTITION_TEST_QUILTER_CONFIG,
                clock = clock.asClock(),
                heartbeatConfig = FAST_HEARTBEAT,
                registry = trackRegistry(seamB.selfId),
            )
            val nodeC = WarpNode(
                selfId = seamC.selfId,
                seam = seamC,
                rosterFlow = rosterFlow,
                scope = backgroundScope,
                quilterConfig = PARTITION_TEST_QUILTER_CONFIG,
                clock = clock.asClock(),
                heartbeatConfig = FAST_HEARTBEAT,
                registry = trackRegistry(seamC.selfId),
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
            tasks.forEach { nodeA.enqueue(it, it.descriptor()) }

            // Advance to let CRDT replication and task execution complete.
            // With RingWithIntent, tasks enqueued shortly after a ring change settle for
            // settleWindow (500 ms) before executing. Allow 700 ms total (14 × 50 ms) so
            // the settle delay plus CRDT replication both complete within the test window.
            repeat(14) { this.advanceTimeByAndClock(50.milliseconds, clock) }

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

    /**
     * Detectors for departed peers must be fully stopped — no ping loop leaking.
     *
     * Scenario:
     * - Two-peer mesh: A and B. Roster = {A, B}.
     * - B appears → detector starts, pings B.
     * - B departs (roster drops to {A}).
     * - Advance past one more heartbeat interval.
     * - Assert no additional pings were sent to B after its departure.
     *
     * Without the child-scope fix, `detector.start(scope)` launches the ping loop as
     * a sibling of `scope`; cancelling only the events-collector job leaves the ping
     * loop alive. Under virtual time this is directly observable: the ping count for B
     * keeps incrementing after departure.
     *
     * The test uses a [CountingSeam] that records every `sendTo(B, ...)` call. We
     * measure the count at the moment B departs, advance virtual time by one more
     * interval, and assert the count did not increase.
     */
    @Test
    fun departedPeerDetectorIsFullyStopped_noPingLeak() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val seamA = loom.host(Pattern("churn-test"))
            val seamB = loom.join(InMemoryTag("b"))

            val clock = VirtualClock()
            val rosterFlow = MutableStateFlow(setOf(seamA.selfId, seamB.selfId))

            // Intercept sends from nodeA to B so we can count pings.
            val pingCountLock = reentrantLock()
            var pingsToB = 0
            val countingSeam = CountingSeam(seamA, seamB.selfId) { pingCountLock.withLock { pingsToB++ } }

            val nodeA = WarpNode(
                selfId = seamA.selfId,
                seam = countingSeam,
                rosterFlow = rosterFlow,
                scope = backgroundScope,
                quilterConfig = PARTITION_TEST_QUILTER_CONFIG,
                clock = clock.asClock(),
                heartbeatConfig = FAST_HEARTBEAT,
                registry = OpRegistry().also { it.register(OpId("ping-test"), Op { args -> args }) },
            )

            // Let the detector start and fire at least one ping interval.
            repeat(3) { this.advanceTimeByAndClock(50.milliseconds, clock) }

            val pingsBeforeDeparture = pingCountLock.withLock { pingsToB }
            assertTrue(pingsBeforeDeparture >= 1, "detector must have sent at least one ping to B before departure")

            // B departs from the roster — detector for B should be fully cancelled.
            rosterFlow.value = setOf(seamA.selfId)

            // One tick to let the departure be processed (onPeersChanged runs, scope cancelled).
            // There may be one in-flight ping from this same tick (the ping loop's delay may
            // fire at the same virtual instant as the roster update); that's acceptable — what
            // must NOT happen is additional pings in subsequent ticks.
            this.advanceTimeByAndClock(50.milliseconds, clock)

            // Snapshot AFTER the departure tick — any same-tick in-flight ping is included.
            val pingsAfterDepartureTick = pingCountLock.withLock { pingsToB }

            // Advance past two more full heartbeat intervals — no further pings must fire.
            repeat(4) { this.advanceTimeByAndClock(50.milliseconds, clock) }

            val pingsAfterAdditionalTicks = pingCountLock.withLock { pingsToB }

            assertEquals(
                pingsAfterDepartureTick,
                pingsAfterAdditionalTicks,
                "ping count must not increase after departure tick — detector ping loop must be stopped",
            )

            nodeA.close()
        }

    private fun TestScope.advanceTimeByAndClock(by: Duration, clock: VirtualClock) {
        clock.advance(by)
        advanceTimeBy(by)
    }
}

/**
 * A thin [Seam] wrapper that intercepts [sendTo] calls carrying heartbeat ping frames
 * to [watchedPeerId] and invokes [onSendTo] for each one.
 *
 * Only frames whose payload starts with [HeartbeatPartitionDetector.PING_PREFIX] are
 * counted. Quilter anti-entropy messages, MuxSeam channel frames, and pong replies are
 * ignored so the counter reflects only the ping loop's activity — the observable we care
 * about in the churn test.
 *
 * All [Seam] members delegate to [delegate] unchanged.
 */
private class CountingSeam(
    private val delegate: Seam,
    private val watchedPeerId: PeerId,
    private val onPing: () -> Unit,
) : Seam by delegate {
    override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
        if (peer == watchedPeerId && payload.decodeToString().startsWith(HeartbeatPartitionDetector.PING_PREFIX)) {
            onPing()
        }
        delegate.sendTo(peer, payload)
    }
}
