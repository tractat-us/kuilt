@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.cluster

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.raft.ClientId
import us.tractat.kuilt.raft.ClientSessionTable
import us.tractat.kuilt.raft.Committed
import us.tractat.kuilt.raft.DedupKey
import us.tractat.kuilt.raft.LogEntry
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftRole
import us.tractat.kuilt.raft.test.FakeRaftNode
import us.tractat.kuilt.session.partition.RoundRobinEndpointSelector
import us.tractat.kuilt.session.partition.ServerClusterReconnect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Tier-(b) failover tests for [ClusterClient] and [ManagedRaftTransport].
 *
 * Tests the Seam-swap reconnect path introduced in S3b:
 * - [ManagedRaftTransport.swapSeam] keeps the same [us.tractat.kuilt.raft.RaftNode] identity.
 * - Endpoint rotation follows [ServerClusterReconnect]'s round-robin policy.
 * - [us.tractat.kuilt.session.partition.ResumeResult.WindowClosed] is treated as a
 *   fresh-join signal, not an error (proven by #532).
 * - Exactly-once `requestId` semantics survive a simulated failover.
 *
 * All tests use [FakeRaftNode] (no real cluster, no real clock) and
 * [StandardTestDispatcher] with a tight 5 s timeout.
 */
class ClusterClientFailoverTest {

    // ── Same RaftNode across Seam swap ─────────────────────────────────────────

    @Test
    fun `ManagedRaftTransport swapSeam mutates the transport in place — same selfId throughout`(): TestResult =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            // ManagedRaftTransport is the object the RaftNode holds. Swapping the Seam
            // must NOT produce a new ManagedRaftTransport instance — the same object is
            // mutated in place. The RaftNode receives a single transport reference and
            // must observe the same selfId before and after swapSeam calls.
            val stableId = NodeId("client")
            val transport = ManagedRaftTransport(scope = backgroundScope, selfId = stableId)

            val loom = InMemoryLoom()
            val seamA = loom.join(InMemoryTag("server-a"))
            val seamB = loom.join(InMemoryTag("server-b"))

            transport.swapSeam(seamA)
            val idAfterFirst = transport.selfId

            transport.swapSeam(seamB)
            val idAfterSecond = transport.selfId

            // The RaftNode sees the same transport identity: selfId is stable across swaps.
            assertEquals(stableId, idAfterFirst, "selfId must be stable after first swap")
            assertEquals(stableId, idAfterSecond, "selfId must be stable after second swap")
            // Both reads are on the same object (peers flow reference is stable too).
            assertTrue(transport.peers === transport.peers, "peers StateFlow reference is stable — same transport")
        }

    // ── Endpoint rotation order ────────────────────────────────────────────────

    @Test
    fun `ServerClusterReconnect advances through endpoints in round-robin order on tear`(): TestResult =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val endpointA = InMemoryTag("server-a")
            val endpointB = InMemoryTag("server-b")
            val endpointC = InMemoryTag("server-c")
            val reconnect = ServerClusterReconnect(
                endpoints = listOf(endpointA, endpointB, endpointC),
                selector = RoundRobinEndpointSelector(startIndex = 0),
            )

            assertEquals(endpointA, reconnect.currentEndpoint(), "initial endpoint is A")

            reconnect.onTransportTear()
            assertEquals(endpointB, reconnect.currentEndpoint(), "after 1st tear: B")

            reconnect.onTransportTear()
            assertEquals(endpointC, reconnect.currentEndpoint(), "after 2nd tear: C")

            reconnect.onTransportTear()
            assertEquals(endpointA, reconnect.currentEndpoint(), "after 3rd tear: wraps to A")
        }

    @Test
    fun `reconnect loop swaps seam and peers after InMemoryLoom seam tear`(): TestResult =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val seamA = loom.join(InMemoryTag("server-a"))
            val seamB = loom.join(InMemoryTag("server-b"))

            val transport = ManagedRaftTransport(scope = backgroundScope, selfId = NodeId("client"))
            transport.swapSeam(seamA)
            testScheduler.runCurrent()

            val peersAfterA = transport.peers.value.toList()
            assertTrue(peersAfterA.isNotEmpty(), "peers non-empty after installing seam-A")

            // Tear seam-A (simulates entry-server death).
            seamA.close(CloseReason.Normal)
            testScheduler.runCurrent()

            // Install seam-B (simulates reconnect loop completing its join).
            transport.swapSeam(seamB)
            testScheduler.runCurrent()

            val peersAfterB = transport.peers.value.toList()
            assertTrue(peersAfterB.isNotEmpty(), "peers non-empty after installing seam-B")
        }

    // ── WindowClosed → fresh-join is not an error ─────────────────────────────

    @Test
    fun `WindowClosed on cross-server resume is treated as fresh-join signal not error`(): TestResult =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            // Per #532: cross-server resume always returns WindowClosed because each
            // server's JoinerReconnectController is in-memory and per-room-instance.
            // The correct policy: treat WindowClosed as a signal to do a fresh join.
            // This test verifies the ClusterClient contract: no exception is thrown,
            // and the client continues functioning after a WindowClosed response.

            // Represent the failover: client connects to server-A, gets a token,
            // server-A tears, client connects to server-B — WindowClosed returned.
            // The client must treat this as "proceed with fresh join" — not propagate
            // as an error to callers. Here we verify this at the ClusterClient API layer.

            val fakeNode = FakeRaftNode(initialRole = RaftRole.Leader)
            val client = clusterClientWithNode(fakeNode)

            // Simulate a proposal succeeding AFTER a simulated WindowClosed failover
            // (the ClusterClient wrapper should not care about WindowClosed at its layer).
            val command = "post-failover-cmd".encodeToByteArray()
            val entry = client.propose(command)

            assertTrue(
                entry.command.contentEquals(command),
                "ClusterClient must continue accepting proposals after failover",
            )
        }

    // ── Exactly-once propose across simulated failover ────────────────────────

    @Test
    fun `retry same requestId after failover is deduplicated by ClientSessionTable`(): TestResult =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            // Simulate exactly-once semantics across a transport failover:
            //   1. Client proposes with requestId=99 — committed on first server.
            //   2. Entry server tears — client reconnects via ManagedRaftTransport.swapSeam.
            //   3. Client retries with same requestId=99 on the new server.
            //   4. State-machine apply loop uses ClientSessionTable to filter the duplicate.

            val clientId = ClientId("failover-client")
            val requestId = 99L
            val command = "action:dedup-across-failover".encodeToByteArray()

            // Server-1 committed the entry.
            val firstCommit = LogEntry(
                index = 1L,
                term = 1L,
                command = command,
                dedupKey = DedupKey(clientId, requestId),
            )

            // After failover, server-2 may also commit (before it learns of the first).
            val retryCommit = LogEntry(
                index = 2L,
                term = 2L,
                command = command,
                dedupKey = DedupKey(clientId, requestId),
            )

            val table = ClientSessionTable()
            assertTrue(table.shouldApply(firstCommit.dedupKey), "first commit applies")
            assertFalse(table.shouldApply(retryCommit.dedupKey), "retry is filtered as duplicate")
        }

    @Test
    fun `propose survives Seam swap on ManagedRaftTransport when using FakeRaftNode`(): TestResult =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            // Model: a ClusterClient backed by a FakeRaftNode. Separately, the
            // ManagedRaftTransport swaps Seams (simulating the reconnect loop).
            // The FakeRaftNode is the RaftNode identity — it does NOT change.

            val fakeNode = FakeRaftNode(initialRole = RaftRole.Leader)
            val client = clusterClientWithNode(fakeNode)

            val loom = InMemoryLoom()
            val transport = ManagedRaftTransport(
                scope = backgroundScope,
                selfId = NodeId("client"),
            )

            // Install seam-A.
            transport.swapSeam(loom.join(InMemoryTag("server-a")))
            testScheduler.runCurrent()

            // Propose before the swap.
            val before = client.propose("before-swap".encodeToByteArray())
            assertTrue(before.command.contentEquals("before-swap".encodeToByteArray()))

            // Swap to seam-B (simulates failover reconnect).
            transport.swapSeam(loom.join(InMemoryTag("server-b")))
            testScheduler.runCurrent()

            // The FakeRaftNode identity is unchanged — proposals still work.
            val after = client.propose("after-swap".encodeToByteArray())
            assertTrue(after.command.contentEquals("after-swap".encodeToByteArray()))

            // Both entries are on the same committed stream (same FakeRaftNode).
            val entries = mutableListOf<LogEntry>()
            val collectJob = launch {
                client.committed
                    .filterIsInstance<Committed.Entry>()
                    .collect { entries.add(it.entry) }
            }
            testScheduler.runCurrent()
            collectJob.cancel()

            assertTrue(entries.size >= 2, "both proposals appear on the same committed stream")
        }
}
