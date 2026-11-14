package us.tractat.kuilt.heddle

import us.tractat.kuilt.math.Int128

/**
 * The virtual time [this] gauge asserts at its fold for an edge of weight [w]:
 * `max(⌈floor · w · G⌉, ticks)` ticks, as a [Rational]. What a test that used to read
 * [Gauge.floor] reads now that a seat or a checkpoint writes [Gauge.ticks] instead (#3353).
 */
internal fun Gauge.assertedAt(w: Weight): Rational = ticksToVirtual(storedTicks(w).toLongExact(), w)

/** A ticks-only gauge asserting [units] of its own service at weight one, at [folded]. */
internal fun gaugeOfUnits(units: Long, folded: Long): Gauge = gaugeAt(Int128.of(units * TICKS_PER_UNIT), folded)
