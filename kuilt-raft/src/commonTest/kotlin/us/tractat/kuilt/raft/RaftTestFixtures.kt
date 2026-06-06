@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/** Fast timings for deterministic tests — elections fire in single-digit ms. */
internal val FAST_RAFT_CONFIG = RaftConfig(
    electionTimeoutMin = 5.milliseconds,
    electionTimeoutMax = 10.milliseconds,
    heartbeatInterval = 2.milliseconds,
)

/**
 * Build a [RaftSimulation] of [n] voters wired with [config].
 *
 * [scope] is the simulation's outer scope (pass the test's `TestScope`);
 * [nodeScope] hosts the node coroutines (pass `backgroundScope` so the infinite
 * election/heartbeat loops are cancelled when the test body finishes without
 * causing [kotlinx.coroutines.test.UncompletedCoroutinesError]).
 */
internal fun raftSim(
    scope: CoroutineScope,
    nodeScope: CoroutineScope,
    n: Int = 3,
    config: RaftConfig = FAST_RAFT_CONFIG,
): RaftSimulation {
    val ids = (1..n).map { NodeId("v$it") }
    val cluster = ClusterConfig(voters = ids.toSet())
    return RaftSimulation(
        nodeIds = ids,
        scope = scope,
        raftConfig = config,
        nodeScope = nodeScope,
        nodeFactory = { _, transport, storage, childScope ->
            childScope.raftNode(cluster, transport, storage, config)
        },
    )
}

/** Poll until some node in [sim] is leader; fail after ~500 ms. */
internal suspend fun awaitLeader(sim: RaftSimulation): RaftNode {
    repeat(500) {
        sim.leader()?.let { return it }
        delay(1)
    }
    error("No leader elected within timeout")
}
