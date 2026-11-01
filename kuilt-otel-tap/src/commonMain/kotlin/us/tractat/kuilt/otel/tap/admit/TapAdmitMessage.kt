@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.otel.tap.admit

import us.tractat.kuilt.core.runCatchingCancellable

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
import us.tractat.kuilt.otel.tap.TapCbor

/**
 * Wire frames for the tap's token-gated admission handshake, multiplexed onto the
 * same `Seam.incoming` the replicator's frames ride. A leading [PREFIX_BYTE] — which a
 * CBOR replication frame does not begin with — lets the gate pull these frames out and
 * pass only replication frames through to the replicator.
 *
 * Flow (offering side = *verifier*, holds the code; pulling side = *prover*):
 * ```
 * verifier → prover : Challenge(nonce)
 * prover   → verifier: Proof(proverNonce, tag)   // tag: see admissionTag
 * verifier → prover : Reject(reason)   // on mismatch/expiry; otherwise the peer is surfaced
 * ```
 * The code never appears on the wire — only the [Proof] tag derived from it does. The tag covers a
 * nonce from each side and both peers' ids, so a captured or forwarded [Proof] is good for no other
 * exchange, and the prover's nonce means a challenger cannot compute the input ahead of time.
 *
 * The frames carry no direction and no role. Which way a proof runs is in the ids the tag covers, so
 * two peers could run the exchange at each other at once with no change to this format.
 *
 * ## Mixed versions refuse, on purpose
 *
 * A peer from before this format shares the wire and not the handshake, and nothing downgrades. An
 * old [Proof] has no `proverNonce`, so it does not decode here and is dropped. A current [Proof] has a
 * key an old decoder does not know, and its tag is not one an old verifier computes. Both ends see a
 * pull that never converges.
 */
@Serializable
public sealed interface TapAdmitMessage {
    /**
     * Verifier → prover: a fresh random nonce the prover must MAC with the join code.
     *
     * ## The nonce is a fixed-width field, and a wrong width is REJECTED, never reshaped (#1820)
     *
     * [nonce] is always exactly [NONCE_BYTES] bytes — enforced here, not merely documented. The
     * check lives in the constructor deliberately: kotlinx-serialization invokes it, so the
     * invariant holds on **every** path, encode and decode alike, and a new consumer cannot
     * forget it.
     *
     * It is load-bearing because the prover MACs this value verbatim and hands the tag back. Leaving
     * the width to the sender let a peer decide how many bytes of the MAC input existed — a
     * zero-length nonce erased it entirely, and with variable widths the two nonces in the MAC input
     * could trade bytes across their boundary. A width is a quantity and could be clamped; a nonce
     * is not: a wrong-width nonce is proof of a malformed or forged challenge, and padding or
     * truncating it to [NONCE_BYTES] would launder that proof into a valid-looking challenge. The
     * frame is dropped instead — [decode] returns `null`, which `TokenGatedSeam` already treats as
     * "do not answer".
     *
     * Enforcing the width does **not** make the nonce unpredictable: it is still chosen wholly by
     * the sender. What stops a peer picking a fixed value and precomputing against it is the prover's
     * own nonce in [Proof], not this check.
     */
    @Serializable
    @SerialName("challenge")
    public data class Challenge(
        @Serializable(with = TapByteStringSerializer::class) val nonce: ByteString,
    ) : TapAdmitMessage {
        init {
            require(nonce.size == NONCE_BYTES) {
                "malformed Challenge: nonce is ${nonce.size} bytes, expected exactly $NONCE_BYTES"
            }
        }

        public companion object {
            /** Width of a challenge nonce, in bytes. Both the generator and the check use this. */
            public const val NONCE_BYTES: Int = 16
        }
    }

    /**
     * Prover → verifier: proof of the join code, good for this one exchange.
     *
     * [proverNonce] is [Challenge.NONCE_BYTES] bytes the prover drew from a secure source when it
     * answered, and [tag] is [TAG_BYTES] bytes. Both widths are enforced here for the reason
     * [Challenge] gives: the verifier feeds [proverNonce] straight into a MAC input whose encoding
     * relies on that width to be unambiguous.
     */
    @Serializable
    @SerialName("proof")
    public data class Proof(
        @Serializable(with = TapByteStringSerializer::class) val proverNonce: ByteString,
        @Serializable(with = TapByteStringSerializer::class) val tag: ByteString,
    ) : TapAdmitMessage {
        init {
            require(proverNonce.size == Challenge.NONCE_BYTES) {
                "malformed Proof: proverNonce is ${proverNonce.size} bytes, expected exactly ${Challenge.NONCE_BYTES}"
            }
            require(tag.size == TAG_BYTES) {
                "malformed Proof: tag is ${tag.size} bytes, expected exactly $TAG_BYTES"
            }
        }

        public companion object {
            /** Width of an HMAC-SHA256 tag, in bytes. */
            public const val TAG_BYTES: Int = 32
        }
    }

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
            val cbor = TapCbor.encodeToByteArray(message)
            return ByteArray(cbor.size + 1).also { out ->
                out[0] = PREFIX_BYTE
                cbor.copyInto(out, destinationOffset = 1)
            }
        }

        /**
         * Decode bytes as a [TapAdmitMessage], or `null` if they are not a prefixed admit
         * frame (an application/replication frame) or fail to decode (malformed).
         *
         * "Malformed" includes a frame that decodes structurally but violates a wire invariant —
         * a [Challenge] or [Proof] with a field of the wrong width throws out of the constructor
         * kotlinx-serialization calls, and is dropped here like any other garbage. So is a [Proof]
         * from a peer that predates [Proof.proverNonce].
         * Use [isAdmitFrame] to tell the two `null`s apart when that matters.
         */
        public fun decode(bytes: ByteArray): TapAdmitMessage? {
            if (!isAdmitFrame(bytes)) return null
            // Non-suspend parse of an untrusted frame; runCatchingCancellable is used
            // uniformly across the codebase and is behaviour-identical here (no coroutine
            // context, so there is no CancellationException to rethrow).
            return runCatchingCancellable {
                TapCbor.decodeFromByteArray<TapAdmitMessage>(bytes.copyOfRange(1, bytes.size))
            }.getOrNull()
        }

        /** True if [bytes] looks like an admit frame (starts with [PREFIX_BYTE]). */
        public fun isAdmitFrame(bytes: ByteArray): Boolean =
            bytes.isNotEmpty() && bytes[0] == PREFIX_BYTE
    }
}

/**
 * Serializes a [ByteString] as a raw byte array. Local to the admit package because
 * `:kuilt-otel`'s equivalent is `internal`. With `TapCbor`'s `alwaysUseByteString`,
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
