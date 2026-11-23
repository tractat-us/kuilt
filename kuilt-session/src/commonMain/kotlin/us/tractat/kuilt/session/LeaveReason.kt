package us.tractat.kuilt.session

import us.tractat.kuilt.core.PeerId

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

    /**
     * The same device came back under a new peer id, [by], and took this member's held seat over
     * (#3159). The old member's link was down: a seat whose member is connected is never superseded,
     * so live tabs of one browser keep a seat each.
     *
     * Everyone in the room sees one `Left(old, Superseded(by))` followed by one `Joined(by)`, and the
     * new member keeps the old one's identity and device key. The superseded room itself, if it is
     * still running, sees `Left(self, Superseded(by))` and then ends: it never says Hello again, so
     * two processes of one device cannot take a seat back and forth.
     */
    public data class Superseded(val by: PeerId) : LeaveReason
}
