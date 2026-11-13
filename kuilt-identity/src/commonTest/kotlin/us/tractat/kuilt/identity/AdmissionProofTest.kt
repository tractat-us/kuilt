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
 * The joiner's signed Admission proof. Its golden vector is written out by hand, field by field, and
 * never derived from the encoder. Every refusal arm carries a control that is accepted, so each one
 * shows the check can fail rather than only that it passed.
 */
class AdmissionProofTest {
    @Test
    fun layoutIsTheDocumentedBytes() = runTest {
        val joiner = FixedDeviceKey(GENERATOR_KEY, PLACEHOLDER_SIGNATURE)
        val proof = sign(joiner)
        val expected = (
            "0011" + "6b75696c742f61646d69742d70726f6f66" + // tag "kuilt/admit-proof"
                "01" + // version
                "0004" + "726f6f6d" + // room id "room"
                "0021" + GENERATOR_COMPRESSED_HEX + // host key: its 33-byte compressed point
                "0001" + "68" + // host peer id "h"
                "0001" + "6a" + // joiner peer id "j"
                "0003" + "646576" + // device id "dev"
                "0010" + "202122232425262728292a2b2c2d2e2f" + // host nonce
                "0000" // channel binding: reserved, empty
            ).hexToByteArray()
        val split = assertNotNull(Layout.split(proof.wire), "the wire splits")
        // The negative control: the reserved field's length byte changed, and the vector no longer decodes.
        val mutated = (expected + PLACEHOLDER_SIGNATURE).withLowBitFlipped(expected.size - 1)
        assertAll(
            { assertEquals(expected.toHexString(), split.first.toHexString(), "layout bytes") },
            { assertContentEquals(PLACEHOLDER_SIGNATURE, split.second, "the signature follows the layout") },
            { assertEquals(expected.toHexString(), joiner.signed.single().toHexString(), "the signer was handed the layout itself") },
            {
                val decoded = assertNotNull(AdmissionProof.decode(expected + PLACEHOLDER_SIGNATURE), "the vector decodes")
                assertEquals(proof, decoded, "and decodes to the statement that was signed")
            },
            { assertNull(AdmissionProof.decode(mutated), "the mutated vector does not decode") },
        )
    }

    @Test
    fun anAbsentDeviceIdIsTheEmptyField() = runTest {
        val proof = sign(FixedDeviceKey(GENERATOR_KEY, PLACEHOLDER_SIGNATURE), deviceId = null)
        val fields = assertNotNull(Layout.decode(assertNotNull(Layout.split(proof.wire)).first, TAG, 1, FIELD_COUNT))
        assertAll(
            { assertEquals(0, fields[DEVICE_ID_FIELD].size, "no device id is zero bytes") },
            { assertEquals("", proof.deviceId) },
            { assertEquals("", assertNotNull(AdmissionProof.decode(proof.wire)).deviceId) },
        )
    }

    @Test
    fun signedDecodesAndVerifies() = runTest {
        val joiner = DeviceKey.generate()
        val proof = sign(joiner)
        val decoded = assertNotNull(AdmissionProof.decode(proof.wire))
        val flipped = assertNotNull(AdmissionProof.decode(proof.wire.withLowBitFlipped(proof.wire.size - 1)))
        val verdicts = listOf(proof.isSignedBy(joiner.id), decoded.isSignedBy(joiner.id), flipped.isSignedBy(joiner.id))
        assertAll(
            { assertEquals(ROOM, decoded.roomId) },
            { assertEquals(GENERATOR_KEY, decoded.hostKey) },
            { assertEquals(HOST_PEER, decoded.hostPeerId) },
            { assertEquals(JOINER_PEER, decoded.joinerPeerId) },
            { assertEquals(DEVICE, decoded.deviceId) },
            { assertContentEquals(HOST_NONCE, decoded.hostNonce) },
            { assertContentEquals(proof.wire, decoded.wire) },
            { assertTrue(verdicts[0], "the signed statement verifies") },
            { assertTrue(verdicts[1], "and so does its decoded copy") },
            // The negative control: one signature bit flipped, and the same check refuses it.
            { assertFalse(verdicts[2], "a flipped signature bit") },
        )
    }

    @Test
    fun tamperedFieldFailsVerification() = runTest {
        val joiner = DeviceKey.generate()
        val proof = sign(joiner)
        val (layout, signature) = assertNotNull(Layout.split(proof.wire))
        val fields = assertNotNull(Layout.decode(layout, TAG, 1, FIELD_COUNT))
        // Every field but the reserved channel binding, which is empty and has no byte to flip;
        // nonEmptyChannelBindingDoesNotDecode covers it.
        val tamperable = fields.indices.filter { it != CHANNEL_BINDING_FIELD }
        val rows = tamperable.map { index ->
            val tampered = Layout.encode(TAG, 1, fields.withField(index, fields[index].withLowBitFlipped(0))) + signature
            val decoded = AdmissionProof.decode(tampered)
            Triple(index, decoded, decoded?.isSignedBy(joiner.id))
        }
        assertAll(
            { assertEquals(7, fields.size, "precondition: seven fields") },
            { assertEquals(6, tamperable.size, "precondition: six of them carry bytes") },
            { assertTrue(tamperable.all { fields[it].isNotEmpty() }, "precondition: every tamperable field has a byte to flip") },
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
        val joiner = DeviceKey.generate()
        val other = DeviceKey.generate()
        val proof = sign(joiner)
        val underOther = proof.isSignedBy(other.id)
        val underHost = proof.isSignedBy(GENERATOR_KEY)
        val underSigner = proof.isSignedBy(joiner.id)
        assertAll(
            { assertFalse(underOther, "checked under another key") },
            { assertFalse(underHost, "checked under the host's key it names") },
            { assertTrue(underSigner, "the control: checked under the signer's key") },
        )
    }

    @Test
    fun decodeRejectsTheOtherStatementsTag() = runTest {
        val proof = sign(DeviceKey.generate())
        val challenge = Challenge.sign(DeviceKey.generate(), ROOM, HOST_PEER, JOINER_PEER, countingBytes(0x10, 16), HOST_NONCE)
        val (layout, signature) = assertNotNull(Layout.split(proof.wire))
        val fields = assertNotNull(Layout.decode(layout, TAG, 1, FIELD_COUNT))
        assertAll(
            { assertNull(AdmissionProof.decode(challenge.wire), "a challenge's wire is not a proof") },
            // Only the tag differs: the proof's own fields under the challenge's tag.
            { assertNull(AdmissionProof.decode(Layout.encode("kuilt/challenge", 1, fields) + signature), "the challenge's tag") },
            { assertNull(AdmissionProof.decode(Layout.encode(TAG, 2, fields) + signature), "another version") },
            // The control: the same fields re-encoded under the proof's tag decode.
            { assertNotNull(AdmissionProof.decode(Layout.encode(TAG, 1, fields) + signature), "the control decodes") },
        )
    }

    @Test
    fun nonEmptyChannelBindingDoesNotDecode() = runTest {
        val proof = sign(DeviceKey.generate())
        val (layout, signature) = assertNotNull(Layout.split(proof.wire))
        val fields = assertNotNull(Layout.decode(layout, TAG, 1, FIELD_COUNT))
        fun decodeWithBinding(binding: ByteArray) =
            AdmissionProof.decode(Layout.encode(TAG, 1, fields.withField(CHANNEL_BINDING_FIELD, binding)) + signature)
        assertAll(
            { assertEquals(0, fields[CHANNEL_BINDING_FIELD].size, "precondition: the signer wrote it empty") },
            { assertNull(decodeWithBinding(byteArrayOf(0)), "one byte of channel binding") },
            { assertNull(decodeWithBinding(ByteArray(32)), "a 32-byte exporter value") },
            { assertNotNull(decodeWithBinding(byteArrayOf()), "the control: empty decodes") },
        )
    }

    @Test
    fun nonceOfFifteenBytesIsRefusedAtConstruction() = runTest {
        val joiner = DeviceKey.generate()
        // assertFailsWith is inline, so a suspending sign runs inside it; assertAll's arms cannot suspend.
        assertFailsWith<IllegalArgumentException>("15-byte host nonce") { sign(joiner, hostNonce = ByteArray(15)) }
        assertFailsWith<IllegalArgumentException>("17-byte host nonce") { sign(joiner, hostNonce = ByteArray(17)) }
        assertFailsWith<IllegalArgumentException>("15-byte host nonce at construction") {
            AdmissionProof(ROOM, GENERATOR_KEY, HOST_PEER, JOINER_PEER, DEVICE, ByteArray(15), ByteArray(0))
        }
        assertTrue(sign(joiner, hostNonce = ByteArray(16)).isSignedBy(joiner.id), "the control: 16 bytes")
    }

    @Test
    fun decodeRefusesMalformedFieldsWithoutThrowing() = runTest {
        val proof = sign(FixedDeviceKey(GENERATOR_KEY, PLACEHOLDER_SIGNATURE))
        val (layout, signature) = assertNotNull(Layout.split(proof.wire))
        val fields = assertNotNull(Layout.decode(layout, TAG, 1, FIELD_COUNT))
        fun decodeWith(index: Int, field: ByteArray) = AdmissionProof.decode(Layout.encode(TAG, 1, fields.withField(index, field)) + signature)
        assertAll(
            { assertNull(decodeWith(HOST_NONCE_FIELD, ByteArray(15)), "a 15-byte host nonce") },
            { assertNull(decodeWith(HOST_KEY_FIELD, fields[HOST_KEY_FIELD].copyOf(34)), "a 34-byte host key") },
            { assertNull(decodeWith(HOST_KEY_FIELD, byteArrayOf()), "an empty host key") },
            { assertNull(decodeWith(DEVICE_ID_FIELD, byteArrayOf(0xc3.toByte())), "a device id that is not UTF-8") },
            { assertNull(AdmissionProof.decode(byteArrayOf()), "no bytes") },
            { assertNull(decodeWith(DEVICE_ID_FIELD, "c080".hexToByteArray()), "an overlong NUL, C0 80") },
            { assertNull(decodeWith(ROOM_FIELD, "eda080".hexToByteArray()), "an encoded surrogate, ED A0 80") },
            { assertNotNull(decodeWith(DEVICE_ID_FIELD, fields[DEVICE_ID_FIELD]), "the control: the unaltered fields") },
        )
    }

    @Test
    fun anUnpairedSurrogateIsRefusedAtSigning() = runTest {
        val joiner = FixedDeviceKey(GENERATOR_KEY, PLACEHOLDER_SIGNATURE)
        // A UTF-8 encoder that is not strict writes a replacement here instead (`?` on the JVM,
        // EF BF BD on wasmJs), so the proof would sign a value other than the one it was given.
        assertFailsWith<IllegalArgumentException>("a device id ending in a high surrogate") {
            sign(joiner, deviceId = "d\uD800")
        }
        assertFailsWith<IllegalArgumentException>("a room id starting with a low surrogate") {
            AdmissionProof.sign(joiner, "\uDC00r", GENERATOR_KEY, HOST_PEER, JOINER_PEER, DEVICE, HOST_NONCE)
        }
        // The control: a device id with characters of three and four UTF-8 bytes signs, and round-trips.
        val device = "端末\uD83D\uDCF1"
        val proof = sign(joiner, deviceId = device)
        val fields = assertNotNull(Layout.decode(assertNotNull(Layout.split(proof.wire)).first, TAG, 1, FIELD_COUNT))
        assertAll(
            // 端 E7 AB AF, 末 E6 9C AB, U+1F4F1 F0 9F 93 B1: written by hand, not by the encoder.
            { assertEquals("e7abafe69cabf09f93b1", fields[DEVICE_ID_FIELD].toHexString(), "the device id's UTF-8") },
            { assertEquals(device, proof.deviceId, "the signed proof carries the value it was given") },
            { assertEquals(device, AdmissionProof.decode(proof.wire)?.deviceId, "and so does its decoded copy") },
        )
    }

    @Test
    fun aStatementIsItsWireBytes() = runTest {
        val joiner = FixedDeviceKey(GENERATOR_KEY, PLACEHOLDER_SIGNATURE)
        val one = sign(joiner)
        one.wire[0] = 0x7f
        one.hostNonce[0] = 0x7f
        val same = sign(joiner)
        val different = sign(joiner, deviceId = "other")
        assertAll(
            { assertEquals(one, same, "equal by wire bytes") },
            { assertEquals(one.hashCode(), same.hashCode()) },
            { assertNotEquals(one, different) },
            { assertEquals(0x00, one.wire[0].toInt(), "the wire is a copy: writing to it changes nothing") },
            { assertEquals(0x20, one.hostNonce[0].toInt(), "and so is the nonce") },
        )
    }

    private suspend fun sign(
        joiner: DeviceKey,
        deviceId: String? = DEVICE,
        hostNonce: ByteArray = HOST_NONCE,
    ): AdmissionProof = AdmissionProof.sign(joiner, ROOM, GENERATOR_KEY, HOST_PEER, JOINER_PEER, deviceId, hostNonce)

    private companion object {
        const val TAG = "kuilt/admit-proof"
        const val FIELD_COUNT = 7
        const val ROOM = "room"
        const val HOST_PEER = "h"
        const val JOINER_PEER = "j"
        const val DEVICE = "dev"
        val HOST_NONCE = countingBytes(0x20, 16)

        const val ROOM_FIELD = 0
        const val HOST_KEY_FIELD = 1
        const val DEVICE_ID_FIELD = 4
        const val HOST_NONCE_FIELD = 5
        const val CHANNEL_BINDING_FIELD = 6
    }
}
