package us.tractat.kuilt.otel.logging

import kotlin.native.concurrent.ThreadLocal

@ThreadLocal
private var slot: ActiveTrace? = null

internal actual fun currentActiveTrace(): ActiveTrace? = slot

internal actual fun setActiveTrace(value: ActiveTrace?): ActiveTrace? {
    val prior = slot
    slot = value
    return prior
}
