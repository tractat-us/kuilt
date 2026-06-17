@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.game

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import us.tractat.kuilt.raft.RaftRole
import us.tractat.kuilt.raft.test.FakeRaftNode
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Samples for the game facade API used by `@sample` KDoc tags.
 *
 * Every function here is compiled as part of commonTest so a typo or API
 * change will break the build, not silently produce stale documentation.
 */

// ── TurnSequencer ─────────────────────────────────────────────────────────────

/**
 * [TurnSequencer] for a tic-tac-toe game.
 *
 * Hides all Raft machinery — [propose] submits a typed action and suspends
 * until a quorum commits it; [TurnSequencer.committed] delivers every
 * committed action in order on all nodes (leader and followers alike).
 *
 * In production, replace [FakeRaftNode] with a real `raftNode(...)` connected
 * to peers over a [us.tractat.kuilt.core.Seam].
 */
@Suppress("unused")
internal fun sampleTurnSequencer() = runTest(timeout = 5.seconds) {
    @Serializable data class Move(val row: Int, val col: Int)

    val node = FakeRaftNode()
    node.setRole(RaftRole.Leader)
    val game = TurnSequencer(node, serializer<Move>())

    // On every node — collect the committed turn stream.
    // scope.launch { game.committed.collect { (index, move) -> applyMove(index, move) } }

    // On any node — propose the local player's move (forwarded to the leader if needed).
    game.propose(Move(row = 0, col = 0))  // X top-left
    game.propose(Move(row = 1, col = 1))  // O centre
    game.propose(Move(row = 0, col = 1))  // X top-centre

    val committed = game.committed.take(3).toList().map { it.action }
    assertEquals(listOf(Move(0, 0), Move(1, 1), Move(0, 1)), committed)
}
