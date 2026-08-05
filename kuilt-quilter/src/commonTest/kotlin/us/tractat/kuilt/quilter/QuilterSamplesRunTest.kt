package us.tractat.kuilt.quilter

import kotlinx.coroutines.test.TestResult
import kotlin.test.Test

/**
 * Runs every `@sample` in `QuilterSamples.kt`.
 *
 * A sample is *compiled* as part of `commonTest`, so a stale API breaks the build — but its
 * `assertEquals(…)` calls only execute if something calls the function, and until this test
 * nothing did. A sample whose claim had quietly become false therefore stayed green, while
 * `verifyDocCitations` went on proving the guide quotes it faithfully and Writerside embedded it
 * whole via `include-symbol`.
 *
 * The sibling `CrdtSamplesRunTest` exists for the same reason and names the incident that
 * motivated it. This file is the second module to close the gap; the repo-wide survey — 17
 * `commonSamples` files, roughly 80 sample functions — is #2116.
 *
 * These samples are `runTest`-based and return a [TestResult], so each gets its own `@Test` that
 * returns it. That matters on JS and wasm, where a `TestResult` is a promise the framework must
 * receive to await: swallowing it would make every one of these pass without running.
 *
 * New samples go in this file as they are added to `QuilterSamples.kt`.
 */
class QuilterSamplesRunTest {

    @Test
    fun quilterConvenienceHolds(): TestResult = sampleQuilterConvenience()

    @Test
    fun quilterMutateOrSkipHolds(): TestResult = sampleQuilterMutateOrSkip()

    @Test
    fun quilterSetupHolds(): TestResult = sampleQuilterSetup()

    @Test
    fun quilterSessionMetadataHolds(): TestResult = sampleQuilterSessionMetadata()

    @Test
    fun rgaChatReplicatorHolds(): TestResult = sampleRgaChatReplicator()

    @Test
    fun quilterSparseDeltaTargetsHolds(): TestResult = sampleQuilterSparseDeltaTargets()

    @Test
    fun voteTallyHolds(): TestResult = sampleVoteTally()
}
