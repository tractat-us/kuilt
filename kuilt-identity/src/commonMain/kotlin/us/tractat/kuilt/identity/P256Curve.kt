package us.tractat.kuilt.identity

import com.ionspin.kotlin.bignum.integer.BigInteger
import com.ionspin.kotlin.bignum.integer.Sign

/**
 * The two P-256 constants the on-curve check needs, from SEC 2 v2.0 § 2.4.2 (secp256r1): the field
 * prime `p` and the curve coefficient `b`. The curve is `y² = x³ − 3x + b` over the integers mod `p`.
 */
internal object P256 {
    val P: BigInteger = BigInteger.parseString("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16)
    val B: BigInteger = BigInteger.parseString("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16)

    private val EULER_EXPONENT: BigInteger = (P - BigInteger.ONE) / BigInteger.TWO
    private val THREE: BigInteger = BigInteger(3)

    /**
     * Whether some point on P-256 has this [x], for `0 ≤ x < p`: Euler's criterion on
     * `rhs = x³ − 3x + b`. A nonzero `rhs` is a square mod `p` exactly when `rhs^((p−1)/2) ≡ 1`; a
     * zero one is `y = 0`, which P-256 never reaches (its order is prime, so it has no point of order
     * two), and is allowed only because it is a square.
     */
    fun hasPointAt(x: BigInteger): Boolean {
        val rhs = (x.pow(3) - THREE * x + B) mod P
        return rhs.isZero() || rhs.toModularBigInteger(P).pow(EULER_EXPONENT).toBigInteger() == BigInteger.ONE
    }
}

/**
 * Whether a 33-byte SEC 1 compressed point names a point on P-256: a `0x02`/`0x03` lead byte, an
 * `x` below `p`, and an `x` at which the curve has a point. The lead byte then picks which of the
 * two `y` values, so nothing more is needed.
 *
 * This is validation, not decompression: the provider still computes `y`. It exists because
 * cryptography-kotlin 0.6.0's JDK decoder takes `rhs^((p+1)/4)` and never checks that the result
 * squares back to `rhs`, nor that `x < p`, so an off-curve `x` decodes into a key there (#3201).
 * Running the check here, in common code, gives every provider the same answer.
 */
internal fun isOnCurve(compressed: ByteArray): Boolean {
    if (compressed.size != COMPRESSED_POINT_SIZE) return false
    if (compressed[0] != 0x02.toByte() && compressed[0] != 0x03.toByte()) return false
    val x = BigInteger.fromByteArray(compressed.copyOfRange(1, COMPRESSED_POINT_SIZE), Sign.POSITIVE)
    return x < P256.P && P256.hasPointAt(x)
}

internal const val COMPRESSED_POINT_SIZE: Int = 33

/** Not yet implemented: the failing-test commit's stub. */
internal fun decompress(compressed: ByteArray): ByteArray? = null
