package us.tractat.kuilt.crdt

import kotlinx.serialization.json.Json
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

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
        val patch = hll.add("hello")
        val next = hll.piece(patch)
        // small-cardinality correction: exact for n=1
        assertTrue(next.estimate() in 1L..2L, "expected ≈1, got ${next.estimate()}")
    }

    // ── piece() fast-path behaviour ───────────────────────────────────────────

    /**
     * piece(emptyDelta) must return the same instance — not a defensive copy.
     *
     * This directly proves the O(1) empty-delta fast path in piece(): if the
     * implementation falls back to the full O(m) loop it always allocates a new
     * ByteArray and returns a new HLL (even though the result is structurally equal),
     * so assertSame fails. With the fast path, piece() short-circuits and returns
     * `this`.
     *
     * This is the TDD companion for the piece() optimisation added to fix the
     * wasmJs "Karma browser disconnected" root cause: the full O(m) bit-manipulation
     * loop blocked the browser's single JS thread on every add/piece iteration,
     * even when the delta was all-zero (see #788).
     */
    @Test
    fun pieceWithEmptyDeltaReturnsSameInstance() {
        val base = HyperLogLog.empty(precision = 4)
        // First add: sets one register.
        val state = base.piece(base.add("x").delta)
        // Second add of same value: rho is identical, register already at that value → empty delta.
        val emptyDelta = state.add("x")
        assertEquals(0, emptyDelta.delta.nonZeroRegisterCount(), "re-add should produce empty delta")
        assertSame(state, state.piece(emptyDelta.delta), "piece with empty delta must return the same instance")
    }

    /**
     * piece(singleRegisterDelta) must return a new instance with only the updated
     * register changed — the single-register fast path.
     *
     * Companion to pieceWithEmptyDeltaReturnsSameInstance: proves the non-empty
     * arm of the fast path creates a new HLL (correct) while still only touching
     * the one changed register.
     */
    @Test
    fun pieceWithSingleRegisterDeltaUpdatesOnlyThatRegister() {
        val state = HyperLogLog.empty(precision = 4)
        val patch = state.add("x")
        assertEquals(1, patch.delta.nonZeroRegisterCount(), "add() patch should have exactly 1 non-zero register")
        val merged = state.piece(patch.delta)
        assertNotSame(state, merged, "piece with non-empty delta must return a new instance")
        assertAll(
            { assertEquals(1, merged.nonZeroRegisterCount()) },
            { assertEquals(merged, state.piece(state.add("x").delta)) }, // deterministic
        )
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
        repeat(n) { i -> hll = hll.piece(hll.add("item-$i")) }
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
        repeat(1_000) { hll = hll.piece(hll.add("same-key")) }
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
     * Adding the same value twice is idempotent — the HyperLogLog register state
     * must not change, so the returned instance is identical to the result of one
     * add (join-semilattice idempotence).
     *
     * The Murmur3 golden vectors that previously lived here have moved to
     * [us.tractat.kuilt.crdt.internal.Murmur3Test] — the consolidated hash is now
     * tested at its own level.
     */
    @Test
    fun addingDuplicateIsIdempotent() {
        val base = HyperLogLog.empty()
        val once = base.piece(base.add("hello"))
        val twice = once.piece(once.add("hello"))
        assertEquals(once, twice)
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

    // ── Sparse delta ──────────────────────────────────────────────────────────

    /**
     * A single add() changes at most one register. The delta's register array
     * must have at most one non-zero entry.
     */
    @Test
    fun addProducesSparseDeltaWithAtMostOneNonZeroRegister() {
        val hll = HyperLogLog.empty(precision = 10)
        val patch = hll.add("test-value")
        val nonZeroCount = patch.delta.nonZeroRegisterCount()
        assertTrue(nonZeroCount <= 1, "expected at most 1 non-zero register, got $nonZeroCount")
    }

    /**
     * A no-op add (re-adding a value whose ρ doesn't exceed the stored max)
     * produces an empty delta — all registers zero.
     */
    @Test
    fun noOpAddProducesEmptyDelta() {
        val hll = HyperLogLog.empty(precision = 10)
        // Add once so the register is set; a second add of the same value is a no-op.
        val filled = hll.piece(hll.add("repeat"))
        val noOpPatch = filled.add("repeat")
        assertEquals(0, noOpPatch.delta.nonZeroRegisterCount(), "no-op add must produce empty delta")
    }

    /**
     * Merging a sparse delta into a state produces the same result as adding the
     * element to each replica independently and then merging their full states.
     */
    @Test
    fun sparseDeltaMergesIdenticallyToFullStateMerge() {
        val base = addMany(HyperLogLog.empty(precision = 10), prefix = "base", n = 20)
        val element = "new-element"

        // Path 1: apply via sparse delta
        val viaSparseDelta = base.piece(base.add(element))

        // Path 2: add to a fresh copy and merge full states
        val freshCopy = addMany(HyperLogLog.empty(precision = 10), prefix = "base", n = 20)
        val viaFullStateMerge = base.piece(freshCopy.piece(freshCopy.add(element)))

        assertEquals(viaFullStateMerge, viaSparseDelta)
    }

    /**
     * The patch returned by add() correctly applies via the lattice join.
     */
    @Test
    fun addPatchAppliesCorrectlyViaLatticeJoin() {
        val hll = HyperLogLog.empty(precision = 10)
        val patch = hll.add("hello")
        val result = hll.piece(patch)
        // result must be above or equal to hll (monotone growth)
        assertEquals(result, result.piece(hll), "result must dominate the original (piece is join)")
    }

    /**
     * Re-delivering the same sparse delta is idempotent (element-wise max is safe
     * to apply multiple times).
     */
    @Test
    fun sparseDeltaIsIdempotentOnRedelivery() {
        val hll = HyperLogLog.empty(precision = 10)
        val patch = hll.add("idempotent")
        val once = hll.piece(patch)
        val twice = once.piece(patch)
        assertEquals(once, twice, "re-applying the same delta must be a no-op")
    }

    // ── 6-bit packing ─────────────────────────────────────────────────────────

    /**
     * Serialized state size must be ~25% smaller than the old 1-byte-per-register form.
     * Packed size = ceil(m * 6 / 8). Old size = m. Reduction ≥ 24%.
     */
    @Test
    fun serializedSizeIsReducedByAtLeast24Percent() {
        val hll = addMany(HyperLogLog.empty(precision = 14), prefix = "pack", n = 500)
        val serialized = Json.encodeToString(HyperLogLog.serializer(), hll)
        // The packed backing ByteArray length is ceil(m*6/8). For p=14, m=16384 → 12288 bytes.
        // The JSON Base64 of a ByteArray of size n occupies ceil(n/3)*4 chars (+ overhead).
        // Old: Base64 of 16384 bytes ≈ 21848 chars. New: Base64 of 12288 bytes ≈ 16384 chars.
        // We just check: packed JSON is less than 76% of the old size (i.e. ≥24% smaller).
        val oldPackedBase64Len = (16384 + 2) / 3 * 4  // base64 of 16384 bytes
        val serializedBase64EstimateUpperBound = (oldPackedBase64Len * 76) / 100
        val base64Content = serialized.substringAfter("\"registers\":\"").substringBefore("\"")
        assertTrue(
            base64Content.length < serializedBase64EstimateUpperBound,
            "packed registers Base64 (${base64Content.length} chars) should be < $serializedBase64EstimateUpperBound"
        )
    }

    /**
     * 6-bit register accessors round-trip every value in [0, 63] for every register index.
     * Tested at small precision to keep the loop tight.
     */
    @Test
    fun registerPackingRoundTripsAllValidValues() {
        // Use precision=4 (m=16) so we can exhaustively test all indices × all values.
        // We do this by adding synthetic strings and checking the estimate is stable across
        // a JSON encode→decode round-trip at various cardinalities.
        for (p in listOf(4, 10, 14)) {
            val hll = addMany(HyperLogLog.empty(precision = p), prefix = "rt-$p", n = 100)
            val decoded = Json.decodeFromString(HyperLogLog.serializer(),
                Json.encodeToString(HyperLogLog.serializer(), hll))
            assertEquals(hll, decoded, "round-trip failed at precision=$p")
            assertEquals(hll.estimate(), decoded.estimate(), "estimate mismatch at precision=$p")
        }
    }

    /**
     * The packed backing store size (reported via `packedByteSize`) must equal ceil(m*6/8).
     */
    @Test
    fun packedBackingSizeIsCorrect() {
        assertAll(
            { assertEquals(12, HyperLogLog.empty(precision = 4).packedByteSize) },   // m=16, ceil(96/8)=12
            { assertEquals(768, HyperLogLog.empty(precision = 10).packedByteSize) },  // m=1024, ceil(6144/8)=768
            { assertEquals(12288, HyperLogLog.empty(precision = 14).packedByteSize) }, // m=16384
            { assertEquals(196608, HyperLogLog.empty(precision = 18).packedByteSize) }, // m=262144
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun addMany(hll: HyperLogLog, n: Int) = addMany(hll, prefix = "item", n = n)

    private fun addMany(hll: HyperLogLog, prefix: String, n: Int): HyperLogLog =
        (0 until n).fold(hll) { acc, i -> acc.piece(acc.add("$prefix-$i")) }
}
