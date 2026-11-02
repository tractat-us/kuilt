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
        const val GOLDEN: String = 
            "bf6473756d73bfbf646e616d65624161646b696e646353554dffbf66636f756e7473bf617201ffffbf646e616d656242" +
            "42646b696e646353554dffbf66636f756e7473bf617201ffffbf646e616d65686c6162656c6c6564646b696e64635355" +
            "4d6a61747472696275746573bf6161613161626132ffffbf66636f756e7473bf617201ffffff6a646f75626c6553756d" +
            "73bfbf646e616d65624161646b696e646353554dffbf66636f756e7473bf6172fb3ff8000000000000ffffbf646e616d" +
            "65624242646b696e646353554dffbf66636f756e7473bf6172fb3ff8000000000000ffffff66676175676573bfbf646e" +
            "616d65624161646b696e64654741554745ffbf6974696d657374616d7001666f726967696e61726576616c7565fb4035" +
            "000000000000ffbf646e616d65624242646b696e64654741554745ffbf6974696d657374616d7001666f726967696e61" +
            "726576616c7565fb4035000000000000ffff6d63617264696e616c6974696573bfbf646e616d65624161646b696e646b" +
            "43415244494e414c495459ffbf69707265636973696f6e04697265676973746572739f000000000001000000000000ff" +
            "ffbf646e616d65624242646b696e646b43415244494e414c495459ffbf69707265636973696f6e046972656769737465" +
            "72739f000000000001000000000000ffffff6a686973746f6772616d73bfbf646e616d65624161646b696e6475455850" +
            "4f4e454e5449414c5f484953544f4752414dffbf7072656c61746976654163637572616379fb3f847ae147ae147b6f6d" +
            "696e496e646578656456616c7565fb3e112e0be826d6956f6d6178496e646578656456616c7565fb43abc16d674ec800" +
            "68706f736974697665bf18e7bf66636f756e7473bf617201ffffff686e65676174697665bfff657a65726f73bf66636f" +
            "756e7473bfffff696f766572666c6f7773bf66636f756e7473bfffffffbf646e616d65624242646b696e64754558504f" +
            "4e454e5449414c5f484953544f4752414dffbf7072656c61746976654163637572616379fb3f847ae147ae147b6f6d69" +
            "6e496e646578656456616c7565fb3e112e0be826d6956f6d6178496e646578656456616c7565fb43abc16d674ec80068" +
            "706f736974697665bf18e7bf66636f756e7473bf617201ffffff686e65676174697665bfff657a65726f73bf66636f75" +
            "6e7473bfffff696f766572666c6f7773bf66636f756e7473bfffffffffff"
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
