@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.examples

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.cluster.attachmentDirectory
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.MuxServerLoom
import us.tractat.kuilt.core.NamedMux
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.RoomAuthorizer
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.fabric.Connection
import us.tractat.kuilt.core.fabric.meshSeam
import us.tractat.kuilt.game.ConsensusPlacement
import us.tractat.kuilt.game.GameSession
import us.tractat.kuilt.game.TurnSequencer
import us.tractat.kuilt.game.gameNode
import us.tractat.kuilt.game.gameNodeRoom
import us.tractat.kuilt.game.gameNodeRoomFederated
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.raft.Committed
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftConfig
import us.tractat.kuilt.raft.RaftNode
import us.tractat.kuilt.raft.RaftRole
import us.tractat.kuilt.test.fabric.InMemoryConnectionSource
import us.tractat.kuilt.test.fabric.InMemoryRoomFabric
import us.tractat.kuilt.test.fabric.connectionPair
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Done-when matrix E2E for game-overlay slice 6 (#794): **the same game code passes on three
 * topologies**, the third being a federated server core surviving one-server failover.
 *
 * The "game code" is one shared helper — [playTurnAndReplicate]: propose an action through a
 * [TurnSequencer] on the proposer's node and prove every observer's Raft log commits it. Only the
 * *bootstrap* differs across the three tests:
 *
 * 1. **In-memory mesh** — [gameNode] roster-given ([ConsensusPlacement.SessionOwned]): the players
 *    themselves are the voters, meshed over an [InMemoryLoom]. No server.
 * 2. **Single-server hub** — [gameNodeRoom] with [ConsensusPlacement.serverCore]: one server is the
 *    whole voter core; a player rides as a learner over the star relay (the shipped game-per-room
 *    path).
 * 3. **Three-server federation** — [gameNodeRoomFederated]: three servers form the voter core, each
 *    bonding its local room seam and a per-game inter-server channel into one federated seam
 *    (`tieredSeam`) disseminated by the two-tier policy. A committed action replicates across the
 *    real inter-server mesh, and after one server is **killed** the surviving two still commit.
 *
 * ## Harness discipline
 *
 * These stand up real [RaftNode]s over the **seam under test** (the game bootstrap's own overlay),
 * so the transport cannot be `MultiNodeRaftSim`'s in-process network — the whole point is that Raft
 * rides the federated seam. This mirrors `:kuilt-game`'s `GameRoomTest`, the established pattern for
 * multi-node game-bootstrap tests: [StandardTestDispatcher] (FIFO virtual time), a tight per-test
 * timeout, **per-node seeded election RNG** ([fedRaftConfig]) for symmetry-breaking, everything
 * long-lived on child scopes of `backgroundScope`, and bounded suspending `first { }` awaits — never
 * `advanceUntilIdle()`.
 */
class FederatedGamePerRoomE2ETest {

    private val gameId = "table-1"

    // ── Topology 1: in-memory mesh (players ARE the voters) ──────────────────────
    @Test
    fun sameGameCode_inMemoryMesh() = runTest(StandardTestDispatcher(), timeout = 20.seconds) {
        val loom = InMemoryLoom()
        val p1 = loom.host(Pattern(gameId))
        val p2 = loom.join(InMemoryTag(gameId))
        val voters = setOf(NodeId(p1.selfId.value), NodeId(p2.selfId.value))

        val alice = backgroundScope.gameNode(p1, voters, raftConfig = fedRaftConfig(1L))
        val bob = backgroundScope.gameNode(p2, voters, raftConfig = fedRaftConfig(2L))

        val proposer = awaitLeaderSession(listOf(alice, bob))
        playTurnAndReplicate(proposer, observers = listOf(alice, bob), action = 42)
    }

    // ── Topology 2: single-server hub (one server IS the whole core) ─────────────
    @Test
    fun sameGameCode_singleServerHub() = runTest(StandardTestDispatcher(), timeout = 20.seconds) {
        val dispatcher = testDispatcher()
        val fabric = InMemoryRoomFabric(backgroundScope, dispatcher, random = Random(7))
        val core = setOf(NodeId("server"))
        val placement = ConsensusPlacement.serverCore(core)

        val server = backgroundScope.gameNodeRoom(
            fabric.serverLoom, gameId, voterIds = core,
            raftConfig = fedRaftConfig(1L), random = Random(11), clock = inertClock, placement = placement,
        )
        val aliceLoom = fabric.clientLoom(PeerId("alice"), Random(21))
        val alice = backgroundScope.gameNodeRoom(
            aliceLoom, gameId, voterIds = core,
            raftConfig = fedRaftConfig(2L), random = Random(12), clock = inertClock, placement = placement,
        )

        // Alice is admitted as a learner by the server's core-admission loop before she can play.
        server.node.membership.first { NodeId("alice") in it.learners }

        // The player proposes (learner→leader forwarding); the server and the player both commit.
        playTurnAndReplicate(proposer = alice, observers = listOf(server, alice), action = 42)
    }

    // ── Topology 3: three-server federation surviving one-server failover ────────
    @Test
    fun sameGameCode_threeServerFederation_survivesOneServerFailover() =
        runTest(StandardTestDispatcher(), timeout = 30.seconds) {
            val dispatcher = testDispatcher()
            val ids = listOf(NodeId("s1"), NodeId("s2"), NodeId("s3"))
            val core = ids.toSet()

            // One inter-server mesh seam per server (a real K_3 clique of in-memory links).
            val coreSeams = interServerMesh(ids, dispatcher, backgroundScope)

            // Each server runs on its OWN child scope so a single server can be killed on failover.
            val serverScopes = ids.associateWith {
                CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
            }

            val clock = increasingMillisClock()
            val sessions = ids.mapIndexed { i, id ->
                val scope = serverScopes.getValue(id)
                // One NamedMux over this server's single inter-server seam (the SOLE collector),
                // carved into the directory-replication channel and the per-game channel — the
                // (b)/(c) provisioning the federated composition assumes. Raft for the game rides
                // NESTED inside the per-game channel (gameNode's own mux), so nothing double-collects.
                val coreMux = NamedMux(coreSeams.getValue(id), scope)
                val directorySeam = coreMux.channel(DIRECTORY_CHANNEL)
                val perGameCore = coreMux.channel(gameId)
                val directory = attachmentDirectory(
                    self = PeerId(id.value), interServerSeam = directorySeam, scope = scope,
                    clock = clock, config = QuilterConfig(expectVirtualTime = true),
                )
                // A per-server room loom for this server's local players (empty here — the core
                // consensus + failover is the proof; the directory + attachment are wired to
                // exercise the two-tier overlay's provisioning contract).
                val roomLoom = serverRoomLoom(scope, PeerId(id.value), dispatcher, Random(100L + i))
                id to scope.gameNodeRoomFederated(
                    rooms = roomLoom, gameId = gameId, core = core,
                    perGameCore = perGameCore, attachment = directory::lookup,
                    raftConfig = fedRaftConfig(i + 1L), random = Random(200L + i), clock = inertClock,
                )
            }.toMap()

            // (1) The federated core elects a leader and commits an action to ALL THREE servers.
            val leaderId = awaitLeaderId(ids, sessions)
            playTurnAndReplicate(
                proposer = sessions.getValue(leaderId),
                observers = sessions.values.toList(),
                action = 42,
            )

            // (2) Kill one FOLLOWER server (a minority — the 2-of-3 majority survives).
            val victim = ids.first { it != leaderId }
            serverScopes.getValue(victim).cancel()
            val survivors = ids.filter { it != victim }

            // (3) The same game code runs again on the survivors — a fresh action still commits.
            val survivorLeader = awaitLeaderId(survivors, sessions)
            playTurnAndReplicate(
                proposer = sessions.getValue(survivorLeader),
                observers = survivors.map { sessions.getValue(it) },
                action = 99,
            )
        }

    // ── The ONE piece of "game code" shared by all three topologies ──────────────

    /**
     * Propose [action] on [proposer]'s node and assert every [observers] Raft log commits it — the
     * identical game move regardless of which bootstrap wove the session.
     */
    private suspend fun playTurnAndReplicate(proposer: GameSession, observers: List<GameSession>, action: Int) {
        val move = TurnSequencer(proposer.node, Int.serializer()).propose(action)
        assertEquals(action, move.action, "the proposer commits its own action")
        observers.forEach { awaitCommittedInt(it.node, action) }
    }

    // ── Bounded awaits (never advanceUntilIdle) ──────────────────────────────────

    /** Suspend until some session's node is Leader, then return it. */
    private suspend fun awaitLeaderSession(sessions: List<GameSession>): GameSession {
        combine(sessions.map { it.node.role }) { roles -> roles.any { it is RaftRole.Leader } }.first { it }
        return sessions.first { it.node.role.value is RaftRole.Leader }
    }

    /** Suspend until some server in [among] is Leader, then return its id. */
    private suspend fun awaitLeaderId(among: List<NodeId>, sessions: Map<NodeId, GameSession>): NodeId {
        val nodes = among.map { sessions.getValue(it).node }
        combine(nodes.map { it.role }) { roles -> roles.any { it is RaftRole.Leader } }.first { it }
        return among.first { sessions.getValue(it).node.role.value is RaftRole.Leader }
    }

    /** Suspend until [expected] appears in [node]'s committed log (bounded by the test timeout). */
    private suspend fun awaitCommittedInt(node: RaftNode, expected: Int) {
        assertEquals(expected, committedInts(node).first { it == expected })
    }

    private fun committedInts(node: RaftNode): Flow<Int> =
        node.committedFrom(1).mapNotNull { committed ->
            if (committed !is Committed.Entry) return@mapNotNull null
            val entry = committed.entry
            if (entry.isNoOp || entry.config != null) return@mapNotNull null
            Cbor.decodeFromByteArray(Int.serializer(), entry.command)
        }

    // ── Federation wiring helpers ────────────────────────────────────────────────

    /**
     * A K_N inter-server mesh: N [meshSeam]s (one per server) over in-memory link pairs, one link
     * for every unordered server pair. Woven concurrently so the handshake preambles cross.
     */
    private suspend fun interServerMesh(
        ids: List<NodeId>,
        dispatcher: CoroutineContext,
        scope: CoroutineScope,
    ): Map<NodeId, Seam> {
        val n = ids.size
        // pairs[i to j] (i < j): (endForI, endForJ).
        val pairs = mutableMapOf<Pair<Int, Int>, Pair<Connection, Connection>>()
        for (i in 0 until n) for (j in i + 1 until n) pairs[i to j] = connectionPair()

        fun connsFor(i: Int): List<Connection> = (0 until n).filter { it != i }.map { j ->
            if (i < j) pairs.getValue(i to j).first else pairs.getValue(j to i).second
        }

        return scope.run {
            ids.mapIndexed { i, id ->
                async {
                    id to (meshSeam(
                        selfId = PeerId(id.value), connections = connsFor(i),
                        dispatcher = dispatcher, random = Random(500L + i),
                    ) as Seam)
                }
            }.awaitAll().toMap()
        }
    }

    /** A per-server room loom (its own accept source, no clients wired here). */
    private fun serverRoomLoom(
        scope: CoroutineScope,
        selfId: PeerId,
        dispatcher: CoroutineContext,
        random: Random,
    ): Loom = MuxServerLoom(
        source = InMemoryConnectionSource(), scope = scope, selfId = selfId,
        authorizer = RoomAuthorizer.AllowAll, dispatcher = dispatcher, random = random,
    )

    private companion object {
        const val DIRECTORY_CHANNEL = "__attachment_directory__"

        val inertClock: () -> Instant = { Instant.fromEpochMilliseconds(0) }

        /** Strictly-increasing millis clock for directory LWW tags. */
        fun increasingMillisClock(): () -> Long {
            var t = 0L
            return { ++t }
        }

        /** Fast virtual-time Raft config with a distinct per-node election seed. */
        fun fedRaftConfig(seed: Long): RaftConfig = RaftConfig(
            electionTimeoutMin = 5.milliseconds,
            electionTimeoutMax = 10.milliseconds,
            heartbeatInterval = 2.milliseconds,
            expectVirtualTime = true,
            random = Random(seed),
        )
    }
}

/** The test dispatcher backing this [runTest] body — the FIFO [StandardTestDispatcher]. */
private suspend fun testDispatcher(): CoroutineContext =
    requireNotNull(coroutineContext[ContinuationInterceptor]) { "no test dispatcher in context" }
