@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.game

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.serializer
import us.tractat.kuilt.raft.RaftRole
import us.tractat.kuilt.raft.test.FakeRaftNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class TurnSequencerTest {

    @Serializable
    private data class Move(val player: Int, val card: Int)

    private fun sequencer(node: FakeRaftNode = FakeRaftNode()) =
        TurnSequencer(node, serializer<Move>())

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

    // Encode a Move to bytes in the same way TurnSequencer does, for injecting
    // via FakeRaftNode.pushCommitted. This must match TurnSequencer's encoding.
    private fun encodeMove(move: Move): ByteArray {
        val serializer = serializer<Move>()
        return kotlinx.serialization.cbor.Cbor.encodeToByteArray(serializer, move)
    }
}
