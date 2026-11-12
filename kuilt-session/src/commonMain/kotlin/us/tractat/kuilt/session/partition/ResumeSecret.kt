package us.tractat.kuilt.session.partition

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.kotlincrypto.random.CryptoRand

/**
 * The proof a member presents that a resume really is its own: 32 random bytes a host mints for
 * one member at admit time and hands to that member alone.
 *
 * Every member learns every other member's id and the room id from the roster, so a
 * [ResumeToken] built from those alone could be written by anyone admitted — and a forged resume
 * spends the real member's reconnect window, locking it out (#3165). The secret is the one field
 * of the token a bystander never sees: the host sends it only in the direct `Welcome` to the
 * member it names, never in the roster copy every other member receives.
 *
 * Three properties the type carries, so no caller has to remember them:
 * - **Fixed width.** Exactly [SIZE_BYTES] bytes, checked at construction.
 * - **Constant-time comparison.** [matches] (and [equals], which delegates to it) reads every byte
 *   whatever the first difference, so the time a refusal takes says nothing about how close a
 *   guess was.
 * - **Never printed.** [toString] is redacted, so a token or a `Welcome` landing in a log line
 *   does not leak the secret inside it.
 *
 * Serialized as a lowercase hex string, so a [ResumeToken] persisted as JSON stays readable text.
 */
@Serializable(with = ResumeSecret.Serializer::class)
public class ResumeSecret(bytes: ByteArray) {
    private val bytes: ByteArray = bytes.copyOf()

    init {
        require(this.bytes.size == SIZE_BYTES) {
            "ResumeSecret must be exactly $SIZE_BYTES bytes, was ${this.bytes.size}"
        }
    }

    /** A copy of the secret's bytes. */
    public fun toByteArray(): ByteArray = bytes.copyOf()

    /**
     * Whether [other] holds the same bytes, compared in constant time: every byte pair is read
     * and XOR-accumulated with no early return.
     */
    public fun matches(other: ResumeSecret): Boolean {
        var diff = 0
        for (i in bytes.indices) diff = diff or (bytes[i].toInt() xor other.bytes[i].toInt())
        return diff == 0
    }

    /** Content equality, in constant time — see [matches]. */
    override fun equals(other: Any?): Boolean = other is ResumeSecret && matches(other)

    override fun hashCode(): Int = bytes.contentHashCode()

    /** Redacted: a secret must never reach a log line through a token's or a frame's `toString`. */
    override fun toString(): String = "ResumeSecret(<redacted>)"

    public companion object {
        /** Width of every secret, in bytes. */
        public const val SIZE_BYTES: Int = 32

        /** A fresh secret drawn from the platform's cryptographically secure random source. */
        public fun generate(): ResumeSecret = ResumeSecret(CryptoRand.Default.nextBytes(ByteArray(SIZE_BYTES)))
    }

    /** Lowercase hex; decoding refuses any other width or alphabet. */
    internal object Serializer : KSerializer<ResumeSecret> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("us.tractat.kuilt.session.partition.ResumeSecret", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: ResumeSecret) {
            encoder.encodeString(value.bytes.toHex())
        }

        override fun deserialize(decoder: Decoder): ResumeSecret {
            val hex = decoder.decodeString()
            if (hex.length != SIZE_BYTES * 2) {
                throw SerializationException("ResumeSecret hex must be ${SIZE_BYTES * 2} chars, was ${hex.length}")
            }
            return ResumeSecret(ByteArray(SIZE_BYTES) { i -> ((hex.nibble(2 * i) shl 4) or hex.nibble(2 * i + 1)).toByte() })
        }

        private fun String.nibble(index: Int): Int =
            when (val c = this[index]) {
                in '0'..'9' -> c - '0'
                in 'a'..'f' -> c - 'a' + 10
                else -> throw SerializationException("ResumeSecret hex has a non-hex character at $index")
            }

        private fun ByteArray.toHex(): String =
            buildString(size * 2) {
                for (b in this@toHex) {
                    val v = b.toInt() and 0xFF
                    append(HEX[v ushr 4])
                    append(HEX[v and 0x0F])
                }
            }

        private const val HEX = "0123456789abcdef"
    }
}
