package us.tractat.kuilt.warp

import kotlin.test.Test

/**
 * Runs every zero-argument `@sample` in `:kuilt-warp`.
 *
 * A sample is *compiled* as part of `commonTest`, so a stale API breaks the build — but its
 * `check(…)` calls execute only if something calls the function. See `CrdtSamplesRunTest` for the
 * incident that motivated this: a sample that asserted something false compiled, was quoted
 * verbatim into the cookbook, and `verifyDocCitations` faithfully proved the quote matched. The
 * citation gate answers "does the quote match the source?"; running the sample is the only thing
 * that answers "is the source true?" (#2116).
 *
 * `sampleConvergentExecution` (takes a `CoroutineScope`) and `sampleLazyFetch` (takes a
 * `WasmRuntime`) are absent deliberately — `verifySamplesAreRun` exempts a parameterised sample,
 * because calling one needs a fixture, and a fixture invented to satisfy a guard is how a runner
 * ends up proving nothing.
 */
class WarpSamplesRunTest {

    @Test
    fun shuttleHolds() = sampleShuttle()

    @Test
    fun zipHolds() = sampleZip()

    @Test
    fun warpStatsHolds() = sampleWarpStats()

    @Test
    fun awaitThresholdHolds() = sampleAwaitThreshold()

    @Test
    fun combineHolds() = sampleCombine()

    @Test
    fun joinAllOrNullHolds() = sampleJoinAllOrNull()

    @Test
    fun pinnedExecutionHolds() = samplePinnedExecution()

    @Test
    fun affinityHolds() = sampleAffinity()
}
