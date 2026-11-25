package us.tractat.kuilt.session

import us.tractat.kuilt.core.PeerId

/** Why a peer is leaving a room. */
public sealed interface LeaveReason {
    /** Normal, intentional departure. */
    public data object Normal : LeaveReason

    /** Departure due to an error. */
    public data class Error(val message: String) : LeaveReason

    /**
     * The peer's held seat ended without its membership coming back.
     *
     * Emitted for non-host peers whose [us.tractat.kuilt.liveness.PartitionEvent.PeerLost]
     * fires on the host or on any other room participant — i.e. genuine liveness-detected
     * loss: the peer vanished without a clean
     * [us.tractat.kuilt.session.admit.AdmitMessage.Goodbye] (crash, dead transport) and its
     * reconnect window elapsed.
     *
     * The host also releases a held seat with this reason, ahead of its window, in two cases. Both
     * follow a Hello under the seat's own id, and both release only once the host's
     * [us.tractat.kuilt.session.admit.AdmissionPolicy] has admitted that joiner as a new member:
     * - a newcomer under another device key, while the seat is held partitioned (Decided 18);
     * - the seat's own device, whose key was revoked after the seat was admitted, so its seat is not
     *   restored (Decided 14). That seat may be **connected**: the device's new Hello is taken as its
     *   old link being over, and the joiner joins afresh.
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
     * Every member present for the takeover sees one `Left(old, Superseded(by))` followed by one
     * `Joined(by)`, and the new member keeps the old one's identity and device key. A member whose
     * own link was down at the time hears neither: when it comes back, the host's admission burst
     * names `by`, which it hears as `Joined(by)`, and its roster rebuild later drops `old` with
     * [Unobserved], since it never saw the takeover. The superseded room itself, if it is still
     * running, sees `Left(self, Superseded(by))` and then ends: it never says Hello again, so two
     * processes of one device cannot take a seat back and forth. A new process of the device that
     * reuses the old id under the same key, while `by` holds the seat, ends the same way at its first
     * Hello, before it was ever admitted.
     */
    public data class Superseded(val by: PeerId) : LeaveReason

    /**
     * The member is gone, and this room did not see it go (#3615).
     *
     * A joiner's link to its host was down for a while, and a member left meanwhile. Its departure
     * was announced down the link that was down, so this room never heard it. When the room comes
     * back, the host's admission burst names every member it still holds, and this room drops each
     * member it holds that the burst did not name, with this reason. Emitted on a joiner's events
     * only, after [MembershipEvent.Resumed] or [MembershipEvent.Readmitted].
     *
     * It claims only what the joiner knows. [Normal] would say the member chose to leave, and
     * [PartitionExpired] that its link was watched dying; this room saw neither. The member may have
     * left cleanly, lost its link, or been superseded by the same device under a new id: treat it as
     * gone for good, and read nothing more into it. A member superseded while this room was away
     * arrives in the same burst under its new id, as a [MembershipEvent.Joined] of its own; nothing
     * here links the two ids.
     *
     * Not a reason to pass to [Room.leave]: it describes how another member left. A room given it
     * closes as it does for [PartitionExpired].
     */
    public data object Unobserved : LeaveReason
}
