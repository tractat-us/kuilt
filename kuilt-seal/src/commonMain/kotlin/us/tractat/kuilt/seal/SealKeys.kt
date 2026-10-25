package us.tractat.kuilt.seal

import dev.whyoleg.cryptography.algorithms.EC
import dev.whyoleg.cryptography.algorithms.ECDH

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
         * not a point on P-256 in uncompressed form.
         *
         * The JDK provider's decoder does not check that a point lies on the curve; its key
         * agreement does. So the check here is a throwaway agreement against the decoded point,
         * which every provider refuses for an off-curve one (`OffCurvePointTest` pins that per
         * target). That makes a bad key fail here, uniformly, rather than at the first seal.
         */
        public suspend fun decode(encoded: ByteArray): SealPublicKey {
            val key = decodeForAgreement(encoded)
            providerCall({ IllegalArgumentException("not a point on P-256 ($it)") }) {
                ecdh().keyPairGenerator(EC.Curve.P256).generateKey()
                    .privateKey.sharedSecretGenerator().generateSharedSecretToByteArray(key)
            }
            return of(key)
        }

        /**
         * Decodes without the agreement probe, for [SealedBox.open]: there the real agreement is the
         * check, and an off-curve ephemeral key fails it as NOT_AUTHENTIC.
         */
        internal suspend fun decodeForAgreement(encoded: ByteArray): ECDH.PublicKey {
            require(encoded.size == ENCODED_SIZE && encoded[0] == UNCOMPRESSED_TAG) {
                "a SealPublicKey is $ENCODED_SIZE bytes starting 0x04; got ${encoded.size} bytes"
            }
            return providerCall({ IllegalArgumentException("not a P-256 point ($it)") }) {
                ecdh().publicKeyDecoder(EC.Curve.P256).decodeFromByteArray(EC.PublicKey.Format.RAW, encoded)
            }
        }

        internal suspend fun of(key: ECDH.PublicKey): SealPublicKey {
            val bytes = key.encodeToByteArray(EC.PublicKey.Format.RAW)
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
 * [generate] makes one in memory. A key that must outlive the process is made outside it — for
 * example `openssl ecparam -name prime256v1 -genkey | openssl pkcs8 -topk8 -nocrypt -outform DER` —
 * and read with [decodePkcs8]. There is deliberately no encoder: the JDK provider writes PKCS #8
 * without the optional public key and cannot recompute it, so an encoder would write bytes that
 * [decodePkcs8] refuses on the JVM. [toString] never prints key material.
 */
public class SealPrivateKey private constructor(
    internal val key: ECDH.PrivateKey,
    /** The public half, to hand to whoever seals. */
    public val publicKey: SealPublicKey,
) {
    override fun toString(): String = "SealPrivateKey(publicKey=$publicKey)"

    public companion object {
        /** A fresh key pair from the platform provider's own key generator. */
        public suspend fun generate(): SealPrivateKey {
            val pair = ecdh().keyPairGenerator(EC.Curve.P256).generateKey()
            return SealPrivateKey(pair.privateKey, SealPublicKey.of(pair.publicKey))
        }

        /**
         * Reads a P-256 key from PKCS #8 DER (`PrivateKeyInfo`, RFC 5958) whose inner `ECPrivateKey`
         * carries the optional public key, as OpenSSL writes it. Throws [IllegalArgumentException]
         * when the bytes are not such a key. A DER without the public key is refused on JVM and
         * Android, where the provider cannot recompute it; other platforms may accept one.
         */
        public suspend fun decodePkcs8(der: ByteArray): SealPrivateKey {
            val (key, public) = providerCall({
                IllegalArgumentException("not a PKCS #8 P-256 private key carrying its public key ($it)")
            }) {
                val key = ecdh().privateKeyDecoder(EC.Curve.P256).decodeFromByteArray(EC.PrivateKey.Format.DER, der)
                key to key.getPublicKey()
            }
            return SealPrivateKey(key, SealPublicKey.of(public))
        }
    }
}

internal fun ecdh(): ECDH = platformProvider().get(ECDH)
