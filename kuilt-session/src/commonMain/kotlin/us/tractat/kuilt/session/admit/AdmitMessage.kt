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

/**
 * Wire messages that implement the admit/identify handshake.
 *
 * Framing: each [AdmitMessage] is CBOR-encoded and sent as a [us.tractat.kuilt.core.Swatch]
 * payload. The receiver detects admit frames by a discriminator prefix tag ([PREFIX_BYTE])
 * that application frames must not start with — this avoids a separate channel or
 * wrapper envelope.
 *
 * Handshake flow, in its first form:
 * ```
 * Joiner → Host : Hello(identity)
 * Host   → Joiner : Welcome(assignedId, identity)
 * Host   → all others : Welcome(assignedId, identity)   [roster broadcast]
 * ```
 * If the host refuses the joiner (a version it cannot admit, a room it does not serve, a proof that
 * fails, another device's key on a connected member's id):
 * ```
 * Host → Joiner : Reject(reason, code)
 * ```
 * The host-side runs this passively for incoming [Hello]s. The joiner
 * sends [Hello] immediately upon transport connection and waits for [Welcome] or [Reject].
 *
 * Since protocol v3 (#3210) every [Hello] and [Welcome] names a member's device key. Since v4
 * (#3211) the host admits only a joiner that proves it holds that key:
 * ```
 * Joiner → Host   : Hello(identity, key, joinerNonce)
 * Host   → Joiner : Challenge(signed by the host's key, echoing joinerNonce)
 * Joiner → Host   : Proof(signed by the joiner's key, over the challenge's hostNonce)
 * Host   → Joiner : Welcome(…)   or   Reject(BadProof)
 * ```
 *
 * Since v7 (#3215) a joiner's return after a dropped link is the same exchange: it says Hello again,
 * and there is no separate resume frame. The direct Welcome and every Reject name the Hello they
 * answer ([Welcome.answers], [Reject.answers]), and the direct Welcome names the membership it
 * admits ([Welcome.membership]). The rest of the admission burst — a Welcome per existing member,
 * then the host's intro — names the same Hello, only to say which burst it belongs to (#3615):
 * ```
 * Host   → Joiner : Welcome(…, answers = joinerNonce, membership)   or   Reject(code, answers = joinerNonce)
 * Host   → Joiner : Welcome(member…, answers = joinerNonce) …, Welcome(host, answers = joinerNonce)
 * ```
 * A roster copy to the other members names no Hello.
 */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
public sealed interface AdmitMessage {
    /**
     * Sent by a joiner to the host immediately after transport connection.
     *
     * [displayName] — human-readable display name.
     * [sessionId] — joiner-minted session identifier (UUID-style).
     * [deviceId] — the app's own id for this device, optional. The joiner's device key signs it into
     *   its [Proof] (v4, #3211), and the host records it as the member's `MemberIdentity.deviceId`. It
     *   does not recognise a returning member: the device key does, through the host's key → seat
     *   table (#3159).
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
     *   as `null` (kotlinx omits the defaulted null, so a version-less `Hello` is byte-identical to
     *   the pre-#1569 wire form). Since v2 the host refuses that `null` with
     *   [RejectCode.ProtocolMismatch] rather than admitting it as legacy ([ProtocolVersion]).
     * [key] — the joiner's device key, from its [us.tractat.kuilt.identity.DeviceKeyStore] (v3, #3210).
     *   The joiner proves it holds the key by answering the host's [Challenge] with a [Proof] (v4, #3211).
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
     * Also broadcast to all other already-admitted members so they can update their rosters. Four
     * shapes, all minted by the host:
     * - the **direct** Welcome, to the member it names, the one that answers that member's [Hello];
     * - its **roster copy**, to every other member;
     * - a **bootstrap** Welcome per existing member, to the new member;
     * - the host's own **intro**, to the new member.
     *
     * [assignedPeerId] — the [us.tractat.kuilt.core.PeerId] value of the member this Welcome names.
     * [displayName], [sessionId], [deviceId] — the identity that member was admitted with.
     * [roomId] — the host's stable room identifier, on the direct Welcome and its roster copy; null
     *   on bootstrap Welcomes and the host intro. A joiner learns `Room.roomId` from it.
     * [key] — the device key of the member [assignedPeerId] names (v3, #3210), on all four shapes:
     *   the direct Welcome and its roster copy carry the joiner's key from its [Hello]; a bootstrap
     *   Welcome carries an existing member's key as the host recorded it; the host intro carries the
     *   host's own key. Every one is the host's assertion. **Required on the wire**, unlike
     *   [Hello.key]: a v2 host, the only sender of a keyless Welcome, refuses a v3 Hello before it
     *   admits anyone, so a v3 joiner never receives one.
     * [returnKind] — set on the direct Welcome when the host restored a seat it was holding rather
     *   than admitting a new member (v5, #3212): under the same peer id, or, taking over a
     *   partitioned seat of the same key, under a new one (v6, #3213). [ReturnKind.Returning] (v9,
     *   #3222) marks a device let back in on its pass, and rides the roster copy as well, so every
     *   member hears it. `null` is an ordinary admission. It describes the host's decision; a joiner reads a restore from [membership]
     *   instead, since only that names *which* membership came back.
     * [answers] — the [Hello.joinerNonce] of the Hello this Welcome answers (v7, #3215), on the direct
     *   Welcome, the bootstrap Welcomes and the host intro; never on a roster copy. A joiner takes a
     *   direct Welcome as the answer to its Hello only when this equals the nonce of the latest Hello
     *   it sent, so a Welcome that answers an older Hello — the one before a reconnect, say — never
     *   closes the reconnect that followed it. On a bootstrap Welcome and the intro it says only which
     *   admission burst the frame belongs to (#3615): a joiner drops either when it names an older
     *   Hello, since that burst's view of the roster may be stale, and rebuilds its roster on the
     *   intro of the burst answering its latest Hello. Neither names the joiner, so neither is ever
     *   taken as the answer.
     *   Width [Hello.JOINER_NONCE_SIZE], checked in the constructor.
     * [membership] — an opaque id the host minted when it admitted this membership (v7, #3215), on
     *   the direct Welcome only. A restored seat keeps the id it had, and every new admission gets a
     *   new one. So a joiner that compares the id on the Welcome answering its reconnect with the one
     *   it already holds knows whether it came back to its own seat (the same id) or was admitted
     *   afresh because the seat was gone (any other id).
     * [grant] — the wire form of a signed [us.tractat.kuilt.identity.Grant] the host issued the
     *   member this Welcome admits (v8, #3221), on the direct Welcome only: its pass back in, which the
     *   joiner keeps in its [us.tractat.kuilt.identity.DeviceKeyStore]. The host renews it while the
     *   member stays connected ([GrantRenewal]). `null` when the host issued none: it withheld the
     *   pass from a device revoked since this membership was admitted, or it could not sign. Opaque here, like [Challenge.statement]: a grant is
     *   checked over exactly the bytes its issuer signed. Compared by content.
     * [previousPeerId] — on a Welcome marked [ReturnKind.Returning] (v9, #3222), the direct one and its
     *   roster copy: the peer id this device last held a seat under in this room, while the host
     *   still remembers it. `null` when it does not: the host restarted since, or the last pass it
     *   gave that membership has run out. `null` on every other Welcome.
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
        val returnKind: ReturnKind? = null,
        val answers: ByteArray? = null,
        val membership: String? = null,
        val grant: ByteArray? = null,
        val previousPeerId: String? = null,
    ) : AdmitMessage {
        init {
            require(answers == null || answers.size == Hello.JOINER_NONCE_SIZE) {
                "a Welcome answers a ${Hello.JOINER_NONCE_SIZE}-byte joiner nonce, not ${answers?.size} bytes"
            }
            require(previousPeerId == null || returnKind == ReturnKind.Returning) {
                "only a Welcome marking a device returning names a previous id, not one marked $returnKind"
            }
        }

        /** Compares [answers] and [grant] by content: a decoded Welcome equals the one that was encoded. */
        override fun equals(other: Any?): Boolean =
            other is Welcome &&
                assignedPeerId == other.assignedPeerId &&
                displayName == other.displayName &&
                sessionId == other.sessionId &&
                key == other.key &&
                deviceId == other.deviceId &&
                roomId == other.roomId &&
                returnKind == other.returnKind &&
                answers.contentEquals(other.answers) &&
                membership == other.membership &&
                grant.contentEquals(other.grant) &&
                previousPeerId == other.previousPeerId

        override fun hashCode(): Int {
            var result = assignedPeerId.hashCode()
            result = 31 * result + displayName.hashCode()
            result = 31 * result + sessionId.hashCode()
            result = 31 * result + key.hashCode()
            result = 31 * result + (deviceId?.hashCode() ?: 0)
            result = 31 * result + (roomId?.hashCode() ?: 0)
            result = 31 * result + (returnKind?.hashCode() ?: 0)
            result = 31 * result + answers.contentHashCode()
            result = 31 * result + (membership?.hashCode() ?: 0)
            result = 31 * result + grant.contentHashCode()
            result = 31 * result + (previousPeerId?.hashCode() ?: 0)
            return result
        }
    }

    /**
     * Sent by the host to refuse a joiner's [Hello], at the Hello gate or once its [Proof] was checked.
     *
     * [reason] is a human-readable rejection cause for debugging; [code] is its structured
     * counterpart, which is what a consumer should branch on (#1572).
     *
     * [codeId] is the wire form of [code]: nullable and defaulted, so a `Reject` carrying no code
     * decodes as [RejectCode.Unspecified], which is retryable.
     *
     * [answers] — the [Hello.joinerNonce] of the Hello this refuses (v7, #3215). A joiner acts on a
     * `Reject` only when this equals the nonce of the latest Hello it sent, so a refusal of some other
     * exchange — an impostor's, sent to the id it claimed, or a forgery — never ends this joiner's
     * admission or its reconnect. `null` only where the refused Hello carried no nonce: one that
     * predates v3, refused by the version gate. Width [Hello.JOINER_NONCE_SIZE], checked in the
     * constructor.
     *
     * [supersededBy] — the [us.tractat.kuilt.core.PeerId] value of the member that took the joiner's
     * seat over, on a [RejectCode.Superseded] refusal and on no other (v7, Decided 32, #3695). The
     * host refuses that way a Hello under a superseded id and the key that was superseded, while the
     * successor holds the seat. A new process of the device reusing the old id has no host to take a
     * [Farewell] from, so the refusal names its Hello instead, and the joiner ends
     * `Left(self, LeaveReason.Superseded(by))`. Both halves are checked in the constructor, and a
     * superseded refusal always names the Hello it answers.
     *
     * [retryable] — whether the sender's [code] said retrying could succeed (v8, Decided 25, #3276).
     * A joiner reads it only for a code it does not know, which decodes as
     * `RejectCode.Unknown(id, retryable)`: an app's own terminal refusal then stays terminal, and a
     * joiner's reconnect stops saying Hello to a host that refused it for good. A code the joiner
     * knows keeps the retryability this build gives it, whatever this says: an app's policy may
     * refuse with its own code under one of kuilt's ids, and the frame still goes and still decodes.
     * `null` when the sender stated none, which leaves an unknown code retryable.
     *
     * Construct with the typed secondary constructor: `Reject(reason, RejectCode.RoomMismatch, nonce)`,
     * or [superseded] for a [RejectCode.Superseded] refusal.
     */
    @Serializable
    @SerialName("reject")
    public data class Reject(
        val reason: String,
        @SerialName("code") val codeId: String? = null,
        val answers: ByteArray? = null,
        val supersededBy: String? = null,
        val retryable: Boolean? = null,
    ) : AdmitMessage {
        init {
            require(answers == null || answers.size == Hello.JOINER_NONCE_SIZE) {
                "a Reject answers a ${Hello.JOINER_NONCE_SIZE}-byte joiner nonce, not ${answers?.size} bytes"
            }
            require((codeId == RejectCode.Superseded.id) == (supersededBy != null)) {
                "a ${RejectCode.Superseded.id} Reject names its successor, and no other Reject names one: code $codeId, successor $supersededBy"
            }
            require(supersededBy == null || answers != null) { "a ${RejectCode.Superseded.id} Reject names the Hello it answers" }
        }

        /** A refusal for [code] of the Hello whose nonce is [answers], stating whether [code] is retryable. */
        public constructor(reason: String, code: RejectCode, answers: ByteArray?) :
            this(reason, code.id, answers, retryable = code.retryable)

        /**
         * The structured cause: a code this build knows, or [RejectCode.Unknown] with the sender's
         * [retryable] when the sender named one it does not.
         */
        public val code: RejectCode get() = RejectCode.fromWire(codeId, retryable)

        /** Compares [answers] by content: a decoded Reject equals the one that was encoded. */
        override fun equals(other: Any?): Boolean =
            other is Reject &&
                reason == other.reason &&
                codeId == other.codeId &&
                answers.contentEquals(other.answers) &&
                supersededBy == other.supersededBy &&
                retryable == other.retryable

        override fun hashCode(): Int {
            var result = reason.hashCode()
            result = 31 * result + (codeId?.hashCode() ?: 0)
            result = 31 * result + answers.contentHashCode()
            result = 31 * result + (supersededBy?.hashCode() ?: 0)
            result = 31 * result + (retryable?.hashCode() ?: 0)
            return result
        }

        public companion object {
            /**
             * The [RejectCode.Superseded] refusal of the Hello whose nonce is [answers]: that Hello's
             * id lost its seat to [successor], the [us.tractat.kuilt.core.PeerId] value of the same
             * device's newer process, which holds it now.
             */
            public fun superseded(successor: String, answers: ByteArray): Reject =
                Reject("superseded: the seat is held by $successor", RejectCode.Superseded.id, answers, successor, RejectCode.Superseded.retryable)
        }
    }

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
     * [supersededBy] — set when the departed member's held seat was taken over by the same device
     * under a new peer id (v6, #3159): the [us.tractat.kuilt.core.PeerId] value of the member that
     * took it. A member evicts with [us.tractat.kuilt.session.LeaveReason.Superseded] naming it, and
     * the roster [Welcome] for that member follows on the same lane. The host also sends this
     * Farewell **directly** to the superseded member, which then ends its room and never says Hello
     * again. `null` on every other Farewell.
     *
     * A Hello from a superseded id under the key that was superseded is **not** answered with this
     * Farewell but with a [Reject] naming that Hello, under [RejectCode.Superseded] (v7, Decided 32):
     * a joiner honours a Farewell only from its identified host, and a new process of the device
     * reusing the old id has none yet.
     *
     * Wire-compatible: an older build that doesn't know this message drops it as
     * malformed and degrades to its own heartbeat-window eviction (the prior behavior).
     */
    @Serializable
    @SerialName("farewell")
    public data class Farewell(
        val peerId: String,
        val expired: Boolean = false,
        val supersededBy: String? = null,
    ) : AdmitMessage

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
     * with a [Proof] (v3, #3210; sent since v4, #3211).
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
     * Sent by a joiner to answer a [Challenge] (v3, #3210; sent since v4, #3211).
     *
     * [statement] is the wire form of a signed `us.tractat.kuilt.identity.AdmissionProof`, carried
     * opaque like [Challenge.statement]. [grants] are the wire forms of the grants this device holds
     * from the challenging host for the room the challenge named or for every room of that host's,
     * each a signed `us.tractat.kuilt.identity.Grant`: two at most, and empty when it holds none. Sent
     * since v9 (#3222): a host that finds one good, and holds no seat for the device, lets it back in
     * without asking its admission policy. A host sets aside a grant whose fields rule it out before
     * checking any signature, and checks a few signatures at most. Compared by content, element by
     * element.
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

    /**
     * Sent by the host to a connected member: a fresh pass back in, renewing the one its direct
     * [Welcome] carried (v8, #3221).
     *
     * A pass is good for a fixed time after it was issued, and the host issues a new one every third of
     * that time while the member stays connected, so a member that drops off holds a pass good for at
     * most that time after its last contact and, provided the last renewal reached it, at least two
     * thirds of it, its term, however long ago it joined. A renewal is sent once: one lost on the way is
     * not sent again (tracked by #3711). A member the host holds as partitioned is sent nothing, and a
     * pass that never expires is never renewed. Nor is the pass of a member whose device the host
     * revoked after admitting it (Decided 14).
     *
     * [grant] is the wire form of a signed [us.tractat.kuilt.identity.Grant], opaque as on the
     * [Welcome]. A joiner keeps it only from its identified host, and only when it decodes, names that
     * host's proven key as issuer and this device's as subject, and carries the issuer's signature.
     * Compared by content.
     */
    @Serializable
    @SerialName("grant-renewal")
    public class GrantRenewal(public val grant: ByteArray) : AdmitMessage {
        override fun equals(other: Any?): Boolean = other is GrantRenewal && grant.contentEquals(other.grant)

        override fun hashCode(): Int = grant.contentHashCode()

        override fun toString(): String = "GrantRenewal(grant=${grant.size} bytes)"
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

/**
 * Why a direct [AdmitMessage.Welcome] let its member in, when it was not an ordinary admission (#3159).
 */
@Serializable
public enum class ReturnKind {
    /**
     * The host was still holding a seat under the key this joiner proved, and gave it back rather
     * than admitting a new member. Under the same peer id nobody else is told anything new, and the
     * direct Welcome carries the membership id the seat already had. Under a new one (v6, #3213) the
     * joiner took over a partitioned seat of its device, keeping that seat's identity, and every
     * other member was told the old id left, superseded, before it heard the new id join.
     *
     * It describes what the host decided, and **closes nothing on the joiner** (v7, #3215). A
     * returning joiner tells its own seat from a fresh admission by [AdmitMessage.Welcome.membership]:
     * the id it already holds means the seat came back, and any other means it is a new member.
     */
    @SerialName("restored")
    Restored,

    /**
     * The seat was gone, and the joiner was let back in without vetting on a grant the host issued
     * it (v9, #3222): a new membership the host's admission policy was not asked about. Set on the
     * direct Welcome and on its roster copy to every other member, so each of them can tell the same
     * device returning from a stranger; [AdmitMessage.Welcome.previousPeerId] names the id it last
     * held, when the host remembers it.
     */
    @SerialName("returning")
    Returning,
}
