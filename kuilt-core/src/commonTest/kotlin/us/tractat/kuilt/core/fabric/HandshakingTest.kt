@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.core.fabric

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.fabric.connectionPair
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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
     * degenerate 2-peer seam whose "remote" is itself (which would echo its own frames) — and close
     * the link, so neither end is left holding it half-open.
     */
    @Test
    fun rejectsAConnectionWhoseRemoteIsSelfAndClosesIt() = runTest {
        val conn = ScriptedConnection(Hello.encode(PeerId("self")))
        val refused = assertFailsWith<HelloSelfConnectionException> { handshaking(conn, PeerId("self"), dispatcher()) }
        assertRefusedAndClosed(conn, PeerId("self"), refused)
    }

    /**
     * A pre-v1 peer opens with its id as bare UTF-8 (#2894). `handshaking` must refuse it by name
     * rather than weave a seam whose remote is whatever those bytes happen to decode to, and close
     * the link rather than leave the remote weaving a seam to a dead peer.
     */
    @Test
    fun refusesAPreV1PeerSendingABareUtf8IdAndClosesIt() = runTest {
        val conn = ScriptedConnection("old-peer".encodeToByteArray())
        val refused = assertFailsWith<HelloBadMagicException> { handshaking(conn, PeerId("A"), dispatcher()) }
        assertRefusedAndClosed(conn, PeerId("A"), refused)
    }

    /** A link that ends before any frame is named, not a bare `NoSuchElementException`. */
    @Test
    fun aLinkThatEndsBeforeAnyHelloIsRefusedAsAbsentAndClosed() = runTest {
        val conn = ScriptedConnection()
        val refused = assertFailsWith<HelloAbsentException> { handshaking(conn, PeerId("A"), dispatcher()) }
        assertRefusedAndClosed(conn, PeerId("A"), refused)
    }

    /** The control arm: a well-formed hello is NOT closed, so the closes above are the refusal's doing. */
    @Test
    fun anAcceptedHelloLeavesTheLinkOpen() = runTest {
        val conn = ScriptedConnection(Hello.encode(PeerId("B")), hangUp = false)
        val seam = handshaking(conn, PeerId("A"), dispatcher())
        assertAll(
            { assertEquals(setOf(PeerId("A"), PeerId("B")), seam.peers.value) },
            { assertEquals(0, conn.closes.value, "an accepted handshake must not close its link") },
        )
        seam.close()
    }

    /** A failed send of our own hello is not a format refusal, and still closes the link. */
    @Test
    fun aFailedHelloSendClosesTheLink() = runTest {
        val conn = ScriptedConnection(hangUp = false, failSend = true)
        assertFailsWith<SendFailed> { handshaking(conn, PeerId("A"), dispatcher()) }
        assertEquals(1, conn.closes.value, "a failed send must close the link")
    }

    /** Cancelled while waiting for the remote's hello: the link is closed before the cancellation propagates. */
    @Test
    fun cancellationDuringTheHandshakeClosesTheLink() = runTest {
        val conn = ScriptedConnection(hangUp = false)
        val dispatcher = dispatcher()
        val job = launch { handshaking(conn, PeerId("A"), dispatcher) }
        runCurrent()
        assertAll(
            { assertEquals(1, conn.sent.value.size, "precondition: the handshake is in flight, our hello sent") },
            { assertEquals(0, conn.closes.value, "precondition: nothing closed yet") },
        )
        job.cancelAndJoin()
        assertAll(
            { assertTrue(job.isCancelled, "the handshake ended cancelled") },
            { assertEquals(1, conn.closes.value, "cancellation must close the link") },
        )
    }

    private fun assertRefusedAndClosed(conn: ScriptedConnection, selfId: PeerId, refused: Throwable) {
        assertAll(
            { assertEquals(1, conn.closes.value, "refusal ($refused) must close the link exactly once") },
            {
                assertEquals(1, conn.sent.value.size, "only our own hello is sent; nothing follows a refusal")
                assertContentEquals(Hello.encode(selfId), conn.sent.value.single())
            },
        )
    }

    private suspend fun dispatcher() = currentCoroutineContext()[ContinuationInterceptor]!!

    /**
     * Emits [frames], then ends (a remote that sent them and hung up) or, with [hangUp] false, stays
     * open. Records sends and closes; with [failSend], every send throws [SendFailed].
     */
    private class ScriptedConnection(
        vararg frames: ByteArray,
        private val hangUp: Boolean = true,
        private val failSend: Boolean = false,
    ) : Connection {
        private val script = frames.toList()
        val sent = atomic(emptyList<ByteArray>())
        val closes = atomic(0)

        override suspend fun send(frame: ByteArray) {
            if (failSend) throw SendFailed()
            sent.update { it + frame }
        }

        override val incoming: Flow<ByteArray> = flow {
            script.forEach { emit(it) }
            if (!hangUp) awaitCancellation()
        }

        override suspend fun close() {
            closes.incrementAndGet()
        }
    }

    /** A transport failure that is deliberately not an [IllegalArgumentException]. */
    private class SendFailed : Exception("scripted send failure")
}
