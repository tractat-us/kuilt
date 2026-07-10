@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.cluster

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftEnvelope
import us.tractat.kuilt.raft.RaftTransport
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The security-critical core of the cross-core Raft relay: [RoutedRaftTransport]
 * routes a Raft RPC to a node behind another server while keeping the true origin
 * intact, and rejects a spoofed origin before it can reach any engine.
 *
 * Like [RoutedUnicastRouterTest] these drive real [Seam]s ([InMemoryLoom]) under
 * `UnconfinedTestDispatcher` with a tight timeout — no Raft cluster, so no
 * `MultiNodeRaftSim`; the transport itself is the unit under test. The inner
 * transport is a controllable [FakeInnerTransport] fake so each routing decision
 * is asserted structurally.
 */
class RoutedRaftTransportTest {

    // ── Routing decisions ────────────────────────────────────────────────────

    @Test
    fun directPeerGoesToInnerUnchanged() = runTest(UnconfinedTestDispatcher(), timeout = 10.seconds) {
        val (relay, _) = relayLoomWith("self", "other")
        val self = NodeId("n-self")
        val x = NodeId("n-x")
        val inner = FakeInnerTransport(selfId = self, peers = setOf(self, x))
        val recording = RecordingSeam(relay)
        val t = serverRelayTransport(inner, recording, core = setOf(self), scope = backgroundScope, attachment = { null })

        t.sendTo(x, "hello".encodeToByteArray())
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(x), inner.sent.map { it.first }, "a direct peer must go straight to inner")
        assertEquals("hello", inner.sent.single().second.decodeToString())
        assertTrue(recording.sentTo.isEmpty(), "no relay frame for a direct peer")
        assertEquals(0, recording.broadcastCount, "the relay decorator must never broadcast")
    }

    @Test
    fun remotePlayerRelayedViaAttachment() = runTest(UnconfinedTestDispatcher(), timeout = 10.seconds) {
        // S1 holds a frame for a player behind S2. Next hop = attachment(player) = S2.
        val (s1Relay, peers) = relayLoomWith("s1", "s2")
        val s1 = NodeId(s1Relay.selfId.value)
        val s2 = NodeId(peers.getValue("s2").selfId.value)
        val premote = NodeId("player-remote")
        val inner = FakeInnerTransport(selfId = s1, peers = setOf(s1)) // S2 not directly reachable
        val recording = RecordingSeam(s1Relay)
        val t = serverRelayTransport(
            inner, recording, core = setOf(s1, s2), scope = backgroundScope,
            attachment = { if (it == premote) s2 else null },
        )
        val atS2 = collectInto(peers.getValue("s2"))

        t.sendTo(premote, "log".encodeToByteArray())
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(peers.getValue("s2").selfId), recording.sentTo, "must relay via the player's server")
        val delivered = RaftRelay.decode(atS2.single().toByteArray())
        assertEquals(RaftRelay(s1, premote, "log".encodeToByteArray()), delivered, "origin/dest/bytes preserved")
    }

    @Test
    fun remoteCoreServerRelayedToThatSeamPeer() = runTest(UnconfinedTestDispatcher(), timeout = 10.seconds) {
        // S1 holds a frame for core server S2 (not a direct inner peer): send to S2 over the core.
        val (s1Relay, peers) = relayLoomWith("s1", "s2")
        val s1 = NodeId(s1Relay.selfId.value)
        val s2 = NodeId(peers.getValue("s2").selfId.value)
        val inner = FakeInnerTransport(selfId = s1, peers = setOf(s1))
        val recording = RecordingSeam(s1Relay)
        val t = serverRelayTransport(inner, recording, core = setOf(s1, s2), scope = backgroundScope, attachment = { null })
        val atS2 = collectInto(peers.getValue("s2"))

        t.sendTo(s2, "vote".encodeToByteArray())
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(peers.getValue("s2").selfId), recording.sentTo)
        assertEquals(RaftRelay(s1, s2, "vote".encodeToByteArray()), RaftRelay.decode(atS2.single().toByteArray()))
    }

    @Test
    fun playerAlwaysForwardsToItsOneServer() = runTest(UnconfinedTestDispatcher(), timeout = 10.seconds) {
        // A player's relay channel has exactly one other peer — its server. A frame for
        // any node it cannot reach directly (e.g. the leader) is relayed to that server.
        val (pRelay, peers) = relayLoomWith("player", "server")
        // A node's inner transport and its relay channel share one identity; the player's
        // self-filter over relayChannel.peers relies on that to find its single server.
        val self = NodeId(pRelay.selfId.value)
        val leader = NodeId("leader-x")
        val server = peers.getValue("server").selfId
        val inner = FakeInnerTransport(selfId = self, peers = setOf(self)) // leader not a direct peer
        val recording = RecordingSeam(pRelay)
        val t = playerRelayTransport(inner, recording, core = setOf(NodeId(server.value)), scope = backgroundScope)
        val atServer = collectInto(peers.getValue("server"))
        testScheduler.advanceUntilIdle()

        t.sendTo(leader, "reply".encodeToByteArray())
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(server), recording.sentTo, "player forwards to its single server")
        assertEquals(RaftRelay(self, leader, "reply".encodeToByteArray()), RaftRelay.decode(atServer.single().toByteArray()))
    }

    // ── from-preservation ────────────────────────────────────────────────────

    @Test
    fun relayedFrameSurfacesWithTrueOriginAsFrom() = runTest(UnconfinedTestDispatcher(), timeout = 10.seconds) {
        // S2 (a core peer) relays a frame that originated at a player behind it, destined
        // for S1. S1's engine must see from = the player (origin), NEVER S2 (the relay).
        val (s1Relay, peers) = relayLoomWith("s1", "s2")
        val s1 = NodeId(s1Relay.selfId.value)
        val s2Seam = peers.getValue("s2")
        val s2 = NodeId(s2Seam.selfId.value)
        val origin = NodeId("player-behind-s2")
        val inner = FakeInnerTransport(selfId = s1, peers = setOf(s1))
        val t = serverRelayTransport(inner, s1Relay, core = setOf(s1, s2), scope = backgroundScope, attachment = { null })
        val received = collectInto(t)
        testScheduler.advanceUntilIdle()

        s2Seam.sendTo(s1Relay.selfId, RaftRelay.encode(RaftRelay(origin, s1, "committed".encodeToByteArray())))
        testScheduler.advanceUntilIdle()

        assertEquals(1, received.size)
        assertEquals(origin, received.single().from, "from must be the true origin, not the relaying server")
        assertContentEquals("committed".encodeToByteArray(), received.single().bytes)
    }

    // ── Happy-path forwarding ────────────────────────────────────────────────

    @Test
    fun serverForwardsCoreFrameDownToLocalPlayer() = runTest(UnconfinedTestDispatcher(), timeout = 10.seconds) {
        // S2 relays a frame across the core to S1 for a player P that is local to S1.
        // S1 forwards it down to P, origin preserved, and does NOT hand it to its own engine.
        val (s1Relay, peers) = relayLoomWith("s1", "s2", "p")
        val s1 = NodeId(s1Relay.selfId.value)
        val s2Seam = peers.getValue("s2")
        val s2 = NodeId(s2Seam.selfId.value)
        val pSeam = peers.getValue("p")
        val pNode = NodeId(pSeam.selfId.value)
        val origin = NodeId("leader-x")
        val inner = FakeInnerTransport(selfId = s1, peers = setOf(s1, pNode)) // P directly reachable
        val recording = RecordingSeam(s1Relay)
        val t = serverRelayTransport(inner, recording, core = setOf(s1, s2), scope = backgroundScope, attachment = { null })
        val received = collectInto(t)
        val atP = collectInto(pSeam)
        testScheduler.advanceUntilIdle()

        s2Seam.sendTo(s1Relay.selfId, RaftRelay.encode(RaftRelay(origin, pNode, "append".encodeToByteArray())))
        testScheduler.advanceUntilIdle()

        assertTrue(received.isEmpty(), "a frame for the player must NOT surface on the server's own engine")
        assertEquals(listOf(pSeam.selfId), recording.sentTo, "forwarded down to exactly the local player")
        assertEquals(RaftRelay(origin, pNode, "append".encodeToByteArray()), RaftRelay.decode(atP.single().toByteArray()))
    }

    @Test
    fun serverTakesOneCoreHopForSpokeFrame() = runTest(UnconfinedTestDispatcher(), timeout = 10.seconds) {
        // A local spoke P sends a frame (for itself) destined for a player behind S2.
        // S1 takes one core hop to S2 = attachment(dest).
        val (s1Relay, peers) = relayLoomWith("s1", "s2", "p")
        val s1 = NodeId(s1Relay.selfId.value)
        val s2Seam = peers.getValue("s2")
        val s2 = NodeId(s2Seam.selfId.value)
        val pSeam = peers.getValue("p")
        val pNode = NodeId(pSeam.selfId.value)
        val premote = NodeId("player-behind-s2")
        val inner = FakeInnerTransport(selfId = s1, peers = setOf(s1, pNode))
        val recording = RecordingSeam(s1Relay)
        val t = serverRelayTransport(
            inner, recording, core = setOf(s1, s2), scope = backgroundScope,
            attachment = { if (it == premote) s2 else null },
        )
        val received = collectInto(t)
        val atS2 = collectInto(s2Seam)
        testScheduler.advanceUntilIdle()

        // Spoke P speaks for itself (origin == its own id) to a remote destination.
        pSeam.sendTo(s1Relay.selfId, RaftRelay.encode(RaftRelay(pNode, premote, "prop".encodeToByteArray())))
        testScheduler.advanceUntilIdle()

        assertTrue(received.isEmpty(), "not for S1's engine")
        assertEquals(listOf(s2Seam.selfId), recording.sentTo, "one core hop to the destination's server")
        assertEquals(RaftRelay(pNode, premote, "prop".encodeToByteArray()), RaftRelay.decode(atS2.single().toByteArray()))
    }

    // ── G5: origin-spoofing rejected (commit-safety) ─────────────────────────

    @Test
    fun g5_spokeSpoofingAnotherOriginIsRejected() = runTest(UnconfinedTestDispatcher(), timeout = 10.seconds) {
        // A spoke P forges origin = S1 (the leader) on a frame destined for S1. It must
        // reach NO engine. A subsequent legitimate core frame proves the pipeline still
        // works — only the forgery was dropped.
        val (s1Relay, peers) = relayLoomWith("s1", "s2", "p")
        val s1 = NodeId(s1Relay.selfId.value)
        val s2Seam = peers.getValue("s2")
        val s2 = NodeId(s2Seam.selfId.value)
        val pSeam = peers.getValue("p") // a spoke, not in core
        val inner = FakeInnerTransport(selfId = s1, peers = setOf(s1))
        val t = serverRelayTransport(inner, s1Relay, core = setOf(s1, s2), scope = backgroundScope, attachment = { null })
        val received = collectInto(t)
        testScheduler.advanceUntilIdle()

        // Forged: sender = P but origin claims to be S1.
        pSeam.sendTo(s1Relay.selfId, RaftRelay.encode(RaftRelay(origin = s1, dest = s1, bytes = "forged-vote".encodeToByteArray())))
        testScheduler.advanceUntilIdle()
        assertTrue(received.isEmpty(), "a spoofed origin must reach no engine")

        // Positive control: a legit core frame with a matching origin does surface.
        val legit = NodeId("real-origin")
        s2Seam.sendTo(s1Relay.selfId, RaftRelay.encode(RaftRelay(origin = legit, dest = s1, bytes = "ok".encodeToByteArray())))
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(legit), received.map { it.from }, "only the legitimate frame surfaces")
    }

    @Test
    fun g5_nonCoreSenderForgingCoreOriginIsRejectedAndNotForwarded() = runTest(UnconfinedTestDispatcher(), timeout = 10.seconds) {
        // A spoke P forges origin = S2 (a core member) on a frame destined for a remote
        // player. It must neither surface locally NOR be forwarded onto the core.
        val (s1Relay, peers) = relayLoomWith("s1", "s2", "p")
        val s1 = NodeId(s1Relay.selfId.value)
        val s2Seam = peers.getValue("s2")
        val s2 = NodeId(s2Seam.selfId.value)
        val pSeam = peers.getValue("p")
        val premote = NodeId("player-behind-s2")
        val inner = FakeInnerTransport(selfId = s1, peers = setOf(s1))
        val recording = RecordingSeam(s1Relay)
        val t = serverRelayTransport(
            inner, recording, core = setOf(s1, s2), scope = backgroundScope,
            attachment = { if (it == premote) s2 else null },
        )
        val received = collectInto(t)
        testScheduler.advanceUntilIdle()

        pSeam.sendTo(s1Relay.selfId, RaftRelay.encode(RaftRelay(origin = s2, dest = premote, bytes = "forged".encodeToByteArray())))
        testScheduler.advanceUntilIdle()

        assertTrue(received.isEmpty(), "a non-core sender's core-origin forgery must not surface")
        assertTrue(recording.sentTo.isEmpty(), "and must never be forwarded onto the core")
    }

    @Test
    fun g5_coreFrameForNonLocalDestIsNotReforwarded_loopGuard() = runTest(UnconfinedTestDispatcher(), timeout = 10.seconds) {
        // A frame arriving from the core whose destination is neither this server nor a
        // local player must be DROPPED — never re-forwarded onto the core (the loop guard).
        val (s1Relay, peers) = relayLoomWith("s1", "s2", "s3")
        val s1 = NodeId(s1Relay.selfId.value)
        val s2Seam = peers.getValue("s2")
        val s2 = NodeId(s2Seam.selfId.value)
        val s3 = NodeId(peers.getValue("s3").selfId.value)
        val remote = NodeId("player-elsewhere")
        val inner = FakeInnerTransport(selfId = s1, peers = setOf(s1)) // remote is NOT local
        val recording = RecordingSeam(s1Relay)
        val t = serverRelayTransport(
            inner, recording, core = setOf(s1, s2, s3), scope = backgroundScope,
            attachment = { s3 }, // even though a hop "exists", a core-received frame must not use it
        )
        val received = collectInto(t)
        testScheduler.advanceUntilIdle()

        s2Seam.sendTo(s1Relay.selfId, RaftRelay.encode(RaftRelay(origin = NodeId("leader"), dest = remote, bytes = "x".encodeToByteArray())))
        testScheduler.advanceUntilIdle()

        assertTrue(received.isEmpty(), "not destined here — must not surface")
        assertTrue(recording.sentTo.isEmpty(), "a core-received frame must never be re-forwarded onto the core")
    }

    // ── G6: strict no-op off federation ──────────────────────────────────────

    @Test
    fun g6_noRelayFrameWhenAllPeersAreLocal() = runTest(UnconfinedTestDispatcher(), timeout = 10.seconds) {
        // Every addressee is a direct inner peer: sendTo delegates to inner and NO relay
        // frame is ever emitted on the relay channel.
        val (relay, _) = relayLoomWith("self", "other")
        val self = NodeId("n-self")
        val a = NodeId("n-a")
        val b = NodeId("n-b")
        val inner = FakeInnerTransport(selfId = self, peers = setOf(self, a, b))
        val recording = RecordingSeam(relay)
        val t = serverRelayTransport(inner, recording, core = setOf(self, a, b), scope = backgroundScope, attachment = { null })

        t.sendTo(a, "1".encodeToByteArray())
        t.sendTo(b, "2".encodeToByteArray())
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(a, b), inner.sent.map { it.first }, "all sends went to inner")
        assertTrue(recording.sentTo.isEmpty(), "no RAFT_RELAY frame emitted off-federation")
        assertEquals(0, recording.broadcastCount, "and nothing broadcast")
    }

    // ── G7: payload budget ───────────────────────────────────────────────────

    @Test
    fun g7_payloadBudgetLeavesRoomForEnvelope() = runTest(UnconfinedTestDispatcher(), timeout = 10.seconds) {
        val (relay, _) = relayLoomWith("self", "other")
        val self = NodeId("node-self-with-a-realistic-length-id")
        val innerLimit = 32_768
        val inner = FakeInnerTransport(selfId = self, peers = setOf(self), maxPayloadBytes = innerLimit)
        val t = serverRelayTransport(inner, relay, core = setOf(self), scope = backgroundScope, attachment = { null })

        val budgeted = t.maxPayloadBytes!!
        assertTrue(budgeted < innerLimit, "the relay must reserve room for its envelope")
        assertTrue(innerLimit - budgeted >= RELAY_HEADER_BUDGET, "reserve at least the header budget")

        // A ceiling-sized chunk plus its envelope must still fit the inner frame limit.
        val dest = NodeId("another-realistic-length-destination-node-id")
        val wire = RaftRelay.encode(RaftRelay(self, dest, ByteArray(budgeted)))
        assertTrue(wire.size <= innerLimit, "chunk + envelope (${wire.size}) must fit inner limit ($innerLimit)")
    }

    @Test
    fun g7_unboundedInnerStaysUnbounded() = runTest(UnconfinedTestDispatcher(), timeout = 10.seconds) {
        val (relay, _) = relayLoomWith("self", "other")
        val self = NodeId("n-self")
        val inner = FakeInnerTransport(selfId = self, peers = setOf(self), maxPayloadBytes = null)
        val t = serverRelayTransport(inner, relay, core = setOf(self), scope = backgroundScope, attachment = { null })
        assertNull(t.maxPayloadBytes, "an unbounded inner transport leaves the relay unbounded")
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Host a relay [InMemoryLoom] with one seam per given name; the first is the host.
     * Returns the first seam plus a name→seam map of all of them.
     */
    private suspend fun relayLoomWith(vararg names: String): Pair<Seam, Map<String, Seam>> {
        val loom = InMemoryLoom()
        val seams = LinkedHashMap<String, Seam>()
        names.forEachIndexed { i, name ->
            seams[name] = if (i == 0) loom.host(Pattern("relay")) else loom.join(InMemoryTag("relay"))
        }
        return seams.values.first() to seams
    }

    /** Collect a seam's incoming into a growing list on the test's background scope. */
    private fun TestScope.collectInto(seam: Seam): List<Swatch> {
        val received = mutableListOf<Swatch>()
        backgroundScope.launch { seam.incoming.collect { received += it } }
        return received
    }

    /** Collect a transport's incoming into a growing list on the test's background scope. */
    private fun TestScope.collectInto(transport: RaftTransport): List<RaftEnvelope> {
        val received = mutableListOf<RaftEnvelope>()
        backgroundScope.launch { transport.incoming.collect { received += it } }
        return received
    }
}

/**
 * A controllable [RaftTransport] fake: fixed [selfId] and [peers], a recording
 * [sendTo], and a hot [incoming] a test can feed. Test-only; access is serial
 * under `runTest`.
 */
private class FakeInnerTransport(
    override val selfId: NodeId,
    peers: Set<NodeId>,
    override val maxPayloadBytes: Int? = null,
) : RaftTransport {
    override val peers: StateFlow<Set<NodeId>> = MutableStateFlow(peers)
    val sent: MutableList<Pair<NodeId, ByteArray>> = mutableListOf()
    private val incomingFlow: MutableSharedFlow<RaftEnvelope> =
        MutableSharedFlow(extraBufferCapacity = Int.MAX_VALUE)
    override val incoming: Flow<RaftEnvelope> = incomingFlow

    override suspend fun sendTo(peer: NodeId, message: ByteArray) {
        sent += peer to message
    }
}
