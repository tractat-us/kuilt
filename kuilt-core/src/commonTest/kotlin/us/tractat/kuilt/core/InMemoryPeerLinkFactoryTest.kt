package us.tractat.kuilt.core

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class InMemoryPeerLinkFactoryTest {
    // ── Construction & membership ────────────────────────────────────────────

    @Test
    fun `open returns a link whose peers contains only selfId`() =
        runTest {
            val factory = InMemoryPeerLinkFactory()
            val link = factory.open(SessionConfig("Alice"))
            assertEquals(setOf(link.selfId), link.peers.value)
        }

    @Test
    fun `join after open causes both peers to appear in each other's peer set`() =
        runTest {
            val factory = InMemoryPeerLinkFactory()
            val host = factory.open(SessionConfig("Alice"))
            val joiner = factory.join(InMemoryPeerAdvertisement("Bob"))

            assertEquals(setOf(host.selfId, joiner.selfId), host.peers.value)
            assertEquals(setOf(host.selfId, joiner.selfId), joiner.peers.value)
        }

    @Test
    fun `third join updates all three peers to contain three ids`() =
        runTest {
            val factory = InMemoryPeerLinkFactory()
            val a = factory.open(SessionConfig("Alice"))
            val b = factory.join(InMemoryPeerAdvertisement("Bob"))
            val c = factory.join(InMemoryPeerAdvertisement("Charlie"))

            val expected = setOf(a.selfId, b.selfId, c.selfId)
            assertEquals(expected, a.peers.value)
            assertEquals(expected, b.peers.value)
            assertEquals(expected, c.peers.value)
        }

    @Test
    fun `close removes the closing peer from every other peer's peers set`() =
        runTest {
            val factory = InMemoryPeerLinkFactory()
            val a = factory.open(SessionConfig("Alice"))
            val b = factory.join(InMemoryPeerAdvertisement("Bob"))
            val c = factory.join(InMemoryPeerAdvertisement("Charlie"))

            b.close()

            val expected = setOf(a.selfId, c.selfId)
            assertEquals(expected, a.peers.value)
            assertEquals(expected, c.peers.value)
        }

    @Test
    fun `close is idempotent — calling twice does not throw`() =
        runTest {
            val factory = InMemoryPeerLinkFactory()
            val link = factory.open(SessionConfig("Alice"))

            link.close()
            link.close() // must not throw
        }

    @Test
    fun `close only removes the peer once from the peer set`() =
        runTest {
            val factory = InMemoryPeerLinkFactory()
            val a = factory.open(SessionConfig("Alice"))
            val b = factory.join(InMemoryPeerAdvertisement("Bob"))

            b.close()
            b.close()

            assertEquals(setOf(a.selfId), a.peers.value)
        }

    @Test
    fun `selfId is unique across peers from the same factory`() =
        runTest {
            val factory = InMemoryPeerLinkFactory()
            val a = factory.open(SessionConfig("Alice"))
            val b = factory.join(InMemoryPeerAdvertisement("Bob"))
            val c = factory.join(InMemoryPeerAdvertisement("Charlie"))

            assertNotEquals(a.selfId, b.selfId)
            assertNotEquals(a.selfId, c.selfId)
            assertNotEquals(b.selfId, c.selfId)
        }

    // ── Broadcast ────────────────────────────────────────────────────────────

    @Test
    fun `broadcast from A causes B to receive the frame`() =
        runTest {
            val factory = InMemoryPeerLinkFactory()
            val a = factory.open(SessionConfig("Alice"))
            val b = factory.join(InMemoryPeerAdvertisement("Bob"))

            val receivedByB = async { b.incoming.first() }

            a.broadcast(byteArrayOf(1, 2, 3))

            val frame = receivedByB.await()
            assertEquals(OpaqueFrame(byteArrayOf(1, 2, 3), sender = a.selfId, sequence = 1L), frame)
        }

    @Test
    fun `broadcast from A reaches both B and C`() =
        runTest {
            val factory = InMemoryPeerLinkFactory()
            val a = factory.open(SessionConfig("Alice"))
            val b = factory.join(InMemoryPeerAdvertisement("Bob"))
            val c = factory.join(InMemoryPeerAdvertisement("Charlie"))

            val receivedByB = async { b.incoming.first() }
            val receivedByC = async { c.incoming.first() }

            a.broadcast(byteArrayOf(99))

            val frameB = receivedByB.await()
            val frameC = receivedByC.await()

            assertEquals(a.selfId, frameB.sender)
            assertEquals(a.selfId, frameC.sender)
            assertTrue(frameB.payload.contentEquals(byteArrayOf(99)))
            assertTrue(frameC.payload.contentEquals(byteArrayOf(99)))
        }

    @Test
    fun `broadcast does not echo to the sender`() =
        runTest {
            val factory = InMemoryPeerLinkFactory()
            val a = factory.open(SessionConfig("Alice"))
            val b = factory.join(InMemoryPeerAdvertisement("Bob"))

            // Buffer A's incoming into a channel so we can inspect it without blocking.
            val aIncoming = a.incoming.produceIn(this)

            // Subscribe on B so the broadcast has somewhere to go.
            val receivedByB = async { b.incoming.first() }

            a.broadcast(byteArrayOf(7))
            receivedByB.await()

            // After the broadcast is fully dispatched (B received it), A's channel
            // must be empty — the sender must not receive its own broadcast.
            assertTrue(aIncoming.tryReceive().isFailure, "A should not receive its own broadcast")

            aIncoming.cancel()
        }

    @Test
    fun `OpaqueFrame sender field on received broadcast equals sender TransportPeerId`() =
        runTest {
            val factory = InMemoryPeerLinkFactory()
            val a = factory.open(SessionConfig("Alice"))
            val b = factory.join(InMemoryPeerAdvertisement("Bob"))

            val deferred = async { b.incoming.first() }
            a.broadcast(byteArrayOf(0))
            val frame = deferred.await()

            assertEquals(a.selfId, frame.sender)
        }

    @Test
    fun `two broadcasts from A arrive at B in order`() =
        runTest {
            val factory = InMemoryPeerLinkFactory()
            val a = factory.open(SessionConfig("Alice"))
            val b = factory.join(InMemoryPeerAdvertisement("Bob"))

            val frames = async { b.incoming.take(2).toList() }

            a.broadcast(byteArrayOf(1))
            a.broadcast(byteArrayOf(2))

            val received = frames.await()
            assertTrue(received[0].payload.contentEquals(byteArrayOf(1)))
            assertTrue(received[1].payload.contentEquals(byteArrayOf(2)))
        }

    // ── SendTo ───────────────────────────────────────────────────────────────

    @Test
    fun `sendTo B from A causes B to receive and C nothing`() =
        runTest {
            val factory = InMemoryPeerLinkFactory()
            val a = factory.open(SessionConfig("Alice"))
            val b = factory.join(InMemoryPeerAdvertisement("Bob"))
            val c = factory.join(InMemoryPeerAdvertisement("Charlie"))

            val receivedByB = async { b.incoming.first() }

            a.sendTo(b.selfId, byteArrayOf(42))

            val frame = receivedByB.await()
            assertEquals(a.selfId, frame.sender)
            assertTrue(frame.payload.contentEquals(byteArrayOf(42)))

            // C should have received nothing
            var cReceived = false
            val cJob =
                launch {
                    c.incoming.first()
                    cReceived = true
                }
            cJob.cancel()
            assertFalse(cReceived)
        }

    @Test
    fun `sendTo self throws IllegalArgumentException`() =
        runTest {
            val factory = InMemoryPeerLinkFactory()
            val a = factory.open(SessionConfig("Alice"))

            assertFailsWith<IllegalArgumentException> {
                a.sendTo(a.selfId, byteArrayOf(1))
            }
        }

    @Test
    fun `sendTo a closed peer is silently dropped`() =
        runTest {
            val factory = InMemoryPeerLinkFactory()
            val a = factory.open(SessionConfig("Alice"))
            val b = factory.join(InMemoryPeerAdvertisement("Bob"))

            b.close()

            // Should not throw — dropped like a UDP packet
            a.sendTo(b.selfId, byteArrayOf(1))
        }

    @Test
    fun `two sendTo calls from A to B arrive in order`() =
        runTest {
            val factory = InMemoryPeerLinkFactory()
            val a = factory.open(SessionConfig("Alice"))
            val b = factory.join(InMemoryPeerAdvertisement("Bob"))

            val frames = async { b.incoming.take(2).toList() }

            a.sendTo(b.selfId, byteArrayOf(10))
            a.sendTo(b.selfId, byteArrayOf(20))

            val received = frames.await()
            assertTrue(received[0].payload.contentEquals(byteArrayOf(10)))
            assertTrue(received[1].payload.contentEquals(byteArrayOf(20)))
        }

    // ── Sequence stamping ────────────────────────────────────────────────────

    @Test
    fun `sequence on received frames is monotonically increasing starting from 1`() =
        runTest {
            val factory = InMemoryPeerLinkFactory()
            val a = factory.open(SessionConfig("Alice"))
            val b = factory.join(InMemoryPeerAdvertisement("Bob"))

            val frames = async { b.incoming.take(3).toList() }

            a.broadcast(byteArrayOf(1))
            a.broadcast(byteArrayOf(2))
            a.broadcast(byteArrayOf(3))

            val received = frames.await()
            assertEquals(1L, received[0].sequence)
            assertEquals(2L, received[1].sequence)
            assertEquals(3L, received[2].sequence)
        }

    @Test
    fun `sequence numbers are receiver-local — A and B have independent counters`() =
        runTest {
            val factory = InMemoryPeerLinkFactory()
            val a = factory.open(SessionConfig("Alice"))
            val b = factory.join(InMemoryPeerAdvertisement("Bob"))
            val c = factory.join(InMemoryPeerAdvertisement("Charlie"))

            // Send 2 frames to A and 1 frame to B from C
            val framesA = async { a.incoming.take(2).toList() }
            val framesB = async { b.incoming.take(1).toList() }

            c.broadcast(byteArrayOf(1))
            c.broadcast(byteArrayOf(2))

            val receivedByA = framesA.await()
            val receivedByB = framesB.await()

            // A's counter: 1, 2
            assertEquals(1L, receivedByA[0].sequence)
            assertEquals(2L, receivedByA[1].sequence)

            // B's counter starts at 1 independently
            assertEquals(1L, receivedByB[0].sequence)
        }

    @Test
    fun `sequence increments across mixed broadcast and sendTo calls at receiver`() =
        runTest {
            val factory = InMemoryPeerLinkFactory()
            val a = factory.open(SessionConfig("Alice"))
            val b = factory.join(InMemoryPeerAdvertisement("Bob"))

            val frames = async { b.incoming.take(2).toList() }

            a.broadcast(byteArrayOf(1)) // B seq = 1
            a.sendTo(b.selfId, byteArrayOf(2)) // B seq = 2

            val received = frames.await()
            assertEquals(1L, received[0].sequence)
            assertEquals(2L, received[1].sequence)
        }

    // ── Close semantics ──────────────────────────────────────────────────────

    @Test
    fun `sending from a closed link throws IllegalStateException`() =
        runTest {
            val factory = InMemoryPeerLinkFactory()
            val a = factory.open(SessionConfig("Alice"))
            val b = factory.join(InMemoryPeerAdvertisement("Bob"))

            a.close()

            assertFailsWith<IllegalStateException> {
                a.broadcast(byteArrayOf(1))
            }
        }

    @Test
    fun `sendTo from a closed link throws IllegalStateException`() =
        runTest {
            val factory = InMemoryPeerLinkFactory()
            val a = factory.open(SessionConfig("Alice"))
            val b = factory.join(InMemoryPeerAdvertisement("Bob"))

            a.close()

            assertFailsWith<IllegalStateException> {
                a.sendTo(b.selfId, byteArrayOf(1))
            }
        }

    @Test
    fun `closed peer is removed from peers set atomically`() =
        runTest {
            val factory = InMemoryPeerLinkFactory()
            val a = factory.open(SessionConfig("Alice"))
            val b = factory.join(InMemoryPeerAdvertisement("Bob"))

            b.close()

            assertFalse(b.selfId in a.peers.value)
            assertFalse(b.selfId in b.peers.value)
        }

    // ── Concurrency ──────────────────────────────────────────────────────────

    @Test
    fun `concurrent broadcasts from multiple peers all arrive at all receivers`() =
        runTest {
            val factory = InMemoryPeerLinkFactory()
            val a = factory.open(SessionConfig("Alice"))
            val b = factory.join(InMemoryPeerAdvertisement("Bob"))
            val c = factory.join(InMemoryPeerAdvertisement("Charlie"))

            val messagesPerSender = 10
            // Each of A, B, C broadcasts 10 frames; each other peer receives 20.
            val receivedByA = async { a.incoming.take(messagesPerSender * 2).toList() }
            val receivedByB = async { b.incoming.take(messagesPerSender * 2).toList() }
            val receivedByC = async { c.incoming.take(messagesPerSender * 2).toList() }

            val jobA = launch { repeat(messagesPerSender) { a.broadcast(byteArrayOf(it.toByte())) } }
            val jobB = launch { repeat(messagesPerSender) { b.broadcast(byteArrayOf(it.toByte())) } }
            val jobC = launch { repeat(messagesPerSender) { c.broadcast(byteArrayOf(it.toByte())) } }

            jobA.join()
            jobB.join()
            jobC.join()

            val framesA = receivedByA.await()
            val framesB = receivedByB.await()
            val framesC = receivedByC.await()

            assertEquals(messagesPerSender * 2, framesA.size)
            assertEquals(messagesPerSender * 2, framesB.size)
            assertEquals(messagesPerSender * 2, framesC.size)
        }
}
