package us.tractat.kuilt.session

/** Why a peer is leaving a room. */
public sealed interface LeaveReason {
    /** Normal, intentional departure. */
    public data object Normal : LeaveReason

    /** Departure due to an error. */
    public data class Error(val message: String) : LeaveReason

    /**
     * The peer's reconnect window expired without recovery.
     *
     * Emitted for non-host peers whose [us.tractat.kuilt.session.partition.PartitionEvent.PeerLost]
     * fires on the host or on any other room participant.
     */
    public data object PartitionExpired : LeaveReason
}
