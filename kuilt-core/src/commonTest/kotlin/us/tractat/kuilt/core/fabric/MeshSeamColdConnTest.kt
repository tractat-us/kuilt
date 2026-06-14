@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.core.fabric

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.PeerId
import kotlin.coroutines.ContinuationInterceptor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * [meshSeam] (and [Mesh.addLink]) must work over a *cold, single-collection* [Conn] — the
 * shape a stream fabric's `framed()` produces — without a hot-reader pump. The mesh exchanges
 * a [MeshHello] preamble (`firstFrame`) AND launches a per-link `readLoop`; that is two
 * collections of the same conn, so over a cold conn it hangs unless the mesh wraps each link
 * with [singleCollection].
 *
 * [ColdConn.incoming] is a cold flow that throws on a second collection, so a double-collect
 * (the bug) fails loudly instead of hanging. It emits the preamble then a payload then stays
 * open (the wire is live until [Seam.close]), so the read loop and roster are stable.
 */
class MeshSeamColdConnTest {

    @Test
    fun meshSeamWorksOverColdSingleCollectionConn() = runTest {
        val dispatcher = currentCoroutineContext()[ContinuationInterceptor]!!
        val remote = PeerId("B")
        val conn = ColdConn(remoteId = remote, payload = byteArrayOf(7))

        val mesh = async { meshSeam(PeerId("A"), listOf(conn), dispatcher, Random(0)) }.await()

        // The read loop and the preamble read shared ONE collection of incoming: the
        // post-preamble payload frame surfaces (a double-collect would throw "collected twice").
        val frame = mesh.incoming.first()
        assertEquals(remote, frame.sender)
        assertContentEquals(byteArrayOf(7), frame.payload)
        assertEquals(setOf(PeerId("A"), remote), mesh.peers.value, "remote must be in the roster")

        mesh.close(CloseReason.Normal)
    }

    @Test
    fun addLinkWorksOverColdSingleCollectionConn() = runTest {
        val dispatcher = currentCoroutineContext()[ContinuationInterceptor]!!
        val joiner = PeerId("C")
        val joinConn = ColdConn(remoteId = joiner, payload = byteArrayOf(9))

        // Start with an empty mesh (no initial links), then admit the cold conn late.
        val mesh = async { meshSeam(PeerId("A"), emptyList(), dispatcher, Random(0)) }.await()
        async { mesh.addLink(joinConn) }.await()

        val frame = mesh.incoming.first()
        assertEquals(joiner, frame.sender)
        assertContentEquals(byteArrayOf(9), frame.payload)
        assertEquals(setOf(PeerId("A"), joiner), mesh.peers.value, "late joiner must be in the roster")

        mesh.close(CloseReason.Normal)
    }

    /**
     * Emits the remote [MeshHello] preamble (with a non-empty nonce) then one payload frame, then
     * stays open ([awaitCancellation]) until the read loop is cancelled by [Seam.close] — so the
     * roster is stable and the link is not torn down by an end-of-stream. Rejects a second
     * collection. Mirrors `HandshakingColdConnTest.ColdConn` for the mesh wire.
     */
    private class ColdConn(remoteId: PeerId, payload: ByteArray) : Conn {
        private val frames = listOf(MeshHello.encode(remoteId, byteArrayOf(1, 2, 3, 4)), payload)
        private val collected = atomic(false)
        val sent = atomic(emptyList<ByteArray>())

        override suspend fun send(frame: ByteArray) {
            sent.update { it + frame }
        }

        override val incoming: Flow<ByteArray> = flow {
            check(collected.compareAndSet(expect = false, update = true)) {
                "incoming collected more than once"
            }
            frames.forEach { emit(it) }
            awaitCancellation()
        }

        override suspend fun close() {}
    }
}
