package us.tractat.kuilt.crdt

import kotlinx.serialization.Serializable
import us.tractat.kuilt.crdt.internal.Murmur3

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
 * standard error (`1.04 / sqrt(m)`) at the cost of memory (`ceil(m * 6 / 8)` bytes).
 * The default (`p = 14`) gives `m = 16384` registers and ~0.81% relative error.
 *
 * **Storage.** Registers are packed at **6 bits per register** (values 0–63), reducing
 * state size by 25% compared to the previous 1-byte-per-register form. The backing
 * `ByteArray` has size `ceil(m * 6 / 8)`. Register `i` spans bits `[i*6, i*6+5]`
 * across at most two consecutive bytes. See [getRegister] and [setRegisterInto].
 *
 * **Wire format.** The packed register array is serialized directly. This is a
 * **wire-breaking change** vs the previous 1-byte-per-register format (pre-1.0,
 * intentional). Old-format data is not compatible.
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
 * **Immutable.** [add] does not mutate the receiver; it returns a [Patch]. The
 * delta inside the patch is a sparse fragment: only the changed register is
 * non-zero (all others are zero). For a no-op add (the new ρ does not exceed
 * the stored max) the delta is all-zero — an empty fragment that Quilter can
 * skip without broadcasting.
 *
 * [piece] is the join: a new instance whose registers are element-wise max.
 * A sparse delta is a valid lattice fragment — element-wise max makes the join
 * correct and idempotent regardless of how many registers are non-zero.
 *
 * @param precision `p` in [4, 18]. Registers = `2^p`. Default 14 (~0.81% error).
 *
 * @sample us.tractat.kuilt.crdt.sampleHyperLogLog
 * @sample us.tractat.kuilt.crdt.sampleHyperLogLogMerge
 */
@Serializable
public class HyperLogLog private constructor(
    private val precision: Int,
    /**
     * 6-bit-per-register packed backing store. Size = ceil(m * 6 / 8).
     * Register `i` occupies bits [i*6, i*6+5] across at most two consecutive bytes.
     */
    private val registers: ByteArray,
) : Quilted<HyperLogLog> {

    /** Number of registers (`m = 2^precision`). */
    public val m: Int get() = 1 shl precision

    /** Size of the packed backing store in bytes: `ceil(m * 6 / 8)`. */
    public val packedByteSize: Int get() = registers.size

    /**
     * Add [value] to the sketch. Returns a [Patch] whose delta is a sparse
     * fragment — at most one register is non-zero (the one that changed). If
     * the new ρ value does not exceed the currently stored max the delta is
     * all-zero (a no-op fragment; Quilter skips broadcasting it).
     *
     * The receiver is unchanged. Apply the patch with [piece]:
     * `val next = hll.piece(hll.add(value))`.
     *
     * The value is hashed with [us.tractat.kuilt.crdt.internal.Murmur3] (32-bit). The top [precision] bits
     * select the register index; the remaining bits determine the position of the
     * leftmost `1` bit (plus one), which is the value stored if larger than the
     * current register entry.
     */
    public fun add(value: String): Patch<HyperLogLog> {
        val hash = Murmur3.hash32(value)
        val (index, leadingZeros) = indexAndRho(hash)
        if (leadingZeros <= getRegister(index)) return emptyPatch()
        val sparse = ByteArray(packedSize(m))
        setRegisterInto(sparse, index, leadingZeros)
        return Patch(HyperLogLog(precision, sparse))
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
        val merged = ByteArray(packedSize(m))
        for (i in 0 until m) {
            setRegisterInto(merged, i, maxOf(getRegister(i), other.getRegister(i)))
        }
        return HyperLogLog(precision, merged)
    }

    override fun equals(other: Any?): Boolean =
        other is HyperLogLog &&
            precision == other.precision &&
            registers.contentEquals(other.registers)

    override fun hashCode(): Int = 31 * precision + registers.contentHashCode()

    override fun toString(): String = "HyperLogLog(p=$precision, estimate=${estimate()})"

    // ── Internal test support ────────────────────────────────────────────────

    /** Number of non-zero registers. Exposed for testing sparse delta invariants. */
    internal fun nonZeroRegisterCount(): Int = (0 until m).count { i -> getRegister(i) != 0.toByte() }

    // ── Private: register accessors ──────────────────────────────────────────

    /**
     * Reads the 6-bit register value at logical index [index] from [registers].
     * Register `i` occupies bits `[i*6 .. i*6+5]` of the packed byte array,
     * spanning at most two consecutive bytes.
     */
    private fun getRegister(index: Int): Byte {
        val bitPos = index * 6
        val byteIndex = bitPos ushr 3          // bitPos / 8
        val bitOffset = bitPos and 7           // bitPos % 8
        return if (bitOffset <= 2) {
            // All 6 bits fit in one byte (offsets 0, 1, 2).
            ((registers[byteIndex].toInt() and 0xFF) ushr (2 - bitOffset) and 0x3F).toByte()
        } else {
            // 6 bits span two consecutive bytes (offsets 3..7).
            val bitsInFirst = 8 - bitOffset    // bits contributed by registers[byteIndex]
            val hi = (registers[byteIndex].toInt() and 0xFF) and ((1 shl bitsInFirst) - 1)
            val bitsInSecond = 6 - bitsInFirst
            val lo = (registers[byteIndex + 1].toInt() and 0xFF) ushr (8 - bitsInSecond)
            ((hi shl bitsInSecond) or lo).toByte()
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private fun emptyPatch(): Patch<HyperLogLog> = Patch(HyperLogLog(precision, ByteArray(packedSize(m))))

    private fun rawEstimate(): Double {
        var harmonicSum = 0.0
        for (i in 0 until m) {
            harmonicSum += TWO_POW_NEG[getRegister(i).toInt() and 0xFF]
        }
        return alphaMM(m) / harmonicSum
    }

    private fun linearCountingOrRaw(rawEstimate: Double): Long {
        var emptyRegisters = 0
        for (i in 0 until m) {
            if (getRegister(i) == 0.toByte()) emptyRegisters++
        }
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
            return HyperLogLog(precision, ByteArray(packedSize(1 shl precision)))
        }

        /** Packed backing store size in bytes for [m] registers at 6 bits/register. */
        internal fun packedSize(m: Int): Int = (m * 6 + 7) / 8

        /**
         * Writes a 6-bit [value] (0–63) into register [index] of [backing].
         * Pure function on the array (no allocation). Companion placement allows
         * reuse from [add] without a receiver.
         */
        internal fun setRegisterInto(backing: ByteArray, index: Int, value: Byte) {
            val v = value.toInt() and 0x3F   // clamp to 6 bits
            val bitPos = index * 6
            val byteIndex = bitPos ushr 3
            val bitOffset = bitPos and 7
            if (bitOffset <= 2) {
                // All 6 bits fit in one byte.
                val shift = 2 - bitOffset
                val mask = 0x3F shl shift
                backing[byteIndex] = ((backing[byteIndex].toInt() and 0xFF and mask.inv()) or (v shl shift)).toByte()
            } else {
                // 6 bits span two consecutive bytes.
                val bitsInFirst = 8 - bitOffset
                val bitsInSecond = 6 - bitsInFirst
                // High bitsInFirst bits of v go into the low bitsInFirst bits of backing[byteIndex].
                val maskFirst = (1 shl bitsInFirst) - 1
                val hiPart = (v ushr bitsInSecond) and maskFirst
                backing[byteIndex] = ((backing[byteIndex].toInt() and 0xFF and maskFirst.inv()) or hiPart).toByte()
                // Low bitsInSecond bits of v go into the top bitsInSecond bits of backing[byteIndex+1].
                val shift2 = 8 - bitsInSecond
                val maskSecond = ((1 shl bitsInSecond) - 1) shl shift2
                val loPart = (v and ((1 shl bitsInSecond) - 1)) shl shift2
                backing[byteIndex + 1] = ((backing[byteIndex + 1].toInt() and 0xFF and maskSecond.inv()) or loPart).toByte()
            }
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

    }
}
