package us.tractat.kuilt.otel.logging

/**
 * The execution-local slot holding the trace active on the current thread of
 * execution. [ActiveTraceElement] mirrors an [ActiveTrace] into it as a coroutine is
 * dispatched; [CoroutineContextTraceProvider.current] reads it synchronously at the
 * capture edge. Backed by a `ThreadLocal` on JVM/Android, a Kotlin/Native
 * `@ThreadLocal` on Apple, and a plain module-level var on single-threaded wasmJs.
 */
internal expect fun currentActiveTrace(): ActiveTrace?

/** Set the slot, returning the prior value (so callers can save/restore). */
internal expect fun setActiveTrace(value: ActiveTrace?): ActiveTrace?
