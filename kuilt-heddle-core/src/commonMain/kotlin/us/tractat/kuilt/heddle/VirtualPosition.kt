package us.tractat.kuilt.heddle

import us.tractat.kuilt.math.Int128
import us.tractat.kuilt.math.Rounding
import us.tractat.kuilt.math.mulDiv
import us.tractat.kuilt.math.timesWide

/**
 * How finely an edge's virtual position is kept: [TICKS_PER_UNIT] ticks per service unit of the
 * edge's own service (design §7.1).
 *
 * ## Why positions are whole ticks (#3313, #3231)
 *
 * The scheduler used to keep each virtual time as an exact [Rational]. A joiner — a newborn seated
 * at the front, a waker clamped up to it — stored that front, a weighted mean whose denominator
 * carries `Σw` of the set it was taken over. The next front averaged that value in again, so every
 * join could multiply a stored denominator by another `Σw`, and a pick overflowed `Long` within
 * tens of picks once the waiting set changed between them.
 *
 * Now an edge's position is a `Long` count of ticks of its own service, and its virtual time is
 * `position / (G · w)` with `G =` [TICKS_PER_UNIT]. Service, returns and sleeper credit are whole
 * units, so they move a position by whole ticks, exactly. **Only two operations round, and both
 * round up**: seating a newborn and clamping a waker, each landing at `⌈V · w · G⌉` on the joiner's
 * own grid. Up is the side §10.5 allows, a sliver of penalty and never credit, and the sliver is
 * under one tick. A gauge checkpoint adds whole ticks and so never rounds, which is why rounding
 * cannot compound across checkpoints the way #1752 warned a fixed-point floor would.
 *
 * Across edges nothing is divided: a front stays an unreduced sum of positions over a sum of
 * weights ([VirtualTime]), and comparing two edges cross-multiplies in 128 bits.
 *
 * ## Why 2⁻⁴
 *
 * Each bit of grid comes out of the `Long` ceiling on a position, and the design review measured
 * the knee at 2⁻⁴: the churn fairness error read 0.0170 against 0.0167 exact, the backlogged
 * fingerprints held, and 4 bits of ceiling were spent. A finer grid buys fairness no test could
 * see and spends ceiling every busy parent pays for.
 */
internal const val TICKS_PER_UNIT: Long = 16L

/**
 * [units] of an edge's own service as ticks: `units · G`. Exact; throws on `Long` overflow
 * (design §10.12).
 */
internal fun ticksOf(units: Long): Long = checkedMul(units, TICKS_PER_UNIT)

/**
 * The virtual time [v] read on the grid of an edge of weight [w]: `⌈v · w · G⌉` ticks.
 *
 * Exact for a value already on that grid — every value this module writes is — and the one
 * rounding left for any other: a floor or offset an older peer wrote as an arbitrary rational, or a
 * front handed to the public [EntitlementLedger.seat]. Rounding up is the same side the seat takes.
 *
 * @throws ArithmeticException if the result does not fit in a `Long`.
 */
internal fun ticksAt(v: Rational, w: Weight): Long {
    val scale = checkedMul(w.numerator, TICKS_PER_UNIT)
    val divisor = checkedMul(v.denominator, w.denominator)
    return mulDiv(v.numerator, scale, divisor, Rounding.CEILING)
        ?: throw ArithmeticException("Long overflow: ⌈$v · $w · $TICKS_PER_UNIT⌉")
}

/**
 * [ticks] on the grid of an edge of weight [w], as the virtual time it stands for:
 * `ticks / (G · w)`. The form a position takes on the wire, in a [Gauge.floor] and in the stored
 * wake offsets, so that a peer reading it as a [Rational] reads the exact value.
 *
 * `G · w.numerator` is divided out of [ticks] first, so the only overflow left is the reduced
 * numerator's own: at least `ticks · w.denominator / (G · w.numerator)`, the position's service
 * scaled by the weight's inverse.
 *
 * @throws ArithmeticException if the reduced numerator does not fit in a `Long`.
 */
internal fun ticksToVirtual(ticks: Long, w: Weight): Rational {
    val scale = checkedMul(TICKS_PER_UNIT, w.numerator)
    val g = gcdOf(if (ticks < 0L) checkedNegate(ticks) else ticks, scale)
    return Rational.of(checkedMul(ticks / g, w.denominator), scale / g)
}

private tailrec fun gcdOf(a: Long, b: Long): Long = if (b == 0L) a else gcdOf(b, a % b)

/**
 * A virtual time kept unreduced, as a sum of positions over a weight: `V = ticks / (G · w)`, where
 * `w = weightNumerator / weightDenominator`.
 *
 * A front is a weighted mean, `Σ w·v / Σ w`, and `w·v` is just an edge's position over `G`, so the
 * mean is `Σ p / (G · Σ w)`: a plain sum of positions over a sum of weights. Keeping that pair,
 * rather than dividing it out into a [Rational], is what stops a front's denominator ever reaching
 * a stored value. The sum is 128-bit because it is a sum of many `Long` positions (#3313: the sum of
 * positions is the site that overflowed first).
 *
 * The one thing read off it is [ticksFor], a joiner's position on its own grid, so the comparison
 * a pick makes and the position a seat writes are the same expression.
 */
internal class VirtualTime(
    private val ticks: Int128,
    private val weightNumerator: Long,
    private val weightDenominator: Long,
) {
    init {
        require(ticks >= Int128.ZERO) { "a virtual time is never negative, was $ticks" }
        require(weightNumerator > 0L && weightDenominator > 0L) { "weight must be positive" }
    }

    /**
     * This time on the grid of an edge of weight [w]: `⌈V · w · G⌉`. Where a newborn of weight [w]
     * is seated, where a waker is clamped to, and the highest position at which an edge of weight
     * [w] is still eligible (design §7.3 step 3, read at grid resolution).
     *
     * `V · w · G = ticks · (wDen · w.num) / (wNum · w.den)`. The quotient `ticks / divisor` is
     * split off first so no intermediate needs more than 128 bits: it fits in a `Long` because the
     * divisor is at least the number of positions summed (each edge's integer weight is at least
     * one) and no position exceeds a `Long`.
     *
     * @throws ArithmeticException if the result, or a weight product, does not fit in a `Long`.
     */
    fun ticksFor(w: Weight): Long {
        val multiplier = checkedMul(weightDenominator, w.numerator)
        val divisor = checkedMul(weightNumerator, w.denominator)
        val whole = ticks.divideOrNull(divisor, Rounding.FLOOR)
            ?: throw ArithmeticException("Long overflow: $ticks / $divisor")
        val remainder = ticks % divisor // ticks ≥ 0, so this is the floor remainder, in [0, divisor)
        val fraction = checkNotNull(mulDiv(remainder, multiplier, divisor, Rounding.CEILING)) {
            "remainder · m / d < m always fits: $remainder · $multiplier / $divisor"
        }
        return (whole.timesWide(multiplier) + fraction).toLongOrNull()
            ?: throw ArithmeticException("Long overflow: ⌈$ticks · $multiplier / $divisor⌉")
    }

    /** This time as an exact [Rational], for diagnostics. Throws when it does not fit one. */
    fun toRational(): Rational {
        val denominator = checkedMul(TICKS_PER_UNIT, weightNumerator)
        val g = gcd(ticks % denominator, denominator)
        val numerator = ticks.divideOrNull(g, Rounding.FLOOR) // exact: g divides ticks
            ?: throw ArithmeticException("Long overflow: $ticks / $g")
        return Rational.of(checkedMul(numerator, weightDenominator), denominator / g)
    }

    companion object {
        /**
         * The weighted mean of [positions] over [weights]: `Σ p / (G · Σ w)` (design §7.3 step 2).
         * The weights are brought to one denominator, their least common multiple `L`, so
         * `Σ w = S / L` with `S` an integer.
         *
         * @throws ArithmeticException if `L` or `S` does not fit in a `Long`.
         */
        fun mean(positions: List<Long>, weights: List<Weight>): VirtualTime {
            require(positions.size == weights.size && positions.isNotEmpty())
            val l = weights.fold(1L) { acc, w -> checkedMul(acc / gcd(acc, w.denominator), w.denominator) }
            val s = weights.fold(0L) { acc, w -> checkedAdd(acc, checkedMul(w.numerator, l / w.denominator)) }
            val sum = positions.fold(Int128.ZERO) { acc, p -> acc + p }
            return VirtualTime(sum, s, l)
        }

        /** One edge's own time: its position [ticks] at weight [w]. */
        fun of(ticks: Long, w: Weight): VirtualTime = VirtualTime(Int128.of(ticks), w.numerator, w.denominator)

        private tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)
    }
}

/**
 * Compare `a / wa` with `b / wb` for two positions on their own edges' grids: the order of the two
 * virtual times they stand for. Cross-multiplied in 128 bits, so it never throws on the positions;
 * it throws only if a weight product `wa.den · wb.num` leaves `Long`.
 */
internal fun compareVirtual(a: Long, wa: Weight, b: Long, wb: Weight): Int =
    a.timesWide(checkedMul(wa.denominator, wb.numerator))
        .compareTo(b.timesWide(checkedMul(wb.denominator, wa.numerator)))
