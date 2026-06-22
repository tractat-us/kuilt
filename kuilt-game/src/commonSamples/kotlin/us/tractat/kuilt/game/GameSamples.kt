@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.game

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.RgaId
import us.tractat.kuilt.quilter.QuiltMessage
import us.tractat.kuilt.quilter.Quilter
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.raft.NodeId
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
 * [TurnSequencer.events]. Ride extra application traffic (chat, cursors, …) over
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
    assertEquals(2, incoming.await().payloadSize)

    // Collect committed turns on any node in the game loop:
    // scope.launch {
    //     joinerGame.events.collect { event ->
    //         when (event) {
    //             is TurnEvent.Committed -> applyMove(event.indexed.index, event.indexed.action)
    //             is TurnEvent.Reset -> resetStateMachine(event.snapshot)
    //         }
    //     }
    // }

    // Tear the session down when done (stops the node, then closes the fabric).
    host.close()
    joiner.close()
}

// ── gameNode ──────────────────────────────────────────────────────────────────

/**
 * [gameNode] over an [InMemoryLoom]: roster-given bootstrap path.
 *
 * Every peer builds the same [NodeId] set and calls [gameNode]; Raft's own election
 * picks the leader symmetrically — no pre-Raft coordination step required.
 * Returns a [GameSession] immediately (no waiting for other peers).
 *
 * Use this path when all participating peers' identities are known before the
 * session starts (e.g. from matchmaking). For the appoint-the-host path (dynamic
 * join without a fixed roster) see [sampleGameHostJoin].
 */
@Suppress("unused")
internal fun sampleGameNode() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
    val loom = InMemoryLoom()
    val seam1 = loom.host(Pattern("my-game"))
    val seam2 = loom.join(InMemoryTag("player-2"))

    val id1 = NodeId(seam1.selfId.value)
    val id2 = NodeId(seam2.selfId.value)
    val voterIds = setOf(id1, id2)

    // Both peers call gameNode with the same voter set. Raft elects one leader.
    val session1 = backgroundScope.gameNode(seam1, voterIds, raftConfig = RaftConfig(expectVirtualTime = true))
    val session2 = backgroundScope.gameNode(seam2, voterIds, raftConfig = RaftConfig(expectVirtualTime = true))

    // Both sessions are live. Drive the game through TurnSequencer over either node:
    // propose() is forwarded to the leader transparently by Raft.
    // For a concrete propose + committed example see sampleGameHostJoin.
    TurnSequencer(session1.node, Int.serializer())
    TurnSequencer(session2.node, Int.serializer())

    // Ride named application channels (chat, cursors, …) over the same fabric.
    val chatIncoming = async { session2.appChannel("chat").incoming.first() }
    session1.appChannel("chat").broadcast(byteArrayOf(0x68, 0x69)) // "hi"
    assertEquals(2, chatIncoming.await().payloadSize)

    session1.close()
    session2.close()
}

// ── SpeculativeSequencer ──────────────────────────────────────────────────────

/**
 * [SpeculativeSequencer] for a simple counter game.
 *
 * Optimistically applies local moves before the Raft quorum commits them, then
 * rolls back and replays if the committed order differs from what was predicted.
 * The [SpeculativeGame] implementation must be **pure and deterministic** — replay
 * correctness depends on it.
 *
 * [speculativeState][SpeculativeSequencer.speculativeState] is always current (with
 * optimistically applied local moves on top); the UI can observe it directly.
 */
@Suppress("unused")
internal fun sampleSpeculativeSequencer() = runTest(timeout = 5.seconds) {
    // A trivially pure game: state is a list of committed integers.
    val counterGame = object : SpeculativeGame<List<Int>, Int> {
        override fun apply(state: List<Int>, action: Int): List<Int> = state + action
        override fun snapshot(state: List<Int>): List<Int> = state.toList()
        override fun restore(snapshot: List<Int>): List<Int> = snapshot.toList()
    }

    val node = FakeRaftNode()
    node.setRole(RaftRole.Leader)
    val sequencer = TurnSequencer(node, Int.serializer())

    val speculative = SpeculativeSequencer(
        sequencer = sequencer,
        game = counterGame,
        initialState = emptyList(),
        scope = backgroundScope,
    )

    // Optimistic apply: speculativeState reflects 42 immediately, before quorum.
    val proposed = async { speculative.propose(42) }
    assertEquals(listOf(42), speculative.speculativeState.value)

    // Once quorum confirms, pending count drops to 0.
    val indexed = proposed.await()
    assertEquals(42, indexed.action)
    speculative.awaitConfirmedCount(1)
    assertEquals(0, speculative.pendingCount)
    assertEquals(listOf(42), speculative.speculativeState.value)
}

// ── TurnSequencer ─────────────────────────────────────────────────────────────

/**
 * [TurnSequencer] for a tic-tac-toe game.
 *
 * Hides all Raft machinery — [propose] submits a typed action and suspends
 * until a quorum commits it; [TurnSequencer.events] delivers every committed
 * action ([TurnEvent.Committed]) in order on all nodes (leader and followers alike).
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

    val committed = game.events
        .filterIsInstance<TurnEvent.Committed<Move>>()
        .map { it.indexed.action }
        .take(3)
        .toList()
    assertEquals(listOf(Move(0, 0), Move(1, 1), Move(0, 1)), committed)
}

// ── chat over appChannel ──────────────────────────────────────────────────────

/**
 * Convergent CRDT chat log over [GameSession.appChannel], sharing the consensus fabric.
 *
 * Two peers each build a [Quilter]`<`[Rga]`<String>>` on `appChannel("chat")`. Because
 * [appChannel] is a named view within the mux already owned by the session, chat frames
 * travel over the same [us.tractat.kuilt.core.Seam] as Raft — no second connection. The
 * application owns the entire name namespace; there are no reserved names.
 *
 * Delivery is **best-effort** (`replay = 0`): a delta sent before the peer subscribes is
 * not replayed. Layer your own reliability on top if you need at-least-once delivery.
 */
@Suppress("unused")
internal fun sampleGameChat() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
    val loom = InMemoryLoom()
    val seamAlice = loom.host(Pattern("my-game"))
    val seamBob = loom.join(InMemoryTag("bob"))

    val id1 = NodeId(seamAlice.selfId.value)
    val id2 = NodeId(seamBob.selfId.value)
    val voterIds = setOf(id1, id2)

    val alice = backgroundScope.gameNode(seamAlice, voterIds, raftConfig = RaftConfig(expectVirtualTime = true))
    val bob = backgroundScope.gameNode(seamBob, voterIds, raftConfig = RaftConfig(expectVirtualTime = true))

    // Both peers attach a Quilter<Rga<String>> to the "chat" app channel.
    val chatMsgSer = QuiltMessage.serializer(Rga.wireSerializer(serializer<String>()))
    val aliceChat = Quilter(
        replica = ReplicaId(seamAlice.selfId.value),
        seam = alice.appChannel("chat"),
        initial = Rga.empty(),
        messageSerializer = chatMsgSer,
        scope = backgroundScope,
        config = QuilterConfig(expectVirtualTime = true),
    )
    val bobChat = Quilter(
        replica = ReplicaId(seamBob.selfId.value),
        seam = bob.appChannel("chat"),
        initial = Rga.empty(),
        messageSerializer = chatMsgSer,
        scope = backgroundScope,
        config = QuilterConfig(expectVirtualTime = true),
    )

    // Each peer appends a message to its local RGA replica, then broadcasts the delta.
    fun Quilter<Rga<String>>.chat(msg: String) {
        val (_, op) = state.value.insertAfter(replica, RgaId.HEAD, msg)
        apply(Patch(Rga.empty<String>().apply(op)))
    }
    aliceChat.chat("alice: hello")
    bobChat.chat("bob: hello")
    delay(10) // advance virtual time so delta broadcasts deliver to both peers

    // Both RGAs converge to the same ordered log of two messages.
    assertEquals(aliceChat.state.value.toList(), bobChat.state.value.toList())
    assertEquals(2, aliceChat.state.value.toList().size)

    alice.close()
    bob.close()
}
