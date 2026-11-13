package us.tractat.kuilt.identity

import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Executes the documentation sample on every target, so the snippet the cookbook quotes verbatim
 * cannot drift from code that runs. Its `check`s are the assertions: a real name parses, and a
 * well-formed name for an off-curve point is refused.
 */
class IdentitySamplesRunTest {
    @Test
    fun parseDidKeySampleRuns(): TestResult = runTest { sampleParseDidKey() }
}
