@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.conformance

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.ScopedCloseable
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
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
 * [AutoCloseable.close] before the test body exits (or use `use {}`), or otherwise stop it by
 * cancelling the scope it was handed. An un-closed coordinator with a live anti-entropy loop can
 * freeze virtual time in `runTest` ([kotlinx.coroutines.test.UncompletedCoroutinesError]).
 *
 * ## What this suite asserts, and what it leaves to the binding
 *
 * Two observables, deliberately distinct. [backgroundJobsOf] is what the binding *reports* it
 * launched; the scope handed to [create] carries, in its children, what the instance *structurally*
 * owns. Every property here is a quantifier over one of the two, and the pair is what keeps a
 * quantifier from being satisfied by absence — see [backgroundJobsOf] for the floor that makes the
 * first one non-vacuous and [closeCancelsTheJobTheInstanceOwnsInTheGivenScope] for the second.
 *
 * **What it still does not express**, stated so a reader does not infer coverage from a green run:
 * nothing here closes a coordinator that is *mid-operation* (every instance is idle at the moment
 * it is closed), nothing calls a method *after* [AutoCloseable.close] — [ScopedCloseable] does not
 * say what that does, so every subclass currently invents an answer — and nothing calls
 * [AutoCloseable.close] from two threads at once, which is single-threaded-harness-unreachable and
 * lives instead in `:kuilt-core`'s real-threaded `ScopedCloseableCloseOnceCapabilityConcurrencyTest`.
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
     * Create a fresh, un-closed instance of the coordinator under test, launching every background
     * coroutine it owns into [scope] and putting **nothing else** there.
     *
     * The second clause is load-bearing rather than tidiness.
     * [closeCancelsTheJobTheInstanceOwnsInTheGivenScope] and
     * [parentScopeCancellationStopsBackgroundJobs] read [scope]'s children as the structural record
     * of what the instance owns — that is the only handle on [ScopedCloseable.ownJob] a different
     * module has, since it is `protected`. A fixture coroutine parked in the same scope is
     * indistinguishable from one the instance leaked, and would be asserted against as though it
     * were. Give a fake that needs a scope of its own a separate one.
     */
    protected abstract fun create(scope: CoroutineScope): ScopedCloseable

    /**
     * Return the background [Job]s to assert active/inactive state on. These are
     * the concrete jobs launched during construction (the jobs that run the coordinator's
     * work). For single-job coordinators return a one-element list; for multi-job
     * coordinators return all owned jobs.
     *
     * ## It must be non-empty, and that is checked rather than assumed
     *
     * Every property that reads this list is a quantifier over it — `all { it.isActive }`,
     * `none { it.isActive }` — and **every one of them is true of `emptyList()`**. A binding whose
     * hook returned no jobs would pass this entire suite having asserted nothing, and would do so
     * silently, because a green quantifier over an empty set looks exactly like a green one over a
     * full set. So each property asserts its own precondition first: an empty list **fails**.
     *
     * There is deliberately no "this coordinator has no observable background work" opt-out. An
     * opt-out only moves the vacuity one level up, where it is harder to see; and a coordinator with
     * nothing to stop is not a thing a *lifecycle* suite can say anything about, so the right answer
     * for such a type is not to subclass this suite at all.
     *
     * ## What the floor cannot detect
     *
     * It is a floor of **one**, so *under-reporting* still passes here: a coordinator that launches
     * five coroutines and hands back one of them satisfies every property in this file. Nothing in
     * the [ScopedCloseable] contract makes the full set reachable from outside the instance, so the
     * suite cannot compare this list against a ground truth.
     *
     * [closeCancelsTheJobTheInstanceOwnsInTheGivenScope] narrows the gap from the other side, on the
     * failure that matters: it asserts against the children of the scope [create] was handed rather
     * than against this list, so a coroutine that escaped [ScopedCloseable.scope] into the caller's
     * scope is caught even when it is not reported here. What stays invisible is an unreported
     * coroutine that really is a proper child of [ScopedCloseable.ownJob] — and that one is also
     * harmless to these properties, since [AutoCloseable.close] and parent-scope cancellation both
     * stop it whether it was listed or not.
     */
    protected abstract fun backgroundJobsOf(instance: ScopedCloseable): List<Job>

    /**
     * [backgroundJobsOf], with its non-emptiness asserted at every call site that depends on it.
     *
     * Written as a helper rather than an `init`-time check because the hook takes the instance: the
     * earliest moment the list exists is inside a test body, and the precondition belongs to each
     * property that quantifies over it.
     */
    private fun backgroundJobsOrFail(instance: ScopedCloseable): List<Job> {
        val jobs = backgroundJobsOf(instance)
        assertTrue(
            jobs.isNotEmpty(),
            "backgroundJobsOf() returned no jobs. Every assertion in this suite is a quantifier " +
                "over that list and all of them hold vacuously on an empty one, so a binding that " +
                "reports nothing would pass the suite having asserted nothing. Report the jobs the " +
                "coordinator launched, or do not bind it to this suite.",
        )
        return jobs
    }

    @Test
    public fun backgroundJobsActiveBeforeClose(): TestResult = runTest(
        UnconfinedTestDispatcher(),
        timeout = TEST_WEDGE_BACKSTOP,
    ) {
        val instance = create(backgroundScope)
        try {
            assertTrue(
                backgroundJobsOrFail(instance).all { it.isActive },
                "All background jobs should be active before close()",
            )
        } finally {
            instance.close()
        }
    }

    @Test
    public fun closeStopsAllBackgroundJobs(): TestResult = runTest(
        UnconfinedTestDispatcher(),
        timeout = TEST_WEDGE_BACKSTOP,
    ) {
        val instance = create(backgroundScope)
        val jobs = backgroundJobsOrFail(instance)
        instance.close()
        assertFalse(
            jobs.any { it.isActive },
            "No background job should remain active after close()",
        )
    }

    @Test
    public fun closeIsIdempotent(): TestResult = runTest(
        UnconfinedTestDispatcher(),
        timeout = TEST_WEDGE_BACKSTOP,
    ) {
        val instance = create(backgroundScope)
        val jobs = backgroundJobsOrFail(instance)
        instance.close()
        instance.close() // must not throw
        assertFalse(
            jobs.any { it.isActive },
            "Jobs should remain inactive after double-close()",
        )
    }

    /**
     * [AutoCloseable.close] cancels the job the instance owns **inside the scope it was given** —
     * the observable form of "[ScopedCloseable.ownJob] is cancelled on close".
     *
     * The suite cannot name [ScopedCloseable.ownJob]: it is `protected`, so a different module has
     * no handle on it. What it can name is the set of children the instance put into the scope
     * [create] was handed, which for a conforming [ScopedCloseable] is exactly that job — the
     * constructor makes it a structural child of the parent scope's [Job] and launches everything
     * else beneath it.
     *
     * **Why this is not [closeStopsAllBackgroundJobs] again.** That property quantifies over what
     * the binding *reports*; this one quantifies over what the instance *structurally owns*. The gap
     * between them is where a coroutine launched into the caller's scope instead of
     * [ScopedCloseable.scope] hides: [AutoCloseable.close] cancels only [ScopedCloseable.ownJob], so
     * such a coroutine survives the close, and it survives silently as long as
     * [backgroundJobsOf] does not list it.
     *
     * **The children are captured before the close, and that is the whole reason this test is not
     * itself vacuous.** A job leaves its parent's [Job.children] once it completes, so reading them
     * *after* the cancel would very often be a `none { }` over an empty sequence — green by absence,
     * which is the defect this suite was audited for. Captured first and asserted non-empty, the
     * quantifier has something to quantify over.
     *
     * **The capture is a snapshot, and what that does and does not expose it to.** It is *not* a
     * race: this suite closes on a single-threaded virtual-time dispatcher, from the test body,
     * after the capture — there is no second closer, so the lock-free [Job.children] traversal has
     * nothing mutating underneath it. (A genuinely concurrent `close()` is a different property
     * with a different mechanism, and it lives in `:kuilt-core`'s real-threaded
     * `ScopedCloseableCloseOnceCapabilityConcurrencyTest`, which cannot be a subclass here —
     * `:kuilt-conformance` depends on `:kuilt-core`, so the reverse edge would be a cycle.)
     *
     * What the snapshot genuinely cannot see is a coroutine attached to the caller's scope
     * **after** construction — a coordinator that launches lazily on first use rather than in its
     * constructor escapes this assertion, because there was nothing to capture when the capture
     * happened. That residual is inherent to the shape rather than an oversight of it: capturing
     * *after* the close instead is strictly worse, since it reintroduces exactly the green-by-
     * absence hole this test exists to close.
     */
    @Test
    public fun closeCancelsTheJobTheInstanceOwnsInTheGivenScope(): TestResult = runTest(
        UnconfinedTestDispatcher(),
        timeout = TEST_WEDGE_BACKSTOP,
    ) {
        val parentJob = Job()
        val parentScope = CoroutineScope(coroutineContext + parentJob)
        val instance = create(parentScope)
        try {
            val owned = parentJob.children.toList()
            assertTrue(
                owned.isNotEmpty(),
                "create() must hand back an instance that owns a child job of the scope it was " +
                    "given. An empty child set means the instance's own job is not parented to " +
                    "that scope — either it was constructed with a different scope, or its job is " +
                    "parentless — and neither close() nor parent cancellation can then be observed.",
            )

            instance.close()

            assertFalse(
                owned.any { it.isActive },
                "close() must cancel every job the instance owns in the scope it was given; a " +
                    "still-active child is a coroutine that escaped ScopedCloseable.ownJob",
            )
        } finally {
            instance.close()
            parentJob.cancel()
        }
    }

    /**
     * Cancelling the scope [create] was handed stops the coordinator, with **no** call to
     * [AutoCloseable.close].
     *
     * [ScopedCloseable.ownJob] is documented as a structural child of the parent scope's [Job], so
     * cancellation propagates down and a caller who simply drops its scope leaks nothing. The
     * failure this catches is a coordinator that owns a *parentless* job and relies on its own
     * `close()` to stop it: every other property in this file passes for such a type, because they
     * all reach the coordinator through `close()`. `MuxServerLoom` shipped in exactly that shape
     * (#1366), and every module that had noticed the gap was hand-rolling this test locally —
     * `:kuilt-quilter` against a `Quilter`, `:kuilt-core` against a `MuxServerLoom` — which is why
     * it belongs here rather than in a binding.
     */
    @Test
    public fun parentScopeCancellationStopsBackgroundJobs(): TestResult = runTest(
        UnconfinedTestDispatcher(),
        timeout = TEST_WEDGE_BACKSTOP,
    ) {
        val parentJob = Job()
        val parentScope = CoroutineScope(coroutineContext + parentJob)
        val instance = create(parentScope)
        try {
            val jobs = backgroundJobsOrFail(instance)
            assertTrue(
                jobs.all { it.isActive },
                "All background jobs should be active before the parent scope is cancelled",
            )

            parentJob.cancel() // the caller's scope dies; nobody calls close()

            assertFalse(
                jobs.any { it.isActive },
                "Parent-scope cancellation must stop every background job without a close(): " +
                    "ScopedCloseable.ownJob is a structural child of the scope handed to create()",
            )
        } finally {
            // Both of these, and in this order. A binding that FAILS this property has, by
            // definition, a coordinator that its scope's cancellation does not stop — so the
            // `parentJob.cancel()` that would normally clean up is exactly the thing that does
            // not work here, and without the close() the failing binding leaks a live coordinator
            // into `runTest` teardown. Measured: a timer-driven coordinator left running that way
            // spins the test scheduler and the run WEDGES instead of reporting the failure, which
            // is the least useful shape a conformance red can take.
            instance.close()
            parentJob.cancel()
        }
    }
}
