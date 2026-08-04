@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.game

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.raft.Committed
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftNode
import us.tractat.kuilt.raft.RaftRole
import us.tractat.kuilt.raft.test.FakeRaftNode
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ConsensusPlacementTest {

    /**
     * The downstream hard acceptance criterion: a consumer can bootstrap the full game stack
     * against a pre-built test double — `FakeRaftNode` pinned to Leader — under pure virtual
     * time, with no real Raft engine anywhere, and drive it through the identical consuming
     * layer ([TurnSequencer]).
     */
    @Test
    fun preBuiltPlacementHandsTheSessionTheProvidedNode() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val loom = InMemoryLoom()
        val seam = loom.host(Pattern("fake-node-game"))
        val self = NodeId(seam.selfId.value)
        val fake = FakeRaftNode(self, initialRole = RaftRole.Leader)

        val session = backgroundScope.gameNode(
            seam,
            voterIds = setOf(self),
            placement = ConsensusPlacement.preBuilt(fake),
        )
        val move = TurnSequencer(session.node, Int.serializer()).propose(3)

        assertAll(
            { assertSame<RaftNode>(fake, session.node, "the session must drive exactly the provided node") },
            { assertEquals(3, move.action) },
        )
    }

    /** The appoint-the-host path accepts a pre-built node too — presence and admission run against it. */
    @Test
    fun gameHostBootstrapsAgainstAPreBuiltNode() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val loom = InMemoryLoom()
        val hostSeam = loom.host(Pattern("fake-host-game"))
        val fake = FakeRaftNode(NodeId(hostSeam.selfId.value), initialRole = RaftRole.Leader)

        val session = backgroundScope.gameHost(
            hostSeam,
            peerCount = 1,
            raftConfig = fastRaftConfig(seed = 1L),
            clock = inertTestClock,
            placement = ConsensusPlacement.preBuilt(fake),
        )

        assertSame<RaftNode>(fake, session.node)
    }

    /**
     * The server-core placement, end to end: three "server" peers form the voter core (all of
     * them vote), a player peer rides as a learner. The core elects among itself; the core
     * leader's admission loop adds the player to the replicated config; the player then receives
     * the committed log and proposes through the identical consuming layer (learner→leader
     * forwarding) — never holding a voter seat.
     */
    @Test
    fun serverCoreAllServersVoteAndPlayerRidesAsLearner() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val loom = InMemoryLoom()
        val seams = seats(loom, 4)
        val serverSeams = seams.take(3)
        val playerSeam = seams[3]
        val core = serverSeams.map { NodeId(it.selfId.value) }.toSet()
        val playerId = NodeId(playerSeam.selfId.value)
        val placement = ConsensusPlacement.serverCore(core)

        val serverNodes = serverSeams.mapIndexed { i, seam ->
            backgroundScope.gameNode(
                seam,
                voterIds = core,
                raftConfig = fastRaftConfig(seed = (i + 1).toLong()),
                placement = placement,
            ).node
        }
        val player = backgroundScope.gameNode(
            playerSeam,
            voterIds = core,
            raftConfig = fastRaftConfig(seed = 4L),
            placement = placement,
        )

        // The core elects among the servers, then the leader's admission loop adds the player
        // as a learner in the replicated config.
        val leader = awaitAnyLeader(serverNodes)
        leader.membership.first { playerId in it.learners }

        // The player proposes through the identical consuming layer — forwarded to the core
        // leader — and replays the committed action from the log it receives as a learner.
        val move = TurnSequencer(player.node, Int.serializer()).propose(21)
        val replayed = player.node.committedFrom(1)
            .mapNotNull { committed ->
                if (committed !is Committed.Entry) return@mapNotNull null
                val logEntry = committed.entry
                if (logEntry.isNoOp || logEntry.config != null) return@mapNotNull null
                Cbor.decodeFromByteArray(Int.serializer(), logEntry.command)
            }
            .first()

        assertAll(
            { assertEquals(21, move.action) },
            { assertEquals(21, replayed, "the player must receive the committed log as a learner") },
            { assertIs<RaftRole.Learner>(player.node.role.value, "the player must never take a voter seat") },
            { assertEquals(core, leader.membership.value.voters, "every server votes in the game") },
            { assertTrue(playerId in leader.membership.value.learners, "the player rides in the learner set") },
        )
    }

    /**
     * The **federated** server-core placement composes end to end through the real [gameNode]
     * bootstrap: over an all-direct in-memory mesh the routing decorator is inert (every addressee
     * is a direct peer, so no relay frame is ever produced), and the game behaves exactly like the
     * plain [ConsensusPlacement.serverCore] — the core votes, the player rides as a learner and
     * receives the committed log through the identical consuming layer. This proves the bootstrap
     * picks up the decorator and that wrapping is a byte-inert drop-in off a real federation.
     */
    @Test
    fun federatedCoreIsADropInForServerCore_overAnAllDirectMesh() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val loom = InMemoryLoom()
        val seams = seats(loom, 4)
        val serverSeams = seams.take(3)
        val playerSeam = seams[3]
        val core = serverSeams.map { NodeId(it.selfId.value) }.toSet()
        val playerId = NodeId(playerSeam.selfId.value)
        // A player owns no attachment directory — it never routes for anyone, so { null } is honest.
        val placement = ConsensusPlacement.federatedCore(core, attachment = { null })

        val serverNodes = serverSeams.mapIndexed { i, seam ->
            backgroundScope.gameNode(
                seam, voterIds = core, raftConfig = fastRaftConfig(seed = (i + 1).toLong()), placement = placement,
            ).node
        }
        val player = backgroundScope.gameNode(
            playerSeam, voterIds = core, raftConfig = fastRaftConfig(seed = 4L), placement = placement,
        )

        val leader = awaitAnyLeader(serverNodes)
        leader.membership.first { playerId in it.learners }

        val move = TurnSequencer(player.node, Int.serializer()).propose(21)
        val replayed = player.node.committedFrom(1)
            .mapNotNull { committed ->
                if (committed !is Committed.Entry) return@mapNotNull null
                val logEntry = committed.entry
                if (logEntry.isNoOp || logEntry.config != null) return@mapNotNull null
                Cbor.decodeFromByteArray(Int.serializer(), logEntry.command)
            }
            .first()

        assertAll(
            { assertEquals(21, move.action) },
            { assertEquals(21, replayed, "the player receives the committed log through the routed transport") },
            { assertIs<RaftRole.Learner>(player.node.role.value, "the player never takes a voter seat") },
            { assertEquals(core, leader.membership.value.voters, "every server votes in the game") },
        )
    }

    /**
     * Behaviour-preservation canary for the default placement: the roster-given path still
     * fail-fasts when this peer is not in the voter roster ([ConsensusPlacement.SessionOwned]
     * keeps today's precondition).
     */
    @Test
    fun sessionOwnedGameNodeStillRequiresSelfInRoster() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val loom = InMemoryLoom()
        val seam = loom.host(Pattern("roster-guard"))

        assertFailsWith<IllegalArgumentException> {
            backgroundScope.gameNode(
                seam,
                voterIds = setOf(NodeId("someone-else")),
                raftConfig = fastRaftConfig(seed = 1L),
            )
        }
    }

    /**
     * The appoint-the-host paths promote session peers to voter seats — a fixed external voter
     * core contradicts that machinery, so they reject a [AuthoritySeating.CoreVoters] placement
     * loudly before touching the seam (server-core bootstraps via [gameNode] today).
     */
    @Test
    fun hostPathsRejectCoreVoterSeating() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val loom = InMemoryLoom()
        val seam = loom.host(Pattern("seating-guard"))
        val placement = ConsensusPlacement.serverCore(setOf(NodeId("s1"), NodeId("s2")))

        // Each guard fires before the entry point touches the seam, so one seam serves all three.
        assertFailsWith<IllegalArgumentException> {
            backgroundScope.gameHost(
                seam,
                peerCount = 2,
                raftConfig = fastRaftConfig(seed = 1L),
                clock = inertTestClock,
                placement = placement,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            backgroundScope.gameJoin(seam, raftConfig = fastRaftConfig(seed = 2L), placement = placement)
        }
        assertFailsWith<IllegalArgumentException> {
            backgroundScope.gameSpectate(seam, raftConfig = fastRaftConfig(seed = 3L), placement = placement)
        }
    }

    @Test
    fun serverCoreRejectsAnEmptyCore() {
        assertFailsWith<IllegalArgumentException> {
            ConsensusPlacement.serverCore(emptySet())
        }
    }
}
