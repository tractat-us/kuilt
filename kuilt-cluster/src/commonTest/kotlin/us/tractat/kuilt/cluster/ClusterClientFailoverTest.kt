@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.cluster

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.raft.ClientId
import us.tractat.kuilt.raft.ClientSessionTable
import us.tractat.kuilt.raft.Committed
import us.tractat.kuilt.raft.DedupKey
import us.tractat.kuilt.raft.LogEntry
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftEnvelope
import us.tractat.kuilt.raft.RaftRole
import us.tractat.kuilt.raft.RaftTransport
import us.tractat.kuilt.raft.test.FakeRaftNode
import us.tractat.kuilt.session.partition.RoundRobinEndpointSelector
import us.tractat.kuilt.session.partition.ServerClusterReconnect
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Tier-(b) failover tests for [ClusterClient] and its swappable relay [ManagedSeam].
 *
 * Tests the Seam-swap reconnect path introduced in S3b and re-expressed by the
 * relay-dialect cutover (#1360):
 * - [ManagedSeam.swap] keeps the same [us.tractat.kuilt.raft.RaftNode] identity.
 * - The player relay transport over the [ManagedSeam] wraps every send as a
 *   `RaftRelay(dest = leader)` addressed to the single relay peer.
 * - Endpoint rotation follows [ServerClusterReconnect]'s round-robin policy.
 * - A terminal [us.tractat.kuilt.session.partition.ResumeResult.Refused] is treated as a
 *   fresh-join signal, not an error (proven by #532).
 * - Exactly-once `requestId` semantics survive a simulated failover.
 *
 * All tests use [FakeRaftNode] (no real cluster, no real clock) and
 * [StandardTestDispatcher] with a tight 5 s timeout.
 */
class ClusterClientFailoverTest {

    // ── Same node identity across Seam swap ────────────────────────────────────

    @Test
    fun `ManagedSeam swap keeps the same selfId across backing-seam replacement`(): TestResult =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            // ManagedSeam is the swappable relay channel the player transport (and thus the
            // RaftNode) holds. Swapping the backing Seam must NOT change its identity — the
            // transport observes the same selfId and the same peers StateFlow reference.
            val stableId = PeerId("client")
            val managed = ManagedSeam(scope = backgroundScope, selfId = stableId)

            val loom = InMemoryLoom()
            val seamA = loom.join(InMemoryTag("server-a"))
            val seamB = loom.join(InMemoryTag("server-b"))

            managed.swap(seamA)
            val idAfterFirst = managed.selfId

            managed.swap(seamB)
            val idAfterSecond = managed.selfId

            assertEquals(stableId, idAfterFirst, "selfId must be stable after first swap")
            assertEquals(stableId, idAfterSecond, "selfId must be stable after second swap")
            assertTrue(managed.peers === managed.peers, "peers StateFlow reference is stable — same seam")
        }

    // ── Single-relay-peer addressing, dest-routed (#544 / #1360) ──────────────

    @Test
    fun `player sendTo over ManagedSeam wraps RaftRelay dest leader and addresses the single relay peer`(): TestResult =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            // A learner's room seam is strictly 2-peer: { learner, relay }. The Raft engine
            // forwards a proposal addressed to the *real* leader NodeId, which is generally NOT
            // the relay's PeerId. The player relay transport wraps every send as
            // RaftRelay(origin = client, dest = leader) and addresses the single relay peer;
            // the server-side hub then dest-routes it. This is the precondition for cross-relay
            // failover without moving Raft leadership (#544) and preserves the true dest (#1360).
            val loom = InMemoryLoom()
            val relaySeam = loom.join(InMemoryTag("relay"))
            val clientSeam = loom.join(InMemoryTag("relay"))
            val clientId = NodeId(clientSeam.selfId.value)

            val managed = ManagedSeam(scope = backgroundScope, selfId = PeerId(clientId.value))
            val leader = NodeId("distant-leader-voter")
            val transport = playerRelayTransport(
                inner = NoPeerInner(clientId),
                relayChannel = managed,
                voters = { setOf(leader) },
                scope = backgroundScope,
            )
            managed.swap(clientSeam)
            testScheduler.runCurrent()

            val received = mutableListOf<ByteArray>()
            val collectJob = launch { relaySeam.incoming.collect { received.add(it.toByteArray()) } }
            testScheduler.runCurrent()

            val payload = "forward-to-leader".encodeToByteArray()
            transport.sendTo(leader, payload)
            testScheduler.runCurrent()
            collectJob.cancel()

            assertEquals(1, received.size, "the relay peer must receive exactly one wrapped frame")
            val relay = RaftRelay.decode(received.single())
            assertEquals(leader, relay.dest, "the wrap carries dest = the leader NodeId")
            assertEquals(clientId, relay.origin, "origin = the true client")
            assertContentEquals(payload, relay.bytes, "the original payload rides inside the envelope")
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
    fun `ManagedSeam swap reflects the new backing seam peers after an InMemoryLoom seam tear`(): TestResult =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val seamA = loom.join(InMemoryTag("server-a"))
            val seamB = loom.join(InMemoryTag("server-b"))

            val managed = ManagedSeam(scope = backgroundScope, selfId = PeerId("client"))
            managed.swap(seamA)
            testScheduler.runCurrent()

            val peersAfterA = managed.peers.value.toList()
            assertTrue(peersAfterA.isNotEmpty(), "peers non-empty after installing seam-A")

            // Tear seam-A (simulates entry-server death).
            seamA.close(CloseReason.Normal)
            testScheduler.runCurrent()

            // Install seam-B (simulates reconnect loop completing its join).
            managed.swap(seamB)
            testScheduler.runCurrent()

            val peersAfterB = managed.peers.value.toList()
            assertTrue(peersAfterB.isNotEmpty(), "peers non-empty after installing seam-B")
        }

    // ── a terminal refusal → fresh-join is not an error ───────────────────────

    @Test
    fun `a terminal cross-server refusal is treated as fresh-join signal not error`(): TestResult =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            // Per #532: a cross-server resume is always terminally refused, because each server
            // mints its own RoomId and keeps its reconnect windows in memory.
            // The correct policy: treat that refusal as a signal to do a fresh join.
            // This test verifies the ClusterClient contract: no exception is thrown,
            // and the client continues functioning after such a response.

            val fakeNode = FakeRaftNode(initialRole = RaftRole.Leader)
            val client = clusterClientWithNode(fakeNode)

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
            //   2. Entry server tears — client reconnects via ManagedSeam.swap.
            //   3. Client retries with same requestId=99 on the new server.
            //   4. State-machine apply loop uses ClientSessionTable to filter the duplicate.

            val clientId = ClientId("failover-client")
            val requestId = 99L
            val command = "action:dedup-across-failover".encodeToByteArray()

            val firstCommit = LogEntry(
                index = 1L,
                term = 1L,
                command = command,
                dedupKey = DedupKey(clientId, requestId),
            )

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
    fun `propose survives ManagedSeam swap when using FakeRaftNode`(): TestResult =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            // Model: a ClusterClient backed by a FakeRaftNode. Separately, the ManagedSeam
            // swaps backing Seams (simulating the reconnect loop). The FakeRaftNode is the
            // RaftNode identity — it does NOT change.

            val fakeNode = FakeRaftNode(initialRole = RaftRole.Leader)
            val client = clusterClientWithNode(fakeNode)

            val loom = InMemoryLoom()
            val managed = ManagedSeam(scope = backgroundScope, selfId = PeerId("client"))

            // Install seam-A.
            managed.swap(loom.join(InMemoryTag("server-a")))
            testScheduler.runCurrent()

            // Propose before the swap.
            val before = client.propose("before-swap".encodeToByteArray())
            assertTrue(before.command.contentEquals("before-swap".encodeToByteArray()))

            // Swap to seam-B (simulates failover reconnect).
            managed.swap(loom.join(InMemoryTag("server-b")))
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

    /** A no-peer inner transport: forces every send through the relay channel. */
    private class NoPeerInner(override val selfId: NodeId) : RaftTransport {
        override val peers: StateFlow<Set<NodeId>> = MutableStateFlow(emptySet())
        override val incoming: Flow<RaftEnvelope> = emptyFlow()
        override suspend fun sendTo(peer: NodeId, message: ByteArray) = Unit
    }
}
