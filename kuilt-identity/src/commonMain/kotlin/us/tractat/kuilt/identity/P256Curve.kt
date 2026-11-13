package us.tractat.kuilt.identity

import com.ionspin.kotlin.bignum.integer.BigInteger

internal object P256 {
    val P: BigInteger = BigInteger.parseString("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16)

    fun hasPointAt(x: BigInteger): Boolean = true
}

internal fun isOnCurve(compressed: ByteArray): Boolean = true
