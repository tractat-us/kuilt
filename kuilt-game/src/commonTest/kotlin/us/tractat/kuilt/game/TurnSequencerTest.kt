@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.game

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.BinaryFormat
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.serializer
import us.tractat.kuilt.raft.LeadershipLostException
import us.tractat.kuilt.raft.RaftRole
import us.tractat.kuilt.raft.test.FakeRaftNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    @Test
    fun proposedActionAppearsOnCommittedFlow() = runTest(timeout = 5.seconds) {
        val node = FakeRaftNode()
        node.setRole(RaftRole.Leader)
        val seq = sequencer(node)

        val action = Move(player = 1, card = 3)
        seq.propose(action)

        val committed = seq.committed.first()
        assertEquals(action, committed.action)
    }

    @Test
    fun committedFlowCarriesAssignedIndex() = runTest(timeout = 5.seconds) {
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

        val committed = seq.committed.first()
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

        val results = seq.committed.take(3).toList()
        assertEquals(3, results.size)
        assertEquals(listOf(1L, 2L, 3L), results.map { it.index })
        assertEquals(listOf(Move(1, 1), Move(2, 2), Move(3, 3)), results.map { it.action })
    }

    @Test
    fun externalCommitAppearsOnCommittedFlow() = runTest(timeout = 5.seconds) {
        val node = FakeRaftNode()
        val seq = sequencer(node)

        val action = Move(player = 1, card = 5)
        node.pushCommitted(encodeMove(action))

        val committed = seq.committed.first()
        assertEquals(action, committed.action)
        assertEquals(1L, committed.index)
    }

    @Test
    fun indexedActionReflectsRaftLogIndex() = runTest(timeout = 5.seconds) {
        val node = FakeRaftNode()
        node.setRole(RaftRole.Leader)
        val seq = sequencer(node)

        val result = seq.propose(Move(player = 1, card = 4))

        // propose returns the IndexedAction mirroring the committed log entry
        assertEquals(Move(player = 1, card = 4), result.action)
        assertEquals(result.index, seq.committed.first().index)
    }

    @Test
    fun propose_fromForwardingFollower_commits() = runTest(timeout = 5.seconds) {
        val node = FakeRaftNode()
        // Simulate forwarding: follower node succeeds (as a real forwarding RaftNode would)
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
}
