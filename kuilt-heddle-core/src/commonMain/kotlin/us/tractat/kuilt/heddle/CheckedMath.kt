package us.tractat.kuilt.heddle

import us.tractat.kuilt.math.Int128
import us.tractat.kuilt.math.WideSum
import us.tractat.kuilt.math.minusOrNull
import us.tractat.kuilt.math.negateOrNull
import us.tractat.kuilt.math.plusOrNull
import us.tractat.kuilt.math.timesOrNull

/**
 * Overflow-checked `Long` arithmetic that **throws** rather than wraps (design §10.12). A
 * fair-share tally that silently wrapped past [Long.MAX_VALUE] would report authority nobody was
 * ever granted, so every add and multiply that could overflow in this module routes through here.
 * [us.tractat.kuilt.crdt.GCounter.inc] and `value` refuse an overflow themselves (#3318), but this
 * module's tallies are mostly its own arithmetic over slot counts, so the guarding happens here.
 *
 * The arithmetic is `:kuilt-math`'s, which answers `null` on overflow; these turn that `null` into
 * the throw this module's callers were written against. They stay `internal`: `:kuilt-math`'s API
 * is the `null` form, and a caller in another module writes `?: throw` itself, as `:kuilt-heddle`'s
 * `BoundMetrics` does. New code that can turn an overflow into a typed refusal should call
 * `:kuilt-math` directly instead.
 */

/** Add [a] and [b], throwing [ArithmeticException] on `Long` overflow. */
internal fun checkedAdd(a: Long, b: Long): Long = a.plusOrNull(b) ?: throw ArithmeticException("Long overflow: $a + $b")

/** Subtract [b] from [a], throwing [ArithmeticException] on `Long` overflow. */
internal fun checkedSub(a: Long, b: Long): Long = a.minusOrNull(b) ?: throw ArithmeticException("Long overflow: $a - $b")

/** Negate [a], throwing [ArithmeticException] on `Long` overflow (only `Long.MIN_VALUE`). */
internal fun checkedNegate(a: Long): Long = a.negateOrNull() ?: throw ArithmeticException("Long overflow: -$a")

/** Multiply [a] and [b], throwing [ArithmeticException] on `Long` overflow. */
internal fun checkedMul(a: Long, b: Long): Long = a.timesOrNull(b) ?: throw ArithmeticException("Long overflow: $a * $b")

/** This value as a `Long`, or [ArithmeticException] when it does not fit (design §10.12). */
internal fun Int128.toLongExact(): Long =
    toLongOrNull() ?: throw ArithmeticException("Long overflow: 128-bit value $this does not fit")

/**
 * This sum as a `Long`, or [ArithmeticException] when it does not fit (design §10.12).
 *
 * A [WideSum] of `Long` terms is exact whatever order they come in, so a fold over a ledger that
 * changes incrementally throws on fewer inputs than the same terms folded through [checkedAdd], never
 * on more, and returns the same value wherever that fold returns one: what throws here depends on the
 * state alone, not on the history that reached it.
 */
internal fun WideSum.toLongExact(): Long = toInt128().toLongExact()
