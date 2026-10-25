package us.tractat.kuilt.seal

import dev.whyoleg.cryptography.algorithms.EC
import dev.whyoleg.cryptography.algorithms.ECDH
import kotlin.coroutines.cancellation.CancellationException

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
         */
        public suspend fun decode(encoded: ByteArray): SealPublicKey {
            require(encoded.size == ENCODED_SIZE && encoded[0] == UNCOMPRESSED_TAG) {
                "a SealPublicKey is $ENCODED_SIZE bytes starting 0x04; got ${encoded.size} bytes"
            }
            val key = try {
                ecdh().publicKeyDecoder(EC.Curve.P256).decodeFromByteArray(EC.PublicKey.Format.RAW, encoded)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw IllegalArgumentException("not a P-256 point (${e::class.simpleName})")
            }
            return of(key)
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
 * Generate one with [generate], keep its [encodePkcs8] bytes wherever the holder keeps secrets, and
 * read it back with [decodePkcs8]. [toString] never prints key material.
 */
public class SealPrivateKey private constructor(
    internal val key: ECDH.PrivateKey,
    /** The public half, to hand to whoever seals. */
    public val publicKey: SealPublicKey,
) {
    /** The key as PKCS #8 DER (`PrivateKeyInfo`, RFC 5958) — secret material; store it as such. */
    public suspend fun encodePkcs8(): ByteArray = key.encodeToByteArray(EC.PrivateKey.Format.DER)

    override fun toString(): String = "SealPrivateKey(publicKey=$publicKey)"

    public companion object {
        /** A fresh key pair from the platform provider's own key generator. */
        public suspend fun generate(): SealPrivateKey {
            val pair = ecdh().keyPairGenerator(EC.Curve.P256).generateKey()
            return SealPrivateKey(pair.privateKey, SealPublicKey.of(pair.publicKey))
        }

        /**
         * Reads a key from PKCS #8 DER, as [encodePkcs8] writes it. Throws
         * [IllegalArgumentException] when the bytes are not a P-256 private key.
         */
        public suspend fun decodePkcs8(der: ByteArray): SealPrivateKey {
            val key = try {
                ecdh().privateKeyDecoder(EC.Curve.P256).decodeFromByteArray(EC.PrivateKey.Format.DER, der)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw IllegalArgumentException("not a PKCS #8 P-256 private key (${e::class.simpleName})")
            }
            return SealPrivateKey(key, SealPublicKey.of(key.getPublicKey()))
        }
    }
}

internal fun ecdh(): ECDH = platformProvider().get(ECDH)
