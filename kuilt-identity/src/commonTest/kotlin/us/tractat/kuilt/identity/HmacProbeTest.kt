package us.tractat.kuilt.identity

import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * HMAC-SHA-256 through this platform's own provider, against RFC 4231's vectors: the probe #3776
 * starts with, run on every target, as slice 0's compressed-point probe was. `ProofNonces` keys its
 * MAC with a raw 32-byte secret, so the 32-byte-key row is the one it rests on; the other two are
 * the RFC's own short-key cases, so a provider that pads or truncates a key differently shows here.
 */
class HmacProbeTest {
    @Test
    fun hmacSha256MatchesRfc4231OnEveryTarget() = runTest {
        val hmac = platformProvider().get(HMAC)
        suspend fun mac(key: ByteArray, data: ByteArray): String =
            hmac.keyDecoder(SHA256).decodeFromByteArray(HMAC.Key.Format.RAW, key).signatureGenerator().generateSignature(data).toHexString()
        // RFC 4231 § 4.2, test case 1: a 20-byte key of 0x0b, "Hi There".
        val case1 = mac(ByteArray(20) { 0x0b }, "Hi There".encodeToByteArray())
        // RFC 4231 § 4.3, test case 2: the key "Jefe".
        val case2 = mac("Jefe".encodeToByteArray(), "what do ya want for nothing?".encodeToByteArray())
        // A 32-byte key, the width ProofNonces draws. 0x00..0x1f over "kuilt", cross-checked with
        // `printf kuilt | openssl dgst -sha256 -mac HMAC -macopt hexkey:000102…1f`.
        val case32 = mac(countingBytes(0, 32), "kuilt".encodeToByteArray())
        assertAll(
            { assertEquals("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7", case1, "RFC 4231 case 1") },
            { assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843", case2, "RFC 4231 case 2") },
            { assertEquals(KUILT_UNDER_COUNTING_KEY, case32, "a 32-byte raw key") },
        )
    }

    private companion object {
        const val KUILT_UNDER_COUNTING_KEY = "5a0d7296096a2ee974cbe5879e47bc035e3cdc0aa3a1ab9e20c77fd07a09d9c8"
    }
}
