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
 * The room left above a position at [positionCeiling] for one lease: the largest reservation,
 * [LocalHeddle.MAX_RESERVATION], in ticks. Only the weight budget's P1′ reads it
 * ([weightsFitTheBudget]); a pick forms its deadline `p + q · G` in 128 bits.
 */
internal const val POSITION_HEADROOM: Long = LocalHeddle.MAX_RESERVATION * TICKS_PER_UNIT

/**
 * The position limit, `2¹²⁵` ticks (design §7.1, step B): the most a parent's **load**, the sum of
 * its active children's effective positions each on its own grid, may reach after a child lands
 * there, and the furthest its clock may run either way from zero. A landing that would pass it is
 * not made ([Ticks.PastLimit]). It sits two bits under `2¹²⁷`, so every value a pick then forms
 * stays inside 128 bits with a bit to spare.
 */
internal val POSITION_LIMIT: Int128 = Int128.fromTwosComplement(1L shl 61, 0L)

/** A position formed for a landing: one that fits the [POSITION_LIMIT], or one that does not. */
internal sealed interface Ticks {
    /** [value] ticks, at most [POSITION_LIMIT] either way from zero. */
    class Fits(val value: Int128) : Ticks

    /** Past the [POSITION_LIMIT], or outside 128 bits altogether. */
    data object PastLimit : Ticks
}

/**
 * Whether [positions], each counted by its size, sum to at most [POSITION_LIMIT]. Formed so that it
 * never overflows: the sum stays at or below the limit, so the room left never goes negative.
 */
internal fun loadFits(positions: Sequence<Int128>): Boolean {
    var load = Int128.ZERO
    for (p in positions) {
        if (p < Int128.ZERO - POSITION_LIMIT || p > POSITION_LIMIT) return false
        val size = if (p < Int128.ZERO) Int128.ZERO - p else p
        if (size > POSITION_LIMIT - load) return false
        load += size
    }
    return true
}

/**
 * The highest position, in ticks, the public virtual-time reads of an edge of weight [w] report
 * exactly: `(Long.MAX_VALUE −` [POSITION_HEADROOM]`) / w.denominator`. A position read as a virtual
 * time is `ticks / (G · w)`, whose numerator is at most `ticks · w.denominator` ([ticksToVirtual]),
 * so dividing by the denominator keeps it a [Rational]. Past it a read reports the ceiling
 * ([inWindow]), the penalty side.
 *
 * **No pick reads it.** A pick orders the full 128-bit effective position, so a child past its
 * ceiling is ordered exactly and nothing is refused there. A gauge's stored position, the raw
 * position, the front and a wake clamp are all 128-bit too.
 * Nothing rebases a busy parent's positions back down (#3472), so a long-running one passes it and
 * only those reads saturate. What it still sizes is the weight budget's P1′ ([rebaseThreshold]).
 */
internal fun positionCeiling(w: Weight): Long = (Long.MAX_VALUE - POSITION_HEADROOM) / w.denominator

/**
 * The lowest effective position, in ticks, the public reads of an edge of weight [w] report:
 * `−`[positionCeiling]. An edge's **window** is `[positionFloor, positionCeiling)`.
 *
 * Nothing seats, clamps or returns a child below zero any more (#3472), so only its own returns can
 * take it there: a lease cancelled walks its holder back by the lease. A pick orders it all the
 * same, exactly, since it reads the wide position, and the public reads hold it at the floor
 * ([inWindow]). More than `2⁴⁸ / d` ticks below its floor, for a weight `n / d`, `ticks · d`
 * leaves `Long`, which is why the reads saturate rather than convert.
 */
internal fun positionFloor(w: Weight): Long = -positionCeiling(w)

/**
 * A sixteenth of [w]'s [positionCeiling]: the position past which the engine once rebased a parent
 * (#3329, #3353). Nothing rebases now (#3472); the weight budget's P1′ still measures its lease
 * room from here ([weightsFitTheBudget]). P1′ stays: it is the set of weights an apply accepts,
 * and the position limit (step B, slice 5) sits beside it rather than replacing it.
 */
internal fun rebaseThreshold(w: Weight): Long = positionCeiling(w) / 16

/**
 * Whether one parent's children, of [weights], fit the weight budget: every product a pick forms
 * stays in `Long`, and one full lease fits every child's window. Every site that admits a weight
 * set asks this one question (#3353): `Prepare` and `Activate` on the control log, an ungoverned
 * node's `prepare` and `activate`, a static node's boot, [LocalHeddle.apply], and
 * [FairShareAllocator.schedule] on read, for a set a merge assembled.
 *
 * ## The `Long` products
 *
 * Four checks cover them all ([VirtualTime.mean], [VirtualTime.ticksForWide], [compareVirtualWide]):
 * - `L`, the least common multiple of the denominators;
 * - `S = Σ n · L/d`, the weights' sum over `L`;
 * - `G · S`, the front's denominator in ticks;
 * - `S · d` for every weight, the divisor [VirtualTime.ticksForWide] forms.
 *
 * The other products follow. `L · n = (n · L/d) · d ≤ S · d`, since `n · L/d` is one term of `S`.
 * And `d_a · n_b ≤ L · n_b ≤ S · d_b`, since `d_a` divides `L`.
 *
 * ## One full lease (P1′)
 *
 * Then, for every child `i`, `C_i − R_i > H + gridStep(S) · G · n_i/d_i`, where `C` is the
 * [positionCeiling], `R` the [rebaseThreshold], `H` the [POSITION_HEADROOM] (one lease of
 * [LocalHeddle.MAX_RESERVATION]) and [gridStepOf] the whole step the origin moves in. A child is
 * rebased only once it passes `R`, and the origin moves by whole steps, so a child can sit up to
 * `R` plus one step on its own grid with no rebase due. Its next lease adds up to `H`, and that
 * must stay below `C`. Without the check a child that an under-charged `complete` parks between
 * `C − H` and `R` is never rebased, and its next lease carries it past its ceiling, where the
 * public reads saturate (#3353 review: `{1/30,721, 1/30,722}`, one lease charged `1.172 · 10¹²`;
 * before picks were wide, the parent refused every pick there for good).
 *
 * The step is the whole set's, so activating one child can refuse on behalf of another:
 * activating `1/3` beside a heavy child triples that child's step. The rule keeps every
 * denominator at or below 30,719, at any lease size: `H` is a constant, the largest lease, and
 * [HeddlePolicy.pick] grants no more than that. Near that edge, several fine weights whose denominators
 * share no factor are refused too, since the step grows with their least common multiple.
 *
 * ## Subsets
 *
 * A subset's `L` divides the whole set's, so its `S` is no larger, and its grid step divides the
 * whole set's, so every front a pick takes over part of the set stays in range too. And a weight
 * that fails alone fails beside any siblings, which is what lets a `Prepare` be refused on the
 * record alone.
 */
internal fun weightsFitTheBudget(weights: List<Weight>): Boolean {
    if (weights.isEmpty()) return true
    var l = 1L
    for (w in weights) l = (l / gcdOf(l, w.denominator)).timesOrNull(w.denominator) ?: return false
    var sum = 0L
    for (w in weights) sum = sum.plusOrNull(w.numerator.timesOrNull(l / w.denominator) ?: return false) ?: return false
    if (sum.timesOrNull(TICKS_PER_UNIT) == null) return false
    if (!weights.all { sum.timesOrNull(it.denominator) != null }) return false
    val step = gridStepOf(weights) ?: return false
    return weights.all { aFullLeaseFits(it, step) }
}

/**
 * Whether one lease of [LocalHeddle.MAX_RESERVATION] from just under [w]'s [rebaseThreshold], plus
 * one origin step of [step] on its grid, stays below its [positionCeiling]: P1′, in 128 bits.
 */
private fun aFullLeaseFits(w: Weight, step: Long): Boolean {
    val ceiling = positionCeiling(w)
    val window = Int128.of(ceiling - rebaseThreshold(w))
    return window > Int128.of(POSITION_HEADROOM) + stepTicks(step, w)
}

/**
 * One origin step of [step] units of virtual time on [w]'s grid, in ticks: `step · G · n / d`,
 * exactly. [step] is a whole multiple of `d / gcd(d, G · n)`, so the quotient is whole.
 */
private fun stepTicks(step: Long, w: Weight): Int128 {
    val scale = checkedMul(TICKS_PER_UNIT, w.numerator)
    val g = gcdOf(w.denominator, scale)
    return (step / (w.denominator / g)).timesWide(scale / g)
}

/**
 * The least origin step, in whole units of virtual time, that moves an edge of every one of
 * [weights] by whole ticks: the lcm of `d / gcd(d, G · n)`, or `null` when it leaves `Long`.
 *
 * Never `null` for a set inside the `Long` budget: each `d / gcd(d, G · n)` divides `d`, so their
 * lcm divides `L`. The rebase steps the origin in multiples of it, and P1′ measures one step with
 * it, so the two cannot disagree about what a step is.
 */
internal fun gridStepOf(weights: List<Weight>): Long? {
    var step = 1L
    for (w in weights) {
        val need = w.denominator / gcdOf(w.denominator, checkedMul(TICKS_PER_UNIT, w.numerator))
        step = (step / gcdOf(step, need)).timesOrNull(need) ?: return null
    }
    return step
}

/**
 * [units] of an edge's own service as ticks: `units · G`. Exact; throws on `Long` overflow
 * (design §10.12).
 */
internal fun ticksOf(units: Long): Long = checkedMul(units, TICKS_PER_UNIT)

/**
 * The virtual time [v] read on the grid of an edge of weight [w], exactly and uncapped:
 * `⌈v · w · G⌉` ticks, or `⌊…⌋` when [rounding] says so (#3353).
 *
 * Exact for a value already on that grid — every value this module writes is — and the one
 * rounding left for any other: a front handed to the public [EntitlementLedger.seat]. Rounding up
 * is the same side the seat takes.
 *
 * The fraction is reduced crosswise before anything multiplies. When the two denominators' product
 * fits a `Long`, the quotient is formed as `k · s + ⌈r · s / d⌉` with `a = k · d + r`, so only the
 * `k · s` term is wide and it is exact. When it does not, the quotient is below the scale `s` and
 * fits a `Long`, and the division is done in two steps instead (`⌈⌈x / a⌉ / b⌉ = ⌈x / (a · b)⌉`
 * for positive `a` and `b`; the same holds for floors). So a fat denominator never throws on the
 * way to a small result, and a large one is never capped on the way to a big one.
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
 * wake offsets.
 *
 * `G · w.numerator` is divided out of [ticks] first, so the only overflow left is the reduced
 * numerator's own: at least `ticks · w.denominator / (G · w.numerator)`, the position's service
 * scaled by the weight's inverse.
 *
 * @throws ArithmeticException if the reduced numerator does not fit in a `Long`.
 */
internal fun ticksToVirtual(ticks: Long, w: Weight): Rational {
    // An in-window position (|ticks| <= C) never throws: |ticks| · d <= Long.MAX_VALUE − H.
    val scale = checkedMul(TICKS_PER_UNIT, w.numerator)
    val g = gcdOf(if (ticks < 0L) checkedNegate(ticks) else ticks, scale)
    return Rational.of(checkedMul(ticks / g, w.denominator), scale / g)
}

/**
 * [ticks] held inside [w]'s window ([positionFloor], [positionCeiling]): the position a public read
 * reports (#3353). Above, at the ceiling, as before; below, at the floor, so a child a `cancel` has
 * walked back past its floor reads higher than it is, the penalty side, rather than throwing.
 */
internal fun inWindow(ticks: Int128, w: Weight): Long {
    val ceiling = positionCeiling(w)
    if (ticks >= Int128.of(ceiling)) return ceiling
    val floor = positionFloor(w)
    if (ticks <= Int128.of(floor)) return floor
    return checkNotNull(ticks.toLongOrNull()) { "a position inside its window fits a Long: $ticks" }
}

/**
 * [ticks] as the virtual time a public read reports: [inWindow], then [ticksToVirtual]. Total: every
 * position in a window converts exactly, so no read throws however far a child is from its origin.
 */
internal fun readVirtual(ticks: Int128, w: Weight): Rational = ticksToVirtual(inWindow(ticks, w), w)

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
     * Where a newborn of weight [w] is seated, where a waker is
     * clamped to, and the highest position at which an edge of weight [w] is still eligible for a
     * pick (design §7.3 step 3, read at grid resolution). Nothing caps it (#3353).
     *
     * `V · w · G = ticks · (wDen · w.num) / (wNum · w.den)`, formed by [mulDiv] in 192 bits so only
     * the result has to fit.
     *
     * It never throws on how far the time has run: a result past the [POSITION_LIMIT], or one that
     * leaves 128 bits, is [Ticks.PastLimit], and there is no throwing form, so no landing can be
     * formed without handling it (design §7.1).
     *
     * @throws ArithmeticException if a weight product does not fit in a `Long`, which
     *   [weightsFitTheBudget] rules out for a topology [LocalHeddle.apply] accepted.
     */
    fun ticksForWide(w: Weight): Ticks {
        val multiplier = checkedMul(weightDenominator, w.numerator)
        val divisor = checkedMul(weightNumerator, w.denominator)
        val value = mulDiv(ticks, multiplier, divisor, Rounding.CEILING) ?: return Ticks.PastLimit
        return if (value > POSITION_LIMIT || value < Int128.ZERO - POSITION_LIMIT) Ticks.PastLimit else Ticks.Fits(value)
    }

    /**
     * Whether [position], ticks on the grid of weight [w], is at or below this time read on that
     * grid rounded up: `position ≤ ⌈V · w · G⌉`, the eligibility test of a pick (design §7.3 step 3).
     * Exact for every pair, with no position limit: a bound past 128 bits is above every position
     * when this time is positive and below every one when it is negative.
     */
    fun admits(position: Int128, w: Weight): Boolean {
        val bound = mulDiv(ticks, checkedMul(weightDenominator, w.numerator), checkedMul(weightNumerator, w.denominator), Rounding.CEILING)
            ?: return ticks > Int128.ZERO
        return position <= bound
    }

    /** This time in whole units of virtual time, rounded down: `⌊ticks / (G · w)⌋`. */
    fun unitsFloor(): Int128 =
        mulDiv(ticks, weightDenominator, checkedMul(TICKS_PER_UNIT, weightNumerator), Rounding.FLOOR)
            ?: throw ArithmeticException("Int128 overflow: $ticks · $weightDenominator / (G · $weightNumerator)")

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
 * Compare `a / wa` with `b / wb`, two positions on their own edges' grids, the order of the two
 * virtual times they stand for: the sign of `a · ka − b · kb` with `ka = wa.den · wb.num` and
 * `kb = wb.den · wa.num`, both positive. Exact for every pair (#3353): it compares
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

/** This value as the nearest `Double`, for a diagnostic ratio: `high · 2⁶⁴ + unsigned(low)`. */
internal fun Int128.toDouble(): Double = high.toDouble() * TWO_TO_THE_64 + low.toULong().toDouble()

private const val TWO_TO_THE_64: Double = 18446744073709551616.0
