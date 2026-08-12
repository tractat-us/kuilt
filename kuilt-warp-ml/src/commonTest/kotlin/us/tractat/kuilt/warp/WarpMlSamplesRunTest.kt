package us.tractat.kuilt.warp

import kotlin.test.Test

/**
 * Runs every zero-argument `@sample` in `:kuilt-warp-ml`.
 *
 * A sample is *compiled* as part of `commonTest`, so a stale API breaks the build — but its
 * `check(…)` calls execute only if something calls the function. See `CrdtSamplesRunTest` for the
 * incident that motivated this: a sample that asserted something false compiled, was quoted
 * verbatim into the cookbook, and `verifyDocCitations` faithfully proved the quote matched. The
 * citation gate answers "does the quote match the source?"; running the sample is the only thing
 * that answers "is the source true?" (#2116).
 *
 * `verifySamplesAreRun` fails the build if a sample here is not named by this file, so a new one
 * cannot quietly revert to being unexecuted.
 */
class WarpMlSamplesRunTest {

    @Test
    fun fedAvgKernelCodecHolds() = sampleFedAvgKernelCodec()

    @Test
    fun fedAvgHolds() = sampleFedAvg()
}
