# Module kuilt-math

**Integer arithmetic that never quietly gives the wrong answer.**

Add one to the largest number a computer's `Long` can hold and you get the most negative one. Most
programs never get there. A program that keeps a running total for years — a quota, a fair-share
clock, a counter every device adds to — does, and when it does the total is simply wrong, with no
error anywhere. This module is the small set of operations that either give the exact answer or
say plainly that there isn't one that fits.

## Quick start

<!-- verbatim from kuilt-math/src/commonSamples/kotlin/us/tractat/kuilt/math/Samples.kt#sampleMulDiv -->
```kotlin
val big = 1L shl 62
// big · 4 overflows a Long, but big · 4 / 8 = 2⁶¹ does not, and mulDiv only needs the answer to fit.
assertEquals(1L shl 61, mulDiv(big, 4L, 8L, Rounding.FLOOR))
assertEquals(3L, mulDiv(7L, 1L, 2L, Rounding.FLOOR))
assertEquals(4L, mulDiv(7L, 1L, 2L, Rounding.CEILING))
assertNull(mulDiv(Long.MAX_VALUE, 2L, 1L, Rounding.FLOOR))
```

## What is here

- **`plusOrNull`, `minusOrNull`, `timesOrNull`, `negateOrNull`** on `Long`. Each returns the exact
  result, or `null` when it does not fit. The caller decides what that means — a refusal, a
  fallback, or a throw — instead of the operator deciding to wrap.
- **`Int128`**, an exact signed 128-bit integer. The product of two `Long`s always fits in one
  (`Long.timesWide`), so comparing two fractions by cross-multiplying (`compareProducts`) is exact
  for every input. A running sum of up to 2⁶⁴ `Long`s fits too, in any order.
- **`mulDiv(a, b, divisor, rounding)`**: `a · b / divisor` with the product formed in 128 bits, so
  only the final quotient has to fit in a `Long`. The rounding is a required argument —
  `Rounding.FLOOR` or `Rounding.CEILING` — because the direction is usually the decision: round a
  share down and you never hand out more than you hold; round a newcomer's position up and it
  never starts ahead of the people already waiting.

## Why null, not an exception

An overflow is usually a fact about the data, not a bug in the program: a total grew past what one
`Long` holds. Code that must keep running — a scheduler deciding the next turn, a ledger applying a
peer's update — needs to turn that fact into an answer it can return, and a `null` is checked by the
compiler where an exception is not. Code that genuinely wants the throw writes
`a.plusOrNull(b) ?: throw ArithmeticException(…)`, and the message is its own.

`Int128` arithmetic is the one place an overflow throws, and how near that is depends on what is
being summed. A sum of `Long`s needs more than 2⁶⁴ terms to leave 128 bits, so getting there is a
bug, not data. A sum of products is different. Each `timesWide` product can be as large as 2¹²⁶,
so two of them — `Long.MIN_VALUE` squared, twice — already reach 2¹²⁷ and throw. The difference of
two products always fits, and a third term can leave. Code that sums products of its data bounds
those inputs, or the number of terms, before it adds.

Dividing by zero throws `ArithmeticException`, as `Long` division does. A zero divisor is a bug in
the caller, not an answer that fails to fit.

## Identical on every target

Everything is built from `Long` and `ULong` operations whose results Kotlin defines the same way on
the JVM, Kotlin/Native and wasmJs, so two devices computing the same expression get the same bits.
Nothing here uses floating point, and nothing uses the JVM-only `BigInteger`.
