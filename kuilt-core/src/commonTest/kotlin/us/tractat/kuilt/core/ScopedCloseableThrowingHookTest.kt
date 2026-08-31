package us.tractat.kuilt.core

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * What [ScopedCloseable.close] owes when [ScopedCloseable.onClose] throws (#2330).
 *
 * The latch is claimed *before* the work it guards is known to have succeeded, so a throwing hook
 * used to leave the instance in a state that is neither open nor closed: `ownJob.cancel()` was
 * skipped, the coroutine tree under it leaked, and every later `close()` returned at the latch, so
 * there was no retry. Ten in-tree subclasses inherit that, and several of their `onClose()` bodies
 * close *other* [ScopedCloseable]s — the shape where a throw partway through strands the rest of an
 * object graph.
 *
 * ## Which assertion here is load-bearing
 *
 * That `close()` **rethrows** is true before the fix as well — asserting only that would pass
 * against the defect, the vacuity trap #2330 names by hand. The assertions that reduce to `false`
 * on the unfixed base class are the ones about the *scope*: [Job.isCancelled] on the owned job, and
 * on a coroutine actually launched into it. The rethrow is asserted alongside them only to pin that
 * the fix propagates rather than swallows.
 *
 * [closingTwiceAfterAThrowingHookStillRunsTheHookOnlyOnce] pins the other direction: `finally`
 * rather than releasing the latch, so a throwing hook cannot be re-entered. Releasing it was
 * considered and rejected — it would reintroduce the double-`onClose()` that #2305 removed.
 */
class ScopedCloseableThrowingHookTest {

    /** Counts hook invocations, throws [failure] from every one, and exposes what it owns. */
    private class ThrowingCloseable(
        parentScope: CoroutineScope,
        private val failure: Throwable,
    ) : ScopedCloseable(parentScope) {
        private val invocations = atomic(0)

        /** [ScopedCloseable.ownJob] is `protected`; a test in another module has no other handle. */
        val job: Job get() = ownJob
        val hookInvocations: Int get() = invocations.value

        fun launchChild(): Job = scope.launch { awaitCancellation() }

        override fun onClose() {
            invocations.incrementAndGet()
            throw failure
        }
    }

    /** Closes [inner] and *then* throws — the "hook tears down other closeables" shape. */
    private class DelegatingThrowingCloseable(
        parentScope: CoroutineScope,
        private val inner: ScopedCloseable,
        private val failure: Throwable,
    ) : ScopedCloseable(parentScope) {
        val job: Job get() = ownJob

        override fun onClose() {
            inner.close()
            throw failure
        }
    }

    @Test
    fun aThrowingHookStillCancelsTheOwnedScope() = runTest(UnconfinedTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val boom = IllegalStateException("onClose blew up")
        val closeable = ThrowingCloseable(backgroundScope, boom)
        val child = closeable.launchChild()
        assertTrue(child.isActive, "rig: the child coroutine must be running before close() is called")

        val thrown = assertFailsWith<IllegalStateException> { closeable.close() }

        assertAll(
            { assertSame(boom, thrown, "close() must propagate the hook's throwable, not wrap or replace it") },
            { assertTrue(closeable.job.isCancelled, "ownJob must be cancelled even though onClose() threw (#2330)") },
            { assertTrue(child.isCancelled, "a coroutine launched into the owned scope must be cancelled too") },
            { assertEquals(1, closeable.hookInvocations, "the winning caller runs onClose() exactly once") },
        )
    }

    @Test
    fun aHookThatTearsDownAnotherCloseableAndThrowsStillCancelsBoth() =
        runTest(UnconfinedTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val boom = IllegalStateException("outer hook blew up after tearing down inner")
            val inner = ThrowingCloseableWithoutThrow(backgroundScope)
            val innerChild = inner.launchChild()
            val outer = DelegatingThrowingCloseable(backgroundScope, inner, boom)

            assertFailsWith<IllegalStateException> { outer.close() }

            assertAll(
                { assertTrue(inner.job.isCancelled, "the closeable the hook tore down must still be closed") },
                { assertTrue(innerChild.isCancelled, "the inner instance's coroutines must be cancelled") },
                { assertTrue(outer.job.isCancelled, "the throwing outer instance must not strand its own scope") },
            )
        }

    @Test
    fun closingTwiceAfterAThrowingHookStillRunsTheHookOnlyOnce() =
        runTest(UnconfinedTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val closeable = ThrowingCloseable(backgroundScope, IllegalStateException("onClose blew up"))

            assertFailsWith<IllegalStateException> { closeable.close() }
            closeable.close()

            assertAll(
                {
                    assertEquals(
                        1,
                        closeable.hookInvocations,
                        "a throwing hook must not release the latch — that would reintroduce the double-onClose() " +
                            "#2305 removed",
                    )
                },
                { assertTrue(closeable.job.isCancelled, "the owned job stays cancelled across the second close()") },
            )
        }

    @Test
    fun aHookThatReturnsNormallyIsUnaffected() = runTest(UnconfinedTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val closeable = ThrowingCloseableWithoutThrow(backgroundScope)
        val child = closeable.launchChild()

        assertFalse(closeable.job.isCancelled, "rig: the job must be live before close()")
        closeable.close()

        assertAll(
            { assertEquals(1, closeable.hookInvocations, "the hook still runs exactly once") },
            { assertTrue(closeable.job.isCancelled, "close() cancels the owned job on the happy path") },
            { assertTrue(child.isCancelled, "child coroutines are cancelled on the happy path") },
        )
    }

    /** The control arm: identical to [ThrowingCloseable] but its hook returns normally. */
    private class ThrowingCloseableWithoutThrow(parentScope: CoroutineScope) : ScopedCloseable(parentScope) {
        private val invocations = atomic(0)
        val job: Job get() = ownJob
        val hookInvocations: Int get() = invocations.value

        fun launchChild(): Job = scope.launch { awaitCancellation() }

        override fun onClose() {
            invocations.incrementAndGet()
        }
    }
}
