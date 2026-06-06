/**
 * # TestDispatcher contract for raft tests
 *
 * Every raft test in this suite that constructs a real [RaftNode] (via [raftSim]
 * or by calling `nodeScope.raftNode(...)` directly) runs under
 * `UnconfinedTestDispatcher()`. This is **load-bearing**: the engine uses real-clock
 * `delay()` for elections and heartbeats; `UnconfinedTestDispatcher` runs
 * continuations eagerly but does NOT install virtual time, so those `delay()` calls
 * elapse normally on the wall clock. With [FAST_RAFT_CONFIG]'s single-digit-ms
 * timings, the cluster converges in milliseconds.
 *
 * **DO NOT** switch to `StandardTestDispatcher()` or add `testScheduler.advanceTimeBy(...)`
 * to "speed up" a slow test. Under `StandardTestDispatcher` (without explicit
 * `advanceTimeBy`), `delay()` virtual-time-waits forever and the engine deadlocks
 * silently. The lone exception is tests that use `StandardTestDispatcher` +
 * `advanceTimeBy` *intentionally* to drive the real-clock election path deterministically.
 *
 * [FAST_RAFT_CONFIG] sets `expectVirtualTime = true` to suppress the TestDispatcher
 * warning across the whole suite. If you introduce a new test fixture, mirror this
 * config — or migrate away from real [RaftNode] per issue #186.
 */
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

/** Bundles a single-voter [RaftNode] with its injectable [storage] for compaction tests. */
internal class SingleVoterHarness(val node: RaftNode, val storage: InMemoryRaftStorage)

/**
 * Builds a single-voter [RaftNode] backed by [InMemoryRaftNetwork] — the same transport
 * already used by all multi-node tests — and returns a [SingleVoterHarness] that exposes
 * both the node and its [storage].
 *
 * [storage] is injectable so Task 5's recovery test can pre-load a persisted snapshot + log.
 */
internal fun singleVoterNode(
    scope: CoroutineScope,
    storage: InMemoryRaftStorage = InMemoryRaftStorage(),
): SingleVoterHarness {
    val self = NodeId("solo")
    val cluster = ClusterConfig(voters = setOf(self))
    val network = InMemoryRaftNetwork()
    val transport = network.transport(self)
    val node = scope.raftNode(cluster, transport, storage, FAST_RAFT_CONFIG)
    return SingleVoterHarness(node, storage)
}

/** Fast timings for deterministic tests — elections fire in single-digit ms. */
internal val FAST_RAFT_CONFIG = RaftConfig(
    electionTimeoutMin = 5.milliseconds,
    electionTimeoutMax = 10.milliseconds,
    heartbeatInterval = 2.milliseconds,
    expectVirtualTime = true,  // see banner above — raft tests use UnconfinedTestDispatcher intentionally
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
    maxPayloadBytes: Int? = null,
): RaftSimulation {
    val ids = (1..n).map { NodeId("v$it") }
    val cluster = ClusterConfig(voters = ids.toSet())
    return RaftSimulation(
        nodeIds = ids,
        scope = scope,
        raftConfig = config,
        nodeScope = nodeScope,
        maxPayloadBytes = maxPayloadBytes,
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
