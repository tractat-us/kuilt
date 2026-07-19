package us.tractat.kuilt.session

import us.tractat.kuilt.liveness.PartitionEvent

/**
 * Why a peer's link is currently down and a reconnect / grace window is in progress.
 *
 * Attached to [MembershipEvent.Partitioned]. A consumer driving a "reconnecting…" banner
 * uses this to distinguish a silent Wi-Fi drop ([LinkTimeout]) from a peer that stopped
 * reading ([Backpressure]) from a clean transport close ([TransportClosed]).
 *
 * Session-level counterpart of [PartitionEvent.Reason]: the joiner-side [MembershipEvent.Partitioned]
 * (host-tear) does not originate from a [PartitionEvent], so the public session vocabulary owns its
 * own type rather than leaking the lower-level liveness enum.
 */
public sealed interface ReconnectReason {
    /** No heartbeat within `HeartbeatConfig.timeout` — a silent drop. */
    public data object LinkTimeout : ReconnectReason

    /** The per-peer outbound buffer exceeded its configured ceiling. */
    public data object Backpressure : ReconnectReason

    /** The underlying `Seam` was closed or torn. */
    public data object TransportClosed : ReconnectReason
}

/**
 * Why a joiner's session terminally failed. Attached to [MembershipEvent.HostLost].
 *
 * The post-admission analogue of [AdmissionFailure] (which classifies pre-admission
 * failures on [MembershipEvent.AdmissionFailed]).
 */
public sealed interface FailureReason {
    /** The reconnect window elapsed without a successful resume. */
    public data object WindowExpired : FailureReason

    /**
     * The host actively rejected the resume with an `AdmitMessage.Reject`, carrying its raw
     * [message]. kuilt cannot type the host's intent (auth-expired, protocol-mismatch, …) —
     * the admit protocol carries only a free-form string — so those surface here and the
     * consumer parses semantics from [message]. Retrying the same token is futile.
     */
    public data class Refused(public val message: String) : FailureReason

    /** No resume path exists: no reweave support, a non-conforming loom, or no known host. */
    public data object Unrecoverable : FailureReason
}

/** Lift a liveness-layer [PartitionEvent.Reason] to the session-level [ReconnectReason]. */
internal fun PartitionEvent.Reason.toReconnectReason(): ReconnectReason = when (this) {
    PartitionEvent.Reason.Timeout -> ReconnectReason.LinkTimeout
    PartitionEvent.Reason.Backpressure -> ReconnectReason.Backpressure
    PartitionEvent.Reason.TransportClosed -> ReconnectReason.TransportClosed
}
