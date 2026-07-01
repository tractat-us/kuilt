package us.tractat.kuilt.otel.logging

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope

/**
 * wasmJs: single-threaded, so setting the slot around [block] and restoring the prior
 * value on exit is fully reliable — there is no other thread to observe it.
 */
public actual suspend fun <T> withActiveTrace(trace: ActiveTrace, block: suspend CoroutineScope.() -> T): T {
    val prev = setActiveTrace(trace)
    try {
        return coroutineScope(block)
    } finally {
        setActiveTrace(prev)
    }
}
