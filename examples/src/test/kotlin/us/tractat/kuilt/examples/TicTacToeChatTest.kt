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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.MuxSeam
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.RgaId
import us.tractat.kuilt.quilter.ReplicatorMessage
import us.tractat.kuilt.quilter.SeamReplicator
import us.tractat.kuilt.quilter.SeamReplicatorConfig
import us.tractat.kuilt.game.IndexedAction
import us.tractat.kuilt.game.TurnSequencer
import us.tractat.kuilt.raft.ClusterConfig
import us.tractat.kuilt.raft.InMemoryRaftStorage
import us.tractat.kuilt.raft.LeadershipLostException
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftConfig
import us.tractat.kuilt.raft.SeamRaftTransport
import us.tractat.kuilt.raft.raftNode
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Integration example: tic-tac-toe (consensus via real [raftNode]) **and** side chat
 * ([Rga] + [SeamReplicator]) over a single shared [InMemoryLoom] fabric. Two peers.
 *
 * ## Why this example matters — the forwarding litmus test
 *
 * In a 2-peer cluster, exactly one peer is the Raft leader at any time. Both
 * players must be able to propose moves. Leader-forwarding (issue #479) makes
 * [us.tractat.kuilt.raft.RaftNode.propose] callable from any peer: the leader
 * appends directly; a follower forwards to the leader and awaits commit.
 *
 * Raft leadership and turn ownership are deliberately ORTHOGONAL: turns are
 * derived from the count of committed moves (even = X/alice, odd = O/bob);
 * which peer is the Raft leader is irrelevant to whose turn it is. Each peer
 * proposes its own moves via its own [TurnSequencer], and the non-leader's
 * proposals exercise the forwarding path.
 *
 * ## API-leakage inventory (issue #479 probe)
 *
 * A "tic-tac-toe with chat" author currently must name these Raft/Seam internals:
 *
 * - [ClusterConfig] / [ClusterConfig.ofVoters] — cluster membership
 * - [NodeId] — Raft node identity, must align with [SeamRaftTransport]'s selfId mapping
 * - [SeamRaftTransport] — bridges a [us.tractat.kuilt.core.Seam] to the Raft transport interface
 * - [InMemoryRaftStorage] — Raft log / term / vote durability
 * - [RaftConfig.expectVirtualTime] — test-guard suppression flag
 * - [MuxSeam] — multiplex raft and the replicator over the single-collection seam
 * - [SeamReplicatorConfig.expectVirtualTime] — test-guard suppression on the CRDT side
 * - [Rga.wireSerializer] — custom serializer required for CBOR; the generated one fails
 * - [Patch] / [Rga.empty] / [Rga.apply] — wrap a local op into a [Patch] for [SeamReplicator.apply]
 *
 * See follow-up issue #480 for a proposed facade that hides this surface.
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

    private val chatCfg = SeamReplicatorConfig(expectVirtualTime = true)
    private val chatMsgSer = ReplicatorMessage.serializer(Rga.wireSerializer(serializer<String>()))

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
     * down mid-flight). [propose] re-awaits a new leader before forwarding on each retry.
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

            // Weave two seams — IDs are assigned by the loom after weaving.
            val seamAlice = loom.host(Pattern("ttt+chat"))
            val seamBob = loom.join(InMemoryTag("bob"))

            // MuxSeam: channel 0 = raft traffic, channel 1 = chat traffic.
            // Required because Seam.incoming is single-collection — only MuxSeam
            // may collect from the underlying seam; each channel gets its own view.
            val muxAlice = MuxSeam(seamAlice, backgroundScope)
            val muxBob = MuxSeam(seamBob, backgroundScope)

            // Build ClusterConfig after weaving — the IDs are not known until then.
            // Both nodes must agree on the same config.
            val aliceId = NodeId(seamAlice.selfId.value)
            val bobId = NodeId(seamBob.selfId.value)
            val cluster = ClusterConfig.ofVoters(listOf(aliceId, bobId))

            // Game layer: real RaftNode + TurnSequencer for each peer.
            val aliceNode = backgroundScope.raftNode(cluster, SeamRaftTransport(muxAlice.channel(0)), InMemoryRaftStorage(), raftCfg)
            val bobNode = backgroundScope.raftNode(cluster, SeamRaftTransport(muxBob.channel(0)), InMemoryRaftStorage(), raftCfg)
            val aliceGame = TurnSequencer(aliceNode, serializer<Move>())
            val bobGame = TurnSequencer(bobNode, serializer<Move>())

            // Chat layer: SeamReplicator<Rga<String>> for each peer.
            val aliceChat = SeamReplicator(
                replica = ReplicaId(seamAlice.selfId.value),
                seam = muxAlice.channel(1),
                initial = Rga.empty(),
                messageSerializer = chatMsgSer,
                scope = backgroundScope,
                config = chatCfg,
            )
            val bobChat = SeamReplicator(
                replica = ReplicaId(seamBob.selfId.value),
                seam = muxBob.channel(1),
                initial = Rga.empty(),
                messageSerializer = chatMsgSer,
                scope = backgroundScope,
                config = chatCfg,
            )

            // Scripted moves: X wins by filling the top row.
            // X (alice, even turns 0, 2, 4): (0,0) → (0,1) → (0,2)
            // O (bob,   odd  turns 1, 3):    (1,0) → (1,1)
            //
            // Raft leadership is orthogonal to turns. Whichever peer is NOT the Raft
            // leader will forward its proposals via RaftNode.propose() — that is the
            // central behaviour this test validates.
            val aliceScript = listOf(Move(0, 0), Move(0, 1), Move(0, 2))
            val bobScript = listOf(Move(1, 0), Move(1, 1))

            // Each play loop:
            //   - Collects committed moves until the game is over.
            //   - After each commit, if it is this peer's turn, proposes the next scripted move.
            //   - The first move (aliceScript[0]) is proposed externally below; the loop skips it.
            //   - Signals completion via a CompletableDeferred<List<Move>>.
            //
            // The loop runs in a Job that is cancelled once the result is delivered, so it
            // does not linger as an uncompleted coroutine under runTest.
            val aliceResult = CompletableDeferred<List<Move>>()
            val bobResult = CompletableDeferred<List<Move>>()

            fun launchPlayLoop(
                game: TurnSequencer<Move>,
                script: List<Move>,
                isMine: (turnIndex: Int) -> Boolean,
                result: CompletableDeferred<List<Move>>,
                // The first move is proposed externally; skip it in the loop.
                skipFirstProposal: Boolean,
            ): Job = launch {
                val committed = mutableListOf<Move>()
                // Skip script index 0 if this peer's first proposal was made externally.
                var scriptIndex = if (skipFirstProposal) 1 else 0
                game.committed.collect { (_, move): IndexedAction<Move> ->
                    committed.add(move)
                    val board = buildBoard(committed)
                    if (isOver(board)) {
                        result.complete(committed.toList())
                        // Cancel this coroutine to break out of collect.
                        // CancellationException propagates to complete the Job cleanly.
                        throw CancellationException("game over")
                    }
                    val turnIndex = committed.size // index of the next (not-yet-played) turn
                    if (isMine(turnIndex) && scriptIndex < script.size) {
                        proposeWithRetry(game, script[scriptIndex++])
                    }
                }
            }

            val aliceLoop = launchPlayLoop(aliceGame, aliceScript, isMine = { it % 2 == 0 }, aliceResult, skipFirstProposal = true)
            val bobLoop = launchPlayLoop(bobGame, bobScript, isMine = { it % 2 == 1 }, bobResult, skipFirstProposal = false)
            delay(1) // let collectors subscribe under StandardTestDispatcher

            // Kick off the game with alice's first move.
            // propose() suspends until a leader is elected — if alice is not the leader,
            // the proposal is forwarded automatically. Both play loops then handle all
            // subsequent moves, with whichever peer is the non-leader forwarding its proposals.
            proposeWithRetry(aliceGame, aliceScript[0])
            delay(50) // advance virtual time to drive replication and remaining play turns

            val committedMovesAlice = aliceResult.await()
            val committedMovesBob = bobResult.await()

            aliceLoop.cancelAndJoin()
            bobLoop.cancelAndJoin()

            val finalBoardAlice = buildBoard(committedMovesAlice)
            val finalBoardBob = buildBoard(committedMovesBob)

            // X (alice) should win with a top-row run; both peers must see identical committed
            // sequences (the core Raft consensus guarantee).
            assertEquals(Mark.X, winner(finalBoardAlice))
            assertEquals(finalBoardAlice, finalBoardBob)
            assertEquals(committedMovesAlice, committedMovesBob)

            // Both peers send a chat message and the RGA logs must converge.
            fun SeamReplicator<Rga<String>>.chat(msg: String) {
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
