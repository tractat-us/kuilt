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
 * time representable: a position read as a virtual time, by the public reads and in a stored wake
 * offset, is `ticks / (G · w)`, whose numerator is at most `ticks · w.denominator`
 * ([ticksToVirtual]). A gauge stores its position in 128-bit ticks and is not held here; only a
 * read of it is (#3353). Short of `Long.MAX_VALUE` by [POSITION_HEADROOM], it
 * leaves a deadline room. A position that would pass it **saturates** here instead of throwing
 * (#3313): it no longer orders exactly, so a pick that would have to order it is refused with
 * [AcquireOutcome.PositionCeiling], while a charge, which may not be refused, lands with the
 * position held at the ceiling.
 */
internal fun positionCeiling(w: Weight): Long = (Long.MAX_VALUE - POSITION_HEADROOM) / w.denominator

/**
 * The position, in ticks, past which [LocalHeddle] rebases a parent: a sixteenth of [w]'s
 * [positionCeiling] (#3329). Far enough below the ceiling that the spread of a busy parent's
 * children above the rebased one never reaches it, and far enough above zero that a rebase is rare.
 */
internal fun rebaseThreshold(w: Weight): Long = positionCeiling(w) / 16

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
 * when [rounding] says so, narrowed to a position: [wideTicksAt] held at [w]'s [positionCeiling]
 * ([saturated], #3313). A front or an offset a pick orders is read here; a stored position is
 * read through [wideTicksAt], which does not cap.
 *
 * @throws ArithmeticException if the result is negative and below `Long.MIN_VALUE`.
 */
internal fun ticksAt(v: Rational, w: Weight, rounding: Rounding = Rounding.CEILING): Long =
    saturated(wideTicksAt(v, w, rounding), w)

/**
 * The virtual time [v] read on the grid of an edge of weight [w], exactly and uncapped:
 * `⌈v · w · G⌉` ticks, or `⌊…⌋` when [rounding] says so (#3353).
 *
 * Exact for a value already on that grid — every value this module writes is — and the one
 * rounding left for any other: a floor an older peer wrote as an arbitrary rational, or a front
 * handed to the public [EntitlementLedger.seat]. Rounding up is the same side the seat takes.
 *
 * The fraction is reduced crosswise before anything multiplies. When the two denominators' product
 * fits a `Long`, the quotient is formed as `k · s + ⌈r · s / d⌉` with `a = k · d + r`, so only the
 * `k · s` term is wide and it is exact. When it does not, the quotient is below the scale `s` and
 * fits a `Long`, and the division is done in two steps instead (`⌈⌈x / a⌉ / b⌉ = ⌈x / (a · b)⌉`
 * for positive `a` and `b`; the same holds for floors). So an older peer's fat denominator never
 * throws on the way to a small result, and a large one is never capped on the way to a big one.
 */
internal fun wideTicksAt(v: Rational, w: Weight, rounding: Rounding = Rounding.CEILING): Int128 {
    val scale = checkedMul(w.numerator, TICKS_PER_UNIT)
    val g1 = gcdOf(w.denominator, absRemainder(v.numerator, w.denominator))
    val g2 = gcdOf(v.denominator, scale)
    val numerator = v.numerator / g1
    val weightDenominator = w.denominator / g1
    val denominator = v.denominator / g2
    val reducedScale = scale / g2
    val divisor = denominator.timesOrNull(weightDenominator)
    if (divisor != null) return mulDivWide(numerator, reducedScale, divisor, rounding)
    val inner = mulDivWide(numerator, reducedScale, denominator, rounding)
    return Int128.of(
        checkNotNull(inner.divideOrNull(weightDenominator, rounding)) {
            "a quotient over a divisor past Long is below its scale: $inner / $weightDenominator"
        },
    )
}

/** `a · s / d` rounded as [rounding] says, exactly in 128 bits, for positive [s] and [d]. */
private fun mulDivWide(a: Long, s: Long, d: Long, rounding: Rounding): Int128 {
    val k = a.floorDiv(d)
    val r = a.mod(d) // in [0, d), so r · s / d is below s and fits
    return k.timesWide(s) + checkNotNull(mulDiv(r, s, d, rounding)) { "r · s / d < s always fits: $r · $s / $d" }
}

/** `|a mod b|` for a positive [b]: in `[0, b)`, and never a negation of `Long.MIN_VALUE`. */
private fun absRemainder(a: Long, b: Long): Long {
    val r = a % b
    return if (r < 0L) -r else r
}

/**
 * [ticks] on the grid of an edge of weight [w], as the virtual time it stands for:
 * `ticks / (G · w)`. The form a position takes in the public virtual-time reads and in the stored
 * wake offsets, and the form a build before #3353 wrote a [Gauge.floor] in, so that a peer
 * reading it as a [Rational] reads the exact value.
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

internal tailrec fun gcdOf(a: Long, b: Long): Long = if (b == 0L) a else gcdOf(b, a % b)

/**
 * A virtual time kept unreduced, as a sum of positions over a weight: `V = ticks / (G · w)`, where
 * `w = weightNumerator / weightDenominator`.
 *
 * A front is a weighted mean, `Σ w·v / Σ w`, and `w·v` is just an edge's position over `G`, so the
 * mean is `Σ p / (G · Σ w)`: a plain sum of positions over a sum of weights. Keeping that pair,
 * rather than dividing it out into a [Rational], is what stops a front's denominator ever reaching
 * a stored value. The sum is 128-bit because it is a sum of many positions (#3313: the sum of
 * positions is the site that overflowed first), and so are the positions themselves (#3353): a
 * front is taken over effective positions before anything narrows them.
 *
 * The one thing read off it is [ticksForWide], a joiner's position on its own grid, so the
 * comparison a pick makes and the position a seat writes are the same expression.
 */
internal class VirtualTime(
    private val ticks: Int128,
    private val weightNumerator: Long,
    private val weightDenominator: Long,
) {
    init {
        require(weightNumerator > 0L && weightDenominator > 0L) { "weight must be positive" }
    }

    /**
     * This time on the grid of an edge of weight [w], exactly: `⌈V · w · G⌉` ticks, in 128 bits.
     * Where a newborn of weight [w] is seated, relative to its parent's origin, and where a waker
     * is clamped to. Nothing caps it (#3353).
     *
     * `V · w · G = ticks · (wDen · w.num) / (wNum · w.den)`, formed by [mulDiv] in 192 bits so only
     * the result has to fit.
     *
     * @throws ArithmeticException if a weight product does not fit in a `Long`, which
     *   [weightsFitTheBudget] rules out for a topology [LocalHeddle.apply] accepted, or the result
     *   leaves 128 bits.
     */
    fun ticksForWide(w: Weight): Int128 {
        val multiplier = checkedMul(weightDenominator, w.numerator)
        val divisor = checkedMul(weightNumerator, w.denominator)
        return mulDiv(ticks, multiplier, divisor, Rounding.CEILING)
            ?: throw ArithmeticException("Int128 overflow: $ticks · $multiplier / $divisor")
    }

    /**
     * [ticksForWide] narrowed to a position a pick orders, held at [w]'s [positionCeiling]
     * ([saturated]): the highest position at which an edge of weight [w] is still eligible (design
     * §7.3 step 3, read at grid resolution). A bound at the ceiling admits every position below it,
     * as the exact bound would.
     */
    fun ticksFor(w: Weight): Long = saturated(ticksForWide(w), w)

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
         * @throws ArithmeticException if `L` or `S` does not fit in a `Long`, or the sum leaves
         *   128 bits.
         */
        fun mean(positions: List<Int128>, weights: List<Weight>): VirtualTime {
            require(positions.size == weights.size && positions.isNotEmpty())
            val l = weights.fold(1L) { acc, w -> checkedMul(acc / gcd(acc, w.denominator), w.denominator) }
            val s = weights.fold(0L) { acc, w -> checkedAdd(acc, checkedMul(w.numerator, l / w.denominator)) }
            val sum = positions.fold(Int128.ZERO) { acc, p -> acc + p }
            return VirtualTime(sum, s, l)
        }

        /** One edge's own time: its position [ticks] at weight [w]. */
        fun of(ticks: Int128, w: Weight): VirtualTime = VirtualTime(ticks, w.numerator, w.denominator)

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

/**
 * [compareVirtual] for two wide positions: the sign of `a · ka − b · kb` with `ka = wa.den · wb.num`
 * and `kb = wb.den · wa.num`, both positive. Exact for every pair (#3353): it compares
 * `⌊a · ka / kb⌋` with `b`, which decides the order unless they are equal, and then the remainder
 * does. A quotient past 128 bits is past every `b`, so its sign decides.
 */
internal fun compareVirtualWide(a: Int128, wa: Weight, b: Int128, wb: Weight): Int {
    val ka = checkedMul(wa.denominator, wb.numerator)
    val kb = checkedMul(wb.denominator, wa.numerator)
    val floor = mulDiv(a, ka, kb, Rounding.FLOOR) ?: return if (a < Int128.ZERO) -1 else 1
    val byFloor = floor.compareTo(b)
    if (byFloor != 0) return if (byFloor < 0) -1 else 1
    // ⌊a · ka / kb⌋ = b: equal exactly when nothing was left over, above `b` otherwise.
    return if (mulDiv(a, ka, kb, Rounding.CEILING) == floor) 0 else 1
}
