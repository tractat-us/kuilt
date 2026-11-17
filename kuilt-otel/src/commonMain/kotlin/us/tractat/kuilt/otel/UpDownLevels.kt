package us.tractat.kuilt.otel

import us.tractat.kuilt.crdt.LWWMap
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.math.Int128

/**
 * Every replica's level in this integer up-down series, summed exactly, or `null` when the sum does
 * not fit in a `Long`, which an OTLP integer sum cannot carry. The levels are summed in 128 bits, so
 * no partial sum wraps on the way.
 */
internal fun LWWMap<ReplicaId, Long>.upDownTotalOrNull(): Long? =
    entries.values.fold(Int128.ZERO) { total, level -> total + level }.toLongOrNull()

/**
 * Every replica's level in this fractional up-down series, summed in replica order, so every
 * replica that holds the same entries reads the same total whatever order it merged them in. A
 * total that rounds past [Double.MAX_VALUE] is infinite, and OTLP/JSON carries it as such.
 */
internal fun LWWMap<ReplicaId, Double>.upDownTotal(): Double =
    entries.entries.sortedBy { it.key }.fold(0.0) { total, (_, level) -> total + level }
