package us.tractat.kuilt.identity

import com.ionspin.kotlin.bignum.integer.BigInteger
import dev.whyoleg.cryptography.algorithms.EC
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import us.tractat.kuilt.test.assertAll
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A `did:key` names a P-256 public key: `did:key:z` + base58btc(`0x80 0x24` ‖ 33-byte compressed
 * point). Syntax is checked when the value is built; [DidKey.parse] and [DidKey.verify] also check
 * that the point is really on the curve, because the JDK provider's decoder does not (#3201).
 */
@OptIn(ExperimentalSerializationApi::class)
class DidKeyTest {
    @Test
    fun isOnCurveMatchesKnownPoints() {
        val xEqualsP = P256.P.toFixedBytes()
        // p + 5, not the plan's p + 1: 1 has no point on P-256, so p + 1 would be refused by the square
        // test even with the x < p arm deleted, and could not show that arm does anything.
        val xEqualsPPlus5 = (P256.P + BigInteger(5)).toFixedBytes()
        assertAll(
            { assertTrue(isOnCurve(EVEN_COMPRESSED.hexToByteArray()), "G0's even-y point (0x02)") },
            { assertTrue(isOnCurve(ODD_COMPRESSED.hexToByteArray()), "G0's odd-y point (0x03)") },
            { assertTrue(isOnCurve(GENERATOR_COMPRESSED.hexToByteArray()), "the generator's x") },
            // Preconditions: x mod p is a valid x in both arms below, so only `x < p` can refuse them.
            { assertTrue(P256.hasPointAt(BigInteger.ZERO), "precondition: p mod p = 0 has a point") },
            { assertTrue(P256.hasPointAt(BigInteger(5)), "precondition: (p + 5) mod p = 5 has a point") },
            { assertFalse(isOnCurve(byteArrayOf(0x02) + xEqualsP), "x = p is refused by x < p") },
            { assertFalse(isOnCurve(byteArrayOf(0x02) + xEqualsPPlus5), "x = p + 5 is refused by x < p") },
            // The other arm: 1 < p, so only the square test can refuse it.
            { assertFalse(P256.hasPointAt(BigInteger.ONE), "x = 1 has no point on P-256") },
            { assertFalse(isOnCurve(OFF_CURVE_COMPRESSED.hexToByteArray()), "x = 1 is refused") },
            { assertFalse(isOnCurve(GENERATOR_COMPRESSED.hexToByteArray().copyOf(32)), "32 bytes is refused") },
            {
                val uncompressedLead = GENERATOR_COMPRESSED.hexToByteArray().also { it[0] = 0x04 }
                assertFalse(isOnCurve(uncompressedLead), "a 0x04 lead byte is refused")
            },
        )
    }

    @Test
    fun w3cVectorsRoundTrip() = runTest {
        val decoder = algorithms().ecdsa.publicKeyDecoder(EC.Curve.P256)
        val rows = W3C_P256_VECTORS.map { vector ->
            val key = DidKey.parse(vector.did)
            val x = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(vector.jwkX)
            val y = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(vector.jwkY)
            val decompressed = decoder.decodeFromByteArray(EC.PublicKey.Format.RAW.Compressed, key.compressedPoint())
                .encodeToByteArray(EC.PublicKey.Format.RAW)
            Triple(vector, key, Pair(x + y, decompressed))
        }
        assertAll(
            { assertEquals(2, rows.size, "both W3C P-256 vectors ran") },
            *rows.flatMap { (vector, key, xy) ->
                listOf(
                    { assertEquals(vector.did, key.value, "parse keeps the value") },
                    { assertTrue(key.value.startsWith("did:key:zDn"), "${vector.did} starts did:key:zDn") },
                    { assertEquals(32, xy.first.size / 2, "JWK x and y are 32 bytes each") },
                    {
                        assertEquals(
                            "04" + xy.first.toHexString(),
                            xy.second.toHexString(),
                            "decompressing ${vector.did} through the provider gives the JWK x‖y",
                        )
                    },
                    { assertTrue(DidKey.ofCompressedPoint(key.compressedPoint()) == key, "re-encoding gives the same name") },
                )
            }.toTypedArray(),
        )
    }

    @Test
    fun syntaxRejects() = runTest {
        val generator = GENERATOR_COMPRESSED.hexToByteArray()
        val p256 = byteArrayOf(0x80.toByte(), 0x24)
        val ed25519 = byteArrayOf(0xed.toByte(), 0x01)
        val good = didOf(p256 + generator)
        val parsedShort = parseRefusal("did:key:zDn")
        assertAll(
            // Positive control: the same construction with every part right is accepted, so each
            // refusal below is the one part that differs.
            { assertEquals(good, DidKey(good).value, "precondition: the well-formed name is accepted") },
            { assertFailsWith<IllegalArgumentException>("wrong prefix") { DidKey("did:web:" + good.removePrefix("did:key:")) } },
            { assertFailsWith<IllegalArgumentException>("not multibase z") { DidKey("did:key:" + good.removePrefix("did:key:z")) } },
            { assertFailsWith<IllegalArgumentException>("non-base58 character") { DidKey(good.dropLast(1) + "0") } },
            { assertFailsWith<IllegalArgumentException>("Ed25519 multicodec") { DidKey(didOf(ed25519 + generator)) } },
            { assertFailsWith<IllegalArgumentException>("32 point bytes") { DidKey(didOf(p256 + generator.copyOf(32))) } },
            { assertFailsWith<IllegalArgumentException>("34 point bytes") { DidKey(didOf(p256 + generator + 0x00)) } },
            {
                val lead04 = generator.copyOf().also { it[0] = 0x04 }
                assertFailsWith<IllegalArgumentException>("leading 0x04") { DidKey(didOf(p256 + lead04)) }
            },
            { assertFailsWith<IllegalArgumentException>("empty") { DidKey("") } },
            { assertTrue(parsedShort is IllegalArgumentException, "parse checks syntax too: $parsedShort") },
        )
    }

    @Test
    fun offCurvePointIsRejectedByParseAndVerifiesNothing() = runTest {
        val compressed = OFF_CURVE_COMPRESSED.hexToByteArray()
        val did = didOf(byteArrayOf(0x80.toByte(), 0x24) + compressed)
        val provider = platformProvider().name
        val ecdsa = algorithms().ecdsa
        // The rig's control: does this target's provider decode a point that is not on the curve?
        val rawDecode = try {
            ecdsa.publicKeyDecoder(EC.Curve.P256).decodeFromByteArray(EC.PublicKey.Format.RAW.Compressed, compressed)
            "accepted"
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            "refused: ${e::class.simpleName}"
        } catch (e: Error) {
            throw e
        } catch (e: Throwable) {
            "refused: ${e::class.simpleName}"
        }
        println("off-curve x = 1: provider=$provider rawDecode=$rawDecode")
        val fromTheWire = Cbor.decodeFromByteArray<DidKey>(Cbor.encodeToByteArray(String.serializer(), did))
        val verdict = fromTheWire.verify("anything".encodeToByteArray(), ByteArray(SIGNATURE_SIZE) { 1 })
        val parsed = parseRefusal(did)
        assertAll(
            // On the JDK the decoder accepts it: the defect this check exists for. Elsewhere, recorded above.
            { if (provider == "JDK") assertEquals("accepted", rawDecode, "control: the JDK decodes an off-curve x") },
            { assertEquals(did, DidKey(did).value, "precondition: the name is well-formed, so parse's refusal is the curve check") },
            { assertTrue(parsed is IllegalArgumentException, "parse refuses an off-curve point: $parsed") },
            { assertEquals(did, fromTheWire.value, "the serializer checks syntax only") },
            { assertFalse(verdict, "an off-curve key verifies nothing") },
        )
    }

    @Test
    fun verifyIsFalseOnABadSignatureLength() = runTest {
        val key = DidKey.parse(W3C_P256_VECTORS.first().did)
        val verdicts = listOf(0, SIGNATURE_SIZE - 1, SIGNATURE_SIZE + 1, SIGNATURE_SIZE).map { size ->
            size to key.verify(byteArrayOf(1), ByteArray(size) { 1 })
        }
        assertAll(*verdicts.map { (size, verdict) -> { assertFalse(verdict, "a $size-byte signature that signs nothing") } }.toTypedArray())
    }

    @Test
    fun serializerRoundTripsAsAString() {
        val key = DidKey(W3C_P256_VECTORS.first().did)
        val bytes = Cbor.encodeToByteArray(key)
        assertAll(
            { assertEquals(key.value, Cbor.decodeFromByteArray(String.serializer(), bytes), "a string on the wire") },
            { assertEquals(key, Cbor.decodeFromByteArray<DidKey>(bytes), "round trip") },
            {
                assertFailsWith<IllegalArgumentException>("a malformed string is refused on decode") {
                    Cbor.decodeFromByteArray<DidKey>(Cbor.encodeToByteArray(String.serializer(), "did:key:nope"))
                }
            },
        )
    }

    @Test
    fun compressedPointIsACopy() {
        val key = DidKey(W3C_P256_VECTORS.first().did)
        val first = key.compressedPoint()
        first.fill(0)
        assertAll(
            { assertEquals(COMPRESSED_SIZE, key.compressedPoint().size, "33 bytes") },
            { assertContentEquals(W3C_FIRST_COMPRESSED.hexToByteArray(), key.compressedPoint(), "unchanged by a caller's write") },
        )
    }

    /** What [DidKey.parse] threw, or `null` when it accepted. */
    private suspend fun parseRefusal(value: String): Throwable? = try {
        DidKey.parse(value)
        null
    } catch (e: IllegalArgumentException) {
        e
    }

    private fun didOf(multicodecAndPoint: ByteArray): String = "did:key:z" + Base58.encode(multicodecAndPoint)

    private fun BigInteger.toFixedBytes(): ByteArray {
        val magnitude = toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        check(magnitude.size <= 32) { "does not fit in 32 bytes" }
        return ByteArray(32 - magnitude.size) + magnitude
    }

    private class W3cVector(val did: String, val jwkX: String, val jwkY: String)

    private companion object {
        const val COMPRESSED_SIZE = 33
        const val SIGNATURE_SIZE = 64

        // The same fixed points CompressedPointProbeTest decodes on every target.
        const val EVEN_COMPRESSED = "02bb5e9f71cd43e4452723479973d0326f901ed963fee59f419bba6cd8649ffb17"
        const val ODD_COMPRESSED = "0339a9e222559fa1d3da385b00368b9d29258895752e83e3f9d34dfd29d957e296"

        // SEC 2 v2.0 § 2.4.2, secp256r1's base point G.
        const val GENERATOR_COMPRESSED = "026b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296"

        // x = 1, which has no point on P-256 (x³ − 3x + b is not a square mod p). Measured by #3201.
        const val OFF_CURVE_COMPRESSED = "020000000000000000000000000000000000000000000000000000000000000001"

        // The W3C CCG did:key test vectors, test-vectors/nist-curves.json in
        // https://github.com/w3c-ccg/did-method-key at commit 1b4f72d577faef66d4ecb0103ce25472ed353f3a
        // (the file was folded into the spec's index.html by the next commit; the spec still shows
        // the first DID as its P-256 example). JsonWebKey2020 entries: the DID and its public JWK x, y.
        val W3C_P256_VECTORS = listOf(
            W3cVector(
                did = "did:key:zDnaerx9CtbPJ1q36T5Ln5wYt3MQYeGRG5ehnPAmxcf5mDZpv",
                jwkX = "igrFmi0whuihKnj9R3Om1SoMph72wUGeFaBbzG2vzns",
                jwkY = "efsX5b10x8yjyrj4ny3pGfLcY7Xby1KzgqOdqnsrJIM",
            ),
            W3cVector(
                did = "did:key:zDnaerDaTF5BXEavCrfRZEk316dpbLsfPDZ3WJ5hRTPFU2169",
                jwkX = "fyNYMN0976ci7xqiSdag3buk-ZCwgXU4kz9XNkBlNUI",
                jwkY = "hW2ojTNfH7Jbi8--CJUo3OCbH3y5n91g-IMA9MLMbTU",
            ),
        )

        // The first vector's compressed point, from its JWK: y is odd, so the lead byte is 0x03.
        const val W3C_FIRST_COMPRESSED = "038a0ac59a2d3086e8a12a78fd4773a6d52a0ca61ef6c1419e15a05bcc6dafce7b"
    }
}

/** base58btc against an independent construction: the IETF draft's examples, leading zeros included. */
class Base58Test {
    @Test
    fun matchesTheDraftExamples() {
        assertAll(
            { assertEquals("2NEpo7TZRRrLZSi2U", Base58.encode("Hello World!".encodeToByteArray())) },
            { assertEquals("11233QC4", Base58.encode("0000287fb4cd".hexToByteArray())) },
            { assertEquals("", Base58.encode(ByteArray(0))) },
            { assertEquals("1", Base58.encode(ByteArray(1))) },
            { assertContentEquals("0000287fb4cd".hexToByteArray(), Base58.decode("11233QC4", maxBytes = 64)) },
            { assertContentEquals("Hello World!".encodeToByteArray(), Base58.decode("2NEpo7TZRRrLZSi2U", maxBytes = 64)) },
        )
    }

    @Test
    fun decodeRefuses() {
        assertAll(
            { assertEquals(null, Base58.decode("0", maxBytes = 64), "0 is not in the alphabet") },
            { assertEquals(null, Base58.decode("I", maxBytes = 64), "I is not in the alphabet") },
            { assertEquals(null, Base58.decode("l", maxBytes = 64), "l is not in the alphabet") },
            { assertEquals(null, Base58.decode("O", maxBytes = 64), "O is not in the alphabet") },
            { assertEquals(null, Base58.decode("2NEpo7TZRRrLZSi2U", maxBytes = 11), "longer than maxBytes") },
            { assertEquals(12, Base58.decode("2NEpo7TZRRrLZSi2U", maxBytes = 12)?.size, "exactly maxBytes") },
        )
    }

    @Test
    fun roundTripsEveryLeadingZeroCount() {
        val rows = (0..4).flatMap { zeros ->
            listOf(byteArrayOf(), byteArrayOf(1), byteArrayOf(0xff.toByte(), 0x00, 0x7f)).map { ByteArray(zeros) + it }
        }
        assertAll(*rows.map { bytes -> { assertContentEquals(bytes, Base58.decode(Base58.encode(bytes), maxBytes = 64), bytes.toHexString()) } }.toTypedArray())
    }
}
