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
 * A device's signed proof to a server. Its golden vector is written out by hand, field by field, and
 * never derived from the encoder. Every refusal arm carries a control that is accepted, so each one
 * shows the check can fail rather than only that it passed.
 */
class DeviceProofTest {
    @Test
    fun layoutIsTheDocumentedBytes() = runTest {
        val device = FixedDeviceKey(GENERATOR_KEY, PLACEHOLDER_SIGNATURE)
        val proof = sign(device)
        val expected = (
            "0012" + "6b75696c742f6465766963652d70726f6f66" + // tag "kuilt/device-proof"
                "01" + // version
                "0021" + GENERATOR_COMPRESSED_HEX + // subject: its 33-byte compressed point
                "0009" + "68747470733a2f2f61" + // audience "https://a"
                "0002" + "2f62" + // resource "/b"
                "0005" + "3031323334" + // nonce, as served
                "0000" // channel binding: reserved, empty
            ).hexToByteArray()
        val split = assertNotNull(Layout.split(proof.wire), "the wire splits")
        // The negative control: the reserved field's length byte changed, and the vector no longer decodes.
        val mutated = (expected + PLACEHOLDER_SIGNATURE).withLowBitFlipped(expected.size - 1)
        assertAll(
            { assertEquals(expected.toHexString(), split.first.toHexString(), "layout bytes") },
            { assertContentEquals(PLACEHOLDER_SIGNATURE, split.second, "the signature follows the layout") },
            { assertEquals(expected.toHexString(), device.signed.single().toHexString(), "the signer was handed the layout itself") },
            {
                val decoded = assertNotNull(DeviceProof.decode(expected + PLACEHOLDER_SIGNATURE), "the vector decodes")
                assertEquals(proof, decoded, "and decodes to the statement that was signed")
            },
            { assertNull(DeviceProof.decode(mutated), "the mutated vector does not decode") },
        )
    }

    @Test
    fun signedDecodesAndVerifies() = runTest {
        val device = DeviceKey.generate()
        val proof = sign(device)
        val decoded = assertNotNull(DeviceProof.decode(proof.wire))
        val flipped = assertNotNull(DeviceProof.decode(proof.wire.withLowBitFlipped(proof.wire.size - 1)))
        val verdicts = listOf(proof.isSignedBySubject(), decoded.isSignedBySubject(), flipped.isSignedBySubject())
        assertAll(
            { assertEquals(device.id, decoded.subject) },
            { assertEquals(AUDIENCE, decoded.audience) },
            { assertEquals(RESOURCE, decoded.resource) },
            { assertContentEquals(NONCE, decoded.nonce) },
            { assertContentEquals(proof.wire, decoded.wire) },
            { assertTrue(verdicts[0], "the signed statement verifies") },
            { assertTrue(verdicts[1], "and so does its decoded copy") },
            // The negative control: one signature bit flipped, and the same check refuses it.
            { assertFalse(verdicts[2], "a flipped signature bit") },
        )
    }

    @Test
    fun aSignatureByAnotherKeyIsRefused() = runTest {
        val claimed = DeviceKey.generate()
        val signer = DeviceKey.generate()
        // Names `claimed` as its subject and signs with `signer`'s key.
        val forged = sign(Impostor(claimed.id, signer))
        val honest = sign(claimed)
        val forgedVerifies = forged.isSignedBySubject()
        val honestVerifies = honest.isSignedBySubject()
        assertAll(
            { assertEquals(claimed.id, forged.subject, "precondition: the forgery names the claimed key") },
            { assertFalse(forgedVerifies, "signed by another key than the subject it names") },
            { assertTrue(honestVerifies, "the control: signed by the subject itself") },
        )
    }

    @Test
    fun aSubstitutedSubjectIsRefused() = runTest {
        val device = DeviceKey.generate()
        val other = DeviceKey.generate()
        val proof = sign(device)
        val (layout, signature) = assertNotNull(Layout.split(proof.wire))
        val fields = assertNotNull(Layout.decode(layout, TAG, 1, FIELD_COUNT))
        // The device's signature kept, its subject swapped for another real key's.
        val swapped = Layout.encode(TAG, 1, fields.withField(SUBJECT_FIELD, StatementCodec.key(other.id))) + signature
        val decoded = assertNotNull(DeviceProof.decode(swapped), "the rig: the swapped proof still decodes")
        val swappedVerifies = decoded.isSignedBySubject()
        val originalVerifies = proof.isSignedBySubject()
        assertAll(
            { assertEquals(other.id, decoded.subject, "the rig fired: the proof now names the other key") },
            { assertFalse(swappedVerifies, "the substituted subject") },
            { assertTrue(originalVerifies, "the control: the proof as signed") },
        )
    }

    @Test
    fun tamperedFieldFailsVerification() = runTest {
        val device = DeviceKey.generate()
        val proof = sign(device)
        val (layout, signature) = assertNotNull(Layout.split(proof.wire))
        val fields = assertNotNull(Layout.decode(layout, TAG, 1, FIELD_COUNT))
        // Every field but the reserved channel binding, which is empty and has no byte to flip;
        // nonEmptyChannelBindingDoesNotDecode covers it. The subject's flip is in its last byte,
        // since its first is the parity byte and a flip there leaves 0x02/0x03 for another point.
        val tamperable = fields.indices.filter { it != CHANNEL_BINDING_FIELD }
        val rows = tamperable.map { index ->
            val at = if (index == SUBJECT_FIELD) fields[index].size - 1 else 0
            val tampered = Layout.encode(TAG, 1, fields.withField(index, fields[index].withLowBitFlipped(at))) + signature
            val decoded = DeviceProof.decode(tampered)
            Triple(index, decoded, decoded?.isSignedBySubject())
        }
        assertAll(
            { assertEquals(5, fields.size, "precondition: five fields") },
            { assertEquals(4, tamperable.size, "precondition: four of them carry bytes") },
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
    fun decodeRefusesAnotherTagOrVersion() = runTest {
        val proof = sign(FixedDeviceKey(GENERATOR_KEY, PLACEHOLDER_SIGNATURE))
        val (layout, signature) = assertNotNull(Layout.split(proof.wire))
        val fields = assertNotNull(Layout.decode(layout, TAG, 1, FIELD_COUNT))
        // Only the tag or the version differs: the proof's own five fields under each.
        fun under(tag: String, version: Int) = DeviceProof.decode(Layout.encode(tag, version, fields) + signature)
        assertAll(
            { assertNull(under("kuilt/admit-proof", 1), "the admission proof's tag") },
            { assertNull(under("kuilt/challenge", 1), "the challenge's tag") },
            { assertNull(under("kuilt/grant", 1), "the grant's tag") },
            { assertNull(under("kuilt/device-proofs", 1), "a tag with one character more") },
            { assertNull(under(TAG, 0), "version 0") },
            { assertNull(under(TAG, 2), "version 2") },
            { assertNotNull(under(TAG, 1), "the control: the same fields under the proof's own tag decode") },
        )
    }

    @Test
    fun nonEmptyChannelBindingDoesNotDecode() = runTest {
        val proof = sign(FixedDeviceKey(GENERATOR_KEY, PLACEHOLDER_SIGNATURE))
        val (layout, signature) = assertNotNull(Layout.split(proof.wire))
        val fields = assertNotNull(Layout.decode(layout, TAG, 1, FIELD_COUNT))
        fun decodeWithBinding(binding: ByteArray) =
            DeviceProof.decode(Layout.encode(TAG, 1, fields.withField(CHANNEL_BINDING_FIELD, binding)) + signature)
        assertAll(
            { assertEquals(0, fields[CHANNEL_BINDING_FIELD].size, "precondition: the signer wrote it empty") },
            { assertNull(decodeWithBinding(byteArrayOf(0)), "one byte of channel binding") },
            { assertNull(decodeWithBinding(ByteArray(32)), "a 32-byte exporter value") },
            { assertNotNull(decodeWithBinding(byteArrayOf()), "the control: empty decodes") },
        )
    }

    @Test
    fun decodeRefusesMalformedFieldsWithoutThrowing() = runTest {
        val proof = sign(FixedDeviceKey(GENERATOR_KEY, PLACEHOLDER_SIGNATURE))
        val (layout, signature) = assertNotNull(Layout.split(proof.wire))
        val fields = assertNotNull(Layout.decode(layout, TAG, 1, FIELD_COUNT))
        fun decodeWith(index: Int, field: ByteArray) = DeviceProof.decode(Layout.encode(TAG, 1, fields.withField(index, field)) + signature)
        val subject = fields[SUBJECT_FIELD]
        assertAll(
            { assertNull(decodeWith(SUBJECT_FIELD, subject.copyOf(32)), "a 32-byte subject") },
            { assertNull(decodeWith(SUBJECT_FIELD, subject.copyOf(34)), "a 34-byte subject") },
            { assertNull(decodeWith(SUBJECT_FIELD, byteArrayOf()), "an empty subject") },
            { assertNull(decodeWith(SUBJECT_FIELD, subject.copyOf().also { it[0] = 0x04 }), "a subject led by 0x04, not compressed") },
            { assertNull(decodeWith(AUDIENCE_FIELD, byteArrayOf(0xc3.toByte())), "an audience that is not UTF-8") },
            { assertNull(decodeWith(AUDIENCE_FIELD, "c080".hexToByteArray()), "an overlong NUL in the audience, C0 80") },
            { assertNull(decodeWith(RESOURCE_FIELD, "eda080".hexToByteArray()), "an encoded surrogate in the resource, ED A0 80") },
            { assertNull(decodeWith(NONCE_FIELD, byteArrayOf()), "an empty nonce") },
            { assertNull(DeviceProof.decode(Layout.encode(TAG, 1, fields.dropLast(1)) + signature), "four fields") },
            { assertNull(DeviceProof.decode(Layout.encode(TAG, 1, fields + byteArrayOf()) + signature), "six fields") },
            { assertNull(DeviceProof.decode(layout + byteArrayOf(0) + signature), "a trailing byte after the layout") },
            { assertNull(DeviceProof.decode(layout), "the layout without its signature") },
            { assertNull(DeviceProof.decode(byteArrayOf()), "no bytes") },
            { assertNotNull(decodeWith(NONCE_FIELD, fields[NONCE_FIELD]), "the control: the unaltered fields") },
        )
    }

    @Test
    fun noOtherStatementDecodesAsADeviceProofNorItAsThem() = runTest {
        val key = DeviceKey.generate()
        val proof = sign(key)
        val admission = AdmissionProof.sign(key, "room", GENERATOR_KEY, "h", "j", "dev", countingBytes(0x20, Challenge.NONCE_SIZE))
        val challenge = Challenge.sign(key, "room", "h", "j", countingBytes(0x10, Challenge.NONCE_SIZE), countingBytes(0x20, Challenge.NONCE_SIZE))
        val grant = Grant.issue(key, GENERATOR_KEY, GrantScope.AnyRoomOfIssuer, issuedAt = 0, notAfter = 1)
        // A signApp output, assembled the way its signed bytes are: app header, message, signature.
        val appMessage = proof.wire.copyOfRange(0, proof.wire.size - Layout.SIGNATURE_SIZE)
        val appWire = Layout.header("app/device-proof", 1) + appMessage + key.signApp("device-proof", appMessage)
        assertAll(
            { assertNull(DeviceProof.decode(admission.wire), "an admission proof is not a device proof") },
            { assertNull(DeviceProof.decode(challenge.wire), "a challenge is not a device proof") },
            { assertNull(DeviceProof.decode(grant.wire), "a grant is not a device proof") },
            { assertNull(DeviceProof.decode(appWire), "an app signature is not a device proof") },
            { assertNull(AdmissionProof.decode(proof.wire), "a device proof is not an admission proof") },
            { assertNull(Challenge.decode(proof.wire), "a device proof is not a challenge") },
            { assertNull(Grant.decode(proof.wire), "a device proof is not a grant") },
            // The third byte is what keeps an app signature apart from every statement: `a` against `k`.
            { assertEquals('k'.code.toByte(), proof.wire[2], "a device proof's third byte") },
            { assertEquals('a'.code.toByte(), appWire[2], "an app signature's third byte") },
            // The controls: each wire decodes as its own kind.
            { assertNotNull(DeviceProof.decode(proof.wire), "the control: a device proof decodes as one") },
            { assertNotNull(AdmissionProof.decode(admission.wire), "the control: the admission proof") },
            { assertNotNull(Challenge.decode(challenge.wire), "the control: the challenge") },
            { assertNotNull(Grant.decode(grant.wire), "the control: the grant") },
        )
    }

    @Test
    fun aPeerCannotObtainADeviceProofThroughSignApp() = runTest {
        val victim = DeviceKey.generate()
        // The layout of a proof the victim would sign for the peer's server: what the peer wants signed.
        val honest = sign(victim)
        val layout = honest.wire.copyOfRange(0, honest.wire.size - Layout.SIGNATURE_SIZE)
        val header = layout.copyOfRange(0, 2 + TAG.length + 1).decodeToString()
        // Contexts a peer might talk the app into, each trying to make the signed bytes open with kuilt's header.
        val attempts = listOf(
            "x" to layout,
            "bee/socket" to layout,
            header to layout.copyOfRange(header.length, layout.size),
            "\u0000\u0012$TAG" to layout.copyOfRange(header.length - 1, layout.size),
        )
        val rows = attempts.map { (context, message) ->
            val signature = victim.signApp(context, message)
            val forged = DeviceProof.decode(layout + signature)
            Triple(victim.id.verifyApp(context, message, signature), forged, forged?.isSignedBySubject())
        }
        val honestVerifies = honest.isSignedBySubject()
        assertAll(
            { assertTrue(honestVerifies, "precondition: a proof the victim really signed verifies") },
            *rows.mapIndexed { i, (appVerifies, forged, forgedVerifies) ->
                listOf<() -> Unit>(
                    { assertTrue(appVerifies, "attempt $i: the app signature itself is sound") },
                    { assertNotNull(forged, "attempt $i: precondition, the assembled bytes decode as a device proof") },
                    { assertEquals(false, forgedVerifies, "attempt $i: the assembled proof is not the victim's") },
                )
            }.flatten().toTypedArray(),
        )
    }

    @Test
    fun anEmptyNonceIsRefusedAtSigningAndConstruction() = runTest {
        val device = FixedDeviceKey(GENERATOR_KEY, PLACEHOLDER_SIGNATURE)
        // assertFailsWith is inline, so a suspending sign runs inside it; assertAll's arms cannot suspend.
        assertFailsWith<IllegalArgumentException>("an empty nonce at signing") { sign(device, nonce = ByteArray(0)) }
        assertFailsWith<IllegalArgumentException>("an empty nonce at construction") {
            DeviceProof(GENERATOR_KEY, AUDIENCE, RESOURCE, ByteArray(0), ByteArray(0))
        }
        assertEquals(1, sign(device, nonce = ByteArray(1)).nonce.size, "the control: one byte signs")
    }

    @Test
    fun anUnpairedSurrogateIsRefusedAtSigning() = runTest {
        val device = FixedDeviceKey(GENERATOR_KEY, PLACEHOLDER_SIGNATURE)
        assertFailsWith<IllegalArgumentException>("an audience ending in a high surrogate") {
            DeviceProof.sign(device, "https://a\uD800", RESOURCE, NONCE)
        }
        assertFailsWith<IllegalArgumentException>("a resource starting with a low surrogate") {
            DeviceProof.sign(device, AUDIENCE, "\uDC00/b", NONCE)
        }
        // The control: a resource with characters of three and four UTF-8 bytes signs, and round-trips.
        val resource = "/端📱"
        val proof = DeviceProof.sign(device, AUDIENCE, resource, NONCE)
        val fields = assertNotNull(Layout.decode(assertNotNull(Layout.split(proof.wire)).first, TAG, 1, FIELD_COUNT))
        assertAll(
            // / 2F, 端 E7 AB AF, U+1F4F1 F0 9F 93 B1: written by hand, not by the encoder.
            { assertEquals("2fe7abaff09f93b1", fields[RESOURCE_FIELD].toHexString(), "the resource's UTF-8") },
            { assertEquals(resource, proof.resource, "the signed proof carries the value it was given") },
            { assertEquals(resource, DeviceProof.decode(proof.wire)?.resource, "and so does its decoded copy") },
        )
    }

    @Test
    fun aStatementIsItsWireBytes() = runTest {
        val device = FixedDeviceKey(GENERATOR_KEY, PLACEHOLDER_SIGNATURE)
        val one = sign(device)
        one.wire[0] = 0x7f
        one.nonce[0] = 0x7f
        val same = sign(device)
        val different = sign(device, nonce = countingBytes(0x40, 5))
        val source = same.wire
        val decoded = assertNotNull(DeviceProof.decode(source))
        source[source.size - 1] = 0x00
        assertAll(
            { assertEquals(one, same, "equal by wire bytes") },
            { assertEquals(one.hashCode(), same.hashCode()) },
            { assertNotEquals(one, different, "another nonce is another proof") },
            { assertEquals(0x00, one.wire[0].toInt(), "the wire is a copy: writing to it changes nothing") },
            { assertEquals(0x30, one.nonce[0].toInt(), "and so is the nonce") },
            { assertEquals(same, decoded, "decode keeps its own copy of the bytes it was handed") },
        )
    }

    private suspend fun sign(device: DeviceKey, nonce: ByteArray = NONCE): DeviceProof = DeviceProof.sign(device, AUDIENCE, RESOURCE, nonce)

    private companion object {
        const val TAG = "kuilt/device-proof"
        const val FIELD_COUNT = 5
        const val AUDIENCE = "https://a"
        const val RESOURCE = "/b"
        val NONCE = countingBytes(0x30, 5)

        const val SUBJECT_FIELD = 0
        const val AUDIENCE_FIELD = 1
        const val RESOURCE_FIELD = 2
        const val NONCE_FIELD = 3
        const val CHANNEL_BINDING_FIELD = 4
    }
}
