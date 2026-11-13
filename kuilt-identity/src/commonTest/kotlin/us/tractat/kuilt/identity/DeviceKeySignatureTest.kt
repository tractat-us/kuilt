package us.tractat.kuilt.identity

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A device's key signs, and every platform reads what any platform signed.
 *
 * ECDSA signatures are randomised, so a vector can only be checked one way: a fixed public key,
 * message and signature, verified. [wycheproofSignaturesVerifyOnEveryTarget] does that against an
 * independent source on every target, so each platform's verifier is held to the standard rather
 * than to another platform. [signThenVerifyHoldsPerTarget] then shows each platform's signer
 * produces what its verifier accepts. Together they mean every platform signs standard `r ‖ s`
 * signatures: a verifier pinned to the standard accepts nothing else.
 */
class DeviceKeySignatureTest {
    @Test
    fun wycheproofSignaturesVerifyOnEveryTarget() = runTest {
        val valid = WYCHEPROOF.filter { it.valid }
        val rows = WYCHEPROOF.mapIndexed { index, vector ->
            val key = DidKey.parse(didKeyOf(vector.uncompressedKey))
            val message = vector.message.hexToByteArray()
            val signature = vector.signature.hexToByteArray()
            // Wrong key: the next vector whose key differs, so the control is never the same key twice.
            val other = WYCHEPROOF.drop(index + 1).plus(WYCHEPROOF.take(index)).first { it.uncompressedKey != vector.uncompressedKey }
            VectorRow(
                vector = vector,
                verdict = key.verify(message, signature),
                flippedSignature = key.verify(message, signature.flipLowBit(signature.size - 1)),
                flippedMessage = key.verify(message.flipLowBit(0), signature),
                wrongKey = DidKey.parse(didKeyOf(other.uncompressedKey)).verify(message, signature),
            )
        }
        assertAll(
            { assertEquals(6, valid.size, "precondition: six valid vectors") },
            { assertEquals(4, WYCHEPROOF.size - valid.size, "precondition: four invalid vectors") },
            { assertEquals(5, valid.map { it.uncompressedKey }.toSet().size, "precondition: the valid vectors span five keys") },
            {
                assertTrue(
                    WYCHEPROOF.all { it.signature.length == 2 * SIGNATURE_SIZE && it.message.isNotEmpty() },
                    "precondition: every vector is a 64-byte r‖s over a non-empty message",
                )
            },
            *rows.flatMap { row ->
                val tc = "Wycheproof tcId ${row.vector.tcId}"
                if (row.vector.valid) {
                    listOf(
                        { assertTrue(row.verdict, "$tc verifies") },
                        { assertFalse(row.flippedSignature, "$tc with one flipped signature bit") },
                        { assertFalse(row.flippedMessage, "$tc with one flipped message bit") },
                        { assertFalse(row.wrongKey, "$tc under another key") },
                    )
                } else {
                    listOf({ assertFalse(row.verdict, "$tc is refused") })
                }
            }.toTypedArray(),
        )
    }

    @Test
    fun signThenVerifyHoldsPerTarget() = runTest {
        val messages = listOf(ByteArray(0), byteArrayOf(0x2a), ByteArray(4096) { it.toByte() })
        val keys = List(KEYS) { DeviceKey.generate() }
        val rows = keys.mapIndexed { index, key ->
            val other = keys[(index + 1) % keys.size].id
            messages.map { message ->
                val signature = key.sign(message)
                SignRow(
                    size = signature.size,
                    verifies = key.id.verify(message, signature),
                    underOther = other.verify(message, signature),
                    parsed = DidKey.parse(key.id.value) == key.id,
                )
            }
        }.flatten()
        assertAll(
            { assertEquals(KEYS, keys.map { it.id }.toSet().size, "every generated key has its own name") },
            { assertEquals(KEYS * messages.size, rows.size, "precondition: every key signed every message") },
            { assertTrue(keys.all { it.id.value.startsWith("did:key:zDn") }, "every name is a P-256 did:key") },
            { assertTrue(rows.all { it.parsed }, "every name parses back to itself") },
            { assertEquals(listOf(SIGNATURE_SIZE), rows.map { it.size }.distinct(), "every signature is 64 bytes") },
            { assertEquals(0, rows.count { !it.verifies }, "every signature verifies under its key's name") },
            { assertEquals(0, rows.count { it.underOther }, "no signature verifies under another key's name") },
        )
    }

    @Test
    fun pkcs8RoundTripKeepsTheId() = runTest {
        val original = SoftwareDeviceKey.generate()
        val der = original.encodePkcs8()
        val decoded = SoftwareDeviceKey.decodePkcs8(der)
        val message = "the same key, read back".encodeToByteArray()
        // The decoded key signs, and the ORIGINAL name accepts it: the private half came back, not
        // just a public key that happens to encode the same.
        val signature = decoded.sign(message)
        val other = SoftwareDeviceKey.generate()
        assertAll(
            { assertTrue(der.isNotEmpty(), "precondition: PKCS#8 bytes") },
            { assertFalse(original.id == other.id, "precondition: two generated keys differ, so equal ids below mean something") },
            { assertEquals(original.id, decoded.id, "the decoded key has the original's name") },
            { assertTrue(original.id.verify(message, signature), "the decoded key's signature verifies under the original name") },
            { assertFalse(other.id.verify(message, signature), "and not under another key's name") },
            { assertEquals(original.id, SoftwareDeviceKey.decodePkcs8(decoded.encodePkcs8()).id, "a second round trip keeps it too") },
            { assertFailsWith<IllegalArgumentException>("garbage") { SoftwareDeviceKey.decodePkcs8(byteArrayOf(1, 2, 3)) } },
            { assertFailsWith<IllegalArgumentException>("empty") { SoftwareDeviceKey.decodePkcs8(ByteArray(0)) } },
            { assertFailsWith<IllegalArgumentException>("truncated") { SoftwareDeviceKey.decodePkcs8(der.copyOf(der.size / 2)) } },
        )
    }

    private class VectorRow(
        val vector: WycheproofVector,
        val verdict: Boolean,
        val flippedSignature: Boolean,
        val flippedMessage: Boolean,
        val wrongKey: Boolean,
    )

    private class SignRow(val size: Int, val verifies: Boolean, val underOther: Boolean, val parsed: Boolean)

    /**
     * One Wycheproof ECDSA-verify case, its fields copied verbatim from the source: the group's
     * `publicKey.uncompressed`, and the case's `msg`, `sig` (IEEE P1363 `r ‖ s`) and `result`.
     */
    private class WycheproofVector(
        val tcId: Int,
        val uncompressedKey: String,
        val message: String,
        val signature: String,
        val valid: Boolean,
    )

    private companion object {
        const val SIGNATURE_SIZE = 64
        const val KEYS = 20

        /** The `did:key` for a Wycheproof `0x04 ‖ x ‖ y` key: the lead byte is `0x02`, or `0x03` when `y` is odd. */
        fun didKeyOf(uncompressedHex: String): String {
            val uncompressed = uncompressedHex.hexToByteArray()
            check(uncompressed.size == 65 && uncompressed[0] == 0x04.toByte()) { "not an uncompressed P-256 point" }
            val lead = if (uncompressed[64].toInt() and 1 == 1) 0x03 else 0x02
            val compressed = byteArrayOf(lead.toByte()) + uncompressed.copyOfRange(1, 33)
            return DidKey.ofCompressedPoint(compressed).value
        }

        fun ByteArray.flipLowBit(index: Int): ByteArray = copyOf().also { it[index] = (it[index].toInt() xor 0x01).toByte() }

        /*
         * Project Wycheproof, testvectors_v1/ecdsa_secp256r1_sha256_p1363_test.json at
         * https://github.com/C2SP/wycheproof commit 878e5366008753df2064d40c49f8e2f50f9c6af7
         * (ECDSA verify, P-256, SHA-256, IEEE P1363 signatures). Picked: two ordinary valid cases on
         * the file's first key, four valid arithmetic edge cases on four other keys, and four invalid
         * cases. Each was also checked with a separate pure-Python ECDSA verifier before pasting.
         */
        val WYCHEPROOF = listOf(
            WycheproofVector(
                tcId = 1, // signature malleability; valid
                uncompressedKey = "042927b10512bae3eddcfe467828128bad2903269919f7086069c8c4df6c732838c7787964eaac00e5921fb1498a60f4606766b3d9685001558d1a974e7341513e",
                message = "313233343030",
                signature = "2ba3a8be6b94d5ec80a6d9d1190a436effe50d85a1eee859b8cc6af9bd5c2e184cd60b855d442f5b3c7b11eb6c4e0ae7525fe710fab9aa7c77a67f79e6fadd76",
                valid = true,
            ),
            WycheproofVector(
                tcId = 61, // special case hash; valid
                uncompressedKey = "042927b10512bae3eddcfe467828128bad2903269919f7086069c8c4df6c732838c7787964eaac00e5921fb1498a60f4606766b3d9685001558d1a974e7341513e",
                message = "343236343739373234",
                signature = "16aea964a2f6506d6f78c81c91fc7e8bded7d397738448de1e19a0ec580bf266252cd762130c6667cfe8b7bc47d27d78391e8e80c578d1cd38c3ff033be928e9",
                valid = true,
            ),
            WycheproofVector(
                tcId = 115, // k*G has a large x-coordinate; valid
                uncompressedKey = "040ad99500288d466940031d72a9f5445a4d43784640855bf0a69874d2de5fe103c5011e6ef2c42dcd50d5d3d29f99ae6eba2c80c9244f4c5422f0979ff0c3ba5e",
                message = "313233343030",
                signature = "000000000000000000000000000000004319055358e8617b0c46353d039cdaabffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc63254e",
                valid = true,
            ),
            WycheproofVector(
                tcId = 117, // r,s are large; valid
                uncompressedKey = "04ab05fd9d0de26b9ce6f4819652d9fc69193d0aa398f0fba8013e09c58220455419235271228c786759095d12b75af0692dd4103f19f6a8c32f49435a1e9b8d45",
                message = "313233343030",
                signature = "ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc63254fffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc63254e",
                valid = true,
            ),
            WycheproofVector(
                tcId = 120, // small r and s; valid
                uncompressedKey = "04a71af64de5126a4a4e02b7922d66ce9415ce88a4c9d25514d91082c8725ac9575d47723c8fbe580bb369fec9c2665d8e30a435b9932645482e7c9f11e872296b",
                message = "313233343030",
                signature = "00000000000000000000000000000000000000000000000000000000000000050000000000000000000000000000000000000000000000000000000000000001",
                valid = true,
            ),
            WycheproofVector(
                tcId = 152, // s == 1; valid
                uncompressedKey = "048aeb368a7027a4d64abdea37390c0c1d6a26f399e2d9734de1eb3d0e1937387405bd13834715e1dbae9b875cf07bd55e1b6691c7f7536aef3b19bf7a4adf576d",
                message = "313233343030",
                signature = "555555550000000055555555555555553ef7a8e48d07df81a693439654210c700000000000000000000000000000000000000000000000000000000000000001",
                valid = true,
            ),
            WycheproofVector(
                tcId = 4, // replaced r by n - r; invalid
                uncompressedKey = "042927b10512bae3eddcfe467828128bad2903269919f7086069c8c4df6c732838c7787964eaac00e5921fb1498a60f4606766b3d9685001558d1a974e7341513e",
                message = "313233343030",
                signature = "d45c5740946b2a147f59262ee6f5bc90bd01ed280528b62b3aed5fc93f06f739b329f479a2bbd0a5c384ee1493b1f5186a87139cac5df4087c134b49156847db",
                valid = false,
            ),
            WycheproofVector(
                tcId = 11, // Signature with special case values r=0 and s=0; invalid
                uncompressedKey = "042927b10512bae3eddcfe467828128bad2903269919f7086069c8c4df6c732838c7787964eaac00e5921fb1498a60f4606766b3d9685001558d1a974e7341513e",
                message = "313233343030",
                signature = "00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000",
                valid = false,
            ),
            WycheproofVector(
                tcId = 27, // Signature with special case values r=n and s=n; invalid
                uncompressedKey = "042927b10512bae3eddcfe467828128bad2903269919f7086069c8c4df6c732838c7787964eaac00e5921fb1498a60f4606766b3d9685001558d1a974e7341513e",
                message = "313233343030",
                signature = "ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551",
                valid = false,
            ),
            WycheproofVector(
                tcId = 47, // Signature with special case values r=p and s=1; invalid
                uncompressedKey = "042927b10512bae3eddcfe467828128bad2903269919f7086069c8c4df6c732838c7787964eaac00e5921fb1498a60f4606766b3d9685001558d1a974e7341513e",
                message = "313233343030",
                signature = "ffffffff00000001000000000000000000000000ffffffffffffffffffffffff0000000000000000000000000000000000000000000000000000000000000001",
                valid = false,
            ),
        )
    }
}
