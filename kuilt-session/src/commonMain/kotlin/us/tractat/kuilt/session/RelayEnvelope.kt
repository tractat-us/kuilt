package us.tractat.kuilt.session

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.runCatchingCancellable

/**
 * Where a relayed frame is bound.
 *
 * A sealed type rather than a nullable `PeerId` so the two cases are exhaustive at every use site
 * and neither can be reached by forgetting a null check.
 */
@Serializable
internal sealed interface RelayDest {

    /**
     * The host, plus every admitted member except the origin. The relayed form of
     * `Room.broadcast`.
     */
    @Serializable
    @SerialName("all")
    data object Everyone : RelayDest

    /** Exactly [peer] — which may be the host itself. The relayed form of `Room.sendTo`. */
    @Serializable
    @SerialName("one")
    data class One(val peer: PeerId) : RelayDest
}

/**
 * One frame travelling spoke → host → spoke, with the **true originator kept intact** (#1994).
 *
 * On a star fabric a joiner's frame reaches only the host; nothing forwards it onward, so a
 * `Quilter` over `Room.channel(...)` targets co-members it can never address. The host forwards
 * the frame instead — and because the fabric then stamps the *host* as the sender, the real
 * originator has to ride inside the frame. [origin] is that field.
 *
 * ## The origin is forgeable and is never trusted
 *
 * [origin] is attacker-controlled wire data. Every receiver checks it against the fabric-stamped
 * sender via `validFirstHop` before crediting it: the host rejects a spoke naming another spoke,
 * and the joiner accepts a relay frame only from its identified host. The payload is additionally
 * gated by `SeamRoom.isRelayable`, so a relayed frame can carry application data only — never
 * admit, lobby, heartbeat, or a nested relay.
 *
 * ## The host forwards these bytes unchanged
 *
 * [dest] is meaningful on the host hop only, so there is no per-recipient re-wrapping: one
 * enqueue, one encoding, and [RelayDest.Everyone] stays `Everyone` on the wire. The joiner
 * independently re-checks that [dest] names it — a second, cheap check of the leak boundary at the
 * far end rather than trusting the host's routing.
 *
 * CBOR behind [RoomFramePrefix.Relay] (`0x72`), matching every other room frame family. Lives in
 * `:kuilt-session` rather than `:kuilt-core` because the contract module carries no CBOR
 * dependency and nothing outside this module consumes this type.
 *
 * [RelayDest]'s subclasses carry explicit `@SerialName`s for the same reason `AdmitMessage`'s do
 * (`@SerialName("hello")`, `"paused"`, …): without one the CBOR discriminator is the
 * fully-qualified class name, so a package move or rename would silently break cross-version wire
 * compatibility with no compile error — and a 44-byte discriminator would put the envelope well
 * past the spec's "roughly 40–60 bytes" estimate, which matters while budgeting is deferred.
 * [equals]/[hashCode] compare [payload] by content (kuilt convention for byte-carrying types).
 */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
internal class RelayEnvelope(
    val origin: PeerId,
    val dest: RelayDest,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RelayEnvelope) return false
        return origin == other.origin && dest == other.dest && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = origin.hashCode()
        result = 31 * result + dest.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }

    override fun toString(): String =
        "RelayEnvelope(origin=$origin, dest=$dest, payload=${payload.size}B)"

    internal companion object {
        /**
         * `ignoreUnknownKeys` for the same forward-compatibility reason `AdmitMessage`'s codec has
         * it: a frame minted by a newer build carrying an extra field still decodes here instead
         * of being dropped as malformed.
         */
        private val cbor = Cbor { ignoreUnknownKeys = true }

        /** Encode [envelope] with the [RoomFramePrefix.Relay] framing prefix. */
        fun encode(envelope: RelayEnvelope): ByteArray {
            val encoded = cbor.encodeToByteArray(envelope)
            return ByteArray(encoded.size + 1).also { out ->
                out[0] = RoomFramePrefix.Relay.byte
                encoded.copyInto(out, destinationOffset = 1)
            }
        }

        /**
         * Attempt to decode [bytes] as a relay frame.
         *
         * Returns `null` if [bytes] does not claim [RoomFramePrefix.Relay], or if the body is
         * malformed. Never throws: this decodes attacker-controlled wire data on the inbound path,
         * and a throw there would kill the room's single inbound collector.
         */
        fun decode(bytes: ByteArray): RelayEnvelope? {
            if (!isRelayFrame(bytes)) return null
            return runCatchingCancellable {
                cbor.decodeFromByteArray<RelayEnvelope>(bytes.copyOfRange(1, bytes.size))
            }.getOrNull()
        }

        /** Whether [bytes] claims the relay prefix. Does not decode. */
        fun isRelayFrame(bytes: ByteArray): Boolean = RoomFramePrefix.Relay.matches(bytes)
    }
}
