package us.tractat.kuilt.core

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
 * [close] is **idempotent** — safe to call multiple times. On the first call it invokes
 * [onClose] (subclass hook for cleanup work), then cancels [ownJob], which cancels all
 * child coroutines. Subsequent calls are no-ops.
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

    private var _closed = false

    /** `true` after [close] has been called. */
    protected val closed: Boolean get() = _closed

    /**
     * Cancels all background coroutines owned by this instance. Idempotent — safe to
     * call multiple times.
     *
     * Calls [onClose] before cancelling [ownJob], giving subclasses a chance to perform
     * synchronous cleanup (e.g. clearing retry maps, releasing resources).
     *
     * The [parentScope] passed at construction is **not** cancelled — only [ownJob] and
     * its children are stopped.
     */
    final override fun close() {
        if (_closed) return
        _closed = true
        onClose()
        ownJob.cancel()
    }

    /**
     * Called once by [close] before [ownJob] is cancelled. Override to perform synchronous
     * cleanup — clearing caches, releasing locks, logging — that must happen before the
     * coroutines stop.
     *
     * This method is called at most once and always before [ownJob] is cancelled.
     */
    protected open fun onClose() {}
}
