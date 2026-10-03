@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.core.fabric

import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.test.fabric.connectionPair
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HandshakingTest {
    @Test
    fun learnsRemoteIdThenCarriesPayload() = runTest {
        val (a, b) = connectionPair()
        val dispatcher = currentCoroutineContext()[ContinuationInterceptor]!!
        val seamA = async { handshaking(a, PeerId("A"), dispatcher) }
        val seamB = async { handshaking(b, PeerId("B"), dispatcher) }
        val sa = seamA.await()
        val sb = seamB.await()
        assertEquals(setOf(PeerId("A"), PeerId("B")), sa.peers.value)
        sa.broadcast(byteArrayOf(7))
        assertContentEquals(byteArrayOf(7), sb.incoming.first().toByteArray())
    }

    /**
     * Self-connection guard (#1488): when `handshaking` reads back its own [PeerId] in the peer's
     * preamble (a peer that dialed its own advertised endpoint), it must refuse rather than weave a
     * degenerate 2-peer seam whose "remote" is itself (which would echo its own frames).
     */
    @Test
    fun rejectsAConnectionWhoseRemoteIsSelf() = runTest {
        val (a, b) = connectionPair()
        val dispatcher = currentCoroutineContext()[ContinuationInterceptor]!!
        // Far end: drain our Hello, then reply with a preamble claiming the SAME id — a self-dial.
        val far = launch {
            b.incoming.first()
            b.send(Hello.encode(PeerId("self")))
        }
        assertFailsWith<IllegalArgumentException> { handshaking(a, PeerId("self"), dispatcher) }
        far.join()
    }

    /**
     * A pre-v1 peer opens with its id as bare UTF-8 (#2894). `handshaking` must refuse it by name
     * rather than weave a seam whose remote is whatever those bytes happen to decode to.
     */
    @Test
    fun refusesAPreV1PeerSendingABareUtf8Id() = runTest {
        val (a, b) = connectionPair()
        val dispatcher = currentCoroutineContext()[ContinuationInterceptor]!!
        val far = launch {
            b.incoming.first()
            b.send("old-peer".encodeToByteArray())
        }
        assertFailsWith<HelloBadMagicException> { handshaking(a, PeerId("A"), dispatcher) }
        far.join()
    }
}
