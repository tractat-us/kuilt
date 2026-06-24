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
        val epsilon = Math.E / width  // ≈ 0.0053

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

    // ── Width/depth configuration ─────────────────────────────────────────────

    @Test
    fun requiresWidthAtLeastOne() {
        assertFailsWith<IllegalArgumentException> { CountMinSketch.empty(width = 0, depth = 4) }
    }

    @Test
    fun requiresDepthAtLeastOne() {
        assertFailsWith<IllegalArgumentException> { CountMinSketch.empty(width = 16, depth = 0) }
    }
}
