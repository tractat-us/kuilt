@file:OptIn(IdentityInternalApi::class)

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
 * The host's signed Challenge. Its golden vector is written out by hand, field by field, and never
 * derived from the encoder. Every refusal arm carries a control that is accepted, so each one shows
 * the check can fail rather than only that it passed.
 */
class ChallengeTest {
    @Test
    fun layoutIsTheDocumentedBytes() = runTest {
        val host = FixedDeviceKey(GENERATOR_KEY, PLACEHOLDER_SIGNATURE)
        val challenge = signFixed(host)
        val expected = (
            "000f" + "6b75696c742f6368616c6c656e6765" + // tag "kuilt/challenge"
                "01" + // version
                "0021" + GENERATOR_COMPRESSED_HEX + // host key: its 33-byte compressed point
                "0004" + "726f6f6d" + // room id "room"
                "0001" + "68" + // host peer id "h"
                "0001" + "6a" + // joiner peer id "j"
                "0010" + "101112131415161718191a1b1c1d1e1f" + // joiner nonce
                "0010" + "202122232425262728292a2b2c2d2e2f" // host nonce
            ).hexToByteArray()
        val split = assertNotNull(Layout.split(challenge.wire), "the wire splits")
        // The negative control: one length byte changed, and the vector no longer decodes.
        val mutated = (expected + PLACEHOLDER_SIGNATURE).withLowBitFlipped(HOST_KEY_LENGTH_OFFSET)
        assertAll(
            { assertEquals(expected.toHexString(), split.first.toHexString(), "layout bytes") },
            { assertContentEquals(PLACEHOLDER_SIGNATURE, split.second, "the signature follows the layout") },
            { assertEquals(1, host.signed.size, "signed once") },
            { assertEquals(expected.toHexString(), host.signed.single().toHexString(), "the signer was handed the layout itself") },
            {
                val decoded = assertNotNull(Challenge.decode(expected + PLACEHOLDER_SIGNATURE), "the vector decodes")
                assertEquals(challenge, decoded, "and decodes to the statement that was signed")
            },
            { assertNull(Challenge.decode(mutated), "the mutated vector does not decode") },
        )
    }

    @Test
    fun signedDecodesAndVerifies() = runTest {
        val host = DeviceKey.generate()
        val challenge = signFixed(host)
        val decoded = assertNotNull(Challenge.decode(challenge.wire))
        val flipped = assertNotNull(Challenge.decode(challenge.wire.withLowBitFlipped(challenge.wire.size - 1)))
        val verdicts = listOf(challenge.isSignedByHost(), decoded.isSignedByHost(), flipped.isSignedByHost())
        assertAll(
            { assertEquals(host.id, decoded.hostKey) },
            { assertEquals(ROOM, decoded.roomId) },
            { assertEquals(HOST_PEER, decoded.hostPeerId) },
            { assertEquals(JOINER_PEER, decoded.joinerPeerId) },
            { assertContentEquals(JOINER_NONCE, decoded.joinerNonce) },
            { assertContentEquals(HOST_NONCE, decoded.hostNonce) },
            { assertContentEquals(challenge.wire, decoded.wire) },
            { assertTrue(verdicts[0], "the signed statement verifies") },
            { assertTrue(verdicts[1], "and so does its decoded copy") },
            // The negative control: one signature bit flipped, and the same check refuses it.
            { assertFalse(verdicts[2], "a flipped signature bit") },
        )
    }

    @Test
    fun tamperedFieldFailsVerification() = runTest {
        val challenge = signFixed(DeviceKey.generate())
        val (layout, signature) = assertNotNull(Layout.split(challenge.wire))
        val fields = assertNotNull(Layout.decode(layout, TAG, 1, FIELD_COUNT))
        val rows = fields.indices.map { index ->
            // The field's first byte, flipped in place: re-encoding changes no length, so these are
            // exactly the original wire bytes with one bit changed inside field [index].
            val tampered = Layout.encode(TAG, 1, fields.withField(index, fields[index].withLowBitFlipped(0))) + signature
            val decoded = Challenge.decode(tampered)
            Triple(index, decoded, decoded?.isSignedByHost())
        }
        assertAll(
            { assertEquals(6, fields.size, "precondition: six fields, all non-empty") },
            { assertTrue(fields.all { it.isNotEmpty() }, "precondition: every field has a byte to flip") },
            *rows.flatMap<_, () -> Unit> { (index, decoded, verdict) ->
                listOf(
                    // Every flip here still decodes, so the signature check is what refuses it: the rig reached it.
                    { assertNotNull(decoded, "field $index tampered still decodes") },
                    { assertEquals(false, verdict, "field $index tampered fails verification") },
                )
            }.toTypedArray(),
        )
    }

    @Test
    fun wrongSignerFails() = runTest {
        val host = DeviceKey.generate()
        val other = DeviceKey.generate()
        val forged = signFixed(Impostor(claimed = host.id, actual = other))
        val honest = signFixed(host)
        val forgedVerdict = forged.isSignedByHost()
        val honestVerdict = honest.isSignedByHost()
        assertAll(
            { assertEquals(host.id, forged.hostKey, "precondition: the forgery names the host") },
            { assertFalse(forgedVerdict, "signed by another key") },
            { assertTrue(honestVerdict, "the control: signed by the key it names") },
        )
    }

    @Test
    fun decodeRejectsTheOtherStatementsTag() = runTest {
        val challenge = signFixed(DeviceKey.generate())
        val proof = AdmissionProof.sign(DeviceKey.generate(), ROOM, GENERATOR_KEY, HOST_PEER, JOINER_PEER, "dev", HOST_NONCE)
        val (layout, signature) = assertNotNull(Layout.split(challenge.wire))
        val fields = assertNotNull(Layout.decode(layout, TAG, 1, FIELD_COUNT))
        assertAll(
            { assertNull(Challenge.decode(proof.wire), "a proof's wire is not a challenge") },
            // Only the tag differs: the challenge's own fields under the proof's tag.
            { assertNull(Challenge.decode(Layout.encode("kuilt/admit-proof", 1, fields) + signature), "the proof's tag") },
            { assertNull(Challenge.decode(Layout.encode(TAG, 2, fields) + signature), "another version") },
            // The control: the same fields re-encoded under the challenge's tag decode.
            { assertNotNull(Challenge.decode(Layout.encode(TAG, 1, fields) + signature), "the control decodes") },
        )
    }

    @Test
    fun nonceOfFifteenBytesIsRefusedAtConstruction() = runTest {
        val host = DeviceKey.generate()
        // assertFailsWith is inline, so a suspending sign runs inside it; assertAll's arms cannot suspend.
        assertFailsWith<IllegalArgumentException>("15-byte joiner nonce") { sign(host, joinerNonce = ByteArray(15)) }
        assertFailsWith<IllegalArgumentException>("15-byte host nonce") { sign(host, hostNonce = ByteArray(15)) }
        assertFailsWith<IllegalArgumentException>("17-byte joiner nonce") { sign(host, joinerNonce = ByteArray(17)) }
        assertFailsWith<IllegalArgumentException>("17-byte host nonce") { sign(host, hostNonce = ByteArray(17)) }
        assertFailsWith<IllegalArgumentException>("15-byte joiner nonce at construction") {
            Challenge(host.id, ROOM, HOST_PEER, JOINER_PEER, ByteArray(15), HOST_NONCE, ByteArray(0))
        }
        // The control: sixteen bytes each is a challenge.
        val control = sign(host, ByteArray(16), ByteArray(16)).isSignedByHost()
        assertAll(
            { assertEquals(16, Challenge.NONCE_SIZE) },
            { assertTrue(control, "16-byte nonces") },
        )
    }

    @Test
    fun decodeRefusesMalformedFieldsWithoutThrowing() = runTest {
        val challenge = signFixed(FixedDeviceKey(GENERATOR_KEY, PLACEHOLDER_SIGNATURE))
        val (layout, signature) = assertNotNull(Layout.split(challenge.wire))
        val fields = assertNotNull(Layout.decode(layout, TAG, 1, FIELD_COUNT))
        fun decodeWith(index: Int, field: ByteArray) = Challenge.decode(Layout.encode(TAG, 1, fields.withField(index, field)) + signature)
        assertAll(
            { assertNull(decodeWith(JOINER_NONCE_FIELD, ByteArray(15)), "a 15-byte joiner nonce") },
            { assertNull(decodeWith(HOST_NONCE_FIELD, ByteArray(17)), "a 17-byte host nonce") },
            { assertNull(decodeWith(HOST_KEY_FIELD, fields[HOST_KEY_FIELD].copyOf(32)), "a 32-byte host key") },
            { assertNull(decodeWith(HOST_KEY_FIELD, fields[HOST_KEY_FIELD].copyOf().also { it[0] = 0x04 }), "a 0x04 lead byte") },
            { assertNull(decodeWith(ROOM_FIELD, byteArrayOf(0xc3.toByte())), "a room id that is not UTF-8") },
            { assertNull(decodeWith(HOST_PEER_FIELD, byteArrayOf(0xff.toByte())), "a host peer id that is not UTF-8") },
            { assertNull(Challenge.decode(ByteArray(Layout.SIGNATURE_SIZE - 1)), "shorter than a signature") },
            // The control: the unaltered fields decode, and a multi-byte UTF-8 room id does too.
            { assertNotNull(decodeWith(ROOM_FIELD, fields[ROOM_FIELD]), "the unaltered fields") },
            { assertEquals("é", decodeWith(ROOM_FIELD, byteArrayOf(0xc3.toByte(), 0xa9.toByte()))?.roomId, "well-formed UTF-8") },
            // Two encodings a lenient decoder accepts and well-formed UTF-8 forbids.
            { assertNull(decodeWith(ROOM_FIELD, "c080".hexToByteArray()), "an overlong NUL, C0 80") },
            { assertNull(decodeWith(JOINER_PEER_FIELD, "eda080".hexToByteArray()), "an encoded surrogate, ED A0 80") },
        )
    }

    @Test
    fun anUnpairedSurrogateIsRefusedAtSigning() = runTest {
        val host = FixedDeviceKey(GENERATOR_KEY, PLACEHOLDER_SIGNATURE)
        // A UTF-8 encoder that is not strict writes a replacement here instead (`?` on the JVM,
        // EF BF BD on wasmJs), so the statement would sign a value other than the one it was given.
        assertFailsWith<IllegalArgumentException>("a room id ending in a high surrogate") {
            Challenge.sign(host, "r\uD800", HOST_PEER, JOINER_PEER, JOINER_NONCE, HOST_NONCE)
        }
        assertFailsWith<IllegalArgumentException>("a host peer id starting with a low surrogate") {
            Challenge.sign(host, ROOM, "\uDC00h", JOINER_PEER, JOINER_NONCE, HOST_NONCE)
        }
        assertFailsWith<IllegalArgumentException>("a joiner peer id with its surrogates reversed") {
            Challenge.sign(host, ROOM, HOST_PEER, "\uDFB2\uD83C", JOINER_NONCE, HOST_NONCE)
        }
        // The control: a room id with characters of three and four UTF-8 bytes signs, and round-trips.
        val room = "部屋\uD83C\uDFB2"
        val challenge = Challenge.sign(host, room, HOST_PEER, JOINER_PEER, JOINER_NONCE, HOST_NONCE)
        val fields = assertNotNull(Layout.decode(assertNotNull(Layout.split(challenge.wire)).first, TAG, 1, FIELD_COUNT))
        assertAll(
            // 部 E9 83 A8, 屋 E5 B1 8B, U+1F3B2 F0 9F 8E B2: written by hand, not by the encoder.
            { assertEquals("e983a8e5b18bf09f8eb2", fields[ROOM_FIELD].toHexString(), "the room id's UTF-8") },
            { assertEquals(room, challenge.roomId, "the signed statement carries the value it was given") },
            { assertEquals(room, Challenge.decode(challenge.wire)?.roomId, "and so does its decoded copy") },
        )
    }

    @Test
    fun aStatementIsItsWireBytes() = runTest {
        val host = FixedDeviceKey(GENERATOR_KEY, PLACEHOLDER_SIGNATURE)
        val one = signFixed(host)
        val wire = one.wire
        wire[0] = 0x7f
        val nonce = one.joinerNonce
        nonce[0] = 0x7f
        val same = signFixed(host)
        val different = sign(host, hostNonce = ByteArray(16))
        assertAll(
            { assertEquals(one, same, "equal by wire bytes") },
            { assertEquals(one.hashCode(), same.hashCode()) },
            { assertNotEquals(one, different) },
            { assertEquals(0x00, one.wire[0].toInt(), "the wire is a copy: writing to it changes nothing") },
            { assertEquals(0x10, one.joinerNonce[0].toInt(), "and so is a nonce") },
        )
    }

    private suspend fun signFixed(host: DeviceKey): Challenge = sign(host)

    private suspend fun sign(
        host: DeviceKey,
        joinerNonce: ByteArray = JOINER_NONCE,
        hostNonce: ByteArray = HOST_NONCE,
    ): Challenge = Challenge.sign(host, ROOM, HOST_PEER, JOINER_PEER, joinerNonce, hostNonce)

    private companion object {
        const val TAG = "kuilt/challenge"
        const val FIELD_COUNT = 6
        const val ROOM = "room"
        const val HOST_PEER = "h"
        const val JOINER_PEER = "j"
        val JOINER_NONCE = countingBytes(0x10, 16)
        val HOST_NONCE = countingBytes(0x20, 16)

        const val HOST_KEY_FIELD = 0
        const val ROOM_FIELD = 1
        const val HOST_PEER_FIELD = 2
        const val JOINER_PEER_FIELD = 3
        const val JOINER_NONCE_FIELD = 4
        const val HOST_NONCE_FIELD = 5

        /** The low byte of the host key's length prefix: after the `u16` tag length, 15 tag bytes, the version and the prefix's high byte. */
        const val HOST_KEY_LENGTH_OFFSET = 2 + 15 + 1 + 1
    }
}
