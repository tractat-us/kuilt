@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    ExperimentalStdlibApi::class,
)

package us.tractat.kuilt.warp

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.crdt.HyperLogLog
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece
import us.tractat.kuilt.quilter.MonotonicMillis
import us.tractat.kuilt.quilter.QuiltMessage
import us.tractat.kuilt.quilter.Quilter
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Peers that merged the same [WarpStats] operands in different orders must encode the same bytes
 * (#2715). No `Quilter` carries this type in-tree, but one a consumer wires hashes this encoding for
 * its anti-entropy root, and a mismatch ships the whole state on every round.
 *
 * `"Aa"`, `"BB"` and `"C#"` share one `String.hashCode`, so they collide into one JVM `HashMap` bin.
 * A bin iterates in insertion order, as a whole map does on Kotlin/Native and wasmJs, so the fold
 * order reaches the encoder on every target family. [theFoldOrderReachesTheIterationOrder] is the
 * receipt.
 *
 * [PlainStatsWire] is the reference: a plain map, hand-sorted, no merge and no canonical serializer.
 * It also stands in for a peer on a build that predates canonical ordering.
 */
class WarpStatsCanonicalEncodingTest {

    private val serializer = WarpStats.serializer()

    private val operands: List<WarpStats> = SOURCES.map { WarpStats.of(mapOf(it to SKETCH)) }

    private fun fold(from: List<WarpStats>): WarpStats =
        from.fold(WarpStats.empty()) { merged, next -> merged.piece(next) }

    private fun hex(stats: WarpStats): String = Cbor.encodeToByteArray(serializer, stats).toHexString()

    @Test
    fun theSketchesEncodeTheSameWhicheverOrderTheyMerged() {
        val forward = fold(operands)
        val backward = fold(operands.reversed())
        assertAll(
            { assertEquals(forward, backward, "the merge itself disagrees, so bytes prove nothing") },
            { assertEquals(hex(forward), hex(backward), "the merge order reached the wire") },
        )
    }

    /**
     * The rig receipt, read off production's own merged map. A byte comparison can only fail where
     * that map has more than one iteration order, so on the JVM this rests on the hash collision:
     * swap [SOURCES] for ordinary ids and this reds rather than leaving the test above green by
     * accident.
     */
    @Test
    fun theFoldOrderReachesTheIterationOrder() {
        assertNotEquals(
            fold(operands).sketches().keys.toList(),
            fold(operands.reversed()).sketches().keys.toList(),
            "merged sketches' iteration order must depend on the fold order on this target",
        )
    }

    /**
     * Plain map in hand-sorted order encodes to the golden. No canonical serializer is on this path,
     * so this stays green through the fix: it says the fix reorders entries and changes nothing else.
     */
    @Test
    fun aPlainEncodingOfHandSortedEntriesIsTheGolden() {
        assertEquals(GOLDEN, Cbor.encodeToByteArray(PlainStatsWire.serializer(), plainWire(SOURCES)).toHexString())
    }

    /** The cross-target pin: `commonTest` runs everywhere, so every target owes this one answer. */
    @Test
    fun mergedStatsEncodeToTheGoldenWhicheverWayTheyMerged() {
        assertAll(
            { assertEquals(GOLDEN, hex(fold(operands)), "forward") },
            { assertEquals(GOLDEN, hex(fold(operands.reversed())), "backward") },
        )
    }

    /** A peer on an older build reads canonical bytes: its decoder takes entries in any order. */
    @Test
    fun aPlainReaderDecodesCanonicalBytes() {
        val decoded = Cbor.decodeFromByteArray(PlainStatsWire.serializer(), GOLDEN.hexToByteArray())
        assertEquals(plainWire(SOURCES).sketches, decoded.sketches)
    }

    /** Unsorted bytes from an older peer decode to the same value and re-encode canonically. */
    @Test
    fun anOlderPeersUnsortedBytesDecodeAndReEncodeCanonically() {
        val unsorted = Cbor.encodeToByteArray(PlainStatsWire.serializer(), plainWire(SOURCES.reversed()))
        val decoded = Cbor.decodeFromByteArray(serializer, unsorted)
        assertAll(
            { assertEquals(fold(operands), decoded, "the value does not depend on the entry order") },
            { assertEquals(GOLDEN, hex(decoded), "re-encoding must not carry the sender's order forward") },
        )
    }

    /** The consequence, off a real [Quilter]. The control stops a state-blind root passing for free. */
    @Test
    fun peersThatMergedInDifferentOrdersAnnounceTheSameRoot() =
        runTest(UnconfinedTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val forward = announcedRoot(fold(operands))
            val backward = announcedRoot(fold(operands.reversed()))
            val empty = announcedRoot(WarpStats.empty())
            assertAll(
                { assertEquals(forward, backward, "equal stats must announce equal roots") },
                { assertNotEquals(empty, forward, "the root must depend on the state") },
            )
        }

    private fun TestScope.announcedRoot(state: WarpStats): Long {
        val seam = FakeSeam(selfId = PeerId("self"))
        Quilter(
            seam = seam,
            initial = state,
            valueSerializer = serializer,
            scope = backgroundScope,
            replica = ReplicaId("self"),
            config = QuilterConfig(expectVirtualTime = true, fullStateRetryLimit = 0),
            clock = MonotonicMillis { testScheduler.currentTime },
            random = Random(11),
        )
        seam.addPeer(PeerId("peer"))
        testScheduler.runCurrent()
        return seam.directed
            .map { (_, frame) -> Cbor.decodeFromByteArray(QuiltMessage.serializer(serializer), frame) }
            .filterIsInstance<QuiltMessage.RootDigest<WarpStats>>()
            .single()
            .root
    }

    private fun plainWire(order: List<OpId>): PlainStatsWire =
        PlainStatsWire(linkedMapOf(*order.map { it to SKETCH }.toTypedArray()))

    private companion object {
        /** One `String.hashCode`. See the class KDoc. */
        val SOURCES = listOf("Aa", "BB", "C#").map(::OpId)

        /** Precision 4 is the smallest legal sketch: 12 bytes, against 12 KiB at the default. */
        val SKETCH = HyperLogLog.empty(precision = 4).let { it.piece(it.add("u1")) }

        /**
         * Taken from [PlainStatsWire] before the fix, and read back by a throwaway CBOR decoder that
         * shares nothing with kotlinx-serialization. Do not regenerate it to clear a red: a change
         * here is a wire change, and that wants a conversation first.
         */
        const val GOLDEN: String = 
            "bf68736b657463686573bf624161bf69707265636973696f6e04697265676973746572739f0000000000010000000000" +
            "00ffff624242bf69707265636973696f6e04697265676973746572739f000000000001000000000000ffff624323bf69" +
            "707265636973696f6e04697265676973746572739f000000000001000000000000ffffffff"
    }
}

/** The old serializer, spelled out. The field name and its order are the wire format. */
@Serializable
private class PlainStatsWire(@SerialName("sketches") val sketches: Map<OpId, HyperLogLog>)
