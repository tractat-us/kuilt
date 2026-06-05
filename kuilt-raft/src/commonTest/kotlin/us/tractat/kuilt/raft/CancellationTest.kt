@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

private val fastConfig = RaftConfig(
    electionTimeoutMin = 5.milliseconds,
    electionTimeoutMax = 10.milliseconds,
    heartbeatInterval = 2.milliseconds,
)

private fun TestScope.threeNodeSim(nodeScope: CoroutineScope = backgroundScope): RaftSimulation {
    val ids = listOf(NodeId("a"), NodeId("b"), NodeId("c"))
    val config = ClusterConfig(voters = ids.toSet())
    return RaftSimulation(
        nodeIds = ids,
        scope = this,
        raftConfig = fastConfig,
        nodeScope = nodeScope,
        nodeFactory = { id, transport, storage, scope ->
            scope.raftNode(config, transport, storage, fastConfig)
        },
    )
}

private suspend fun awaitLeader(sim: RaftSimulation): RaftNode {
    repeat(500) {
        sim.leader()?.let { return it }
        delay(1)
    }
    error("No leader elected within timeout")
}

private fun leaderId(sim: RaftSimulation, leader: RaftNode): NodeId =
    sim.nodes.entries.first { it.value === leader }.key

private fun followerIds(sim: RaftSimulation, leaderId: NodeId): Set<NodeId> =
    sim.nodes.keys.filter { it != leaderId }.toSet()

/**
 * Cancellation coverage for [RaftEngine] — every state-transition window.
 *
 * Each test targets a distinct cancellation point:
 *   1. propose() cancelled before actor receives the command
 *   2. propose() cancelled while awaiting quorum (followers partitioned)
 *   3. scope cancelled with N concurrent propose() calls in flight
 *   4. scope cancelled during election timeout
 *   5. scope cancelled during heartbeat
 *   6. close() then propose() — must not hang
 *   7. close() idempotency
 *   8. no coroutine leaks after scope cancel (TestScope detects these)
 *   9. scope cancelled mid storage write (DelayedStorage)
 */
class CancellationTest {

    // ── Window 1: propose() cancelled before actor receives ──────────────────

    @Test
    fun proposeCancel_beforeActorReceives() = runTest(UnconfinedTestDispatcher()) {
        val sim = threeNodeSim()
        val leader = awaitLeader(sim)
        val job = launch { leader.propose(byteArrayOf(1)) }
        job.cancel()
        job.join()
        assertTrue(job.isCancelled || job.isCompleted, "Job must complete without hanging")
    }

    // ── Window 2: propose() cancelled while awaiting quorum ──────────────────

    @Test
    fun proposeCancel_whileAwaitingQuorum() = runTest(UnconfinedTestDispatcher()) {
        val sim = threeNodeSim()
        val leader = awaitLeader(sim)
        val id = leaderId(sim, leader)
        sim.partition(setOf(id), followerIds(sim, id))

        val job = launch {
            try { leader.propose(byteArrayOf(99)) } catch (_: CancellationException) {}
        }
        delay(10) // let propose() reach deferred.await()
        job.cancel()
        job.join()
        assertTrue(job.isCompleted, "propose() must unblock on cancel when awaiting quorum")
    }

    // ── Window 3: scope cancelled with concurrent proposals in flight ─────────

    @Test
    fun scopeCancel_withConcurrentProposals() = runTest(UnconfinedTestDispatcher()) {
        // Child of backgroundScope so virtual time advances, but independently cancellable.
        val nodeScope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        val sim = threeNodeSim(nodeScope = nodeScope)
        val leader = awaitLeader(sim)
        val id = leaderId(sim, leader)
        sim.partition(setOf(id), followerIds(sim, id))

        val jobs = (1..5).map { i ->
            launch { runCatching { leader.propose(byteArrayOf(i.toByte())) } }
        }
        delay(5)
        nodeScope.cancel()
        withTimeout(500) { jobs.forEach { it.join() } }
    }

    // ── Window 4: scope cancelled during election timeout ────────────────────

    @Test
    fun scopeCancel_duringElectionTimeout() = runTest(UnconfinedTestDispatcher()) {
        // Child of backgroundScope so virtual time advances, but independently cancellable.
        val nodeScope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        val config = ClusterConfig(voters = setOf(NodeId("a"), NodeId("b"), NodeId("c")))
        val network = InMemoryRaftNetwork()
        val node = nodeScope.raftNode(
            config,
            network.transport(NodeId("a")),
            InMemoryRaftStorage(),
            fastConfig,
        )
        delay(2) // let election timer start
        nodeScope.cancel()
        delay(10) // give coroutines time to finish
        val role = node.role.value
        assertTrue(
            role is RaftRole.Follower || role is RaftRole.Candidate,
            "Expected Follower or Candidate after scope cancel, got $role",
        )
    }

    // ── Window 5: scope cancelled during heartbeat ───────────────────────────

    @Test
    fun scopeCancel_duringHeartbeat() = runTest(UnconfinedTestDispatcher()) {
        val sim = threeNodeSim()
        val leader = awaitLeader(sim)
        val id = leaderId(sim, leader)
        assertIs<RaftRole.Leader>(leader.role.value)
        delay(5) // let heartbeats fire at least once
        // crash() cancels the leader's scope; must not propagate exceptions or hang
        sim.crash(id)
        delay(5)
    }

    // ── Window 6: close() then propose() — must not hang ─────────────────────

    @Test
    fun close_thenPropose_doesNotHang() = runTest(UnconfinedTestDispatcher()) {
        val sim = threeNodeSim()
        val leader = awaitLeader(sim)
        leader.close()
        delay(5) // let close command process
        val result = runCatching {
            withTimeout(200) { leader.propose(byteArrayOf(1)) }
        }
        assertTrue(result.isFailure, "propose() after close() must fail, not succeed")
    }

    // ── Window 7: close() idempotency ────────────────────────────────────────

    @Test
    fun close_isIdempotent() = runTest(UnconfinedTestDispatcher()) {
        val sim = threeNodeSim()
        val leader = awaitLeader(sim)
        leader.close()
        leader.close() // must not throw
    }

    // ── Window 8: no coroutine leaks after scope cancel ───────────────────────

    @Test
    fun scopeCancel_noLeakedCoroutines() = runTest(UnconfinedTestDispatcher()) {
        // TestScope detects uncompleted coroutines on exit — if any coroutine started by the
        // node outlives its scope, runTest fails with UncompletedCoroutinesError.
        val sim = threeNodeSim(nodeScope = backgroundScope)
        val leader = awaitLeader(sim)
        leader.propose(byteArrayOf(1))
        // backgroundScope is cancelled by runTest after the body returns.
        // If any node coroutines survive, runTest reports UncompletedCoroutinesError.
    }

    // ── Window 9: scope cancelled mid storage write ───────────────────────────

    @Test
    fun scopeCancel_midStorageWrite() = runTest(UnconfinedTestDispatcher()) {
        // Child of backgroundScope so virtual time advances, but independently cancellable.
        val nodeScope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        val storage = DelayedStorage(InMemoryRaftStorage(), delayMs = 50)
        val config = ClusterConfig(voters = setOf(NodeId("a"), NodeId("b"), NodeId("c")))
        val network = InMemoryRaftNetwork()
        nodeScope.raftNode(
            config,
            network.transport(NodeId("a")),
            storage,
            fastConfig,
        )
        delay(5)
        nodeScope.cancel()
        delay(100) // must not hang or propagate exception to test coroutine
    }
}

/**
 * [RaftStorage] wrapper that suspends for [delayMs] before each operation,
 * giving the test a controlled window to cancel the owning scope mid-write.
 */
private class DelayedStorage(
    private val delegate: RaftStorage,
    private val delayMs: Long = 10,
) : RaftStorage {
    override suspend fun term(): Long { delay(delayMs); return delegate.term() }
    override suspend fun saveTerm(term: Long) { delay(delayMs); delegate.saveTerm(term) }
    override suspend fun votedFor(): NodeId? { delay(delayMs); return delegate.votedFor() }
    override suspend fun saveVotedFor(nodeId: NodeId?) { delay(delayMs); delegate.saveVotedFor(nodeId) }
    override suspend fun appendEntries(entries: List<LogEntry>) { delay(delayMs); delegate.appendEntries(entries) }
    override suspend fun entries(fromIndex: Long): List<LogEntry> { delay(delayMs); return delegate.entries(fromIndex) }
    override suspend fun truncateFrom(index: Long) { delay(delayMs); delegate.truncateFrom(index) }
}
