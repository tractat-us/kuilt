package us.tractat.kuilt.otel.logging

private val slot = ThreadLocal<Map<String, String>>()

internal actual fun currentLogContext(): Map<String, String> = slot.get() ?: emptyMap()

internal actual fun setLogContext(value: Map<String, String>): Map<String, String> {
    val prior = slot.get() ?: emptyMap()
    // remove() rather than set(emptyMap()) so an unbound thread holds no entry at
    // all — a ThreadLocal left set on a pooled thread outlives the scope that set it.
    if (value.isEmpty()) slot.remove() else slot.set(value)
    return prior
}
