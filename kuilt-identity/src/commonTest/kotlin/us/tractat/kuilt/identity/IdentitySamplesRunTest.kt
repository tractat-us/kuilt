package us.tractat.kuilt.identity

import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Executes the documentation samples on every target, so the snippets the cookbook quotes verbatim
 * cannot drift from code that runs. Their `check`s are the assertions: a real name parses, a
 * well-formed name for an off-curve point is refused, and a key's signature verifies under its own
 * name and no other, and a grant round-trips under its issuer's signature until one byte changes.
 */
class IdentitySamplesRunTest {
    @Test
    fun parseDidKeySampleRuns(): TestResult = runTest { sampleParseDidKey() }

    @Test
    fun deviceKeySignsSampleRuns(): TestResult = runTest { sampleDeviceKeySigns() }

    @Test
    fun issueGrantSampleRuns(): TestResult = runTest { sampleIssueGrant() }
}
