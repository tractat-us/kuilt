package us.tractat.kuilt.heddle

import us.tractat.kuilt.math.Int128
import us.tractat.kuilt.math.Rounding

/**
 * The virtual time [this] gauge asserts at its fold for an edge of weight [w]:
 * `max(⌈floor · w · G⌉, ticks)` ticks, as a [Rational]. What a test that used to read
 * [Gauge.floor] reads now that a seat or a checkpoint writes [Gauge.ticks] instead (#3353).
 */
internal fun Gauge.assertedAt(w: Weight): Rational = ticksToVirtual(storedTicks(w).toLongExact(), w)

/** A ticks-only gauge asserting [units] of its own service at weight one, at [folded]. */
internal fun gaugeOfUnits(units: Long, folded: Long): Gauge = gaugeAt(Int128.of(units * TICKS_PER_UNIT), folded)

/**
 * [this] snapshot without the net-service clocks, their per-child marks or the departures read
 * against them (#3376): a control's memory, for a test that shows the clock a snapshot keeps is what
 * moves a returner.
 */
internal fun LocalHeddleSnapshot.withoutNetClocks(): LocalHeddleSnapshot = LocalHeddleSnapshot(
    epoch,
    ledger,
    AllocatorMemory(allocatorMemory.wakeOffsets, allocatorMemory.demandingObserved),
    totalSlots,
    open,
    lastApplied,
)

/**
 * The virtual time [v] on the grid of an edge of weight [w], narrowed: `⌈v · w · G⌉` ticks, or
 * `⌊…⌋` when [rounding] says so, [wideTicksAt] held at [w]'s [positionCeiling]. Test support only:
 * nothing in the engine narrows a value before the effective position a pick orders (#3353).
 */
internal fun ticksAt(v: Rational, w: Weight, rounding: Rounding = Rounding.CEILING): Long =
    saturated(wideTicksAt(v, w, rounding), w)
