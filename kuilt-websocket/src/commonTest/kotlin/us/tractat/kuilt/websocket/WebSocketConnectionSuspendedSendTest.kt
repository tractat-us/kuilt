@file:OptIn(InternalAPI::class)

package us.tractat.kuilt.websocket

import io.ktor.utils.io.InternalAPI
import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketExtension
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException
import us.tractat.kuilt.test.assertAll
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The three ways a `send` can end while it is **suspended** on a full outgoing queue, which the
 * conformance suite cannot reach: its cancelled-caller arm cancels *before* calling `send`, so it
 * only pins the closed-queue path. Here the session's queue is a rendezvous channel nobody receives
 * from, so `send` really suspends, and each arm asserts that before it fires its event.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WebSocketConnectionSuspendedSendTest {

    @Test
    fun callersTimeoutExpiringDuringASuspendedSendReachesTheCallerAsATimeout() =
        runTest(StandardTestDispatcher()) {
            val session = BlockedSession()
            val connection = WebSocketConnection(session)
            val outcome = async { endOf { withTimeout(1.seconds) { connection.send(FRAME) } } }

            runCurrent()
            assertAll(
                { assertTrue(outcome.isActive, "rig: the send must be suspended before the timeout") },
                { assertEquals(0L, currentTime, "rig: no virtual time has passed yet") },
            )

            advanceTimeBy(1.seconds.inWholeMilliseconds + 1)
            runCurrent()
            val ended = outcome.await()

            assertIs<TimeoutCancellationException>(ended, "a timeout is the caller's own, not a closed link: $ended")
        }

    @Test
    fun callerCancelledDuringASuspendedSendSeesACancellationNotAnIoFailure() =
        runTest(StandardTestDispatcher()) {
            val session = BlockedSession()
            val connection = WebSocketConnection(session)
            var ended: Throwable? = null
            val sender = launch { ended = endOf { connection.send(FRAME) } }

            runCurrent()
            assertTrue(sender.isActive, "rig: the send must be suspended before the caller is cancelled")

            sender.cancel()
            runCurrent()

            assertAll(
                { assertTrue(sender.isCompleted, "the sender finished") },
                { assertTrue(ended is CancellationException, "the caller's own cancel must stay a cancellation: $ended") },
            )
        }

    @Test
    fun sessionDyingUnderASuspendedSendFailsALiveCallerWithAnIoExceptionCarryingTheCancellation() =
        runTest(StandardTestDispatcher()) {
            val session = BlockedSession()
            val connection = WebSocketConnection(session)
            val outcome = async { endOf { connection.send(FRAME) } }

            runCurrent()
            assertTrue(outcome.isActive, "rig: the send must be suspended when the session dies")

            val death = CancellationException("the session died")
            session.die(death)
            runCurrent()
            val ended = outcome.await()

            assertIs<IOException>(ended, "a live caller must not hear a dead link as a cancellation: $ended")
            val cause = assertNotNull(ended.cause, "the session's own exception rides along")
            assertAll(
                { assertTrue(cause is CancellationException, "cause: $cause") },
                { assertEquals(death.message, cause.message) },
                { assertFalse(ended is CancellationException) },
            )
        }

    private suspend fun endOf(block: suspend () -> Unit): Throwable? =
        try {
            block()
            null
        } catch (failure: Throwable) {
            failure
        }

    private companion object {
        val FRAME = byteArrayOf(1, 2, 3)
    }
}

/**
 * A session whose outgoing queue is a rendezvous channel with no receiver: `send` suspends until the
 * test kills the queue. Only `outgoing` and `send` are exercised; the rest is inert.
 */
private class BlockedSession : DefaultWebSocketSession {
    private val queue = Channel<Frame>(Channel.RENDEZVOUS)

    /** What Netty and CIO do when the socket closes: cancel the queue, failing a suspended sender. */
    fun die(cause: CancellationException) = queue.cancel(cause)

    override val coroutineContext: CoroutineContext = Job()
    override val outgoing: SendChannel<Frame> get() = queue
    override val incoming: ReceiveChannel<Frame> = Channel()
    override val extensions: List<WebSocketExtension<*>> = emptyList()
    override val closeReason: Deferred<CloseReason?> = CompletableDeferred()
    override var masking: Boolean = false
    override var maxFrameSize: Long = Long.MAX_VALUE
    override var pingIntervalMillis: Long = 0
    override var timeoutMillis: Long = 0

    override suspend fun flush() = Unit

    @Deprecated("Use cancel() instead.", level = DeprecationLevel.ERROR)
    override fun terminate() = Unit

    override fun start(negotiatedExtensions: List<WebSocketExtension<*>>) = Unit
}
