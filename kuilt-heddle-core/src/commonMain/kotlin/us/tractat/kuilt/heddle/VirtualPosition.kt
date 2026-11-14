package us.tractat.kuilt.heddle

import us.tractat.kuilt.math.Int128
import us.tractat.kuilt.math.Rounding
import us.tractat.kuilt.math.mulDiv
import us.tractat.kuilt.math.plusOrNull
import us.tractat.kuilt.math.timesOrNull
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
 * The room left above a position at its ceiling for one deadline: the largest reservation,
 * [LocalHeddle.MAX_RESERVATION], in ticks. A pick reads `p + q · G`, so with this much spare, a
 * deadline over a position below the ceiling never leaves `Long`.
 */
internal const val POSITION_HEADROOM: Long = LocalHeddle.MAX_RESERVATION * TICKS_PER_UNIT

/**
 * The highest position, in ticks, an edge of weight [w] can hold exactly: `(Long.MAX_VALUE −`
 * [POSITION_HEADROOM]`) / w.denominator`.
 *
 * The bound has two jobs. Divided by the weight's denominator, it keeps the position's own virtual
 * time representable: a floor on the wire is `ticks / (G · w)`, whose numerator is at most
 * `ticks · w.denominator` ([ticksToVirtual]). Short of `Long.MAX_VALUE` by [POSITION_HEADROOM], it
 * leaves a deadline room. A position that would pass it **saturates** here instead of throwing
 * (#3313): it no longer orders exactly, so a pick that would have to order it is refused with
 * [AcquireOutcome.PositionCeiling], while a charge, which may not be refused, lands with the
 * position held at the ceiling.
 */
internal fun positionCeiling(w: Weight): Long = (Long.MAX_VALUE - POSITION_HEADROOM) / w.denominator

/** Whether [ticks] is at [w]'s [positionCeiling]: a position there may stand for a larger one. */
internal fun atCeiling(ticks: Long, w: Weight): Boolean = ticks >= positionCeiling(w)

/**
 * [ticks] held at [w]'s [positionCeiling]: the one place a position saturates rather than throws.
 *
 * @throws ArithmeticException if [ticks] is below `Long.MIN_VALUE`, a position no service reaches.
 */
internal fun saturated(ticks: Int128, w: Weight): Long {
    val ceiling = positionCeiling(w)
    if (ticks >= Int128.of(ceiling)) return ceiling
    return ticks.toLongOrNull() ?: throw ArithmeticException("Long overflow: a position of $ticks ticks")
}

/** [units] of an edge's own service as ticks, held at [w]'s [positionCeiling] ([saturated]). */
internal fun saturatedTicksOf(units: Long, w: Weight): Long = saturated(units.timesWide(TICKS_PER_UNIT), w)

/**
 * Thrown where a pick would have to order a position at its ceiling, or where a deadline leaves
 * `Long` (#3313). It is an [ArithmeticException], as the overflow it replaces was, so a caller of
 * [HeddlePolicy.pick] or [FairShareAllocator.pickChild] sees the same type; the engine catches this
 * subtype and answers a choosing call with a typed refusal instead.
 */
internal class PositionCeilingException(message: String) : ArithmeticException(message)

/**
 * Whether one parent's children, of [weights], keep every product a pick forms in `Long`
 * ([VirtualTime.mean], [VirtualTime.ticksFor], [compareVirtual]). Four checks cover them all:
 * - `L`, the least common multiple of the denominators;
 * - `S = Σ n · L/d`, the weights' sum over `L`;
 * - `G · S`, the front's denominator in ticks;
 * - `S · d` for every weight, the divisor [VirtualTime.ticksFor] forms.
 *
 * The other products follow. `L · n = (n · L/d) · d ≤ S · d`, since `n · L/d` is one term of `S`.
 * And `d_a · n_b ≤ L · n_b ≤ S · d_b`, since `d_a` divides `L`. A subset's `L` divides the whole
 * set's, so its `S` is no larger, and every front a pick takes over part of the set stays in range
 * too. A reload that would break it is refused at [LocalHeddle.apply] (#3313).
 */
internal fun weightsFitTheBudget(weights: List<Weight>): Boolean {
    if (weights.isEmpty()) return true
    var l = 1L
    for (w in weights) l = (l / gcdOf(l, w.denominator)).timesOrNull(w.denominator) ?: return false
    var sum = 0L
    for (w in weights) sum = sum.plusOrNull(w.numerator.timesOrNull(l / w.denominator) ?: return false) ?: return false
    if (sum.timesOrNull(TICKS_PER_UNIT) == null) return false
    return weights.all { sum.timesOrNull(it.denominator) != null }
}

/**
 * [units] of an edge's own service as ticks: `units · G`. Exact; throws on `Long` overflow
 * (design §10.12).
 */
internal fun ticksOf(units: Long): Long = checkedMul(units, TICKS_PER_UNIT)

/**
 * The virtual time [v] read on the grid of an edge of weight [w]: `⌈v · w · G⌉` ticks, or `⌊…⌋`
 * when [rounding] says so.
 *
 * Exact for a value already on that grid — every value this module writes is — and the one
 * rounding left for any other: a floor or offset an older peer wrote as an arbitrary rational, or a
 * front handed to the public [EntitlementLedger.seat]. Rounding up is the same side the seat takes.
 *
 * The fraction is reduced crosswise before anything multiplies, and when the two denominators'
 * product still leaves `Long` the division is done in two ceiling steps instead
 * (`⌈⌈x / a⌉ / b⌉ = ⌈x / (a · b)⌉` for positive `a` and `b`; the same holds for floors). So an
 * older peer's fat denominator never throws on the way to a small result. A result above [w]'s
 * [positionCeiling], or past `Long`, reads as the ceiling (#3313).
 *
 * @throws ArithmeticException if the result is negative and below `Long.MIN_VALUE`.
 */
internal fun ticksAt(v: Rational, w: Weight, rounding: Rounding = Rounding.CEILING): Long {
    val ticks = exactTicksAt(v, w, rounding)
    // Past Long is past the ceiling too, for a positive value: hold it there.
    return when {
        ticks != null -> minOf(ticks, positionCeiling(w))
        v.numerator > 0L -> positionCeiling(w)
        else -> throw ArithmeticException("Long overflow: ⌈$v · $w · $TICKS_PER_UNIT⌉")
    }
}

/** [ticksAt] without the ceiling: `null` when the result leaves `Long`. */
private fun exactTicksAt(v: Rational, w: Weight, rounding: Rounding): Long? {
    val scale = checkedMul(w.numerator, TICKS_PER_UNIT)
    val g1 = gcdOf(w.denominator, absRemainder(v.numerator, w.denominator))
    val g2 = gcdOf(v.denominator, scale)
    val numerator = v.numerator / g1
    val weightDenominator = w.denominator / g1
    val denominator = v.denominator / g2
    val reducedScale = scale / g2
    val divisor = denominator.timesOrNull(weightDenominator)
    if (divisor != null) return mulDiv(numerator, reducedScale, divisor, rounding)
    val inner = mulDiv(numerator, reducedScale, denominator, rounding) ?: return null
    return mulDiv(inner, 1L, weightDenominator, rounding)
}

/** `|a mod b|` for a positive [b]: in `[0, b)`, and never a negation of `Long.MIN_VALUE`. */
private fun absRemainder(a: Long, b: Long): Long {
    val r = a % b
    return if (r < 0L) -r else r
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
    /**
     * Whether a position this time was taken over is at its ceiling, so the time may stand for a
     * later one. Then every [ticksFor] reads the ceiling: a seat or clamp taken against it lands at
     * the penalty end, never below a front it cannot see (§10.5, #3313).
     */
    private val atCeiling: Boolean = false,
) {
    init {
        require(weightNumerator > 0L && weightDenominator > 0L) { "weight must be positive" }
    }

    /**
     * This time on the grid of an edge of weight [w]: `⌈V · w · G⌉`. Where a newborn of weight [w]
     * is seated, where a waker is clamped to, and the highest position at which an edge of weight
     * [w] is still eligible (design §7.3 step 3, read at grid resolution).
     *
     * `V · w · G = ticks · (wDen · w.num) / (wNum · w.den)`. The floor quotient `ticks / divisor`
     * is split off first so no intermediate needs more than 128 bits: it fits in a `Long` because
     * the divisor is at least the number of positions summed (each edge's integer weight is at
     * least one) and every position is a `Long`. The remainder is taken against that floor
     * quotient, so it is in `[0, divisor)` for a negative sum too: a position can be negative when
     * an edge has returned more than its base issuance, relocation credit included.
     *
     * A result above [w]'s [positionCeiling] is held there ([saturated]): a seat or a clamp there
     * lands at the ceiling, and an eligibility bound there admits every position below it, as the
     * exact bound would.
     *
     * @throws ArithmeticException if a weight product does not fit in a `Long`, which
     *   [weightsFitTheBudget] rules out for a topology [LocalHeddle.apply] accepted.
     */
    fun ticksFor(w: Weight): Long {
        if (atCeiling) return positionCeiling(w)
        val multiplier = checkedMul(weightDenominator, w.numerator)
        val divisor = checkedMul(weightNumerator, w.denominator)
        val whole = ticks.divideOrNull(divisor, Rounding.FLOOR)
            ?: throw ArithmeticException("Long overflow: $ticks / $divisor")
        // The floor remainder, in [0, divisor): `ticks % divisor` truncates, which differs for a negative sum.
        val remainder = checkNotNull((ticks - whole.timesWide(divisor)).toLongOrNull()) {
            "a floor remainder is below its divisor: $ticks − $whole · $divisor"
        }
        val fraction = checkNotNull(mulDiv(remainder, multiplier, divisor, Rounding.CEILING)) {
            "remainder · m / d < m always fits: $remainder · $multiplier / $divisor"
        }
        return saturated(whole.timesWide(multiplier) + fraction, w)
    }

    /** This time, marked as taken over a position at its ceiling when [saturated] says so. */
    fun atCeilingIf(saturated: Boolean): VirtualTime =
        if (saturated && !atCeiling) VirtualTime(ticks, weightNumerator, weightDenominator, atCeiling = true) else this

    /** This time as an exact [Rational], for diagnostics. Throws when it does not fit one. */
    fun toRational(): Rational {
        val denominator = checkedMul(TICKS_PER_UNIT, weightNumerator)
        val g = gcd(denominator, absRemainder128(ticks, denominator))
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

        private fun absRemainder128(a: Int128, b: Long): Long {
            val r = a % b
            return if (r < 0L) -r else r
        }
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
