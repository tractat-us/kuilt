@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.core.fabric

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.test.fabric.connectionPair
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression for the send-failure eviction race (#1452).
 *
 * `removePeer(remoteId, conn = null)` carries a conn-guard so a stale link's teardown can't evict a
 * *replacement* link to the same peer. The `readLoop → finally` path already passes the conn. This
 * test pins the send-failure path (`broadcast`/`sendTo` `.onFailure { removePeer(...) }`) to also
 * pass the conn.
 *
 * **The race, made deterministic:** a peer's live link `connA` is snapshotted under the lock and a
 * `broadcast` send goes out to it *outside* the lock, where the send parks. While it is parked a
 * reconnect installs a fresh replacement link `connB` for the SAME peer (via `addLink`, winning the
 * canonical-nonce dedup so it displaces `connA`). The parked send on the now-stale `connA` then
 * throws. Without the conn-guard, `broadcast`'s `onFailure` calls `removePeer(peer)` with no conn,
 * skips the guard, and evicts the healthy `connB` — a silent asymmetric half-edge (peer dropped from
 * the roster while its readLoop keeps delivering). With the conn-guard the eviction is scoped to the
 * stale `connA` and is a no-op, so the fresh link survives.
 *
 * Deterministic interleave: `StandardTestDispatcher`, a `CompletableDeferred` send-park signal, and a
 * far-end nonce of all-zeros for `connB` so it is the guaranteed dedup survivor (its canonical link
 * nonce has the minimal `"00…0"` low half). No `advanceUntilIdle()` — the mesh readLoops re-arm.
 */
class MeshSeamStaleConnEvictionTest {

    @Test
    fun staleSendFailureDoesNotEvictReplacementLink() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val self = PeerId("peer-0")
        val peer = PeerId("peer-1")

        // connA: real Connection for the handshake (send #1), then send #2 parks on a signal and throws.
        val sendParked = CompletableDeferred<Unit>()
        val releaseSend = CompletableDeferred<Unit>()
        val (mineA, theirsA) = connectionPair()
        val connA = ParkThenThrowConnection(mineA, sendParked, releaseSend)

        // Build the hub with connA as peer-1's only link. Far-end nonce 0xFF so connB (nonce 0x00)
        // deterministically wins the dedup tiebreak later.
        val meshDeferred = async { hubMesh(self, listOf(connA), dispatcher, Random(42)) }
        val handshakeA = async { driveFarEnd(theirsA, peer, ByteArray(NONCE_LEN) { 0xFF.toByte() }) }
        val mesh = meshDeferred.await()
        handshakeA.await()
        assertEquals(setOf(self, peer), mesh.peers.value, "precondition: hub sees self + peer over connA")

        // Broadcast snapshots connA under the lock and sends OUTSIDE it — where send #2 parks.
        val payload = byteArrayOf(1, 2, 3)
        val broadcast = launch { mesh.broadcast(payload) }
        sendParked.await() // connA.send(payload) is now parked, mid-broadcast.

        // Reconnect: a fresh connB for the SAME peer dials in and displaces connA (wins dedup with the
        // all-zeros nonce). After this, links[peer].conn == connB while the parked send still holds connA.
        val (mineB, theirsB) = connectionPair()
        val addLink = launch { mesh.addLink(mineB) }
        val handshakeB = async { driveFarEnd(theirsB, peer, ByteArray(NONCE_LEN) { 0x00 }) }
        addLink.join()
        handshakeB.await()
        assertTrue(peer in mesh.peers.value, "replacement link connB must be installed for the peer")

        // Release the parked send on the now-stale connA → it throws → broadcast's onFailure removePeer.
        releaseSend.complete(Unit)
        broadcast.join()

        // The fresh connB must survive: the guard scopes the eviction to the stale connA (a no-op).
        assertTrue(peer in mesh.peers.value, "fresh replacement link must survive a stale-conn send failure")

        // ...and it must still deliver: a frame from connB's far end reaches incoming, sender == peer.
        val delivered = async { mesh.incoming.first() }
        val liveFrame = byteArrayOf(9, 8, 7)
        theirsB.send(liveFrame)
        val swatch = delivered.await()
        assertContentEquals(liveFrame, swatch.toByteArray(), "replacement link must keep delivering frames")
        assertEquals(peer, swatch.sender, "delivered frame must be attributed to the peer")
    }

    /** Read the mesh's preamble off [theirs], then reply with [remoteId] + [nonce]. */
    private suspend fun driveFarEnd(theirs: Connection, remoteId: PeerId, nonce: ByteArray) {
        theirs.incoming.first() // the mesh's MeshHello preamble
        theirs.send(MeshHello.encode(remoteId, nonce))
    }

    /**
     * Delegates send #1 (the mesh handshake preamble) to [delegate], then on send #2 signals
     * [parked], suspends on [release], and throws — a link whose in-flight send can be pinned
     * open across a concurrent replacement, then failed on demand.
     */
    private class ParkThenThrowConnection(
        private val delegate: Connection,
        private val parked: CompletableDeferred<Unit>,
        private val release: CompletableDeferred<Unit>,
    ) : Connection {
        private var sendCount = 0

        override suspend fun send(frame: ByteArray) {
            sendCount++
            if (sendCount == 1) {
                delegate.send(frame)
                return
            }
            parked.complete(Unit)
            release.await()
            throw RuntimeException("simulated failure on stale link send #$sendCount")
        }

        override val incoming: Flow<ByteArray> get() = delegate.incoming

        override suspend fun close() = delegate.close()
    }

    private companion object {
        // Matches the mesh's NONCE_BYTES; kept local since that constant is file-private in MeshSeam.
        const val NONCE_LEN = 16
    }
}
