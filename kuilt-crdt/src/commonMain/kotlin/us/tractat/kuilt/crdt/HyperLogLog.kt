package us.tractat.kuilt.crdt

import kotlinx.serialization.Serializable

/**
 * A HyperLogLog cardinality estimator: a sketch that answers "how many distinct
 * items have been added?" using a fixed, small amount of memory instead of
 * keeping the full set.
 *
 * **As a CRDT.** HyperLogLog is a join-semilattice whose [piece] takes the
 * element-wise maximum of the register arrays. Two replicas that independently
 * add overlapping streams converge to the same estimate when merged — no
 * coordination required. This makes it a natural fit for distributed cardinality
 * queries: unique peers, unique events, unique keys observed across the mesh.
 *
 * **Precision.** The [precision] parameter `p` controls the number of registers
 * `m = 2^p` (valid range: 4–18 inclusive). Higher precision reduces the relative
 * standard error (`1.04 / sqrt(m)`) at the cost of memory (`m` bytes). The
 * default (`p = 14`) gives `m = 16384` registers and ~0.81% relative error.
 *
 * **Accuracy.** The estimate uses standard HyperLogLog bias-correction combined
 * with a small-range linear-counting correction (HLL++-style). For very low
 * cardinalities the linear count of empty registers is used; for the rest the
 * harmonic-mean HLL formula applies.
 *
 * **Hash function.** Elements are hashed with a 32-bit MurmurHash3 implemented
 * inline in pure Kotlin — no external library, no platform API, deterministic
 * on all targets. The hash is stable across runs and platforms for any given
 * input string.
 *
 * **Immutable.** [add] does not mutate the receiver; it returns a new instance.
 * [piece] is the join: a new instance whose registers are element-wise max.
 *
 * @param precision `p` in [4, 18]. Registers = `2^p`. Default 14 (~0.81% error).
 *
 * @sample us.tractat.kuilt.crdt.sampleHyperLogLog
 * @sample us.tractat.kuilt.crdt.sampleHyperLogLogMerge
 */
@Serializable
public class HyperLogLog private constructor(
    private val precision: Int,
    /** One byte per register: the maximum leading-zero count + 1 seen so far. */
    private val registers: ByteArray,
) : Quilted<HyperLogLog> {

    /** Number of registers (`m = 2^precision`). */
    public val m: Int get() = registers.size

    /**
     * Add [value] to the sketch. Returns a new sketch; the receiver is unchanged.
     *
     * The value is hashed with MurmurHash3 (32-bit). The top [precision] bits
     * select the register index; the remaining bits determine the position of the
     * leftmost `1` bit (plus one), which is the value stored if larger than the
     * current register entry.
     */
    public fun add(value: String): HyperLogLog {
        val hash = murmur3Hash32(value)
        val (index, leadingZeros) = indexAndRho(hash)
        if (leadingZeros <= registers[index]) return this
        val next = registers.copyOf()
        next[index] = leadingZeros
        return HyperLogLog(precision, next)
    }

    /**
     * Estimate the number of distinct elements that have been added.
     *
     * Uses the HyperLogLog harmonic-mean estimator with bias correction and a
     * small-range linear-counting fallback for low cardinalities.
     */
    public fun estimate(): Long {
        val rawEstimate = rawEstimate()
        return when {
            rawEstimate <= 2.5 * m -> linearCountingOrRaw(rawEstimate)
            else -> rawEstimate.toLong()
        }
    }

    /** The join: element-wise maximum of the two register arrays. */
    override fun piece(other: HyperLogLog): HyperLogLog {
        require(precision == other.precision) {
            "Cannot merge HyperLogLog instances with different precision ($precision vs ${other.precision})"
        }
        val merged = ByteArray(m) { i -> maxOf(registers[i], other.registers[i]) }
        return HyperLogLog(precision, merged)
    }

    override fun equals(other: Any?): Boolean =
        other is HyperLogLog &&
            precision == other.precision &&
            registers.contentEquals(other.registers)

    override fun hashCode(): Int = 31 * precision + registers.contentHashCode()

    override fun toString(): String = "HyperLogLog(p=$precision, estimate=${estimate()})"

    // ── Private helpers ──────────────────────────────────────────────────────

    private fun rawEstimate(): Double {
        val harmonicSum = registers.fold(0.0) { acc, reg -> acc + TWO_POW_NEG[reg.toInt() and 0xFF] }
        return alphaMM(m) / harmonicSum
    }

    private fun linearCountingOrRaw(rawEstimate: Double): Long {
        val emptyRegisters = registers.count { it == 0.toByte() }
        return when {
            emptyRegisters == m -> 0L // completely empty sketch
            emptyRegisters > 0 -> (m * ln(m.toDouble() / emptyRegisters)).toLong().coerceAtLeast(1L)
            else -> rawEstimate.toLong().coerceAtLeast(1L)
        }
    }

    /** Splits the 32-bit hash into a register index and a ρ (leading-zeros + 1) value. */
    private fun indexAndRho(hash: Int): Pair<Int, Byte> {
        // Top p bits select the register index.
        val index = hash ushr (32 - precision)
        // Remaining (32 - p) bits: position of the leftmost 1-bit, counting from bit 1.
        // Shift the low bits to the MSB position so rho() can scan from the top.
        val remaining = 32 - precision
        val shifted = hash shl precision
        val rho = rho(shifted, remaining)
        return index to rho
    }

    public companion object {
        private const val MIN_PRECISION = 4
        private const val MAX_PRECISION = 18

        /**
         * An empty sketch with [precision] `p` in [4, 18] (default 14).
         * All registers are initialised to zero.
         */
        public fun empty(precision: Int = 14): HyperLogLog {
            require(precision in MIN_PRECISION..MAX_PRECISION) {
                "HyperLogLog precision must be in [$MIN_PRECISION, $MAX_PRECISION], was $precision"
            }
            return HyperLogLog(precision, ByteArray(1 shl precision))
        }

        // ── HLL constants ────────────────────────────────────────────────────

        /** Alpha correction constant: α_m · m². */
        private fun alphaMM(m: Int): Double {
            val alpha = when (m) {
                16 -> 0.673
                32 -> 0.697
                64 -> 0.709
                else -> 0.7213 / (1.0 + 1.079 / m)
            }
            return alpha * m * m
        }

        /**
         * Precomputed `2^-i` for i in [0, 33]. Index i is the register value;
         * `TWO_POW_NEG[0]` = 1.0 (empty register contributes 2^0 = 1 to harmonic sum,
         * but an empty register should not occur after the small-range correction).
         */
        private val TWO_POW_NEG: DoubleArray = DoubleArray(34) { i -> 1.0 / (1L shl i) }

        // ── ρ (rho): position of leftmost 1-bit + 1 ──────────────────────────

        /**
         * Returns the position of the first `1` bit in [bits] within [width] bits,
         * scanning from the most-significant bit downward, plus 1. [bits] must be
         * left-aligned (the first bit to test is the MSB of the 32-bit value).
         * If all [width] bits are zero, returns `width + 1`. This is the ρ function
         * from the HLL paper.
         */
        private fun rho(bits: Int, width: Int): Byte {
            var b = bits
            for (pos in 1..width) {
                if (b < 0) return pos.toByte() // MSB is 1 when the value is negative (signed int)
                b = b shl 1
            }
            return (width + 1).toByte()
        }

        // ── Natural log approximation (platform-free) ─────────────────────────

        /**
         * Natural logarithm. Delegates to `kotlin.math.ln`, which is available
         * on all KMP targets. The wrapper keeps call sites within this file
         * uniform and avoids a qualified import at each use.
         */
        private fun ln(x: Double): Double = kotlin.math.ln(x)

        // ── MurmurHash3 (32-bit, seed 0) ─────────────────────────────────────

        /**
         * MurmurHash3 32-bit for a UTF-8 string. Produces a stable, platform-
         * independent hash for any input. Seed is fixed at 0 so the hash is
         * deterministic across all runs and targets.
         *
         * Implementation follows the canonical public-domain reference by
         * Austin Appleby (https://github.com/aappleby/smhasher).
         */
        internal fun murmur3Hash32(key: String, seed: Int = 0): Int {
            val data = key.encodeToByteArray()
            return murmur3Hash32Bytes(data, seed)
        }

        internal fun murmur3Hash32Bytes(data: ByteArray, seed: Int = 0): Int {
            val len = data.size
            val nblocks = len / 4
            var h1 = seed

            val c1 = 0xcc9e2d51.toInt()
            val c2 = 0x1b873593

            // Body — process 4-byte blocks
            for (i in 0 until nblocks) {
                val base = i * 4
                var k1 = (data[base].toInt() and 0xFF) or
                    ((data[base + 1].toInt() and 0xFF) shl 8) or
                    ((data[base + 2].toInt() and 0xFF) shl 16) or
                    ((data[base + 3].toInt() and 0xFF) shl 24)

                k1 = k1 * c1
                k1 = rotl32(k1, 15)
                k1 = k1 * c2

                h1 = h1 xor k1
                h1 = rotl32(h1, 13)
                h1 = h1 * 5 + 0xe6546b64.toInt()
            }

            // Tail — remaining bytes
            val tail = nblocks * 4
            var k1 = 0

            @Suppress("KotlinConstantConditions")
            when (len and 3) {
                3 -> { k1 = k1 xor ((data[tail + 2].toInt() and 0xFF) shl 16); k1 = k1 xor ((data[tail + 1].toInt() and 0xFF) shl 8); k1 = k1 xor (data[tail].toInt() and 0xFF) }
                2 -> { k1 = k1 xor ((data[tail + 1].toInt() and 0xFF) shl 8); k1 = k1 xor (data[tail].toInt() and 0xFF) }
                1 -> k1 = k1 xor (data[tail].toInt() and 0xFF)
            }
            if (len and 3 != 0) {
                k1 = k1 * c1
                k1 = rotl32(k1, 15)
                k1 = k1 * c2
                h1 = h1 xor k1
            }

            // Finalization mix
            h1 = h1 xor len
            h1 = fmix32(h1)

            return h1
        }

        private fun rotl32(x: Int, r: Int): Int = (x shl r) or (x ushr (32 - r))

        private fun fmix32(h: Int): Int {
            var h1 = h
            h1 = h1 xor (h1 ushr 16)
            h1 = h1 * 0x85ebca6b.toInt()
            h1 = h1 xor (h1 ushr 13)
            h1 = h1 * 0xc2b2ae35.toInt()
            h1 = h1 xor (h1 ushr 16)
            return h1
        }
    }
}
