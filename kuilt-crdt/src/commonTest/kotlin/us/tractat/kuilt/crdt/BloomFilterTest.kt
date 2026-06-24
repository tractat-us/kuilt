package us.tractat.kuilt.crdt

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BloomFilterTest {

    // ── Construction ────────────────────────────────────────────────────────

    @Test
    fun emptyFilterContainsNothing() {
        val filter = BloomFilter.create(expectedElements = 100)
        assertFalse(filter.mightContain("anything"))
    }

    @Test
    fun addedElementAlwaysReportsPresent() {
        val filter = BloomFilter.create(expectedElements = 100)
        val withElement = filter.piece(filter.add("hello"))
        assertTrue(withElement.mightContain("hello"))
    }

    @Test
    fun multipleAddedElementsAllReportPresent() {
        var filter = BloomFilter.create(expectedElements = 200)
        val elements = (1..50).map { "element-$it" }
        for (el in elements) {
            filter = filter.piece(filter.add(el))
        }
        for (el in elements) {
            assertTrue(filter.mightContain(el), "Expected $el to be present")
        }
    }

    // ── No false negatives ───────────────────────────────────────────────────

    @Test
    fun noFalseNegatives() {
        var filter = BloomFilter.create(expectedElements = 1000, falsePositiveRate = 0.01)
        val elements = (1..200).map { "item-$it" }
        for (el in elements) {
            filter = filter.piece(filter.add(el))
        }
        for (el in elements) {
            assertTrue(filter.mightContain(el), "False negative for $el")
        }
    }

    // ── False positive rate within bound ─────────────────────────────────────

    @Test
    fun falsePositiveRateWithinBound() {
        val targetRate = 0.01
        var filter = BloomFilter.create(expectedElements = 1000, falsePositiveRate = targetRate)
        val inserted = (1..1000).map { "inserted-$it" }
        for (el in inserted) {
            filter = filter.piece(filter.add(el))
        }

        // Check against a disjoint set of elements.
        val probed = (10001..11000).map { "probe-$it" }
        val falsePositives = probed.count { filter.mightContain(it) }
        val observedRate = falsePositives.toDouble() / probed.size

        // Allow 3× the configured rate as tolerance for the sample size.
        assertTrue(
            observedRate <= targetRate * 3.0,
            "False positive rate $observedRate exceeded 3× target $targetRate"
        )
    }

    // ── Lattice laws ─────────────────────────────────────────────────────────

    @Test
    fun mergeIsIdempotent() {
        val filter = BloomFilter.create(expectedElements = 100)
            .piece(BloomFilter.create(expectedElements = 100).add("x"))
        assertEquals(filter, filter.piece(filter))
    }

    @Test
    fun mergeIsCommutative() {
        val a = BloomFilter.create(expectedElements = 100)
            .piece(BloomFilter.create(expectedElements = 100).add("x"))
        val b = BloomFilter.create(expectedElements = 100)
            .piece(BloomFilter.create(expectedElements = 100).add("y"))
        assertEquals(a.piece(b), b.piece(a))
    }

    @Test
    fun mergeIsAssociative() {
        val a = BloomFilter.create(expectedElements = 100)
            .piece(BloomFilter.create(expectedElements = 100).add("x"))
        val b = BloomFilter.create(expectedElements = 100)
            .piece(BloomFilter.create(expectedElements = 100).add("y"))
        val c = BloomFilter.create(expectedElements = 100)
            .piece(BloomFilter.create(expectedElements = 100).add("z"))
        assertEquals(a.piece(b).piece(c), a.piece(b.piece(c)))
    }

    @Test
    fun mergedFilterContainsElementsFromBothSides() {
        val left = BloomFilter.create(expectedElements = 100)
            .piece(BloomFilter.create(expectedElements = 100).add("alice"))
        val right = BloomFilter.create(expectedElements = 100)
            .piece(BloomFilter.create(expectedElements = 100).add("bob"))

        val merged = left.piece(right)
        assertTrue(merged.mightContain("alice"))
        assertTrue(merged.mightContain("bob"))
    }

    @Test
    fun mergeViaOROfBitArrays() {
        // A merge of two filters must be at least as "full" as either side.
        val a = BloomFilter.create(expectedElements = 100)
            .piece(BloomFilter.create(expectedElements = 100).add("x"))
        val b = BloomFilter.create(expectedElements = 100)

        // Merging with the empty filter must keep all existing bits.
        assertEquals(a, a.piece(b))
        assertEquals(a, b.piece(a))
    }

    // ── Removes are not supported ─────────────────────────────────────────────

    @Test
    fun onceAddedAnElementCannotBeRemoved() {
        // This test documents that BloomFilter is union-only.
        // Bloom filters are monotone: bits can only be set, never cleared.
        var filter = BloomFilter.create(expectedElements = 100)
        filter = filter.piece(filter.add("permanent"))
        // No remove API exists — the type is correct by construction.
        assertTrue(filter.mightContain("permanent"))
    }

    // ── Serialization ─────────────────────────────────────────────────────────

    @Test
    fun roundTripsThroughJson() {
        var filter = BloomFilter.create(expectedElements = 100, falsePositiveRate = 0.01)
        filter = filter.piece(filter.add("hello"))
        filter = filter.piece(filter.add("world"))

        val encoded = Json.encodeToString(BloomFilter.serializer(), filter)
        val decoded = Json.decodeFromString(BloomFilter.serializer(), encoded)

        assertEquals(filter, decoded)
        assertTrue(decoded.mightContain("hello"))
        assertTrue(decoded.mightContain("world"))
    }

    // ── Configuration ─────────────────────────────────────────────────────────

    @Test
    fun filtersWithDifferentSizeConfigurationsAreIncompatible() {
        val small = BloomFilter.create(expectedElements = 100)
        val large = BloomFilter.create(expectedElements = 1000)

        assertFailsWith<IllegalArgumentException> { small.piece(large) }
    }

    @Test
    fun defaultConfigurationProducesAUsableFilter() {
        val filter = BloomFilter.create(expectedElements = 100)
        val withData = filter.piece(filter.add("test"))
        assertTrue(withData.mightContain("test"))
        assertFalse(filter.mightContain("test"))
    }

    // ── Sparse delta ──────────────────────────────────────────────────────────

    @Test
    fun addDeltaIsSparse() {
        // A single add touches at most k distinct words (one word per hash position);
        // the delta must carry only those non-zero words, not the full O(m) array.
        val filter = BloomFilter.create(expectedElements = 10_000, falsePositiveRate = 0.01)
        val delta = filter.add("hello").delta
        // k = hashCount for this config; non-zero words must be at most k.
        assertTrue(
            delta.nonZeroWordCount() <= filter.hashCount,
            "Expected sparse delta: nonZeroWordCount=${delta.nonZeroWordCount()} should be ≤ hashCount=${filter.hashCount}"
        )
        // Crucially it must be *strictly* less than the full array length.
        assertTrue(
            delta.nonZeroWordCount() < filter.wordCount,
            "Delta should be sparse: nonZeroWordCount=${delta.nonZeroWordCount()} vs wordCount=${filter.wordCount}"
        )
    }

    @Test
    fun sparseDeltaMergesIdenticallyToFullArrayDelta() {
        // A sparse delta and a full-array delta encoding the same k bits must produce
        // the same merged state, because OR-merging 0L words is a no-op.
        // We verify this by cross-checking two independent merge paths:
        //   A) add() sparse delta absorbed into an empty filter.
        //   B) the same k bits set in a full-width BloomFilter via fromRaw.
        val n = 1000
        val p = 0.01
        val template = BloomFilter.create(n, p)

        val element = "cross-check"
        // Path A: sparse delta from add().
        val sparseResult = template.piece(template.add(element))

        // Path B: use addToFullArray() which returns a BloomFilter with a full-width
        // backing array (the old add() behaviour) and absorb it the same way.
        val fullDelta = template.addToFullArray(element)
        val fullResult = template.piece(fullDelta)

        assertEquals(sparseResult, fullResult, "Sparse and full-array deltas must produce identical merged state")
        assertTrue(sparseResult.mightContain(element))
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun addDeltaWireSizeFarSmallerThanFullState() {
        // The add() delta must encode much more compactly than a dense full state.
        // For n=10_000, p=1%: 1498 words total, k=7 hash positions.
        // Sparse delta: ~7 (index+word) pairs ≈ tens of bytes.
        // Dense full state: 1498 longs ≈ 1.5 KB+ in CBOR.
        val emptyFilter = BloomFilter.create(10_000, 0.01)

        // Build a non-trivial full state with many non-zero words.
        var fullState = emptyFilter
        repeat(500) { i -> fullState = fullState.piece(fullState.add("item-$i")) }

        val addDelta = emptyFilter.add("new-element").delta
        val addDeltaBytes = Cbor.encodeToByteArray(BloomFilter.serializer(), addDelta)
        val fullStateBytes = Cbor.encodeToByteArray(BloomFilter.serializer(), fullState)

        println("add() delta CBOR: ${addDeltaBytes.size} bytes (nonZeroWords=${addDelta.nonZeroWordCount()}/${addDelta.wordCount})")
        println("Full state CBOR: ${fullStateBytes.size} bytes (nonZeroWords=${fullState.nonZeroWordCount()}/${fullState.wordCount})")

        // The add() delta must be at least 10× smaller than the full state.
        assertTrue(
            addDeltaBytes.size * 10 < fullStateBytes.size,
            "add() delta (${addDeltaBytes.size}B) should be at least 10× smaller than full state (${fullStateBytes.size}B)"
        )
    }

    @Test
    fun sparseDeltaIsIdempotentUnderRedelivery() {
        // Re-delivering a sparse delta must not corrupt the state.
        val filter = BloomFilter.create(expectedElements = 100)
        val delta = filter.add("idempotent")
        val once = filter.piece(delta)
        val twice = once.piece(delta)
        assertEquals(once, twice)
        assertTrue(twice.mightContain("idempotent"))
    }
}
