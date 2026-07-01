/**
 * Tests for the Raft-backed coordinated execution path in [WarpNode].
 *
 * Covers three behaviours introduced in B-2 (#859):
 * 1. A coordinated task proposes to the Raft log BEFORE invoking [coordinatedExecutor].
 * 2. Enqueuing a coordinated task without a [RaftNode] fails loud at enqueue time.
 * 3. Coordinated tasks execute exactly once across a three-node Raft cluster and survive
 *    a Raft leadership failover.
 *
 * The multi-node test uses [raftSimTest] and [MultiNodeRaftSim] per the repo's multi-node
 * test discipline: [StandardTestDispatcher], seeded per-node [Random] (inside the harness),
 * bounded [MultiNodeRaftSim.awaitTrue] — never [advanceUntilIdle]. The WarpNode instances
 * run in [TestScope.backgroundScope] alongside the Raft nodes.
 */
@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    kotlinx.serialization.ExperimentalSerializationApi::class,
)

package us.tractat.kuilt.warp

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.raft.RaftRole
import us.tractat.kuilt.raft.test.FakeRaftNode
import us.tractat.kuilt.raft.test.raftSimTest
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.drainAntiEntropy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Short-cadence config shared by all tests in this file. */
private val RAFT_SIM_QUILTER_CONFIG = QuilterConfig(
    antiEntropyInterval = 100.milliseconds,
    fullStateRetryInterval = 150.milliseconds,
    expectVirtualTime = true,
)

private fun schedulerClock(scheduler: TestCoroutineScheduler): () -> Instant =
    { Instant.fromEpochMilliseconds(scheduler.currentTime) }

/**
 * Advances virtual time through five anti-entropy intervals then the settle window —
 * the same drain used by [WarpNodeTest] and [WarpNodeCoordinationTagTest].
 */
private fun TestScope.drainWarp() =
    drainAntiEntropy(
        RAFT_SIM_QUILTER_CONFIG.antiEntropyInterval,
        rounds = 5,
        settleWindow = ClaimStrategy.DEFAULT_SETTLE_WINDOW,
    )

class WarpNodeCoordinatedRaftSimTest {

    /**
     * A coordinated task's Raft proposal is sent BEFORE [coordinatedExecutor] is invoked,
     * and the proposal encodes the [TaskId] bytes.
     *
     * Proof: we intercept [FakeRaftNode.proposeBehavior] and record the sequence. The order
     * must be `proposed:<id>` then `executed:<id>` — never the reverse.
     */
    @Test
    fun coordinatedTaskProposesToRaftBeforeExecuting() =
        runTest(UnconfinedTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val seam = loom.host(Pattern("raft-unit-test"))
            // Single-node ring: nodeA is the sole peer and therefore owns every task.
            // Using a fixed MutableStateFlow avoids any InMemoryLoom peer-discovery timing.
            val singlePeerRoster = MutableStateFlow<Set<PeerId>>(setOf(seam.selfId))

            val events = mutableListOf<String>()
            val lock = reentrantLock()

            val fakeRaft = FakeRaftNode(initialRole = RaftRole.Leader)
            fakeRaft.proposeBehavior = { command ->
                lock.withLock { events.add("proposed:${command.decodeToString()}") }
                fakeRaft.pushCommitted(command)
            }

            val taskId = TaskId("coord-raft-unit-task")

            val nodeA = WarpNode(
                selfId = seam.selfId,
                seam = seam,
                rosterFlow = singlePeerRoster,
                scope = backgroundScope,
                quilterConfig = RAFT_SIM_QUILTER_CONFIG,
                clock = schedulerClock(testScheduler),
                strategy = ClaimStrategy.Ring,
                registry = OpRegistry().also { it.register(OpId("free"), Op { args -> args }) },
                coordinatedExecutor = { tid ->
                    lock.withLock { events.add("executed:${tid.value}") }
                    "raft-result"
                },
                raftNode = fakeRaft,
            )

            nodeA.enqueue(taskId, CoordinationKind.Coordinated)
            drainWarp()

            val captured = lock.withLock { events.toList() }
            assertAll(
                {
                    assertEquals(
                        "proposed:${taskId.value}",
                        captured.firstOrNull(),
                        "Raft proposal must precede coordinatedExecutor",
                    )
                },
                {
                    assertEquals(
                        "executed:${taskId.value}",
                        captured.getOrNull(1),
                        "coordinatedExecutor called after Raft commit",
                    )
                },
                { assertNotNull(nodeA.results[taskId], "result present after execution") },
            )

            nodeA.close()
        }

    /**
     * [WarpNode.enqueue] with [CoordinationKind.Coordinated] throws [IllegalStateException]
     * immediately when no [raftNode] was supplied — fail-loud, not silent ring downgrade.
     */
    @Test
    fun coordinatedTaskWithoutRaftNodeFailsLoud() =
        runTest(UnconfinedTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val seam = loom.host(Pattern("raft-required-test"))

            val node = WarpNode(
                selfId = seam.selfId,
                seam = seam,
                rosterFlow = seam.rosterSnapshot(),
                scope = backgroundScope,
                quilterConfig = RAFT_SIM_QUILTER_CONFIG,
                clock = schedulerClock(testScheduler),
                registry = OpRegistry().also { it.register(OpId("free"), Op { args -> args }) },
                // raftNode intentionally omitted — must fail loud at enqueue
            )

            assertFailsWith<IllegalStateException>("enqueue(Coordinated) without raftNode must throw") {
                node.enqueue(TaskId("no-raft-task"), CoordinationKind.Coordinated)
            }

            node.close()
        }

    /**
     * Coordinated tasks execute exactly once in a three-node Raft cluster, including
     * after a Raft leadership failover.
     *
     * Phase 1: elect leader, enqueue 3 coordinated tasks (one per warp node), verify
     * all 3 execute exactly once.
     *
     * Phase 2: partition the Raft leader, elect a replacement, heal, wait for the
     * old leader to step down, then enqueue 3 more tasks. Verify the 6-total execution
     * count — no task executes twice.
     *
     * Multi-node Raft discipline:
     * - [raftSimTest] provides [StandardTestDispatcher] + 5 s timeout.
     * - [MultiNodeRaftSim] handles per-node seeded [Random], backgroundScope child scopes,
     *   and the bounded [MultiNodeRaftSim.awaitLeader] / [MultiNodeRaftSim.awaitTrue] helpers.
     * - [ClaimStrategy.Ring] on each [WarpNode] eliminates the settle-window delay so the
     *   test runs entirely in bounded virtual-time ticks.
     * - [MultiNodeRaftSim.checkInvariants] asserts election safety and state-machine safety
     *   after each phase.
     */
    @Test
    fun coordinatedTaskExecutesExactlyOnceAcrossLeaderFailover() = raftSimTest(n = 3) { sim ->
        val loom = InMemoryLoom()
        val seams = listOf(
            loom.host(Pattern("warp-raft-failover")),
            loom.join(InMemoryTag("wrf-b")),
            loom.join(InMemoryTag("wrf-c")),
        )

        var executionCount = 0
        val executionLock = reentrantLock()

        val warpNodes = sim.nodeIds.mapIndexed { i, nodeId ->
            WarpNode(
                selfId = seams[i].selfId,
                seam = seams[i],
                rosterFlow = seams[i].rosterSnapshot(),
                scope = backgroundScope,
                quilterConfig = RAFT_SIM_QUILTER_CONFIG,
                clock = { Instant.fromEpochMilliseconds(testScheduler.currentTime) },
                strategy = ClaimStrategy.Ring,
                registry = OpRegistry().also { it.register(OpId("free"), Op { args -> args }) },
                coordinatedExecutor = { _ ->
                    executionLock.withLock { executionCount++ }
                    "raft-coordinated"
                },
                raftNode = sim.nodes[nodeId]!!,
            )
        }

        // Let initial WarpNode roster flows settle before proceeding.
        sim.settle()

        // ── Phase 1: normal operation ────────────────────────────────────────────
        sim.awaitLeader()
        val initialLeaderId = sim.nodeIds.first { sim.nodes[it]!!.role.value is RaftRole.Leader }

        val phase1Tasks = (1..3).map { TaskId("p1-$it") }
        phase1Tasks.forEachIndexed { i, task ->
            warpNodes[i].enqueue(task, CoordinationKind.Coordinated)
        }

        sim.awaitTrue("phase-1 executions complete (3)", within = 4.seconds) {
            executionLock.withLock { executionCount } == 3
        }
        sim.checkInvariants()

        // ── Phase 2: Raft leader failover ────────────────────────────────────────
        val survivors = sim.nodeIds.filter { it != initialLeaderId }.toSet()
        sim.partitionOff(initialLeaderId)
        sim.awaitLeader(among = survivors)
        sim.heal()
        // Wait for the old leader to step down before proposing again, so every
        // propose() call forwards to the new leader rather than hanging on the stale one.
        sim.awaitRole(initialLeaderId, RaftRole.Follower)

        val phase2Tasks = (1..3).map { TaskId("p2-$it") }
        phase2Tasks.forEachIndexed { i, task ->
            warpNodes[i].enqueue(task, CoordinationKind.Coordinated)
        }

        sim.awaitTrue("phase-2 executions complete (6 total)", within = 4.seconds) {
            executionLock.withLock { executionCount } == 6
        }
        sim.checkInvariants()

        warpNodes.forEach { it.close() }
    }
}
