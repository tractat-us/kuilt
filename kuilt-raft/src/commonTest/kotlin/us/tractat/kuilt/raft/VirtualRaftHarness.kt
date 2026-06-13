@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * # Virtual-time raft test harness (issue #383 — PoC)
 *
 * Runs a real [RaftNode] cluster under **fully virtual, deterministic time**, fixing the
 * load-non-determinism documented (incorrectly, see below) in the [RaftTestFixtures] banner.
 *
 * ## What was actually wrong
 *
 * The old banner claims `delay()` "elapses on the wall clock" under
 * `runTest(UnconfinedTestDispatcher())`. That is **false** in coroutines 1.9: an
 * `UnconfinedTestDispatcher()` with no explicit scheduler binds to the enclosing `runTest`'s
 * `TestCoroutineScheduler`, so `delay()` is already virtual (a 5 s delay advances virtual time
 * 5 s and consumes ~0 ms wall — proven by `VirtualTimeProbeTest`).
 *
 * The real determinism defect is **ordering**, not wall-clock waits. `UnconfinedTestDispatcher`
 * runs continuations **eagerly inline**, so at a single virtual instant the interleaving of an
 * engine timer fire against an in-flight message round-trip depends on how many continuation steps
 * the CPU happens to take — load-dependent ordering, which is exactly the race issue #383 reproduced
 * (flipping the abandon timer to `delay(0)` deterministically loses a different race each time).
 *
 * ## The fix
 *
 * Bind the engine + node scopes to a [StandardTestDispatcher] over the test's `testScheduler`.
 * `StandardTestDispatcher` is **FIFO at each virtual instant** — no eager inline execution — so
 * ordering is deterministic regardless of host load. The engine never quiesces (heartbeat/election
 * timers perpetually re-arm), so `advanceUntilIdle()` would spin forever; instead the await helpers
 * drive time in **bounded [STEP] increments** ([advanceTimeBy] + [runCurrent]) until the target
 * condition holds or [VIRTUAL_TIMEOUT] virtual time elapses.
 */

/** Virtual-time advance per poll step. One election-timeout window of [FAST_RAFT_CONFIG] is 5–10 ms. */
internal val STEP: Duration = 1.milliseconds

/** Upper bound on virtual time a single await drives before declaring non-convergence. */
internal val VIRTUAL_TIMEOUT: Duration = 5.seconds

/**
 * Run a raft test under deterministic virtual time on a [StandardTestDispatcher].
 *
 * Unlike [raftRunTest] (which uses `UnconfinedTestDispatcher` and relies on eager execution),
 * this body must drive virtual time explicitly via [VirtualRaftSim.awaitLeaderV] and friends —
 * nothing runs until time is advanced.
 */
internal fun raftVirtualTest(
    timeout: Duration = 30.seconds,
    body: suspend TestScope.() -> Unit,
): TestResult = runTest(StandardTestDispatcher(), timeout = timeout, testBody = body)

/**
 * A virtual-time simulation: builds a real-[RaftNode] cluster whose every coroutine runs on the
 * test scheduler, and exposes await helpers that advance virtual time in bounded steps.
 */
internal class VirtualRaftSim(
    private val testScope: TestScope,
    nodeScope: CoroutineScope,
    n: Int = 3,
    config: RaftConfig = FAST_RAFT_CONFIG,
) {
    private val ids = (1..n).map { NodeId("v$it") }
    private val cluster = ClusterConfig(voters = ids.toSet())
    val sim = RaftSimulation(
        nodeIds = ids,
        scope = testScope,
        raftConfig = config,
        nodeScope = nodeScope,
        nodeFactory = { _, transport, storage, childScope ->
            childScope.raftNode(cluster, transport, storage, config)
        },
    )

    /** Drive virtual time in [STEP] increments until [probe] is non-null; fail fast on timeout. */
    private fun <T : Any> driveUntil(what: String, probe: () -> T?): T {
        testScope.runCurrent()
        var elapsed = Duration.ZERO
        while (elapsed < VIRTUAL_TIMEOUT) {
            probe()?.let { return it }
            testScope.advanceTimeBy(STEP)
            testScope.runCurrent()
            elapsed += STEP
        }
        probe()?.let { return it }
        throw AssertionError("$what did not hold within $VIRTUAL_TIMEOUT of virtual time")
    }

    fun awaitLeaderV(): RaftNode = driveUntil("awaitLeader") { sim.leader() }

    fun awaitCommitV(index: Long, on: Collection<NodeId> = ids) =
        driveUntil("awaitCommit($index)") {
            true.takeIf { on.all { id -> (sim.nodes[id]?.commitIndex?.value ?: -1L) >= index } }
        }
}
