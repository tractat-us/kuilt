package us.tractat.kuilt.core.fabric

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The three paths on which a [LinkSeam] closes its own connection, without the application calling
 * [Seam.close] (#2898): the read loop after a read error, the writer after a failed send, and a
 * [Seam.close] that arrives while one of those closes is still in flight.
 *
 * Each runs over [GatedConnection], whose sends and close can be held open, so a path that waits on
 * the wrong thing shows up as a missing close rather than as a hang. Every wait here is a suspended
 * coroutine, never a timer, so `advanceUntilIdle` returns whether or not the seam is wedged, and the
 * assertions after it are what fail.
 */
class LinkSeamSelfCloseTest {
    private val self = PeerId("self")
    private val remote = PeerId("remote")

    private fun TestScope.seamOver(conn: GatedConnection): Seam =
        identified(conn, self, remote, StandardTestDispatcher(testScheduler))

    /**
     * A peer that sends a refused frame but has stopped reading: our writer is stuck in `send` with
     * more frames queued behind it. The refusal must close the transport at once, not after a flush
     * that can never finish, and no queued frame may reach the wire after it (`docs/tcp-wire.md` § 6).
     */
    @Test
    fun aReadErrorClosesAtOnceEvenWithTheWriterStuckAndSendsNothingAfter() = runTest {
        val conn = GatedConnection()
        val seam = seamOver(conn)
        repeat(3) { seam.broadcast(byteArrayOf(it.toByte())) }
        testScheduler.advanceUntilIdle()
        // Rig: the writer is inside `send` with the first frame, and nothing has been written.
        assertEquals(1, conn.sendAttempts, "the writer is stuck in its first send")

        val refusal = IllegalStateException("frame too large")
        conn.failRead(refusal)
        testScheduler.advanceUntilIdle()

        assertAll(
            { assertEquals(1, conn.closes, "the refusal closed the transport before Seam.close") },
            { assertEquals(0, conn.attemptsAfterReadError, "no queued frame was sent after the refusal") },
            { assertTrue(conn.written.isEmpty(), "no frame reached the wire") },
            {
                // By message, not identity: coroutines' stack-trace recovery may hand back a copy.
                val reason = (seam.state.value as? SeamState.Torn)?.reason
                assertTrue(
                    reason is CloseReason.Error && reason.throwable.message == refusal.message,
                    "tears with the refusal, got $reason",
                )
            },
        )
        seam.close()
        testScheduler.advanceUntilIdle()
        assertEquals(1, conn.closes, "Seam.close did not close a second time")
    }

    /** A send that throws tears the seam and closes the transport, while the read side is still open. */
    @Test
    fun aFailedSendClosesTheTransportOnce() = runTest {
        val conn = GatedConnection(failSends = true)
        val seam = seamOver(conn)
        seam.broadcast(byteArrayOf(1))
        testScheduler.advanceUntilIdle()

        assertAll(
            { assertEquals(1, conn.sendAttempts, "the send was attempted and failed") },
            { assertEquals(1, conn.closes, "the failed send closed the transport before Seam.close") },
            { assertIs<SeamState.Torn>(seam.state.value) },
        )
        seam.close()
        testScheduler.advanceUntilIdle()
        assertEquals(1, conn.closes, "Seam.close did not close a second time")
    }

    /**
     * The remote ends the link and the read loop's close is still in flight when the application
     * calls [Seam.close]. That call must not close a second time, and must not return until the
     * close it lost the race to has landed.
     */
    @Test
    fun aCloseThatLosesTheRaceWaitsForTheWinnersClose() = runTest {
        val conn = GatedConnection(holdClose = true)
        val seam = seamOver(conn)
        conn.endRead()
        testScheduler.advanceUntilIdle()
        // Rig: the read loop is inside `conn.close()`, held there.
        assertAll(
            { assertEquals(1, conn.closes, "the read loop entered conn.close") },
            { assertFalse(conn.closeReturned, "and is held inside it") },
        )

        val closing = launch { seam.close() }
        testScheduler.advanceUntilIdle()
        assertAll(
            { assertEquals(1, conn.closes, "Seam.close did not close a second time") },
            { assertFalse(closing.isCompleted, "Seam.close waits for the close in flight") },
        )

        conn.releaseClose()
        testScheduler.advanceUntilIdle()
        assertAll(
            { assertTrue(closing.isCompleted, "Seam.close returned once that close landed") },
            { assertEquals(1, conn.closes) },
        )
    }

    /**
     * A link whose sends block until it is closed, like a socket whose peer has stopped reading, and
     * whose close can be held open. Counts every send attempt and close call.
     */
    private class GatedConnection(
        private val failSends: Boolean = false,
        holdClose: Boolean = false,
    ) : Connection {
        private val inbound = Channel<ByteArray>(Channel.UNLIMITED)
        private val sendGate = CompletableDeferred<Unit>()
        private val closeGate = CompletableDeferred<Unit>().apply { if (!holdClose) complete(Unit) }
        private var readErrored = false

        var sendAttempts = 0
        var attemptsAfterReadError = 0
        val written = mutableListOf<ByteArray>()
        var closes = 0
        var closeReturned = false

        override val incoming: Flow<ByteArray> = inbound.receiveAsFlow()

        fun failRead(error: Throwable) {
            readErrored = true
            inbound.close(error)
        }

        fun endRead() {
            inbound.close()
        }

        fun releaseClose() {
            closeGate.complete(Unit)
        }

        override suspend fun send(frame: ByteArray) {
            sendAttempts++
            if (readErrored) attemptsAfterReadError++
            if (failSends) throw IllegalStateException("send failed")
            sendGate.await()
            written += frame
        }

        override suspend fun close() {
            closes++
            sendGate.completeExceptionally(IllegalStateException("closed"))
            inbound.close()
            closeGate.await()
            closeReturned = true
        }
    }
}
