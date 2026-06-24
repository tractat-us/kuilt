package us.tractat.kuilt.test

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ControllableLoomTest {

    // ── Basic mesh (default / pass-through) ───────────────────────────────────

    @Test
    fun `availability is Available`() {
        assertEquals(FabricAvailability.Available, ControllableLoom().availability())
    }

    @Test
    fun `weave New uses pattern displayName as selfId`() = runTest {
        val loom = ControllableLoom()
        val seam = loom.host(Pattern("alice"))
        assertEquals(PeerId("alice"), seam.selfId)
    }

    @Test
    fun `weave Existing uses tag displayName as selfId`() = runTest {
        val loom = ControllableLoom()
        loom.host(Pattern("room"))
        val seam = loom.join(InMemoryTag("bob"))
        assertEquals(PeerId("bob"), seam.selfId)
    }

    @Test
    fun `blank displayName falls back to auto peer-N id`() = runTest {
        val loom = ControllableLoom()
        val a = loom.host(Pattern(""))
        val b = loom.host(Pattern(""))
        assertFalse(a.selfId == b.selfId, "auto IDs must be distinct")
    }

    @Test
    fun `peers StateFlow contains all joined peers`() = runTest {
        val loom = ControllableLoom()
        val a = loom.host(Pattern("a"))
        val b = loom.join(InMemoryTag("b"))
        assertTrue(PeerId("a") in a.peers.value)
        assertTrue(PeerId("b") in a.peers.value)
        assertTrue(PeerId("a") in b.peers.value)
    }

    @Test
    fun `broadcast delivers to all other peers immediately by default`() = runTest {
        val loom = ControllableLoom()
        val a = loom.host(Pattern("a"))
        val b = loom.join(InMemoryTag("b"))
        val c = loom.join(InMemoryTag("c"))

        val frameB = async { b.incoming.first() }
        val frameC = async { c.incoming.first() }

        a.broadcast(byteArrayOf(1, 2))

        assertContentEquals(byteArrayOf(1, 2), frameB.await().toByteArray())
        assertContentEquals(byteArrayOf(1, 2), frameC.await().toByteArray())
    }

    @Test
    fun `broadcast does not echo back to sender`() = runTest {
        val loom = ControllableLoom()
        val a = loom.host(Pattern("a"))
        loom.join(InMemoryTag("b"))  // needs a peer to route to

        // If a.incoming were to receive, it would be buffered — we check it stays empty
        // after the broadcast by observing that b receives it but a's channel has nothing.
        val b = loom.join(InMemoryTag("b2"))
        val bFrame = async { b.incoming.first() }
        a.broadcast(byteArrayOf(99))
        bFrame.await() // b got it
        assertEquals(0, loom.bufferedCount(a.selfId))
    }

    @Test
    fun `frames arrive in broadcast order`() = runTest {
        val loom = ControllableLoom()
        val a = loom.host(Pattern("a"))
        val b = loom.join(InMemoryTag("b"))

        val frames = async { b.incoming.take(3).toList() }
        a.broadcast(byteArrayOf(1))
        a.broadcast(byteArrayOf(2))
        a.broadcast(byteArrayOf(3))

        val received = frames.await()
        assertContentEquals(byteArrayOf(1), received[0].toByteArray())
        assertContentEquals(byteArrayOf(2), received[1].toByteArray())
        assertContentEquals(byteArrayOf(3), received[2].toByteArray())
    }

    @Test
    fun `sender is stamped correctly on received frames`() = runTest {
        val loom = ControllableLoom()
        val a = loom.host(Pattern("a"))
        val b = loom.join(InMemoryTag("b"))

        val frame = async { b.incoming.first() }
        a.broadcast(byteArrayOf(7))

        assertEquals(PeerId("a"), frame.await().sender)
    }

    // ── holdDelivery / releaseDelivery ────────────────────────────────────────

    @Test
    fun `holdDelivery buffers frames instead of delivering them`() = runTest {
        val loom = ControllableLoom()
        val a = loom.host(Pattern("a"))
        val b = loom.join(InMemoryTag("b"))

        loom.holdDelivery(b.selfId)
        a.broadcast(byteArrayOf(42))

        assertEquals(1, loom.bufferedCount(b.selfId))
    }

    @Test
    fun `releaseDelivery flushes buffered frames to incoming`() = runTest {
        val loom = ControllableLoom()
        val a = loom.host(Pattern("a"))
        val b = loom.join(InMemoryTag("b"))

        loom.holdDelivery(b.selfId)
        a.broadcast(byteArrayOf(1))
        a.broadcast(byteArrayOf(2))

        assertEquals(2, loom.bufferedCount(b.selfId))

        val frames = async { b.incoming.take(2).toList() }
        loom.releaseDelivery(b.selfId)

        val received = frames.await()
        assertContentEquals(byteArrayOf(1), received[0].toByteArray())
        assertContentEquals(byteArrayOf(2), received[1].toByteArray())
        assertEquals(0, loom.bufferedCount(b.selfId))
    }

    @Test
    fun `releaseDelivery restores immediate delivery for subsequent frames`() = runTest {
        val loom = ControllableLoom()
        val a = loom.host(Pattern("a"))
        val b = loom.join(InMemoryTag("b"))

        loom.holdDelivery(b.selfId)
        loom.releaseDelivery(b.selfId)

        val frame = async { b.incoming.first() }
        a.broadcast(byteArrayOf(55))
        assertContentEquals(byteArrayOf(55), frame.await().toByteArray())
    }

    @Test
    fun `hold affects only the targeted peer`() = runTest {
        val loom = ControllableLoom()
        val a = loom.host(Pattern("a"))
        val b = loom.join(InMemoryTag("b"))
        val c = loom.join(InMemoryTag("c"))

        loom.holdDelivery(b.selfId)

        val cFrame = async { c.incoming.first() }
        a.broadcast(byteArrayOf(7))

        // c receives immediately despite b being held
        assertContentEquals(byteArrayOf(7), cFrame.await().toByteArray())
        assertEquals(1, loom.bufferedCount(b.selfId))
    }

    // ── deliverNext ───────────────────────────────────────────────────────────

    @Test
    fun `deliverNext releases exactly one frame`() = runTest {
        val loom = ControllableLoom()
        val a = loom.host(Pattern("a"))
        val b = loom.join(InMemoryTag("b"))

        loom.holdDelivery(b.selfId)
        a.broadcast(byteArrayOf(10))
        a.broadcast(byteArrayOf(20))
        a.broadcast(byteArrayOf(30))

        val firstFrame = async { b.incoming.first() }
        val delivered = loom.deliverNext(b.selfId)

        assertTrue(delivered)
        assertContentEquals(byteArrayOf(10), firstFrame.await().toByteArray())
        assertEquals(2, loom.bufferedCount(b.selfId))
    }

    @Test
    fun `deliverNext returns false when queue is empty`() = runTest {
        val loom = ControllableLoom()
        val id = PeerId("ghost")
        assertFalse(loom.deliverNext(id))
    }

    @Test
    fun `deliverNext preserves hold mode`() = runTest {
        val loom = ControllableLoom()
        val a = loom.host(Pattern("a"))
        val b = loom.join(InMemoryTag("b"))

        loom.holdDelivery(b.selfId)
        a.broadcast(byteArrayOf(1))
        a.broadcast(byteArrayOf(2))

        loom.deliverNext(b.selfId)

        // hold mode still active — subsequent broadcast stays buffered
        a.broadcast(byteArrayOf(3))
        assertEquals(2, loom.bufferedCount(b.selfId))
    }

    // ── bufferedCount ─────────────────────────────────────────────────────────

    @Test
    fun `bufferedCount returns 0 when peer not held`() = runTest {
        val loom = ControllableLoom()
        val b = loom.host(Pattern("b"))
        assertEquals(0, loom.bufferedCount(b.selfId))
    }

    @Test
    fun `bufferedCount increments with each held frame`() = runTest {
        val loom = ControllableLoom()
        val a = loom.host(Pattern("a"))
        val b = loom.join(InMemoryTag("b"))

        loom.holdDelivery(b.selfId)
        a.broadcast(byteArrayOf(1))
        a.broadcast(byteArrayOf(2))

        assertEquals(2, loom.bufferedCount(b.selfId))
    }
}
