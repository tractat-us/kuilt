@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.conformance

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.ScopedCloseable
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Reusable lifecycle contract test suite for [ScopedCloseable] implementations.
 *
 * Subclass and implement [create] to bind any coordinator under test. The returned instance
 * must be freshly constructed (owning its own child job) and must not be closed yet.
 *
 * Lives in `commonMain` of `:kuilt-conformance` so every module can subclass it from its
 * own `commonTest` source set. Every [Test] encodes a required invariant of the
 * [ScopedCloseable] contract — a conforming implementation must pass all of them.
 *
 * **Test-body close requirement.** Every test that creates a coordinator must call
 * [AutoCloseable.close] before the test body exits (or use `use {}`). An un-closed
 * coordinator with a live anti-entropy loop can freeze virtual time in `runTest`
 * ([kotlinx.coroutines.test.UncompletedCoroutinesError]).
 *
 * ## Wiring
 *
 * ```kotlin
 * class MyCoordinatorLifecycleTest : CloseableLifecycleConformanceSuite() {
 *     override fun create(scope: CoroutineScope): MyCoordinator =
 *         MyCoordinator(scope)
 *
 *     override fun backgroundJobsOf(instance: ScopedCloseable): List<Job> =
 *         (instance as MyCoordinator).backgroundJobsForTest
 * }
 * ```
 */
public abstract class CloseableLifecycleConformanceSuite {

    /**
     * Create a fresh, un-closed instance of the coordinator under test.
     *
     * Implementations must launch all background coroutines into [scope]. The instance
     * must implement [ScopedCloseable] — the suite verifies [ScopedCloseable.ownJob] is
     * cancelled on [AutoCloseable.close].
     */
    protected abstract fun create(scope: CoroutineScope): ScopedCloseable

    /**
     * Return the background [Job]s to assert active/inactive state on. These are
     * the concrete jobs launched during construction (the jobs that run the coordinator's
     * work). For single-job coordinators return a one-element list; for multi-job
     * coordinators return all owned jobs.
     *
     * The [ScopedCloseable.ownJob] itself is always asserted by the suite; this method
     * lets subclasses also assert the child coroutine jobs they expose for testing.
     */
    protected abstract fun backgroundJobsOf(instance: ScopedCloseable): List<Job>

    @Test
    fun backgroundJobsActiveBeforeClose(): TestResult = runTest(UnconfinedTestDispatcher()) {
        val instance = create(backgroundScope)
        try {
            assertTrue(
                backgroundJobsOf(instance).all { it.isActive },
                "All background jobs should be active before close()",
            )
        } finally {
            instance.close()
        }
    }

    @Test
    fun closeStopsAllBackgroundJobs(): TestResult = runTest(UnconfinedTestDispatcher()) {
        val instance = create(backgroundScope)
        instance.close()
        assertFalse(
            backgroundJobsOf(instance).any { it.isActive },
            "No background job should remain active after close()",
        )
    }

    @Test
    fun closeIsIdempotent(): TestResult = runTest(UnconfinedTestDispatcher()) {
        val instance = create(backgroundScope)
        instance.close()
        instance.close() // must not throw
        assertFalse(
            backgroundJobsOf(instance).any { it.isActive },
            "Jobs should remain inactive after double-close()",
        )
    }
}
