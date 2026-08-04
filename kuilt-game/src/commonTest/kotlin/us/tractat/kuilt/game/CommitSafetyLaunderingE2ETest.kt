@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.game

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.gossip.starOverlay
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftEnvelope
import us.tractat.kuilt.raft.RaftNode
import us.tractat.kuilt.raft.RaftRole
import us.tractat.kuilt.raft.test.FakeRaftNode
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * End-to-end commit-safety regression for #1370, exercising the **real [gameNode] bootstrap wiring**
 * (not a hand-assembled composition).
 *
 * A [gameNode] built with a gossip `overlay` (the star/mesh room path) must keep the Raft channel
 * BELOW the flood, so the overlay's origin-restamping ([us.tractat.kuilt.gossip.GossipSeam]
 * re-stamps `sender = frame.origin`) can never launder a forged consensus `from` into the engine.
 *
 * The observation seam is a [ConsensusPlacement] spy: it records every [RaftEnvelope] that reaches
 * the Raft channel of the *bootstrap-built* mux, then returns a [FakeRaftNode] (which never collects
 * the transport), so the spy is the sole collector. This flips on the layering change — before the
 * fix (mux over the overlay) the forged frame surfaces on the Raft channel with `from = V`; after
 * it (overlay below the raft mux) it never does.
 */
class CommitSafetyLaunderingE2ETest {

    /** The game layer's private Raft mux tag (channel 1). */
    private val raftTag: Byte = 1

    /** The game layer's private broadcast (flood-plane) mux tag (channel 0). */
    private val broadcastTag: Byte = 0

    /**
     * A placement that spies on the bootstrap-built Raft transport: it becomes the sole collector of
     * the Raft channel and returns a [FakeRaftNode] that ignores the transport entirely.
     */
    private class RaftChannelSpy : ConsensusPlacement {
        val received = mutableListOf<RaftEnvelope>()
        override val seating: AuthoritySeating = AuthoritySeating.SessionPeers
        override fun node(scope: CoroutineScope, binding: ConsensusBinding): RaftNode {
            scope.launch { binding.transport.incoming.collect { received += it } }
            return FakeRaftNode(binding.self, initialRole = RaftRole.Leader)
        }
    }

    /**
     * Hand-encodes a [us.tractat.kuilt.gossip.GossipSeam] relay frame (the type is `internal` to
     * kuilt-gossip). Wire format:
     * `[MAGIC 'gsp1'][VERSION 1][ttl][originLen: 2 BE][origin UTF-8][seq: 8 BE][payload]`.
     */
    private fun gossipFrameBytes(origin: String, seq: Long, ttl: Int, payload: ByteArray): ByteArray {
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

    @Test
    fun forgedGossipFrameIsNotLaunderedIntoTheRaftChannel() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val raw = FakeSeam(selfId = PeerId("H"), initialPeers = setOf(PeerId("H"), PeerId("V"), PeerId("X")))
        val spy = RaftChannelSpy()

        // The real bootstrap: a room-style node over a star-relay overlay. Sole voter H → leader
        // (the FakeRaftNode is pinned Leader); the spy observes the bootstrap-built Raft channel.
        backgroundScope.gameNode(
            seam = raw,
            voterIds = setOf(NodeId("H")),
            raftConfig = fastRaftConfig(seed = 1L),
            placement = spy,
            overlay = { starOverlay(it, Random(7), inertTestClock) },
        )
        runCurrent()
        advanceTimeBy(10)
        runCurrent()

        // Positive control: an AUTHENTIC unicast Raft frame on the Raft channel DOES reach the engine —
        // proving the spy observes real Raft-channel traffic (so absence of a forged sender is meaningful).
        raw.deliver(PeerId("authpeer"), byteArrayOf(raftTag, 5, 5, 5))
        runCurrent()

        val forgedRaftPayload = byteArrayOf(raftTag, 9, 9, 9)
        // (a) Naive attack: a bare gossip frame claiming origin = V, carrying [RAFT][forged].
        raw.deliver(PeerId("X"), gossipFrameBytes(origin = "V", seq = 1, ttl = 5, payload = forgedRaftPayload))
        // (b) Adapted attack: prefix the broadcast tag so it reaches the flood plane, then relies on
        //     the overlay to re-stamp sender = V.
        raw.deliver(
            PeerId("X"),
            byteArrayOf(broadcastTag) + gossipFrameBytes(origin = "V", seq = 2, ttl = 5, payload = forgedRaftPayload),
        )
        runCurrent()

        assertAll(
            {
                assertTrue(
                    spy.received.any { it.from == NodeId("authpeer") },
                    "positive control: an authentic Raft-channel frame reaches the engine (from=${spy.received.map { it.from }})",
                )
            },
            {
                assertTrue(
                    spy.received.none { it.from == NodeId("V") },
                    "a forged GossipFrame(origin=V) must NEVER surface on the Raft channel with from=V (#1370) — " +
                        "got ${spy.received.map { it.from }}",
                )
            },
        )
    }
}
