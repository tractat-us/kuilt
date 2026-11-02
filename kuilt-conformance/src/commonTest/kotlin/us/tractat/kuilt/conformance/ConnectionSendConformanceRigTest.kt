package us.tractat.kuilt.conformance

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.fabric.Connection
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Proves [ConnectionSendConformanceSuite] can fail. Its in-tree bindings are real sockets, so without
 * this the suite's own reds would be seen only by whoever broke a transport.
 *
 * Each property runs against a link built to break it. The two properties are exposed as functions
 * rather than driven through a subclass's `@Test`, because `runTest` returns a promise on wasmJs and
 * an `assertFailsWith` around one would pass without running anything.
 */
class ConnectionSendConformanceRigTest {

    private class Link(private val onSend: suspend () -> Unit) : Connection {
        override suspend fun send(frame: ByteArray) = onSend()

        override val incoming: Flow<ByteArray> = emptyFlow()

        override suspend fun close() = Unit
    }

    private val minted = CancellationException("a bare session.send over a closed session")

    @Test
    fun aLinkThatMintsACancellationRedsTheLiveCallerProperty() = runTest {
        val link = Link { throw minted }
        assertFailsWith<AssertionError> { assertLiveCallerGetsAnOrdinaryFailure(link) }
    }

    /** A healthy link must not pass by never failing: that is the vacuous case this property closes. */
    @Test
    fun aLinkThatNeverRefusesAFrameRedsTheLiveCallerProperty() = runTest {
        val link = Link { }
        assertFailsWith<AssertionError> { assertLiveCallerGetsAnOrdinaryFailure(link) }
    }

    @Test
    fun aLinkThatFailsOrdinarilySatisfiesTheLiveCallerProperty() = runTest {
        val link = Link { throw IllegalStateException("the link is dead") }
        assertLiveCallerGetsAnOrdinaryFailure(link)
    }

    /** The overshooting fix: every cancellation converted, so a cancelled caller is told it failed. */
    @Test
    fun aLinkThatConvertsTheCallersOwnCancellationRedsTheCancelledCallerProperty() = runTest {
        val link = Link { throw IllegalStateException("converted") }
        assertFailsWith<AssertionError> { assertCancelledCallerOutcome(link, CancelledCallerFixture.Observes) }
    }

    @Test
    fun aLinkThatReportsCancellationSatisfiesTheCancelledCallerProperty() = runTest {
        val link = Link { throw minted }
        assertCancelledCallerOutcome(link, CancelledCallerFixture.Observes)
    }

    @Test
    fun aDeclaredNeverChecksThatDoesReportCancellationRedsTheCancelledCallerProperty() = runTest {
        val link = Link { throw minted }
        assertFailsWith<AssertionError> {
            assertCancelledCallerOutcome(link, CancelledCallerFixture.NeverChecks("it blocks"))
        }
    }

    @Test
    fun aBlankNeverChecksReasonRedsTheCancelledCallerProperty() = runTest {
        val link = Link { }
        assertFailsWith<AssertionError> {
            assertCancelledCallerOutcome(link, CancelledCallerFixture.NeverChecks(" "))
        }
    }

    @Test
    fun aDeclaredNeverChecksThatFailsOrdinarilySatisfiesTheCancelledCallerProperty() = runTest {
        val link = Link { throw IllegalStateException("the transport's own failure") }
        assertCancelledCallerOutcome(link, CancelledCallerFixture.NeverChecks("it blocks"))
    }
}
