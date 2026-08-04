package us.tractat.kuilt.session.election

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.session.RoomFramePrefix

/**
 * Wire messages for the host-election freeze round (#1439). Sent over the raw lobby [seam] BEFORE any
 * [us.tractat.kuilt.session.Room] adopts it, so the framing is parallel to — and prefix-disjoint from —
 * [us.tractat.kuilt.session.admit.AdmitMessage].
 *
 * Freeze round: `Freeze` (host→all) → `FreezeAck` (each member→host) → `Commit` (host→all, adopt now)
 * or `Reopen` (host→all, abort). See `docs/host-election-design.md`.
 */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
public sealed interface LobbyMessage {
    /** Host→all: "closing the lobby; [roster] are the members; ack if I'm your elected host." */
    @Serializable
    @SerialName("freeze")
    public data class Freeze(val hostId: String, val roster: Set<String>, val epoch: Long) : LobbyMessage

    /** Member→host: "agreed you're host, I'm ready." */
    @Serializable
    @SerialName("freeze-ack")
    public data class FreezeAck(val hostId: String, val epoch: Long) : LobbyMessage

    /** Host→all: "every member acked — adopt now." */
    @Serializable
    @SerialName("commit")
    public data class Commit(val hostId: String, val epoch: Long) : LobbyMessage

    /** Host→all: "freeze aborted — back to the lobby." */
    @Serializable
    @SerialName("reopen")
    public data class Reopen(val epoch: Long) : LobbyMessage

    public companion object {
        /** First byte of every lobby frame. Reserved by [RoomFramePrefix.Lobby] (#2007). */
        public val PREFIX_BYTE: Byte = RoomFramePrefix.Lobby.byte

        @OptIn(ExperimentalSerializationApi::class)
        private val cbor = Cbor { ignoreUnknownKeys = true }

        /** Encode a [LobbyMessage] to bytes with the [PREFIX_BYTE] framing prefix. */
        @OptIn(ExperimentalSerializationApi::class)
        public fun encode(message: LobbyMessage): ByteArray {
            val encoded = cbor.encodeToByteArray(message)
            return ByteArray(encoded.size + 1).also { out ->
                out[0] = PREFIX_BYTE
                encoded.copyInto(out, destinationOffset = 1)
            }
        }

        /** Decode bytes as a [LobbyMessage]; null if the prefix mismatches or decoding fails. */
        @OptIn(ExperimentalSerializationApi::class)
        public fun decode(bytes: ByteArray): LobbyMessage? {
            if (bytes.isEmpty() || bytes[0] != PREFIX_BYTE) return null
            return runCatchingCancellable {
                cbor.decodeFromByteArray<LobbyMessage>(bytes.copyOfRange(1, bytes.size))
            }.getOrNull()
        }

        /** True if [bytes] looks like a lobby frame (starts with [PREFIX_BYTE]). */
        public fun isLobbyFrame(bytes: ByteArray): Boolean = bytes.isNotEmpty() && bytes[0] == PREFIX_BYTE
    }
}
