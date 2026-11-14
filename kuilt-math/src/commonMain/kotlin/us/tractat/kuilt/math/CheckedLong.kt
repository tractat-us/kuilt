package us.tractat.kuilt.math

/**
 * `Long` arithmetic that answers `null` instead of wrapping.
 *
 * Kotlin's `+`, `-` and `*` on `Long` wrap silently past [Long.MAX_VALUE], and the JDK's
 * `Math.addExact` family is not available on Kotlin/Native or wasmJs. A tally that wrapped would
 * report a number nobody ever counted, so code that cannot afford one routes through these and
 * decides for itself what an overflow means: a typed refusal, a fallback, or a throw.
 *
 * A `null` here always means the exact result does not fit in a `Long`. It never means anything
 * else, so `a.plusOrNull(b) ?: return Refused` is a complete check.
 *
 * @sample us.tractat.kuilt.math.sampleCheckedLong
 */
public fun Long.plusOrNull(other: Long): Long? {
    val sum = this + other
    // Overflow iff both operands share a sign that the wrapped sum does not.
    return if ((this xor sum) and (other xor sum) < 0L) null else sum
}

/** `this - other`, or `null` when the exact difference does not fit in a `Long`. See [plusOrNull]. */
public fun Long.minusOrNull(other: Long): Long? {
    val diff = this - other
    // Overflow iff the operands differ in sign and the wrapped difference left this one's sign.
    return if ((this xor other) and (this xor diff) < 0L) null else diff
}

/** `this * other`, or `null` when the exact product does not fit in a `Long`. See [plusOrNull]. */
public fun Long.timesOrNull(other: Long): Long? {
    if (this == 0L || other == 0L) return 0L
    val product = this * other
    // The division round-trip catches every overflow but one: MIN_VALUE * -1 wraps back to
    // MIN_VALUE, and MIN_VALUE / -1 wraps the same way, so that pair is named explicitly.
    val overflowed = product / this != other ||
        (this == -1L && other == Long.MIN_VALUE) ||
        (other == -1L && this == Long.MIN_VALUE)
    return if (overflowed) null else product
}

/** `-this`, or `null` for [Long.MIN_VALUE], the one `Long` whose negation does not fit. */
public fun Long.negateOrNull(): Long? = if (this == Long.MIN_VALUE) null else -this
