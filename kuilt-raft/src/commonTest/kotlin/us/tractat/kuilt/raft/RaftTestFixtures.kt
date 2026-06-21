/**
 * # Deterministic virtual-time contract for raft tests (issue #383)
 *
 * Every raft test that constructs a real [RaftNode] (via [raftSim] or by calling
 * `nodeScope.raftNode(...)` directly) runs under **[kotlinx.coroutines.test.StandardTestDispatcher]
 * bound to the enclosing `runTest`'s `TestCoroutineScheduler`** — see [raftRunTest]. This makes the
 * suite deterministic regardless of host load.
 *
 * ## `delay()` is already virtual — the engine needs no production change
 *
 * A common misconception (which the previous version of this banner asserted, and which was
 * empirically wrong) is that the engine's election/heartbeat `delay()` calls "elapse on the wall
 * clock" under `runTest`. They do **not**: any `TestDispatcher` constructed with no explicit
 * scheduler binds to the enclosing `runTest`'s `TestCoroutineScheduler`, so every engine `delay()`
 * is virtual — a 5 s delay advances virtual time 5 s and consumes ~0 ms wall. No production timer
 * change is required to test under virtual time.
 *
 * ## The real defect was *ordering*, and `StandardTestDispatcher` fixes it
 *
 * The suite previously ran on `UnconfinedTestDispatcher`, which runs continuations **eagerly
 * inline**. At a single virtual instant the interleaving of an engine timer firing against an
 * in-flight message round-trip then depended on how many continuation steps the CPU happened to
 * take — load-dependent ordering, the exact flake issue #383 reproduced.
 *
 * [StandardTestDispatcher] is **FIFO at each virtual instant** (no eager inline execution), so the
 * ordering of timers vs message round-trips is fixed and reproducible on any machine. That — not a
 * wall-clock change — is the whole fix.
 *
 * ## How to write a deterministic raft test
 *
 * - Wrap the body in [raftRunTest] and build the cluster with [raftSim] (or [singleVoterNode]).
 * - Pass the test `TestScope` as `scope` and `backgroundScope` as `nodeScope`, so the engine's
 *   never-ending election/heartbeat loops are cancelled at teardown without an
 *   [kotlinx.coroutines.test.UncompletedCoroutinesError].
 * - Wait on cluster state **only** through the bounded [RaftSimulation] `await*` helpers (or
 *   [settle]). Each suspends on virtual `delay`, which `runTest` auto-advances — they drive time
 *   for you. Plain `delay(n)` in a body is fine too; it advances virtual time.
 * - **Never call `advanceUntilIdle()`.** The engine never quiesces (heartbeat/election timers
 *   perpetually re-arm), so it would spin forever. The `await*` helpers advance time in bounded
 *   steps and fail fast with a state dump on non-convergence instead.
 * - If you launch a collector into `backgroundScope` and then expect it to observe a *subsequent*
 *   emission, call [RaftSimulation.settle] after the `launch` so the collector actually subscribes
 *   before you produce the event (under FIFO scheduling a launched coroutine does not run until time
 *   advances — this was the lone conversion fix in `CommittedReplayTest`).
 *
 * [FAST_RAFT_CONFIG] sets `expectVirtualTime = true` to suppress the [RaftNode] TestDispatcher
 * guard across the whole suite. If you introduce a new fixture, mirror this config.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Run a `:kuilt-raft` consensus test under [StandardTestDispatcher] (virtual, deterministic time)
 * with a **tight 5-second default timeout**.
 *
 * [StandardTestDispatcher] gives FIFO ordering at each virtual instant — see the banner above for
 * why that, not a wall-clock change, is what makes the suite deterministic. The dispatcher binds to
 * this `runTest`'s `TestCoroutineScheduler`, so the engine's `delay()`-based timers are virtual and
 * the [RaftSimulation] `await*` helpers (which suspend on virtual `delay`) drive time forward as
 * `runTest` auto-advances.
 *
 * The engine never quiesces (heartbeat/election timers perpetually re-arm), so `runTest` never
 * auto-idles — without a tight timeout the only backstop is the 60s default, which surfaces as an
 * opaque failure with zero state. This wrapper caps the wait at [timeout] and, paired with the
 * bounded await helpers, guarantees a fast, diagnosable failure instead of a hang. Never use
 * `advanceUntilIdle()`.
 */
internal fun raftRunTest(
    timeout: Duration = 5.seconds,
    body: suspend TestScope.() -> Unit,
): TestResult = runTest(StandardTestDispatcher(), timeout = timeout, testBody = body)

/** Bundles a single-voter [RaftNode] with its injectable [storage] for compaction tests. */
internal class SingleVoterHarness(val node: RaftNode, val storage: InMemoryRaftStorage)

/**
 * Bounded await for a single-voter harness: suspend until [node]'s commit index reaches [index],
 * failing fast (via [withTimeout]) rather than hanging if the node never commits. The sibling of
 * [RaftSimulation.awaitCommit] for the single-node path — kept in this sanctioned fixture file so the
 * raw `commitIndex.first` lives behind the bound (issue #192 harness discipline), not in test bodies.
 */
internal suspend fun SingleVoterHarness.awaitCommit(index: Long, within: Duration = 2.seconds) {
    withTimeout(within) { node.commitIndex.first { it >= index } }
}

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
    identity: ClientIdentity = ClientIdentity.Auto,
): SingleVoterHarness {
    val self = NodeId("solo")
    val cluster = ClusterConfig(voters = setOf(self))
    val network = InMemoryRaftNetwork()
    val transport = network.transport(self)
    val node = scope.raftNode(cluster, transport, storage, FAST_RAFT_CONFIG, identity)
    return SingleVoterHarness(node, storage)
}

/**
 * Fast timings for deterministic tests — elections fire in single-digit ms.
 *
 * The election timeout is drawn from a **seeded** [kotlin.random.Random]. Under
 * [StandardTestDispatcher] scheduling is deterministic, but the *duration* each node waits is still
 * an RNG draw — an unseeded `Random.Default` reintroduces non-determinism in the timeouts (the
 * residual flake of issue #383: a partitioned leader's election timeout occasionally drew below a
 * test's `delay`, stepping it down before the test acted). Seeding pins every draw so the whole
 * engine is reproducible. NEVER seed in production — see [RaftConfig.random].
 */
internal val FAST_RAFT_CONFIG = RaftConfig(
    electionTimeoutMin = 5.milliseconds,
    electionTimeoutMax = 10.milliseconds,
    heartbeatInterval = 2.milliseconds,
    expectVirtualTime = true,  // see banner above — raft tests run on StandardTestDispatcher under virtual time
    random = Random(RAFT_TEST_SEED),
)

/**
 * Fixed seed for the raft suite's election-timeout RNG — keeps every run identical. Any test fixture
 * that builds its own [RaftConfig] (rather than reusing [FAST_RAFT_CONFIG]) must seed `random` with
 * this so the whole suite stays deterministic.
 */
internal const val RAFT_TEST_SEED = 383L

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
