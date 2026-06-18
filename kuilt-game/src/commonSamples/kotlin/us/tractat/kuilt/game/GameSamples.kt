@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.game

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.serializer
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.raft.RaftConfig
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

// ── gameHost / gameJoin ───────────────────────────────────────────────────────

/**
 * [gameHost] and [gameJoin] over an [InMemoryLoom]: appoint-the-host bootstrap path.
 *
 * Exactly one peer calls [gameHost]; the rest call [gameJoin]. Both suspend until the
 * cluster reaches [gameHost]'s requested [peerCount] voters, then return a [GameSession].
 * Pass a plain [us.tractat.kuilt.core.Seam] in both cases — muxing (Raft channel tag 1,
 * presence channel tag 2, app-envelope channel tag 3) is internal.
 *
 * After the calls return, drive the game through [TurnSequencer] over [GameSession.node]: any
 * node may [TurnSequencer.propose]; commits are delivered in order on every node via
 * [TurnSequencer.committed]. Ride extra application traffic (chat, cursors, …) over
 * [GameSession.appChannel]; tear the session down with [GameSession.close].
 *
 * **Do not collect `seam.incoming` after calling [gameHost] or [gameJoin].** Each wraps
 * the seam in a [us.tractat.kuilt.core.MuxSeam] that becomes the sole consumer of
 * `seam.incoming` (ADR-034 single-collection). A second collector races the Raft engine.
 */
@Suppress("unused")
internal fun sampleGameHostJoin() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
    val loom = InMemoryLoom()
    val hostSeam = loom.host(Pattern("tic-tac-toe"))
    val joinSeam = loom.join(InMemoryTag("player-2"))

    // Launch concurrently: gameHost suspends while admitting joiners;
    // gameJoin suspends until the host promotes it to voter.
    val hostDeferred = async {
        backgroundScope.gameHost(
            hostSeam,
            peerCount = 2,
            raftConfig = RaftConfig(expectVirtualTime = true),
        )
    }
    val joinDeferred = async {
        backgroundScope.gameJoin(
            joinSeam,
            raftConfig = RaftConfig(expectVirtualTime = true),
        )
    }

    val host = hostDeferred.await()
    val joiner = joinDeferred.await()

    // Both nodes are voters. propose() may be called on any node —
    // followers forward to the leader transparently.
    val hostGame = TurnSequencer(host.node, Int.serializer())
    val joinerGame = TurnSequencer(joiner.node, Int.serializer())

    val move = hostGame.propose(1)
    assertEquals(1, move.action)

    // Any node may propose; the joiner's call is forwarded to the host (leader).
    val joinerMove = joinerGame.propose(2)
    assertEquals(2, joinerMove.action)

    // Ride an application channel (chat, cursors, …) over the same fabric as consensus.
    val incoming = async { joiner.appChannel("chat").incoming.first() }
    host.appChannel("chat").broadcast(byteArrayOf(0x68, 0x69)) // "hi"
    assertEquals(2, incoming.await().payload.size)

    // Collect committed turns on any node in the game loop:
    // scope.launch { joinerGame.committed.collect { (index, action) -> applyMove(index, action) } }

    // Tear the session down when done (stops the node, then closes the fabric).
    host.close()
    joiner.close()
}

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
