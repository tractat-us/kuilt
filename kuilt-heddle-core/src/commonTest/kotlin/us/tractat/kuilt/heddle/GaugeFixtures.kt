package us.tractat.kuilt.heddle

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.math.Int128
import us.tractat.kuilt.math.Rounding

/** The virtual time [this] gauge asserts at its fold for an edge of weight [w], as a [Rational]. */
internal fun Gauge.assertedAt(w: Weight): Rational = ticksToVirtual(ticks.toLongExact(), w)

/** A gauge asserting [units] of its own service at weight one, at [folded]. */
internal fun gaugeOfUnits(units: Long, folded: Long): Gauge = gaugeAt(Int128.of(units * TICKS_PER_UNIT), folded)

/**
 * A gauge asserting the virtual time [v] for an edge of weight [w], at [folded]: `⌈v · w · G⌉`
 * ticks, and zero for a negative [v]. The weight is required because a position in ticks is on
 * one edge's grid: the same [v] is a different count of ticks at another weight.
 */
internal fun gaugeOfVirtual(v: Rational, folded: Long, w: Weight): Gauge =
    gaugeAt(maxOf(wideTicksAt(v, w), Int128.ZERO), folded)

/**
 * [json] with a v0.7.4 `floor` of [floor] units put into every gauge in it, ahead of its
 * `folded`: a non-zero one is what [GaugeSerializer] refuses. Only a gauge has a `folded` key.
 */
internal fun withLegacyFloor(json: String, floor: Long = 70L): String {
    val folded = "\"folded\":"
    check(folded in json) { "the rig: no gauge to put a floor into in $json" }
    return json.replace(folded, "\"floor\":{\"numerator\":$floor,\"denominator\":1},$folded")
}

/**
 * [cbor] with a v0.7.4 `floor` of [floor] units put into every gauge in it: the `floor` key and
 * the value [RationalSerializer] wrote, ahead of the gauge's first key, `folded`. The map header
 * just before it is the gauge's own, since `folded` is always written first; a definite-length one
 * is counted up by the entry added, an indefinite-length one needs nothing.
 */
internal fun withLegacyFloor(cbor: ByteArray, floor: Long = 70L): ByteArray {
    val folded = Cbor.encodeToByteArray(String.serializer(), "folded")
    val entry = Cbor.encodeToByteArray(String.serializer(), "floor") +
        Cbor.encodeToByteArray(RationalSerializer, Rational.of(floor))
    val out = ArrayList<Byte>(cbor.size + 32)
    var spliced = 0
    var i = 0
    while (i < cbor.size) {
        if (i > 0 && cbor.startsWithAt(folded, i)) {
            val header = out.removeAt(out.lastIndex).toInt() and 0xFF
            out += when (header) {
                INDEFINITE_MAP -> header.toByte()
                in SMALL_MAP_MIN until SMALL_MAP_MAX -> (header + 1).toByte()
                else -> error("the rig: a gauge map header ${header.toString(16)} this splice does not handle")
            }
            out += entry.toList()
            spliced++
        }
        out += cbor[i]
        i++
    }
    check(spliced > 0) { "the rig: no gauge to put a floor into" }
    return out.toByteArray()
}

private const val INDEFINITE_MAP = 0xBF
private const val SMALL_MAP_MIN = 0xA0
private const val SMALL_MAP_MAX = 0xB7

private fun ByteArray.startsWithAt(prefix: ByteArray, at: Int): Boolean =
    at + prefix.size <= size && prefix.indices.all { this[at + it] == prefix[it] }

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
