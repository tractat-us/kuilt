package us.tractat.kuilt.crdt

import kotlinx.serialization.json.Json
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CountMinSketchTest {

    // ── Lattice laws ──────────────────────────────────────────────────────────

    @Test
    fun idempotent() {
        val sketch = CountMinSketch.empty(width = 16, depth = 4)
            .piece(CountMinSketch.empty(width = 16, depth = 4).add("hello"))
        assertEquals(sketch, sketch.piece(sketch))
    }

    @Test
    fun commutative() {
        val a = CountMinSketch.empty(width = 16, depth = 4).add("a")
        val b = CountMinSketch.empty(width = 16, depth = 4).add("b")
        assertEquals(a.delta.piece(b.delta), b.delta.piece(a.delta))
    }

    @Test
    fun associative() {
        val base = CountMinSketch.empty(width = 16, depth = 4)
        val a = base.add("alpha").delta
        val b = base.add("beta").delta
        val c = base.add("gamma").delta
        assertEquals(a.piece(b).piece(c), a.piece(b.piece(c)))
    }

    // ── Core semantics ────────────────────────────────────────────────────────

    @Test
    fun emptySketchEstimatesZeroForAnyItem() {
        val sketch = CountMinSketch.empty(width = 32, depth = 4)
        assertAll(
            { assertEquals(0L, sketch.estimate("anything")) },
            { assertEquals(0L, sketch.estimate("")) },
            { assertEquals(0L, sketch.estimate("xyz")) },
        )
    }

    @Test
    fun addOnceEstimatesAtLeastOne() {
        var sketch = CountMinSketch.empty(width = 64, depth = 4)
        sketch = sketch.piece(sketch.add("hello"))
        assertTrue(sketch.estimate("hello") >= 1L)
    }

    @Test
    fun estimateNeverUnderestimates() {
        var sketch = CountMinSketch.empty(width = 64, depth = 4)
        repeat(10) { sketch = sketch.piece(sketch.add("hello")) }
        assertTrue(sketch.estimate("hello") >= 10L, "estimate ${sketch.estimate("hello")} < 10")
    }

    @Test
    fun estimateIsWithinErrorBandOverLargeSample() {
        // Count-Min with width w and depth d guarantees: P(estimate > true + ε·n) < δ
        // where ε = e/w and δ = e^-d for the standard CMS error bound.
        // Here width=512, depth=5 → ε ≈ 0.005, δ ≈ 0.007.
        val width = 512
        val depth = 5
        val totalItems = 10_000
        val epsilon = kotlin.math.E / width  // ≈ 0.0053

        var sketch = CountMinSketch.empty(width = width, depth = depth)
        val trueCounts = mutableMapOf<String, Long>()
        // Add a zipf-like distribution: item-0 appears N times, item-1 appears N/2, etc.
        for (i in 0 until 50) {
            val count = (totalItems / (i + 1)).toLong().coerceAtLeast(1L)
            val key = "item-$i"
            trueCounts[key] = count
            repeat(count.toInt()) { sketch = sketch.piece(sketch.add(key)) }
        }

        val n = trueCounts.values.sum()
        val errorBound = (epsilon * n).toLong()

        for ((key, trueCount) in trueCounts) {
            val est = sketch.estimate(key)
            assertTrue(est >= trueCount, "underestimate: estimate($key)=$est < true=$trueCount")
            assertTrue(
                est <= trueCount + errorBound,
                "overestimate: estimate($key)=$est > true=$trueCount + ε·n=$errorBound",
            )
        }
    }

    @Test
    fun addIsDeltaState_receiverUnchanged() {
        val original = CountMinSketch.empty(width = 16, depth = 4)
        original.add("x")  // returns Patch; original must be unchanged
        assertEquals(0L, original.estimate("x"))
    }

    @Test
    fun mergeMaxNotSum_idempotentOnRedelivery() {
        // Simulates receiving the same patch twice (re-delivery). With max-merge the
        // count must not grow beyond the single-add value.
        var sketch = CountMinSketch.empty(width = 32, depth = 4)
        val patch = sketch.add("hello")
        sketch = sketch.piece(patch)
        val afterFirst = sketch.estimate("hello")
        sketch = sketch.piece(patch)  // re-deliver same patch
        assertEquals(afterFirst, sketch.estimate("hello"), "max-merge must be idempotent")
    }

    @Test
    fun mergeCombinesDistinctReplicas() {
        var a = CountMinSketch.empty(width = 64, depth = 4)
        var b = CountMinSketch.empty(width = 64, depth = 4)
        repeat(5) { a = a.piece(a.add("shared")) }
        repeat(3) { b = b.piece(b.add("shared")) }

        // After merging, estimate should be >= max(5, 3) = 5
        val merged = a.piece(b)
        assertTrue(merged.estimate("shared") >= 5L)
    }

    // ── Serialization ─────────────────────────────────────────────────────────

    @Test
    fun roundTripsThroughJson() {
        var sketch = CountMinSketch.empty(width = 16, depth = 4)
        repeat(7) { sketch = sketch.piece(sketch.add("test")) }
        val encoded = Json.encodeToString(CountMinSketch.serializer(), sketch)
        val decoded = Json.decodeFromString(CountMinSketch.serializer(), encoded)
        assertAll(
            { assertEquals(sketch, decoded) },
            { assertEquals(sketch.estimate("test"), decoded.estimate("test")) },
        )
    }

    // ── Hash stability (golden vectors) ──────────────────────────────────────

    /**
     * Pins the column assignments produced by [CountMinSketch.columnFor].
     *
     * **Wire-breaking warning:** if these values change, persisted matrices are
     * invalidated — every stored estimate silently maps to the wrong cells after
     * an upgrade. Any change to the hash algorithm requires a migration strategy.
     *
     * Algorithm: MurmurHash3_x86_32(item, seed = rowIndex) mod width.
     * Vectors independently verified against the canonical smhasher reference.
     */
    @Test
    fun hashStabilityGoldenVector() {
        assertAll(
            { assertEquals(7, CountMinSketch.columnFor("hello", row = 0, width = 16)) },
            { assertEquals(13, CountMinSketch.columnFor("hello", row = 1, width = 16)) },
            { assertEquals(13, CountMinSketch.columnFor("hello", row = 2, width = 16)) },
            { assertEquals(5, CountMinSketch.columnFor("hello", row = 3, width = 16)) },
            { assertEquals(3, CountMinSketch.columnFor("world", row = 0, width = 8)) },
            { assertEquals(7, CountMinSketch.columnFor("world", row = 1, width = 8)) },
            { assertEquals(7, CountMinSketch.columnFor("world", row = 2, width = 8)) },
        )
    }

    // ── Width/depth configuration ─────────────────────────────────────────────

    @Test
    fun requiresWidthAtLeastOne() {
        assertFailsWith<IllegalArgumentException> { CountMinSketch.empty(width = 0, depth = 4) }
    }

    @Test
    fun requiresDepthAtLeastOne() {
        assertFailsWith<IllegalArgumentException> { CountMinSketch.empty(width = 16, depth = 0) }
    }

    @Test
    fun requiresValidDimensionsOnDeserialization() {
        // kotlinx-serialization bypasses the private constructor, so the init block
        // must fire to reject invalid dimensions that arrive through the wire.
        val badJson = """{"width":0,"depth":4,"cells":[]}"""
        assertFailsWith<IllegalArgumentException> {
            Json.decodeFromString(CountMinSketch.serializer(), badJson)
        }
    }

    // ── Cell overflow (#3397) ─────────────────────────────────────────────────

    /**
     * A one-cell sketch whose cell holds [count]. Every item hashes to that cell, so it stands for
     * a sketch that has seen [count] occurrences without making the test add them. The decode
     * goes through the type's own serializer, the way a full state arrives over anti-entropy.
     */
    private fun oneCellAt(count: Long): CountMinSketch =
        Json.decodeFromString(CountMinSketch.serializer(), """{"width":1,"depth":1,"cells":[[$count]]}""")

    /**
     * An add that would carry a cell past [Long.MAX_VALUE] is refused. Before the fix the delta
     * carried a wrapped, negative cell, and [CountMinSketch.piece], a max, discarded it: the
     * occurrence was lost on every replica with nothing to say so. The first assertion is the rig:
     * the cell really is full before the add.
     */
    @Test
    fun addRefusesAnOccurrenceThatWouldOverflowItsCell() {
        val full = oneCellAt(Long.MAX_VALUE)
        assertAll(
            { assertEquals(Long.MAX_VALUE, full.estimate("x"), "the rig's cell is at Long.MAX_VALUE") },
            { assertFailsWith<ArithmeticException> { full.add("x") } },
        )
    }

    /**
     * One full row is enough to refuse the add, even when the estimate reads from a row with room:
     * a delta that raised the other rows and dropped the full one would leave the rows disagreeing
     * about what was counted.
     */
    @Test
    fun addRefusesWhenAnyRowsCellIsFull() {
        val oneRowFull = Json.decodeFromString(
            CountMinSketch.serializer(),
            """{"width":1,"depth":2,"cells":[[${Long.MAX_VALUE}],[3]]}""",
        )
        assertAll(
            { assertEquals(3L, oneRowFull.estimate("x"), "the rig's estimate comes from the row with room") },
            { assertFailsWith<ArithmeticException> { oneRowFull.add("x") } },
        )
    }

    /**
     * The boundary on the other side: an add landing exactly on [Long.MAX_VALUE] is kept, and the
     * estimate answers it. Before the fix `estimate` read [Long.MAX_VALUE] as "no row seen" and
     * answered `0` for a full cell.
     */
    @Test
    fun addReachesExactlyLongMaxValue() {
        val nearlyFull = oneCellAt(Long.MAX_VALUE - 1L)
        assertAll(
            { assertEquals(Long.MAX_VALUE - 1L, nearlyFull.estimate("x"), "the rig's cell is one below the ceiling") },
            { assertEquals(Long.MAX_VALUE, nearlyFull.piece(nearlyFull.add("x")).estimate("x")) },
        )
    }

    // ── Wire shape (#3414) ────────────────────────────────────────────────────

    private fun decode(json: String): CountMinSketch = Json.decodeFromString(CountMinSketch.serializer(), json)

    /**
     * A negative cell is refused. The sketch's one guarantee is that it never underestimates, and
     * a negative cell breaks it: it wins the row minimum and reports less than was counted. The
     * first assertion is the rig: the same matrix with the cell positive decodes.
     */
    @Test
    fun decoderRefusesANegativeCell() {
        assertAll(
            { assertEquals(1L, decode("""{"width":2,"depth":1,"cells":[[1,1]]}""").estimate("x"), "the rig's valid matrix decodes") },
            { assertFailsWith<IllegalArgumentException> { decode("""{"width":2,"depth":1,"cells":[[1,-1]]}""") } },
        )
    }

    /** A negative value in a sparse delta is refused too: it is a cell, only carried differently. */
    @Test
    fun decoderRefusesANegativeSparseCell() {
        val sparse = """{"width":2,"depth":1,"cells":[[0]],"sparseCells":[{"row":0,"col":1,"value":%d}]}"""
        assertAll(
            { assertEquals(listOf(CellDelta(0, 1, 1L)), decode(sparse.replace("%d", "1")).sparseCells, "the rig's valid delta decodes") },
            { assertFailsWith<IllegalArgumentException> { decode(sparse.replace("%d", "-1")) } },
        )
    }

    /**
     * A full-state matrix must have exactly [CountMinSketch.depth] rows of exactly
     * [CountMinSketch.width] cells. Before the fix a mis-shaped one decoded, and `estimate` or
     * `piece` failed later with an index error, far from the bad input. The first arm is the rig.
     */
    @Test
    fun decoderRefusesAMatrixOfTheWrongShape() {
        assertAll(
            { assertEquals(2, decode("""{"width":2,"depth":2,"cells":[[0,0],[0,0]]}""").depth, "the rig's 2×2 matrix decodes") },
            { assertFailsWith<IllegalArgumentException>("too few rows") { decode("""{"width":2,"depth":2,"cells":[[0,0]]}""") } },
            { assertFailsWith<IllegalArgumentException>("too many rows") { decode("""{"width":2,"depth":2,"cells":[[0,0],[0,0],[0,0]]}""") } },
            { assertFailsWith<IllegalArgumentException>("a short row") { decode("""{"width":2,"depth":2,"cells":[[0,0],[0]]}""") } },
            { assertFailsWith<IllegalArgumentException>("a long row") { decode("""{"width":2,"depth":2,"cells":[[0,0,0],[0,0]]}""") } },
        )
    }

    /**
     * A sparse cell must address the matrix: row in `[0, depth)`, column in `[0, width)`. Before the
     * fix an out-of-range one decoded and `piece` threw an index error when it was applied.
     */
    @Test
    fun decoderRefusesASparseCellOutsideTheMatrix() {
        fun at(row: Int, col: Int) =
            """{"width":2,"depth":2,"cells":[[0],[0]],"sparseCells":[{"row":$row,"col":$col,"value":1}]}"""
        assertAll(
            { assertEquals(listOf(CellDelta(1, 1, 1L)), decode(at(1, 1)).sparseCells, "the rig's last in-range cell decodes") },
            { assertFailsWith<IllegalArgumentException>("row = depth") { decode(at(2, 0)) } },
            { assertFailsWith<IllegalArgumentException>("negative row") { decode(at(-1, 0)) } },
            { assertFailsWith<IllegalArgumentException>("col = width") { decode(at(0, 2)) } },
            { assertFailsWith<IllegalArgumentException>("negative col") { decode(at(0, -1)) } },
        )
    }

    /**
     * `estimate` on a sparse delta is refused rather than answered. A delta's matrix is a one-wide
     * stub of zeros, so before the fix it answered `0` for an item the delta had just counted. The
     * sketch is one column wide so every column lands inside the stub: the old code answered
     * instead of throwing an index error, which is the silent case. The first assertion is the rig:
     * the delta really carries the occurrence.
     */
    @Test
    fun estimateRefusesASparseDelta() {
        val delta = CountMinSketch.empty(width = 1, depth = 2).add("x").delta
        assertAll(
            { assertEquals(listOf(CellDelta(0, 0, 1L), CellDelta(1, 0, 1L)), delta.sparseCells, "the rig's delta counts x once per row") },
            { assertFailsWith<IllegalStateException> { delta.estimate("x") } },
        )
    }

    /**
     * `add` on a sparse delta is refused for the same reason: it reads the stub, so it would mint
     * a delta of `1` whatever the delta already carried.
     */
    @Test
    fun addRefusesASparseDelta() {
        val delta = CountMinSketch.empty(width = 1, depth = 1).add("x").delta
        assertAll(
            { assertEquals(listOf(CellDelta(0, 0, 1L)), delta.sparseCells, "the rig's delta counts x once") },
            { assertFailsWith<IllegalStateException> { delta.add("x") } },
        )
    }

    // ── ByteArray overloads (#727) ────────────────────────────────────────────

    @Test
    fun byteArrayAddProducesSameResultAsStringAdd() {
        val key = "rate-limit-key"
        val encoded = key.encodeToByteArray()
        var sketchStr = CountMinSketch.empty(width = 64, depth = 4)
        var sketchBytes = CountMinSketch.empty(width = 64, depth = 4)
        repeat(5) {
            sketchStr = sketchStr.piece(sketchStr.add(key))
            sketchBytes = sketchBytes.piece(sketchBytes.add(encoded))
        }
        assertEquals(sketchStr, sketchBytes)
    }

    @Test
    fun byteArrayEstimateMatchesStringEstimate() {
        val key = "event-type"
        val encoded = key.encodeToByteArray()
        var sketch = CountMinSketch.empty(width = 64, depth = 4)
        repeat(7) { sketch = sketch.piece(sketch.add(key)) }
        assertEquals(sketch.estimate(key), sketch.estimate(encoded))
    }

    @Test
    fun cachedEncodingIsIdempotent() {
        // Simulates a caller who caches the ByteArray and reuses it across calls.
        val key = "hot-path-key"
        val encoded = key.encodeToByteArray()
        var sketch = CountMinSketch.empty(width = 32, depth = 4)
        val patch1 = sketch.add(encoded)
        sketch = sketch.piece(patch1)
        val patch2 = sketch.add(encoded)
        sketch = sketch.piece(patch2)
        // max-merge: re-delivery of patch1 must not inflate beyond 2
        sketch = sketch.piece(patch1)
        assertEquals(2L, sketch.estimate(encoded))
    }

    // ── Sparse delta (#726) ───────────────────────────────────────────────────

    @Test
    fun sparseDeltaIsSmallerthanFullMatrix() {
        // At typical dimensions the sparse delta carries only d cells vs d×w.
        val width = 512
        val depth = 5
        val sketch = CountMinSketch.empty(width = width, depth = depth)
        val patch = sketch.add("item")
        val sparseCells = patch.delta.sparseCells
        // Sparse delta has exactly depth entries (one changed cell per row).
        assertEquals(depth, sparseCells!!.size)
        // And is much smaller than the full d×w = 2560 cells.
        assertTrue(sparseCells.size < width * depth, "sparse delta must be smaller than full matrix")
    }

    @Test
    fun sparseDeltaMergesIdenticallyToFullStateDelta() {
        // A sparse patch applied via piece() must yield the same sketch as if
        // the patch had carried the full matrix.
        val sketch = CountMinSketch.empty(width = 16, depth = 4)
        val patch = sketch.add("hello")
        val viaSparsePiece = sketch.piece(patch)

        // Both replicas add "hello" once from empty — must reach the same state.
        var sketchB = CountMinSketch.empty(width = 16, depth = 4)
        sketchB = sketchB.piece(sketchB.add("hello"))
        assertEquals(viaSparsePiece, sketchB)
    }

    @Test
    fun sparseDeltaIsIdempotentOnRedelivery() {
        var sketch = CountMinSketch.empty(width = 32, depth = 4)
        val patch = sketch.add("x")
        sketch = sketch.piece(patch)
        val afterFirst = sketch.estimate("x")
        sketch = sketch.piece(patch)  // re-deliver
        assertEquals(afterFirst, sketch.estimate("x"), "sparse max-merge must be idempotent")
    }

    @Test
    fun sparseDeltasFromDistinctReplicasMergeCorrectly() {
        val base = CountMinSketch.empty(width = 64, depth = 4)
        var a = base
        var b = base
        repeat(5) { a = a.piece(a.add("shared")) }
        repeat(3) { b = b.piece(b.add("shared")) }
        val merged = a.piece(b)
        assertTrue(merged.estimate("shared") >= 5L)
    }
}
