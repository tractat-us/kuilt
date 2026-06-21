@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.game

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.BinaryFormat
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.serializer
import us.tractat.kuilt.raft.ClientId
import us.tractat.kuilt.raft.ClientSessionTable
import us.tractat.kuilt.raft.DedupKey
import us.tractat.kuilt.raft.LeadershipLostException
import us.tractat.kuilt.raft.LogEntry
import us.tractat.kuilt.raft.RaftRole
import us.tractat.kuilt.raft.Snapshot
import us.tractat.kuilt.raft.test.FakeRaftNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class TurnSequencerTest {

    @Serializable
    private data class Move(val player: Int, val card: Int)

    // Single shared format — same instance used by TurnSequencer and encodeMove so
    // the wire encoding is guaranteed to match without any implicit coupling.
    private val format: BinaryFormat = Cbor

    private fun sequencer(node: FakeRaftNode = FakeRaftNode()) =
        TurnSequencer(node, serializer<Move>(), format)

    // Encode a Move to bytes in the same way TurnSequencer does, for injecting
    // via FakeRaftNode.pushCommitted. Uses the shared [format] instance.
    private fun encodeMove(move: Move): ByteArray =
        format.encodeToByteArray(serializer<Move>(), move)

    // The committed-IndexedAction view of the events stream, dropping resets.
    private fun TurnSequencer<Move>.committedActions(): Flow<IndexedAction<Move>> =
        events.filterIsInstance<TurnEvent.Committed<Move>>().map { it.indexed }

    // ── events: committed actions ───────────────────────────────────────────────

    @Test
    fun proposedActionAppearsOnEventsFlow() = runTest(timeout = 5.seconds) {
        val node = FakeRaftNode()
        node.setRole(RaftRole.Leader)
        val seq = sequencer(node)

        val action = Move(player = 1, card = 3)
        seq.propose(action)

        val committed = seq.committedActions().first()
        assertEquals(action, committed.action)
    }

    @Test
    fun proposeReturnsAssignedIndexAndAction() = runTest(timeout = 5.seconds) {
        val node = FakeRaftNode()
        node.setRole(RaftRole.Leader)
        val seq = sequencer(node)

        val indexed = seq.propose(Move(player = 1, card = 2))

        assertEquals(1L, indexed.index)
        assertEquals(Move(player = 1, card = 2), indexed.action)
    }

    @Test
    fun proposedActionRoundTripsSerializer() = runTest(timeout = 5.seconds) {
        val node = FakeRaftNode()
        node.setRole(RaftRole.Leader)
        val seq = sequencer(node)

        val action = Move(player = 2, card = 7)
        seq.propose(action)

        val committed = seq.committedActions().first()
        assertEquals(action, committed.action)
    }

    @Test
    fun multipleActionsAreOrderedByIndex() = runTest(timeout = 5.seconds) {
        val node = FakeRaftNode()
        node.setRole(RaftRole.Leader)
        val seq = sequencer(node)

        seq.propose(Move(player = 1, card = 1))
        seq.propose(Move(player = 2, card = 2))
        seq.propose(Move(player = 3, card = 3))

        val results = seq.committedActions().take(3).toList()
        assertEquals(3, results.size)
        assertEquals(listOf(1L, 2L, 3L), results.map { it.index })
        assertEquals(listOf(Move(1, 1), Move(2, 2), Move(3, 3)), results.map { it.action })
    }

    @Test
    fun externalCommitAppearsOnEventsFlow() = runTest(timeout = 5.seconds) {
        val node = FakeRaftNode()
        val seq = sequencer(node)

        val action = Move(player = 1, card = 5)
        // A commit replicated from another node carries that node's stamped dedup key.
        node.pushCommitted(stampedEntry(index = 1L, move = action, requestId = 1L))

        val committed = seq.committedActions().first()
        assertEquals(action, committed.action)
        assertEquals(1L, committed.index)
    }

    @Test
    fun indexedActionReflectsRaftLogIndex() = runTest(timeout = 5.seconds) {
        val node = FakeRaftNode()
        node.setRole(RaftRole.Leader)
        val seq = sequencer(node)

        val result = seq.propose(Move(player = 1, card = 4))

        // propose returns the IndexedAction mirroring the committed log entry.
        assertEquals(Move(player = 1, card = 4), result.action)
        assertEquals(result.index, seq.committedActions().first().index)
    }

    @Test
    fun propose_fromForwardingFollower_commits() = runTest(timeout = 5.seconds) {
        val node = FakeRaftNode()
        // Simulate forwarding: follower node succeeds (as a real forwarding RaftNode would).
        node.proposeBehavior = { command -> node.pushCommitted(command) }
        val seq = sequencer(node)

        val indexed = seq.propose(Move(player = 1, card = 7))

        assertEquals(Move(player = 1, card = 7), indexed.action)
        assertEquals(1L, indexed.index)
    }

    @Test
    fun propose_onLeadershipLost_propagatesLeadershipLostException() = runTest(timeout = 5.seconds) {
        val node = FakeRaftNode()
        node.setRole(RaftRole.Leader)
        val raftCause = LeadershipLostException("lost during test")
        node.proposeBehavior = { _ -> throw raftCause }
        val seq = sequencer(node)

        val ex = assertFailsWith<LeadershipLostException> {
            seq.propose(Move(player = 1, card = 1))
        }
        assertEquals(raftCause, ex)
    }

    // ── (a) apply-side dedup: same DedupKey ⇒ exactly one admitted ───────────────

    @Test
    fun duplicateDedupKeyAdmittedExactlyOnceThroughClientSessionTable() = runTest(timeout = 5.seconds) {
        val node = FakeRaftNode()
        val seq = sequencer(node)

        val client = ClientId("durable-client")
        val move = Move(player = 1, card = 9)
        // Two committed entries bearing the SAME dedup key — a cross-crash retry replicated twice.
        node.pushCommitted(stampedEntry(index = 1L, move = move, clientId = client, requestId = 7L))
        node.pushCommitted(stampedEntry(index = 2L, move = move, clientId = client, requestId = 7L))

        // Fold both events through a ClientSessionTable, exactly as a consumer's apply loop would.
        val table = ClientSessionTable()
        val admitted = seq.committedActions()
            .take(2)
            .toList()
            .count { table.shouldApply(it.dedupKey) }

        assertEquals(1, admitted, "the same DedupKey must be admitted exactly once")
    }

    @Test
    fun distinctDedupKeysAllAdmitted() = runTest(timeout = 5.seconds) {
        val node = FakeRaftNode()
        val seq = sequencer(node)

        val client = ClientId("durable-client")
        node.pushCommitted(stampedEntry(index = 1L, move = Move(1, 1), clientId = client, requestId = 1L))
        node.pushCommitted(stampedEntry(index = 2L, move = Move(2, 2), clientId = client, requestId = 2L))

        val table = ClientSessionTable()
        val admitted = seq.committedActions()
            .take(2)
            .toList()
            .count { table.shouldApply(it.dedupKey) }

        assertEquals(2, admitted, "distinct request ids must each be admitted")
    }

    // ── (b) install surfacing: Committed.Install ⇒ TurnEvent.Reset ───────────────

    @Test
    fun installSurfacesAsResetEvent() = runTest(timeout = 5.seconds) {
        val node = FakeRaftNode()
        val seq = sequencer(node)

        val snapshot = Snapshot(throughIndex = 4L, state = byteArrayOf(1, 2, 3))
        node.pushInstall(snapshot)

        val event = seq.events.first()
        assertTrue(event is TurnEvent.Reset, "install must surface as Reset, not be dropped")
        // Assert the snapshot survives the mapping by value (throughIndex + bytes), not by reference.
        assertEquals(4L, event.snapshot.throughIndex)
        assertTrue(byteArrayOf(1, 2, 3).contentEquals(event.snapshot.state), "snapshot bytes must survive")
    }

    @Test
    fun unkeyedCommittedEntryFailsLoud() = runTest(timeout = 5.seconds) {
        val node = FakeRaftNode()
        val seq = sequencer(node)

        // A legacy/unkeyed entry is vestigial and unsupported by the facade — it must fail loud,
        // never silently drop the dedup key or the entry.
        node.pushCommitted(LogEntry(index = 1L, term = 1L, command = encodeMove(Move(1, 1)), dedupKey = null))

        assertFailsWith<IllegalStateException> { seq.events.first() }
    }

    @Test
    fun installInterleavesInOrderWithCommittedEntries() = runTest(timeout = 5.seconds) {
        val node = FakeRaftNode()
        val seq = sequencer(node)

        node.pushCommitted(stampedEntry(index = 1L, move = Move(1, 1), requestId = 1L))
        val snapshot = Snapshot(throughIndex = 5L, state = byteArrayOf(9))
        node.pushInstall(snapshot)
        node.pushCommitted(stampedEntry(index = 6L, move = Move(6, 6), requestId = 2L))

        val events = seq.events.take(3).toList()
        assertTrue(events[0] is TurnEvent.Committed)
        assertTrue(events[1] is TurnEvent.Reset)
        assertTrue(events[2] is TurnEvent.Committed)
        assertEquals(snapshot, (events[1] as TurnEvent.Reset).snapshot)
    }

    // ── (c) propose stamps the dedup key ─────────────────────────────────────────

    @Test
    fun proposeWithRequestIdStampsDedupKeyOnCommittedEvent() = runTest(timeout = 5.seconds) {
        val node = FakeRaftNode(clientId = ClientId("durable-x"))
        node.setRole(RaftRole.Leader)
        val seq = sequencer(node)

        seq.propose(Move(player = 1, card = 3), requestId = 7L)

        val committed = seq.committedActions().first()
        assertEquals(DedupKey(ClientId("durable-x"), 7L), committed.dedupKey)
    }

    @Test
    fun proposeWithRequestId_overForwardingFollower_ridesTheKeyUnchanged() = runTest(timeout = 5.seconds) {
        // A follower forwards the proposal to the leader; the durable (clientId, requestId) must
        // ride the forward hop unchanged — this is the cross-crash exactly-once mechanism of #616.
        val node = FakeRaftNode(clientId = ClientId("durable-fwd"))
        node.proposeBehavior = { command -> node.pushCommitted(command) } // forwarding follower commits
        val seq = sequencer(node)

        val indexed = seq.propose(Move(player = 1, card = 7), requestId = 3L)

        assertEquals(DedupKey(ClientId("durable-fwd"), 3L), indexed.dedupKey)
        assertEquals(DedupKey(ClientId("durable-fwd"), 3L), seq.committedActions().first().dedupKey)
    }

    @Test
    fun durableReplayOfSameRequestIdYieldsExactlyOneApply() = runTest(timeout = 5.seconds) {
        // End-to-end headline guarantee: a durable client that replays the SAME requestId after a
        // crash (here: a second propose under the same clientId+requestId) commits twice on the wire
        // but applies exactly once when folded through a ClientSessionTable.
        val node = FakeRaftNode(clientId = ClientId("durable-reconnect"))
        node.setRole(RaftRole.Leader)
        val seq = sequencer(node)

        seq.propose(Move(player = 1, card = 1), requestId = 7L)
        seq.propose(Move(player = 1, card = 1), requestId = 7L) // the post-crash retry

        val table = ClientSessionTable()
        val admitted = seq.committedActions()
            .take(2)
            .toList()
            .count { table.shouldApply(it.dedupKey) }

        assertEquals(1, admitted, "replaying the same requestId must apply exactly once")
    }

    @Test
    fun proposeWithRequestIdReturnsStampedIndexedAction() = runTest(timeout = 5.seconds) {
        val node = FakeRaftNode(clientId = ClientId("durable-x"))
        node.setRole(RaftRole.Leader)
        val seq = sequencer(node)

        val indexed = seq.propose(Move(player = 1, card = 3), requestId = 7L)

        assertEquals(DedupKey(ClientId("durable-x"), 7L), indexed.dedupKey)
        assertEquals(Move(player = 1, card = 3), indexed.action)
    }

    @Test
    fun autoSerialProposeStillStampsADedupKey() = runTest(timeout = 5.seconds) {
        val node = FakeRaftNode(clientId = ClientId("auto-ish"))
        node.setRole(RaftRole.Leader)
        val seq = sequencer(node)

        val indexed = seq.propose(Move(player = 1, card = 1))

        // The auto-serial path stamps a monotonic serial under this node's clientId — never null.
        assertEquals(ClientId("auto-ish"), indexed.dedupKey.clientId)
    }

    // ── (d) clientId stability: same client across proposes ──────────────────────

    @Test
    fun proposesUnderTheSameNodeShareClientId() = runTest(timeout = 5.seconds) {
        val node = FakeRaftNode(clientId = ClientId("durable-stable"))
        node.setRole(RaftRole.Leader)
        val seq = sequencer(node)

        val first = seq.propose(Move(player = 1, card = 1), requestId = 1L)
        val second = seq.propose(Move(player = 1, card = 2), requestId = 2L)

        assertEquals(ClientId("durable-stable"), first.dedupKey.clientId)
        assertEquals(ClientId("durable-stable"), second.dedupKey.clientId)
        assertEquals(first.dedupKey.clientId, second.dedupKey.clientId)
        // requestIds differ — only the clientId is stable.
        assertEquals(1L, first.dedupKey.requestId)
        assertEquals(2L, second.dedupKey.requestId)
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private fun stampedEntry(
        index: Long,
        move: Move,
        clientId: ClientId = ClientId("test-client"),
        requestId: Long,
    ): LogEntry = LogEntry(
        index = index,
        term = 1L,
        command = encodeMove(move),
        dedupKey = DedupKey(clientId, requestId),
    )
}
