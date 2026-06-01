package us.tractat.kuilt.test

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PeerNotConnected
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeSeamTest {
    // ── Defaults ─────────────────────────────────────────────────────────────

    @Test
    fun `default selfId is 'self'`() = runTest {
        val seam = FakeSeam()
        assertEquals(PeerId("self"), seam.selfId)
    }

    @Test
    fun `default peers contains only selfId`() = runTest {
        val seam = FakeSeam()
        assertEquals(setOf(PeerId("self")), seam.peers.value)
    }

    @Test
    fun `default state is Woven`() = runTest {
        val seam = FakeSeam()
        assertEquals(SeamState.Woven, seam.state.value)
    }

    @Test
    fun `custom selfId and initialPeers are respected`() = runTest {
        val alice = PeerId("alice")
        val bob = PeerId("bob")
        val seam = FakeSeam(selfId = alice, initialPeers = setOf(alice, bob))
        assertEquals(
            setOf(alice, bob),
            seam.peers.value,
        )
    }

    @Test
    fun `default initialPeers tracks a custom selfId`() = runTest {
        val alice = PeerId("alice")
        val seam = FakeSeam(selfId = alice)
        // peers must always include selfId (Seam contract) even when only selfId is customized.
        assertEquals(setOf(alice), seam.peers.value)
    }

    @Test
    fun `custom initialState Weaving is respected`() = runTest {
        val seam = FakeSeam(initialState = SeamState.Weaving)
        assertEquals(SeamState.Weaving, seam.state.value)
    }

    // ── plies default ─────────────────────────────────────────────────────────

    @Test
    fun `plies default maps PlyId Sole to current state`() = runTest {
        val seam = FakeSeam(initialState = SeamState.Weaving)
        assertEquals(SeamState.Weaving, seam.plies.value[us.tractat.kuilt.core.PlyId.Sole])
    }

    // ── Peer mutation ─────────────────────────────────────────────────────────

    @Test
    fun `addPeer adds peer to peers set`() = runTest {
        val seam = FakeSeam()
        val bob = PeerId("bob")
        seam.addPeer(bob)
        assertContains(seam.peers.value, bob)
    }

    @Test
    fun `removePeer removes peer from peers set`() = runTest {
        val bob = PeerId("bob")
        val seam = FakeSeam(initialPeers = setOf(PeerId("self"), bob))
        seam.removePeer(bob)
        assertFalse(bob in seam.peers.value)
    }

    // ── Lifecycle helpers ─────────────────────────────────────────────────────

    @Test
    fun `weave transitions state from Weaving to Woven`() = runTest {
        val seam = FakeSeam(initialState = SeamState.Weaving)
        seam.weave()
        assertEquals(SeamState.Woven, seam.state.value)
    }

    @Test
    fun `tear transitions state to Torn with given reason`() = runTest {
        val seam = FakeSeam()
        seam.tear(CloseReason.RemoteRequested)
        assertEquals(SeamState.Torn(CloseReason.RemoteRequested), seam.state.value)
    }

    @Test
    fun `tear defaults to Normal reason`() = runTest {
        val seam = FakeSeam()
        seam.tear()
        assertEquals(SeamState.Torn(CloseReason.Normal), seam.state.value)
    }

    @Test
    fun `close transitions state to Torn Normal and is idempotent`() = runTest {
        val seam = FakeSeam()
        seam.close()
        assertEquals(SeamState.Torn(CloseReason.Normal), seam.state.value)
        seam.close() // must not throw
    }

    @Test
    fun `close with explicit reason sets that reason`() = runTest {
        val seam = FakeSeam()
        seam.close(CloseReason.RemoteRequested)
        assertEquals(SeamState.Torn(CloseReason.RemoteRequested), seam.state.value)
    }

    // ── deliver → incoming ────────────────────────────────────────────────────

    @Test
    fun `deliver pushes swatch into incoming`() = runTest {
        val seam = FakeSeam()
        val swatch = Swatch(payload = byteArrayOf(1, 2, 3), sender = PeerId("bob"), sequence = 1L)
        val received = async { seam.incoming.first() }
        seam.deliver(swatch)
        assertEquals(swatch, received.await())
    }

    @Test
    fun `deliver convenience stamps sender and monotonically increasing sequence`() = runTest {
        val bob = PeerId("bob")
        val seam = FakeSeam()
        val received = async { seam.incoming.take(2).toList() }
        seam.deliver(bob, byteArrayOf(10))
        seam.deliver(bob, byteArrayOf(20))
        val frames = received.await()
        assertEquals(bob, frames[0].sender)
        assertEquals(bob, frames[1].sender)
        assertTrue(frames[0].sequence < frames[1].sequence)
        assertTrue(frames[0].payload.contentEquals(byteArrayOf(10)))
        assertTrue(frames[1].payload.contentEquals(byteArrayOf(20)))
    }

    @Test
    fun `frames delivered before collector subscribes are buffered not lost`() = runTest {
        val seam = FakeSeam()
        seam.deliver(PeerId("bob"), byteArrayOf(42))
        // collect AFTER delivery
        val received = seam.incoming.first()
        assertTrue(received.payload.contentEquals(byteArrayOf(42)))
    }

    @Test
    fun `deliver preserves in-order delivery`() = runTest {
        val bob = PeerId("bob")
        val seam = FakeSeam()
        seam.deliver(bob, byteArrayOf(1))
        seam.deliver(bob, byteArrayOf(2))
        seam.deliver(bob, byteArrayOf(3))
        val frames = seam.incoming.take(3).toList()
        assertContentEquals(byteArrayOf(1), frames[0].payload)
        assertContentEquals(byteArrayOf(2), frames[1].payload)
        assertContentEquals(byteArrayOf(3), frames[2].payload)
    }

    // ── broadcast send semantics ──────────────────────────────────────────────

    @Test
    fun `broadcast while Torn throws IllegalStateException`() = runTest {
        val seam = FakeSeam()
        seam.close()
        assertFailsWith<IllegalStateException> { seam.broadcast(byteArrayOf(1)) }
    }

    @Test
    fun `broadcast while Weaving with no other peers is a no-op that is recorded`() = runTest {
        val seam = FakeSeam(initialState = SeamState.Weaving)
        seam.broadcast(byteArrayOf(99))
        assertEquals(1, seam.broadcasts.size)
        assertContentEquals(byteArrayOf(99), seam.broadcasts[0])
    }

    @Test
    fun `broadcast while Woven with no other peers is recorded`() = runTest {
        val seam = FakeSeam() // Woven, single peer
        seam.broadcast(byteArrayOf(7))
        assertEquals(1, seam.broadcasts.size)
        assertContentEquals(byteArrayOf(7), seam.broadcasts[0])
    }

    @Test
    fun `multiple broadcasts are all recorded in order`() = runTest {
        val seam = FakeSeam()
        seam.broadcast(byteArrayOf(1))
        seam.broadcast(byteArrayOf(2))
        assertEquals(2, seam.broadcasts.size)
        assertContentEquals(byteArrayOf(1), seam.broadcasts[0])
        assertContentEquals(byteArrayOf(2), seam.broadcasts[1])
    }

    // ── sendTo send semantics ─────────────────────────────────────────────────

    @Test
    fun `sendTo absent peer throws PeerNotConnected`() = runTest {
        val seam = FakeSeam()
        assertFailsWith<PeerNotConnected> { seam.sendTo(PeerId("ghost"), byteArrayOf(1)) }
    }

    @Test
    fun `sendTo while Torn throws IllegalStateException`() = runTest {
        val bob = PeerId("bob")
        val seam = FakeSeam(initialPeers = setOf(PeerId("self"), bob))
        seam.close()
        assertFailsWith<IllegalStateException> { seam.sendTo(bob, byteArrayOf(1)) }
    }

    @Test
    fun `sendTo a known peer is recorded`() = runTest {
        val bob = PeerId("bob")
        val seam = FakeSeam(initialPeers = setOf(PeerId("self"), bob))
        seam.sendTo(bob, byteArrayOf(55))
        assertEquals(1, seam.directed.size)
        assertEquals(bob, seam.directed[0].first)
        assertContentEquals(byteArrayOf(55), seam.directed[0].second)
    }

    @Test
    fun `multiple sendTo calls are recorded in order`() = runTest {
        val bob = PeerId("bob")
        val carol = PeerId("carol")
        val seam = FakeSeam(initialPeers = setOf(PeerId("self"), bob, carol))
        seam.sendTo(bob, byteArrayOf(1))
        seam.sendTo(carol, byteArrayOf(2))
        assertEquals(2, seam.directed.size)
        assertEquals(bob, seam.directed[0].first)
        assertEquals(carol, seam.directed[1].first)
    }
}
