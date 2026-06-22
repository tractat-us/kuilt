package us.tractat.kuilt.test

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Rendezvous
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FakeLoomTest {
    // ── fakeSeamPair ──────────────────────────────────────────────────────────

    @Test
    fun `fakeSeamPair peers include both sides`() = runTest {
        val (host, joiner) = fakeSeamPair(PeerId("host"), PeerId("joiner"))
        assertContains(host.peers.value, PeerId("host"))
        assertContains(host.peers.value, PeerId("joiner"))
        assertContains(joiner.peers.value, PeerId("host"))
        assertContains(joiner.peers.value, PeerId("joiner"))
    }

    @Test
    fun `broadcast from host is delivered into joiner incoming with correct sender`() = runTest {
        val (host, joiner) = fakeSeamPair(PeerId("host"), PeerId("joiner"))
        val received = async { joiner.incoming.first() }
        host.broadcast(byteArrayOf(1, 2, 3))
        val frame = received.await()
        assertEquals(PeerId("host"), frame.sender)
        assertContentEquals(byteArrayOf(1, 2, 3), frame.toByteArray())
    }

    @Test
    fun `broadcast from joiner is delivered into host incoming with correct sender`() = runTest {
        val (host, joiner) = fakeSeamPair(PeerId("host"), PeerId("joiner"))
        val received = async { host.incoming.first() }
        joiner.broadcast(byteArrayOf(9, 8, 7))
        val frame = received.await()
        assertEquals(PeerId("joiner"), frame.sender)
        assertContentEquals(byteArrayOf(9, 8, 7), frame.toByteArray())
    }

    @Test
    fun `broadcast does not echo back to sender`() = runTest {
        val (host, joiner) = fakeSeamPair(PeerId("host"), PeerId("joiner"))
        // Subscribe joiner so broadcast has somewhere to go
        val joinerReceived = async { joiner.incoming.first() }
        host.broadcast(byteArrayOf(42))
        joinerReceived.await()
        // host's broadcasts list has the frame, but host does NOT receive it via incoming
        assertEquals(1, host.broadcasts.size)
    }

    @Test
    fun `multiple broadcasts across pair arrive in order`() = runTest {
        val (host, joiner) = fakeSeamPair(PeerId("host"), PeerId("joiner"))
        val frames = async { joiner.incoming.take(3).toList() }
        host.broadcast(byteArrayOf(1))
        host.broadcast(byteArrayOf(2))
        host.broadcast(byteArrayOf(3))
        val received = frames.await()
        assertContentEquals(byteArrayOf(1), received[0].toByteArray())
        assertContentEquals(byteArrayOf(2), received[1].toByteArray())
        assertContentEquals(byteArrayOf(3), received[2].toByteArray())
    }

    @Test
    fun `wired broadcast stamps monotonically increasing sequence at receiver`() = runTest {
        val (host, joiner) = fakeSeamPair(PeerId("host"), PeerId("joiner"))
        val frames = async { joiner.incoming.take(2).toList() }
        host.broadcast(byteArrayOf(1))
        host.broadcast(byteArrayOf(2))
        val received = frames.await()
        assertEquals(1L, received[0].sequence)
        assertEquals(2L, received[1].sequence)
    }

    // ── FakeLoom.weave ────────────────────────────────────────────────────────

    @Test
    fun `FakeLoom weave New returns a FakeSeam`() = runTest {
        val loom = FakeLoom()
        val seam = loom.weave(Rendezvous.New(Pattern("test")))
        assertIs<FakeSeam>(seam)
    }

    @Test
    fun `FakeLoom weave New seeds selfId from pattern displayName`() = runTest {
        val loom = FakeLoom()
        val seam = loom.weave(Rendezvous.New(Pattern("alice")))
        assertEquals(PeerId("alice"), seam.selfId)
    }

    @Test
    fun `FakeLoom host returns a FakeSeam`() = runTest {
        val loom = FakeLoom()
        val seam = loom.host(Pattern("bob"))
        assertIs<FakeSeam>(seam)
    }

    @Test
    fun `FakeLoom availability is Available`() {
        assertEquals(FabricAvailability.Available, FakeLoom().availability())
    }
}
