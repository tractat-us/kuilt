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
     * Emitted for non-host peers whose [us.tractat.kuilt.liveness.PartitionEvent.PeerLost]
     * fires on the host or on any other room participant — i.e. genuine liveness-detected
     * loss: the peer vanished without a clean
     * [us.tractat.kuilt.session.admit.AdmitMessage.Goodbye] (crash, dead transport) and its
     * reconnect window elapsed.
     *
     * A **clean** leave never carries this reason: the departing peer's Goodbye reaches the
     * host, which propagates an authoritative
     * [us.tractat.kuilt.session.admit.AdmitMessage.Farewell] to every remaining member, so
     * all of them evict promptly with [Normal] (#1292).
     */
    public data object PartitionExpired : LeaveReason
}
