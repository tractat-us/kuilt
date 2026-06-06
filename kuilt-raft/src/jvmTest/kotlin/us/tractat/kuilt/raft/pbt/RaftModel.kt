@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft.pbt

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import us.tractat.kuilt.raft.ClusterConfig
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftConfig
import us.tractat.kuilt.raft.RaftNode
import us.tractat.kuilt.raft.RaftSimulation
import us.tractat.kuilt.raft.raftNode
import kotlin.time.Duration.Companion.milliseconds

/**
 * Fast timings: elections fire in 50–100 ms virtual time, heartbeat at 10 ms.
 * One advanceUntilLeader() pass covers at most ~300 ms virtual time — zero wall-clock cost.
 */
internal val PBT_CONFIG = RaftConfig(
    electionTimeoutMin = 50.milliseconds,
    electionTimeoutMax = 100.milliseconds,
    heartbeatInterval = 10.milliseconds,
)

/**
 * Virtual-time Raft simulation model for jqwik stateful property tests.
 *
 * All timing inside [RaftSimulation] is driven by [scheduler] — no real-clock
 * waits occur. jqwik actions call [advanceTimeBy] or [advanceUntilIdle] to move
 * virtual time forward instantly, making each property run near-instantaneous.
 */
internal class RaftModel(val clusterSize: Int) {

    val scheduler = TestCoroutineScheduler()
    // UnconfinedTestDispatcher dispatches resumed continuations eagerly on the calling thread,
    // so scheduler.advanceTimeBy() from a jqwik action drives Raft coroutines inline.
    val dispatcher = UnconfinedTestDispatcher(scheduler)
    private val testScope = TestScope(dispatcher)

    val sim: RaftSimulation = buildSim()

    /**
     * True while a network partition is active.
     * Invariant checks that assert at-most-one-leader are suppressed during partitions
     * because Raft permits an old-term minority leader and a new-term majority leader
     * to coexist — this does not violate the per-term election-safety guarantee.
     */
    var partitioned: Boolean = false

    private fun buildSim(): RaftSimulation {
        val ids = (1..clusterSize).map { NodeId("n$it") }
        val cluster = ClusterConfig(voters = ids.toSet())
        return RaftSimulation(
            nodeIds = ids,
            scope = testScope,
            raftConfig = PBT_CONFIG,
            nodeScope = testScope.backgroundScope,
            nodeFactory = { _, transport, storage, nodeScope ->
                CoroutineScope(nodeScope.coroutineContext + Job(nodeScope.coroutineContext[Job]))
                    .raftNode(cluster, transport, storage, PBT_CONFIG)
            },
        )
    }

    /** Advance virtual time by [ms] milliseconds and drain pending coroutines. */
    fun advanceTimeBy(ms: Long) {
        scheduler.advanceTimeBy(ms)
        scheduler.runCurrent()
    }

    /** Drain all coroutines pending at the current virtual timestamp. */
    fun advanceUntilIdle() = scheduler.advanceUntilIdle()

    /**
     * Advance virtual time until a leader is elected, or give up after several election windows.
     *
     * Returns the leader node, or `null` if no leader emerges (e.g. cluster is partitioned
     * or has lost quorum). Zero real-clock cost — all advancement is virtual.
     */
    fun advanceUntilLeader(): RaftNode? {
        val electionWindowMs = PBT_CONFIG.electionTimeoutMax.inWholeMilliseconds
        val heartbeatMs = PBT_CONFIG.heartbeatInterval.inWholeMilliseconds
        repeat(5) {
            scheduler.advanceTimeBy(electionWindowMs + heartbeatMs * 3)
            scheduler.runCurrent()
            sim.leader()?.let { return it }
        }
        return null
    }

    fun cancel() = testScope.cancel()
}
