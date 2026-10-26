package us.tractat.kuilt.seal

import dev.whyoleg.cryptography.algorithms.EC
import dev.whyoleg.cryptography.algorithms.ECDH
import dev.whyoleg.cryptography.serialization.asn1.BitArray
import dev.whyoleg.cryptography.serialization.asn1.Der
import dev.whyoleg.cryptography.serialization.asn1.modules.EcPrivateKey
import dev.whyoleg.cryptography.serialization.asn1.modules.PrivateKeyInfo

/**
 * A recipient's public key: anyone holding it can [seal][SealedBox.seal] bytes that only the
 * matching [SealPrivateKey] can open.
 *
 * Its byte form, [encoded], is stable: the 65-byte SEC 1 uncompressed P-256 point
 * (`0x04 || X || Y`). Two keys are equal exactly when their encodings are, so a consumer can compile
 * a production key into an app as bytes and check at startup that a test key is not the one in use.
 */
public class SealPublicKey private constructor(
    internal val key: ECDH.PublicKey,
    private val bytes: ByteArray,
) {
    /** The stable 65-byte encoding; a fresh copy on every call. */
    public fun encoded(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean = other is SealPublicKey && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "SealPublicKey(${bytes.toHexString().take(FINGERPRINT_HEX_CHARS)}…)"

    public companion object {
        /** Length of [encoded]: one tag byte and two 32-byte coordinates. */
        public const val ENCODED_SIZE: Int = 65

        private const val FINGERPRINT_HEX_CHARS = 16

        /**
         * Reads a key from its [encoded] form. Throws [IllegalArgumentException] when the bytes are
         * not a point on P-256 in uncompressed form, and [IllegalStateException] when the platform
         * provider cannot run at all.
         *
         * The JDK provider's decoder does not check that a point lies on the curve; its key
         * agreement does. So the check here is a throwaway agreement against the decoded point,
         * which every provider refuses for an off-curve one (`OffCurvePointTest` pins that per
         * target). That makes a bad key fail here, uniformly, rather than at the first seal.
         */
        public suspend fun decode(encoded: ByteArray): SealPublicKey {
            val algorithms = algorithms()
            val key = decodeForAgreement(algorithms, encoded)
            providerCall({ IllegalArgumentException("not a point on P-256 ($it)") }) {
                algorithms.ecdh.keyPairGenerator(EC.Curve.P256).generateKey()
                    .privateKey.sharedSecretGenerator().generateSharedSecretToByteArray(key)
            }
            return of(key)
        }

        /**
         * Decodes without the agreement probe, for [SealedBox.open]: there the real agreement is the
         * check, and an off-curve ephemeral key fails it as NOT_AUTHENTIC.
         */
        internal suspend fun decodeForAgreement(algorithms: Algorithms, encoded: ByteArray): ECDH.PublicKey {
            require(encoded.size == ENCODED_SIZE && encoded[0] == UNCOMPRESSED_TAG) {
                "a SealPublicKey is $ENCODED_SIZE bytes starting 0x04; got ${encoded.size} bytes"
            }
            return providerCall({ IllegalArgumentException("not a P-256 point ($it)") }) {
                algorithms.ecdh.publicKeyDecoder(EC.Curve.P256).decodeFromByteArray(EC.PublicKey.Format.RAW, encoded)
            }
        }

        /** Wraps a key the provider already holds; a failure here is the provider's, not the input's. */
        internal suspend fun of(key: ECDH.PublicKey): SealPublicKey {
            val bytes = providerCall({ IllegalStateException("provider could not encode a P-256 public key ($it)") }) {
                key.encodeToByteArray(EC.PublicKey.Format.RAW)
            }
            check(bytes.size == ENCODED_SIZE && bytes[0] == UNCOMPRESSED_TAG) {
                "provider encoded a P-256 public key as ${bytes.size} bytes, not the $ENCODED_SIZE-byte SEC 1 form"
            }
            return SealPublicKey(key, bytes)
        }

        private const val UNCOMPRESSED_TAG: Byte = 0x04
    }
}

/**
 * A recipient's private key: it [opens][SealedBox.open] what was sealed to its [publicKey].
 *
 * [generate] makes one; [encodePkcs8] writes it as standard PKCS #8 that carries its public key, and
 * [decodePkcs8] reads that back on any platform — or a key made elsewhere, such as by
 * `openssl ecparam -name prime256v1 -genkey | openssl pkcs8 -topk8 -nocrypt -outform DER`.
 * [toString] never prints key material.
 */
public class SealPrivateKey private constructor(
    internal val key: ECDH.PrivateKey,
    /** The public half, to hand to whoever seals. */
    public val publicKey: SealPublicKey,
) {
    /**
     * The key as PKCS #8 DER (`PrivateKeyInfo`, RFC 5958) whose inner `ECPrivateKey` (RFC 5915)
     * carries the public key — secret material; store it as such.
     *
     * The public key is written explicitly on every platform. The JDK provider's own export omits
     * it, and the JDK cannot recompute it when reading, so without this a key written on the JVM
     * could not be read back there. The structure is rebuilt with cryptography-kotlin's own DER
     * codec; no key material is computed here.
     */
    public suspend fun encodePkcs8(): ByteArray {
        algorithms()
        // One guard over both steps: a DER codec message may quote bytes, and these are secret.
        return providerCall({ IllegalStateException("could not write a P-256 private key as PKCS #8 ($it)") }) {
            pkcs8WithPublicKey(key.encodeToByteArray(EC.PrivateKey.Format.DER), publicKey.encoded())
        }
    }

    override fun toString(): String = "SealPrivateKey(publicKey=$publicKey)"

    public companion object {
        /** A fresh key pair from the platform provider's own key generator. */
        public suspend fun generate(): SealPrivateKey {
            val algorithms = algorithms()
            val pair = providerCall({ IllegalStateException("provider could not generate a P-256 key pair ($it)") }) {
                algorithms.ecdh.keyPairGenerator(EC.Curve.P256).generateKey()
            }
            return SealPrivateKey(pair.privateKey, SealPublicKey.of(pair.publicKey))
        }

        /**
         * Reads a P-256 key from PKCS #8 DER (`PrivateKeyInfo`, RFC 5958), as [encodePkcs8] or
         * OpenSSL writes it. Throws [IllegalArgumentException] when the bytes are not such a key,
         * including when the public key it carries does not belong to its private key.
         *
         * That last check is not paranoia: the JDK provider takes the embedded public key on trust,
         * so without it a file whose public key was swapped would decode, and boxes sealed to the
         * swapped key would never open. Here the two halves must agree a secret with a fresh key
         * pair — the private half one way, the public half the other — on every platform. A DER
         * without the optional public key is refused on JVM and Android, where the provider cannot
         * recompute it; other platforms may accept one.
         */
        public suspend fun decodePkcs8(der: ByteArray): SealPrivateKey {
            val algorithms = algorithms()
            val (key, public) = providerCall({
                IllegalArgumentException("not a PKCS #8 P-256 private key carrying its public key ($it)")
            }) {
                val key = algorithms.ecdh.privateKeyDecoder(EC.Curve.P256).decodeFromByteArray(EC.PrivateKey.Format.DER, der)
                key to key.getPublicKey()
            }
            requirePair(algorithms, key, public)
            val publicKey = SealPublicKey.of(public)
            // CryptoKit derives the public half from the scalar and ignores the embedded one, so the
            // pair check alone would accept a swapped file there. Compare the embedded point too.
            val embedded = providerCall({ IllegalArgumentException("not a PKCS #8 P-256 private key ($it)") }) {
                embeddedPublicPoint(der)
            }
            require(embedded == null || embedded.contentEquals(publicKey.encoded())) {
                "the PKCS #8 public key does not belong to its private key"
            }
            return SealPrivateKey(key, publicKey)
        }

        private suspend fun requirePair(algorithms: Algorithms, private: ECDH.PrivateKey, public: ECDH.PublicKey) {
            val matches = providerCall({ IllegalArgumentException("the PKCS #8 public key is unusable ($it)") }) {
                val witness = algorithms.ecdh.keyPairGenerator(EC.Curve.P256).generateKey()
                val viaPrivate = private.sharedSecretGenerator().generateSharedSecretToByteArray(witness.publicKey)
                val viaPublic = witness.privateKey.sharedSecretGenerator().generateSharedSecretToByteArray(public)
                viaPrivate.contentEquals(viaPublic)
            }
            require(matches) { "the PKCS #8 public key does not belong to its private key" }
        }
    }
}

/** The public point a PKCS #8 `ECPrivateKey` carries, or null when it carries none. */
internal fun embeddedPublicPoint(pkcs8: ByteArray): ByteArray? {
    val info = Der.decodeFromByteArray(PrivateKeyInfo.serializer(), pkcs8)
    return Der.decodeFromByteArray(EcPrivateKey.serializer(), info.privateKey).publicKey?.byteArray
}

/**
 * Rebuilds a provider's PKCS #8 so its inner `ECPrivateKey` carries [publicPoint], keeping the
 * algorithm identifier, version, secret scalar and any curve parameters exactly as they were.
 */
internal fun pkcs8WithPublicKey(pkcs8: ByteArray, publicPoint: ByteArray): ByteArray {
    val info = Der.decodeFromByteArray(PrivateKeyInfo.serializer(), pkcs8)
    val inner = Der.decodeFromByteArray(EcPrivateKey.serializer(), info.privateKey)
    val filled = EcPrivateKey(inner.version, inner.privateKey, inner.parameters, BitArray(0, publicPoint))
    val rebuilt = PrivateKeyInfo(info.version, info.privateKeyAlgorithm, Der.encodeToByteArray(EcPrivateKey.serializer(), filled))
    return Der.encodeToByteArray(PrivateKeyInfo.serializer(), rebuilt)
}
