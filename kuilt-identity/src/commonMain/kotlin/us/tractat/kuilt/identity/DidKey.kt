package us.tractat.kuilt.identity

import dev.whyoleg.cryptography.algorithms.EC
import dev.whyoleg.cryptography.algorithms.ECDSA
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.coroutines.cancellation.CancellationException
import kotlin.jvm.JvmInline

/**
 * The public name of one device's key: a short string another device can store, compare, and check a
 * signature against. Two devices holding the same name are talking about the same key.
 *
 * It is a [W3C `did:key`](https://w3c-ccg.github.io/did-method-key/) for a P-256 public key:
 * `did:key:z`, then base58btc of the `p256-pub` multicodec (`0x80 0x24`) and the key's 33-byte SEC 1
 * compressed point. So every value starts `did:key:zDn`. Each key has exactly one name, so
 * comparing two `DidKey`s compares their keys.
 *
 * **Two levels of checking, because a constructor cannot suspend.** Building a `DidKey` — including
 * decoding one with its [serializer], where it travels as a plain string — checks the syntax only:
 * the prefix, the alphabet, the multicodec, the 33-byte length and the `0x02`/`0x03` lead byte.
 * [parse] also checks that the point is on the curve and that this platform's provider decodes it,
 * and [verify] checks the curve before it trusts the point. Use [parse] for a name from outside.
 *
 * The on-curve check is not decoration. cryptography-kotlin's JDK decoder computes a square root
 * without checking it, so on the JVM and Android a name whose `x` has no point on P-256 would
 * otherwise decode into a key. The check runs in common code, so every platform agrees.
 *
 * @sample us.tractat.kuilt.identity.sampleParseDidKey
 */
@Serializable(with = DidKey.Serializer::class)
@JvmInline
public value class DidKey internal constructor(
    /** The whole name, `did:key:zDn…`. */
    public val value: String,
) {
    init {
        val problem = syntaxProblem(value)
        require(problem == null) { "not a P-256 did:key: $problem" }
    }

    /** A copy of the key's 33-byte SEC 1 compressed point: a `0x02`/`0x03` lead byte, then `x`. */
    public fun compressedPoint(): ByteArray {
        val decoded = checkNotNull(decodeMultikey(value)) { "a DidKey that passed its init no longer decodes" }
        return decoded.copyOfRange(MULTICODEC.size, decoded.size)
    }

    /**
     * Whether [signature] is this key's ECDSA P-256 signature over SHA-256 of [message], as the 64-byte
     * raw `r ‖ s` form.
     *
     * Never throws on bad input: a signature of the wrong length, a key whose point is not on the
     * curve, and a signature the provider cannot read are all `false`. A provider that cannot run on
     * this platform at all still throws [IllegalStateException], since that is not a verdict on the
     * signature.
     */
    public suspend fun verify(message: ByteArray, signature: ByteArray): Boolean {
        if (signature.size != SIGNATURE_SIZE) return false
        val key = decodeOnCurve(compressedPoint()) ?: return false
        return try {
            key.signatureVerifier(SHA256, ECDSA.SignatureFormat.RAW).tryVerifySignature(message, signature)
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            false
        } catch (e: Error) {
            throw e
        } catch (e: Throwable) {
            // Throwable, not Exception: a WebCrypto rejection arrives on wasmJs as a bare JsException.
            false
        }
    }

    public companion object {
        /**
         * The `DidKey` [value] names, after checking its syntax, that its point is on P-256, and that
         * this platform's provider decodes it.
         *
         * @throws IllegalArgumentException when any of those fails.
         * @throws IllegalStateException when the platform's provider cannot run here at all.
         */
        public suspend fun parse(value: String): DidKey {
            val key = DidKey(value)
            requireNotNull(decodeOnCurve(key.compressedPoint())) { "not a P-256 did:key: the point is not on the curve, or this platform's provider refused it" }
            return key
        }

        /** The name of the key whose 33-byte compressed point is [point]. Checks syntax only. */
        internal fun ofCompressedPoint(point: ByteArray): DidKey = DidKey(PREFIX + Base58.encode(MULTICODEC + point))
    }

    /** A `DidKey` is a plain string on the wire. Decoding checks its syntax only; [parse] does the rest. */
    internal object Serializer : KSerializer<DidKey> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("us.tractat.kuilt.identity.DidKey", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: DidKey): Unit = encoder.encodeString(value.value)

        override fun deserialize(decoder: Decoder): DidKey = DidKey(decoder.decodeString())
    }
}

/**
 * The provider's key for [point], or `null` when the point is not on P-256 or the provider refuses it.
 *
 * The one path from a compressed point to a provider key: [DidKey.parse] and [DidKey.verify] both
 * come through here, so the on-curve check cannot drop out of either. It runs before the provider
 * sees the point, because the JDK provider would accept an off-curve one (#3201). A provider that
 * cannot run here at all still throws [IllegalStateException] from [algorithms].
 */
internal suspend fun decodeOnCurve(point: ByteArray): ECDSA.PublicKey? {
    if (!isOnCurve(point)) return null
    val ecdsa = algorithms().ecdsa
    return try {
        ecdsa.publicKeyDecoder(EC.Curve.P256).decodeFromByteArray(EC.PublicKey.Format.RAW.Compressed, point)
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
        null
    } catch (e: Error) {
        throw e
    } catch (e: Throwable) {
        // Throwable, not Exception: a WebCrypto rejection arrives on wasmJs as a bare JsException.
        null
    }
}

/** `did:key:` plus the multibase prefix for base58btc. */
private const val PREFIX = "did:key:z"

/** The `p256-pub` multicodec, `0x1200`, as its unsigned varint. */
private val MULTICODEC = byteArrayOf(0x80.toByte(), 0x24)

/** The multicodec and the compressed point. */
private const val MULTIKEY_SIZE = 2 + COMPRESSED_POINT_SIZE

/** The most base58 digits [MULTIKEY_SIZE] bytes can need: ⌈35 × log₅₈ 256⌉. A longer string is refused before decoding. */
private const val MAX_ENCODED_LENGTH = 48

private const val SIGNATURE_SIZE = 64

/** The multicodec and point bytes behind [value], or `null` when it is not `did:key:z` + base58btc within the size bound. */
private fun decodeMultikey(value: String): ByteArray? {
    if (!value.startsWith(PREFIX)) return null
    val encoded = value.substring(PREFIX.length)
    if (encoded.length > MAX_ENCODED_LENGTH) return null
    return Base58.decode(encoded, maxBytes = MULTIKEY_SIZE)
}

/** Why [value] is not a P-256 `did:key`, or `null` when its syntax is right. */
private fun syntaxProblem(value: String): String? {
    if (!value.startsWith(PREFIX)) return "it does not start \"$PREFIX\""
    val decoded = decodeMultikey(value) ?: return "the part after \"$PREFIX\" is not base58btc of at most $MULTIKEY_SIZE bytes"
    return when {
        decoded.size < MULTICODEC.size || decoded[0] != MULTICODEC[0] || decoded[1] != MULTICODEC[1] ->
            "the multicodec is not p256-pub (0x80 0x24)"
        decoded.size != MULTIKEY_SIZE -> "the point is ${decoded.size - MULTICODEC.size} bytes, not $COMPRESSED_POINT_SIZE"
        decoded[2] != 0x02.toByte() && decoded[2] != 0x03.toByte() -> "the point is not compressed (lead byte is not 0x02 or 0x03)"
        else -> null
    }
}
