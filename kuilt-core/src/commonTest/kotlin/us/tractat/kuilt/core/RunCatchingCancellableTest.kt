package us.tractat.kuilt.core

import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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
}
