@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.core.fabric

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.test.fabric.connectionPair
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Unit tests for [meshSeam] edge cases not covered by [us.tractat.kuilt.conformance.MeshConformanceSuite].
 */
class MeshSeamTest {

    /**
     * Regression: broadcast must NOT throw ConcurrentModificationException when one link's
     * send fails mid-iteration.
     *
     * Before the fix, `broadcast` called `removePeer` inside `links.forEach { … }`, which
     * structurally modified the map during iteration → CME on JVM whenever the failing peer
     * was not the last entry.
     *
     * Setup: 3-peer mesh (peer-0 has links to peer-1 and peer-2). The failing link is passed
     * FIRST in the conn list so its PeerId occupies the first slot in the LinkedHashMap and is
     * visited first during iteration. The good link comes second. After `broadcast`:
     *   (a) no exception thrown,
     *   (b) the good peer received the payload,
     *   (c) the failing peer was removed from the roster.
     */
    @Test
    fun broadcastDoesNotThrowWhenFirstLinkFails() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)

        val selfId = PeerId("peer-0")
        val goodId = PeerId("peer-1")
        val badId = PeerId("peer-2")

        // Bad link: wraps a connectionPair for the Hello handshake, then throws on every subsequent send.
        // Passed FIRST so badId enters the links LinkedHashMap first and is iterated first.
        val (badMine, badTheirs) = connectionPair()
        val failingConnection = ThrowsOnSendAfterFirstConnection(badMine)

        // Good link: passed SECOND so goodId is iterated after the failing link throws.
        val (goodMine, goodTheirs) = connectionPair()

        // peer-0 constructs its mesh: failingConnection first so badId is visited first in broadcast.
        val senderMeshDeferred = async {
            meshSeam(selfId, listOf(failingConnection, goodMine), dispatcher)
        }

        // Simulate peer-2 handshake (the failing conn — sends hello once, which succeeds).
        val peer2HelloDeferred = async {
            badTheirs.send(MeshHello.encode(badId, byteArrayOf(2)))
            MeshHello.decode(badTheirs.incoming.first())
        }

        // Simulate peer-1 handshake (the good conn).
        val peer1HelloDeferred = async {
            goodTheirs.send(MeshHello.encode(goodId, byteArrayOf(1)))
            MeshHello.decode(goodTheirs.incoming.first())
        }

        val senderMesh = senderMeshDeferred.await()
        peer1HelloDeferred.await()
        peer2HelloDeferred.await()

        // Sanity: peer-0 must see all 3 peers (self + peer-1 + peer-2) after handshakes.
        assertEquals(setOf(selfId, goodId, badId), senderMesh.peers.value)

        // This call must NOT throw — specifically must not throw ConcurrentModificationException
        // when removePeer(badId) is called during iteration of the same links map.
        val payload = byteArrayOf(1, 2, 3)
        senderMesh.broadcast(payload)

        // (b) The good link must have delivered the payload despite the failing link being first.
        val received = goodTheirs.incoming.first()
        assertContentEquals(payload, received, "surviving peer must receive the broadcast payload")

        // (c) The failing peer must be removed from the roster.
        assertFalse(badId in senderMesh.peers.value, "failing peer must be removed after broadcast failure")
        assertEquals(setOf(selfId, goodId), senderMesh.peers.value)
    }

    /**
     * #419 — cross-node dedup AGREEMENT on a genuine simultaneous dial.
     *
     * A and B dial each other at the same time, producing TWO physical links (X and Y) between
     * the same pair. Each node independently runs dedup. The hazard the old self-relative
     * `selfId < remoteId` rule created: A and B can pick DIFFERENT survivors (A keeps X, B keeps
     * Y), leaving both links half-open and the rosters inconsistent.
     *
     * This test wires both links to both meshes and asserts the survivors agree: the link A keeps
     * to B is the exact same physical link B keeps to A. We detect "same link" by sending a
     * round-trip frame over A's surviving conn and confirming B's surviving conn receives it —
     * if they disagreed, the byte would vanish into a closed/abandoned link and the receive would
     * never complete (or land on the wrong, closed conn).
     *
     * Driven with a SEEDED [Random] on each side so the per-link nonce that breaks the tie is
     * deterministic — the canonical survivor is a pure function of the two nonces, identical on
     * both ends.
     */
    @Test
    fun simultaneousDialDedupAgreesCrossNode() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val a = PeerId("peer-a")
        val b = PeerId("peer-b")

        // Two independent physical links between the SAME pair (the simultaneous-dial race).
        val (aX, bX) = connectionPair()
        val (aY, bY) = connectionPair()

        // Each node feeds BOTH conns to its mesh. Seeded RNGs make the nonce draw deterministic
        // but the two sides draw DIFFERENT nonces (different seeds) — as on the wire.
        val meshADeferred = async { meshSeam(a, listOf(aX, aY), dispatcher, Random(1)) }
        val meshBDeferred = async { meshSeam(b, listOf(bX, bY), dispatcher, Random(2)) }

        val meshA = meshADeferred.await()
        val meshB = meshBDeferred.await()

        // Both rosters converged to exactly {a, b} — no half-open duplicate inflated the count.
        assertEquals(setOf(a, b), meshA.peers.value, "A must see exactly {a,b} after dedup")
        assertEquals(setOf(a, b), meshB.peers.value, "B must see exactly {a,b} after dedup")

        // The surviving links must be the two ends of the SAME physical link. Prove it with a
        // round trip: A sends to B; B must receive it on its surviving link.
        val received = async { meshB.incoming.first() }
        val payload = byteArrayOf(5, 6, 7)
        meshA.sendTo(b, payload)
        val swatch = received.await()
        assertContentEquals(payload, swatch.payload, "B must receive A's frame over the agreed link")
        assertEquals(a, swatch.sender)

        // And the reverse direction, proving the agreed link is duplex and not crossed.
        val receivedBack = async { meshA.incoming.first() }
        val reply = byteArrayOf(8, 9)
        meshB.sendTo(a, reply)
        val backSwatch = receivedBack.await()
        assertContentEquals(reply, backSwatch.payload, "A must receive B's reply over the agreed link")
        assertEquals(b, backSwatch.sender)
    }

    /**
     * #420 — a link admitted AFTER construction converges into the roster and participates in
     * broadcast and sendTo.
     *
     * peer-0 starts with a single link to peer-1, then a peer-2 dials in late and is admitted via
     * [Mesh.addLink]. After admission peer-0's roster must include peer-2, and both an existing
     * peer (peer-1) and the late joiner (peer-2) must receive peer-0's broadcast.
     */
    @Test
    fun addLinkAdmitsLateJoiner() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val self = PeerId("peer-0")
        val existing = PeerId("peer-1")
        val joiner = PeerId("peer-2")

        val (mine1, theirs1) = connectionPair()
        val mesh = async { meshSeam(self, listOf(mine1), dispatcher, Random(0)) }
        val peer1Handshake = async { handshakeRemote(theirs1, existing) }

        val seam = mesh.await()
        peer1Handshake.await()
        assertEquals(setOf(self, existing), seam.peers.value, "before join: just self + peer-1")

        // peer-2 dials in late.
        val (mine2, theirs2) = connectionPair()
        val joinDeferred = async { seam.addLink(mine2) }
        val peer2Handshake = async { handshakeRemote(theirs2, joiner) }
        joinDeferred.await()
        peer2Handshake.await()

        assertEquals(setOf(self, existing, joiner), seam.peers.value, "late joiner must join the roster")

        // Both the existing peer and the late joiner receive the broadcast.
        val onPeer1 = async { theirs1.incoming.first() }
        val onPeer2 = async { theirs2.incoming.first() }
        val payload = byteArrayOf(11, 22)
        seam.broadcast(payload)
        assertContentEquals(payload, onPeer1.await(), "existing peer must receive broadcast")
        assertContentEquals(payload, onPeer2.await(), "late joiner must receive broadcast")
    }

    /** Drive the far end of a [connectionPair] through the mesh handshake for [remoteId]. */
    private suspend fun handshakeRemote(theirs: Connection, remoteId: PeerId) {
        val helloFromMesh = theirs.incoming.first()
        val meshNonce = MeshHello.decode(helloFromMesh).nonce
        assertTrue(meshNonce.isNotEmpty(), "mesh preamble must carry a non-empty nonce")
        theirs.send(MeshHello.encode(remoteId, byteArrayOf(0)))
    }

    /**
     * A [Connection] that delegates to a real [Connection] for its first [send] call (the Hello preamble),
     * then throws [RuntimeException] on every subsequent [send].
     *
     * Lets the [meshSeam] handshake succeed while simulating a link failure during broadcast.
     */
    private class ThrowsOnSendAfterFirstConnection(private val delegate: Connection) : Connection {
        private var sendCount = 0

        override suspend fun send(frame: ByteArray) {
            sendCount++
            if (sendCount > 1) throw RuntimeException("simulated link failure on send #$sendCount")
            delegate.send(frame)
        }

        override val incoming: Flow<ByteArray> get() = delegate.incoming

        override suspend fun close() = delegate.close()
    }
}
