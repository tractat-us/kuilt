package us.tractat.kuilt.otel.logging

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope

/**
 * iOS / macOS: no Kotlin/Native primitive mirrors a thread-local across coroutine
 * dispatches, so the slot is set imperatively and the context reaches only a line
 * logged **synchronously within the block** (see [withLogContext]'s platform note).
 *
 * The restore is **identity-guarded**: it writes the prior context back only if this
 * thread's slot still holds the exact merged instance we set. If [block] suspended
 * and resumed on a different worker thread, that thread's slot is not ours, so we
 * leave it untouched — the restore can therefore never overwrite (corrupt) an
 * unrelated scope's context. `Map.plus` always allocates, so the merged map is a
 * fresh instance and the identity check is sound even when two scopes bind equal
 * attributes.
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
