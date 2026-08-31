package us.tractat.kuilt.otel.logging

/**
 * The execution-local slot holding the log context bound to the current thread of
 * execution — the attributes [withLogContext] put there.
 *
 * Read synchronously by `LogCapture.resolveAtEdge` at the capture edge, which is a
 * **non-`suspend`** framework callback (`CapturingAppender.log`) and so cannot read
 * the coroutine context directly. That is the whole reason this is an
 * execution-local slot rather than a plain `CoroutineContext.Element`: on
 * JVM/Android [LogContextElement] mirrors the element into the slot on every
 * dispatch, so the edge can read it without suspending.
 *
 * Backed by a `ThreadLocal` on JVM/Android, a Kotlin/Native `@ThreadLocal` on Apple,
 * and a plain module-level var on single-threaded wasmJs — the same three backings as
 * the sibling [currentActiveTrace] slot.
 *
 * Empty means "no context bound", and is the value outside any [withLogContext].
 */
internal expect fun currentLogContext(): Map<String, String>

/** Set the slot, returning the prior value (so callers can save/restore). */
internal expect fun setLogContext(value: Map<String, String>): Map<String, String>
