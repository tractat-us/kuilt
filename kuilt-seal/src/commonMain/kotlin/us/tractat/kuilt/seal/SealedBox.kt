package us.tractat.kuilt.seal

import dev.whyoleg.cryptography.BinarySize.Companion.bytes
import dev.whyoleg.cryptography.CryptographySystem
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.algorithms.EC
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.random.CryptographyRandom

/**
 * Encrypt bytes to a recipient's public key so that only the holder of the private key can read
 * them, with caller-chosen associated data bound to the result.
 *
 * Every [seal] generates a fresh P-256 key pair and a fresh 96-bit nonce from the platform CSPRNG,
 * agrees a secret with the recipient by ECDH, stretches it with HKDF-SHA-256 into an AES-256-GCM key,
 * and encrypts. Sealing the same plaintext twice gives two different boxes, and there is no
 * deterministic form, for tests or otherwise: a reproducible seal lets a reader who knows all but
 * one input confirm guesses at it.
 *
 * **A box proves nothing about who sealed it.** Sealing needs only the public key, and anyone can
 * hold that — an app that compiles it in hands it to every user. Opening a box shows it was sealed
 * to this key and not altered since; it does not show by whom. Authenticate the sender separately
 * where that matters.
 *
 * The format (version 1), all lengths in bytes:
 *
 * ```
 * version (1) = 0x01 | ephemeral public key (65, SEC 1 uncompressed) | nonce (12) | ciphertext | tag (16)
 * ```
 *
 * The HKDF salt is empty (RFC 5869's all-zero default) and its info is the ASCII label
 * `kuilt-seal/v1 P-256 HKDF-SHA-256 AES-256-GCM` followed by the ephemeral and then the recipient
 * public key, so a box is bound to the key it was sealed for. The associated data is the AEAD's, as
 * given. A box is `plaintext.size + `[OVERHEAD] bytes.
 *
 * **No compression, deliberately.** Compressing before encrypting makes the length depend on the
 * content. A caller that must hide the length pads the plaintext to a fixed size itself.
 *
 * Both calls are `suspend` because WebCrypto, the wasmJs provider, has only asynchronous operations.
 * A failure caused by the bytes passed in is a [SealedBoxException]; none carries plaintext. A
 * platform provider that cannot run at all fails its one-time self-test with [IllegalStateException].
 */
public object SealedBox {
    private const val VERSION: Byte = 1
    private const val VERSION_SIZE = 1
    private const val HEADER_SIZE = VERSION_SIZE + SealPublicKey.ENCODED_SIZE
    private const val NONCE_SIZE = 12
    private const val TAG_SIZE = 16
    private const val AES_KEY_SIZE = 32
    private val LABEL = "kuilt-seal/v1 P-256 HKDF-SHA-256 AES-256-GCM".encodeToByteArray()

    /** Bytes a box adds to its plaintext: version, ephemeral key, nonce and tag. */
    public const val OVERHEAD: Int = VERSION_SIZE + SealPublicKey.ENCODED_SIZE + NONCE_SIZE + TAG_SIZE

    /**
     * Seals [plaintext] to [recipient], binding [associatedData] (which is authenticated, not
     * encrypted, and must be passed again to [open]). Throws [IllegalStateException] if the process
     * has replaced the platform CSPRNG or the provider fails its self-test, and [SealedBoxException]
     * with [SealedBoxException.Reason.SEAL_FAILED] if a sealing step fails.
     */
    public suspend fun seal(recipient: SealPublicKey, plaintext: ByteArray, associatedData: ByteArray = ByteArray(0)): ByteArray =
        seal(recipient, plaintext, associatedData, CryptographySystem.getDefaultRandom())

    /** [seal] with the process random passed in, so a test can show the guard is on the path. */
    internal suspend fun seal(
        recipient: SealPublicKey,
        plaintext: ByteArray,
        associatedData: ByteArray,
        currentRandom: CryptographyRandom,
    ): ByteArray {
        requirePlatformRandom(currentRandom)
        val algorithms = algorithms()
        return providerCall({ SealedBoxException(SealedBoxException.Reason.SEAL_FAILED, it) }) {
            val ephemeral = algorithms.ecdh.keyPairGenerator(EC.Curve.P256).generateKey()
            val ephemeralPublic = SealPublicKey.of(ephemeral.publicKey)
            val shared = ephemeral.privateKey.sharedSecretGenerator().generateSharedSecretToByteArray(recipient.key)
            val cipher = aeadKey(algorithms, shared, ephemeralPublic.encoded(), recipient.encoded()).cipher(TAG_SIZE_BITS)
            // Provider layout: nonce (12, drawn from the CSPRNG checked above) || ciphertext || tag.
            val nonceCiphertextTag = cipher.encrypt(plaintext, associatedData)
            byteArrayOf(VERSION) + ephemeralPublic.encoded() + nonceCiphertextTag
        }
    }

    /**
     * Opens a box [seal] produced for [recipient]'s public key under the same [associatedData], and
     * returns the plaintext. Throws [SealedBoxException]: [SealedBoxException.Reason.NOT_AUTHENTIC]
     * for a wrong key, different associated data or altered bytes, alike.
     */
    public suspend fun open(recipient: SealPrivateKey, sealed: ByteArray, associatedData: ByteArray): ByteArray {
        if (sealed.size < OVERHEAD) {
            throw SealedBoxException(
                SealedBoxException.Reason.MALFORMED,
                "${sealed.size} bytes, the minimum is $OVERHEAD",
            )
        }
        if (sealed[0] != VERSION) throw SealedBoxException(SealedBoxException.Reason.UNSUPPORTED_VERSION)
        // Outside the guard below: an unusable provider is the environment's fault, not the box's.
        val algorithms = algorithms()
        return providerCall({ SealedBoxException(SealedBoxException.Reason.NOT_AUTHENTIC, it) }) {
            val ephemeralBytes = sealed.copyOfRange(VERSION_SIZE, HEADER_SIZE)
            // The agreement below is the on-curve check for this attacker-chosen point.
            val ephemeralPublic = SealPublicKey.decodeForAgreement(algorithms, ephemeralBytes)
            val shared = recipient.key.sharedSecretGenerator().generateSharedSecretToByteArray(ephemeralPublic)
            val cipher = aeadKey(algorithms, shared, ephemeralBytes, recipient.publicKey.encoded()).cipher(TAG_SIZE_BITS)
            cipher.decrypt(sealed.copyOfRange(HEADER_SIZE, sealed.size), associatedData)
        }
    }

    private suspend fun aeadKey(
        algorithms: Algorithms,
        shared: ByteArray,
        ephemeral: ByteArray,
        recipient: ByteArray,
    ): AES.GCM.Key {
        val keyBytes = algorithms.hkdf
            .secretDerivation(SHA256, AES_KEY_SIZE.bytes, salt = null, info = LABEL + ephemeral + recipient)
            .deriveSecretToByteArray(shared)
        return algorithms.gcm.keyDecoder().decodeFromByteArray(AES.Key.Format.RAW, keyBytes)
    }
}
