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
 * The [Partitioned], [Recovered], and [HostLost] events carry [at: Instant] timestamps
 * sourced from the injected clock — never [kotlin.time.Clock.System.now()].
 *
 * TODO(1D): [Resumed] driven by [us.tractat.kuilt.session.partition.JoinerReconnectController].
 */
public sealed interface MembershipEvent {
    /** A new peer completed the admit handshake and entered the roster. */
    public data class Joined(val member: Member) : MembershipEvent

    /** A peer left the room (clean leave or transport disconnect after admission). */
    public data class Left(val peerId: PeerId, val reason: LeaveReason) : MembershipEvent

    /**
     * An admitted peer's transport link dropped; reconnect window may be open.
     *
     * The member's [Liveness] transitions to [Liveness.Partitioned].
     * Driven by [us.tractat.kuilt.session.partition.PartitionEvent.PeerUnresponsive].
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
     * A peer resumed from a [us.tractat.kuilt.session.partition.ResumeToken].
     * TODO(1D): fully wired in 1D via [us.tractat.kuilt.session.partition.JoinerReconnectController].
     */
    public data class Resumed(val peerId: PeerId) : MembershipEvent

    /**
     * The host's transport link was permanently lost (joiner perspective only).
     *
     * Terminal state — no further events follow. [Room.broadcast] and [Room.sendTo]
     * become silent no-ops after this event.
     *
     * Driven by [us.tractat.kuilt.session.partition.PartitionEvent.PeerLost] for the
     * host peer. No auto-election (D-010): the room does not promote a new host.
     */
    public data class HostLost(val at: Instant) : MembershipEvent
}
