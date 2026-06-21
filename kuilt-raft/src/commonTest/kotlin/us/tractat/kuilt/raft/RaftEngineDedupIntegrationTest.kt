@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * Integration coverage for the engine dedup behaviours that need a running node:
 * - Task 6: a lost-ack retry of the same `requestId` on a still-leading node coalesces (appends once).
 * - Task 8: two live writers sharing one **durable** clientId surface a [ClientIdCollisionException].
 */
class RaftEngineDedupIntegrationTest {
    @Test
    fun retryWithSameRequestIdOnSameLeaderAppendsOnce() = raftRunTest {
        val h = singleVoterNode(backgroundScope, identity = ClientIdentity.Durable(ClientId("c")))
        h.node.awaitLeadership()

        val first = h.node.propose("x".encodeToByteArray(), requestId = 1)
        h.awaitCommit(first.index)
        // Retry the SAME serial (as a client would after a lost ack) — the leader cache coalesces.
        val second = h.node.propose("x".encodeToByteArray(), requestId = 1)

        assertEquals(first.index, second.index, "retry must coalesce onto the originally committed entry")
        val userEntries = h.storage.entries().filter { !it.isNoOp }
        assertEquals(1, userEntries.size, "the leader must not append a second entry for a retried serial")
        assertEquals(DedupKey(ClientId("c"), 1), userEntries.single().dedupKey)
    }

    @Test
    fun sharedDurableClientIdSurfacesCollision() = raftRunTest {
        // Both voters are constructed with the SAME stable clientId — an operational misconfiguration.
        // Run the nodes under a supervisor scope with a handler so the offending node's fail-loud
        // ClientIdCollisionException is captured (rather than tearing down the whole test scope).
        val collision = CompletableDeferred<Throwable>()
        val handler = CoroutineExceptionHandler { _, e -> collision.complete(e) }
        val nodeScope = CoroutineScope(
            backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]) + handler,
        )
        val ids = listOf(NodeId("v1"), NodeId("v2"))
        val cluster = ClusterConfig(voters = ids.toSet())
        val shared = ClientId("shared")
        val sim = RaftSimulation(
            nodeIds = ids,
            scope = this,
            raftConfig = FAST_RAFT_CONFIG,
            nodeScope = nodeScope,
            nodeFactory = { _, transport, storage, childScope ->
                childScope.raftNode(cluster, transport, storage, FAST_RAFT_CONFIG, ClientIdentity.Durable(shared))
            },
        )
        val leader = awaitLeader(sim)

        // The leader issues serial 1 under "shared"; the follower — which never issued it — observes
        // its own clientId paired with a serial above its high-water-mark when the entry commits.
        leader.propose("x".encodeToByteArray(), requestId = 1)

        val e = withTimeout(2.seconds) { collision.await() }
        assertIs<ClientIdCollisionException>(e)
    }
}
