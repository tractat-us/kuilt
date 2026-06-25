/**
 * B-4: Coordinated-path duplicate-execution rate under warp-roster churn.
 *
 * Measures the per-task execution count when the warp ring/roster changes while a
 * coordinated proposal is in flight — the scenario where two ring owners independently
 * propose the same task to Raft and both commits arrive in the log.
 *
 * **Expected with unfixed code:** dup-rate > 0 — the original ring owner and the
 * new ring owner each call [WarpNode.coordinatedExecutor] after their respective
 * [RaftNode.propose] calls return, producing two executions for one task.
 *
 * **Required after B-4 fix:** dup-rate = 0 — execution is driven from the committed
 * log and only the current Raft leader fires [coordinatedExecutor]; the [coordinatedApplied]
 * set prevents a second committed entry for the same task from re-executing it.
 *
 * Multi-node Raft discipline (per CLAUDE.md):
 * - [raftSimTest] → [StandardTestDispatcher], 5 s timeout.
 * - [MultiNodeRaftSim] seeded per-node [Random], backgroundScope child scopes.
 * - Bounded [MultiNodeRaftSim.awaitTrue] — never [advanceUntilIdle].
 * - [ClaimStrategy.Ring] so claiming fires immediately without a settle window.
 * - Manually-controlled [MutableStateFlow] rosterFlows drive warp-ring churn without
 *   disconnecting the [InMemoryLoom] seam (which must stay connected for CRDT replication
 *   and the Raft-backed coordinated path to function).
 */
@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    kotlinx.serialization.ExperimentalSerializationApi::class,
)

package us.tractat.kuilt.warp

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.raft.test.raftSimTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Short-cadence Quilter config — anti-entropy every 50 ms so CRDT state propagates quickly. */
private val CHURN_SIM_QUILTER_CONFIG = QuilterConfig(
    antiEntropyInterval = 50.milliseconds,
    fullStateRetryInterval = 75.milliseconds,
    expectVirtualTime = true,
)

class WarpNodeCoordinatedChurnSimTest {

    /**
     * Measures the coordinated-path dup-rate under a single warp-roster churn event.
     *
     * Setup: 3 WarpNodes sharing one InMemoryLoom seam (for CRDT replication) and a
     * 3-node Raft cluster (for coordinated execution ordering). The ring owner of the
     * test task is determined from [TaskRing.owner].
     *
     * Churn schedule: all nodes pre-seed the task in their local coord queues (avoiding
     * a 50 ms anti-entropy delay that would let the original owner finish before the new
     * owner learns of the task). After the ring owner starts its proposal (suspended in
     * [RaftNode.propose]), the rosterFlow for every WarpNode is updated to remove the
     * original owner. The new ring owner fires [claimOwned] and also proposes.
     *
     * Assertion (dup-rate=0): exactly one [coordinatedExecutor] invocation per task,
     * i.e., `executionCount == 1`. With unfixed code, both the original and new owner
     * call [coordinatedExecutor] after their respective [propose] returns → fails.
     * With the B-4 fix, execution is leader-only from the committed log → passes.
     */
    @Test
    fun coordinatedDupRateIsZeroUnderRosterChurn() = raftSimTest(n = 3) { sim ->
        val loom = InMemoryLoom()
        val seams = listOf(
            loom.host(Pattern("churn-dup-sim")),
            loom.join(InMemoryTag("churn-b")),
            loom.join(InMemoryTag("churn-c")),
        )

        val peerIds = seams.map { it.selfId }
        val allPeers = peerIds.toSet()

        // Manually-controlled rosterFlows — one per WarpNode. Changing .value drives
        // ring churn without disconnecting the seam (the seam must stay up for CRDT
        // replication and for Raft transport via MultiNodeRaftNetwork).
        val rosterFlows = peerIds.map { MutableStateFlow<Set<PeerId>>(allPeers) }

        val executionLock = reentrantLock()
        // Maps task → number of times coordinatedExecutor was called for it.
        val executionCounts = mutableMapOf<TaskId, Int>()

        val warpNodes = sim.nodeIds.mapIndexed { i, nodeId ->
            WarpNode(
                selfId = seams[i].selfId,
                seam = seams[i],
                rosterFlow = rosterFlows[i],
                scope = backgroundScope,
                quilterConfig = CHURN_SIM_QUILTER_CONFIG,
                clock = { Instant.fromEpochMilliseconds(testScheduler.currentTime) },
                strategy = ClaimStrategy.Ring,
                executor = { "free" },
                coordinatedExecutor = { taskId ->
                    executionLock.withLock { executionCounts[taskId] = (executionCounts[taskId] ?: 0) + 1 }
                    "coordinated"
                },
                raftNode = sim.nodes[nodeId]!!,
            )
        }

        // Wait for the Raft cluster to elect a leader before proposing.
        sim.awaitLeader()
        // Let WarpNode init subscribers (roster, queue collectors) start running.
        sim.settle()

        // Identify the ring owner of the test task under the full 3-peer roster.
        val task = TaskId("churn-task-1")
        val ring = TaskRing(allPeers)
        val ownerPeer = ring.owner(task)!!
        val ownerIndex = peerIds.indexOf(ownerPeer)

        // ── Pre-seed the task in EVERY WarpNode's local coord queue ──────────────
        // Each node calls enqueue() on its OWN coord queue so no anti-entropy wait
        // is needed for the new ring owner to know about the task at churn time.
        warpNodes.forEach { node -> node.enqueue(task, CoordinationKind.Coordinated) }

        // Under StandardTestDispatcher, settle() runs pending coroutines without advancing
        // the clock. After settle():
        //   • The ring owner has claimed the task (added to claimed set).
        //   • The ring owner's doExecute → executeViaRaft → propose() is SUSPENDED
        //     (waiting for Raft commit, which requires clock advancement).
        //   • Non-owners see the task but do not claim (they are not the ring owner).
        sim.settle()

        // ── Trigger roster churn: remove the ring owner from every node's view ───
        // This simulates the ring owner's seam peer departing (e.g. disconnecting).
        // All WarpNodes rebuild their rings on the next settle, and the new ring owner
        // (the successor on the ring) finds the task in its local coord queue and also
        // claims → also proposes.
        val newRoster = allPeers - ownerPeer
        rosterFlows.forEach { flow -> flow.value = newRoster }

        // settle() triggers onPeersChanged → ring rebuild → new owner claims task →
        // new owner's propose() is now ALSO suspended in flight.
        sim.settle()

        // ── Advance Raft time — both proposals commit, execution fires ──────────
        // With the B-4 fix (leader-only from committed log + coordinatedApplied dedup):
        //   executionCount should reach exactly 1.
        // With unfixed code (coordinatedExecutor called inline after propose() returns):
        //   executionCount reaches 2 (both proposers execute), failing the assertion below.
        sim.awaitTrue("task executes at least once", within = 4.seconds) {
            executionLock.withLock { executionCounts[task] != null }
        }

        // Allow one extra anti-entropy cycle for any concurrent execution to settle.
        repeat(3) { advanceTimeBy(CHURN_SIM_QUILTER_CONFIG.antiEntropyInterval); runCurrent() }

        val executionCount = executionLock.withLock { executionCounts[task] ?: 0 }
        val duplicates = if (executionCount > 1) executionCount - 1 else 0
        assertEquals(
            0,
            duplicates,
            "Coordinated task '$task' must execute exactly once under roster churn " +
                "(got $executionCount executions — dup-rate=${duplicates.toDouble() / executionCount})",
        )
        assertEquals(
            1,
            executionCount,
            "Coordinated task '$task' must execute exactly once (not $executionCount times)",
        )

        warpNodes.forEach { it.close() }
    }
}
