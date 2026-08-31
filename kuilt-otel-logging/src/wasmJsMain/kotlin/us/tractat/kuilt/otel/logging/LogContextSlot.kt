package us.tractat.kuilt.otel.logging

// Single-threaded runtime — a plain module-level var is correct; there is no other
// thread to observe a partially-updated slot.
private var slot: Map<String, String> = emptyMap()

internal actual fun currentLogContext(): Map<String, String> = slot

internal actual fun setLogContext(value: Map<String, String>): Map<String, String> {
    val prior = slot
    slot = value
    return prior
}
