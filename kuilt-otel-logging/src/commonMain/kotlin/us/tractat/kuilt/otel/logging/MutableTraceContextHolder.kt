package us.tractat.kuilt.otel.logging

import kotlinx.atomicfu.atomic

/**
 * A directly-settable [TraceContextProvider] — the escape hatch for apps or tests
 * that cannot scope their tracing as coroutines (prefer [withActiveTrace] +
 * [CoroutineContextTraceProvider], which is the primary API).
 *
 * The current trace is a single process-global value, guarded by an atomic ref. It
 * has **no execution locality**: on a multi-threaded runtime a concurrent logger on
 * another thread sees whatever was [set] last, so use this only where a single
 * logical trace is active at a time (e.g. a single-threaded wasmJs app, or a test).
 */
public class MutableTraceContextHolder(initial: ActiveTrace? = null) : TraceContextProvider {
    private val ref = atomic(initial)

    /** Set the current trace (or `null` to clear it). */
    public fun set(trace: ActiveTrace?) {
        ref.value = trace
    }

    override fun current(): ActiveTrace? = ref.value
}
