package us.tractat.kuilt.identity

import com.ionspin.kotlin.bignum.integer.BigInteger
import com.ionspin.kotlin.bignum.integer.Sign

/**
 * The P-256 constants this module needs, from SEC 2 v2.0 § 2.4.2 (secp256r1): the field prime `p` and
 * the curve coefficient `b` for point decompression, and the group order `n` that bounds a private
 * scalar. The curve is `y² = x³ − 3x + b` over the integers mod `p`.
 */
internal object P256 {
    val P: BigInteger = BigInteger.parseString("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16)
    val B: BigInteger = BigInteger.parseString("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16)
    val N: BigInteger = BigInteger.parseString("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16)

    private val EULER_EXPONENT: BigInteger = (P - BigInteger.ONE) / BigInteger.TWO

    /** `(p + 1) / 4`: since `p ≡ 3 (mod 4)`, a square `rhs` raised to it is one of its square roots. */
    private val ROOT_EXPONENT: BigInteger = (P + BigInteger.ONE) / BigInteger(4)
    private val THREE: BigInteger = BigInteger(3)

    /** `x³ − 3x + b mod p`: the `y²` a point at `x` must have. */
    private fun rhs(x: BigInteger): BigInteger = (x.pow(3) - THREE * x + B) mod P

    /**
     * Whether some point on P-256 has this [x], for `0 ≤ x < p`: Euler's criterion on
     * `rhs = x³ − 3x + b`. A nonzero `rhs` is a square mod `p` exactly when `rhs^((p−1)/2) ≡ 1`; a
     * zero one is `y = 0`, which P-256 never reaches (its order is prime, so it has no point of order
     * two), and is allowed only because it is a square.
     */
    fun hasPointAt(x: BigInteger): Boolean = isSquare(rhs(x))

    /**
     * The `y` of parity [odd] of the point at [x], for `0 ≤ x < p`, or `null` when the curve has no
     * point there. The two roots of `rhs` are `r` and `p − r`, and exactly one is odd because `p` is.
     */
    fun yAt(x: BigInteger, odd: Boolean): BigInteger? {
        val rhs = rhs(x)
        if (!isSquare(rhs)) return null
        val root = rhs.toModularBigInteger(P).pow(ROOT_EXPONENT).toBigInteger()
        if (root.isZero()) return if (odd) null else root
        return if (root.isOdd() == odd) root else P - root
    }

    private fun isSquare(rhs: BigInteger): Boolean =
        rhs.isZero() || rhs.toModularBigInteger(P).pow(EULER_EXPONENT).toBigInteger() == BigInteger.ONE

    private fun BigInteger.isOdd(): Boolean = (this mod BigInteger.TWO) == BigInteger.ONE
}

/**
 * The 65-byte SEC 1 uncompressed form `0x04 ‖ x ‖ y` of a 33-byte compressed point, or `null` when
 * it does not name a point on P-256: a lead byte other than `0x02`/`0x03`, an `x` not below `p`, or
 * an `x` at which the curve has no point (Euler's criterion). `0x02` picks the even `y`, `0x03` the
 * odd one.
 *
 * kuilt decompresses the point itself rather than hand the provider 33 bytes, for two reasons.
 * Safari's and WebKit's WebCrypto refuse a compressed `importKey("raw")` with a `DataError`, while
 * the 65-byte form imports in every browser (#3232). And cryptography-kotlin 0.6.0's JDK decoder
 * takes `rhs^((p+1)/4)` without checking that it squares back to `rhs`, or that `x < p`, so an
 * off-curve `x` would decode there (#3201). Doing both here, in common code, gives every provider
 * the same bytes and the same answer.
 *
 * Each coordinate is exactly 32 bytes, big-endian, padded with leading zeros: about one point in 256
 * has a `y` below `2^248`, and a provider refuses a 64-byte point.
 */
internal fun decompress(compressed: ByteArray): ByteArray? {
    if (compressed.size != COMPRESSED_POINT_SIZE) return null
    val odd = when (compressed[0]) {
        0x02.toByte() -> false
        0x03.toByte() -> true
        else -> return null
    }
    val xBytes = compressed.copyOfRange(1, COMPRESSED_POINT_SIZE)
    val x = BigInteger.fromByteArray(xBytes, Sign.POSITIVE)
    if (x >= P256.P) return null
    val y = P256.yAt(x, odd) ?: return null
    return byteArrayOf(UNCOMPRESSED_LEAD) + xBytes + y.toCoordinate()
}

/** Whether a 33-byte SEC 1 compressed point names a point on P-256; see [decompress]. */
internal fun isOnCurve(compressed: ByteArray): Boolean = decompress(compressed) != null

/** This coordinate as exactly [COORDINATE_SIZE] big-endian bytes, zero-padded on the left. */
private fun BigInteger.toCoordinate(): ByteArray {
    val magnitude = toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
    check(magnitude.size <= COORDINATE_SIZE) { "a coordinate below p does not fit in $COORDINATE_SIZE bytes" }
    return ByteArray(COORDINATE_SIZE - magnitude.size) + magnitude
}

internal const val COMPRESSED_POINT_SIZE: Int = 33
private const val COORDINATE_SIZE = 32
private const val UNCOMPRESSED_LEAD: Byte = 0x04
