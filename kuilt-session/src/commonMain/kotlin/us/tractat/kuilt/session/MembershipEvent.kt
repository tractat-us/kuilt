package us.tractat.kuilt.session

import us.tractat.kuilt.core.PeerId

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
 * Events defined here but driven in later slices (1C/1D) are present as stubs —
 * they carry enough structure for consumers to compile against but the [Room]
 * implementation only emits [Joined] and [Left] in 1B.
 *
 * TODO(1C): [HostLost] driven by host-election + [us.tractat.kuilt.session.partition.PartitionEvent].
 * TODO(1C): [Partitioned] and [Recovered] driven by [us.tractat.kuilt.session.partition.PartitionEvent].
 * TODO(1D): [Resumed] driven by [us.tractat.kuilt.session.partition.JoinerReconnectController].
 */
public sealed interface MembershipEvent {
    /** A new peer completed the admit handshake and entered the roster. */
    public data class Joined(val member: Member) : MembershipEvent

    /** A peer left the room (clean leave or transport disconnect after admission). */
    public data class Left(val peerId: PeerId, val reason: LeaveReason) : MembershipEvent

    /**
     * An admitted peer's transport link dropped; reconnect window may be open.
     * Stub in 1B — fully driven in 1C via [us.tractat.kuilt.session.partition.PartitionEvent].
     */
    public data class Partitioned(val peerId: PeerId) : MembershipEvent

    /**
     * A partitioned peer's link recovered before the window expired.
     * Stub in 1B — fully driven in 1C via [us.tractat.kuilt.session.partition.PartitionEvent].
     */
    public data class Recovered(val peerId: PeerId) : MembershipEvent

    /**
     * A peer resumed from a [us.tractat.kuilt.session.partition.ResumeToken].
     * Stub in 1B — fully wired in 1D.
     */
    public data class Resumed(val peerId: PeerId) : MembershipEvent

    /**
     * The host's transport link was permanently lost.
     * Terminal state — no further events follow.
     * Stub in 1B — fully driven in 1C.
     */
    public data object HostLost : MembershipEvent
}
