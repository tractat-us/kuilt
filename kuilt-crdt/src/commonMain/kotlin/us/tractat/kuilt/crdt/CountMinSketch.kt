package us.tractat.kuilt.crdt

import kotlinx.serialization.Serializable
import us.tractat.kuilt.crdt.internal.Murmur3

/**
 * A single changed cell in a [CountMinSketch] sparse delta: the (row, col) position
 * and the new absolute value after increment.
 *
 * Sparse deltas carry exactly [CountMinSketch.depth] of these — one per hash row —
 * versus the full `depth × width` matrix that would otherwise travel on the wire.
 */
@Serializable
public data class CellDelta(val row: Int, val col: Int, val value: Long)

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
 * One hash function per row, derived from canonical MurmurHash3_x86_32 (Austin
 * Appleby, public domain). Seed for row `i` is `i` (row index), giving each row
 * an independent hash function with proper avalanche. The hash is byte-identical
 * on all Kotlin Multiplatform targets.
 *
 * ## Delta format
 *
 * [add] produces a sparse delta carrying exactly [depth] [CellDelta] entries —
 * one per hash row. Wire cost per add is `O(depth)` rather than `O(depth × width)`.
 * At typical dimensions (w=512, d=5) this reduces gossip bandwidth 256× per event.
 * Full-state anti-entropy still uses the complete `depth × width` matrix; only
 * incremental deltas are sparse.
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
    /**
     * Non-null only for sparse deltas produced by [add]. Contains exactly
     * [depth] entries — one changed cell per hash row. When non-null, [cells]
     * is a stub (all zeros) and [piece] applies the sparse cells rather than
     * scanning the full matrix.
     *
     * Full-state sketches (produced by [empty] or [piece]) have `sparseCells = null`
     * and carry the complete `depth × width` count matrix in [cells].
     */
    public val sparseCells: List<CellDelta>? = null,
) : Quilted<CountMinSketch> {

    init {
        require(width >= 1) { "width must be ≥ 1, was $width" }
        require(depth >= 1) { "depth must be ≥ 1, was $depth" }
    }

    /**
     * Add one occurrence of [encodedItem] to this sketch. Returns a [Patch]
     * carrying a **sparse** delta with exactly [depth] [CellDelta] entries —
     * one per hash row. Wire cost per add is `O(depth)` rather than
     * `O(depth × width)`. The receiver is unchanged.
     *
     * Use this overload when the caller already holds or can cache the UTF-8
     * encoding of the key — it avoids the `ByteArray` allocation that
     * [add(String)][add] performs on every call.
     */
    public fun add(encodedItem: ByteArray): Patch<CountMinSketch> {
        val sparse = buildList(depth) {
            for (row in 0 until depth) {
                val col = columnFor(encodedItem, row, width)
                add(CellDelta(row, col, cells[row][col] + 1L))
            }
        }
        return Patch(CountMinSketch(width, depth, stubCells(depth), sparse))
    }

    /**
     * Add one occurrence of [item] to this sketch. Returns a [Patch] carrying
     * a **sparse** delta with exactly [depth] [CellDelta] entries — one per
     * hash row. The receiver is unchanged.
     *
     * For high-frequency hot paths where the same key is added many times,
     * prefer [add(ByteArray)][add] with a cached encoding to avoid per-call allocation.
     */
    public fun add(item: String): Patch<CountMinSketch> = add(item.encodeToByteArray())

    /**
     * Estimate the number of times [encodedItem] has been added.
     *
     * Use this overload when the caller already holds or can cache the UTF-8
     * encoding of the key — it avoids the `ByteArray` allocation that
     * [estimate(String)][estimate] performs on every call.
     *
     * Because replicas merge via element-wise maximum, a merged sketch reflects
     * the replica that observed the item most — it is NOT the sum across
     * replicas. The estimate is always ≥ the true count seen by any single
     * merged replica, and within the Count-Min error bound with high probability.
     */
    public fun estimate(encodedItem: ByteArray): Long {
        var min = Long.MAX_VALUE
        for (row in 0 until depth) {
            val col = columnFor(encodedItem, row, width)
            val v = cells[row][col]
            if (v < min) min = v
        }
        return if (min == Long.MAX_VALUE) 0L else min
    }

    /**
     * Estimate the number of times [item] has been added. Because replicas merge
     * via element-wise maximum, a merged sketch reflects the replica that observed
     * the item most — it is NOT the sum across replicas. The estimate is always ≥
     * the true count seen by any single merged replica, and within the Count-Min
     * error bound with high probability.
     *
     * For high-frequency hot paths where the same key is estimated many times,
     * prefer [estimate(ByteArray)][estimate] with a cached encoding to avoid per-call allocation.
     */
    public fun estimate(item: String): Long = estimate(item.encodeToByteArray())

    /**
     * The join: element-wise **max** of the two count matrices.
     *
     * Handles sparse deltas produced by [add] efficiently — no full-matrix
     * allocation needed when [other] is a sparse delta. A receiver initialises
     * missing cells to zero; `max(0, v) = v` preserves correctness.
     */
    override fun piece(other: CountMinSketch): CountMinSketch {
        require(width == other.width && depth == other.depth) {
            "Cannot merge CountMinSketches with different dimensions: " +
                "(${width}×${depth}) vs (${other.width}×${other.depth})"
        }
        return when {
            other.sparseCells != null && sparseCells != null -> mergeSparsePairs(other)
            other.sparseCells != null -> applySparse(other.sparseCells)
            sparseCells != null -> other.applySparse(sparseCells)
            else -> mergeFullMatrices(other)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (other !is CountMinSketch) return false
        if (width != other.width || depth != other.depth) return false
        // Sparse deltas compare by their cell list; full-state sketches compare by the matrix.
        return when {
            sparseCells != null && other.sparseCells != null -> sparseCells == other.sparseCells
            sparseCells == null && other.sparseCells == null ->
                cells.indices.all { row -> cells[row].contentEquals(other.cells[row]) }
            else -> false
        }
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + depth
        if (sparseCells != null) {
            result = 31 * result + sparseCells.hashCode()
        } else {
            for (row in cells) result = 31 * result + row.contentHashCode()
        }
        return result
    }

    override fun toString(): String = "CountMinSketch(width=$width, depth=$depth)"

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Apply a list of sparse cell updates (element-wise max) to produce a full-state result. */
    private fun applySparse(sparse: List<CellDelta>): CountMinSketch {
        val merged = Array(depth) { row -> cells[row].copyOf() }
        for ((row, col, value) in sparse) {
            merged[row][col] = maxOf(merged[row][col], value)
        }
        return CountMinSketch(width, depth, merged)
    }

    /** Merge two sparse deltas into a full-state result via a combined max over their cell sets. */
    private fun mergeSparsePairs(other: CountMinSketch): CountMinSketch {
        val merged = zeroCells(width, depth)
        for ((row, col, value) in sparseCells!!) merged[row][col] = maxOf(merged[row][col], value)
        for ((row, col, value) in other.sparseCells!!) merged[row][col] = maxOf(merged[row][col], value)
        return CountMinSketch(width, depth, merged)
    }

    /** Full-matrix element-wise max — used for full-state anti-entropy joins. */
    private fun mergeFullMatrices(other: CountMinSketch): CountMinSketch {
        val merged = Array(depth) { row ->
            LongArray(width) { col -> maxOf(cells[row][col], other.cells[row][col]) }
        }
        return CountMinSketch(width, depth, merged)
    }

    public companion object {

        /**
         * An empty sketch with the given [width] and [depth]. Both must be
         * at least 1.
         *
         * Larger [width] → lower error rate (`ε = e/width`).
         * Larger [depth] → lower failure probability (`δ = e^-depth`).
         */
        public fun empty(width: Int, depth: Int): CountMinSketch {
            require(width >= 1) { "width must be ≥ 1, was $width" }
            require(depth >= 1) { "depth must be ≥ 1, was $depth" }
            return CountMinSketch(width, depth, zeroCells(width, depth))
        }

        /**
         * Column index for [encodedItem] in [row], mapped to `[0, width)`.
         *
         * Uses canonical MurmurHash3_x86_32 seeded by [row], giving each row an
         * independent hash function with proper avalanche. The unsigned hash value
         * is reduced to the column range with a modulo.
         */
        internal fun columnFor(encodedItem: ByteArray, row: Int, width: Int): Int {
            val h = Murmur3.hash32(encodedItem, seed = row).toLong() and 0xFFFF_FFFFL
            return (h % width).toInt()
        }

        /** Column index for [item] in [row]; convenience overload that encodes to UTF-8. */
        internal fun columnFor(item: String, row: Int, width: Int): Int =
            columnFor(item.encodeToByteArray(), row, width)

        private fun zeroCells(width: Int, depth: Int): Array<LongArray> =
            Array(depth) { LongArray(width) }

        /**
         * A minimal stub cell array used for sparse deltas. The cells are all zeros
         * and are not accessed for estimates; only [sparseCells] carries the data.
         */
        private fun stubCells(depth: Int): Array<LongArray> = Array(depth) { LongArray(1) }
    }
}
