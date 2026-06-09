package us.tractat.kuilt.deal

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.measureTime

/**
 * Tier 1 performance benchmarks for SraScheme using ionspin/bignum (pure Kotlin, multiplatform).
 *
 * NOTE: ionspin's ModularBigInteger.pow is a pure-Kotlin implementation without Montgomery
 * multiplication optimisations. Observed JVM medians: ~27ms encrypt (512-bit exponent),
 * ~160ms strip (2048-bit inverse exponent). These are slower than Java's BigInteger.modPow
 * (~1–2ms for 2048-bit RSA), which is used by ElGamalScheme (Task 7, JVM/Android fast path).
 *
 * Thresholds here are set to the realistic ionspin baseline on a modern JVM, not the
 * Java-BigInteger target. Tighten once ElGamalScheme replaces SraScheme on JVM/Android.
 */
class SraSchemeBenchmark {

    @Test
    fun sraEncryptMedianUnder100ms() {
        val scheme = SraScheme()
        val key = scheme.generateKey()
        val data = "card:ACE_OF_SPADES".encodeToByteArray()
        val iterations = 20

        // warm up
        repeat(5) { scheme.encrypt(data, key.encryptKey) }

        val times = LongArray(iterations) {
            measureTime { scheme.encrypt(data, key.encryptKey) }.inWholeMilliseconds
        }
        val median = times.sorted()[iterations / 2]
        println("SRA-2048 encrypt median: ${median}ms (ionspin/bignum baseline; target <100ms; JVM fast path via ElGamalScheme)")
        assertTrue(median < 100, "SRA-2048 encrypt median ${median}ms exceeded 100ms threshold")
    }

    @Test
    fun sraStripMedianUnder400ms() {
        val scheme = SraScheme()
        val key = scheme.generateKey()
        val data = "card:ACE_OF_SPADES".encodeToByteArray()
        val (encrypted, _) = scheme.encrypt(data, key.encryptKey)
        val iterations = 10

        repeat(3) { scheme.strip(encrypted, key.stripKey) }

        val times = LongArray(iterations) {
            measureTime { scheme.strip(encrypted, key.stripKey) }.inWholeMilliseconds
        }
        val median = times.sorted()[iterations / 2]
        println("SRA-2048 strip median: ${median}ms (ionspin/bignum baseline; target <400ms; JVM fast path via ElGamalScheme)")
        assertTrue(median < 400, "SRA-2048 strip median ${median}ms exceeded 400ms threshold")
    }
}
