package us.tractat.kuilt.session

import us.tractat.kuilt.core.PeerId
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
     * [reason] says *why* the link is down, at the granularity kuilt can honestly observe —
     * enough to phrase a reconnect banner. It is never terminal: a window is open. The
     * terminal counterpart is [HostLost]'s [FailureReason].
     */
    public data class Partitioned(
        val peerId: PeerId,
        val at: Instant,
        val reason: ReconnectReason,
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
     * [reason] says whether anything could have helped — the window merely elapsed
     * ([FailureReason.WindowExpired]), the host actively refused a resume
     * ([FailureReason.Refused]), or no resume path existed at all
     * ([FailureReason.Unrecoverable]). It is the "retry or give up for good" signal.
     */
    public data class HostLost(val at: Instant, val reason: FailureReason) : MembershipEvent

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
     * — for example the #1172 room-mismatch gate. [message] is the host's stated reason.
     * Retrying the same request is futile; the joiner needs to change something (e.g. its target room).
     */
    public data class Rejected(val message: String) : AdmissionFailure

    /**
     * No [us.tractat.kuilt.session.admit.AdmitMessage.Welcome] arrived within the admit deadline —
     * the host may be absent, or the joiner's `Hello` was dropped. Unlike [Rejected] this is silence,
     * not a refusal, so a later retry may succeed.
     */
    public data object TimedOut : AdmissionFailure
}
