package us.tractat.kuilt.crdt

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [DDSketch.addBuckets]: counts that were bucketed elsewhere — one interval of an OpenTelemetry
 * SDK's exponential histogram — added to a replica's sketch as weighted increments (#3291).
 */
class DDSketchAddBucketsTest {

    private val r1 = ReplicaId("r1")

    @Test
    fun addingBucketCountsEqualsAddingEachValue() {
        val values = listOf(1.0, 1.0, 1.0, 5.0, 250.0, -3.0, -3.0, 0.0, 0.0)
        val byValue = DDSketch.empty().adding(r1, values)
        val empty = DDSketch.empty()
        val byBuckets = empty.piece(
            empty.addBuckets(r1, byValue.positiveBuckets, byValue.negativeBuckets, byValue.zeroCount).delta,
        )
        assertAll(
            { assertTrue(byValue.positiveBuckets.values.any { it > 1L }, "precondition: a positive bucket holds > 1") },
            { assertTrue(byValue.negativeBuckets.values.any { it > 1L }, "precondition: a negative bucket holds > 1") },
            { assertEquals(2L, byValue.zeroCount, "precondition: zeros recorded") },
            { assertEquals(byValue, byBuckets, "same replica cells as one add per value") },
        )
    }

    @Test
    fun bucketCountsAddOnTopOfThisReplicasEarlierValues() {
        // The cell already holds r1's count; the patch must carry old + n, not n, or the join keeps the old one.
        val earlier = DDSketch.empty().adding(r1, 1.0, 5.0, 5.0)
        val later = listOf(1.0, 5.0, 40.0)
        val laterBuckets = DDSketch.empty().adding(r1, later)
        val viaBuckets = earlier.piece(
            earlier.addBuckets(r1, laterBuckets.positiveBuckets, emptyMap(), 0L).delta,
        )
        assertAll(
            { assertTrue(earlier.positiveBuckets.keys.intersect(laterBuckets.positiveBuckets.keys).isNotEmpty(), "precondition: buckets overlap") },
            { assertEquals(earlier.adding(r1, later), viaBuckets) },
        )
    }

    @Test
    fun successiveDeltasAddWhereAJoinWouldNot() {
        val empty = DDSketch.empty()
        val index = empty.adding(r1, 10.0).positiveBuckets.keys.single()
        val first = mapOf(index to 3L)
        val second = mapOf(index to 4L, index + 1 to 1L)

        val afterFirst = empty.piece(empty.addBuckets(r1, first, emptyMap(), 0L).delta)
        val afterBoth = afterFirst.piece(afterFirst.addBuckets(r1, second, emptyMap(), 0L).delta)
        // Control: the same two deltas, each made against the empty sketch and joined, keep the max.
        val joined = empty.addBuckets(r1, first, emptyMap(), 0L).delta
            .piece(empty.addBuckets(r1, second, emptyMap(), 0L).delta)

        assertAll(
            { assertEquals(mapOf(index to 3L), afterFirst.positiveBuckets, "precondition: first delta landed") },
            { assertEquals(mapOf(index to 7L, index + 1 to 1L), afterBoth.positiveBuckets, "3 + 4 in the shared bucket") },
            { assertEquals(8L, afterBoth.count) },
            { assertEquals(mapOf(index to 4L, index + 1 to 1L), joined.positiveBuckets, "control: a join keeps the max") },
            { assertNotEquals(afterBoth, joined, "control: the join is not the sum") },
        )
    }

    @Test
    fun aRedeliveredPatchIsANoOp() {
        val base = DDSketch.empty().adding(r1, 2.0, -7.0)
        val patch = base.addBuckets(r1, base.positiveBuckets, base.negativeBuckets, 4L)
        val once = base.piece(patch.delta)
        assertAll(
            { assertTrue(patch.delta.count > 0L, "precondition: the patch is not empty") },
            { assertEquals(base.count * 2 + 4L, once.count, "precondition: applying it once adds") },
            { assertEquals(once, once.piece(patch.delta), "applying it again changes nothing") },
        )
    }

    @Test
    fun zeroCountsMakeNoCell() {
        val empty = DDSketch.empty()
        val index = empty.adding(r1, 10.0).positiveBuckets.keys.single()
        val withZeros = empty.addBuckets(r1, mapOf(index - 1 to 0L, index to 2L), mapOf(index to 0L), 0L).delta
        val withoutZeros = empty.addBuckets(r1, mapOf(index to 2L), emptyMap(), 0L).delta
        val allZero = empty.addBuckets(r1, mapOf(index to 0L), mapOf(index to 0L), 0L).delta
        assertAll(
            { assertEquals(2L, withZeros.count, "precondition: the non-zero bucket landed") },
            { assertEquals(GOLDEN_TWO_IN_BUCKET_116, cbor(withZeros), "golden: one cell, no zero or overflow slot") },
            { assertEquals(cbor(withoutZeros), cbor(withZeros)) },
            { assertEquals(cbor(empty), cbor(allZero), "an all-zero call is an empty patch") },
        )
    }

    @Test
    fun bucketsAboveTheRangeClampIntoTheTopBucketAndCountAsOverflow() {
        val empty = DDSketch.empty()
        val top = empty.adding(r1, DDSketch.DEFAULT_MAX_INDEXED_VALUE).positiveBuckets.keys.single()
        val byValue = empty.adding(r1, 1e30, 1e25, -1e30)
        val byBuckets = empty.piece(
            empty.addBuckets(r1, mapOf(top + 50 to 1L, top + 10 to 1L), mapOf(top + 3 to 1L), 0L).delta,
        )
        val atTheEdge = empty.addBuckets(r1, mapOf(top to 1L), emptyMap(), 0L).delta
        assertAll(
            { assertEquals(3L, byValue.overflowCount, "precondition: add clamped all three") },
            { assertEquals(byValue, byBuckets) },
            { assertEquals(0L, atTheEdge.overflowCount, "the top bucket itself is in range, as add(max) is") },
        )
    }

    @Test
    fun bucketsBelowTheRangeCountAsZeros() {
        val empty = DDSketch.empty()
        val bottom = empty.adding(r1, DDSketch.DEFAULT_MIN_INDEXED_VALUE).positiveBuckets.keys.single()
        val byValue = empty.adding(r1, 1e-12, -1e-15, 0.0)
        val byBuckets = empty.piece(
            empty.addBuckets(r1, mapOf(bottom - 100 to 1L), mapOf(bottom - 1 to 1L), 1L).delta,
        )
        val atTheEdge = empty.addBuckets(r1, mapOf(bottom to 1L), emptyMap(), 0L).delta
        assertAll(
            { assertEquals(3L, byValue.zeroCount, "precondition: add counted all three as zeros") },
            { assertEquals(0, byValue.bucketCount, "precondition: and made no bucket") },
            { assertEquals(byValue, byBuckets) },
            { assertEquals(1, atTheEdge.bucketCount, "the bottom bucket itself is in range, as add(min) is") },
        )
    }

    @Test
    fun negativeCountsAreRejected() {
        val empty = DDSketch.empty()
        assertAll(
            { assertFailsWith<IllegalArgumentException> { empty.addBuckets(r1, mapOf(1 to -1L), emptyMap(), 0L) } },
            { assertFailsWith<IllegalArgumentException> { empty.addBuckets(r1, emptyMap(), mapOf(1 to -1L), 0L) } },
            { assertFailsWith<IllegalArgumentException> { empty.addBuckets(r1, emptyMap(), emptyMap(), -1L) } },
        )
    }

    private fun DDSketch.adding(replica: ReplicaId, vararg values: Double): DDSketch = adding(replica, values.toList())

    private fun DDSketch.adding(replica: ReplicaId, values: List<Double>): DDSketch =
        values.fold(this) { acc, v -> acc.piece(acc.add(replica, v).delta) }

    @OptIn(ExperimentalSerializationApi::class)
    private fun cbor(sketch: DDSketch): String =
        Cbor.encodeToByteArray(DDSketch.serializer(), sketch).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private companion object {
        /**
         * Two values from r1 in the bucket holding 10.0 and nothing else. Decoded: `positive`
         * `{116 (0x1874): {counts: {"r1": 2}}}`, then an empty `negative` map, and `zeros` and
         * `overflows` each `{counts: {}}` — no slot for r1 at zero. A skipped zero count that made a
         * cell would add a key to one of those maps.
         */
        const val GOLDEN_TWO_IN_BUCKET_116 =
            "bf7072656c61746976654163637572616379fb3f847ae147ae147b6f6d696e496e646578656456616c7565fb3e112e0be826d6956f" +
                "6d6178496e646578656456616c7565fb43abc16d674ec80068706f736974697665bf1874bf66636f756e7473bf62723102ffffff" +
                "686e65676174697665bfff657a65726f73bf66636f756e7473bfffff696f766572666c6f7773bf66636f756e7473bfffffff"
    }
}
