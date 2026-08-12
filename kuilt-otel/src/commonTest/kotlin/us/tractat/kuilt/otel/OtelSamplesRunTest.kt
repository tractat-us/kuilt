package us.tractat.kuilt.otel

import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Runs every zero-argument `@sample` in `:kuilt-otel`.
 *
 * A sample is *compiled* as part of `commonTest`, so a stale API breaks the build — but its
 * `check(…)` calls execute only if something calls the function. See `CrdtSamplesRunTest` for the
 * incident that motivated this: a sample that asserted something false compiled, was quoted
 * verbatim into the cookbook, and `verifyDocCitations` faithfully proved the quote matched. The
 * citation gate answers "does the quote match the source?"; running the sample is the only thing
 * that answers "is the source true?" (#2116).
 *
 * Each test returns the [TestResult] rather than swallowing it: on JS and wasm the result is a
 * promise the framework must receive to await, so discarding it would make the test pass without
 * running the sample to completion.
 *
 * **Four of these assert nothing, and this file does not pretend otherwise.**
 * `sampleBulkExport`, `sampleWarpTelemetryClear` and `sampleWarpOtlpBridge` `when`/`println` over
 * a result without claiming a value, and `sampleOtlpEdge` has a body of pure comments. Running
 * them buys "it constructs and does not throw" and nothing more — for `sampleOtlpEdge`, not even
 * that. They are here because `verifySamplesAreRun` requires every callable sample to be called,
 * not because a green here means their prose is true. Adding assertions to make them meaningful
 * would mean inventing claims about documentation, which is the defect this file exists to catch.
 */
class OtelSamplesRunTest {

    @Test
    fun warpLogRecordExporterHolds(): TestResult = runTest { sampleWarpLogRecordExporter() }

    @Test
    fun bulkExportHolds(): TestResult = runTest { sampleBulkExport() }

    @Test
    fun warpTelemetryHolds(): TestResult = runTest { sampleWarpTelemetry() }

    @Test
    fun warpTelemetryClearHolds(): TestResult = runTest { sampleWarpTelemetryClear() }

    @Test
    fun warpOtlpBridgeHolds(): TestResult = runTest { sampleWarpOtlpBridge() }

    @Test
    fun otlpEdgeHolds(): TestResult = runTest { sampleOtlpEdge() }

    @Test
    fun warpMetricExporterHolds(): TestResult = runTest { sampleWarpMetricExporter() }

    @Test
    fun warpSpanExporterHolds(): TestResult = runTest { sampleWarpSpanExporter() }

    @Test
    fun inferCausalLinksHolds() = sampleInferCausalLinks()
}
