@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.examples

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
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
import us.tractat.kuilt.crdt.replicator.ReplicatorMessage
import us.tractat.kuilt.crdt.replicator.SeamReplicator
import us.tractat.kuilt.crdt.replicator.SeamReplicatorConfig
import us.tractat.kuilt.game.TurnSequencer
import us.tractat.kuilt.raft.ClusterConfig
import us.tractat.kuilt.raft.InMemoryRaftStorage
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftConfig
import us.tractat.kuilt.raft.RaftRole
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

            // Collect the first 2 committed moves on both nodes before proposing.
            val aliceMoves = async { aliceGame.committed.take(2).toList().map { it.action } }
            val bobMoves = async { bobGame.committed.take(2).toList().map { it.action } }
            delay(1) // let collectors subscribe under StandardTestDispatcher

            // Wait for leader by advancing virtual time 1 ms per step.
            while (aliceNode.role.value !is RaftRole.Leader && bobNode.role.value !is RaftRole.Leader) {
                delay(1)
            }

            val leader = if (aliceNode.role.value is RaftRole.Leader) aliceGame else bobGame
            leader.propose(Move(0, 0))
            leader.propose(Move(1, 1))
            delay(10) // let raft replicate; both committed flows deliver

            val expectedMoves = listOf(Move(0, 0), Move(1, 1))
            assertEquals(expectedMoves, aliceMoves.await())
            assertEquals(expectedMoves, bobMoves.await())

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
