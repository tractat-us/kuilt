package us.tractat.kuilt.session

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.PayloadTooLarge
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
 * "channel"), reserved by [RoomFramePrefix.Channel] — the registry that owns the
 * whole room frame-prefix byte space (#2007). It is distinct from every other
 * claimed byte by construction.
 *
 * This KDoc previously claimed `0x63` is safe because it sits "outside the CBOR
 * major-type-7 range (`0xe0`–`0xff`) used by serialization". **That was false.**
 * CBOR text-string headers are `0x60 or len`, so a bare 3-character CBOR string
 * begins `0x63` — and the same is true of every other prefix in the registry. The
 * real collision band is `0x60..0x7f`; see [RoomFramePrefix] for the full table.
 * The codebase lives with it because room payloads are framed, not bare.
 *
 * Applications **must not** emit raw payloads starting with `0x63` via [Room.broadcast]
 * or [Room.sendTo] — that byte is reserved for channel framing. An application frame of
 * 3 bytes or more starting with `0x63` is misclassified as a channel frame and routed
 * (or silently dropped); a 1- or 2-byte one survives, on the strength of the classifier's
 * length test alone. Do not build on that. [Room.broadcast] carries the whole reserved
 * byte space and which of it is conditional.
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
     * Value: `0x63` (ASCII 'c' for "channel"). The class-level documentation
     * describes the byte space this belongs to — note that the registry asserts
     * **distinctness** between frame families and explicitly cannot assert safety
     * against an application payload that happens to lead with the same byte.
     */
    public val CHANNEL_PREFIX: Byte = RoomFramePrefix.Channel.byte

    /**
     * Bytes every channel frame spends on its header: [CHANNEL_PREFIX] plus the 2-byte sub-id.
     *
     * Doubles as the classifier's minimum length — a payload shorter than a whole header cannot be
     * a channel frame — and as what a channel view holds back from the room's own payload budget
     * (#2047).
     */
    internal const val HEADER_BYTES: Int = 3

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
        bytes.size >= HEADER_BYTES && bytes[0] == CHANNEL_PREFIX

    /** Returns `true` if [swatch] carries a channel frame. Does not allocate. */
    internal fun isChannelFrame(swatch: Swatch): Boolean =
        swatch.payloadSize >= HEADER_BYTES && swatch.byteAt(0) == CHANNEL_PREFIX

    /** Extracts the sub-id from a channel frame (bytes 1–2). Requires [isChannelFrame]. */
    internal fun subIdOf(bytes: ByteArray): Short =
        ((bytes[1].toInt() and 0xFF) shl 8 or (bytes[2].toInt() and 0xFF)).toShort()

    /** Extracts the sub-id from a channel frame (bytes 1–2). Does not allocate. */
    internal fun subIdOf(swatch: Swatch): Short =
        ((swatch.byteAt(1).toInt() and 0xFF) shl 8 or (swatch.byteAt(2).toInt() and 0xFF)).toShort()

    /** Wraps [payload] in channel framing for sub-id [subId]. */
    internal fun frame(subId: Short, payload: ByteArray): ByteArray {
        val out = ByteArray(payload.size + HEADER_BYTES)
        out[0] = CHANNEL_PREFIX
        out[1] = (subId.toInt() ushr 8).toByte()
        out[2] = subId.toByte()
        payload.copyInto(out, destinationOffset = HEADER_BYTES)
        return out
    }

    /** Strips the 3-byte channel header from [swatch], returning the payload-only [Swatch]. */
    internal fun stripped(swatch: Swatch): Swatch = swatch.dropFirst(HEADER_BYTES)
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
 * ## The upstream is a merge of two inbound streams
 *
 * [sharedRaw] is `SeamRoom`'s direct inbound stream **merged with** its relayed one (#1994) — a
 * `Flow`, not a `SharedFlow`, purely because `merge` returns one; the only operations applied are
 * `filter`/`map`, so nothing here depended on the narrower type. The split upstream is deliberate
 * and asymmetric: a channel view must see a co-spoke's host-relayed frames, while the per-peer
 * liveness detectors (which collect the direct stream alone) must **not** — a detector treats any
 * inbound frame as proof of liveness, so feeding it relayed data would mask a dead direct edge.
 * Data is relayed; liveness is not.
 *
 * Both underlying streams are hot `SharedFlow`s started eagerly, so frames emitted before
 * [incoming] is first collected are not held in a buffer. Late subscribers use `replay = 0` and may
 * miss frames; this is safe for [us.tractat.kuilt.quilter.Quilter] (gaps heal via FullState +
 * resend). Those `replay = 0` semantics are unchanged by the merge.
 *
 * Construction should go through [SeamRoom.channel], which caches instances by id.
 */
internal class RoomChannelSeam(
    private val room: SeamRoom,
    private val subId: Short,
    sharedRaw: Flow<Swatch>,
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
     * The room's own budget less this view's [RoomChannel.HEADER_BYTES] framing (#2047).
     *
     * A channel view is a [Seam] over a [Room], and both layers add bytes on the way down: the room
     * may wrap the frame in a relay envelope, and this view has already prefixed it with a channel
     * header. Each subtracts its own cost, so the number a `Quilter` over this view reads is one it
     * can actually fill.
     */
    override val maxPayloadBytes: Int?
        get() = room.maxPayloadBytes?.let { (it - RoomChannel.HEADER_BYTES).coerceAtLeast(0) }

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

    /**
     * Checked against [maxPayloadBytes] here rather than left to [room], so the error names the
     * bytes the *caller* passed. Delegating would report the framed size against the room's budget
     * — both numbers three larger than the pair this view published, for no reason the caller could
     * see.
     */
    override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
        val budget = maxPayloadBytes
        if (budget != null && payload.size > budget) {
            throw PayloadTooLarge(payload.size, budget, RELAY_ENVELOPE_BUDGET + RoomChannel.HEADER_BYTES)
        }
        room.sendTo(peer, RoomChannel.frame(subId, payload))
    }

    /** No-op — the Room owns the lifecycle. */
    override suspend fun close(reason: CloseReason) = Unit
}
