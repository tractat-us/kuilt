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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.cluster.OverlayServer
import us.tractat.kuilt.cluster.attachConnections
import us.tractat.kuilt.cluster.attachmentDirectory
import us.tractat.kuilt.cluster.overlayServer
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.MuxClientLoom
import us.tractat.kuilt.core.MuxServerLoom
import us.tractat.kuilt.core.NamedMux
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.RoomAuthorizer
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.core.fabric.Connection
import us.tractat.kuilt.core.fabric.meshSeam
import us.tractat.kuilt.game.AuthoritySeating
import us.tractat.kuilt.game.ConsensusBinding
import us.tractat.kuilt.game.ConsensusPlacement
import us.tractat.kuilt.game.GameSession
import us.tractat.kuilt.game.TurnSequencer
import us.tractat.kuilt.game.gameNode
import us.tractat.kuilt.game.gameNodeRoom
import us.tractat.kuilt.game.gameNodeRoomFederated
import us.tractat.kuilt.gossip.starOverlay
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.raft.Committed
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftConfig
import us.tractat.kuilt.raft.RaftNode
import us.tractat.kuilt.raft.RaftRole
import us.tractat.kuilt.raft.RaftTraceEvent
import us.tractat.kuilt.raft.test.FakeRaftNode
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.fabric.InMemoryConnectionSource
import us.tractat.kuilt.test.fabric.InMemoryRoomFabric
import us.tractat.kuilt.test.fabric.connectionPair
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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

    // ══ Guard tests G1–G5: cross-server delivery over a REAL federation (#794 slice 6 PR 2a) ══════
    //
    // The topology-3 failover test above stands up three core servers with EMPTY rooms — it proves
    // server-core consensus + failover, but never puts a real player behind a server. These guard
    // tests fill those rooms with REAL players joined through the production federated-player call
    // site — `gameNodeRoom(playerLoom, gameId, voterIds = core, placement = federatedPlayer(core))`
    // — and prove the committed Raft log crosses the core to a player behind ANY server.
    //
    // Directory note (the H5 "admit is a caller obligation" gap, now closed at the connection layer):
    // `gameNodeRoomFederated` does not itself publish `player → self` into the routing directory. The
    // production publisher lives one layer down — `attachConnections` collects the server loom's
    // `MuxServerLoom.connectedPeers` and calls `OverlayServer.attachDirectoryOnly` for each accepted
    // player link, which the leader's `RoutedRaftTransport` reads to pick its core hop. `hostGame`
    // wires exactly that shipped helper (see #1384 for converging it with `admit`'s spoke path), so
    // these guard tests exercise the real connection-layer wiring, not a hand-rolled roster poke.

    /**
     * **G1 — identity → reply path.** Three-server federation, player P2 behind a *follower*, leader
     * elsewhere. A turn P2 proposes commits on ALL three servers AND on P2, AND the leader credits
     * P2's true origin on the reply path.
     *
     * `matchIndex` is not on the public [RaftNode] surface, so the reply-path credit is proved via the
     * leader's own [RaftNode.trace]: once the leader has processed P2's origin-preserved
     * AppendEntriesResponse, `matchIndex[P2]` (hence `nextIndex[P2]`) advances, so the leader's
     * subsequent `AppendEntries(to = P2)` carries `prevLogIndex ≥` the action's committed index. A relay
     * that lost the origin would credit the *relaying server* instead, leaving `nextIndex[P2]` pinned
     * and `prevLogIndex` below the entry forever.
     */
    @Test
    fun g1_playerBehindFollower_commitsEverywhereAndCreditsTrueOrigin() =
        runTest(StandardTestDispatcher(), timeout = 30.seconds) {
            val dispatcher = testDispatcher()
            val ids = listOf(NodeId("s1"), NodeId("s2"), NodeId("s3"))
            val fed = backgroundScope.federation(ids, dispatcher, increasingMillisClock())
            val servers = hostGame(fed, gameId)

            // Elect a leader, THEN attach the player behind a server that is NOT the leader — so its
            // AppendEntries can only reach P2 across the core relay.
            val leaderId = awaitLeaderId(ids, servers)
            val followerId = ids.first { it != leaderId }
            val p2Id = PeerId("player-two")
            val p2 = backgroundScope.joinFederatedPlayer(fed.fabric(followerId), gameId, ids.toSet(), p2Id, seed = 42L)

            // P2 is admitted as a learner (cross-server roster exchange) before it can play.
            servers.getValue(leaderId).node.membership.first { NodeId(p2Id.value) in it.learners }

            val idx = TurnSequencer(p2.node, Int.serializer()).propose(1).index
            assertEquals(1, committedInts(p2.node).first { it == 1 }, "P2 commits its own proposed action")
            ids.forEach { awaitCommittedInt(servers.getValue(it).node, 1) }

            // Reply-path credit: the leader replicates PAST the entry to P2 — only possible if P2's
            // origin-preserved response advanced matchIndex[P2]/nextIndex[P2] on the leader.
            servers.getValue(leaderId).node.trace.first {
                it is RaftTraceEvent.AppendEntries && it.to == NodeId(p2Id.value) && it.prevLogIndex >= idx
            }
        }

    /**
     * **G2 — a relayed frame reaches P2's engine.** P2 behind a follower commits an entry it did NOT
     * propose: the *leader* proposes and the only path the committed entry can reach P2 is the
     * cross-server relay (P2 receives no AppendEntries from a server it is not local to without it).
     */
    @Test
    fun g2_relayDeliversLeaderProposedEntryToPlayerBehindFollower() =
        runTest(StandardTestDispatcher(), timeout = 30.seconds) {
            val dispatcher = testDispatcher()
            val ids = listOf(NodeId("s1"), NodeId("s2"), NodeId("s3"))
            val fed = backgroundScope.federation(ids, dispatcher, increasingMillisClock())
            val servers = hostGame(fed, gameId)

            val leaderId = awaitLeaderId(ids, servers)
            val followerId = ids.first { it != leaderId }
            val p2Id = PeerId("player-two")
            val p2 = backgroundScope.joinFederatedPlayer(fed.fabric(followerId), gameId, ids.toSet(), p2Id, seed = 42L)
            servers.getValue(leaderId).node.membership.first { NodeId(p2Id.value) in it.learners }

            // The LEADER proposes; P2 (behind a different server) can only receive it via the relay.
            TurnSequencer(servers.getValue(leaderId).node, Int.serializer()).propose(7)
            awaitCommittedInt(p2.node, 7)
        }

    /**
     * **G3 — game isolation.** Two federated games (A and B) ride the same three servers over
     * per-game channels; a player joins each. A commit in game A never surfaces in game B's engine —
     * the per-room / per-game-channel mux is structurally isolated, so a relay frame in one game
     * cannot cross into the other.
     */
    @Test
    fun g3_twoFederatedGamesAreIsolated() = runTest(StandardTestDispatcher(), timeout = 30.seconds) {
        val dispatcher = testDispatcher()
        val ids = listOf(NodeId("s1"), NodeId("s2"), NodeId("s3"))
        val gameA = "game-A"
        val gameB = "game-B"

        // ONE shared core; both games ride it, isolated only by per-game channel + room.
        val fed = backgroundScope.federation(ids, dispatcher, increasingMillisClock())
        val serversA = hostGame(fed, gameA)
        val serversB = hostGame(fed, gameB)

        val leaderA = awaitLeaderId(ids, serversA)
        val leaderB = awaitLeaderId(ids, serversB)

        val pAId = PeerId("player-A")
        val pBId = PeerId("player-B")
        val pA = backgroundScope.joinFederatedPlayer(fed.fabric(ids.first { it != leaderA }), gameA, ids.toSet(), pAId, seed = 51L)
        val pB = backgroundScope.joinFederatedPlayer(fed.fabric(ids.first { it != leaderB }), gameB, ids.toSet(), pBId, seed = 52L)
        serversA.getValue(leaderA).node.membership.first { NodeId(pAId.value) in it.learners }
        serversB.getValue(leaderB).node.membership.first { NodeId(pBId.value) in it.learners }

        // Commit distinct actions in each game, then a per-game BARRIER action: by the time a game
        // commits its barrier, any cross-game leak of the other game's earlier action would already
        // sit in its log (Raft commits in index order).
        TurnSequencer(pA.node, Int.serializer()).propose(111)
        TurnSequencer(pB.node, Int.serializer()).propose(222)
        awaitCommittedInt(pA.node, 111)
        awaitCommittedInt(pB.node, 222)
        TurnSequencer(pA.node, Int.serializer()).propose(999)
        TurnSequencer(pB.node, Int.serializer()).propose(888)

        // Isolation: each game's committed application log through its barrier holds ONLY its own two
        // actions — never a frame from the sibling game riding the same core.
        assertEquals(
            listOf(111, 999), collectCommittedIntsUntil(pA.node, 999),
            "game A committed only its own actions — a game-B frame leaked across the shared core",
        )
        assertEquals(
            listOf(222, 888), collectCommittedIntsUntil(pB.node, 888),
            "game B committed only its own actions — a game-A frame leaked across the shared core",
        )
    }

    /**
     * **G4 — leak boundary (negative).** P2 behind S_b and P3 behind S_c, same game. Every relay hop
     * is a single-addressee `sendTo`, never a broadcast, so a frame addressed to P2 travels only
     * `leader → S_b → P2` and never touches P3's link. P3 tails every frame delivered to its own
     * session seam; not one of them is part of P2's addressed relay chain.
     *
     * The relay envelope ([us.tractat.kuilt.cluster.RaftRelay]) is `internal`, so we discriminate by a
     * byte-substring of P2's id (`player-two`) — a P3-destined AppendEntries carries the leader id and
     * `player-three`, never `player-two`; a leaked P2 frame would. Positive control: P3 DOES receive
     * relay frames (its own) and commits the entry, so the tap is proven live.
     */
    @Test
    fun g4_frameAddressedToOnePlayerNeverLeaksToAnother() =
        runTest(StandardTestDispatcher(), timeout = 30.seconds) {
            val dispatcher = testDispatcher()
            val ids = listOf(NodeId("s1"), NodeId("s2"), NodeId("s3"))
            val fed = backgroundScope.federation(ids, dispatcher, increasingMillisClock())
            val servers = hostGame(fed, gameId)

            val leaderId = awaitLeaderId(ids, servers)
            val followers = ids.filter { it != leaderId }
            val p2Id = PeerId("player-two")
            val p3Id = PeerId("player-three")
            backgroundScope.joinFederatedPlayer(fed.fabric(followers[0]), gameId, ids.toSet(), p2Id, seed = 61L)

            // P3 joins through a tapping loom so we observe every frame delivered to its session seam.
            val p3Tap = mutableListOf<Swatch>()
            val p3 = backgroundScope.joinFederatedPlayer(
                fed.fabric(followers[1]), gameId, ids.toSet(), p3Id, seed = 62L, tap = p3Tap,
            )
            servers.getValue(leaderId).node.membership.first {
                NodeId(p2Id.value) in it.learners && NodeId(p3Id.value) in it.learners
            }

            // The leader proposes; both learners commit via their own relay chains.
            TurnSequencer(servers.getValue(leaderId).node, Int.serializer()).propose(9)
            awaitCommittedInt(p3.node, 9)

            // Positive control: P3's tap is live — it received relay frames (tag 5) addressed to itself.
            val p3Marker = p3Id.value.encodeToByteArray()
            assertTrue(
                p3Tap.any { it.payloadSize > 0 && it.byteAt(0) == RAFT_RELAY_TAG && it.toByteArray().containsSub(p3Marker) },
                "positive control: P3's session must receive relay frames addressed to itself (tap is live)",
            )
            // Leak boundary: no relay frame delivered to P3 is part of P2's addressed chain.
            val p2Marker = p2Id.value.encodeToByteArray()
            assertFalse(
                p3Tap.any { it.payloadSize > 0 && it.byteAt(0) == RAFT_RELAY_TAG && it.toByteArray().containsSub(p2Marker) },
                "leak: a relay frame referencing ${p2Id.value} reached P3's session",
            )
        }

    /**
     * **G5 — broadcast-laundering rejection (the H1 class, for the relay/roster tags).** A forged
     * `GossipFrame`-wrapped payload injected on the flood/broadcast plane (tag 0, the overlay) can
     * never surface on the below-overlay Raft-relay (tag 5) or core-roster (tag 6) channels — so it
     * reaches no engine and admits/commits nothing. This mirrors the shape of
     * `CommitSafetyLaunderingE2ETest` (which pins the Raft channel, tag 1) for the two federated-only
     * tags introduced by this PR.
     *
     * The bootstrap is the real [gameNode] wiring over a star overlay; a spy [ConsensusPlacement]
     * becomes the sole collector of the bootstrap-built relay and roster channels (the federated
     * placement would collect them, so it is replaced by the spy — the [FakeRaftNode] ignores the
     * transport). Positive control: a payload delivered DIRECTLY on tag 5 / tag 6 IS observed on the
     * channel; the same payload wrapped in a tag-0 flood is NOT — proving the below-overlay seating,
     * not the spy, is what rejects it.
     */
    @Test
    fun g5_forgedFloodFrameNeverLaundersOntoRelayOrRosterChannels() =
        runTest(StandardTestDispatcher(), timeout = 10.seconds) {
            val raw = FakeSeam(
                selfId = PeerId("s1"),
                initialPeers = setOf(PeerId("s1"), PeerId("s2"), PeerId("attacker")),
            )
            val spy = RelayRosterChannelSpy()

            backgroundScope.gameNode(
                seam = raw,
                voterIds = setOf(NodeId("s1")),
                raftConfig = fedRaftConfig(1L),
                placement = spy,
                overlay = { starOverlay(it, Random(7), inertClock) },
            )
            runCurrent()
            advanceTimeBy(10)
            runCurrent()

            // Positive control: a direct frame on each below-overlay channel reaches the spy — proving
            // the spy taps the real channels (so an EMPTY forged-marker result is meaningful).
            raw.deliver(PeerId("s2"), byteArrayOf(RAFT_RELAY_TAG, DIRECT_MARKER))
            raw.deliver(PeerId("s2"), byteArrayOf(CORE_ROSTER_TAG, DIRECT_MARKER))
            runCurrent()

            // Attack: the SAME payloads, wrapped in a GossipFrame on the flood plane (tag 0), spoofing a
            // core origin. The overlay re-stamps sender = origin, but only INSIDE the flood sub-mux —
            // which has no relay/roster channel, so the payload is discarded before either channel.
            raw.deliver(
                PeerId("attacker"),
                byteArrayOf(BROADCAST_TAG) +
                    gossipFrameBytes("s2", seq = 1, ttl = 5, byteArrayOf(RAFT_RELAY_TAG, FORGED_MARKER)),
            )
            raw.deliver(
                PeerId("attacker"),
                byteArrayOf(BROADCAST_TAG) +
                    gossipFrameBytes("s2", seq = 2, ttl = 5, byteArrayOf(CORE_ROSTER_TAG, FORGED_MARKER)),
            )
            runCurrent()
            advanceTimeBy(10)
            runCurrent()

            assertTrue(
                spy.relayReceived.any { it.marker() == DIRECT_MARKER },
                "positive control: a direct tag-5 frame reaches the relay channel",
            )
            assertTrue(
                spy.rosterReceived.any { it.marker() == DIRECT_MARKER },
                "positive control: a direct tag-6 frame reaches the roster channel",
            )
            assertFalse(
                spy.relayReceived.any { it.marker() == FORGED_MARKER },
                "a forged tag-0 flood must NEVER launder onto the Raft-relay channel (#1370)",
            )
            assertFalse(
                spy.rosterReceived.any { it.marker() == FORGED_MARKER },
                "a forged tag-0 flood must NEVER launder onto the core-roster channel (#1370)",
            )
        }

    /**
     * **F8 — cross-server failover with a real player.** Three-server federation, player P2 behind a
     * *survivor* server S_b, a leader elected on another server. A baseline turn commits, then the
     * **leader server's whole scope is killed** (mirroring the topology-3 failover's kill mechanics).
     * A NEW leader emerges among the two survivors and still commits a turn **proposed by AND
     * delivered to P2** — the routing handoff across the failover.
     *
     * This pins the three things a player's routing depends on surviving a server loss:
     * - the attachment **directory** survives on the survivors (Quilter-replicated), still naming S_b
     *   as P2's hop, so the new leader's `RoutedRaftTransport` relays down to P2 correctly;
     * - **admission** survives via the continuous roster flow (`launchFederatedCoreAdmission`) — P2
     *   stays a learner in the survivors' membership across the leadership change; and
     * - the **relay** re-routes through S_b's still-live attachment, so P2's forwarded proposal
     *   reaches the new leader and the committed entry comes back down to P2.
     */
    @Test
    fun f8_playerBehindSurvivorServer_commitsAfterLeaderServerKilled() =
        runTest(StandardTestDispatcher(), timeout = 30.seconds) {
            val dispatcher = testDispatcher()
            val ids = listOf(NodeId("s1"), NodeId("s2"), NodeId("s3"))
            val fed = backgroundScope.federation(ids, dispatcher, increasingMillisClock())
            val servers = hostGame(fed, gameId)

            // Elect a leader; place P2 behind a server that will SURVIVE the kill (not the leader).
            val leaderId = awaitLeaderId(ids, servers)
            val sBid = ids.first { it != leaderId }
            val p2Id = PeerId("player-two")
            val p2 = backgroundScope.joinFederatedPlayer(fed.fabric(sBid), gameId, ids.toSet(), p2Id, seed = 71L)
            servers.getValue(leaderId).node.membership.first { NodeId(p2Id.value) in it.learners }

            // Baseline: P2's proposal commits under the ORIGINAL leader (routing works pre-failover).
            TurnSequencer(p2.node, Int.serializer()).propose(11)
            awaitCommittedInt(p2.node, 11)

            // Kill the LEADER server's whole scope. P2's entry server S_b survives, so the directory
            // still names S_b as P2's attachment and the survivors keep P2 in their membership.
            fed.members.getValue(leaderId).scope.cancel()
            val survivors = ids.filter { it != leaderId }

            // A NEW leader emerges among the survivors. First it commits an action DELIVERED to P2 —
            // the down-path routing handoff: the surviving directory still names S_b as P2's hop, so the
            // new leader's RoutedRaftTransport relays the entry down to P2 through S_b's live attachment.
            // This also lets P2's engine LEARN the new leader (from the relayed AppendEntries) — a
            // federated player forwards its proposals to the leader it last heard from, so it must
            // observe the new leader before it can forward there (the stale-leader re-forward of R1).
            val newLeader = awaitLeaderId(survivors, servers)
            TurnSequencer(servers.getValue(newLeader).node, Int.serializer()).propose(22)
            awaitCommittedInt(p2.node, 22)

            // Now the up-path handoff: a turn PROPOSED BY P2 re-routes through S_b to the new leader and
            // commits on P2 and on both survivors — the relay re-routed across the failover.
            TurnSequencer(p2.node, Int.serializer()).propose(33)
            assertEquals(33, committedInts(p2.node).first { it == 33 }, "P2 commits its post-failover proposal")
            survivors.forEach { awaitCommittedInt(servers.getValue(it).node, 33) }
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

    // ── Guard-test federation wiring (real players behind real servers) ──────────

    /**
     * A per-server in-memory room fabric with a *custom* server [PeerId] (unlike [InMemoryRoomFabric],
     * which fixes it to `"server"`). A federated core server's room-loom `selfId` must equal its Raft
     * [NodeId] / inter-server-mesh id, so each server needs its own. Exposes [clientLoom] to attach a
     * real player over one fresh in-memory connection into *this* server's accept source.
     */
    private inner class ServerRoomFabric(
        val serverId: PeerId,
        private val scope: CoroutineScope,
        private val dispatcher: CoroutineContext,
        random: Random,
    ) {
        private val source = InMemoryConnectionSource()
        val loom: MuxServerLoom = MuxServerLoom(
            source = source, scope = scope, selfId = serverId,
            authorizer = RoomAuthorizer.AllowAll, dispatcher = dispatcher, random = random,
        )

        /** A client [Loom] wired to THIS server over one fresh in-memory connection. */
        fun clientLoom(peerId: PeerId, random: Random): Loom {
            val base = object : Loom {
                override suspend fun weave(rendezvous: Rendezvous): Seam {
                    val (serverConn, clientConn) = connectionPair()
                    source.offer(serverConn)
                    return meshSeam(
                        selfId = peerId, connections = listOf(clientConn),
                        dispatcher = dispatcher, random = random,
                    )
                }
            }
            return MuxClientLoom(
                base = base,
                baseRendezvous = Rendezvous.New(Pattern(peerId.value)),
                scope = scope,
                nameOf = { rv ->
                    when (rv) {
                        is Rendezvous.New -> rv.pattern.sessionName
                        is Rendezvous.Existing -> rv.tag.sessionName
                    }
                },
            )
        }
    }

    /** One core server's *game-agnostic* infrastructure, shared by every game hosted on this server. */
    private class ServerInfra(
        val id: NodeId,
        val scope: CoroutineScope,
        val coreMux: NamedMux,
        val overlay: OverlayServer,
        val fabric: ServerRoomFabric,
    )

    /** A stood-up N-server federated core: shared inter-server mesh, directory and room fabrics. */
    private class Federation(val members: Map<NodeId, ServerInfra>) {
        val ids: List<NodeId> get() = members.keys.toList()
        fun fabric(id: NodeId): ServerRoomFabric = members.getValue(id).fabric
    }

    /**
     * Stand up the shared, game-agnostic federation infrastructure — the failover test's inter-server
     * mesh + directory wiring, but with room fabrics that carry REAL client connections
     * ([ServerRoomFabric]) and one child scope per server so games can be hosted onto it. Host a game
     * on it with [hostGame]; a game is isolated from another by its per-game channel + room, over this
     * one shared core.
     */
    private suspend fun CoroutineScope.federation(
        ids: List<NodeId>,
        dispatcher: CoroutineContext,
        clock: () -> Long,
    ): Federation {
        val coreSeams = interServerMesh(ids, dispatcher, this)
        val members = ids.mapIndexed { i, id ->
            val scope = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
            val coreMux = NamedMux(coreSeams.getValue(id), scope)
            // Full OverlayServer: the directory (read by the federated transport via ::lookup) plus a
            // routing spoke over a DISTINCT channel — the game path never uses the router (delivery is
            // the room hub's membership), but `attachConnections` drives the directory half through it.
            val overlay = overlayServer(
                self = PeerId(id.value),
                coreSeam = coreMux.channel(ROUTING_CHANNEL),
                directorySeam = coreMux.channel(DIRECTORY_CHANNEL),
                scope = scope,
                clock = clock,
                directoryConfig = QuilterConfig(expectVirtualTime = true),
            )
            val fabric = ServerRoomFabric(PeerId(id.value), scope, dispatcher, Random(100L + i))
            id to ServerInfra(id, scope, coreMux, overlay, fabric)
        }.toMap()
        return Federation(members)
    }

    /**
     * Host one [gameId] on every server of [fed] via [gameNodeRoomFederated], carving a fresh per-game
     * channel off each server's shared core mux and launching each server's directory-publish loop.
     * Returns the per-server [GameSession]s. Two calls with different [gameId]s ride the same core
     * isolated only by channel + room (the property G3 pins).
     */
    private suspend fun CoroutineScope.hostGame(fed: Federation, gameId: String): Map<NodeId, GameSession> {
        val core = fed.ids.map { PeerId(it.value) }.toSet()
        return fed.members.values.mapIndexed { i, infra ->
            val perGameCore = infra.coreMux.channel(gameId)
            val session = infra.scope.gameNodeRoomFederated(
                rooms = infra.fabric.loom, gameId = gameId, core = fed.ids.toSet(),
                perGameCore = perGameCore, attachment = infra.overlay::lookup,
                raftConfig = fedRaftConfig(i + 1L), random = Random(200L + i), clock = inertClock,
            )
            // Production connection-layer publisher: each player whose link this server accepts is
            // attached into the routing directory, which the leader's RoutedRaftTransport reads to pick
            // its core hop. This is the shipped `attachConnections` — the hand-rolled per-game roster
            // publisher it replaces is gone; the G1 guard now protects the production wiring.
            infra.scope.attachConnections(infra.fabric.loom.connectedPeers, infra.overlay, core)
            infra.id to session
        }.toMap()
    }

    /**
     * Join a real federated player behind [fabric]'s server via the production federated-player call
     * site: `gameNodeRoom(playerLoom, gameId, voterIds = core, placement = federatedPlayer(core))`.
     * The player role always forwards Raft to its one server; it owns no directory.
     *
     * When [tap] is supplied, the player's session seam is wrapped so every inbound [Swatch] is
     * recorded (single-collection preserved — the tap runs inline in the one collection), for G4's
     * leak-boundary inspection.
     */
    private suspend fun CoroutineScope.joinFederatedPlayer(
        fabric: ServerRoomFabric,
        gameId: String,
        core: Set<NodeId>,
        playerId: PeerId,
        seed: Long,
        tap: MutableList<Swatch>? = null,
    ): GameSession {
        val loom = if (tap == null) fabric.clientLoom(playerId, Random(seed)) else {
            val inner = fabric.clientLoom(playerId, Random(seed))
            object : Loom {
                override suspend fun weave(rendezvous: Rendezvous): Seam = TappingSeam(inner.weave(rendezvous), tap)
            }
        }
        return gameNodeRoom(
            rooms = loom, gameId = gameId, voterIds = core,
            raftConfig = fedRaftConfig(seed), random = Random(seed), clock = inertClock,
            placement = ConsensusPlacement.federatedPlayer(core),
        )
    }

    /**
     * Collect [node]'s committed application ints from index 1 until (and including) [target] appears,
     * returning the full prefix seen. Bounded — terminates at [target]; a leak of another game's action
     * committed before [target] would appear in the returned list.
     */
    private suspend fun collectCommittedIntsUntil(node: RaftNode, target: Int): List<Int> {
        val out = mutableListOf<Int>()
        committedInts(node).first { out += it; it == target }
        return out
    }

    private companion object {
        /** Below-overlay mux tags (see `GameNode.kt`): the flood plane, the relay, and the roster. */
        const val BROADCAST_TAG: Byte = 0
        const val RAFT_RELAY_TAG: Byte = 5
        const val CORE_ROSTER_TAG: Byte = 6

        /** Distinct payload markers so a direct delivery and a laundered flood are told apart in G5. */
        const val DIRECT_MARKER: Byte = 0xA1.toByte()
        const val FORGED_MARKER: Byte = 0xB2.toByte()

        const val DIRECTORY_CHANNEL = "__attachment_directory__"
        const val ROUTING_CHANNEL = "__overlay_routing__"

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

        /**
         * Hand-encodes a `us.tractat.kuilt.gossip.GossipSeam` relay frame (the type is `internal` to
         * kuilt-gossip). Wire format:
         * `[MAGIC 'gsp1'][VERSION 1][ttl][originLen: 2 BE][origin UTF-8][seq: 8 BE][payload]`.
         * Mirrors `CommitSafetyLaunderingE2ETest.gossipFrameBytes`.
         */
        fun gossipFrameBytes(origin: String, seq: Long, ttl: Int, payload: ByteArray): ByteArray {
            val originBytes = origin.encodeToByteArray()
            val out = ByteArray(8 + originBytes.size + 8 + payload.size)
            var i = 0
            byteArrayOf(0x67, 0x73, 0x70, 0x31).copyInto(out, i); i += 4 // MAGIC 'gsp1'
            out[i++] = 1 // VERSION
            out[i++] = ttl.toByte()
            out[i++] = (originBytes.size ushr 8).toByte()
            out[i++] = originBytes.size.toByte()
            originBytes.copyInto(out, i); i += originBytes.size
            for (shift in 56 downTo 0 step 8) out[i++] = (seq ushr shift).toByte()
            payload.copyInto(out, i)
            return out
        }
    }
}

/** The test dispatcher backing this [runTest] body — the FIFO [StandardTestDispatcher]. */
private suspend fun testDispatcher(): CoroutineContext =
    requireNotNull(coroutineContext[ContinuationInterceptor]) { "no test dispatcher in context" }

/**
 * A transparent [Seam] decorator that records every inbound [Swatch] into [tap] as it flows through
 * the single collection — the observation surface G4 uses to inspect what reaches a player's session
 * without breaking the single-collection contract (the tap runs inline in the one collection the game
 * bootstrap performs).
 */
private class TappingSeam(
    private val delegate: Seam,
    private val tap: MutableList<Swatch>,
) : Seam {
    override val selfId: PeerId get() = delegate.selfId
    override val peers get() = delegate.peers
    override val state get() = delegate.state
    // `onEach` is a transparent intermediate operator — it does NOT start a second collection; the
    // tap runs inline in whatever single collection the game bootstrap performs.
    override val incoming: Flow<Swatch> = delegate.incoming.onEach { tap += it }
    override suspend fun broadcast(payload: ByteArray) = delegate.broadcast(payload)
    override suspend fun sendTo(peer: PeerId, payload: ByteArray) = delegate.sendTo(peer, payload)
    override suspend fun close(reason: us.tractat.kuilt.core.CloseReason) = delegate.close(reason)
}

/** The below-overlay relay (tag 5) and roster (tag 6) mux tags — the kuilt-game consts are module-internal. */
private const val RELAY_CHANNEL_TAG: Byte = 5
private const val ROSTER_CHANNEL_TAG: Byte = 6

/**
 * A [ConsensusPlacement] that becomes the sole collector of the bootstrap-built **relay** (tag 5) and
 * **roster** (tag 6) channels and returns a [FakeRaftNode] that ignores the transport — the G5 spy.
 * Whatever surfaces on those two below-overlay channels is recorded; a forged flood frame that the
 * #1370 layering rejects never does.
 */
private class RelayRosterChannelSpy : ConsensusPlacement {
    val relayReceived = mutableListOf<Swatch>()
    val rosterReceived = mutableListOf<Swatch>()
    override val seating: AuthoritySeating = AuthoritySeating.SessionPeers
    override fun node(scope: CoroutineScope, binding: ConsensusBinding): RaftNode {
        scope.launch { binding.channel(RELAY_CHANNEL_TAG).incoming.collect { relayReceived += it } }
        scope.launch { binding.channel(ROSTER_CHANNEL_TAG).incoming.collect { rosterReceived += it } }
        return FakeRaftNode(binding.self, initialRole = RaftRole.Leader)
    }
}

/** The first payload byte of a below-overlay channel frame (the mux strips the leading tag). */
private fun Swatch.marker(): Byte = if (payloadSize >= 1) byteAt(0) else 0

/** Whether [sub] appears as a contiguous byte-subsequence of this array. */
private fun ByteArray.containsSub(sub: ByteArray): Boolean {
    if (sub.isEmpty() || sub.size > size) return false
    outer@ for (start in 0..size - sub.size) {
        for (j in sub.indices) if (this[start + j] != sub[j]) continue@outer
        return true
    }
    return false
}
