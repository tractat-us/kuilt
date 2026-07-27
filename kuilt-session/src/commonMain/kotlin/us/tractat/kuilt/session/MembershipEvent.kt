package us.tractat.kuilt.session

import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.session.admit.RejectCode
import kotlin.time.Instant

/**
 * A frame received from an admitted [Member], tagged with their [PeerId].
 *
 * Frames from unadmitted peers are dropped before reaching [Room.incoming].
 */
public data class RoomFrame(
    val sender: PeerId,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RoomFrame) return false
        return sender == other.sender && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = sender.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

/**
 * Events emitted by [Room] describing changes to member membership and liveness.
 *
 * All events that carry timestamps use [kotlin.time.Instant] sourced from the
 * injected clock — never [kotlin.time.Clock.System.now()] directly.
 */
public sealed interface MembershipEvent {
    /** A new peer completed the admit handshake and entered the roster. */
    public data class Joined(val member: Member) : MembershipEvent

    /** A peer left the room (clean leave or transport disconnect after admission). */
    public data class Left(val peerId: PeerId, val reason: LeaveReason) : MembershipEvent

    /**
     * A peer's transport link dropped; a reconnect window may be open.
     *
     * **Dual-role.** Emitted on the **host's** events when an admitted joiner goes unresponsive
     * (driven by [us.tractat.kuilt.session.partition.PartitionEvent.PeerUnresponsive], with the
     * member's [Liveness] transitioning to [Liveness.Partitioned]); and on a **joiner's** events
     * when its host link tears and the joiner begins an in-window resume attempt (#1037). Either
     * way [peerId] identifies the peer whose link dropped (the joiner, or the host, respectively).
     *
     * [reason] classifies why the link is down (silent timeout / backpressure / transport close),
     * so a consumer can tailor its "reconnecting…" surface — see [ReconnectReason].
     */
    public data class Partitioned(
        val peerId: PeerId,
        val at: Instant,
        val reason: ReconnectReason,
        /**
         * This peer's own [Room.localFabric] at the instant this event was emitted.
         *
         * **Precedence.** When this is [FabricAvailability.Unavailable], this event is **not
         * evidence about [peerId]** — our own end of the fabric was down, so their silence says
         * nothing about them. Read it off the event rather than correlating two streams by
         * timestamp (#1712).
         *
         * That inference holds where *we* observed the silence — our own detection or our own link
         * tear. It does **not** hold for a host-relayed pause: that report is host-authoritative and
         * arrived over a link working well enough to deliver it, so an `Unavailable` tag there says
         * only that our own end was down when we processed the report, not that the report is
         * unfounded. Either way [Room.roster]'s liveness stays authoritative.
         *
         * [FabricAvailability.Unknown] means the fabric has no path observer, so precedence cannot
         * be determined — treat it as "no information", not as "we were fine". It is the **normal**
         * value on every fabric without a live OS path observer (see
         * `SeamCapabilities.reportsLiveCapability`), so a consumer must handle it as a first-class
         * third answer rather than a gap.
         *
         * **Best-effort on a bonded multi-transport room** (#1778). Over a `CompositeSeam` the
         * rolled-up availability is an eventually-consistent fold of what each transport last
         * announced, so when *every* transport drops within one dispatch window this tag can capture
         * a briefly-stale value. It converges immediately after — but an event is a snapshot, so the
         * captured value does not. A single-transport room has no such fold and no such window.
         * [Room.localFabric] and [Room.roster] are the authoritative surfaces: re-read
         * [Room.localFabric] at handling time if a decision must be certain.
         */
        val localFabric: FabricAvailability,
    ) : MembershipEvent

    /**
     * A partitioned peer's link recovered before the window expired.
     *
     * The member's [Liveness] transitions back to [Liveness.Connected].
     * Driven by [us.tractat.kuilt.session.partition.PartitionEvent.PeerRecovered].
     */
    public data class Recovered(val peerId: PeerId, val at: Instant) : MembershipEvent

    /**
     * A reconnect window opened for a peer whose link dropped.
     *
     * **Dual-role.** Emitted on the **host's** events when a joiner goes unresponsive and the
     * host opens a window to admit its `resume` ([peerId] = the joiner; if the joiner resumes
     * before [expiresAt], [Resumed] follows, otherwise [Left] with [LeaveReason.PartitionExpired]);
     * and on a **joiner's** events when its host link tears and the joiner opens its own window to
     * re-weave and resume ([peerId] = the host; on success [Resumed] follows, otherwise [HostLost]).
     * [expiresAt] is the wall-clock instant at which the window closes.
     */
    public data class WindowOpened(val peerId: PeerId, val expiresAt: Instant) : MembershipEvent

    /**
     * A partitioned joiner successfully resumed via [Room.resume].
     *
     * Emitted on the **host's** events when the joiner's [us.tractat.kuilt.session.partition.ResumeToken]
     * validated and the reconnect window was still open. Also emitted on the **joiner's** events
     * to confirm local state recovery.
     */
    public data class Resumed(val peerId: PeerId) : MembershipEvent

    /**
     * The host's transport link was permanently lost (joiner perspective only).
     *
     * Terminal state — no further events follow. [Room.broadcast] and [Room.sendTo]
     * become silent no-ops after this event.
     *
     * Driven by [us.tractat.kuilt.session.partition.PartitionEvent.PeerLost] for the
     * host peer. The room does not auto-elect a new host.
     *
     * [reason] classifies the terminal failure (window expired / refused / unrecoverable) — see
     * [FailureReason], the post-admission analogue of [AdmissionFailure] on [AdmissionFailed].
     */
    public data class HostLost(
        val at: Instant,
        val reason: FailureReason,
        /**
         * This peer's own [Room.localFabric] at the instant this event was emitted.
         *
         * **Precedence.** When this is [FabricAvailability.Unavailable], this event is **not
         * evidence about the host** — our own end of the fabric was down, so its silence says
         * nothing about it. This is the highest-value site for the distinction: a joiner whose own
         * radio died would otherwise render "the host is gone" (#1712). Read it off the event
         * rather than correlating two streams by timestamp.
         *
         * [FabricAvailability.Unknown] means the fabric has no path observer, so precedence cannot
         * be determined — treat it as "no information", not as "we were fine". It is the **normal**
         * value on every fabric without a live OS path observer (see
         * `SeamCapabilities.reportsLiveCapability`), so a consumer must handle it as a first-class
         * third answer rather than a gap.
         *
         * **Best-effort on a bonded multi-transport room** (#1778). Over a `CompositeSeam` the
         * rolled-up availability is an eventually-consistent fold of what each transport last
         * announced, so when *every* transport drops within one dispatch window this tag can capture
         * a briefly-stale value. It converges immediately after — but an event is a snapshot, so the
         * captured value does not. A single-transport room has no such fold and no such window.
         * [Room.localFabric] and [Room.roster] are the authoritative surfaces: re-read
         * [Room.localFabric] at handling time if a decision must be certain.
         */
        val localFabric: FabricAvailability,
    ) : MembershipEvent

    /**
     * **This peer's own end** of the fabric carrying this room can no longer carry frames.
     *
     * Self-attributed and **session-scoped** — it says nothing about the device as a whole. A peer
     * in two rooms over two fabrics gets this independently per room, and neither speaks for the
     * other; a room over a bonded `CompositeSeam` emits it only when every woven ply is down.
     *
     * Emitted only on a transition **into** [us.tractat.kuilt.core.FabricAvailability.Unavailable].
     * A move into [us.tractat.kuilt.core.FabricAvailability.Unknown] emits nothing — "we stopped
     * being able to tell" is not a loss. Read [Room.localFabric] for the authoritative level; this
     * is the notification. [reason] is the transport's own words.
     */
    public data class LocalFabricLost(val at: Instant, val reason: String) : MembershipEvent

    /**
     * This peer's own end of the room's fabric can carry frames again.
     *
     * Emitted on a transition into [us.tractat.kuilt.core.FabricAvailability.Available] when the
     * last decided state was [us.tractat.kuilt.core.FabricAvailability.Unavailable] — including
     * when the path passed through [us.tractat.kuilt.core.FabricAvailability.Unknown] on the way
     * back. Never emitted for a first-ever `Available`: nothing was lost.
     *
     * **A room whose fabric was already `Unavailable` when it was constructed emits this with no
     * preceding [LocalFabricLost].** That is deliberate, not a gap: [Room.localFabric] carried the
     * initial `Unavailable` from the start, so the consumer was never misinformed — there was simply
     * no transition to announce. Consumers must not treat a `Lost` as a precondition for a `Restored`.
     */
    public data class LocalFabricRestored(val at: Instant) : MembershipEvent

    /**
     * The joiner's admit handshake failed terminally — it never entered a roster
     * (joiner perspective only).
     *
     * Terminal state — no further events follow. [Room.broadcast] and [Room.sendTo]
     * become silent no-ops after this event.
     *
     * Distinct from [HostLost]: [HostLost] means an *already-admitted* joiner lost its host;
     * [AdmissionFailed] means admission never completed at all. Emitting it is the loud
     * alternative to the pre-#1178 silent hang — without it, a dropped or refused `Hello`
     * left the consumer waiting on [Room.roster] forever with no signal.
     *
     * [reason] separates an active host refusal from silence — see [AdmissionFailure].
     */
    public data class AdmissionFailed(val reason: AdmissionFailure, val at: Instant) : MembershipEvent
}

/**
 * Why a joiner's admit handshake failed terminally. See [MembershipEvent.AdmissionFailed].
 */
public sealed interface AdmissionFailure {
    /**
     * The host actively refused admission with an [us.tractat.kuilt.session.admit.AdmitMessage.Reject]
     * — for example the #1172 room-mismatch gate, which arrives as
     * [us.tractat.kuilt.session.admit.RejectCode.RoomMismatch]. [message] is the host's stated
     * reason (for logs); [code] is its structured counterpart, and is what to branch on. A host
     * that predates typed codes surfaces [us.tractat.kuilt.session.admit.RejectCode.Unknown].
     * Retrying the same request is futile; the joiner needs to change something (e.g. its target room).
     */
    public data class Rejected(
        val message: String,
        val code: RejectCode = RejectCode.Unspecified,
    ) : AdmissionFailure

    /**
     * No [us.tractat.kuilt.session.admit.AdmitMessage.Welcome] arrived within the admit deadline —
     * the host may be absent, or the joiner's `Hello` was dropped. Unlike [Rejected] this is silence,
     * not a refusal, so a later retry may succeed.
     */
    public data object TimedOut : AdmissionFailure
}
