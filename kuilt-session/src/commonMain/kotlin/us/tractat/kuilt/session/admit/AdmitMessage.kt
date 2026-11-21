package us.tractat.kuilt.session.admit

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.identity.DidKey
import us.tractat.kuilt.identity.IdentityInternalApi
import us.tractat.kuilt.session.RoomFramePrefix
import us.tractat.kuilt.identity.Challenge as ChallengeStatement
import us.tractat.kuilt.session.partition.ResumeSecret

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
 *
 * Since protocol v3 (#3210) every [Hello] and [Welcome] names a member's device key, and the wire
 * has the [Challenge] and [Proof] frames of the key-proving exchange. Nothing sends those two yet,
 * and admission does not depend on the key.
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
     * [key] — the joiner's device key, from its [us.tractat.kuilt.identity.DeviceKeyStore] (v3, #3210).
     *   In this version it is the joiner's claim: nothing yet checks that the joiner holds the key.
     * [joinerNonce] — [JOINER_NONCE_SIZE] fresh random bytes, drawn for every Hello (v3, #3210). The
     *   host's signed Challenge will echo it, which is what makes each answer single-use. Its width is
     *   checked here, in the constructor, so a wrong-width nonce is refused on every decode path and a
     *   [decode] of one returns `null`.
     *
     * **[key] and [joinerNonce] are nullable on the wire, though a v3 Hello must carry both.** A v2
     * Hello has neither. Were they required, a v2 Hello would fail to decode, [decode] would drop it as
     * malformed, and the version gate — which needs a decoded Hello to read [protocolVersion] from —
     * would never answer: the v2 joiner would wait out its admit deadline instead of hearing
     * [RejectCode.ProtocolMismatch]. So the requirement is the host's, enforced next to the version
     * gate, and a v3 Hello missing either is refused with that same code.
     */
    @Serializable
    @SerialName("hello")
    public data class Hello(
        val displayName: String,
        val sessionId: String,
        val deviceId: String? = null,
        val targetRoom: String? = null,
        val protocolVersion: Int? = null,
        val key: DidKey? = null,
        val joinerNonce: ByteArray? = null,
    ) : AdmitMessage {
        init {
            require(joinerNonce == null || joinerNonce.size == JOINER_NONCE_SIZE) {
                "the joiner nonce is ${joinerNonce?.size} bytes, not $JOINER_NONCE_SIZE"
            }
        }

        /** Compares [joinerNonce] by content: a decoded Hello equals the one that was encoded. */
        override fun equals(other: Any?): Boolean =
            other is Hello &&
                displayName == other.displayName &&
                sessionId == other.sessionId &&
                deviceId == other.deviceId &&
                targetRoom == other.targetRoom &&
                protocolVersion == other.protocolVersion &&
                key == other.key &&
                joinerNonce.contentEquals(other.joinerNonce)

        override fun hashCode(): Int {
            var result = displayName.hashCode()
            result = 31 * result + sessionId.hashCode()
            result = 31 * result + (deviceId?.hashCode() ?: 0)
            result = 31 * result + (targetRoom?.hashCode() ?: 0)
            result = 31 * result + (protocolVersion ?: 0)
            result = 31 * result + (key?.hashCode() ?: 0)
            result = 31 * result + joinerNonce.contentHashCode()
            return result
        }

        public companion object {
            /**
             * Width of [joinerNonce], in bytes: the width the signed Challenge requires of the nonce it
             * echoes, so a Hello this constructor accepts is always one a host can challenge.
             */
            @OptIn(IdentityInternalApi::class)
            public const val JOINER_NONCE_SIZE: Int = ChallengeStatement.NONCE_SIZE
        }
    }

    /**
     * Sent by the host to confirm admission.
     *
     * Also broadcast to all other already-admitted members so they can update their rosters.
     *
     * [assignedPeerId] — the new member's [us.tractat.kuilt.core.PeerId] value.
     * [identity] — the identity the new member declared in their [Hello].
     * [roomId] — the host's stable room identifier; null in bootstrap welcomes for existing members.
     *   Joiners use this to mint their [us.tractat.kuilt.session.partition.ResumeToken].
     * [resumeSecret] — the secret the host minted for [assignedPeerId], present **only** on the
     *   direct Welcome to that member (#3165). The roster copy every other member receives, and the
     *   bootstrap and host-intro Welcomes, carry `null`: a bystander holding it could resume as that
     *   member. Additive — nullable and defaulted, so an older joiner ignores it and a secretless
     *   Welcome encodes byte-identically to the pre-#3165 form.
     * [key] — the device key of the member [assignedPeerId] names (v3, #3210), on all three shapes:
     *   the direct Welcome and its roster copy carry the joiner's key from its [Hello]; a bootstrap
     *   Welcome carries an existing member's key as the host recorded it; the host intro carries the
     *   host's own key. Every one is the host's assertion. **Required on the wire**, unlike
     *   [Hello.key]: a v2 host, the only sender of a keyless Welcome, refuses a v3 Hello before it
     *   admits anyone, so a v3 joiner never receives one.
     */
    @Serializable
    @SerialName("welcome")
    public data class Welcome(
        val assignedPeerId: String,
        val displayName: String,
        val sessionId: String,
        val key: DidKey,
        val deviceId: String? = null,
        val roomId: String? = null,
        val resumeSecret: ResumeSecret? = null,
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
     * [resumeSecret] — the secret from the joiner's own [Welcome.resumeSecret] (#3165). Nullable on
     *   the wire only so a frame from an older joiner still decodes: a host refuses a `Resume`
     *   without one as [RejectCode.ResumeTokenInvalid]. That code is not retryable, so that joiner's
     *   reconnect ends in `HostLost(Refused)`; it joins again only if its consumer reacts to that.
     */
    @Serializable
    @SerialName("resume")
    public data class Resume(
        val tokenPeerId: String,
        val tokenRoomId: String,
        val issuedAt: Long,
        val resumeSecret: ResumeSecret? = null,
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
     * reconnect window, or it resumed there — the release counterpart of [Paused] (#1557, #2908).
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

    /**
     * Sent by the host to a joiner whose [Hello] it received: a signed challenge the joiner answers
     * with a [Proof] (v3, #3210). **Nothing sends this frame yet**; the exchange that does is the next
     * step of #3159.
     *
     * [statement] is the wire form of a signed `us.tractat.kuilt.identity.Challenge` — its layout
     * followed by the host's signature — carried opaque here. The session layer never re-encodes it:
     * a statement is verified over exactly the bytes that were signed. Compared by content.
     */
    @Serializable
    @SerialName("challenge")
    public class Challenge(public val statement: ByteArray) : AdmitMessage {
        override fun equals(other: Any?): Boolean = other is Challenge && statement.contentEquals(other.statement)

        override fun hashCode(): Int = statement.contentHashCode()

        override fun toString(): String = "Challenge(statement=${statement.size} bytes)"
    }

    /**
     * Sent by a joiner to answer a [Challenge] (v3, #3210). **Nothing sends this frame yet.**
     *
     * [statement] is the wire form of a signed `us.tractat.kuilt.identity.AdmissionProof`, carried
     * opaque like [Challenge.statement]. [grants] are the wire forms of the grants this device holds
     * from the challenging host, each a signed `us.tractat.kuilt.identity.Grant`; empty when it holds
     * none, and nothing reads them yet. Compared by content, element by element.
     */
    @Serializable
    @SerialName("proof")
    public class Proof(
        public val statement: ByteArray,
        public val grants: List<ByteArray> = emptyList(),
    ) : AdmitMessage {
        override fun equals(other: Any?): Boolean =
            other is Proof &&
                statement.contentEquals(other.statement) &&
                grants.size == other.grants.size &&
                grants.indices.all { grants[it].contentEquals(other.grants[it]) }

        override fun hashCode(): Int = grants.fold(statement.contentHashCode()) { hash, grant -> 31 * hash + grant.contentHashCode() }

        override fun toString(): String = "Proof(statement=${statement.size} bytes, grants=${grants.size})"
    }

    public companion object {
        /** First byte of every admit frame. Reserved by [RoomFramePrefix.Admit] (#2007). */
        public val PREFIX_BYTE: Byte = RoomFramePrefix.Admit.byte

        /**
         * The shared CBOR codec for admit frames. `ignoreUnknownKeys = true` makes decode
         * **forward-compatible**: a frame minted by a newer build that carries an extra field
         * (a future addition to [Hello]/[Welcome]/…) still decodes on an older build instead of
         * throwing — which, given [decode]'s malformed-frame guard, would otherwise silently drop
         * the frame and hang the handshake (there is no admit timeout). Leniency affects decode
         * only; encoding output is byte-identical to the default codec, so the wire is unchanged.
         *
         * `alwaysUseByteString = true` writes every `ByteArray` — [Hello.joinerNonce], the
         * [Challenge] and [Proof] statements, each grant — as a CBOR byte string rather than an array
         * of integers (#3210). The `@ByteString` annotation cannot reach a list's elements, so the
         * setting is codec-wide. It changes no earlier frame because the codec takes the byte-string
         * path only for a field whose serial descriptor is a `ByteArray`'s, and before v3 no admit
         * message had one. The golden Hello vectors check that for the Hello alone; no other message
         * has an encoding vector.
         */
        @OptIn(ExperimentalSerializationApi::class)
        private val cbor = Cbor {
            ignoreUnknownKeys = true
            alwaysUseByteString = true
        }

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
