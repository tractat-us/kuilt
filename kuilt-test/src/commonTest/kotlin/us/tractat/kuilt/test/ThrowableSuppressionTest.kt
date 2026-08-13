package us.tractat.kuilt.test

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A **platform probe**, not a test of kuilt code: it pins whether `kotlin.Throwable`'s
 * suppression pair — `addSuppressed` / `suppressedExceptions` — actually round-trips on the
 * target that is running this test.
 *
 * [assertAll] rests on exactly that. It throws a non-`AssertionError` **as the primary**, so the
 * caller keeps its type and its stack, and carries the sibling `AssertionError` diagnoses on the
 * primary's suppressed list. If a target silently dropped them, `assertAll` on that target would
 * revert to showing one throw and discarding every diagnosis collected before it — the exact
 * failure this mechanism exists to prevent, and invisible unless something asserts on it.
 *
 * So this probe is the receipt for the whole design, and it is deliberately written against the
 * stdlib rather than against `assertAll`: if a future Kotlin release changes suppression on any
 * target, this reddens and names the platform, instead of `assertAll`'s own tests reddening and
 * pointing at kuilt.
 */
class ThrowableSuppressionTest {

    @Test
    fun addSuppressedRoundTripsOnThisTarget() {
        val primary = IllegalStateException("the real error")
        primary.addSuppressed(AssertionError("first diagnosis"))
        primary.addSuppressed(AssertionError("second diagnosis"))

        assertAllLocal(
            { assertEquals(2, primary.suppressedExceptions.size, "both suppressed entries retained") },
            {
                assertEquals(
                    listOf("first diagnosis", "second diagnosis"),
                    primary.suppressedExceptions.map { it.message },
                    "messages retained, in the order added",
                )
            },
            {
                assertEquals(
                    listOf(true, true),
                    primary.suppressedExceptions.map { it is AssertionError },
                    "type retained, not flattened to a message",
                )
            },
            { assertEquals("the real error", primary.message, "the primary is untouched by suppression") },
        )
    }

    /**
     * The control arm for the test above: proves the getter reports what was actually added rather
     * than a constant. Without it, `size == 2` would be consistent with a platform that returns
     * some fixed non-empty list, and the measurement would prove nothing.
     */
    @Test
    fun aThrowableWithNothingSuppressedReportsAnEmptyList() {
        assertTrue(IllegalStateException("lonely").suppressedExceptions.isEmpty())
    }

    /**
     * The *second* half of the property, and the one that actually reaches a developer: retaining a
     * suppressed entry in the object is worth nothing if the rendering a test runner prints omits it.
     * `stackTraceToString()` is that rendering, so this measures whether the sibling diagnosis is
     * **visible**, not merely recoverable.
     */
    @Test
    fun stackTraceRenderingIncludesTheSuppressedDiagnosisOnThisTarget() {
        val primary = IllegalStateException("the real error")
        primary.addSuppressed(AssertionError("the diagnosis that must stay visible"))

        val rendered = primary.stackTraceToString()
        assertTrue(
            "the diagnosis that must stay visible" in rendered,
            "suppressed diagnosis missing from stackTraceToString(); rendered was:\n$rendered",
        )
    }

    /**
     * Deliberately not [assertAll]: this file measures the platform primitive that `assertAll`'s
     * own contract is built on, so it must not depend on that contract holding.
     */
    private fun assertAllLocal(vararg checks: () -> Unit) {
        val failures = checks.mapNotNull { check ->
            try {
                check()
                null
            } catch (failure: AssertionError) {
                failure
            }
        }
        if (failures.isNotEmpty()) throw AssertionError(failures.joinToString("\n") { it.message ?: it.toString() })
    }
}
