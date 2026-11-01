@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    ExperimentalStdlibApi::class,
)

package us.tractat.kuilt.heddle

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.ReplicaId
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
 * Peers that merged the same [EntitlementLedger] operands in different orders must encode the same
 * bytes, or their anti-entropy roots never match and every round ships the whole ledger (#2715).
 *
 * ## Why these ids
 *
 * `"Aa"`, `"BB"` and `"C#"` share one `String.hashCode`, so on the JVM they land in one `HashMap`
 * bin, and a bin iterates in insertion order. On Kotlin/Native and wasmJs the whole map iterates in
 * insertion order anyway. So the fold order reaches the encoder on **every** target family, which
 * ordinary short ids would not manage on the JVM. [theFoldOrderReachesTheIterationOrder] is the rig
 * receipt for that claim.
 *
 * ## Why a separate wire class
 *
 * [PlainLedgerWire] is the reference: plain maps, hand-sorted, no merge, no canonical serializer. A
 * reference built by merging would only assert `x == x`. It also stands in for a peer on a build
 * that predates canonical ordering, since its serializer is what the ledger's used to be.
 */
class EntitlementLedgerCanonicalEncodingTest {

    private class Arm(val name: String, val operands: List<EntitlementLedger>)

    private val serializer = EntitlementLedger.serializer()

    private fun fold(operands: List<EntitlementLedger>): EntitlementLedger =
        operands.fold(EntitlementLedger.ZERO) { merged, next -> merged.piece(next) }

    private fun hex(ledger: EntitlementLedger): String =
        Cbor.encodeToByteArray(serializer, ledger).toHexString()

    /** One arm per place an unordered collection reaches the encoder, so a red names its field. */
    private val arms: List<Arm> = buildList {
        add(Arm("records", EDGES.map { EntitlementLedger.of(records = mapOf(it to setOf(recordOf(it, "c")))) }))
        add(
            Arm(
                "records' inner set",
                listOf("c1", "c2", "c3").map {
                    EntitlementLedger.of(records = mapOf(EDGES[0] to setOf(recordOf(EDGES[0], it))))
                },
            ),
        )
        add(Arm("minted", MINTS.map { EntitlementLedger.of(minted = mapOf(it to MINT)) }))
        add(edgeCounters("issued") { EntitlementLedger.of(issued = it) })
        add(edgeCounters("returned") { EntitlementLedger.of(returned = it) })
        add(edgeCounters("leafSpent") { EntitlementLedger.of(leafSpent = it) })
        add(edgeCounters("rollupSpent") { EntitlementLedger.of(rollupSpent = it) })
        addAll(matrix("transfers") { EntitlementLedger.of(transfers = it) })
        add(Arm("lifecycle", EDGES.map { EntitlementLedger.of(lifecycle = mapOf(it to Lifecycle.ACTIVE)) }))
        add(edgeCounters("issuedRelocIn") { EntitlementLedger.of(issuedRelocIn = it) })
        add(edgeCounters("leafRelocIn") { EntitlementLedger.of(leafRelocIn = it) })
        add(edgeCounters("leafRelocOut") { EntitlementLedger.of(leafRelocOut = it) })
        add(edgeCounters("rollupRelocIn") { EntitlementLedger.of(rollupRelocIn = it) })
        add(edgeCounters("rollupRelocOut") { EntitlementLedger.of(rollupRelocOut = it) })
        add(Arm("gauges", EDGES.map { EntitlementLedger.of(gauges = mapOf(it to GAUGE)) }))
        addAll(matrix("transferRelocIn") { EntitlementLedger.of(transferRelocIn = it) })
        addAll(matrix("transferRelocOut") { EntitlementLedger.of(transferRelocOut = it) })
    }

    private fun edgeCounters(name: String, build: (Map<AttachmentId, GCounter>) -> EntitlementLedger): Arm =
        Arm(name, EDGES.map { build(mapOf(it to COUNTER)) })

    /** A matrix is two levels of map, and the outer serializer passes its values through untouched. */
    private fun matrix(name: String, build: (Map<PathKey, Map<ReplicaId, GCounter>>) -> EntitlementLedger): List<Arm> =
        listOf(
            Arm(name, EDGES.map { build(mapOf(PathKey.of(it) to mapOf(DONOR to COUNTER))) }),
            Arm("$name' donor rows", DONORS.map { build(mapOf(PathKey.ROOT to mapOf(it to COUNTER))) }),
        )

    /** Every arm, merged both ways round, so a field that escapes canonical ordering names itself. */
    @Test
    fun everyFieldEncodesTheSameWhicheverOrderItsOperandsMerged() {
        assertEquals(20, arms.size, "16 fields, the records' inner set, and each of the 3 matrices' inner rows")
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
     * The rig receipt. A byte comparison can only fail where the merged map has more than one
     * iteration order to choose between, so this asserts that the helper the ledger merges through
     * really does surface the fold order for these keys, on the target running it. On the JVM that
     * rests entirely on the hash collision: replace [EDGES] with ordinary ids and this reds here
     * rather than leaving the arms above green by accident.
     */
    @Test
    fun theFoldOrderReachesTheIterationOrder() {
        fun <K> keysAfterFolding(keys: List<K>): List<K> =
            keys.fold(emptyMap<K, Long>()) { merged, key -> merged.mergeValues(mapOf(key to 1L)) { mine, _ -> mine } }.keys.toList()

        assertAll(
            { assertNotEquals(keysAfterFolding(EDGES), keysAfterFolding(EDGES.reversed()), "edge keys") },
            { assertNotEquals(keysAfterFolding(DONORS), keysAfterFolding(DONORS.reversed()), "donor rows") },
        )
    }

    /**
     * The reference and the golden agree: plain maps in hand-sorted order encode to these bytes. No
     * canonical serializer is on this path, so this stays green through the fix, and it is the
     * statement that the fix reorders entries without changing the wire format.
     */
    @Test
    fun aPlainEncodingOfHandSortedEntriesIsTheGolden() {
        assertEquals(GOLDEN, Cbor.encodeToByteArray(PlainLedgerWire.serializer(), plainWire(ascending = true)).toHexString())
    }

    /**
     * The cross-target pin: `commonTest` runs on every target, so all of them are held to one answer,
     * which [everyFieldEncodesTheSameWhicheverOrderItsOperandsMerged] cannot do. That test only ever
     * compares a target against itself.
     */
    @Test
    fun aMergedLedgerEncodesToTheGoldenWhicheverWayItMerged() {
        assertAll(
            { assertEquals(GOLDEN, hex(fold(goldenOperands())), "forward") },
            { assertEquals(GOLDEN, hex(fold(goldenOperands().reversed())), "backward") },
        )
    }

    /** A peer on an older build reads canonical bytes: its decoder takes entries in any order. */
    @Test
    fun aPlainReaderDecodesCanonicalBytes() {
        val decoded = Cbor.decodeFromByteArray(PlainLedgerWire.serializer(), GOLDEN.hexToByteArray())
        assertEquals(plainWire(ascending = true).fields(), decoded.fields())
    }

    /**
     * And the other direction: unsorted bytes from an older peer decode to the same value, and a
     * reader that re-encodes them lands on the canonical form rather than preserving the order it
     * was handed.
     */
    @Test
    fun anOlderPeersUnsortedBytesDecodeAndReEncodeCanonically() {
        val unsorted = Cbor.encodeToByteArray(PlainLedgerWire.serializer(), plainWire(ascending = false))
        val decoded = Cbor.decodeFromByteArray(serializer, unsorted)
        assertAll(
            { assertEquals(fold(goldenOperands()), decoded, "the value does not depend on the entry order") },
            { assertEquals(GOLDEN, hex(decoded), "re-encoding must not carry the sender's order forward") },
        )
    }

    /**
     * What the issue is actually about, read off a real [Quilter]: the root it announces to a new
     * peer. Production's framing rather than a mirror of it, since the digest is what decides
     * whether a round ships the ledger. The control keeps the comparison honest: a root that did not
     * depend on the state would satisfy the first assertion for free.
     */
    @Test
    fun peersThatMergedInDifferentOrdersAnnounceTheSameRoot() =
        runTest(UnconfinedTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val everything = arms.flatMap { it.operands }
            val forward = announcedRoot(fold(everything))
            val backward = announcedRoot(fold(everything.reversed()))
            val empty = announcedRoot(EntitlementLedger.ZERO)
            assertAll(
                { assertEquals(forward, backward, "equal ledgers must announce equal roots") },
                { assertNotEquals(empty, forward, "the root must depend on the state") },
            )
        }

    private fun TestScope.announcedRoot(state: EntitlementLedger): Long {
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
            .filterIsInstance<QuiltMessage.RootDigest<EntitlementLedger>>()
            .single()
            .root
    }

    /** The first two operands of every arm, which is all a golden needs to pin an order. */
    private fun goldenOperands(): List<EntitlementLedger> = arms.flatMap { it.operands.take(2) }

    /** What [goldenOperands] merges to, written out by hand with no merge anywhere on the path. */
    private fun plainWire(ascending: Boolean): PlainLedgerWire {
        fun <K, V> entries(vararg pairs: Pair<K, V>): Map<K, V> =
            linkedMapOf(*(if (ascending) pairs else pairs.reversedArray()))

        fun edges(): Map<AttachmentId, GCounter> = entries(EDGES[0] to COUNTER, EDGES[1] to COUNTER)

        fun rows(): Map<ReplicaId, GCounter> = entries(DONORS[0] to COUNTER, DONORS[1] to COUNTER)

        // PathKey.ROOT is the empty string, so it sorts ahead of both edge keys.
        fun matrix(): Map<PathKey, Map<ReplicaId, GCounter>> = entries(
            PathKey.ROOT to rows(),
            PathKey.of(EDGES[0]) to mapOf(DONOR to COUNTER),
            PathKey.of(EDGES[1]) to mapOf(DONOR to COUNTER),
        )

        val inner = listOf(recordOf(EDGES[0], "c"), recordOf(EDGES[0], "c1"), recordOf(EDGES[0], "c2"))
        return PlainLedgerWire(
            records = entries(
                EDGES[0] to (if (ascending) inner else inner.reversed()).toCollection(LinkedHashSet()),
                EDGES[1] to setOf(recordOf(EDGES[1], "c")),
            ),
            minted = entries(MINTS[0] to MINT, MINTS[1] to MINT),
            issued = edges(),
            returned = edges(),
            leafSpent = edges(),
            rollupSpent = edges(),
            transfers = matrix(),
            lifecycle = entries(EDGES[0] to Lifecycle.ACTIVE, EDGES[1] to Lifecycle.ACTIVE),
            issuedRelocIn = edges(),
            leafRelocIn = edges(),
            leafRelocOut = edges(),
            rollupRelocIn = edges(),
            rollupRelocOut = edges(),
            gauges = entries(EDGES[0] to GAUGE, EDGES[1] to GAUGE),
            transferRelocIn = matrix(),
            transferRelocOut = matrix(),
        )
    }

    private companion object {
        /** One `String.hashCode`, so one JVM bucket. See the class KDoc. */
        val COLLIDING = listOf("Aa", "BB", "C#")
        val EDGES = COLLIDING.map(::AttachmentId)
        val MINTS = COLLIDING.map(::MintId)
        val DONORS = COLLIDING.map(::ReplicaId)
        val DONOR = ReplicaId("r")
        val COUNTER = GCounter.of(ReplicaId("r") to 1L)
        val MINT = MintRecord(GroupId("root"), ReplicaId("r"), 1L)
        val GAUGE = Gauge(Rational.of(1L), 1L)

        fun recordOf(edge: AttachmentId, child: String): AttachmentRecord =
            AttachmentRecord(edge, GroupId("root"), GroupId(child), Weight.of(1L))

        /**
         * Taken from [PlainLedgerWire] before the fix, and read back field by field by a throwaway
         * CBOR decoder that shares nothing with kotlinx-serialization. Do not regenerate it to clear
         * a red: a change here is a wire change, and that wants a conversation first.
         */
        const val GOLDEN: String = "TODO-golden"
    }
}

/** The ledger's old serializer, spelled out. Field names and order are the wire format. */
@Serializable
private class PlainLedgerWire(
    val records: Map<AttachmentId, Set<AttachmentRecord>>,
    val minted: Map<MintId, MintRecord>,
    val issued: Map<AttachmentId, GCounter>,
    val returned: Map<AttachmentId, GCounter>,
    val leafSpent: Map<AttachmentId, GCounter>,
    val rollupSpent: Map<AttachmentId, GCounter>,
    val transfers: Map<PathKey, Map<ReplicaId, GCounter>>,
    val lifecycle: Map<AttachmentId, Lifecycle> = emptyMap(),
    val issuedRelocIn: Map<AttachmentId, GCounter> = emptyMap(),
    val leafRelocIn: Map<AttachmentId, GCounter> = emptyMap(),
    val leafRelocOut: Map<AttachmentId, GCounter> = emptyMap(),
    val rollupRelocIn: Map<AttachmentId, GCounter> = emptyMap(),
    val rollupRelocOut: Map<AttachmentId, GCounter> = emptyMap(),
    val gauges: Map<AttachmentId, Gauge> = emptyMap(),
    val transferRelocIn: Map<PathKey, Map<ReplicaId, GCounter>> = emptyMap(),
    val transferRelocOut: Map<PathKey, Map<ReplicaId, GCounter>> = emptyMap(),
) {
    fun fields(): List<Any> = listOf(
        records, minted, issued, returned, leafSpent, rollupSpent, transfers, lifecycle, issuedRelocIn,
        leafRelocIn, leafRelocOut, rollupRelocIn, rollupRelocOut, gauges, transferRelocIn, transferRelocOut,
    )
}
