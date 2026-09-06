@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.cluster

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftEnvelope
import us.tractat.kuilt.raft.RaftTransport
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * What [RoutedRaftTransport] owes a collector that subscribes **after** the transport was built
 * (#2106) — which is the production order, not an edge case: `buildClusterClient` and
 * `ConsensusPlacement.federatedCore` both construct the transport first and the `RaftNode` that
 * collects it second.
 *
 * The rest of the suite ([RoutedRaftTransportTest]) runs under `UnconfinedTestDispatcher`, where
 * every `launch` is eager and every subscription is therefore already in place by the time a test
 * emits — which is exactly why it is blind to this. These run under [StandardTestDispatcher] and
 * pump **nothing** between constructing a collector and emitting to it; a `runCurrent()` in that
 * window hides the defect, so each one is placed deliberately.
 *
 * `runCurrent()` rather than `advanceUntilIdle()` throughout: `advanceUntilIdle` stops as soon as
 * no *foreground* task remains, so it never dispatches a `backgroundScope` collector at all — every
 * arm here would then be vacuously empty, defect or no defect.
 */
class RoutedRaftTransportIncomingSubscriptionTest {

    @Test
    fun relayFrameArrivingBeforeTheEngineCollectsSurfaces() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            // Production order: the transport exists (and its relay pump is live) before the engine
            // ever calls `incoming`. A down-frame that lands in that window must not evaporate.
            val (pRelay, peers) = relayLoomWith("player", "server")
            val self = NodeId(pRelay.selfId.value)
            val serverSeam = peers.getValue("server")
            val voterA = NodeId("voter-a")
            val inner = SharedFlowInnerTransport(selfId = self, peers = setOf(self))
            val t = playerRelayTransport(inner, pRelay, voters = { setOf(voterA) }, scope = backgroundScope)
            testScheduler.runCurrent()

            serverSeam.sendTo(
                pRelay.selfId,
                RaftRelay.encode(RaftRelay(origin = voterA, dest = self, bytes = "append".encodeToByteArray())),
            )
            testScheduler.runCurrent()

            // Only now does the engine subscribe.
            val received = collectInto(t)
            testScheduler.runCurrent()

            assertEquals(listOf(voterA), received.map { it.from }, "a relayed frame must survive until the engine collects")
            assertContentEquals("append".encodeToByteArray(), received.single().bytes)
        }

    @Test
    fun innerFrameEmittedBeforeTheCollectorIsDispatchedSurfaces() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            // The #2104 shape: construct the collector and emit in one block with nothing pumped in
            // between. `merge` subscribes from a launched child, so the subscription lands a dispatch
            // turn after the collector's own coroutine first runs — and a replay-0 source drops
            // everything emitted in that window.
            val (relay, _) = relayLoomWith("self", "other")
            val self = NodeId("n-self")
            val peer = NodeId("n-peer")
            val inner = SharedFlowInnerTransport(selfId = self, peers = setOf(self))
            val t = serverRelayTransport(inner, relay, core = setOf(self), scope = backgroundScope, attachment = { null })
            testScheduler.runCurrent()

            val received = collectInto(t)
            inner.emit(RaftEnvelope(peer, "direct".encodeToByteArray()))
            testScheduler.runCurrent()

            assertEquals(listOf(peer), received.map { it.from }, "a direct frame must not fall into the subscription window")
            assertContentEquals("direct".encodeToByteArray(), received.single().bytes)
        }

    @Test
    fun channelBackedInnerStreamIsNotDrainedIntoTheVoid() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            // Every real `Seam.incoming` is a `Spool` — a bounded *channel* — so a frame that arrives
            // before its collector is BUFFERED, not dropped. Unioning the two routes at the producers
            // must preserve that: an eager pump feeding a replay-0 `MutableSharedFlow` would drain
            // this frame into the void, moving the drop rather than removing it. This arm is green
            // before and after the fix; it exists to keep it that way.
            val (relay, _) = relayLoomWith("self", "other")
            val self = NodeId("n-self")
            val peer = NodeId("n-peer")
            val inner = ChannelInnerTransport(selfId = self, peers = setOf(self))
            val t = serverRelayTransport(inner, relay, core = setOf(self), scope = backgroundScope, attachment = { null })

            inner.deliver(RaftEnvelope(peer, "buffered".encodeToByteArray()))
            testScheduler.runCurrent()

            val received = collectInto(t)
            testScheduler.runCurrent()

            assertEquals(listOf(peer), received.map { it.from }, "a frame buffered by a channel-backed inner must still be delivered")
            assertContentEquals("buffered".encodeToByteArray(), received.single().bytes)
        }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private suspend fun relayLoomWith(vararg names: String): Pair<Seam, Map<String, Seam>> {
        val loom = InMemoryLoom()
        val seams = LinkedHashMap<String, Seam>()
        names.forEachIndexed { i, name ->
            seams[name] = if (i == 0) loom.host(Pattern("relay")) else loom.join(InMemoryTag("relay"))
        }
        return seams.values.first() to seams
    }

    private fun TestScope.collectInto(transport: RaftTransport): List<RaftEnvelope> {
        val received = mutableListOf<RaftEnvelope>()
        backgroundScope.launch { transport.incoming.collect { received += it } }
        return received
    }
}

/**
 * An inner [RaftTransport] whose `incoming` is a replay-0 hot [MutableSharedFlow] — the shape a
 * `RoomChannelSeam`-backed `SeamRaftTransport` presents, where a frame with no subscriber is lost.
 */
private class SharedFlowInnerTransport(
    override val selfId: NodeId,
    peers: Set<NodeId>,
) : RaftTransport {
    override val peers: StateFlow<Set<NodeId>> = MutableStateFlow(peers)
    private val incomingFlow: MutableSharedFlow<RaftEnvelope> =
        MutableSharedFlow(extraBufferCapacity = Int.MAX_VALUE)
    override val incoming: Flow<RaftEnvelope> = incomingFlow

    /** Never suspends (the buffer is unbounded), so emitting pumps nothing. */
    suspend fun emit(envelope: RaftEnvelope): Unit = incomingFlow.emit(envelope)

    override suspend fun sendTo(peer: NodeId, message: ByteArray) = Unit
}

/**
 * An inner [RaftTransport] whose `incoming` is a buffered *channel* — the shape every real
 * `Seam.incoming` presents (`Spool` is `Channel.receiveAsFlow()`), where a frame delivered before
 * the collector arrives is held rather than dropped.
 */
private class ChannelInnerTransport(
    override val selfId: NodeId,
    peers: Set<NodeId>,
) : RaftTransport {
    override val peers: StateFlow<Set<NodeId>> = MutableStateFlow(peers)
    private val channel = Channel<RaftEnvelope>(capacity = 16)
    override val incoming: Flow<RaftEnvelope> = channel.receiveAsFlow()

    suspend fun deliver(envelope: RaftEnvelope) = channel.send(envelope)

    override suspend fun sendTo(peer: NodeId, message: ByteArray) = Unit
}
