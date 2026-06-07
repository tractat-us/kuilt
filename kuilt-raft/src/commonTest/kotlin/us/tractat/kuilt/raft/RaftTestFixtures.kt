@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Run a `:kuilt-raft` consensus test under [UnconfinedTestDispatcher] with a **tight 5-second
 * default timeout**.
 *
 * Consensus tests drive a real [RaftNode] (real-clock [kotlinx.coroutines.delay] for elections
 * and heartbeats) under virtual time. A non-converging cluster keeps the heartbeat loop scheduling
 * work, so `runTest` never auto-idles — without a tight timeout the only backstop is the 60s default,
 * which surfaces as an opaque failure with zero state. This wrapper caps the wait at [timeout] and,
 * paired with [RaftSimulation]'s bounded await helpers, guarantees a fast, diagnosable failure
 * instead of a hang.
 */
internal fun raftRunTest(
    timeout: Duration = 5.seconds,
    body: suspend TestScope.() -> Unit,
): TestResult = runTest(UnconfinedTestDispatcher(), timeout = timeout, testBody = body)

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

/**
 * Suspend until some node in [sim] is leader; fail fast with a full state dump otherwise.
 *
 * Thin delegator to [RaftSimulation.awaitLeader] — the single bounded, dump-on-timeout
 * implementation. Kept as a free function so existing `awaitLeader(sim)` call sites need
 * no change.
 */
internal suspend fun awaitLeader(sim: RaftSimulation): RaftNode = sim.awaitLeader()
