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

/**
 * [this] ledger with every gauge rewritten as the ticks-only gauge it reads as, at the same fold.
 * A ledger an older build wrote, with its positions in [Gauge.floor], then compares equal to the
 * same ledger this build writes, where a seat and a checkpoint store [Gauge.ticks] (#3353).
 */
internal fun EntitlementLedger.withGaugesAsTicks(): EntitlementLedger {
    val asTicks = allEdges().mapNotNull { edge ->
        val gauge = gauge(edge) ?: return@mapNotNull null
        val w = checkNotNull(record(edge)?.weight) { "a gauge on ${edge.value} with no single record" }
        edge to gaugeAt(gauge.storedTicks(w), gauge.folded)
    }.toMap()
    return withoutGauges().piece(EntitlementLedger.of(gauges = asTicks))
}

/** [this] snapshot with its ledger's gauges rewritten as [EntitlementLedger.withGaugesAsTicks] does. */
internal fun LocalHeddleSnapshot.withGaugesAsTicks(): LocalHeddleSnapshot =
    LocalHeddleSnapshot(epoch, ledger.withGaugesAsTicks(), allocatorMemory, totalSlots, open, lastApplied)
