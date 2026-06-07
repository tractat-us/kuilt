package us.tractat.kuilt.raft

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertIs

/**
 * Validates [RaftNode.awaitLeadership] against a live election.
 *
 * Real-clock test: this test exercises actual election timing under the engine's
 * heartbeat cadence — the point is to confirm that `awaitLeadership()` correctly
 * suspends until a real election completes and then returns immediately once the
 * node is leader. Driving it under a `TestDispatcher` (virtual time) means the
 * raft election loops inherit the virtual clock and the `withTimeout` fires before
 * real elections can happen, causing flaky `UncompletedCoroutinesError`.
 *
 * Lives in `jvmTest` (not `commonTest`) because [runBlocking] is used to block
 * the test thread on real-clock coroutines — a pattern not portable to JS/WASM
 * where the main thread cannot be blocked.
 *
 * Node coroutines live in a [SupervisorJob]-backed scope that is explicitly
 * cancelled in `finally` to avoid leaked coroutines.
 */
class AwaitLeadershipTest {

    @Test
    fun awaitLeadership_returnsWhenNodeBecomesLeader() {
        val nodeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            runBlocking {
                val sim = raftSim(this, nodeScope)
                withTimeout(2000) {
                    // Wait for any node to reach leadership
                    var leaderNode: RaftNode? = null
                    while (leaderNode == null) {
                        leaderNode = sim.nodes.values.firstOrNull { it.role.value is RaftRole.Leader }
                        if (leaderNode == null) delay(1)
                    }
                    // awaitLeadership() on an already-leader node must return immediately
                    leaderNode.awaitLeadership()
                    assertIs<RaftRole.Leader>(leaderNode.role.value)
                }
            }
        } finally {
            nodeScope.cancel()
        }
    }
}
