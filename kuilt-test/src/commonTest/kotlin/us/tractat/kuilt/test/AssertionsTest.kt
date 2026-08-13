package us.tractat.kuilt.test

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The contract of [assertAll] (#2283).
 *
 * Deliberately **not** written with `assertAll`, though the repo convention is to use it for
 * multi-assert tests: these are the tests of `assertAll` itself, so using it here would let a
 * broken implementation grade its own homework.
 *
 * The platform primitive this contract rests on — that suppressed throwables survive and render —
 * is measured separately in [ThrowableSuppressionTest].
 */
class AssertionsTest {

    // ─────────────────────────────────────────────────────────────────────────────
    // preserved: everything passing, and the all-AssertionError aggregate
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun everyAssertionPassingThrowsNothing() {
        val ran = mutableListOf<String>()
        assertAll(
            { ran += "first" },
            { ran += "second" },
        )
        assertEquals(listOf("first", "second"), ran)
    }

    @Test
    fun noAssertionsAtAllThrowsNothing() {
        assertAll()
    }

    @Test
    fun allFailuresBeingAssertionErrorsAggregatesInTodaysFormat() {
        val failure = assertFailsWith<AssertionError> {
            assertAll(
                { throw AssertionError("first named failure") },
                { },
                { throw AssertionError("second named failure") },
            )
        }
        assertEquals(
            "2 assertion(s) failed:\n  - first named failure\n  - second named failure",
            failure.message,
            "the aggregate message format is unchanged by #2283",
        )
    }

    @Test
    fun anAssertionErrorWithNoMessageFallsBackToItsToString() {
        val failure = assertFailsWith<AssertionError> {
            assertAll({ throw AssertionError() })
        }
        assertEquals("1 assertion(s) failed:\n  - ${AssertionError()}", failure.message)
    }

    /**
     * The real `kotlin.test` path, not a hand-thrown [AssertionError]: proves what `assertEquals`
     * actually throws is caught as an assertion failure on every target. Asserted by `contains`,
     * not equality — `kotlin.test`'s generated message wording differs per platform.
     */
    @Test
    fun realKotlinTestFailuresAreCollectedAsAssertionFailures() {
        val failure = assertFailsWith<AssertionError> {
            assertAll(
                { assertEquals(1, 2, "the size assertion") },
                { assertEquals(3, 4, "the content assertion") },
            )
        }
        val message = failure.message
        assertTrue(message != null && message.startsWith("2 assertion(s) failed:"), "got: $message")
        assertContains(message, "the size assertion")
        assertContains(message, "the content assertion")
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // new: a non-assertion throwable is the primary, diagnoses ride along
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * The whole point of the decision on #2283. Identity is the assertion, not the type: the very
     * object the assertion threw is what propagates, so its type, message and stack are all
     * necessarily its own — no aggregate, no `cause` wrapper, no message string.
     */
    @Test
    fun theNonAssertionPrimaryIsTheVeryObjectTheAssertionThrew() {
        val real = IllegalStateException("production code broke")
        val thrown = assertFailsWith<IllegalStateException> {
            assertAll(
                { throw AssertionError("a named diagnosis") },
                { throw real },
            )
        }
        assertSame(real, thrown, "the primary must be the original object, not a re-wrap")
        assertEquals("production code broke", thrown.message)
    }

    /**
     * The 210 masked blocks of #2283, in miniature: a `first()` on an empty collection under test
     * used to abort the block, discarding the named failure collected before it and skipping the
     * ones after. Now the throw is the primary and both diagnoses survive on it.
     */
    @Test
    fun siblingAssertionDiagnosesRideAlongOnTheNonAssertionPrimary() {
        val received = emptyList<String>()
        val thrown = assertFailsWith<NoSuchElementException> {
            assertAll(
                { throw AssertionError("one frame delivered") },
                { assertEquals("origin", received.first()) },
                { throw AssertionError("origin preserved") },
            )
        }
        assertEquals(
            listOf("one frame delivered", "origin preserved"),
            thrown.suppressedExceptions.map { it.message },
            "both named diagnoses ride along, in source order",
        )
        assertTrue(
            thrown.suppressedExceptions.all { it is AssertionError },
            "they ride along as AssertionErrors, not flattened to strings",
        )
    }

    @Test
    fun everyAssertionStillRunsAfterANonAssertionThrowable() {
        val ran = mutableListOf<String>()
        assertFailsWith<NoSuchElementException> {
            assertAll(
                { ran += "first" },
                { ran += "second"; emptyList<String>().first() },
                { ran += "third" },
            )
        }
        assertEquals(
            listOf("first", "second", "third"),
            ran,
            "a non-assertion throwable no longer aborts the block",
        )
    }

    /**
     * More than one non-assertion throwable: the **first** is the primary and the rest ride along
     * too. Nothing collected is discarded — the alternative would be to drop a second, different
     * production error on the floor.
     */
    @Test
    fun theFirstNonAssertionThrowableIsThePrimaryAndLaterOnesRideAlong() {
        val first = IllegalStateException("the first real error")
        val second = IllegalArgumentException("the second real error")
        val thrown = assertFailsWith<IllegalStateException> {
            assertAll(
                { throw first },
                { throw AssertionError("a named diagnosis") },
                { throw second },
            )
        }
        assertSame(first, thrown)
        assertEquals(
            listOf("a named diagnosis", "the second real error"),
            thrown.suppressedExceptions.map { it.message },
        )
    }

    @Test
    fun aLoneNonAssertionThrowablePropagatesWithNothingAttached() {
        val real = IllegalStateException("production code broke")
        val thrown = assertFailsWith<IllegalStateException> {
            assertAll(
                { },
                { throw real },
            )
        }
        assertSame(real, thrown)
        assertTrue(thrown.suppressedExceptions.isEmpty(), "nothing to carry, so nothing is attached")
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // cancellation
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Repo standing exception discipline: a cancellation is never aggregated, never delayed and
     * never decorated. It should be unreachable here — `assertAll` takes non-suspend lambdas, so
     * no `Flow.first()` or `runBlocking` can appear inside one (#2283 measured 0 hits) — so a hit
     * is a bug to surface, not a case to collect.
     */
    @Test
    fun cancellationPropagatesImmediatelyAndUnchanged() {
        val cancellation = CancellationException("cancelled")
        val ran = mutableListOf<String>()
        val thrown = assertFailsWith<CancellationException> {
            assertAll(
                { ran += "first"; throw AssertionError("collected before the cancel") },
                { ran += "second"; throw cancellation },
                { ran += "third" },
            )
        }
        assertSame(cancellation, thrown, "the same object, undecorated")
        assertTrue(thrown.suppressedExceptions.isEmpty(), "a cancellation carries nothing")
        assertEquals(listOf("first", "second"), ran, "no assertion runs after a cancellation")
    }
}
