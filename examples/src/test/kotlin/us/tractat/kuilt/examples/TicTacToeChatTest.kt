@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.examples

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.RgaId
import us.tractat.kuilt.game.TurnEvent
import us.tractat.kuilt.game.TurnSequencer
import us.tractat.kuilt.game.gameNode
import us.tractat.kuilt.quilter.QuiltMessage
import us.tractat.kuilt.quilter.Quilter
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.raft.LeadershipLostException
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftConfig
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Integration example: tic-tac-toe (consensus via `gameNode`) **and** a convergent
 * CRDT chat log ([Rga] + [Quilter]), both riding a single shared [InMemoryLoom] fabric.
 *
 * Two peers bootstrap with [gameNode] (roster-given path — Raft elects the leader
 * symmetrically, no pre-Raft coordination step). The game is driven through
 * [TurnSequencer] over [us.tractat.kuilt.game.GameSession.node]; chat rides
 * [us.tractat.kuilt.game.GameSession.appChannel] on the same fabric connection.
 *
 * Raft leadership and turn ownership are deliberately orthogonal: turns are derived
 * from the count of committed moves (even = X/alice, odd = O/bob). Either peer may
 * call [TurnSequencer.propose]; a follower forwards to the leader transparently.
 * The test scripts both peers proposing so that the non-leader's proposals always
 * exercise the forwarding path.
 */
class TicTacToeChatTest {

    private enum class Mark { X, O }

    @Serializable
    private data class Move(val row: Int, val col: Int)

    private val raftCfg = RaftConfig(
        electionTimeoutMin = 5.milliseconds,
        electionTimeoutMax = 10.milliseconds,
        heartbeatInterval = 2.milliseconds,
        expectVirtualTime = true,
        random = Random(42),
    )

    private val chatCfg = QuilterConfig(expectVirtualTime = true)
    private val chatMsgSer = QuiltMessage.serializer(Rga.wireSerializer(serializer<String>()))

    private fun winner(board: Map<Move, Mark>): Mark? {
        val lines = listOf(
            listOf(Move(0, 0), Move(0, 1), Move(0, 2)),
            listOf(Move(1, 0), Move(1, 1), Move(1, 2)),
            listOf(Move(2, 0), Move(2, 1), Move(2, 2)),
            listOf(Move(0, 0), Move(1, 0), Move(2, 0)),
            listOf(Move(0, 1), Move(1, 1), Move(2, 1)),
            listOf(Move(0, 2), Move(1, 2), Move(2, 2)),
            listOf(Move(0, 0), Move(1, 1), Move(2, 2)),
            listOf(Move(0, 2), Move(1, 1), Move(2, 0)),
        )
        for (line in lines) {
            val marks = line.map { board[it] }
            if (marks[0] != null && marks.all { it == marks[0] }) return marks[0]
        }
        return null
    }

    private fun isOver(board: Map<Move, Mark>) = winner(board) != null || board.size == 9

    private fun buildBoard(moves: List<Move>): Map<Move, Mark> =
        moves.foldIndexed(emptyMap()) { i, board, move ->
            board + (move to if (i % 2 == 0) Mark.X else Mark.O)
        }

    /**
     * Proposes [move] via [game], retrying on [LeadershipLostException] (leader stepped
     * down mid-flight). [TurnSequencer.propose] re-awaits a new leader before forwarding
     * on each retry.
     */
    private suspend fun proposeWithRetry(game: TurnSequencer<Move>, move: Move) {
        while (true) {
            try {
                game.propose(move)
                return
            } catch (e: LeadershipLostException) {
                delay(1)
            }
        }
    }

    @Test
    fun `game moves and chat log both converge across two real peers`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val loom = InMemoryLoom()
            val seamAlice = loom.host(Pattern("ttt+chat"))
            val seamBob = loom.join(InMemoryTag("bob"))

            // Both peers bootstrap with gameNode — identical voter set, Raft elects the leader.
            val voterIds = setOf(NodeId(seamAlice.selfId.value), NodeId(seamBob.selfId.value))
            val aliceSession = backgroundScope.gameNode(seamAlice, voterIds, raftConfig = raftCfg)
            val bobSession = backgroundScope.gameNode(seamBob, voterIds, raftConfig = raftCfg)

            val aliceGame = TurnSequencer(aliceSession.node, serializer<Move>())
            val bobGame = TurnSequencer(bobSession.node, serializer<Move>())

            // Chat rides appChannel("chat") on the same fabric — no second connection needed.
            val aliceChat = Quilter(
                replica = ReplicaId(seamAlice.selfId.value),
                seam = aliceSession.appChannel("chat"),
                initial = Rga.empty(),
                messageSerializer = chatMsgSer,
                scope = backgroundScope,
                config = chatCfg,
            )
            val bobChat = Quilter(
                replica = ReplicaId(seamBob.selfId.value),
                seam = bobSession.appChannel("chat"),
                initial = Rga.empty(),
                messageSerializer = chatMsgSer,
                scope = backgroundScope,
                config = chatCfg,
            )

            // Scripted moves: X wins by filling the top row.
            // X (alice, even turns 0, 2, 4): (0,0) → (0,1) → (0,2)
            // O (bob,   odd  turns 1, 3):    (1,0) → (1,1)
            val aliceScript = listOf(Move(0, 0), Move(0, 1), Move(0, 2))
            val bobScript = listOf(Move(1, 0), Move(1, 1))

            val aliceResult = CompletableDeferred<List<Move>>()
            val bobResult = CompletableDeferred<List<Move>>()

            fun launchPlayLoop(
                game: TurnSequencer<Move>,
                script: List<Move>,
                isMine: (turnIndex: Int) -> Boolean,
                result: CompletableDeferred<List<Move>>,
                skipFirstProposal: Boolean,
            ): Job = launch {
                val committed = mutableListOf<Move>()
                var scriptIndex = if (skipFirstProposal) 1 else 0
                game.events.filterIsInstance<TurnEvent.Committed<Move>>().collect { event ->
                    val move = event.indexed.action
                    committed.add(move)
                    val board = buildBoard(committed)
                    if (isOver(board)) {
                        result.complete(committed.toList())
                        throw CancellationException("game over")
                    }
                    val turnIndex = committed.size
                    if (isMine(turnIndex) && scriptIndex < script.size) {
                        proposeWithRetry(game, script[scriptIndex++])
                    }
                }
            }

            val aliceLoop = launchPlayLoop(aliceGame, aliceScript, isMine = { it % 2 == 0 }, aliceResult, skipFirstProposal = true)
            val bobLoop = launchPlayLoop(bobGame, bobScript, isMine = { it % 2 == 1 }, bobResult, skipFirstProposal = false)
            delay(1) // let collectors subscribe under StandardTestDispatcher

            // Kick off the game; the play loops handle all subsequent moves.
            proposeWithRetry(aliceGame, aliceScript[0])
            delay(50) // advance virtual time to drive replication and remaining play turns

            val committedMovesAlice = aliceResult.await()
            val committedMovesBob = bobResult.await()

            aliceLoop.cancelAndJoin()
            bobLoop.cancelAndJoin()

            val finalBoardAlice = buildBoard(committedMovesAlice)
            val finalBoardBob = buildBoard(committedMovesBob)

            // X (alice) wins the top row; both peers see identical committed sequences.
            assertEquals(Mark.X, winner(finalBoardAlice))
            assertEquals(finalBoardAlice, finalBoardBob)
            assertEquals(committedMovesAlice, committedMovesBob)

            // Both peers post a chat message; the RGA logs must converge.
            fun Quilter<Rga<String>>.chat(msg: String) {
                val (_, op) = state.value.insertAfter(replica, RgaId.HEAD, msg)
                apply(Patch(Rga.empty<String>().apply(op)))
            }
            aliceChat.chat("alice: gg")
            bobChat.chat("bob: gg")
            delay(10) // let delta broadcasts deliver

            assertEquals(aliceChat.state.value.toList(), bobChat.state.value.toList())
            assertEquals(2, aliceChat.state.value.toList().size)
        }
}
