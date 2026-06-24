package us.tractat.kuilt.crdt

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.IntArraySerializer
import kotlinx.serialization.builtins.LongArraySerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure
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
 * **Delta wire format.** The delta returned by [add] is **sparse**: only the (at
 * most [hashCount]) `Long` words that were modified are included on the wire as
 * `(wordIndex, wordValue)` pairs. A full state (e.g. for anti-entropy) is encoded
 * as a dense `LongArray`. The custom [BloomFilterSerializer] selects the format
 * automatically based on density. See [nonZeroWordCount] and [wordCount].
 *
 * @sample us.tractat.kuilt.crdt.sampleBloomFilter
 */
@Serializable(with = BloomFilterSerializer::class)
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
     *
     * The delta is **sparse**: only the (at most [hashCount]) `Long` words that
     * contain newly-set bits are non-zero. [BloomFilterSerializer] encodes these
     * as `(wordIndex, wordValue)` pairs — O(k) wire bytes — instead of the full
     * O(m) bit array.
     */
    public fun add(element: String): Patch<BloomFilter> = Patch(sparseAddDelta(element))

    /** Total number of [Long] words in the underlying bit array. */
    public val wordCount: Int get() = bits.size

    /**
     * Number of non-zero [Long] words in the underlying bit array.
     *
     * For a delta produced by [add], this is at most [hashCount] (one word per
     * distinct bit position). For a full merged state it reflects how densely the
     * filter is populated.
     */
    public fun nonZeroWordCount(): Int = bits.count { it != 0L }

    /**
     * Returns a full-array [BloomFilter] delta for [element] — every word in the
     * backing array is present, with zeros for words not touched by this add.
     * This is the behaviour [add] had before the sparse-delta optimisation and is
     * kept `internal` for round-trip correctness tests.
     */
    internal fun addToFullArray(element: String): BloomFilter {
        val fullDelta = LongArray(bits.size)
        val (h1, h2) = hashPair(element)
        for (i in 0 until hashCount) {
            val bit = positiveMod(h1 + i.toLong() * h2, bitCount.toLong()).toInt()
            fullDelta[bit ushr 6] = fullDelta[bit ushr 6] or (1L shl (bit and 63))
        }
        return BloomFilter(bitCount, hashCount, fullDelta)
    }

    /**
     * Expose the raw [bits] array for serialization — only [BloomFilterSerializer]
     * should call this.
     */
    internal fun bitsArray(): LongArray = bits

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

    /**
     * Builds a sparse delta [BloomFilter] for [element]: a filter whose backing
     * array has the full word-count length, but only the words that contain at
     * least one bit set by this element are non-zero. [BloomFilterSerializer]
     * encodes only the non-zero words as `(wordIndex, wordValue)` pairs, achieving
     * O(k) wire size instead of O(m) for the full array.
     */
    private fun sparseAddDelta(element: String): BloomFilter {
        val (h1, h2) = hashPair(element)
        val changedWords = mutableMapOf<Int, Long>()
        for (i in 0 until hashCount) {
            val bit = positiveMod(h1 + i.toLong() * h2, bitCount.toLong()).toInt()
            val wordIdx = bit ushr 6
            changedWords[wordIdx] = (changedWords[wordIdx] ?: 0L) or (1L shl (bit and 63))
        }
        val sparseBits = LongArray(bits.size)
        for ((idx, word) in changedWords) sparseBits[idx] = word
        return BloomFilter(bitCount, hashCount, sparseBits)
    }

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

        /** Reconstruct a [BloomFilter] from raw parts — used by [BloomFilterSerializer]. */
        internal fun reconstruct(bitCount: Int, hashCount: Int, bits: LongArray): BloomFilter =
            BloomFilter(bitCount, hashCount, bits)

        // ── Sizing formulas ──────────────────────────────────────────────────

        private fun optimalBitCount(n: Int, p: Double): Int =
            ceil(-n * ln(p) / (ln(2.0) * ln(2.0))).toInt().coerceAtLeast(1)

        private fun optimalHashCount(m: Int, n: Int): Int =
            ((m.toDouble() / n) * ln(2.0)).roundToInt().coerceAtLeast(1)

        internal fun longArraySize(bits: Int): Int = (bits + 63) ushr 6

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

/**
 * Custom [KSerializer] for [BloomFilter] that chooses between two wire formats
 * based on density, to keep delta messages O(k) instead of O(m):
 *
 * - **Sparse** (`fmt = 0`): when [BloomFilter.nonZeroWordCount] ≤ [SPARSE_THRESHOLD].
 *   Encodes only the non-zero `(wordIndex: Int, wordValue: Long)` pairs as two
 *   parallel `IntArray` + `LongArray`. An `add()` delta spans at most k words —
 *   k=7 for a 1%-rate filter — so the wire payload is ~84 bytes vs ~12 KB dense
 *   for n=10 000.
 *
 * - **Dense** (`fmt = 1`): for fully-populated states (anti-entropy FullState).
 *   Encodes the full `LongArray` as before. Crossover point: once more than
 *   [SPARSE_THRESHOLD] words are non-zero the overhead of the index array exceeds
 *   the savings from omitting zeros.
 *
 * Wire format (struct with `fmt` discriminator):
 * ```
 * { "fmt": 0, "bc": <bitCount>, "hc": <hashCount>,
 *   "wi": <IntArray of word indices>, "wv": <LongArray of word values> }   // sparse
 * { "fmt": 1, "bc": <bitCount>, "hc": <hashCount>, "bits": <LongArray> }  // dense
 * ```
 */
@OptIn(ExperimentalSerializationApi::class)
public class BloomFilterSerializer : KSerializer<BloomFilter> {

    private val intArraySer = IntArraySerializer()
    private val longArraySer = LongArraySerializer()

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("BloomFilter") {
        element<Int>("fmt")              // 0 = sparse, 1 = dense
        element<Int>("bc")              // bitCount
        element<Int>("hc")             // hashCount
        element("wi", intArraySer.descriptor, isOptional = true)   // sparse: word indices
        element("wv", longArraySer.descriptor, isOptional = true)  // sparse: word values
        element("bits", longArraySer.descriptor, isOptional = true) // dense: full LongArray
    }

    override fun serialize(encoder: Encoder, value: BloomFilter): Unit =
        encoder.encodeStructure(descriptor) {
            val nonZero = value.nonZeroWordCount()
            if (nonZero <= SPARSE_THRESHOLD) {
                encodeIntElement(descriptor, FMT, FORMAT_SPARSE)
                encodeIntElement(descriptor, BC, value.bitCount)
                encodeIntElement(descriptor, HC, value.hashCount)
                val (indices, words) = sparseWords(value)
                encodeSerializableElement(descriptor, WI, intArraySer, indices)
                encodeSerializableElement(descriptor, WV, longArraySer, words)
            } else {
                encodeIntElement(descriptor, FMT, FORMAT_DENSE)
                encodeIntElement(descriptor, BC, value.bitCount)
                encodeIntElement(descriptor, HC, value.hashCount)
                encodeSerializableElement(descriptor, BITS, longArraySer, value.bitsArray())
            }
        }

    override fun deserialize(decoder: Decoder): BloomFilter = decoder.decodeStructure(descriptor) {
        var fmt = -1
        var bitCount = -1
        var hashCount = -1
        var indices: IntArray? = null
        var words: LongArray? = null
        var bits: LongArray? = null

        mainLoop@ while (true) {
            when (val idx = decodeElementIndex(descriptor)) {
                CompositeDecoder.DECODE_DONE -> break@mainLoop
                FMT  -> fmt       = decodeIntElement(descriptor, FMT)
                BC   -> bitCount  = decodeIntElement(descriptor, BC)
                HC   -> hashCount = decodeIntElement(descriptor, HC)
                WI   -> indices   = decodeSerializableElement(descriptor, WI, intArraySer)
                WV   -> words     = decodeSerializableElement(descriptor, WV, longArraySer)
                BITS -> bits      = decodeSerializableElement(descriptor, BITS, longArraySer)
                else -> throw SerializationException("Unexpected index $idx in BloomFilter")
            }
        }

        val bc = bitCount.takeIf { it > 0 } ?: missingField("bc")
        val hc = hashCount.takeIf { it > 0 } ?: missingField("hc")
        val bitsArray = when (fmt) {
            FORMAT_SPARSE -> expandSparse(bc, indices ?: missingField("wi"), words ?: missingField("wv"))
            FORMAT_DENSE  -> bits ?: missingField("bits")
            else          -> throw SerializationException("Unknown BloomFilter fmt: $fmt")
        }
        BloomFilter.reconstruct(bc, hc, bitsArray)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun sparseWords(filter: BloomFilter): Pair<IntArray, LongArray> {
        val bits = filter.bitsArray()
        val indices = mutableListOf<Int>()
        val words = mutableListOf<Long>()
        for (i in bits.indices) {
            if (bits[i] != 0L) {
                indices += i
                words += bits[i]
            }
        }
        return IntArray(indices.size) { indices[it] } to LongArray(words.size) { words[it] }
    }

    private fun expandSparse(bitCount: Int, indices: IntArray, words: LongArray): LongArray {
        require(indices.size == words.size) {
            "Sparse BloomFilter wi/wv arrays must be the same length"
        }
        val bits = LongArray(BloomFilter.longArraySize(bitCount))
        for (i in indices.indices) bits[indices[i]] = bits[indices[i]] or words[i]
        return bits
    }

    private fun <T> missingField(field: String): T =
        throw SerializationException("BloomFilter missing required field '$field'")

    private companion object {
        const val FMT = 0
        const val BC  = 1
        const val HC  = 2
        const val WI  = 3
        const val WV  = 4
        const val BITS = 5

        const val FORMAT_SPARSE = 0
        const val FORMAT_DENSE  = 1

        /**
         * Use sparse encoding when non-zero word count is at or below this threshold.
         * Each sparse word costs 4 (index) + 8 (value) = 12 bytes; each dense zero
         * word costs 1 byte in CBOR. Crossover: k sparse words cost 12k bytes;
         * the equivalent savings from omitting (m/64 - k) zeros = (m/64 - k) bytes.
         * Sparse wins when 12k < m/64 — i.e. for small k relative to m/64.
         * A conservative threshold of 32 ensures we always use sparse for add() deltas
         * (k ≤ ~15 for any practical Bloom filter configuration) and dense for bulk states.
         */
        const val SPARSE_THRESHOLD = 32
    }
}
