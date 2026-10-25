package us.tractat.kuilt.seal

import dev.whyoleg.cryptography.algorithms.EC
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The provider must never compute a shared secret with a point that is not on P-256.
 *
 * `open` agrees a secret between the recipient's long-term private key and an ephemeral key read
 * off the wire, so the ephemeral key is attacker-chosen. Agreeing with an off-curve point is the
 * invalid-curve attack, which leaks the long-term key a few bits at a time. A tampered-box test
 * cannot see this: a tag failure gives the same NOT_AUTHENTIC either way. So this asks the provider
 * directly, through the module's own `ecdh()`, on every target.
 */
class OffCurvePointTest {
    @Test
    fun theProviderRefusesAgreementWithAnOffCurvePoint() = runTest {
        // x = 0, y = 7 — on no curve this module uses (P-256 has no point with x = 0 and y = 7).
        val offCurve = ByteArray(SealPublicKey.ENCODED_SIZE).also { it[0] = 0x04; it[64] = 7 }
        val outcome = try {
            val point = ecdh().publicKeyDecoder(EC.Curve.P256).decodeFromByteArray(EC.PublicKey.Format.RAW, offCurve)
            val mine = ecdh().keyPairGenerator(EC.Curve.P256).generateKey().privateKey
            mine.sharedSecretGenerator().generateSharedSecretToByteArray(point)
            "agreed a shared secret with an off-curve point"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "refused: ${e::class.simpleName}"
        }
        assertTrue(outcome.startsWith("refused"), outcome)
    }
}
