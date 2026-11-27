package us.tractat.kuilt.identity

import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Executes the documentation samples on every target, so the snippets the cookbook quotes verbatim
 * cannot drift from code that runs. Their `check`s are the assertions: a real name parses, a
 * well-formed name for an off-curve point is refused, and a key's signature verifies under its own
 * name and no other, a grant round-trips under its issuer's signature until one byte changes, and a
 * key store returns one key and keeps the newest grant per host and scope, and a host's trust
 * answers each defect with its own verdict and lets a fresh pass in after a revocation, and a
 * revocation kept in a store is still there when the host reopens it, and a device proof round-trips
 * under its subject's signature until one byte changes, and a server's nonce is spent once and then
 * runs out.
 */
class IdentitySamplesRunTest {
    @Test
    fun parseDidKeySampleRuns(): TestResult = runTest { sampleParseDidKey() }

    @Test
    fun deviceKeySignsSampleRuns(): TestResult = runTest { sampleDeviceKeySigns() }

    @Test
    fun issueGrantSampleRuns(): TestResult = runTest { sampleIssueGrant() }

    @Test
    fun deviceKeyStoreSampleRuns(): TestResult = runTest { sampleDeviceKeyStore() }

    @Test
    fun grantTrustSampleRuns(): TestResult = runTest { sampleGrantTrust() }

    @Test
    fun durableRevocationsSampleRuns(): TestResult = runTest { sampleDurableRevocations() }

    @Test
    fun deviceProofSampleRuns(): TestResult = runTest { sampleDeviceProof() }

    @Test
    fun proofNoncesSampleRuns(): TestResult = runTest { sampleProofNonces() }
}
