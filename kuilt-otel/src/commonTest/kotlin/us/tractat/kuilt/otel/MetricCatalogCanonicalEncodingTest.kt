@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class, ExperimentalStdlibApi::class)

package us.tractat.kuilt.otel

import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.crdt.DDSketch
import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.GCounterDouble
import us.tractat.kuilt.crdt.HyperLogLog
import us.tractat.kuilt.crdt.LWWRegister
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Peers that merged the same [MetricCatalog] operands in different orders must encode the same
 * bytes, or the metric tap's anti-entropy roots never match and every round ships the whole catalog
 * (#2715). `:kuilt-otel-tap`'s `MetricCatalogRootTest` reads that consequence off a real `Quilter`.
 *
 * `"Aa"`, `"BB"` and `"C#"` share one `String.hashCode`, and [MetricKey]'s other fields are equal
 * here, so the keys collide into one JVM `HashMap` bin. A bin iterates in insertion order, as a whole
 * map does on Kotlin/Native and wasmJs, so the fold order reaches the encoder on every target family.
 * [theFoldOrderReachesTheIterationOrder] is the receipt.
 *
 * [PlainCatalogWire] is the reference: plain maps, hand-sorted, no merge and no canonical serializer.
 * It also stands in for a peer on a build that predates canonical ordering.
 */
class MetricCatalogCanonicalEncodingTest {

    private class Arm(val name: String, val operands: List<MetricCatalog>)

    private val serializer = MetricCatalog.serializer()

    private fun fold(operands: List<MetricCatalog>): MetricCatalog =
        operands.fold(MetricCatalog()) { merged, next -> merged.piece(next) }

    private fun hex(catalog: MetricCatalog): String = Cbor.encodeToByteArray(serializer, catalog).toHexString()

    private val arms: List<Arm> = listOf(
        Arm("sums", SUMS.map { MetricCatalog(sums = mapOf(it to COUNTER)) }),
        Arm("doubleSums", SUMS.map { MetricCatalog(doubleSums = mapOf(it to DOUBLE_COUNTER)) }),
        Arm("gauges", GAUGES.map { MetricCatalog(gauges = mapOf(it to REGISTER)) }),
        Arm("cardinalities", CARDINALITIES.map { MetricCatalog(cardinalities = mapOf(it to SKETCH)) }),
        Arm("histograms", HISTOGRAMS.map { MetricCatalog(histograms = mapOf(it to QUANTILES)) }),
    )

    /** Every map, merged both ways round, so a field that escapes canonical ordering names itself. */
    @Test
    fun everyFieldEncodesTheSameWhicheverOrderItsOperandsMerged() {
        assertAll(
            *arms.map { arm ->
                {
                    val forward = fold(arm.operands)
                    val backward = fold(arm.operands.reversed())
                    assertEquals(forward, backward, "${arm.name}: the merge itself disagrees, so bytes prove nothing")
                    assertEquals(hex(forward), hex(backward), "${arm.name}: the merge order reached the wire")
                }
            }.toTypedArray(),
        )
    }

    /**
     * The trap `CanonicalMapSerializer`'s KDoc warns about, which the issue's table does not name.
     * [MetricKey] has an unordered `attributes` map of its own, so sorting the entries around it
     * cannot help: these two keys are equal, one survives the merge, and which one depends on the
     * order. It reds on the JVM too, since a `mapOf` keeps insertion order on every target.
     */
    @Test
    fun equalKeysWithDifferentlyOrderedAttributesEncodeTheSame() {
        val abKey = MetricKey("labelled", MetricKind.SUM, linkedMapOf("a" to "1", "b" to "2"))
        val baKey = MetricKey("labelled", MetricKind.SUM, linkedMapOf("b" to "2", "a" to "1"))
        val ab = MetricCatalog(sums = mapOf(abKey to COUNTER))
        val ba = MetricCatalog(sums = mapOf(baKey to COUNTER))
        assertAll(
            { assertEquals(abKey, baKey, "the rig needs two EQUAL keys, or the merge keeps both") },
            { assertEquals(1, ab.piece(ba).sums.size, "the merge must fold them into one series") },
            { assertEquals(hex(ab.piece(ba)), hex(ba.piece(ab)), "the surviving key's attribute order reached the wire") },
        )
    }

    /**
     * The rig receipt, read off production's own merged map. A byte comparison can only fail where
     * that map has more than one iteration order, so on the JVM this rests on the hash collision:
     * swap [SUMS] for ordinary names and this reds rather than leaving the arms above green by
     * accident.
     */
    @Test
    fun theFoldOrderReachesTheIterationOrder() {
        val operands = SUMS.map { MetricCatalog(sums = mapOf(it to COUNTER)) }
        assertNotEquals(
            fold(operands).sums.keys.toList(),
            fold(operands.reversed()).sums.keys.toList(),
            "merged sums' iteration order must depend on the fold order on this target",
        )
    }

    /**
     * Plain maps in hand-sorted order encode to the golden. No canonical serializer is on this path,
     * so this stays green through the fix: it says the fix reorders entries and changes nothing else.
     */
    @Test
    fun aPlainEncodingOfHandSortedEntriesIsTheGolden() {
        assertEquals(GOLDEN, Cbor.encodeToByteArray(PlainCatalogWire.serializer(), plainWire(ascending = true)).toHexString())
    }

    /** The cross-target pin: `commonTest` runs everywhere, so every target owes this one answer. */
    @Test
    fun aMergedCatalogEncodesToTheGoldenWhicheverWayItMerged() {
        assertAll(
            { assertEquals(GOLDEN, hex(fold(goldenOperands())), "forward") },
            { assertEquals(GOLDEN, hex(fold(goldenOperands().reversed())), "backward") },
        )
    }

    /** A peer on an older build reads canonical bytes: its decoder takes entries in any order. */
    @Test
    fun aPlainReaderDecodesCanonicalBytes() {
        val decoded = Cbor.decodeFromByteArray(PlainCatalogWire.serializer(), GOLDEN.hexToByteArray())
        assertEquals(plainWire(ascending = true).fields(), decoded.fields())
    }

    /** Unsorted bytes from an older peer decode to the same value and re-encode canonically. */
    @Test
    fun anOlderPeersUnsortedBytesDecodeAndReEncodeCanonically() {
        val unsorted = Cbor.encodeToByteArray(PlainCatalogWire.serializer(), plainWire(ascending = false))
        val decoded = Cbor.decodeFromByteArray(serializer, unsorted)
        assertAll(
            { assertEquals(fold(goldenOperands()), decoded, "the value does not depend on the entry order") },
            { assertEquals(GOLDEN, hex(decoded), "re-encoding must not carry the sender's order forward") },
        )
    }

    /** Two entries per field, plus a labelled key, which is all a golden needs to pin an order. */
    private fun goldenOperands(): List<MetricCatalog> =
        arms.flatMap { it.operands.take(2) } + MetricCatalog(sums = mapOf(LABELLED to COUNTER))

    /** What [goldenOperands] merges to, written out by hand with no merge anywhere on the path. */
    private fun plainWire(ascending: Boolean): PlainCatalogWire {
        fun <K, V> entries(vararg pairs: Pair<K, V>): Map<K, V> =
            linkedMapOf(*(if (ascending) pairs else pairs.reversedArray()))

        return PlainCatalogWire(
            sums = entries(SUMS[0] to COUNTER, SUMS[1] to COUNTER, LABELLED to COUNTER),
            doubleSums = entries(SUMS[0] to DOUBLE_COUNTER, SUMS[1] to DOUBLE_COUNTER),
            gauges = entries(GAUGES[0] to REGISTER, GAUGES[1] to REGISTER),
            cardinalities = entries(CARDINALITIES[0] to SKETCH, CARDINALITIES[1] to SKETCH),
            histograms = entries(HISTOGRAMS[0] to QUANTILES, HISTOGRAMS[1] to QUANTILES),
        )
    }

    private companion object {
        /** One `String.hashCode`. See the class KDoc. */
        val NAMES = listOf("Aa", "BB", "C#")
        val SUMS = NAMES.map { MetricKey(it, MetricKind.SUM) }
        val GAUGES = NAMES.map { MetricKey(it, MetricKind.GAUGE) }
        val CARDINALITIES = NAMES.map { MetricKey(it, MetricKind.CARDINALITY) }
        val HISTOGRAMS = NAMES.map { MetricKey(it, MetricKind.EXPONENTIAL_HISTOGRAM) }

        /** Sorts after [SUMS] by name, and its attributes must come out sorted too. */
        val LABELLED = MetricKey("labelled", MetricKind.SUM, linkedMapOf("a" to "1", "b" to "2"))

        val REPLICA = ReplicaId("r")
        val COUNTER = GCounter.of(REPLICA to 1L)
        val DOUBLE_COUNTER = GCounterDouble.of(REPLICA to 1.5)
        val REGISTER = LWWRegister.empty<Double>().set(REPLICA, 1L, 21.0)

        /** Precision 4 is the smallest legal sketch: 12 bytes, against 12 KiB at the default. */
        val SKETCH = HyperLogLog.empty(precision = 4).let { it.piece(it.add("u1")) }
        val QUANTILES = DDSketch.empty().let { it.piece(it.add(REPLICA, 100.0)) }

        /**
         * Taken from [PlainCatalogWire] before the fix, and read back by a throwaway CBOR decoder
         * that shares nothing with kotlinx-serialization. Do not regenerate it to clear a red: a
         * change here is a wire change, and that wants a conversation first.
         */
        const val GOLDEN: String = "TODO-golden"
    }
}

/** The catalog's old serializer, spelled out. Field names and order are the wire format. */
@Serializable
private class PlainCatalogWire(
    val sums: Map<MetricKey, GCounter> = emptyMap(),
    val doubleSums: Map<MetricKey, GCounterDouble> = emptyMap(),
    val gauges: Map<MetricKey, LWWRegister<Double>> = emptyMap(),
    val cardinalities: Map<MetricKey, HyperLogLog> = emptyMap(),
    val histograms: Map<MetricKey, DDSketch> = emptyMap(),
) {
    fun fields(): List<Map<MetricKey, Any>> = listOf(sums, doubleSums, gauges, cardinalities, histograms)
}
