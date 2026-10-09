@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.core.fabric

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** A no-op [Connection] identified only by [id]; the test never sends or receives on it. */
private class FakeConnection(val id: String) : Connection {
    override suspend fun send(frame: ByteArray) = Unit
    override val incoming: Flow<ByteArray> = emptyFlow()
    override suspend fun close() = Unit
}

class AcceptPumpTest {

    @Test
    fun consecutiveAcceptFailuresBackOffToACap() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        var attempts = 0
        val times = mutableListOf<Long>()
        val source = object : ConnectionSource {
            override suspend fun accept(): Connection {
                attempts++
                times += testScheduler.currentTime
                // A no-delay mutant must reach the attempts assertion, not spin forever at time zero.
                if (attempts == 100) currentCoroutineContext().cancel()
                throw IllegalStateException("source remains unavailable")
            }
        }
        val job = backgroundScope.acceptPump(source, handshakeTimeout = 2.seconds) {}
        advanceTimeBy(4.seconds)
        runCurrent()
        assertAll(
            { assertEquals(10, attempts, "accept attempts must be bounded to 10 in 4 seconds") },
            { assertEquals(listOf(0L, 10L, 30L, 70L, 150L, 310L, 630L, 1270L, 2270L, 3270L), times) },
            { assertTrue(job.isActive, "the source must still be retried after reaching the cap") },
        )
        job.cancel()
        runCurrent()
        assertTrue(job.isCompleted, "cancellation must interrupt the back-off delay")
    }

    @Test
    fun aSuccessfulAcceptResetsTheBackOff() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val times = mutableListOf<Long>()
        var handled = 0
        val source = object : ConnectionSource {
            override suspend fun accept(): Connection {
                times += testScheduler.currentTime
                return when (times.size) {
                    1, 2, 4 -> throw IllegalStateException("accept failed")
                    3, 5 -> FakeConnection("accepted")
                    else -> awaitCancellation()
                }
            }
        }
        backgroundScope.acceptPump(source, handshakeTimeout = 2.seconds) { handled++ }
        advanceTimeBy(41.milliseconds)
        runCurrent()
        assertAll(
            { assertEquals(listOf(0L, 10L, 30L, 30L, 40L, 40L), times, "success must reset the next delay to 10 ms") },
            { assertEquals(2, handled, "both successful accepts must be handled") },
        )
    }

    @Test
    fun anErrorFromAcceptFailsThePumpUnreported() = assertFatalAcceptFailure(fromReporter = false)

    @Test
    fun anErrorFromTheAcceptReporterEscapes() = assertFatalAcceptFailure(fromReporter = true)

    private fun assertFatalAcceptFailure(fromReporter: Boolean) =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val fatal = Error("fatal accept path")
            val recoverable = IllegalStateException("accept failed")
            val unhandled = mutableListOf<Throwable>()
            val reported = mutableListOf<Throwable>()
            val supervisor = SupervisorJob()
            val scope = CoroutineScope(coroutineContext + supervisor + CoroutineExceptionHandler { _, e -> unhandled += e })
            var completionCause: Throwable? = null
            var attempts = 0
            val source = object : ConnectionSource {
                override suspend fun accept(): Connection {
                    if (++attempts > 1) awaitCancellation()
                    throw if (fromReporter) recoverable else fatal
                }
            }
            try {
                val job = scope.acceptPump(source, handshakeTimeout = 2.seconds, onFailure = {
                    reported += it
                    if (fromReporter) throw fatal
                }) {}
                job.invokeOnCompletion { completionCause = it }
                runCurrent()
                assertAll(
                    { assertTrue(job.isCompleted && job.isCancelled, "an Error must fail the pump") },
                    { assertSame(fatal, completionCause, "the Error must be the pump's failure cause") },
                    { assertEquals(listOf<Throwable>(fatal), unhandled, "the Error must escape to the scope handler") },
                    { assertEquals(if (fromReporter) listOf<Throwable>(recoverable) else emptyList(), reported,
                        "the Error must never be reported as a recoverable failure") },
                    { assertEquals(1, attempts, "an Error must not retry accept") },
                )
            } finally {
                supervisor.cancel()
            }
        }

    @Test
    fun aFailedAcceptDoesNotStopLaterConnections() = assertAcceptRecovery(IllegalStateException("accept failed"))

    @Test
    fun aCalleeMintedCancellationDoesNotStopLaterConnections() =
        assertAcceptRecovery(CancellationException("source cancelled only its own operation"))

    @Test
    fun aThrowingFailureReporterDoesNotStopLaterConnections() =
        assertAcceptRecovery(IllegalStateException("accept failed"), reporterThrows = true)

    private fun assertAcceptRecovery(failure: Exception, reporterThrows: Boolean = false) =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val unhandled = mutableListOf<Throwable>()
            val supervisor = SupervisorJob()
            val scope = CoroutineScope(coroutineContext + supervisor + CoroutineExceptionHandler { _, e -> unhandled += e })
            val connection = FakeConnection("after-failure")
            val handled = mutableListOf<Connection>()
            val reported = mutableListOf<Throwable>()
            var attempts = 0
            val source = object : ConnectionSource {
                override suspend fun accept(): Connection = when (++attempts) {
                    1 -> throw failure
                    2 -> connection
                    else -> awaitCancellation()
                }
            }
            try {
                val job = scope.acceptPump(source, handshakeTimeout = 2.seconds, onFailure = {
                    reported += it
                    if (reporterThrows) throw CancellationException("reporter failed")
                }) { handled += it }
                advanceTimeBy(10.milliseconds)
                runCurrent()
                assertAll(
                    { assertEquals(listOf<Connection>(connection), handled, "the connection after the failed accept must be handled") },
                    { assertEquals(listOf<Throwable>(failure), reported, "the accept failure must be reported once") },
                    { assertEquals(3, attempts, "the pump must reach the next suspended accept") },
                    { assertTrue(job.isActive, "the pump must survive the failed accept") },
                    { assertTrue(unhandled.isEmpty(), "no recoverable failure may escape to the scope handler") },
                )
                job.cancel()
                runCurrent()
                assertAll(
                    { assertTrue(job.isCompleted && job.isCancelled, "cancellation must finish the suspended pump") },
                    { assertEquals(3, attempts, "cancellation must not retry accept") },
                    { assertEquals(listOf<Throwable>(failure), reported, "cancellation must not be reported as an accept failure") },
                )
            } finally {
                supervisor.cancel()
            }
        }

    @Test
    fun cancellationDuringAFailedAcceptIsNotReportedOrRetried() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            var attempts = 0
            val failures = mutableListOf<Throwable>()
            val source = object : ConnectionSource {
                override suspend fun accept(): Connection {
                    attempts++
                    currentCoroutineContext().cancel()
                    throw IllegalStateException("accept failed while the pump was cancelled")
                }
            }
            val job = backgroundScope.acceptPump(source, handshakeTimeout = 2.seconds, onFailure = { failures += it }) {}
            runCurrent()
            assertAll(
                { assertTrue(job.isCompleted && job.isCancelled, "the cancelled pump must finish") },
                { assertEquals(1, attempts, "a cancelled accept must not be retried") },
                { assertTrue(failures.isEmpty(), "the pump's cancellation must propagate before reporting") },
            )
        }

    /** A conn whose handling hangs must not block a later conn's handling (concurrency), and must be
     *  abandoned after the handshake timeout (no permanent wedge). */
    @Test
    fun aHungHandshakeDoesNotStarveLaterConnections() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val handled = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()   // never completed → conn "hangs"
        val conns = ArrayDeque(listOf("hang", "good-1", "good-2"))
        val source = object : ConnectionSource {
            override suspend fun accept(): Connection =
                FakeConnection(conns.removeFirstOrNull() ?: CompletableDeferred<String>().await())
        }
        val failures = mutableListOf<Throwable>()
        val job = acceptPump(source, handshakeTimeout = 2.seconds, onFailure = { failures += it }) { conn ->
            val id = (conn as FakeConnection).id
            if (id == "hang") gate.await() else handled += id
        }
        advanceTimeBy(3.seconds); runCurrent()      // past the 2s handshake timeout → hung conn abandoned
        assertEquals(setOf("good-1", "good-2"), handled.toSet())
        assertTrue(failures.any { it is HandshakeTimeoutException }, "the hung conn surfaced a handshake timeout")
        job.cancel()
    }
}
