package us.tractat.kuilt

import kotlin.test.Test

/** Runs every `@sample`, whose bodies assert what their KDoc claims. */
class SamplesRunTest {

    @Test
    fun kuiltVersionHolds() = sampleKuiltVersion()
}
