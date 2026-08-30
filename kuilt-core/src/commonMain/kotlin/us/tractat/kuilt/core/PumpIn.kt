package us.tractat.kuilt.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Which half of a [pumpIn] pump failed — and, decisively, whether the pump is still running.
 */
public enum class PumpFailure {
    /**
     * The collector body threw on one item. **The pump survives** and consumes the next item; only this
     * item's work was lost.
     */
    ITEM,

    /**
     * The collected flow itself failed, which *ends* it. **The pump is over.**
     *
     * NOT YET IMPLEMENTED — this value exists so `PumpInTest`'s upstream arm compiles and runs RED. The
     * upstream guard lands in the next commit; see that arm for why a body-only helper is worse than no
     * helper.
     */
    UPSTREAM,
}

/**
 * Collect this flow in [scope] as a **long-lived pump** that a throw in [body] cannot kill, reporting the
 * throw through [onFailure].
 *
 * ### `ensureActive`, not `runCatchingCancellable`
 * `runCatchingCancellable` discriminates on **type**, and type cannot separate *"my job was cancelled"*
 * (must propagate) from *"a callee minted a `CancellationException` and threw it at me"* — which is what
 * a consumer writing `withTimeout(…) { … }` inside `Seam.sendTo` or `Loom.weave` does, while this job
 * stays perfectly alive. `currentCoroutineContext().ensureActive()` decides it at runtime.
 */
public fun <T> Flow<T>.pumpIn(
    scope: CoroutineScope,
    onFailure: (PumpFailure, Throwable) -> Unit,
    body: suspend (T) -> Unit,
): Job =
    onEach { value ->
        try {
            body(value)
        } catch (failure: Throwable) {
            // Genuinely our own cancellation → rethrow, so the pump stops as structured concurrency
            // intends; anything else — INCLUDING a `CancellationException` the callee minted itself — is
            // this item's failure. See the KDoc.
            currentCoroutineContext().ensureActive()
            reportPumpFailure(onFailure, PumpFailure.ITEM, failure)
        }
    }
        .launchIn(scope)

/** Hand a pump failure to a consumer callback, absorbing whatever it throws. */
private fun reportPumpFailure(
    onFailure: (PumpFailure, Throwable) -> Unit,
    phase: PumpFailure,
    failure: Throwable,
) {
    try {
        onFailure(phase, failure)
    } catch (_: Throwable) {
        // Deliberately total, `CancellationException` included: this runs inside the pump's own guard, so
        // a rethrow escapes it and kills the pump silently — the very defect the hook exists to report.
    }
}
