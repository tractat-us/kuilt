package us.tractat.kuilt.otel.logging

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope

/**
 * wasmJs: single-threaded and with no `ThreadContextElement`, so the slot is set
 * imperatively around [block] — reliable for a line logged synchronously within it
 * (see [withLogContext]'s platform note).
 *
 * Single-threaded does **not** mean single-scope. Two concurrent scopes share the one
 * slot here, so — exactly as on Apple — [currentLogContext] means "whatever was last
 * set", a scope resuming while a sibling is mid-block reads the sibling's attributes,
 * and the merge below inherits the sibling's keys (#2569).
 *
 * The restore is identity-guarded for the same reason as the Apple actual: two
 * scopes interleaving on the one thread must not have their exits overwrite each
 * other's binding.
 */
public actual suspend fun <T> withLogContext(
    attributes: Map<String, String>,
    block: suspend CoroutineScope.() -> T,
): T {
    val merged = currentLogContext() + attributes
    val prev = setLogContext(merged)
    try {
        return coroutineScope(block)
    } finally {
        if (currentLogContext() === merged) setLogContext(prev)
    }
}
