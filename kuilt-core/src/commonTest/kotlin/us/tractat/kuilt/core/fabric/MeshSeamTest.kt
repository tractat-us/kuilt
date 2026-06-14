@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.core.fabric

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.test.fabric.connPair
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

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

        // Bad link: wraps a connPair for the Hello handshake, then throws on every subsequent send.
        // Passed FIRST so badId enters the links LinkedHashMap first and is iterated first.
        val (badMine, badTheirs) = connPair()
        val failingConn = ThrowsOnSendAfterFirstConn(badMine)

        // Good link: passed SECOND so goodId is iterated after the failing link throws.
        val (goodMine, goodTheirs) = connPair()

        // peer-0 constructs its mesh: failingConn first so badId is visited first in broadcast.
        val senderMeshDeferred = async {
            meshSeam(selfId, listOf(failingConn, goodMine), dispatcher)
        }

        // Simulate peer-2 handshake (the failing conn — sends hello once, which succeeds).
        val peer2HelloDeferred = async {
            badTheirs.send(Hello.encode(badId))
            Hello.decode(badTheirs.incoming.first())
        }

        // Simulate peer-1 handshake (the good conn).
        val peer1HelloDeferred = async {
            goodTheirs.send(Hello.encode(goodId))
            Hello.decode(goodTheirs.incoming.first())
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
     * A [Conn] that delegates to a real [Conn] for its first [send] call (the Hello preamble),
     * then throws [RuntimeException] on every subsequent [send].
     *
     * Lets the [meshSeam] handshake succeed while simulating a link failure during broadcast.
     */
    private class ThrowsOnSendAfterFirstConn(private val delegate: Conn) : Conn {
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
