@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.cluster

import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.raft.ClientId
import us.tractat.kuilt.raft.ClientSessionTable
import us.tractat.kuilt.raft.Committed
import us.tractat.kuilt.raft.DedupKey
import us.tractat.kuilt.raft.LogEntry
import us.tractat.kuilt.raft.RaftRole
import us.tractat.kuilt.raft.test.FakeRaftNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Tier-(a) behavioral tests for [ClusterClient] using [FakeRaftNode].
 *
 * All tests run under [StandardTestDispatcher] with a tight 5s timeout.
 * No real dispatchers, no wall-clock waits.
 *
 * Covers:
 * - [ClusterClient.propose] — delegates to the underlying RaftNode.
 * - [ClusterClient.propose] with explicit requestId — exactly-once dedup.
 * - [ClientSessionTable.shouldApply] dedup on the apply side.
 * - [ClusterClient.committed] — streams entries from the underlying node.
 * - [ClusterClient.role] — mirrors the underlying node role.
 * - Retry-same-requestId on failover: duplicate entry is filtered by shouldApply.
 */
class ClusterClientTest {

    @Test
    fun `propose delegates to underlying raftNode and returns committed entry`(): TestResult =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val fakeNode = FakeRaftNode(initialRole = RaftRole.Leader)
            val client = clusterClientWithNode(fakeNode)

            val command = "cmd-1".encodeToByteArray()
            val entry = client.propose(command)

            assertTrue(entry.command.contentEquals(command), "propose returns the committed entry")
        }

    @Test
    fun `propose with requestId returns entry stamped with that requestId`(): TestResult =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val clientId = ClientId("stable-client")
            val fakeNode = FakeRaftNode(
                initialRole = RaftRole.Leader,
                clientId = clientId,
            )
            val client = clusterClientWithNode(fakeNode)

            val command = "cmd-dedup".encodeToByteArray()
            val entry = client.propose(command, requestId = 42L)

            assertEquals(DedupKey(clientId, 42L), entry.dedupKey)
        }

    @Test
    fun `committed streams entries pushed by underlying node`(): TestResult =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val fakeNode = FakeRaftNode()
            val client = clusterClientWithNode(fakeNode)

            val collected = mutableListOf<LogEntry>()
            val job = launch {
                client.committed
                    .filterIsInstance<Committed.Entry>()
                    .collect { collected.add(it.entry) }
            }

            fakeNode.pushCommitted("entry-1".encodeToByteArray())
            fakeNode.pushCommitted("entry-2".encodeToByteArray())

            // Let the collector drain the two buffered entries.
            testScheduler.advanceTimeBy(1)
            testScheduler.runCurrent()

            job.cancel()

            assertEquals(2, collected.size)
            assertTrue(collected[0].command.contentEquals("entry-1".encodeToByteArray()))
            assertTrue(collected[1].command.contentEquals("entry-2".encodeToByteArray()))
        }

    @Test
    fun `role mirrors underlying node role`(): TestResult =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val fakeNode = FakeRaftNode(initialRole = RaftRole.Learner)
            val client = clusterClientWithNode(fakeNode)

            assertEquals(RaftRole.Learner, client.role.value)

            fakeNode.setRole(RaftRole.Follower)
            assertEquals(RaftRole.Follower, client.role.value)
        }

    @Test
    fun `retry same requestId is deduplicated by ClientSessionTable`(): TestResult =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val clientId = ClientId("retry-client")
            val fakeNode = FakeRaftNode(
                initialRole = RaftRole.Leader,
                clientId = clientId,
            )
            val client = clusterClientWithNode(fakeNode)

            val command = "idempotent-action".encodeToByteArray()

            val first = client.propose(command, requestId = 1L)
            val retry = client.propose(command, requestId = 1L)

            // Both calls succeed (fake node doesn't deduplicate — that's the server's job).
            // What matters for S3a: both entries carry the same dedupKey, so ClientSessionTable
            // on the apply side sees the command exactly once.
            assertEquals(DedupKey(clientId, 1L), first.dedupKey)
            assertEquals(DedupKey(clientId, 1L), retry.dedupKey)

            val table = ClientSessionTable()
            assertTrue(table.shouldApply(first.dedupKey), "first apply must pass")
            assertFalse(table.shouldApply(retry.dedupKey), "retry must be filtered as duplicate")
        }

    @Test
    fun `shouldApply dedup allows first apply and rejects retry across failover simulation`(): TestResult =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            // Simulate the lifecycle of a single proposal that survives an entry-server failover:
            //   1. Client proposes with requestId=7 → committed on first server.
            //   2. Connection tears (entry-server dies).
            //   3. Client reconnects to second server, retries with same requestId=7.
            //   4. State-machine apply loop on the voter uses ClientSessionTable to dedup.

            val clientId = ClientId("failover-client")
            val requestId = 7L
            val command = "action:do-something".encodeToByteArray()

            // Step 1: initial proposal creates a committed entry with the dedup key.
            val initialEntry = LogEntry(
                index = 1L,
                term = 1L,
                command = command,
                dedupKey = DedupKey(clientId, requestId),
            )

            // Step 2: after failover the client retries — the server may commit again
            // if the leader didn't see the first commit before the tear. The retry carries
            // the same dedupKey (same clientId, same requestId).
            val retryEntry = LogEntry(
                index = 2L,
                term = 2L,
                command = command,
                dedupKey = DedupKey(clientId, requestId),
            )

            val table = ClientSessionTable()

            // Apply loop: first entry applies, retry is filtered.
            assertTrue(table.shouldApply(initialEntry.dedupKey), "initial entry must apply")
            assertFalse(table.shouldApply(retryEntry.dedupKey), "retry entry must be filtered")
        }

    @Test
    fun `close terminates the committed stream`(): TestResult =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val fakeNode = FakeRaftNode()
            val client = clusterClientWithNode(fakeNode)

            val firstJob = launch { client.committed.first() }

            fakeNode.pushCommitted("before-close".encodeToByteArray())

            testScheduler.runCurrent()
            firstJob.join()

            // Close the client — the underlying FakeRaftNode closes its channel.
            client.close()

            // committed should now be exhausted (collecting returns empty / completes).
            val afterClose = client.committed
                .filterIsInstance<Committed.Entry>()
                .toList()
            assertEquals(emptyList(), afterClose, "no entries after close")
        }

    @Test
    fun `ClusterEndpoints requires non-empty endpoint list`(): TestResult =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val result = runCatching { ClusterEndpoints(endpoints = emptyList()) }
            assertTrue(result.isFailure, "empty endpoint list must throw")
        }
}
