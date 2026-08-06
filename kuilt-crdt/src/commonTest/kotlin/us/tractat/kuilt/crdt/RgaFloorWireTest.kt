package us.tractat.kuilt.crdt

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.serializer
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The compaction floor is part of [Rga]'s **value** — [Rga.equals] compares it — so a wire
 * format that drops it makes `decode(encode(x)) != x`, which is exactly the delta-fingerprint
 * invariant #779 established and #2127 must not break.
 *
 * These tests pin the floor's presence on the wire, its canonical encoding, its behaviour after
 * decode, and the two clock quantities a floor swallows: the Lamport high-water and the per-author
 * seq ceiling.
 */
@OptIn(ExperimentalSerializationApi::class)
class RgaFloorWireTest {

    private val me = ReplicaId("a")
    private val peer = ReplicaId("b")

    /** Deliberately not in canonical order: sorted is `alpha, zulu`. */
    private val zulu = ReplicaId("zulu")
    private val alpha = ReplicaId("alpha")

    private val cbor = Cbor { alwaysUseByteString = true }
    private val ser = Rga.wireSerializer(serializer<String>())

    /** Append [n] records as [author], returning the state and the minted ids in mint order. */
    private fun chain(n: Int, author: ReplicaId = me, prefix: String = "r"): Pair<Rga<String>, List<RgaId>> {
        var rga = Rga.empty<String>()
        var tail = RgaId.HEAD
        val ids = mutableListOf<RgaId>()
        repeat(n) { i ->
            val (next, op) = rga.insertAfter(author, tail, "$prefix$i")
            rga = next
            tail = op.id
            ids += op.id
        }
        return rga to ids
    }

    /** Five own records with the oldest three folded into the floor — the exporter's shape. */
    private fun windowed(): Rga<String> {
        val (rga, ids) = chain(5)
        return requireNotNull(rga.dropWindow(me, ids.take(3).toSet())) { "three ids is not an empty drop" }.first
    }

    private fun roundTrip(value: Rga<String>): Rga<String> =
        cbor.decodeFromByteArray(ser, cbor.encodeToByteArray(ser, value))

    @Test
    fun theFloorSurvivesAWireRoundTrip() {
        val original = windowed()
        val decoded = roundTrip(original)

        assertAll(
            { assertEquals(original.compactedBelow, decoded.compactedBelow) },
            { assertEquals(original.toList(), decoded.toList()) },
            { assertEquals(original, decoded, "and the value round-trips whole") },
        )
    }

    @Test
    fun aDecodedReplicaStillRefusesAnInsertBeneathItsFloor() {
        val decoded = roundTrip(windowed())
        val ghost = RgaOp.Insert(RgaId(lamport = 1L, replicaId = me, seq = 1L), "ghost", RgaId.HEAD)

        assertEquals(decoded.toList(), decoded.apply(ghost).toList(), "the floor is load-bearing after decode")
    }

    /** Three own records with the oldest two folded into [author]'s own floor entry. */
    private fun windowedSlice(author: ReplicaId): Rga<String> {
        val (rga, ids) = chain(3, author, author.value)
        return requireNotNull(rga.dropWindow(author, ids.take(2).toSet())) { "two ids is not empty" }.first
    }

    /**
     * A two-author floor is only byte-stable because it rides [VersionVector]'s own
     * [CanonicalMapSerializer]: `ceilWith` builds its map from `entries.keys + other.entries.keys`,
     * a `LinkedHashSet` in **merge order**, so the two arms here reach `{zulu, alpha}` and
     * `{alpha, zulu}` for one logical floor.
     *
     * Both halves of this test are load-bearing and they fail on disjoint mutations. The merge-order
     * arm is what catches the floor field being encoded through a plain map; it says nothing about
     * whether the floor reaches the wire at all, because both arms would then be equally empty. The
     * round-trip arm is what catches *that* — a decode that drops the floor re-encodes to different
     * bytes than the value it came from.
     *
     * A **one-author** floor would pin neither: one entry has exactly one iteration order, and only
     * [Rga.dropWindow] can raise a floor soundly, and it raises the caller's own entry only — so a
     * two-author floor has to arrive through [Rga.piece], in a merge order.
     */
    @Test
    fun twoReplicasAtTheSameLogicalStateEncodeIdenticalBytes() {
        val zuluFirst = windowedSlice(zulu).piece(windowedSlice(alpha))
        val alphaFirst = windowedSlice(alpha).piece(windowedSlice(zulu))

        assertAll(
            { assertEquals(2, zuluFirst.compactedBelow.entries.size, "a one-entry floor would pin no order") },
            { assertEquals(zuluFirst, alphaFirst, "the two merge orders are one logical value") },
            { assertEquals(zuluFirst, roundTrip(zuluFirst), "and the decoded value equals the one that was encoded") },
            {
                assertEquals(
                    cbor.encodeToByteArray(ser, zuluFirst).toList(),
                    cbor.encodeToByteArray(ser, alphaFirst).toList(),
                    "VersionVector.entries is canonically encoded; the Rga field must not reintroduce order",
                )
            },
            {
                assertEquals(
                    cbor.encodeToByteArray(ser, zuluFirst).toList(),
                    cbor.encodeToByteArray(ser, roundTrip(zuluFirst)).toList(),
                    "and a decoded state re-encodes to the bytes it came from",
                )
            },
        )
    }

    @Test
    fun aWindowThatNeverDrainsKeepsTheTrueLamportAcrossAWireRoundTrip() {
        // The exporter's shape: survivors always carry the real high-water.
        val original = windowed()

        assertEquals(original.lamport, roundTrip(original).lamport)
    }

    @Test
    fun aFullyDrainedWindowDecodesToTheFloorNotToZero() {
        val (rga, ids) = chain(3)
        val drained = requireNotNull(rga.dropWindow(me, ids.toSet())) { "three ids is not an empty drop" }.first
        val decoded = roundTrip(drained)

        assertAll(
            { assertTrue(drained.ops.isEmpty(), "the window drained — no op survives to carry the clock") },
            { assertEquals(3L, decoded.lamport, "the floor is the only surviving evidence of the clock") },
            { assertEquals(drained, decoded, "and the decoded value equals the one that was encoded") },
            {
                val (_, fresh) = decoded.insertAfter(me, RgaId.HEAD, "next")
                assertTrue(fresh.id.seq > 3L, "and the next seq still does not collide, seq was ${fresh.id.seq}")
            },
        )
    }

    /**
     * A **pre-#2127 blob still decodes**, to the unfloored state it encoded.
     *
     * This direction of the break is the one a persisted store actually walks: bytes written by an
     * older build are read by a newer one. The field is absent, `decodeElementIndex` never yields
     * index 1, and the floor defaults to [VersionVector.EMPTY] — which is exactly what such a blob
     * meant, because no floor existed to record.
     *
     * The other direction does **not** hold and is not made to, but its failure mode depends on
     * the codec. A **strict** codec (`Cbor {}`) meets `compactedBelow` as an unknown key and
     * throws — loud, and safe by construction. A codec with `ignoreUnknownKeys = true` — the
     * prevailing convention in this repo (`RaftEngine.kt`, `RelayEnvelope.kt`, `AdmitMessage.kt`,
     * `LobbyMessage.kt`, `RaftRelay.kt`, `RoutedUnicastRouter.kt`, `CoreLearnerAdmission.kt`,
     * `SignalingMessage.kt`) — **silently skips the field** and decodes an unfloored state. Nothing
     * is lost at that instant, but the floor's *suppression* is: that peer will re-accept any
     * purged dot redelivered by a third peer, its [Rga.equals]/[Rga.hashCode] (which fold
     * [Rga.compactedBelow]) disagree with the sender's for one logical state, and its next
     * [Rga.piece] can hand the resurrected element back. Because `ignoreUnknownKeys = true` is the
     * common case, the silent branch — not the throwing one — is the one a mixed deployment is
     * likely to hit. That is the accepted, pre-1.0 wire break.
     */
    @Test
    fun aPreFloorBlobStillDecodesToAnUnflooredState() {
        val (original, _) = chain(3)
        val legacySerializer = LegacyRgaFrame.serializer(RgaOpSerializer(serializer<String>()))
        val legacy = cbor.encodeToByteArray(legacySerializer, LegacyRgaFrame(original.ops.toList()))

        val decoded = cbor.decodeFromByteArray(ser, legacy)

        assertAll(
            { assertEquals(VersionVector.EMPTY, decoded.compactedBelow, "an absent field reads as no floor") },
            { assertEquals(original, decoded, "so the value a pre-#2127 build persisted still round-trips") },
        )
    }

    /**
     * A blob whose floor carries a non-positive entry must decode to the **canonical** floor.
     *
     * kotlinx-serialization decodes a [VersionVector] through its **primary constructor**, which
     * — unlike [VersionVector.of] — keeps a `0`. Since #2127 put the floor into [Rga.equals], a
     * decoded `{a: 3, b: 0}` would be unequal to the canonical `{a: 3}` while describing the
     * identical suppressed set, so the round-trip would produce an unequal value: precisely the
     * invariant this file exists to establish.
     *
     * The hostile floor is written through [RgaFrame] because no production path mints one.
     */
    @Test
    fun aDecodedFloorDropsTheNonPositiveEntryTheConstructorWouldHaveKept() {
        val canonical = windowed()
        val frameSerializer = RgaFrame.serializer(RgaOpSerializer(serializer<String>()))
        val hostile = RgaFrame(
            ops = canonical.ops.toList(),
            compactedBelow = VersionVector(mapOf(me to 3L, peer to 0L)),
        )

        val decoded = cbor.decodeFromByteArray(ser, cbor.encodeToByteArray(frameSerializer, hostile))

        assertAll(
            { assertEquals(VersionVector.of(mapOf(me to 3L)), decoded.compactedBelow, "the zero entry is dropped") },
            { assertEquals(canonical, decoded, "so the decoded value equals the canonical one") },
        )
    }
}

/**
 * A structural stand-in for `RgaSerializer`'s wire struct — CBOR carries field names, not class
 * names, so a frame with the same two field names encodes to a blob [Rga.wireSerializer] decodes.
 *
 * It exists so a test can put a floor on the wire that no production path would mint.
 */
@Serializable
private class RgaFrame<O>(val ops: List<O>, val compactedBelow: VersionVector)

/** The pre-#2127 shape of the same struct: an op-list and nothing else. */
@Serializable
private class LegacyRgaFrame<O>(val ops: List<O>)
