package us.tractat.kuilt.crdt

import kotlinx.serialization.Serializable

/**
 * A Count-Min sketch: approximate per-item frequency over a stream, in fixed
 * `width × depth` memory. Think of it as the frequency complement to a Bloom
 * filter — where a Bloom filter answers "is X present?", a Count-Min sketch
 * answers "how often?".
 *
 * ## How it works
 *
 * The sketch holds a `depth × width` matrix of `Long` cells. Each of the
 * [depth] rows uses an independent hash function. [add] hashes the item into
 * one cell per row and increments those cells. [estimate] hashes the same way
 * and returns the minimum across rows — the cell least likely to be inflated
 * by hash collisions. The estimate never underestimates the true count.
 *
 * ## CRDT merge rule
 *
 * [piece] takes the element-wise **max** of the two count matrices. Max-merge
 * is the convergent, idempotent variant of Count-Min (sometimes called the
 * "conservative" or "max" CMS): a re-delivered patch never inflates the count
 * beyond its highest seen value, so the CRDT satisfies all three lattice laws.
 *
 * Note: max-merge trades a little accuracy for convergence vs. additive merge.
 * For a single-writer counter without distribution constraints, a plain
 * [GCounter] is simpler; Count-Min shines when you need frequency estimates
 * for a large or unbounded key space over a P2P mesh.
 *
 * ## Error guarantee
 *
 * For [width] `w` and [depth] `d` the standard Count-Min error bound holds:
 * the probability that the estimate exceeds the true count by more than
 * `ε × N` (where `ε = e/w`, `N` = total items added across all replicas)
 * is at most `δ = e^-d`.
 *
 * For example `width = 512, depth = 5` gives `ε ≈ 0.005, δ ≈ 0.007`.
 *
 * ## Hash family
 *
 * One hash function per row, implemented as a seeded multiply-shift hash over
 * the item's UTF-8 bytes. Seed for row `i` is `(i + 1) * 2654435761L` (a
 * Fibonacci-derived multiplier). The hash is entirely self-contained — no
 * external dependencies.
 *
 * ## Use
 *
 * Analytics, rate-limiting, adaptive routing over a P2P mesh. Works on all
 * kuilt targets: JVM, Android, iOS, macOS, wasmJs.
 *
 * @param width  Number of columns in each row (larger = lower error rate).
 * @param depth  Number of independent hash rows (larger = lower failure probability).
 *
 * @sample us.tractat.kuilt.crdt.sampleCountMinSketch
 * @sample us.tractat.kuilt.crdt.sampleCountMinSketchMerge
 */
@Serializable
public class CountMinSketch private constructor(
    public val width: Int,
    public val depth: Int,
    private val cells: Array<LongArray>,
) : Quilted<CountMinSketch> {

    init {
        require(width >= 1) { "width must be ≥ 1, was $width" }
        require(depth >= 1) { "depth must be ≥ 1, was $depth" }
    }

    /**
     * Add one occurrence of [item] to this sketch. Returns a [Patch] carrying
     * the delta; the receiver is unchanged.
     *
     * The delta cell for each row carries the receiver's current cell value + 1
     * (an absolute value, not a relative increment). This is correct for
     * max-merge: when any replica absorbs the patch via [piece], [maxOf] picks
     * up the new value only if it exceeds what the replica already knows.
     */
    public fun add(item: String): Patch<CountMinSketch> {
        val delta = zeroCells(width, depth)
        val hashBytes = item.encodeToByteArray()
        for (row in 0 until depth) {
            val col = columnFor(hashBytes, row, width)
            delta[row][col] = cells[row][col] + 1L
        }
        return Patch(CountMinSketch(width, depth, delta))
    }

    /**
     * Estimate the number of times [item] has been added. Because replicas merge
     * via element-wise maximum, a merged sketch reflects the replica that observed
     * the item most — it is NOT the sum across replicas. The estimate is always ≥
     * the true count seen by any single merged replica, and within the Count-Min
     * error bound with high probability.
     */
    public fun estimate(item: String): Long {
        val hashBytes = item.encodeToByteArray()
        var min = cells[0][columnFor(hashBytes, 0, width)]
        for (row in 1 until depth) {
            val v = cells[row][columnFor(hashBytes, row, width)]
            if (v < min) min = v
        }
        return min
    }

    /** The join: element-wise max of the two count matrices. */
    override fun piece(other: CountMinSketch): CountMinSketch {
        require(width == other.width && depth == other.depth) {
            "Cannot merge CountMinSketches with different dimensions: " +
                "(${width}×${depth}) vs (${other.width}×${other.depth})"
        }
        val merged = Array(depth) { row ->
            LongArray(width) { col -> maxOf(cells[row][col], other.cells[row][col]) }
        }
        return CountMinSketch(width, depth, merged)
    }

    override fun equals(other: Any?): Boolean {
        if (other !is CountMinSketch) return false
        if (width != other.width || depth != other.depth) return false
        return cells.indices.all { row -> cells[row].contentEquals(other.cells[row]) }
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + depth
        for (row in cells) result = 31 * result + row.contentHashCode()
        return result
    }

    override fun toString(): String = "CountMinSketch(width=$width, depth=$depth)"

    public companion object {

        /**
         * An empty sketch with the given [width] and [depth]. Both must be
         * at least 1.
         *
         * Larger [width] → lower error rate (`ε = e/width`).
         * Larger [depth] → lower failure probability (`δ = e^-depth`).
         */
        public fun empty(width: Int, depth: Int): CountMinSketch =
            CountMinSketch(width, depth, zeroCells(width, depth))

        /**
         * Row-seed for row [row]: a Fibonacci-derived multiplier so each row
         * gets an independent hash function.
         */
        private fun seedFor(row: Int): Long = (row + 1).toLong() * 2654435761L

        /**
         * Multiply-shift hash of [bytes] for [row], mapped to `[0, width)`.
         *
         * This is a simple, dependency-free hash adequate for frequency
         * sketching. It is NOT cryptographically secure.
         */
        internal fun columnFor(bytes: ByteArray, row: Int, width: Int): Int {
            var h = seedFor(row)
            for (b in bytes) {
                h = h * 6364136223846793005L + b.toLong()
            }
            // Mix the high and low bits, then map to [0, width).
            h = h xor (h ushr 33)
            h *= -49064778989728563L
            h = h xor (h ushr 33)
            val positive = h and Long.MAX_VALUE
            return (positive % width).toInt()
        }

        private fun zeroCells(width: Int, depth: Int): Array<LongArray> =
            Array(depth) { LongArray(width) }
    }
}
