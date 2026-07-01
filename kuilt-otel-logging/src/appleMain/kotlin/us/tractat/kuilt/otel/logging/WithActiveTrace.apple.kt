package us.tractat.kuilt.otel.logging

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope

/**
 * iOS / macOS: no Kotlin/Native primitive mirrors a thread-local across coroutine
 * dispatches, so the slot is set imperatively and the trace reaches only a log
 * emitted **synchronously within the span** (see [withActiveTrace]'s platform note).
 *
 * The restore is **identity-guarded**: it writes the prior value back only if this
 * thread's slot still holds the exact [trace] instance we set. If [block] suspended
 * and resumed on a different worker thread, that thread's slot is not our `trace`, so
 * we leave it untouched — the restore can therefore never overwrite (corrupt) an
 * unrelated coroutine's trace. The setting thread's slot is left as-is until the next
 * `withActiveTrace` on it supersedes it.
 */
public actual suspend fun <T> withActiveTrace(trace: ActiveTrace, block: suspend CoroutineScope.() -> T): T {
    val prev = setActiveTrace(trace)
    try {
        return coroutineScope(block)
    } finally {
        if (currentActiveTrace() === trace) setActiveTrace(prev)
    }
}
