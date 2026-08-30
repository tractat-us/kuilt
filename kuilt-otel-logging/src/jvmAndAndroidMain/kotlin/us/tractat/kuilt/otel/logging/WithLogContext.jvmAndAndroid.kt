package us.tractat.kuilt.otel.logging

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/**
 * JVM/Android: propagate the context with [LogContextElement] — hop-safe, inherited
 * by child coroutines, and re-established on every dispatch so interleaved scopes
 * stay apart.
 *
 * The enclosing binding is read from the **coroutine context** rather than the
 * thread-local slot: the element is the authoritative carrier here, and reading it
 * is correct even at a point where the slot has not been established on this thread.
 */
public actual suspend fun <T> withLogContext(
    attributes: Map<String, String>,
    block: suspend CoroutineScope.() -> T,
): T {
    val inherited = currentCoroutineContext()[LogContextElement]?.attributes ?: emptyMap()
    // Merged here, so the element always carries the full context in force. `plus`
    // lets the new attributes win — "narrower scope wins" (see withLogContext).
    return withContext(LogContextElement(inherited + attributes), block)
}
