package us.tractat.kuilt.warp

import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Runs every zero-argument `@sample` in `:kuilt-warp-planning`.
 *
 * A sample is *compiled* as part of `commonTest`, so a stale API breaks the build — but its
 * `check(…)` calls execute only if something calls the function. See `CrdtSamplesRunTest` for the
 * incident that motivated this: a sample that asserted something false compiled, was quoted
 * verbatim into the cookbook, and `verifyDocCitations` faithfully proved the quote matched. The
 * citation gate answers "does the quote match the source?"; running the sample is the only thing
 * that answers "is the source true?" (#2116).
 *
 * A `suspend` sample needs a `runTest` of its own, and that test returns the [TestResult] rather
 * than swallowing it: on JS and wasm the result is a promise the framework must receive to await,
 * so discarding it would make the test pass without running the sample to completion.
 */
class WarpPlanningSamplesRunTest {

    @Test
    fun coordinationCostHolds() = sampleCoordinationCost()

    @Test
    fun optimizeHolds() = sampleOptimize()

    @Test
    fun consolidateEmbroideriesHolds() = sampleConsolidateEmbroideries()

    @Test
    fun coordinationCostDepthHolds() = sampleCoordinationCostDepth()

    @Test
    fun executeCoordinatedHolds(): TestResult = runTest { sampleExecuteCoordinated() }
}
