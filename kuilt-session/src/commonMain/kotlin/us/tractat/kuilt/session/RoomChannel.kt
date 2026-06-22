package us.tractat.kuilt.session

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch

/**
 * Constants and framing helpers for [Room.channel] channel views.
 *
 * ## Reserved prefix
 *
 * Every channel frame begins with [CHANNEL_PREFIX] (`0x63`, ASCII 'c' for
 * "channel"). This value is:
 * - Distinct from the admit-protocol prefix (`0x61` / 'a').
 * - Outside the CBOR major-type-7 range (`0xe0`–`0xff`) used by serialization.
 * - Not emitted as the first byte of a heartbeat string (`kuilt.heartbeat.…`
 *   starts with `0x6b`).
 *
 * Applications **must not** emit raw payloads starting with `0x63` via [Room.broadcast]
 * or [Room.sendTo] — that byte is reserved for channel framing. Application frames
 * that happen to start with `0x63` will be misclassified as channel frames and routed
 * (or silently dropped).
 *
 * ## Wire format
 *
 * ```
 * [0x63][hi byte of subId][lo byte of subId][application payload …]
 * ```
 *
 * The 2-byte sub-id is derived from the channel name via [channelSubId]. Both peers
 * compute the same sub-id independently — no registration handshake is needed. The
 * 3-byte overhead keeps the channel-frame header small.
 */
public object RoomChannel {

    /**
     * First byte of every channel frame.
     *
     * Value: `0x63` (ASCII 'c' for "channel"). See the class-level documentation
     * for namespace-collision guarantees.
     */
    public const val CHANNEL_PREFIX: Byte = 0x63

    /**
     * Derive a 2-byte wire sub-id from a channel [name].
     *
     * The derivation is:
     * 1. Compute a stable polynomial hash of the UTF-8 bytes of [name].
     * 2. Fold the result into a [Short] (the low 16 bits of the hash).
     *
     * Collision probability is ~1/65536 per distinct pair of channel names. For
     * typical deployments (< 100 channels), the probability of any collision is
     * negligible. Colliding channels receive each other's frames; name collisions
     * are documented as application-level responsibility.
     *
     * The algorithm is **not** cryptographic — it exists solely for deterministic,
     * coordination-free tag assignment.
     */
    public fun channelSubId(name: String): Short {
        var hash = 5381
        for (byte in name.encodeToByteArray()) {
            hash = hash * 31 + (byte.toInt() and 0xFF)
        }
        return hash.toShort()
    }

    /** Returns `true` if [bytes] is a channel frame (starts with [CHANNEL_PREFIX]). */
    internal fun isChannelFrame(bytes: ByteArray): Boolean =
        bytes.size >= 3 && bytes[0] == CHANNEL_PREFIX

    /** Returns `true` if [swatch] carries a channel frame. Does not allocate. */
    internal fun isChannelFrame(swatch: Swatch): Boolean =
        swatch.payloadSize >= 3 && swatch.byteAt(0) == CHANNEL_PREFIX

    /** Extracts the sub-id from a channel frame (bytes 1–2). Requires [isChannelFrame]. */
    internal fun subIdOf(bytes: ByteArray): Short =
        ((bytes[1].toInt() and 0xFF) shl 8 or (bytes[2].toInt() and 0xFF)).toShort()

    /** Extracts the sub-id from a channel frame (bytes 1–2). Does not allocate. */
    internal fun subIdOf(swatch: Swatch): Short =
        ((swatch.byteAt(1).toInt() and 0xFF) shl 8 or (swatch.byteAt(2).toInt() and 0xFF)).toShort()

    /** Wraps [payload] in channel framing for sub-id [subId]. */
    internal fun frame(subId: Short, payload: ByteArray): ByteArray {
        val out = ByteArray(payload.size + 3)
        out[0] = CHANNEL_PREFIX
        out[1] = (subId.toInt() ushr 8).toByte()
        out[2] = subId.toByte()
        payload.copyInto(out, destinationOffset = 3)
        return out
    }

    /** Strips the 3-byte channel header from [swatch], returning the payload-only [Swatch]. */
    internal fun stripped(swatch: Swatch): Swatch = swatch.dropFirst(3)
}

/**
 * A [Seam] view over a [Room]'s underlying transport, scoped to a single channel.
 *
 * - `peers` reflects the admitted roster (+ self), keeping the admit gate intact.
 * - `incoming` delivers de-framed payloads from admitted peers on this channel only.
 * - `broadcast`/`sendTo` prepend channel framing and delegate to [room].
 * - `state` forwards the delegate seam's state.
 * - `close` is a no-op — the Room owns the lifecycle.
 *
 * The shared upstream ([sharedRaw]) is started eagerly so that frames emitted before
 * [incoming] is first collected are not held in a buffer. Late subscribers use
 * `replay = 0` and may miss frames; this is safe for [us.tractat.kuilt.quilter.Quilter]
 * (gaps heal via FullState + resend).
 *
 * Construction should go through [SeamRoom.channel], which caches instances by id.
 */
internal class RoomChannelSeam(
    private val room: SeamRoom,
    private val subId: Short,
    sharedRaw: SharedFlow<Swatch>,
) : Seam {

    override val selfId: PeerId get() = room.selfId

    /**
     * Admitted roster (+ self) as a [StateFlow] of [PeerId]s.
     *
     * Derived from [SeamRoom.rosterPeers] which maps the roster to ids and adds self.
     */
    override val peers: StateFlow<Set<PeerId>> get() = room.rosterPeers

    override val state: StateFlow<SeamState> get() = room.seamState

    /**
     * Incoming channel frames from admitted peers, payload de-framed.
     *
     * Filters [sharedRaw] to swatches whose sender is an admitted member and whose
     * payload carries this channel's sub-id, then strips the 3-byte header.
     */
    override val incoming: Flow<Swatch> = sharedRaw
        .filter { swatch ->
            RoomChannel.isChannelFrame(swatch) &&
                RoomChannel.subIdOf(swatch) == subId &&
                room.isAdmitted(swatch.sender)
        }
        .map { swatch -> RoomChannel.stripped(swatch) }

    override suspend fun broadcast(payload: ByteArray) =
        room.broadcast(RoomChannel.frame(subId, payload))

    override suspend fun sendTo(peer: PeerId, payload: ByteArray) =
        room.sendTo(peer, RoomChannel.frame(subId, payload))

    /** No-op — the Room owns the lifecycle. */
    override suspend fun close(reason: CloseReason) = Unit
}
