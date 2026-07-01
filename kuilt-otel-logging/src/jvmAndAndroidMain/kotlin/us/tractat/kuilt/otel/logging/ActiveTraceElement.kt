package us.tractat.kuilt.otel.logging

import kotlinx.coroutines.ThreadContextElement
import kotlin.coroutines.CoroutineContext

/**
 * The JVM/Android mechanism behind [withActiveTrace]: a thread-context element that
 * mirrors [trace] into the [execution-local slot][currentActiveTrace] on each
 * coroutine dispatch and restores the prior value on suspend.
 *
 * This is why the JVM path survives thread hops and propagates to child coroutines —
 * `kotlinx.coroutines.ThreadContextElement` is a JVM-only primitive; the native and
 * wasmJs [withActiveTrace] actuals set the slot imperatively instead.
 */
internal class ActiveTraceElement(private val trace: ActiveTrace?) : ThreadContextElement<ActiveTrace?> {
    companion object Key : CoroutineContext.Key<ActiveTraceElement>

    override val key: CoroutineContext.Key<ActiveTraceElement> get() = Key

    override fun updateThreadContext(context: CoroutineContext): ActiveTrace? = setActiveTrace(trace)

    override fun restoreThreadContext(context: CoroutineContext, oldState: ActiveTrace?) {
        setActiveTrace(oldState)
    }
}
