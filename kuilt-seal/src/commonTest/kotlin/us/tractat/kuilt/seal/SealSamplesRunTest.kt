package us.tractat.kuilt.seal

import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Executes the documentation sample on every target, so the snippet the cookbook and `module.md`
 * quote verbatim cannot drift from code that runs. Its `check` is the assertion: the round trip from
 * a stored key and a published key must give back the plaintext.
 */
class SealSamplesRunTest {
    @Test
    fun sealedBoxSampleRoundTrips(): TestResult = runTest { sampleSealedBox() }
}
