package us.tractat.kuilt.store

import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Runs every zero-argument `@sample` in `:kuilt-store`.
 *
 * A sample is *compiled* as part of `commonTest`, so a stale API breaks the build — but its
 * `check(…)` calls execute only if something calls the function. The citation gate answers
 * "does the quote in the docs match the source?"; running the sample is the only thing that
 * answers "is the source true?" (#2116).
 *
 * The test returns the [TestResult] rather than swallowing it: on wasm the result is a promise
 * the framework must receive to await, so discarding it would make the test pass without
 * running the sample to completion.
 *
 * **What this proves is bounded by the store it runs against.** [sampleDurableStore] uses
 * [InMemoryDurableStore], which keeps nothing across a process exit — so the round-trip and
 * the absent-key answer are real, and the durability claim the sample's own comment makes is
 * not exercised here at all. Only a platform store can carry that one.
 */
class StoreSamplesRunTest {

    @Test
    fun durableStoreHolds(): TestResult = runTest { sampleDurableStore() }
}
