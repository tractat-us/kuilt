package us.tractat.kuilt.math

import java.math.BigInteger
import java.math.RoundingMode
import java.math.BigDecimal
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The common-test oracle [Big] checked against `BigInteger`, so the oracle every target relies on
 * is itself right before it judges anything.
 */
class BigOracleTest {

    @Test
    fun bigAgreesWithBigInteger() {
        val random = Random(105)
        repeat(50_000) {
            val a = random.nextCornerLong()
            val b = random.nextCornerLong()
            val d = random.nextCornerLong().takeIf { it != 0L } ?: 1L
            val (ja, jb, jd) = Triple(BigInteger.valueOf(a), BigInteger.valueOf(b), BigInteger.valueOf(d))
            val (ka, kb, kd) = Triple(Big.of(a), Big.of(b), Big.of(d))
            assertEquals(ja.toString(), ka.toString(), "of($a)")
            assertEquals(ja.add(jb).toString(), (ka + kb).toString(), "$a + $b")
            assertEquals(ja.subtract(jb).toString(), (ka - kb).toString(), "$a - $b")
            assertEquals(ja.multiply(jb).toString(), (ka * kb).toString(), "$a * $b")
            val product = BigDecimal(ja.multiply(jb))
            assertEquals(product.divide(BigDecimal(jd), 0, RoundingMode.FLOOR).toBigIntegerExact().toString(), (ka * kb).floorDiv(kd).toString())
            assertEquals(product.divide(BigDecimal(jd), 0, RoundingMode.CEILING).toBigIntegerExact().toString(), (ka * kb).ceilDiv(kd).toString())
            val words = Int128.fromTwosComplement(random.nextLong(), random.nextLong())
            val expected = BigInteger.valueOf(words.high).shiftLeft(64).add(BigInteger(java.lang.Long.toUnsignedString(words.low)))
            assertEquals(expected.toString(), Big.of(words).toString(), "words of $words")
        }
    }
}
