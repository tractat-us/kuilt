package us.tractat.kuilt.otel.logging

import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Runs every zero-argument `@sample` in `:kuilt-otel-logging`.
 *
 * A sample is *compiled* as part of `commonTest`, so a stale API breaks the build — but its
 * `check(…)` calls execute only if something calls the function. See `CrdtSamplesRunTest` for the
 * incident that motivated this: a sample that asserted something false compiled, was quoted
 * verbatim into the cookbook, and `verifyDocCitations` faithfully proved the quote matched. The
 * citation gate answers "does the quote match the source?"; running the sample is the only thing
 * that answers "is the source true?" (#2116).
 *
 * `sampleInstallLogCapture` is absent deliberately — it takes a `CoroutineScope`, and
 * `verifySamplesAreRun` exempts a parameterised sample because calling one needs a fixture.
 *
 * `sampleWithActiveTrace` asserts nothing: it logs inside and outside a `withActiveTrace` scope to
 * show the shape. Running it proves the calls type-check and do not throw — not that the stamping
 * it describes happens. That claim is `:kuilt-otel-logging`'s own tests' job, not this file's.
 */
class OtelLoggingSamplesRunTest {

    @Test
    fun withActiveTraceHolds(): TestResult = runTest { sampleWithActiveTrace() }
}
