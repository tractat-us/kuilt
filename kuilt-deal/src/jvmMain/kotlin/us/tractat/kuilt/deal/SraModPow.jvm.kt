package us.tractat.kuilt.deal

import java.math.BigInteger

/**
 * JVM fast path: `java.math.BigInteger.modPow` is a native intrinsic, ~40× faster
 * than the pure-Kotlin ionspin path used on platforms without a native BigInteger.
 * `BigInteger(1, …)` reads each argument as an unsigned magnitude; the result is
 * canonicalized by [sraModPowCanonical].
 */
internal actual fun sraModPow(base: ByteArray, exponent: ByteArray, modulus: ByteArray): ByteArray =
    BigInteger(1, base).modPow(BigInteger(1, exponent), BigInteger(1, modulus)).toByteArray()
