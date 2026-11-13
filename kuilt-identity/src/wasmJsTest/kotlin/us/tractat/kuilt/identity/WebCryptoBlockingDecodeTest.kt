package us.tractat.kuilt.identity

import dev.whyoleg.cryptography.algorithms.EC
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The evidence behind Decided 13 (planning R1): a `DidKey`'s constructor cannot decode its point,
 * because WebCrypto's decoders are async and a constructor cannot suspend. If the blocking decode
 * worked here, `init` could decode after all and the ruling would rest on nothing.
 *
 * It decodes the 65-byte uncompressed point, the form [decodeOnCurve] hands the provider. The
 * compressed form would make the precondition fail in WebKit, which refuses to import it (#3232).
 */
class WebCryptoBlockingDecodeTest {
    @Test
    fun blockingDecodeIsUnavailableOnWebCrypto() = runTest {
        val ecdsa = algorithms().ecdsa
        val uncompressed = ecdsa.keyPairGenerator(EC.Curve.P256).generateKey()
            .publicKey.encodeToByteArray(EC.PublicKey.Format.RAW)
        // Precondition: the bytes are a good point that this browser imports, so a throw below is
        // the blocking path refusing and not the input being bad.
        assertEquals(65, uncompressed.size)
        ecdsa.publicKeyDecoder(EC.Curve.P256).decodeFromByteArray(EC.PublicKey.Format.RAW, uncompressed)
        val refusal = assertFailsWith<IllegalStateException> {
            ecdsa.publicKeyDecoder(EC.Curve.P256).decodeFromByteArrayBlocking(EC.PublicKey.Format.RAW, uncompressed)
        }
        // The provider's own refusal of blocking calls, as cryptography-kotlin 0.6.0 words it, and
        // not some other IllegalStateException from inside the decode.
        val message = refusal.message.orEmpty()
        assertTrue("non-blocking" in message, "unexpected IllegalStateException: $message")
    }
}
