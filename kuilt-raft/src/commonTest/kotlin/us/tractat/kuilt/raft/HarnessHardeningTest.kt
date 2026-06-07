@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds

/**
 * Proves the hardened harness (issue #192): a cluster that can never converge must FAIL FAST
 * with a full per-node state dump rather than hanging until the runTest timeout.
 *
 * The whole point of `awaitCommit` / `awaitLeader` / `awaitRole` is that a non-convergence bug
 * surfaces in *seconds with diagnostics* instead of as an opaque timeout that needs a jstack to
 * even identify the offending test.
 */
class HarnessHardeningTest {

    /**
     * Three nodes, every inter-node link dropped → no quorum can ever form, so no entry ever
     * commits. `awaitCommit` must throw [AssertionError] (fast, well within its bound) whose
     * message carries the per-node dump.
     */
    @Test
    fun awaitCommit_onNonConvergingCluster_failsFastWithDump() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3)
        // Sever every link between the three nodes: each is now an isolated singleton with no
        // quorum, so commitIndex never advances on any of them.
        sim.nodeIds.forEach { from ->
            sim.nodeIds.forEach { to -> if (from != to) sim.dropLink(from, to) }
        }

        val error = assertFailsWith<AssertionError> {
            sim.awaitCommit(index = 1L, within = 200.milliseconds)
        }

        val message = error.message.orEmpty()
        assertAll(
            { assertTrue("awaitCommit(1)" in message, "dump should name the failing await: $message") },
            { assertTrue("timed out" in message, "dump should say it timed out: $message") },
            { assertTrue("state dump" in message, "dump should be the RaftSimulation state dump: $message") },
            { assertTrue(sim.nodeIds.all { it.value in message }, "dump should list every node: $message") },
            { assertTrue("commitIndex=" in message, "dump should report per-node commitIndex: $message") },
            { assertTrue("Timeout=" in message, "dump should report the trace-event histogram: $message") },
        )
    }

    /** `dumpState` is directly callable for ad-hoc diagnostics, not just on the timeout path. */
    @Test
    fun dumpState_rendersEveryNode() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leader = sim.awaitLeader()
        leader.propose(byteArrayOf(1))

        val dump = sim.dumpState("manual")
        assertAll(
            { assertTrue("manual" in dump, "dump echoes the reason: $dump") },
            { assertTrue(sim.nodeIds.all { it.value in dump }, "dump lists every node: $dump") },
            { assertTrue("role=" in dump, "dump reports each node's role: $dump") },
        )
    }
}

private fun assertAll(vararg assertions: () -> Unit) = assertions.forEach { it() }
