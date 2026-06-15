@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.examples

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import us.tractat.kuilt.game.TurnSequencer
import us.tractat.kuilt.raft.RaftRole
import us.tractat.kuilt.raft.test.FakeRaftNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Example: tic-tac-toe moves over [TurnSequencer] + [FakeRaftNode].
 *
 * Shows how to use kuilt-game's [TurnSequencer] to commit typed game actions
 * through a Raft log. In production, replace [FakeRaftNode] with a real
 * `RaftNode` connected to peers over a [us.tractat.kuilt.core.Seam].
 */
class TicTacToeTest {

    @Serializable
    private data class Move(val row: Int, val col: Int)

    @Test
    fun `moves are committed in order`() = runTest(timeout = 5.seconds) {
        val node = FakeRaftNode()
        node.setRole(RaftRole.Leader)
        val game = TurnSequencer(node, serializer<Move>())

        game.propose(Move(0, 0))  // X top-left
        game.propose(Move(1, 1))  // O center
        game.propose(Move(0, 1))  // X top-center

        val committed = game.committed.take(3).toList().map { it.action }
        assertEquals(listOf(Move(0, 0), Move(1, 1), Move(0, 1)), committed)
    }
}
