@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.game

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.quilter.Quilter
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.raft.Committed
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftNode
import us.tractat.kuilt.raft.RaftRole
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.fabric.InMemoryRoomFabric
import kotlin.coroutines.ContinuationInterceptor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Gating tests for the game-per-room composition ([gameHostedRoom] / [gameJoinRoom] /
 * [gameNodeRoom]): **N concurrent games on one server over one connection set, each an
 * independent, isolated [GameSession]** — the epic's "missing composition" of the hosted game
 * bootstrap with the session mux (`MuxServerLoom` / `RoomHubSeam`).
 *
 * The fabric is the packaged [InMemoryRoomFabric] double: its `serverLoom` is a real
 * `MuxServerLoom`, and each client reaches all of its rooms over **one** connection via a
 * `MuxClientLoom`. Ceremony per the repo test discipline: [StandardTestDispatcher], tight
 * timeout, per-node seeded RNG ([fastRaftConfig]), everything long-lived on `backgroundScope`,
 * bounded awaits (suspending `first { }` under delay-skipping) — never `advanceUntilIdle`.
 */
class GameRoomTest {

    /**
     * The server-core case: one server runs two games (`table-a`, `table-b`) over one
     * `MuxServerLoom`, each a Raft cluster whose only voter is the server
     * ([ConsensusPlacement.serverCore]). Alice plays in **both** games over one connection;
     * bob plays only in `table-b`. Each game admits exactly its own room's players (the
     * core-side admission loop's domain is the room seam), both games commit independently,
     * and a player in one room is structurally invisible to the other.
     */
    @Test
    fun oneServerRunsTwoIsolatedServerCoreGames() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val dispatcher = requireNotNull(coroutineContext[ContinuationInterceptor])
        val fabric = InMemoryRoomFabric(backgroundScope, dispatcher, random = Random(1))
        val core = setOf(NodeId("server"))
        val placement = ConsensusPlacement.serverCore(core)

        val serverA = backgroundScope.gameNodeRoom(
            fabric.serverLoom, "table-a", voterIds = core,
            raftConfig = fastRaftConfig(seed = 1L), random = Random(11), clock = inertTestClock,
            placement = placement,
        )
        val serverB = backgroundScope.gameNodeRoom(
            fabric.serverLoom, "table-b", voterIds = core,
            raftConfig = fastRaftConfig(seed = 2L), random = Random(12), clock = inertTestClock,
            placement = placement,
        )

        val aliceLoom = fabric.clientLoom(PeerId("alice"), Random(21))
        val bobLoom = fabric.clientLoom(PeerId("bob"), Random(22))

        val aliceA = backgroundScope.gameNodeRoom(
            aliceLoom, "table-a", voterIds = core,
            raftConfig = fastRaftConfig(seed = 3L), random = Random(13), clock = inertTestClock,
            placement = placement,
        )
        val aliceB = backgroundScope.gameNodeRoom(
            aliceLoom, "table-b", voterIds = core,
            raftConfig = fastRaftConfig(seed = 4L), random = Random(14), clock = inertTestClock,
            placement = placement,
        )
        val bobB = backgroundScope.gameNodeRoom(
            bobLoom, "table-b", voterIds = core,
            raftConfig = fastRaftConfig(seed = 5L), random = Random(15), clock = inertTestClock,
            placement = placement,
        )

        // Per-room admission: each game's core leader admits exactly its room's players.
        serverA.node.membership.first { NodeId("alice") in it.learners }
        serverB.node.membership.first { NodeId("alice") in it.learners && NodeId("bob") in it.learners }

        // Each game commits independently through the identical consuming layer.
        val moveA = TurnSequencer(aliceA.node, Int.serializer()).propose(11)
        val moveB = TurnSequencer(bobB.node, Int.serializer()).propose(22)
        val replayedA = firstCommittedInt(serverA.node)
        val replayedB = firstCommittedInt(aliceB.node)

        assertAll(
            { assertEquals(11, moveA.action) },
            { assertEquals(22, moveB.action) },
            { assertEquals(11, replayedA, "game A's log carries game A's move") },
            { assertEquals(22, replayedB, "alice receives game B's committed move as a learner") },
            {
                assertEquals(
                    setOf(NodeId("alice")),
                    serverA.node.membership.value.learners,
                    "game A admits only its own room's player — bob is structurally invisible to it",
                )
            },
            {
                assertEquals(
                    setOf(NodeId("alice"), NodeId("bob")),
                    serverB.node.membership.value.learners,
                    "game B admits both of its players",
                )
            },
            { assertEquals(core, serverA.node.membership.value.voters, "quorum stays on the server") },
            { assertEquals(core, serverB.node.membership.value.voters, "quorum stays on the server") },
            { assertIs<RaftRole.Learner>(aliceA.node.role.value, "a player never takes a voter seat") },
        )
    }

    /**
     * The hosted (appoint-the-host) case over a room, proving **room-scoped relay semantics**:
     * the room hub re-floods a spoke's broadcast to the room's other spokes, so an app-channel
     * CRDT converges spoke→hub→spoke exactly as on a dedicated `gameHosted` star.
     */
    @Test
    fun hostedRoomRelaysAppChannelBetweenPlayers() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val dispatcher = requireNotNull(coroutineContext[ContinuationInterceptor])
        val fabric = InMemoryRoomFabric(backgroundScope, dispatcher, random = Random(2))
        val aliceLoom = fabric.clientLoom(PeerId("alice"), Random(31))
        val bobLoom = fabric.clientLoom(PeerId("bob"), Random(32))

        // Host and joins must run concurrently: the host's admit loop suspends on joiners.
        val hostDeferred = async {
            backgroundScope.gameHostedRoom(
                fabric.serverLoom, "casual", peerCount = 3,
                raftConfig = fastRaftConfig(seed = 1L), random = Random(41), clock = inertTestClock,
            )
        }
        val aliceDeferred = async {
            backgroundScope.gameJoinRoom(
                aliceLoom, "casual",
                raftConfig = fastRaftConfig(seed = 2L), random = Random(42), clock = inertTestClock,
            )
        }
        val bobDeferred = async {
            backgroundScope.gameJoinRoom(
                bobLoom, "casual",
                raftConfig = fastRaftConfig(seed = 3L), random = Random(43), clock = inertTestClock,
            )
        }
        val host = hostDeferred.await()
        val alice = aliceDeferred.await()
        val bob = bobDeferred.await()

        val hostChat = chatQuilter(host, backgroundScope, Random(51))
        val aliceChat = chatQuilter(alice, backgroundScope, Random(52))
        val bobChat = chatQuilter(bob, backgroundScope, Random(53))
        advanceTimeBy(500)
        runCurrent()

        aliceChat.appendChat("hello")
        advanceTimeBy(2000)
        runCurrent()

        assertAll(
            { assertEquals(listOf("hello"), hostChat.state.value.toList(), "the hub converged") },
            {
                assertEquals(
                    listOf("hello"),
                    bobChat.state.value.toList(),
                    "a spoke's broadcast is relayed to the room's other spoke through the hub",
                )
            },
        )
    }

    /** First committed non-config, non-noop entry of [node]'s log, decoded as the test's Int action. */
    private suspend fun firstCommittedInt(node: RaftNode): Int =
        node.committedFrom(1)
            .mapNotNull { committed ->
                if (committed !is Committed.Entry) return@mapNotNull null
                val entry = committed.entry
                if (entry.isNoOp || entry.config != null) return@mapNotNull null
                Cbor.decodeFromByteArray(Int.serializer(), entry.command)
            }
            .first()

    private fun chatQuilter(
        session: GameSession,
        scope: CoroutineScope,
        random: Random,
    ): Quilter<Rga<String>> =
        Quilter(
            seam = session.appChannel("chat"),
            initial = Rga.empty(),
            valueSerializer = Rga.wireSerializer(String.serializer()),
            scope = scope,
            config = QuilterConfig(expectVirtualTime = true),
            random = random,
        )

    /** Append [text] at the RGA tail so chat order is preserved. */
    private fun Quilter<Rga<String>>.appendChat(text: String) {
        val current = state.value
        val (_, op) = current.insertAt(
            replica = replica,
            index = current.toList().size,
            value = text,
        )
        apply(Patch(Rga.empty<String>().apply(op)))
    }
}
