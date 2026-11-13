package us.tractat.kuilt.identity

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A host's signed pass that lets one device back in. The golden layouts are written out by hand,
 * field by field — never derived from the encoder — and every refusal arm sits beside a control the
 * same shape that is accepted, so a decoder that refused everything could not pass.
 */
class GrantTest {
    @Test
    fun layoutIsTheDocumentedBytesForARoomScope() = runTest {
        val grant = Grant.issue(FixedKey(ISSUER), SUBJECT, GrantScope.Room("room-7"), ISSUED_AT, NOT_AFTER, FIXED_ID)
        val expected = (
            "000b" + "6b75696c742f6772616e74" + // tag length, "kuilt/grant"
                "01" + // version
                "0021" + EVEN_POINT + // issuer: its 33-byte compressed point
                "0021" + ODD_POINT + // subject
                "0007" + "01" + "726f6f6d2d37" + // scope: Room, then "room-7" as UTF-8
                "0008" + "0000018bcfe56800" + // issuedAt, 1 700 000 000 000 ms
                "0008" + "0000018bd01c5680" + // notAfter, one hour later
                "0010" + "000102030405060708090a0b0c0d0e0f" + // grant id
                "0000" // parent grant id: empty
            ).hexToByteArray()
        assertLayout(grant, expected)
    }

    @Test
    fun layoutIsTheDocumentedBytesForAnyRoomOfIssuer() = runTest {
        val grant = Grant.issue(FixedKey(ISSUER), SUBJECT, GrantScope.AnyRoomOfIssuer, ISSUED_AT, NOT_AFTER, FIXED_ID)
        val expected = (
            "000b" + "6b75696c742f6772616e74" +
                "01" +
                "0021" + EVEN_POINT +
                "0021" + ODD_POINT +
                "0001" + "02" + // scope: AnyRoomOfIssuer, a bare tag
                "0008" + "0000018bcfe56800" +
                "0008" + "0000018bd01c5680" +
                "0010" + "000102030405060708090a0b0c0d0e0f" +
                "0000"
            ).hexToByteArray()
        assertLayout(grant, expected)
    }

    @Test
    fun unknownScopeTagDoesNotDecode() {
        val refused = listOf(
            "an unknown tag 0x03" to byteArrayOf(0x03),
            "an empty scope" to byteArrayOf(),
            "AnyRoomOfIssuer with a trailing byte" to byteArrayOf(0x02, 0x00),
            "a room id that is not UTF-8" to byteArrayOf(0x01, 0xff.toByte()),
            "a room id in overlong UTF-8" to byteArrayOf(0x01, 0xc0.toByte(), 0x80.toByte()),
            "a room id holding an encoded surrogate" to byteArrayOf(0x01, 0xed.toByte(), 0xa0.toByte(), 0x80.toByte()),
        )
        assertAll(
            { assertEquals(GrantScope.AnyRoomOfIssuer, Grant.decode(wireOf(scope = byteArrayOf(0x02)))?.scope, "control: 0x02") },
            {
                assertEquals(
                    GrantScope.Room("room-7"),
                    Grant.decode(wireOf(scope = byteArrayOf(0x01) + "room-7".encodeToByteArray()))?.scope,
                    "control: 0x01 + room id",
                )
            },
            *refused.map { (name, scope) -> { assertNull(Grant.decode(wireOf(scope = scope)), name) } }.toTypedArray(),
        )
    }

    @Test
    fun nonEmptyParentDoesNotDecode() {
        assertAll(
            { assertNotNull(Grant.decode(wireOf(parent = byteArrayOf())), "control: an empty parent decodes") },
            { assertNull(Grant.decode(wireOf(parent = byteArrayOf(0))), "a one-byte parent") },
            { assertNull(Grant.decode(wireOf(parent = FIXED_ID)), "a 16-byte parent") },
        )
    }

    @Test
    fun issueRequiresNotAfterAfterIssuedAt() = runTest {
        val issuer = DeviceKey.generate()
        val control = Grant.issue(issuer, SUBJECT, GrantScope.AnyRoomOfIssuer, ISSUED_AT, ISSUED_AT + 1)
        assertFailsWith<IllegalArgumentException>("notAfter == issuedAt") {
            Grant.issue(issuer, SUBJECT, GrantScope.AnyRoomOfIssuer, ISSUED_AT, ISSUED_AT)
        }
        assertFailsWith<IllegalArgumentException>("notAfter < issuedAt") {
            Grant.issue(issuer, SUBJECT, GrantScope.AnyRoomOfIssuer, ISSUED_AT, ISSUED_AT - 1)
        }
        assertAll(
            { assertEquals(ISSUED_AT + 1, control.notAfter, "control: one millisecond of life is enough") },
            { assertNotNull(Grant.decode(wireOf(issuedAt = ISSUED_AT, notAfter = ISSUED_AT + 1)), "control: decodes") },
            { assertNull(Grant.decode(wireOf(issuedAt = ISSUED_AT, notAfter = ISSUED_AT)), "a zero-length life does not decode") },
            { assertNull(Grant.decode(wireOf(issuedAt = ISSUED_AT, notAfter = ISSUED_AT - 1)), "a negative life does not decode") },
        )
    }

    @Test
    fun tamperedSubjectFailsIssuerSignature() = runTest {
        val issuer = DeviceKey.generate()
        val subject = DeviceKey.generate().id
        val stranger = DeviceKey.generate().id
        val grant = Grant.issue(issuer, subject, GrantScope.Room("room-7"), ISSUED_AT, NOT_AFTER)
        val swappedSubject = grant.wire.replacing(subject.compressedPoint(), stranger.compressedPoint())
        val swappedIssuer = grant.wire.replacing(issuer.id.compressedPoint(), stranger.compressedPoint())
        val tampered = assertNotNull(Grant.decode(swappedSubject), "a swapped subject is still a well-formed layout")
        val relabelled = assertNotNull(Grant.decode(swappedIssuer), "a swapped issuer is still a well-formed layout")
        val flipped = assertNotNull(Grant.decode(grant.wire.flipLowBit(grant.wire.size - 1)))
        val untouchedVerifies = grant.isSignedByIssuer()
        val tamperedVerifies = tampered.isSignedByIssuer()
        val relabelledVerifies = relabelled.isSignedByIssuer()
        val flippedVerifies = flipped.isSignedByIssuer()
        assertAll(
            { assertTrue(untouchedVerifies, "control: the untouched grant verifies") },
            { assertEquals(stranger, tampered.subject, "precondition: the subject really was swapped") },
            { assertFalse(tamperedVerifies, "a swapped subject fails the issuer's signature") },
            { assertEquals(stranger, relabelled.issuer, "precondition: the issuer really was swapped") },
            { assertFalse(relabelledVerifies, "a signature does not verify under another key") },
            { assertFalse(flippedVerifies, "a flipped signature bit fails") },
        )
    }

    @Test
    fun tamperedLayoutBytesAreRefused() = runTest {
        val grant = Grant.issue(DeviceKey.generate(), SUBJECT, GrantScope.Room("room-7"), ISSUED_AT, NOT_AFTER)
        val layoutSize = grant.wire.size - Layout.SIGNATURE_SIZE
        // Every third byte, so each field's content is hit at least twice (the shortest non-empty field
        // here is 7 bytes) at a third of the cost: a verify runs big-integer point decompression, which
        // takes seconds per hundred on a loaded Kotlin/Native target.
        val flipped = (0 until layoutSize step 3).toList()
        var reachedVerification = 0
        val accepted = flipped.filter { index ->
            val decoded = Grant.decode(grant.wire.flipLowBit(index)) ?: return@filter false
            reachedVerification++
            decoded.isSignedByIssuer()
        }
        val untouchedVerifies = grant.isSignedByIssuer()
        assertAll(
            { assertTrue(untouchedVerifies, "control: the untouched grant verifies") },
            // Most flips leave a well-formed layout, so the signature, not the decoder, has to refuse them.
            { assertTrue(reachedVerification > flipped.size / 2, "precondition: $reachedVerification of ${flipped.size} flips reached verification") },
            { assertEquals(emptyList(), accepted, "layout bytes whose flip still verified") },
        )
    }

    @Test
    fun noExpiryRoundTrips() = runTest {
        val grant = Grant.issue(DeviceKey.generate(), SUBJECT, GrantScope.AnyRoomOfIssuer, ISSUED_AT, Grant.NO_EXPIRY)
        val decoded = assertNotNull(Grant.decode(grant.wire))
        val decodedVerifies = decoded.isSignedByIssuer()
        assertAll(
            { assertEquals(Long.MAX_VALUE, Grant.NO_EXPIRY) },
            { assertEquals(Long.MAX_VALUE, decoded.notAfter) },
            { assertEquals(grant, decoded) },
            { assertTrue(decodedVerifies) },
            { assertTrue("0008" + "7fffffffffffffff" in grant.wire.toHexString(), "notAfter is Long.MAX_VALUE on the wire") },
        )
    }

    @Test
    fun issuedIdsAreDistinct() = runTest {
        val issuer = DeviceKey.generate()
        val ids = List(100) { Grant.issue(issuer, SUBJECT, GrantScope.AnyRoomOfIssuer, ISSUED_AT, NOT_AFTER).id }
        assertAll(
            { assertTrue(ids.all { it.size == Grant.ID_SIZE }, "every id is ${Grant.ID_SIZE} bytes") },
            { assertEquals(100, ids.map { it.toHexString() }.toSet().size, "distinct ids") },
        )
    }

    @Test
    fun idOtherThanSixteenBytesIsRefused() = runTest {
        val issuer = DeviceKey.generate()
        assertFailsWith<IllegalArgumentException>("15 bytes") { issue(issuer, ByteArray(15)) }
        assertFailsWith<IllegalArgumentException>("17 bytes") { issue(issuer, ByteArray(17)) }
        assertAll(
            { assertNull(Grant.decode(wireOf(id = ByteArray(15))), "a 15-byte id does not decode") },
            { assertNotNull(Grant.decode(wireOf(id = ByteArray(16))), "control: a 16-byte id decodes") },
        )
    }

    @Test
    fun decodeRefusesWhatIsNotAGrant() {
        val good = wireOf()
        assertAll(
            { assertNotNull(Grant.decode(good), "control: the well-formed wire decodes") },
            { assertNull(Grant.decode(good.copyOf(Layout.SIGNATURE_SIZE - 1)), "shorter than a signature") },
            { assertNull(Grant.decode(good.copyOfRange(1, good.size)), "a byte dropped from the front") },
            { assertNull(Grant.decode(good + byteArrayOf(0)), "a trailing byte past the signature") },
            { assertNull(Grant.decode(wireOf(tag = "kuilt/challenge")), "another statement's tag") },
            { assertNull(Grant.decode(wireOf(version = 2)), "another version") },
            { assertNull(Grant.decode(wireOf(issuer = byteArrayOf(0x04) + ByteArray(32))), "an uncompressed lead byte") },
            { assertNull(Grant.decode(wireOf(subject = EVEN_POINT.hexToByteArray().copyOf(32))), "a 32-byte subject") },
            { assertNull(Grant.decode(wireOf(issuedAt = null)), "a 7-byte time") },
        )
    }

    @Test
    fun equalityIsByWireBytes() = runTest {
        val issuer = DeviceKey.generate()
        val grant = Grant.issue(issuer, SUBJECT, GrantScope.Room("room-7"), ISSUED_AT, NOT_AFTER)
        val twin = Grant.issue(issuer, SUBJECT, GrantScope.Room("room-7"), ISSUED_AT, NOT_AFTER)
        val decoded = assertNotNull(Grant.decode(grant.wire))
        // decode keeps its own copy: overwriting the array it read afterwards changes nothing.
        val source = grant.wire
        val fromSource = assertNotNull(Grant.decode(source))
        source.fill(0)
        val fromSourceVerifies = fromSource.isSignedByIssuer()
        // Same id, same terms, other signature bytes: another grant, since equality is by wire.
        val otherSignature = assertNotNull(Grant.decode(grant.wire.flipLowBit(grant.wire.size - 1)))
        val id = grant.id
        grant.id[0] = (id[0] + 1).toByte()
        grant.wire[0] = 0x7f
        assertAll(
            { assertEquals(grant, decoded) },
            { assertEquals(grant.hashCode(), decoded.hashCode()) },
            { assertEquals(0, source.count { it != 0.toByte() }, "precondition: the source array really was overwritten") },
            { assertEquals(grant, fromSource, "a grant decoded from an array overwritten afterwards") },
            { assertTrue(fromSourceVerifies, "and it still verifies") },
            { assertContentEquals(grant.id, otherSignature.id, "precondition: the flipped copy has the same id") },
            { assertNotEquals(grant, otherSignature, "equal ids, other signature bytes: unequal") },
            { assertNotEquals(grant, twin, "the same terms issued twice are two grants") },
            { assertContentEquals(id, grant.id, "id hands out a copy") },
            { assertContentEquals(decoded.wire, grant.wire, "wire hands out a copy") },
        )
    }

    @Test
    fun aSignatureOfTheWrongSizeIsRefused() = runTest {
        val short = object : DeviceKey {
            override val id: DidKey = ISSUER
            override suspend fun sign(message: ByteArray): ByteArray = ByteArray(63)
        }
        assertFailsWith<IllegalStateException> {
            Grant.issue(short, SUBJECT, GrantScope.AnyRoomOfIssuer, ISSUED_AT, NOT_AFTER)
        }
    }

    private fun assertLayout(grant: Grant, expected: ByteArray) {
        val (layout, signature) = assertNotNull(Layout.split(grant.wire))
        val versionFlipped = grant.wire.copyOf().also { it[VERSION_OFFSET] = 0x02 }
        assertAll(
            { assertEquals(expected.toHexString(), layout.toHexString()) },
            { assertContentEquals(FixedKey.SIGNATURE, signature, "the signature follows the layout") },
            { assertEquals(grant, Grant.decode(expected + FixedKey.SIGNATURE), "the documented bytes decode to this grant") },
            { assertContentEquals(FIXED_ID, grant.id) },
            { assertEquals(0x01, grant.wire[VERSION_OFFSET].toInt(), "precondition: the version byte is where the control flips it") },
            { assertNull(Grant.decode(versionFlipped), "control: the same bytes under version 2 do not decode") },
        )
    }

    private suspend fun issue(issuer: DeviceKey, id: ByteArray): Grant =
        Grant.issue(issuer, SUBJECT, GrantScope.AnyRoomOfIssuer, ISSUED_AT, NOT_AFTER, id)

    /** A grant's wire form built field by field through [Layout], so each field can be made wrong on its own. */
    private fun wireOf(
        tag: String = "kuilt/grant",
        version: Int = 1,
        issuer: ByteArray = EVEN_POINT.hexToByteArray(),
        subject: ByteArray = ODD_POINT.hexToByteArray(),
        scope: ByteArray = byteArrayOf(0x02),
        issuedAt: Long? = ISSUED_AT,
        notAfter: Long = NOT_AFTER,
        id: ByteArray = FIXED_ID,
        parent: ByteArray = byteArrayOf(),
    ): ByteArray {
        val issuedAtBytes = issuedAt?.toBigEndian() ?: ByteArray(7)
        val fields = listOf(issuer, subject, scope, issuedAtBytes, notAfter.toBigEndian(), id, parent)
        return Layout.encode(tag, version, fields) + FixedKey.SIGNATURE
    }

    /** A key whose name is fixed and whose "signature" is a constant, so a layout can be a golden vector. */
    private class FixedKey(override val id: DidKey) : DeviceKey {
        override suspend fun sign(message: ByteArray): ByteArray = SIGNATURE.copyOf()

        companion object {
            val SIGNATURE = ByteArray(Layout.SIGNATURE_SIZE) { 0x5a }
        }
    }

    private companion object {
        /** The two fixed points of `CompressedPointProbeTest`, one of each parity. */
        const val EVEN_POINT = "02bb5e9f71cd43e4452723479973d0326f901ed963fee59f419bba6cd8649ffb17"
        const val ODD_POINT = "0339a9e222559fa1d3da385b00368b9d29258895752e83e3f9d34dfd29d957e296"
        val ISSUER = DidKey.ofCompressedPoint(EVEN_POINT.hexToByteArray())
        val SUBJECT = DidKey.ofCompressedPoint(ODD_POINT.hexToByteArray())
        const val ISSUED_AT = 1_700_000_000_000L
        const val NOT_AFTER = ISSUED_AT + 3_600_000L
        val FIXED_ID = ByteArray(16) { it.toByte() }

        /** `u16` tag length, then the 11 bytes of "kuilt/grant": the version byte follows. */
        const val VERSION_OFFSET = 2 + 11

        fun Long.toBigEndian(): ByteArray = ByteArray(8) { (this ushr (56 - 8 * it)).toByte() }

        fun ByteArray.flipLowBit(index: Int): ByteArray = copyOf().also { it[index] = (it[index].toInt() xor 0x01).toByte() }

        /** These bytes with the one occurrence of [from] replaced by [to], which is the same length. */
        fun ByteArray.replacing(from: ByteArray, to: ByteArray): ByteArray {
            val hex = toHexString()
            val at = hex.indexOf(from.toHexString())
            check(at >= 0 && at % 2 == 0 && hex.indexOf(from.toHexString(), at + 1) < 0) { "expected exactly one occurrence" }
            return (hex.substring(0, at) + to.toHexString() + hex.substring(at + 2 * from.size)).hexToByteArray()
        }
    }
}
