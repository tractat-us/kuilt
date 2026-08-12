package us.tractat.kuilt.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class RunCatchingCancellableTest {

    @Test
    fun successReturnsValue() {
        val result = runCatchingCancellable { 42 }
        assertEquals(Result.success(42), result)
    }

    @Test
    fun nonCancellationExceptionBecomesFailure() {
        val exception = IllegalStateException("boom")
        val result = runCatchingCancellable { throw exception }
        assertTrue(result.isFailure)
        assertEquals(exception, result.exceptionOrNull())
    }

    @Test
    fun cancellationExceptionPropagates() {
        assertFailsWith<CancellationException> {
            runCatchingCancellable { throw CancellationException("x") }
        }
    }

    /**
     * The trap behind #2292, pinned on the primitive that springs it: a `withTimeout` **inside**
     * the block reports its own expiry as a `TimeoutCancellationException`, which **is a**
     * `CancellationException` — so the rethrow arm above fires and the caller's `onFailure` /
     * `getOrElse` never sees the one outcome the bound was written to produce.
     *
     * This is not a defect in [runCatchingCancellable]: it discriminates on TYPE, and type cannot
     * separate "my job was cancelled" from "my callee minted one". It is a defect at every call
     * site that puts a bound inside it and then writes a handler for the timeout.
     */
    @Test
    fun aWithTimeoutInsideIsRethrownRatherThanCaptured() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        assertFailsWith<TimeoutCancellationException> {
            runCatchingCancellable { withTimeout(1.seconds) { awaitCancellation() } }
        }
    }

    /**
     * …and the conversion that makes such a call site correct: [withTimeoutOrNull] absorbs its own
     * expiry and returns `null`, so an explicit non-cancellation throw carries it to the caller as
     * an ordinary [Result.failure]. The shape `MultipeerCrossProcessProbe` was moved to in #2292.
     */
    @Test
    fun withTimeoutOrNullPlusAnExplicitThrowIsCapturedAsAFailure() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val result = runCatchingCancellable {
            withTimeoutOrNull(1.seconds) { awaitCancellation() } ?: error("gave up after 1s")
        }
        assertTrue(result.isFailure, "an absorbed timeout must reach the caller as a failure, not a cancel")
        assertEquals("gave up after 1s", result.exceptionOrNull()?.message)
    }
}
