package us.tractat.kuilt.otel

import kotlin.jvm.JvmInline

/**
 * A typed key for [DurableStore] entries.
 *
 * Wraps a raw string name so that callers cannot accidentally mix up keys
 * by passing a bare `String` literal in the wrong order.
 */
@JvmInline
public value class StoreKey(public val name: String)
