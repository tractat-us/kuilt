package us.tractat.kuilt.otel.logging

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext

/**
 * JVM/Android: propagate the trace with [ActiveTraceElement] — hop-safe and inherited
 * by child coroutines, restoring the prior trace on exit.
 */
public actual suspend fun <T> withActiveTrace(trace: ActiveTrace, block: suspend CoroutineScope.() -> T): T =
    withContext(ActiveTraceElement(trace), block)
