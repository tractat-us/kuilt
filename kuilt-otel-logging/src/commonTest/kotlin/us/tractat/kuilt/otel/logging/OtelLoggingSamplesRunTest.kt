package us.tractat.kuilt.otel.logging

import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Runs every zero-argument `@sample` in `:kuilt-otel-logging`.
 *
 * A sample is *compiled* as part of `commonTest`, so a stale API breaks the build — but its body
 * only executes if something calls the function. See `CrdtSamplesRunTest` for the incident that
 * motivated the sweep: a sample that asserted something false compiled, was quoted verbatim into
 * the guide, and `verifyDocCitations` faithfully proved the quote matched. That gate answers "does
 * the quote match the source?"; running the sample is the only thing that answers "is the source
 * true?" (#2116).
 *
 * `sampleWithActiveTrace` was the third of the three samples that failed the moment #2116 first
 * ran them, with `NoClassDefFoundError: org/slf4j/LoggerFactory` on its first line. It is a
 * `suspend fun` rather than a `runTest` body, so it gets a `runTest { … }` wrapper here and returns
 * the resulting [TestResult] — on JS and wasm that is a promise the framework must receive to
 * await, and swallowing it would make this pass without running (#2289).
 *
 * `sampleInstallLogCapture` takes a `CoroutineScope` and so is not callable un-parameterised;
 * `verifySamplesAreRun` exempts it by design rather than have a fixture invented purely to satisfy
 * the guard.
 */
class OtelLoggingSamplesRunTest {

    @Test
    fun withActiveTraceHolds(): TestResult = runTest { sampleWithActiveTrace() }

    @Test
    fun withLogContextHolds(): TestResult = runTest { sampleWithLogContext() }
}
