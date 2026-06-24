package us.tractat.kuilt.crdt

import kotlinx.serialization.json.Json
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class HyperLogLogTest {

    // ── Construction ─────────────────────────────────────────────────────────

    @Test
    fun emptyEstimatesZero() {
        assertEquals(0L, HyperLogLog.empty().estimate())
    }

    @Test
    fun precisionMustBeInRange() {
        assertAll(
            { assertFailsWith<IllegalArgumentException> { HyperLogLog.empty(precision = 3) } },
            { assertFailsWith<IllegalArgumentException> { HyperLogLog.empty(precision = 19) } },
        )
    }

    @Test
    fun validPrecisionBoundsAreAccepted() {
        assertAll(
            { HyperLogLog.empty(precision = 4) },
            { HyperLogLog.empty(precision = 18) },
        )
    }

    // ── Single-element accuracy ───────────────────────────────────────────────

    @Test
    fun singleElementEstimatesOne() {
        val hll = HyperLogLog.empty()
        val next = hll.add("hello")
        // small-cardinality correction: exact for n=1
        assertTrue(next.estimate() in 1L..2L, "expected ≈1, got ${next.estimate()}")
    }

    // ── CRDT laws ─────────────────────────────────────────────────────────────

    @Test
    fun pieceIsIdempotent() {
        val hll = addMany(HyperLogLog.empty(), 50)
        assertEquals(hll, hll.piece(hll))
    }

    @Test
    fun pieceIsCommutative() {
        val a = addMany(HyperLogLog.empty(), prefix = "A", n = 40)
        val b = addMany(HyperLogLog.empty(), prefix = "B", n = 40)
        assertEquals(a.piece(b), b.piece(a))
    }

    @Test
    fun pieceIsAssociative() {
        val a = addMany(HyperLogLog.empty(), prefix = "A", n = 20)
        val b = addMany(HyperLogLog.empty(), prefix = "B", n = 20)
        val c = addMany(HyperLogLog.empty(), prefix = "C", n = 20)
        assertEquals(a.piece(b).piece(c), a.piece(b.piece(c)))
    }

    @Test
    fun mergingIdenticalReplicasProducesSameEstimate() {
        val hll = addMany(HyperLogLog.empty(), 100)
        assertEquals(hll.estimate(), hll.piece(hll).estimate())
    }

    // ── Accuracy ──────────────────────────────────────────────────────────────

    /**
     * At precision=14 the expected standard error is 1/sqrt(2^14) ≈ 0.6 %.
     * We allow a generous 10% band to stay deterministic without a seeded RNG.
     */
    @Test
    fun estimateIsWithinErrorBandFor10kDistinctItems() {
        val n = 10_000
        var hll = HyperLogLog.empty(precision = 14)
        repeat(n) { i -> hll = hll.add("item-$i") }
        val estimate = hll.estimate()
        val tolerance = (n * 0.10).toLong()
        assertTrue(
            estimate in (n - tolerance)..(n + tolerance),
            "Expected estimate within 10% of $n, got $estimate",
        )
    }

    @Test
    fun estimateScalesWithDistinctItems() {
        // Merging two disjoint HLLs should give a higher estimate than either alone.
        val a = addMany(HyperLogLog.empty(precision = 14), prefix = "A", n = 1_000)
        val b = addMany(HyperLogLog.empty(precision = 14), prefix = "B", n = 1_000)
        val merged = a.piece(b)
        assertTrue(merged.estimate() > a.estimate(), "merged must exceed individual")
        assertTrue(merged.estimate() > b.estimate(), "merged must exceed individual")
    }

    @Test
    fun duplicateAddsDontInflateEstimate() {
        var hll = HyperLogLog.empty(precision = 14)
        repeat(1_000) { hll = hll.add("same-key") }
        val estimate = hll.estimate()
        // Should converge to ~1 (small cardinality correction)
        assertTrue(estimate in 1L..5L, "expected ~1 for one distinct item, got $estimate")
    }

    // ── Error paths ──────────────────────────────────────────────────────────

    @Test
    fun pieceMismatchedPrecisionThrows() {
        val a = HyperLogLog.empty(precision = 10)
        val b = HyperLogLog.empty(precision = 14)
        assertFailsWith<IllegalArgumentException> { a.piece(b) }
    }

    // ── m property ────────────────────────────────────────────────────────────

    @Test
    fun mPropertyReflectsPrecision() {
        assertAll(
            { assertEquals(1 shl 4, HyperLogLog.empty(precision = 4).m) },
            { assertEquals(1 shl 14, HyperLogLog.empty(precision = 14).m) },
            { assertEquals(1 shl 18, HyperLogLog.empty(precision = 18).m) },
        )
    }

    // ── Hash stability (golden vectors) ──────────────────────────────────────

    /**
     * Pin the MurmurHash3-32 output against canonical smhasher reference values
     * (seed = 0, little-endian, Austin Appleby public-domain reference).
     * A change to the hash function that breaks these vectors breaks cross-platform
     * serialisation stability, so this test must stay pinned.
     */
    @Test
    fun murmur3GoldenVectors() {
        assertAll(
            { assertEquals(0x00000000, HyperLogLog.murmur3Hash32("")) },
            { assertEquals(0x3c2569b2.toInt(), HyperLogLog.murmur3Hash32("a")) },
            { assertEquals(0xb3dd93fa.toInt(), HyperLogLog.murmur3Hash32("abc")) },
            { assertEquals(0x248bfa47, HyperLogLog.murmur3Hash32("hello")) },
        )
    }

    // ── Serialization ─────────────────────────────────────────────────────────

    @Test
    fun roundTripsThroughJson() {
        val hll = addMany(HyperLogLog.empty(), prefix = "x", n = 200)
        val encoded = Json.encodeToString(HyperLogLog.serializer(), hll)
        val decoded = Json.decodeFromString(HyperLogLog.serializer(), encoded)
        assertAll(
            { assertEquals(hll, decoded) },
            { assertEquals(hll.estimate(), decoded.estimate()) },
        )
    }

    @Test
    fun serializedFormIsStable() {
        // Same inputs always produce the same serialized bytes (deterministic hash).
        val hll1 = addMany(HyperLogLog.empty(), prefix = "stable", n = 50)
        val hll2 = addMany(HyperLogLog.empty(), prefix = "stable", n = 50)
        assertEquals(
            Json.encodeToString(HyperLogLog.serializer(), hll1),
            Json.encodeToString(HyperLogLog.serializer(), hll2),
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun addMany(hll: HyperLogLog, n: Int) = addMany(hll, prefix = "item", n = n)

    private fun addMany(hll: HyperLogLog, prefix: String, n: Int): HyperLogLog =
        (0 until n).fold(hll) { acc, i -> acc.add("$prefix-$i") }
}
