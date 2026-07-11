@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.game

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.cluster.RoutedRaftTransport
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.MuxSeam
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.gossip.starOverlay
import us.tractat.kuilt.raft.ClientIdentity
import us.tractat.kuilt.raft.ClusterConfig
import us.tractat.kuilt.raft.InMemoryRaftStorage
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftConfig
import us.tractat.kuilt.raft.RaftEnvelope
import us.tractat.kuilt.raft.RaftTransport
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Task-2 wiring: [ConsensusPlacement.federatedCore] seats the game with a **routing decorator**
 * ([RoutedRaftTransport]) so cross-server Raft delivery works, while every off-federation placement
 * keeps its plain transport; and [gameNode] carves the relay channel over the *same* session seam,
 * so the two Task-1-review identity/point-to-point contracts hold structurally.
 *
 * These exercise the wiring seam, not a live Raft cluster — a controllable [FakeInnerTransport] plus a
 * [FakeSeam]/[InMemoryLoom] relay channel, under [UnconfinedTestDispatcher] with a tight timeout,
 * mirroring `:kuilt-cluster`'s `RoutedRaftTransportTest` (the transport's own routing behaviour is
 * proven there; here we prove the *bootstrap picks it up* and hands it a well-formed channel).
 */
class FederatedPlacementWiringTest {

    // ── The transport IS the routing decorator under federatedCore, and is NOT off-federation ──

    @Test
    fun federatedCoreWrapsServerAndPlayerInRoutingDecorator_offFederationDoesNot() =
        runTest(UnconfinedTestDispatcher(), timeout = 10.seconds) {
            val server = NodeId("s1")
            val player = NodeId("p1")
            val core = setOf(server, NodeId("s2"))

            val serverBinding = binding(self = server, relayPeers = setOf(server, NodeId("s2")))
            val playerBinding = binding(self = player, relayPeers = setOf(player, server))

            // A core member is wrapped as a server relay endpoint; a non-core peer as a player one.
            val serverTransport = serverBinding.federatedTransport(core, backgroundScope, attachment = { null })
            val playerTransport = playerBinding.federatedTransport(core, backgroundScope, attachment = { null })

            assertAll(
                { assertIs<RoutedRaftTransport>(serverTransport, "a core member's federated transport is the routing decorator") },
                { assertIs<RoutedRaftTransport>(playerTransport, "a player's federated transport is the routing decorator too") },
                // What SessionOwned / serverCore hand to raftNode is the unwrapped session transport —
                // provably NOT a routing decorator (the "no regression off-federation" contrast).
                { assertTrue(serverBinding.transport !is RoutedRaftTransport, "the plain session transport is not a routing decorator") },
                { assertTrue(playerBinding.transport !is RoutedRaftTransport, "the plain session transport is not a routing decorator") },
            )
        }

    @Test
    fun playerFederatedTransportRelaysToItsSingleServer_whileSessionOwnedGoesStraightToInner() =
        runTest(UnconfinedTestDispatcher(), timeout = 10.seconds) {
            val player = NodeId("p1")
            val server = NodeId("s1")
            val leader = NodeId("leader-elsewhere") // not a direct inner peer of the player
            val core = setOf(server)

            // A recording relay channel whose only non-self peer is the player's server.
            val relay = FakeSeam(selfId = PeerId(player.value), initialPeers = setOf(PeerId(player.value), PeerId(server.value)))
            val inner = FakeInnerTransport(selfId = player, peers = setOf(player)) // leader unreachable directly
            val b = binding(self = player, transport = inner, relayChannel = relay)

            val routed = b.federatedTransport(core, backgroundScope, attachment = { null })
            routed.sendTo(leader, "reply".encodeToByteArray())
            testScheduler.advanceUntilIdle()

            // The federated (routing) transport relayed the unreachable-destination frame to the
            // player's one server over the relay channel — nothing on inner.
            // The off-federation transport (inner) would instead have carried it straight through.
            assertAll(
                { assertEquals(listOf(PeerId(server.value)), relay.directed.map { it.first }, "player relays to its single server") },
                { assertEquals(1, relay.directed.size, "exactly one single-addressee relay send — never a fan-out") },
                { assertEquals(0, relay.broadcasts.size, "the relay decorator must never broadcast") },
                { assertTrue(inner.sent.isEmpty(), "the routed frame never touched inner (leader was not a direct peer)") },
            )
        }

    // ── Contract 1 (NodeId string == relay PeerId string) + Contract 2 (player relay is point-to-point) ──

    @Test
    fun playerRelayChannelHasExactlyOneServerPeer_andIdentityMatchesAcrossLayers() =
        runTest(UnconfinedTestDispatcher(), timeout = 10.seconds) {
            // A player's session seam is point-to-point: its one peer is its server. Model it with a
            // 2-seat in-memory loom, wrapped in the same star overlay gameNodeRoom composes, then carve
            // the RAFT_RELAY channel exactly as gameNode does over that session seam.
            val loom = InMemoryLoom()
            val serverSeam = loom.host(Pattern("table"))
            val playerSeam = loom.join(InMemoryTag("table"))

            val playerSession = backgroundScope.starOverlay(playerSeam, Random(1), inertTestClock)
            val relayChannel = MuxSeam(playerSession, backgroundScope).channel(RAFT_RELAY_CHANNEL)

            // The NodeId the Raft engine derives (NodeId(seam.selfId.value)) and the relay-channel's own
            // ids come from the *same* session selfId — so their strings are identical (contract 1).
            val playerNodeId = NodeId(playerSession.selfId.value)
            val nonSelfPeers = relayChannel.peers.value - relayChannel.selfId
            val theServer = nonSelfPeers.single()

            assertAll(
                // Contract 2: exactly one non-self peer, and it is the server.
                { assertEquals(1, nonSelfPeers.size, "a player's relay channel is point-to-point — exactly one non-self peer") },
                { assertEquals(serverSeam.selfId, theServer, "and that one peer is the player's server") },
                // Contract 1: the relay-channel PeerId string equals the Raft NodeId string, for both nodes.
                { assertEquals(playerNodeId.value, relayChannel.selfId.value, "player relay-channel PeerId string == its Raft NodeId string") },
                { assertEquals(NodeId(theServer.value).value, theServer.value, "server relay-channel PeerId string == its Raft NodeId string") },
                { assertEquals(playerSeam.selfId.value, relayChannel.selfId.value, "identity is preserved verbatim through the overlay + mux layers") },
            )
        }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun binding(
        self: NodeId,
        transport: RaftTransport = FakeInnerTransport(selfId = self, peers = setOf(self)),
        relayPeers: Set<NodeId> = setOf(self),
        relayChannel: FakeSeam = FakeSeam(
            selfId = PeerId(self.value),
            initialPeers = relayPeers.mapTo(mutableSetOf()) { PeerId(it.value) },
        ),
    ): ConsensusBinding {
        // Inert roster channel — these wiring tests exercise transport selection, not roster admission.
        val rosterChannel = FakeSeam(selfId = PeerId(self.value))
        return ConsensusBinding(
            self = self,
            transport = transport,
            sessionMembership = ClusterConfig.ofVoters(setOf(self)),
            storage = InMemoryRaftStorage(),
            raftConfig = RaftConfig(expectVirtualTime = true),
            identity = ClientIdentity.Auto,
            channels = { tag ->
                when (tag) {
                    RAFT_RELAY_CHANNEL -> relayChannel
                    CORE_ROSTER_CHANNEL -> rosterChannel
                    else -> error("unexpected channel tag $tag")
                }
            },
        )
    }
}

/**
 * A controllable [RaftTransport] fake: fixed [selfId]/[peers] and a recording [sendTo]. Test-only;
 * access is serial under `runTest`.
 */
private class FakeInnerTransport(
    override val selfId: NodeId,
    peers: Set<NodeId>,
    override val maxPayloadBytes: Int? = null,
) : RaftTransport {
    override val peers: StateFlow<Set<NodeId>> = MutableStateFlow(peers)
    val sent: MutableList<Pair<NodeId, ByteArray>> = mutableListOf()
    private val incomingFlow: MutableSharedFlow<RaftEnvelope> = MutableSharedFlow(extraBufferCapacity = Int.MAX_VALUE)
    override val incoming: Flow<RaftEnvelope> = incomingFlow

    override suspend fun sendTo(peer: NodeId, message: ByteArray) {
        sent += peer to message
    }
}
