package us.tractat.kuilt.core

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

/**
 * Base for coordinators that launch coroutines into an owned child scope and implement
 * [AutoCloseable].
 *
 * ## Ownership model
 *
 * The constructor creates a [SupervisorJob] that is a **child** of the parent scope's job,
 * so:
 * - A crashing child coroutine does not propagate to the caller's scope (supervisor boundary).
 * - Parent-scope cancellation still propagates down — [ownJob] is a structural child.
 * - Every `scope.launch` inside this class is automatically a child of [ownJob]; no manual
 *   job list is needed. Job-list drift (a launch that escapes [close]) is structurally impossible.
 *
 * ## Close contract
 *
 * [close] is **idempotent and thread-safe** — safe to call multiple times, from any thread,
 * concurrently. Exactly one call wins the guard; it invokes [onClose] (subclass hook for cleanup
 * work), then cancels [ownJob], which cancels all child coroutines. Every other call is a no-op.
 *
 * The guard is a **once barrier, not a completion barrier**: a losing concurrent caller returns
 * immediately and may do so while the winner is still inside [onClose]. Returning from [close]
 * therefore means "cleanup has been *initiated* exactly once", never "everything has stopped" —
 * which is already true of the winning caller too, since [Job.cancel] is asynchronous and child
 * coroutines may still be unwinding after it returns. Callers that need quiescence must join:
 * subclasses can expose [ownJob] for that. Blocking a loser until the winner finishes was
 * considered and rejected — [close] is a non-`suspend` function on every target (including
 * single-threaded wasmJs), so a completion barrier would have to block a thread, and this repo's
 * own [onClose] bodies close *other* [ScopedCloseable]s from inside the hook, which is exactly the
 * shape that turns a blocking close into a lock-ordering deadlock.
 *
 * Subclasses must launch all background coroutines into [scope] (not into the caller's
 * scope). Launching into the caller's scope bypasses the ownership invariant.
 *
 * @param parentScope the caller's [CoroutineScope]. [ownJob] becomes a child of its [Job].
 */
public abstract class ScopedCloseable(parentScope: CoroutineScope) : AutoCloseable {

    /**
     * Owned child job. Cancel this to stop all coroutines launched into [scope].
     * Exposed to subclasses for observability in tests.
     */
    protected val ownJob: Job = SupervisorJob(parentScope.coroutineContext[Job])

    /**
     * The coordinator's own scope. Launch all background coroutines here.
     */
    protected val scope: CoroutineScope = CoroutineScope(parentScope.coroutineContext + ownJob)

    /**
     * The close-once latch. Atomic, not a plain `var`: [close] is a non-`suspend` function
     * callable from any thread, so a check-then-set guard lets two callers both observe `false`
     * and both run [onClose] — breaking its at-most-once contract. Reads of [closed] from other
     * threads need the same primitive to be visible at all (#2305).
     */
    private val _closed = atomic(false)

    /** `true` after [close] has been called. */
    protected val closed: Boolean get() = _closed.value

    /**
     * Cancels all background coroutines owned by this instance. Idempotent and thread-safe —
     * safe to call multiple times, from any thread, concurrently.
     *
     * Exactly one caller wins the latch and calls [onClose] before cancelling [ownJob], giving
     * subclasses a chance to perform synchronous cleanup (e.g. clearing retry maps, releasing
     * resources). Every other caller returns immediately, without waiting for the winner — see
     * the class KDoc on why this is a once barrier rather than a completion barrier.
     *
     * The [parentScope] passed at construction is **not** cancelled — only [ownJob] and
     * its children are stopped.
     *
     * A throwing [onClose] does **not** strand the instance: [ownJob] is cancelled from a `finally`,
     * so the coroutine tree always dies, and the throwable then propagates to the caller (#2330).
     * The latch stays claimed either way — releasing it on a throw would make the hook re-entrant,
     * which is exactly the at-most-once contract [onClose] states. So a failed hook leaves an
     * instance that is *closed* and whose failure was reported, never one that is neither open nor
     * closed with no retry.
     */
    final override fun close() {
        if (!_closed.compareAndSet(expect = false, update = true)) return
        try {
            onClose()
        } finally {
            // In `finally`, not after: the latch is already claimed, so a hook that throws would
            // otherwise leak every coroutine under `ownJob` with no second call able to get past
            // the guard and finish the job (#2330). Ordering is unchanged on the happy path —
            // `finally` still runs after `onClose()` returns.
            ownJob.cancel()
        }
    }

    /**
     * Called once by [close] before [ownJob] is cancelled. Override to perform synchronous
     * cleanup — clearing caches, releasing locks, logging — that must happen before the
     * coroutines stop.
     *
     * This method is called at most once — even if several threads call [close] at the same
     * instant — and always before [ownJob] is cancelled.
     *
     * Throwing from here is reported, not swallowed: the throwable propagates out of [close] to its
     * caller. [ownJob] is cancelled regardless, so a hook that fails partway still stops every
     * coroutine this instance owns. What a partial hook leaves behind is the subclass's own problem
     * — a body that tears down several things wants one `try`/`catch` per item if it must finish
     * them all, since the first throw ends the hook.
     */
    protected open fun onClose() {}
}
