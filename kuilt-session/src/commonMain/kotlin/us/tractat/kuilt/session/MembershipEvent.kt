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
     */
    public data class Partitioned(val peerId: PeerId, val at: Instant) : MembershipEvent

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
     */
    public data class HostLost(val at: Instant) : MembershipEvent
}
