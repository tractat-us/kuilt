package us.tractat.kuilt.otel.logging

import kotlinx.coroutines.ThreadContextElement
import kotlin.coroutines.CoroutineContext

/**
 * The JVM/Android mechanism behind [withLogContext]: a thread-context element that
 * mirrors [attributes] into the [execution-local slot][currentLogContext] on each
 * coroutine dispatch and restores the prior value on suspend.
 *
 * This is why the JVM path survives thread hops, propagates to child coroutines, and
 * — the property #1659 is actually about — keeps two **interleaved** scopes apart:
 * the slot is re-established on every resumption, so whichever scope is running is
 * the one the capture edge reads. `kotlinx.coroutines.ThreadContextElement` is a
 * JVM-only primitive; the native and wasmJs [withLogContext] actuals set the slot
 * imperatively instead and reach only a synchronously-logged line.
 *
 * [attributes] is already merged with the enclosing binding by [withLogContext], so
 * this element carries the full context in force, not a delta.
 */
internal class LogContextElement(val attributes: Map<String, String>) : ThreadContextElement<Map<String, String>> {
    companion object Key : CoroutineContext.Key<LogContextElement>

    override val key: CoroutineContext.Key<LogContextElement> get() = Key

    override fun updateThreadContext(context: CoroutineContext): Map<String, String> = setLogContext(attributes)

    override fun restoreThreadContext(context: CoroutineContext, oldState: Map<String, String>) {
        setLogContext(oldState)
    }
}
