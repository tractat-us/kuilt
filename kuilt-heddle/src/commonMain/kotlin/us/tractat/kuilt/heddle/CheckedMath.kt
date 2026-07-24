package us.tractat.kuilt.heddle

/**
 * Overflow-checked `Long` arithmetic, hand-rolled because the JDK's
 * `Math.addExact`/`Math.multiplyExact` are not available on Kotlin/Native or
 * wasmJs. A fair-share tally that silently wrapped past [Long.MAX_VALUE] would
 * report authority nobody was ever granted, so every add and multiply that could
 * overflow in this module routes through here and **throws** rather than wraps
 * (design §10.12). [us.tractat.kuilt.crdt.GCounter.inc] itself is unchecked, so
 * the guarding happens at this layer.
 */

/** Add [a] and [b], throwing [ArithmeticException] on `Long` overflow. */
internal fun checkedAdd(a: Long, b: Long): Long {
    val sum = a + b
    // Overflow iff a and b share a sign that differs from the result's sign.
    if ((a xor sum) and (b xor sum) < 0L) {
        throw ArithmeticException("Long overflow: $a + $b")
    }
    return sum
}

/** Subtract [b] from [a], throwing [ArithmeticException] on `Long` overflow. */
internal fun checkedSub(a: Long, b: Long): Long {
    val diff = a - b
    // Overflow iff a and b differ in sign and the result's sign differs from a's.
    if ((a xor b) and (a xor diff) < 0L) {
        throw ArithmeticException("Long overflow: $a - $b")
    }
    return diff
}

/** Negate [a], throwing [ArithmeticException] on `Long` overflow (only `Long.MIN_VALUE`). */
internal fun checkedNegate(a: Long): Long {
    if (a == Long.MIN_VALUE) throw ArithmeticException("Long overflow: -$a")
    return -a
}

/** Multiply [a] and [b], throwing [ArithmeticException] on `Long` overflow. */
internal fun checkedMul(a: Long, b: Long): Long {
    if (a == 0L || b == 0L) return 0L
    val product = a * b
    // The division round-trip fails on overflow; the MIN_VALUE/-1 pair overflows
    // without failing that test, so it is checked explicitly.
    val overflowed = product / a != b ||
        (a == -1L && b == Long.MIN_VALUE) ||
        (b == -1L && a == Long.MIN_VALUE)
    if (overflowed) {
        throw ArithmeticException("Long overflow: $a * $b")
    }
    return product
}
