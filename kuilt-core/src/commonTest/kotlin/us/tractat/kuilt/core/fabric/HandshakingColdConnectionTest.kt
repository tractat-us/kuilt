@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.core.fabric

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * `handshaking` must work over a *cold, single-collection* [Connection] — the shape a
 * stream fabric's `framed()` produces — without a hot-reader pump. The preamble
 * read and the inner seam's read loop must share ONE collection of `incoming`.
 *
 * [ColdConnection.incoming] is a cold flow that throws on a second collection, so a
 * double-collect (the previous bug) fails loudly instead of hanging.
 */
class HandshakingColdConnectionTest {
    @Test
    fun completesAndCarriesPayloadOverColdSingleCollectionConnection() = runTest {
        val conn = ColdConnection(remoteId = PeerId("B"), payload = byteArrayOf(7))
        val dispatcher = currentCoroutineContext()[ContinuationInterceptor]!!

        val seam = async { handshaking(conn, PeerId("A"), dispatcher) }.await()

        val frame = seam.incoming.first()
        assertEquals(PeerId("B"), frame.sender)
        assertContentEquals(byteArrayOf(7), frame.payload)
        assertContentEquals(Hello.encode(PeerId("A")), conn.sent.value.single())
    }

    /** Emits the remote Hello then one payload frame; rejects a second collection. */
    private class ColdConnection(remoteId: PeerId, payload: ByteArray) : Connection {
        private val frames = listOf(Hello.encode(remoteId), payload)
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
        }

        override suspend fun close() {}
    }
}
