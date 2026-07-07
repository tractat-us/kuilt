@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.warp.test

import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.warp.TaskId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Self-tests for [MultiNodeWarpSim] — proves the harness works end-to-end.
 *
 * Each test runs under [warpSimTest] (`StandardTestDispatcher` + 5 s timeout). These are
 * deliberately narrow: they validate the harness machinery (deterministic convergence,
 * ownership targeting, fail-fast dumps), not warp correctness — that lives in
 * `:kuilt-warp`'s own suite.
 */
class MultiNodeWarpSimTest {

    /**
     * The primary smoke test: a 3-node mesh executes every enqueued task and the results
     * boards converge on all peers — deterministically, under `StandardTestDispatcher`
     * virtual time, with no hand-rolled settle loop.
     */
    @Test
    fun threeNodeMeshConvergesEnqueuedTasksOntoEveryResultsBoard() = warpSimTest(n = 3) { sim ->
        val tasks = (1..9).map { TaskId("smoke-$it") }
        tasks.forEach { sim.enqueueEcho(it) }

        sim.awaitResults(tasks)

        assertAll(
            { assertEquals(tasks.toSet(), sim.node(0).results.taskIds, "host board must converge") },
            { assertEquals(tasks.toSet(), sim.node(1).results.taskIds, "joiner 1 board must converge") },
            { assertEquals(tasks.toSet(), sim.node(2).results.taskIds, "joiner 2 board must converge") },
            { assertEquals(tasks.toSet(), sim.executedTaskIds(), "every task must have been executed") },
        )
    }

    /**
     * Once the roster has settled, a task targeted at a specific peer via [MultiNodeWarpSim.taskOwnedBy]
     * is executed by exactly that peer — proving the harness's ring-targeting helper matches the
     * ring the nodes actually build, and that the execution log attributes work correctly.
     */
    @Test
    fun ringOwnerExecutesItsTaskExactlyOnceAfterRosterSettles() = warpSimTest(n = 3) { sim ->
        sim.settle()

        val owner = sim.peer(2)
        val task = sim.taskOwnedBy(owner)
        sim.enqueueEcho(task, on = sim.peer(0))

        sim.awaitResults(listOf(task))

        assertEquals(listOf(owner), sim.executedBy(task), "the ring owner (and only it) must execute")
    }

    /**
     * Non-convergence fails **fast** (bounded virtual await, not the outer 5 s wall-clock
     * timeout) and **legibly**: the [AssertionError] carries the [MultiNodeWarpSim.dumpState]
     * snapshot naming each node's board and counters.
     */
    @Test
    fun nonConvergenceFailsFastWithALegibleStateDump() = warpSimTest(n = 2) { sim ->
        val failure = assertFailsWith<AssertionError> {
            sim.awaitTrue("neverConverges", within = 300.milliseconds) { false }
        }

        val message = checkNotNull(failure.message)
        assertAll(
            { assertTrue("MultiNodeWarpSim state dump" in message, "dump header present: $message") },
            { assertTrue("neverConverges" in message, "await description present: $message") },
            { assertTrue(sim.peer(0).value in message, "per-node lines present: $message") },
        )
    }
}
