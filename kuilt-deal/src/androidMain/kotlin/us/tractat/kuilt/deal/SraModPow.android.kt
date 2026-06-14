package us.tractat.kuilt.deal

import java.math.BigInteger

/**
 * Android fast path — identical to the JVM `actual`: `java.math.BigInteger.modPow`
 * is a native intrinsic, ~40× faster than the pure-Kotlin ionspin path. The result
 * is canonicalized by [sraModPowCanonical].
 */
internal actual fun sraModPow(base: ByteArray, exponent: ByteArray, modulus: ByteArray): ByteArray =
    BigInteger(1, base).modPow(BigInteger(1, exponent), BigInteger(1, modulus)).toByteArray()
