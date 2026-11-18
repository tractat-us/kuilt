package us.tractat.kuilt.heddle

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import kotlin.test.Test

/**
 * Runs every `@sample` in `:kuilt-heddle-coroutines`. A sample is compiled as part of `commonTest`,
 * but its `check(…)` calls execute only if something calls it (#2116).
 */
class FairSharePoolSamplesRunTest {

    @Test
    fun samples() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        sampleAcquireSlot()
    }
}
