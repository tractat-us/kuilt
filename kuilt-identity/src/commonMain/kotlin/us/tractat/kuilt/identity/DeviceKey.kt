package us.tractat.kuilt.identity

import com.ionspin.kotlin.bignum.integer.BigInteger
import com.ionspin.kotlin.bignum.integer.Sign
import dev.whyoleg.cryptography.algorithms.EC
import dev.whyoleg.cryptography.algorithms.ECDSA
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64

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
    /** This key's public name. [DidKey.verify] on it accepts every signature [sign] makes. */
    public val id: DidKey

    /**
     * Signs [message]: ECDSA on P-256 over SHA-256 of [message], as the 64-byte raw `r ‖ s` form
     * that [id]'s [DidKey.verify] accepts.
     *
     * An implementation must never reuse a nonce across different messages, since two signatures
     * sharing one give the private key away. Random nonces and deterministic ones (RFC 6979) both
     * meet that, so signing the same message twice may give two signatures or the same one.
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
 * Each [sign] draws a fresh random nonce from the platform's provider, so signing the same message
 * twice gives two different signatures, and both verify.
 *
 * A store that keeps it on disk writes it with [encodeJwk] and reads it back with [readBack]: a JWK
 * carrying `x`, `y` and `d`, checked on the way in by signing and verifying under the name the JWK's
 * public half gives. PKCS#8 cannot be the format on the JVM and Android: the JDK provider writes
 * PKCS#8 without the public key, and reading the key back then needs the public half, which the JDK
 * provider derives only with BouncyCastle present. A JWK carries the public half itself, so the only
 * thing left to check is that the two halves belong together, which the self-test does.
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

    /**
     * This key as a JWK carrying `x`, `y` and `d` — the private half in the clear, for a key store to
     * keep where only this device can read it. [readBack] is the way back in.
     */
    suspend fun encodeJwk(): ByteArray = providerCall(::encodingFailed) {
        privateKey.encodeToByteArray(EC.PrivateKey.Format.JWK)
    }

    companion object {
        /** What the self-test signs: fixed, since it proves only that the two halves belong together. */
        private val SELF_TEST = "kuilt-identity device-key self-test".encodeToByteArray()

        /**
         * The key in [jwk], or why it cannot be used.
         *
         * Names the key from the JWK's own `x` and `y`, decodes its `d` with the platform's private
         * decoder, then signs [SELF_TEST] with `d` and verifies that signature under the name, through
         * [DidKey.verify] and so through the same on-curve check and decompression every other device
         * applies to that name. A key that passes signs for the name every room will check it
         * against. Only the bytes can make it [StoredKey.Refused]: bytes that are not a P-256 JWK, a
         * point that is not on the curve, a `d` out of range, and a `d` that belongs to some other key.
         * A provider that fails while it signs throws instead, since that says nothing about the
         * bytes and a store must not replace a sound key over it. A decoder's exception is the one
         * case that cannot be told apart, and is read as the bytes' fault.
         *
         * The name is read here rather than through a provider, because the three disagree about a
         * JWK's public half (all measured on #3224): the JDK keeps the file's `x` and `y`; CryptoKit's
         * private decoder reads `d` alone and derives the public half from it, so a name taken from
         * there always matches `d` and a mismatched pair would pass; and WebCrypto refuses a JWK
         * carrying `d` through its public decoder. WebCrypto also checks the pair itself on import,
         * so there a mismatched pair is refused by the decoder before the self-test runs.
         *
         * @throws IllegalStateException when this platform's cryptographic provider cannot run at all,
         *   fails while signing, or mints a cancellation while decoding: none of which says anything
         *   about [jwk], so none may be read as a reason to replace it.
         */
        suspend fun readBack(jwk: ByteArray): StoredKey = readBack(jwk, ::selfTestSignature)

        /** [readBack] with the self-test's signing step injected, so a test can make the provider fail there. */
        suspend fun readBack(jwk: ByteArray, sign: suspend (ECDSA.PrivateKey, ByteArray) -> ByteArray): StoredKey {
            val ecdsa = algorithms().ecdsa
            var failing = "its x, y and d are not a P-256 key"
            // Only reading the bytes can condemn them: the JSON, the coordinates, the scalar's range and
            // the provider's decoder. Signing below is the provider's own work on a key already read, so
            // a failure there says nothing about the bytes and throws instead.
            val (privateKey, name) = try {
                val name = nameInJwk(jwk)
                failing = "it does not decode as a P-256 JWK"
                ecdsa.privateKeyDecoder(EC.Curve.P256).decodeFromByteArray(EC.PrivateKey.Format.JWK, jwk) to name
            } catch (e: CancellationException) {
                // My own cancellation propagates; one the provider minted while this job is alive is the
                // provider failing, not the bytes, so it is not a reason to replace the key either.
                currentCoroutineContext().ensureActive()
                throw decodingFailed(e::class.simpleName ?: "CancellationException")
            } catch (e: Error) {
                throw e
            } catch (e: Throwable) {
                // The class only: a provider's message is text this module does not control.
                return StoredKey.Refused("$failing (${e::class.simpleName})")
            }
            val signature = providerCall(::signingFailed) { sign(privateKey, SELF_TEST) }
            if (!name.verify(SELF_TEST, signature)) {
                return StoredKey.Refused("its private half does not sign for the name its public half gives, ${name.value}")
            }
            return StoredKey.Usable(SoftwareDeviceKey(privateKey, name))
        }

        /**
         * The name the JWK's own `x` and `y` give: `x`, behind the lead byte `y`'s parity picks, after
         * checking that `d` is a P-256 scalar (`0 < d < n`). Whether `x` is on the curve is
         * [DidKey.verify]'s question. The scalar is checked here so that every bad `d` is refused as
         * damage, whatever a provider would do with it when asked to sign.
         */
        private fun nameInJwk(jwk: ByteArray): DidKey {
            val fields = Json.parseToJsonElement(jwk.decodeToString()).jsonObject
            fun fixedWidth(field: String): ByteArray =
                JWK_BASE64.decode(fields.getValue(field).jsonPrimitive.content).also {
                    require(it.size == COORDINATE_BYTES) { "'$field' is not $COORDINATE_BYTES bytes" }
                }
            val d = BigInteger.fromByteArray(fixedWidth("d"), Sign.POSITIVE)
            require(d > BigInteger.ZERO && d < P256.N) { "'d' is not a P-256 scalar" }
            val x = fixedWidth("x")
            val lead: Byte = if (fixedWidth("y").last().toInt() and 1 == 1) 0x03 else 0x02
            return DidKey.ofCompressedPoint(byteArrayOf(lead) + x)
        }

        private suspend fun selfTestSignature(privateKey: ECDSA.PrivateKey, message: ByteArray): ByteArray =
            privateKey.signatureGenerator(SHA256, ECDSA.SignatureFormat.RAW).generateSignature(message)

        private const val COORDINATE_BYTES = 32
        private val JWK_BASE64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

        suspend fun generate(): SoftwareDeviceKey {
            val ecdsa = algorithms().ecdsa
            val pair = providerCall(::generationFailed) { ecdsa.keyPairGenerator(EC.Curve.P256).generateKey() }
            return SoftwareDeviceKey(pair.privateKey, nameOf(pair.publicKey))
        }

        private suspend fun nameOf(publicKey: ECDSA.PublicKey): DidKey = providerCall(::encodingFailed) {
            publicKey.encodeToByteArray(EC.PublicKey.Format.RAW.Compressed)
        }.let(DidKey::ofCompressedPoint)

        private fun decodingFailed(failureClass: String) =
            IllegalStateException("kuilt-identity: the platform provider failed while decoding a stored key ($failureClass)")

        private fun signingFailed(failureClass: String) =
            IllegalStateException("kuilt-identity: the platform provider failed to sign ($failureClass)")

        private fun generationFailed(failureClass: String) =
            IllegalStateException("kuilt-identity: the platform provider failed to generate a P-256 key ($failureClass)")

        private fun encodingFailed(failureClass: String) =
            IllegalStateException("kuilt-identity: the platform provider failed to encode a P-256 key ($failureClass)")
    }
}

/** What [SoftwareDeviceKey.readBack] found in a stored key. */
internal sealed interface StoredKey {
    /** A key whose private half signs for its name. */
    class Usable(val key: SoftwareDeviceKey) : StoredKey

    /** Not a usable key, and [reason] says why in words that carry no key material. */
    class Refused(val reason: String) : StoredKey {
        override fun toString(): String = "Refused($reason)"
    }
}
