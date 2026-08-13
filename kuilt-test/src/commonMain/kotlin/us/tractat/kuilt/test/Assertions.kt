package us.tractat.kuilt.test

import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs **every** assertion, collecting every failure instead of stopping at the first, then reports
 * all of them together.
 *
 * What it throws depends on what it collected:
 *
 * - **Only [AssertionError]s** — one aggregate [AssertionError] naming every failure:
 *   `2 assertion(s) failed:\n  - …\n  - …`.
 * - **Anything else in the mix** — a `NoSuchElementException` from a `single()`, an
 *   `IllegalStateException` from production code — then **that** throwable is what propagates,
 *   as itself: the original object, its own type, its own message, its own stack. Every other
 *   failure collected rides along on its [suppressedExceptions], so no diagnosis is lost. If more
 *   than one non-assertion throwable is collected, the first is the primary and the rest ride along
 *   too.
 * - **A [CancellationException]** — propagates immediately and undecorated, ahead of any
 *   collection, per the repo's standing exception discipline. It should be unreachable: `assertAll`
 *   takes non-suspend lambdas, so no `Flow.first()` or `runBlocking` can appear inside one. Treat a
 *   hit as a bug to surface, not a case to aggregate.
 *
 * Carrying the diagnoses on the primary rather than folding the primary into an aggregate is
 * deliberate, and is where this differs from JUnit 5's `Assertions.assertAll` and Kotest's
 * `assertSoftly`: both throw an aggregate and demote a genuine production error to a suppressed
 * entry, costing you its type and its stack (#2283). Suppression is measured to survive and to
 * render on every kuilt target — JVM, Android, wasmJs, iOS and macOS — by `ThrowableSuppressionTest`.
 *
 * **What this replaced, because the old advice is still in a lot of comments (#1823, #2283).**
 * `assertAll` used to catch only [AssertionError], so a `single()` / `first()` / `last()` /
 * `getValue()` / `!!` / `[i]` on the collection *under test* aborted the whole block exactly when
 * that collection was empty or short — the broken state you were trying to diagnose. Every later
 * assertion was skipped and every failure already collected was discarded, so you were shown
 * `NoSuchElementException: List is empty.` instead of the named failures you wrote. That is fixed
 * here, for all 210 blocks the #2283 sweep found, rather than site by site.
 *
 * So writing an assertion list-shaped is now **good style, not a safety requirement**: it still
 * reads better and still gives you a named failure printing both sides, but it is no longer what
 * stands between you and losing the diagnosis.
 *
 * ```kotlin
 * assertAll(
 *     { assertEquals(1, received.size, "one frame delivered") },
 *     { assertEquals(listOf(origin), received.map { it.sender }) },
 * )
 * ```
 */
public fun assertAll(vararg assertions: () -> Unit) {
    val collected = buildList {
        for (assertion in assertions) {
            try {
                assertion()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                add(failure)
            }
        }
    }
    if (collected.isEmpty()) return

    val primary = collected.firstOrNull { it !is AssertionError }
    if (primary == null) {
        val message = collected.joinToString(prefix = "${collected.size} assertion(s) failed:\n", separator = "\n") {
            "  - ${it.message ?: it.toString()}"
        }
        throw AssertionError(message)
    }
    collected.filterNot { it === primary }.forEach { primary.addSuppressed(it) }
    throw primary
}
