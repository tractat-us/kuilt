package us.tractat.kuilt.session.admit

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import us.tractat.kuilt.core.runCatchingCancellable

/**
 * Wire messages that implement the admit/identify handshake.
 *
 * Framing: each [AdmitMessage] is CBOR-encoded and sent as a [us.tractat.kuilt.core.Swatch]
 * payload. The receiver detects admit frames by a discriminator prefix tag ([PREFIX_BYTE])
 * that application frames must not start with — this avoids a separate channel or
 * wrapper envelope.
 *
 * Handshake flow:
 * ```
 * Joiner → Host : Hello(identity)
 * Host   → Joiner : Welcome(assignedId, identity)
 * Host   → all others : Welcome(assignedId, identity)   [roster broadcast]
 * ```
 * If the host rejects the joiner (e.g. already admitted with same dedupKey):
 * ```
 * Host → Joiner : Reject(reason)
 * ```
 * The host-side runs this passively for incoming [Hello]s. The joiner
 * sends [Hello] immediately upon transport connection and waits for [Welcome] or [Reject].
 */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
public sealed interface AdmitMessage {
    /**
     * Sent by a joiner to the host immediately after transport connection.
     *
     * [displayName] — human-readable display name.
     * [sessionId] — joiner-minted session identifier (UUID-style).
     * [deviceId] — optional hardware-stable identifier for reconnect dedup.
     * [targetRoom] — the host [us.tractat.kuilt.core.Pattern.roomKey] this joiner
     *   intends to enter, from [us.tractat.kuilt.core.Tag.roomKey]. `null` (the
     *   default) means the transport already bound the room, so the host admits
     *   without comparing; a non-null value that mismatches the host's own room key
     *   is rejected. Nullable + defaulted so it is wire-safe: an older build that
     *   never sends it decodes as `null` (see the shared [cbor] codec's
     *   `ignoreUnknownKeys`), and an old encoded frame with no `targetRoom` key
     *   still decodes to `null`.
     * [protocolVersion] — the admit-handshake version this joiner speaks
     *   ([ProtocolVersion.CURRENT]). The host compares it against its supported range and
     *   rejects a mismatch at admit time with [RejectCode.ProtocolMismatch] (#1569). **Additive**,
     *   exactly like [targetRoom]: nullable + defaulted, so a peer that predates the field decodes
     *   as `null` and is admitted as legacy (kotlinx omits the defaulted null, so a version-less
     *   `Hello` is byte-identical to the pre-#1569 wire form).
     */
    @Serializable
    @SerialName("hello")
    public data class Hello(
        val displayName: String,
        val sessionId: String,
        val deviceId: String? = null,
        val targetRoom: String? = null,
        val protocolVersion: Int? = null,
    ) : AdmitMessage

    /**
     * Sent by the host to confirm admission.
     *
     * Also broadcast to all other already-admitted members so they can update their rosters.
     *
     * [assignedPeerId] — the new member's [us.tractat.kuilt.core.PeerId] value.
     * [identity] — the identity the new member declared in their [Hello].
     * [roomId] — the host's stable room identifier; null in bootstrap welcomes for existing members.
     *   Joiners use this to mint their [us.tractat.kuilt.session.partition.ResumeToken].
     */
    @Serializable
    @SerialName("welcome")
    public data class Welcome(
        val assignedPeerId: String,
        val displayName: String,
        val sessionId: String,
        val deviceId: String? = null,
        val roomId: String? = null,
    ) : AdmitMessage

    /**
     * Sent by the host to reject a joiner's [Hello] or [Resume].
     *
     * [reason] is a human-readable rejection cause for debugging; [code] is its structured
     * counterpart, which is what a consumer should branch on (#1572).
     *
     * [codeId] is the wire form of [code] and is **additive**: nullable and defaulted, so a peer
     * that predates typed codes decodes as [RejectCode.Unspecified], and a `Reject` carrying no
     * code encodes to byte-identical output (kotlinx defaults are omitted from the encoding).
     * Old↔new interop therefore degrades — an unrecognised code is retryable, the pre-#1572
     * behaviour — rather than breaking.
     *
     * Construct with the typed secondary constructor: `Reject(reason, RejectCode.RoomMismatch)`.
     */
    @Serializable
    @SerialName("reject")
    public data class Reject(
        val reason: String,
        @SerialName("code") val codeId: String? = null,
    ) : AdmitMessage {
        public constructor(reason: String, code: RejectCode) : this(reason, code.id)

        /** The structured cause, or [RejectCode.Unknown] when the sender named one this build does not know. */
        public val code: RejectCode get() = RejectCode.fromId(codeId)
    }

    /**
     * Sent by a joiner to the host to resume a partitioned session.
     *
     * The host validates [tokenPeerId] + [tokenRoomId] against its
     * [us.tractat.kuilt.session.partition.JoinerReconnectController]. On success the host
     * replies with [ResumeAck]; on failure it replies with [Reject].
     *
     * [tokenPeerId] — the joiner's [us.tractat.kuilt.core.PeerId] from the original admit.
     * [tokenRoomId] — the room's stable identifier from the host's [Welcome.roomId].
     * [issuedAt] — epoch-millis when the token was issued (from the injected clock).
     */
    @Serializable
    @SerialName("resume")
    public data class Resume(
        val tokenPeerId: String,
        val tokenRoomId: String,
        val issuedAt: Long,
    ) : AdmitMessage

    /**
     * Sent by the host to confirm that a joiner's [Resume] was accepted.
     *
     * The joiner's [Room.resume] awaits this as confirmation that the
     * reconnect window was open and the token was valid.
     */
    @Serializable
    @SerialName("resume-ack")
    public data object ResumeAck : AdmitMessage

    /**
     * Sent by a joiner to the host on a deliberate [us.tractat.kuilt.session.Room.leave].
     *
     * Distinguishes a graceful leave from a bare transport close: on receipt the host
     * evicts the member immediately with [us.tractat.kuilt.session.LeaveReason.Normal],
     * rather than opening a reconnect window, and propagates a [Farewell] to all remaining
     * members so they evict promptly too. Best-effort — a lost Goodbye degrades to the
     * transport-close → reconnect-window path.
     */
    @Serializable
    @SerialName("goodbye")
    public data object Goodbye : AdmitMessage

    /**
     * Sent by the host to every remaining member when a member departs cleanly
     * (the host received that member's [Goodbye]).
     *
     * The host is the membership authority — it admits via [Welcome]; this is the
     * eviction counterpart. On receipt each member removes the departed peer promptly
     * with [us.tractat.kuilt.session.LeaveReason.Normal], instead of waiting out its
     * own heartbeat window and mislabelling the clean leave as
     * [us.tractat.kuilt.session.LeaveReason.PartitionExpired].
     *
     * **Host-authoritative, not peer-trust:** receivers act on a Farewell only when it
     * comes from their identified host — never on another joiner's raw [Goodbye] (a
     * spoof surface, and hub/relay topologies may lack direct joiner↔joiner edges).
     *
     * [peerId] — the departed member's [us.tractat.kuilt.core.PeerId] value.
     *
     * [expired] — `false` (the default) for a clean [Goodbye]-driven departure, evicted with
     * [us.tractat.kuilt.session.LeaveReason.Normal]; `true` when the host is propagating a
     * **reconnect-window expiry**, evicted with
     * [us.tractat.kuilt.session.LeaveReason.PartitionExpired]. Expiry needs the same
     * authoritative fan-out as a clean leave — on a star topology a joiner has no heartbeat
     * against another joiner, so without it an expired seat is never evicted from a peer
     * joiner's roster (#1557). Defaulted so it is wire-safe: a frame from an older build that
     * never sends the field decodes as a clean leave, which is what that build meant.
     *
     * Wire-compatible: an older build that doesn't know this message drops it as
     * malformed and degrades to its own heartbeat-window eviction (the prior behavior).
     */
    @Serializable
    @SerialName("farewell")
    public data class Farewell(val peerId: String, val expired: Boolean = false) : AdmitMessage

    /**
     * Sent by the host to every other member when a member's link drops and its seat is
     * being held open — the *presence* counterpart of [Farewell]'s eviction (#1557).
     *
     * Liveness is detected **locally**, by each peer's own heartbeat detector. On a symmetric
     * mesh that is enough: every peer watches every other and learns independently. On a
     * **star/host-relayed topology a member has no heartbeat edge to another member**, so
     * without this message it never learns that peer is paused, and
     * [us.tractat.kuilt.session.MembershipEvent] means something different depending on the
     * topology underneath — which is exactly what a consumer assumes it does not.
     *
     * On receipt a member applies [us.tractat.kuilt.session.Liveness.Partitioned] to [peerId]
     * and emits the same [us.tractat.kuilt.session.MembershipEvent.Partitioned] +
     * [us.tractat.kuilt.session.MembershipEvent.WindowOpened] pair a locally-detecting peer
     * emits. **Idempotent** — a mesh peer that both detects locally and receives this emits
     * once, not twice.
     *
     * **Host-authoritative, not peer-trust:** honored only from the identified host, exactly
     * as [Farewell] is.
     *
     * [peerId] — the paused member's [us.tractat.kuilt.core.PeerId] value.
     * [expiresAt] — epoch-millis at which the reconnect window closes; the seat is held until
     *   then, after which a [Farewell] with `expired = true` follows.
     */
    @Serializable
    @SerialName("paused")
    public data class Paused(val peerId: String, val expiresAt: Long) : AdmitMessage

    /**
     * Sent by the host to every other member when a paused member's link recovered inside its
     * reconnect window — the release counterpart of [Paused] (#1557).
     *
     * On receipt a member restores [us.tractat.kuilt.session.Liveness.Connected] for [peerId]
     * and emits [us.tractat.kuilt.session.MembershipEvent.Recovered]. **Idempotent** — a no-op
     * when the member is not currently paused. Host-authoritative, like [Paused].
     *
     * [peerId] — the recovered member's [us.tractat.kuilt.core.PeerId] value.
     */
    @Serializable
    @SerialName("unpaused")
    public data class Unpaused(val peerId: String) : AdmitMessage

    public companion object {
        /**
         * First byte of every encoded admit payload. Application frames must not
         * begin with this byte so the receiver can distinguish protocol from app frames.
         * Value: `0x61` (ASCII 'a' for "admit") — outside the CBOR major-type-7 range
         * that serialization might produce as a bare byte, and unlikely to be the first
         * byte of a legitimate non-CBOR application payload.
         */
        public const val PREFIX_BYTE: Byte = 0x61

        /**
         * The shared CBOR codec for admit frames. `ignoreUnknownKeys = true` makes decode
         * **forward-compatible**: a frame minted by a newer build that carries an extra field
         * (a future addition to [Hello]/[Welcome]/…) still decodes on an older build instead of
         * throwing — which, given [decode]'s malformed-frame guard, would otherwise silently drop
         * the frame and hang the handshake (there is no admit timeout). Leniency affects decode
         * only; encoding output is byte-identical to the default codec, so the wire is unchanged.
         */
        @OptIn(ExperimentalSerializationApi::class)
        private val cbor = Cbor { ignoreUnknownKeys = true }

        /** Encode an [AdmitMessage] to bytes with the [PREFIX_BYTE] framing prefix. */
        @OptIn(ExperimentalSerializationApi::class)
        public fun encode(message: AdmitMessage): ByteArray {
            val encoded = cbor.encodeToByteArray(message)
            return ByteArray(encoded.size + 1).also { out ->
                out[0] = PREFIX_BYTE
                encoded.copyInto(out, destinationOffset = 1)
            }
        }

        /**
         * Attempt to decode bytes as an [AdmitMessage].
         *
         * Returns null if the payload does not start with [PREFIX_BYTE] (app frame),
         * or if decoding fails (malformed). Unknown fields are tolerated (see [cbor]) so a
         * newer sender's frame decodes rather than being dropped as malformed.
         */
        @OptIn(ExperimentalSerializationApi::class)
        public fun decode(bytes: ByteArray): AdmitMessage? {
            if (bytes.isEmpty() || bytes[0] != PREFIX_BYTE) return null
            return runCatchingCancellable {
                cbor.decodeFromByteArray<AdmitMessage>(bytes.copyOfRange(1, bytes.size))
            }.getOrNull()
        }

        /** Returns true if [bytes] looks like an admit frame (starts with [PREFIX_BYTE]). */
        public fun isAdmitFrame(bytes: ByteArray): Boolean = bytes.isNotEmpty() && bytes[0] == PREFIX_BYTE
    }
}
