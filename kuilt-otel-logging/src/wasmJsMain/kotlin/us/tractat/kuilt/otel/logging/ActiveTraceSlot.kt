package us.tractat.kuilt.otel.logging

// Single-threaded runtime — a plain module-level var is correct; there is no other
// thread to observe a partially-updated slot.
private var slot: ActiveTrace? = null

internal actual fun currentActiveTrace(): ActiveTrace? = slot

internal actual fun setActiveTrace(value: ActiveTrace?): ActiveTrace? {
    val prior = slot
    slot = value
    return prior
}
