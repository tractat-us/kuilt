package us.tractat.kuilt.session

import us.tractat.kuilt.liveness.PartitionEvent

/**
 * Why a peer's link is currently down while a reconnect / grace window is in progress.
 *
 * Carried by [MembershipEvent.Partitioned]. This is the "we are still trying" half of the
 * reconnect vocabulary — the terminal half is [FailureReason], carried by
 * [MembershipEvent.HostLost].
 *
 * A consumer driving a reconnect banner reads it to phrase the wait: a [LinkTimeout] is
 * "connection is slow", a [TransportClosed] is "disconnected — reconnecting", a
 * [Backpressure] is "this peer is falling behind". No variant here is terminal: a window is
 * open and a resume may still succeed.
 *
 * Deliberately **mirrors** [PartitionEvent.Reason] rather than reusing it. The joiner-side
 * `Partitioned` for a host tear does not originate from a [PartitionEvent] at all, and the
 * public session vocabulary should not leak the lower-level liveness enum.
 */
public sealed interface ReconnectReason {
    /**
     * No inbound frame — ping or application — arrived from the peer within
     * [us.tractat.kuilt.liveness.HeartbeatConfig.timeout]. The link may simply be slow;
     * the peer often recovers without ever re-weaving.
     */
    public data object LinkTimeout : ReconnectReason

    /**
     * The peer's outbound buffer went over its ceiling — the consumer called
     * [us.tractat.kuilt.liveness.PartitionDetector.onBackpressure]. The link is up, but the
     * peer is not keeping up.
     */
    public data object Backpressure : ReconnectReason

    /**
     * The underlying transport closed or tore — a socket drop, a peer leaving the seam's
     * peer set, or (on a joiner) the host link tearing. The most definitive of the three.
     */
    public data object TransportClosed : ReconnectReason
}

/**
 * Why a joiner's session terminally failed. Carried by [MembershipEvent.HostLost].
 *
 * This is the answer to the one question a reconnect UI must make: *do we keep waiting, or
 * do we show an error and give up?* Every variant here means "give up" — the distinction is
 * what to tell the user, and whether a fresh join is worth offering.
 */
public sealed interface FailureReason {
    /**
     * The reconnect window elapsed with no successful resume, and the host never refused —
     * the link simply never came back in time. A fresh join may well work.
     */
    public data object WindowExpired : FailureReason

    /**
     * The host **refused** at least one resume attempt during the window, and the window then
     * expired. [message] is the host's raw reject text (for example `"resume-window-closed"`
     * or `"resume-token-invalid: session-mismatch"`).
     *
     * Note that a refusal is *not* by itself terminal: a host that has not yet noticed the
     * drop rejects an early resume, and a later retry within the same window succeeds. This
     * variant is emitted only when the window ultimately expired with a refusal recorded — it
     * says "we were refused *and* we ran out of time", never "we stopped on the first no".
     *
     * [message] is free text, not a code: kuilt's reject wire carries no structured reason
     * today, so a consumer should treat it as diagnostic, not as something to branch on.
     */
    public data class Refused(val message: String) : FailureReason

    /**
     * No resume path existed at all — the link tore before the joiner was admitted (no resume
     * token), the joiner was constructed without re-weave support, or the fabric violated the
     * same-instance-heal contract so there was nothing to resume onto. Retrying the same
     * reconnect cannot help; the session must be rebuilt from scratch.
     */
    public data object Unrecoverable : FailureReason
}

/**
 * Lifts a liveness-layer [PartitionEvent.Reason] into the public session vocabulary.
 *
 * One-to-one and total — the two enums are deliberately parallel (see [ReconnectReason]).
 */
internal fun PartitionEvent.Reason.asReconnectReason(): ReconnectReason = when (this) {
    PartitionEvent.Reason.Timeout -> ReconnectReason.LinkTimeout
    PartitionEvent.Reason.Backpressure -> ReconnectReason.Backpressure
    PartitionEvent.Reason.TransportClosed -> ReconnectReason.TransportClosed
}
