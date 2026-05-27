package us.tractat.kuilt.session

/** Why a peer is leaving a room. */
public sealed interface LeaveReason {
    /** Normal, intentional departure. */
    public data object Normal : LeaveReason

    /** Departure due to an error. */
    public data class Error(val message: String) : LeaveReason
}
