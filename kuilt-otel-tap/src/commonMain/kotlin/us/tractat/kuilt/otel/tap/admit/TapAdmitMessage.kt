@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.otel.tap.admit

import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import us.tractat.kuilt.otel.tap.LogTapCbor

/**
 * Wire frames for the tap's token-gated admission handshake, multiplexed onto the
 * same `Seam.incoming` the replicator's frames ride. A leading [PREFIX_BYTE] — which a
 * CBOR replication frame does not begin with — lets the gate pull these frames out and
 * pass only replication frames through to the replicator.
 *
 * Flow (offering side = *verifier*, holds the code; pulling side = *prover*):
 * ```
 * verifier → prover : Challenge(nonce)
 * prover   → verifier: Proof(HMAC-SHA256(code, nonce))
 * verifier → prover : Reject(reason)   // on mismatch/expiry; otherwise the peer is surfaced
 * ```
 * The code never appears on the wire — only the [Proof] tag derived from it does, and a
 * fresh per-attempt [Challenge.nonce] makes a captured [Proof] useless for replay.
 */
@Serializable
public sealed interface TapAdmitMessage {
    /** Verifier → prover: a fresh random nonce the prover must MAC with the join code. */
    @Serializable
    @SerialName("challenge")
    public data class Challenge(
        @Serializable(with = TapByteStringSerializer::class) val nonce: ByteString,
    ) : TapAdmitMessage

    /** Prover → verifier: `HMAC-SHA256(code, nonce)` proving knowledge of the join code. */
    @Serializable
    @SerialName("proof")
    public data class Proof(
        @Serializable(with = TapByteStringSerializer::class) val tag: ByteString,
    ) : TapAdmitMessage

    /** Verifier → prover: admission refused (bad tag or expired token). */
    @Serializable
    @SerialName("reject")
    public data class Reject(val reason: String) : TapAdmitMessage

    public companion object {
        /**
         * First byte of every encoded admit frame. A replication frame must not begin with
         * it, so the gate can distinguish protocol frames from application frames on the one
         * shared `incoming` stream. Value `0x74` ('t' for tap-admit).
         */
        public const val PREFIX_BYTE: Byte = 0x74

        /** Encode a [message] to bytes with the [PREFIX_BYTE] framing prefix. */
        public fun encode(message: TapAdmitMessage): ByteArray {
            val cbor = LogTapCbor.encodeToByteArray(message)
            return ByteArray(cbor.size + 1).also { out ->
                out[0] = PREFIX_BYTE
                cbor.copyInto(out, destinationOffset = 1)
            }
        }

        /**
         * Decode bytes as a [TapAdmitMessage], or `null` if they are not a prefixed admit
         * frame (an application/replication frame) or fail to decode (malformed).
         */
        public fun decode(bytes: ByteArray): TapAdmitMessage? {
            if (!isAdmitFrame(bytes)) return null
            // Non-suspend parse of an untrusted frame — bare runCatching is correct here
            // (no coroutine context, so there is no CancellationException to swallow).
            return runCatching {
                LogTapCbor.decodeFromByteArray<TapAdmitMessage>(bytes.copyOfRange(1, bytes.size))
            }.getOrNull()
        }

        /** True if [bytes] looks like an admit frame (starts with [PREFIX_BYTE]). */
        public fun isAdmitFrame(bytes: ByteArray): Boolean =
            bytes.isNotEmpty() && bytes[0] == PREFIX_BYTE
    }
}

/**
 * Serializes a [ByteString] as a raw byte array. Local to the admit package because
 * `:kuilt-otel`'s equivalent is `internal`. With `LogTapCbor`'s `alwaysUseByteString`,
 * this encodes as a CBOR byte string; content-based [ByteString] equality keeps the wire
 * frames value-comparable (used by the round-trip tests).
 */
internal object TapByteStringSerializer : KSerializer<ByteString> {
    private val delegate = ByteArraySerializer()

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("us.tractat.kuilt.otel.tap.admit.ByteString", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ByteString) {
        encoder.encodeSerializableValue(delegate, value.toByteArray())
    }

    override fun deserialize(decoder: Decoder): ByteString =
        ByteString(decoder.decodeSerializableValue(delegate))
}
