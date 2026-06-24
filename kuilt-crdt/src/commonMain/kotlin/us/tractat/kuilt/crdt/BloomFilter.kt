package us.tractat.kuilt.crdt

import kotlinx.serialization.Serializable
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.roundToInt
import us.tractat.kuilt.crdt.internal.Murmur3

/**
 * A probabilistic, mergeable Bloom filter: an approximate set whose merge is
 * **bitwise OR** of the underlying bit array — trivially idempotent, commutative,
 * and associative. A Bloom filter records whether elements *might* have been added
 * and never produces false negatives (every added element always reports present),
 * with a tunable false-positive rate.
 *
 * **Union-only — no removes.** Removal would require clearing bits that may have
 * been set by other elements, breaking the monotone-join property that makes this
 * a CRDT. A Counting Bloom filter supports removes but loses idempotency (a bit
 * decremented twice is wrong), so it does not fit the [Quilted] contract. If you
 * need probabilistic removes, model the filter together with a [GSet] of
 * tombstones.
 *
 * **Hash function.** Kirsch–Mitzenmacher double-hashing using canonical
 * MurmurHash3_x86_32 (Austin Appleby, public domain): `h_i(x) = (h1 + i * h2) mod m`
 * where `h1 = Murmur3(x, seed=0)` and `h2 = Murmur3(x, seed=h1)`, for i ∈ [0, k).
 * See Kirsch & Mitzenmacher (2008) "Less Hashing, Same Performance". The hash is
 * byte-identical on all Kotlin Multiplatform targets.
 *
 * **Merge compatibility.** Two [BloomFilter] instances can only be [piece]d if
 * they were created with the same [bitCount] and [hashCount]. Use [create] with
 * the same [expectedElements] and [falsePositiveRate] on every replica.
 *
 * @sample us.tractat.kuilt.crdt.sampleBloomFilter
 */
@Serializable
public class BloomFilter private constructor(
    /** Number of bits in the underlying bit array. */
    public val bitCount: Int,
    /** Number of hash functions applied per element. */
    public val hashCount: Int,
    /** The bit array, packed into longs (MSB-first within each long). */
    private val bits: LongArray,
) : Quilted<BloomFilter> {

    /**
     * Returns true if [element] *might* have been added; false if it was
     * definitely not added. Never returns a false negative.
     */
    public fun mightContain(element: String): Boolean {
        val (h1, h2) = hashPair(element)
        return (0 until hashCount).all { i ->
            val bit = positiveMod(h1 + i.toLong() * h2, bitCount.toLong()).toInt()
            isBitSet(bit)
        }
    }

    /**
     * Returns a [Patch] that, when absorbed with [piece], marks [element] as
     * present. The receiver is unchanged — mutations are delta-state.
     */
    public fun add(element: String): Patch<BloomFilter> {
        val delta = LongArray(bits.size)
        val (h1, h2) = hashPair(element)
        for (i in 0 until hashCount) {
            val bit = positiveMod(h1 + i.toLong() * h2, bitCount.toLong()).toInt()
            delta[bit ushr 6] = delta[bit ushr 6] or (1L shl (bit and 63))
        }
        return Patch(BloomFilter(bitCount, hashCount, delta))
    }

    /**
     * The join: bitwise OR of both bit arrays. Satisfies idempotency,
     * commutativity, and associativity — the three lattice laws.
     *
     * @throws IllegalArgumentException if [other] was created with a different
     * [bitCount] or [hashCount].
     */
    override fun piece(other: BloomFilter): BloomFilter {
        require(bitCount == other.bitCount && hashCount == other.hashCount) {
            "Cannot merge BloomFilters with different configurations: " +
                "($bitCount bits, $hashCount hashes) vs (${other.bitCount} bits, ${other.hashCount} hashes)"
        }
        val merged = LongArray(bits.size) { bits[it] or other.bits[it] }
        return BloomFilter(bitCount, hashCount, merged)
    }

    override fun equals(other: Any?): Boolean {
        if (other !is BloomFilter) return false
        return bitCount == other.bitCount && hashCount == other.hashCount && bits.contentEquals(other.bits)
    }

    override fun hashCode(): Int = 31 * (31 * bitCount + hashCount) + bits.contentHashCode()

    override fun toString(): String = "BloomFilter(bits=$bitCount, hashes=$hashCount)"

    // ── Private helpers ──────────────────────────────────────────────────────

    private fun isBitSet(bit: Int): Boolean =
        bits[bit ushr 6] and (1L shl (bit and 63)) != 0L

    public companion object {

        /**
         * Create a [BloomFilter] sized for [expectedElements] at the given
         * [falsePositiveRate] (default 1%). The optimal bit count and hash count
         * are derived from the standard formulas:
         *
         * ```
         * m = -n * ln(p) / (ln 2)²
         * k = (m / n) * ln 2
         * ```
         *
         * where n = [expectedElements] and p = [falsePositiveRate].
         */
        public fun create(expectedElements: Int, falsePositiveRate: Double = 0.01): BloomFilter {
            require(expectedElements > 0) { "expectedElements must be positive" }
            require(falsePositiveRate > 0.0 && falsePositiveRate < 1.0) {
                "falsePositiveRate must be in (0, 1)"
            }
            val bits = optimalBitCount(expectedElements, falsePositiveRate)
            val hashes = optimalHashCount(bits, expectedElements)
            return BloomFilter(bits, hashes, LongArray(longArraySize(bits)))
        }

        /**
         * Restore a [BloomFilter] from raw parameters — useful when deserializing
         * from a format other than the built-in kotlinx-serialization path.
         */
        public fun fromRaw(bitCount: Int, hashCount: Int, bits: LongArray): BloomFilter {
            require(bitCount > 0) { "bitCount must be positive" }
            require(hashCount > 0) { "hashCount must be positive" }
            require(bits.size == longArraySize(bitCount)) {
                "bits array size ${bits.size} does not match bitCount $bitCount"
            }
            return BloomFilter(bitCount, hashCount, bits.copyOf())
        }

        // ── Sizing formulas ──────────────────────────────────────────────────

        private fun optimalBitCount(n: Int, p: Double): Int =
            ceil(-n * ln(p) / (ln(2.0) * ln(2.0))).toInt().coerceAtLeast(1)

        private fun optimalHashCount(m: Int, n: Int): Int =
            ((m.toDouble() / n) * ln(2.0)).roundToInt().coerceAtLeast(1)

        private fun longArraySize(bits: Int): Int = (bits + 63) ushr 6

        // ── Hash ─────────────────────────────────────────────────────────────

        /**
         * Returns a pair (h1, h2) for Kirsch–Mitzenmacher double-hashing.
         * `h1 = Murmur3(element, seed=0)` — the base position.
         * `h2 = Murmur3(element, seed=h1)` — the step size, made odd to ensure
         * full-period coverage over the bit array.
         */
        private fun hashPair(element: String): Pair<Long, Long> {
            val h1 = Murmur3.hash32(element, seed = 0).toLong() and 0xFFFF_FFFFL
            val h2 = (Murmur3.hash32(element, seed = h1.toInt()).toLong() and 0xFFFF_FFFFL) or 1L
            return Pair(h1, h2)
        }

        /** Positive modulo — Kotlin's `%` can return negative values for negative dividends. */
        private fun positiveMod(dividend: Long, divisor: Long): Long {
            val r = dividend % divisor
            return if (r < 0L) r + divisor else r
        }
    }
}
