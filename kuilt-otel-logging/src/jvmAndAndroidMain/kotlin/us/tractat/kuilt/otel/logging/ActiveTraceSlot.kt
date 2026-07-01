package us.tractat.kuilt.otel.logging

private val slot = ThreadLocal<ActiveTrace?>()

internal actual fun currentActiveTrace(): ActiveTrace? = slot.get()

internal actual fun setActiveTrace(value: ActiveTrace?): ActiveTrace? {
    val prior = slot.get()
    if (value == null) slot.remove() else slot.set(value)
    return prior
}
