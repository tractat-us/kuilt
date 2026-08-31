package us.tractat.kuilt.otel.logging

import kotlin.native.concurrent.ThreadLocal

@ThreadLocal
private var slot: Map<String, String> = emptyMap()

internal actual fun currentLogContext(): Map<String, String> = slot

internal actual fun setLogContext(value: Map<String, String>): Map<String, String> {
    val prior = slot
    slot = value
    return prior
}
