package us.tractat.kuilt.raft

import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import kotlin.test.Test

/**
 * Runs every zero-argument `@sample` in `:kuilt-raft`. Mirrors `StoreSamplesRunTest`.
 *
 * A sample is *compiled* as part of `commonTest`, so a stale API breaks the build — but its
 * `check(…)` calls execute only if something calls the function. The citation gate answers
 * "does the quote in the docs match the source?"; running the sample is the only thing that
 * answers "is the source true?" (#2116).
 *
 * The test returns the [TestResult] rather than swallowing it: on wasm the result is a promise the
 * framework must receive to await, so discarding it would make the test pass without running the
 * sample to completion.
 *
 * **What this proves is bounded by the store the sample runs against.** [sampleDurableRaftStorage]
 * uses `InMemoryDurableStore`, which keeps nothing across a process exit — so the reopen it performs
 * is a real second pass through the adapter's encoder and decoder, and the *durability* half of the
 * claim is not exercised here at all. The conformance subclasses over the platform stores carry
 * that one.
 */
class RaftSamplesRunTest {

    @Test
    fun durableRaftStorageHolds(): TestResult = runTest(timeout = TEST_WEDGE_BACKSTOP) { sampleDurableRaftStorage() }
}
