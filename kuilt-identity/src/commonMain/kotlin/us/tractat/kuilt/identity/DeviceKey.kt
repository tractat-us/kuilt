package us.tractat.kuilt.identity

import dev.whyoleg.cryptography.algorithms.EC
import dev.whyoleg.cryptography.algorithms.ECDSA
import dev.whyoleg.cryptography.algorithms.SHA256

/**
 * One device's own key: it signs, and its [id] is the public name other devices check those
 * signatures against.
 *
 * A device proves it is the same device as before by signing something fresh with this key. Nobody
 * else can make that signature, and the key never needs to leave the device to make it, so the
 * private half has no accessor here. A key kept in a hardware or browser store that will not hand
 * its bytes out at all is still a `DeviceKey`: all it has to do is sign.
 *
 * [generate] makes a software key from the platform's own random source. It lives only as long as
 * the object does; a key that outlives the process comes from a key store.
 *
 * @sample us.tractat.kuilt.identity.sampleDeviceKeySigns
 */
public interface DeviceKey {
    /** This key's public name. [DidKey.verify] accepts exactly the signatures [sign] makes. */
    public val id: DidKey

    /**
     * Signs [message]: ECDSA on P-256 over SHA-256 of [message], as the 64-byte raw `r ‖ s` form.
     * Each call draws a fresh random nonce, so signing the same message twice gives two different
     * signatures, and both verify.
     *
     * @throws IllegalStateException when this platform's cryptographic provider cannot sign here.
     */
    public suspend fun sign(message: ByteArray): ByteArray

    public companion object {
        /**
         * A new P-256 key, made in software from the platform's cryptographically secure random
         * source. Kept in memory only.
         *
         * @throws IllegalStateException when this platform's cryptographic provider cannot run here.
         */
        public suspend fun generate(): DeviceKey = SoftwareDeviceKey.generate()
    }
}

/**
 * The software [DeviceKey]: a cryptography-kotlin ECDSA private key held in memory, with its name
 * computed once from the public half when the key is generated.
 *
 * No persistent encoding yet. PKCS#8 cannot be it on the JVM and Android: the JDK provider writes
 * PKCS#8 without the public key, and reading the key back then needs the public half, which the JDK
 * provider derives only with BouncyCastle present. Which form a key store keeps is decided with the
 * first store that keeps one.
 *
 * Immutable after construction, so it is safe to share across threads: each [sign] builds its own
 * signature generator.
 */
internal class SoftwareDeviceKey private constructor(
    private val privateKey: ECDSA.PrivateKey,
    override val id: DidKey,
) : DeviceKey {
    override suspend fun sign(message: ByteArray): ByteArray = providerCall(::signingFailed) {
        privateKey.signatureGenerator(SHA256, ECDSA.SignatureFormat.RAW).generateSignature(message)
    }

    /** The name only: the private half never reaches a log line through [toString]. */
    override fun toString(): String = "SoftwareDeviceKey(${id.value})"

    companion object {
        suspend fun generate(): SoftwareDeviceKey {
            val ecdsa = algorithms().ecdsa
            val pair = providerCall(::generationFailed) { ecdsa.keyPairGenerator(EC.Curve.P256).generateKey() }
            return SoftwareDeviceKey(pair.privateKey, nameOf(pair.publicKey))
        }

        private suspend fun nameOf(publicKey: ECDSA.PublicKey): DidKey = providerCall(::encodingFailed) {
            publicKey.encodeToByteArray(EC.PublicKey.Format.RAW.Compressed)
        }.let(DidKey::ofCompressedPoint)

        private fun signingFailed(failureClass: String) =
            IllegalStateException("kuilt-identity: the platform provider failed to sign ($failureClass)")

        private fun generationFailed(failureClass: String) =
            IllegalStateException("kuilt-identity: the platform provider failed to generate a P-256 key ($failureClass)")

        private fun encodingFailed(failureClass: String) =
            IllegalStateException("kuilt-identity: the platform provider failed to encode a P-256 key ($failureClass)")
    }
}
