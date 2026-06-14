package us.tractat.kuilt.deal

import com.ionspin.kotlin.bignum.integer.BigInteger
import com.ionspin.kotlin.bignum.integer.Sign

/**
 * wasmJs path: JS `BigInt` is not exposed through Kotlin's stdlib here, so use the
 * pure-Kotlin ionspin/bignum implementation. Same algorithm and output as the
 * original common code; the result is canonicalized by [sraModPowCanonical].
 */
internal actual fun sraModPow(base: ByteArray, exponent: ByteArray, modulus: ByteArray): ByteArray {
    val p = BigInteger.fromByteArray(modulus, Sign.POSITIVE)
    val m = BigInteger.fromByteArray(base, Sign.POSITIVE)
    val e = BigInteger.fromByteArray(exponent, Sign.POSITIVE)
    return m.toModularBigInteger(p).pow(e).toBigInteger().toByteArray()
}
