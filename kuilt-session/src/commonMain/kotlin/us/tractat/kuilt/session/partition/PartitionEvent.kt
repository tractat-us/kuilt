package us.tractat.kuilt.session.partition

import us.tractat.kuilt.core.PeerId
import kotlin.time.Instant

/**
 * An event emitted by [PartitionDetector] describing a peer's reachability state change.
 *
 * The event hierarchy is intentionally role-agnostic: there is no distinction between
 * "host" and "joiner" here. Any leader-election protocol (explicit handoff or automatic
 * election) consumes the same [PartitionEvent] stream without re-plumbing the detection
 * layer.
 *
 * Reserved heartbeat frame namespace: `kuilt.heartbeat.ping` / `kuilt.heartbeat.pong`.
 * Applications must not emit frames with these namespaces; they are consumed exclusively
 * by [HeartbeatPartitionDetector].
 *
 * The runtime is responsible for calling [PartitionDetector.observedPeer] whenever any
 * frame (including application frames) arrives from a peer. App-layer frames reset the
 * timeout just as pong frames do — heartbeat is a dead-man's switch, not a dedicated
 * keepalive channel.
 */
public sealed interface PartitionEvent {
    public val peerId: PeerId
    public val at: Instant

    /**
     * The peer has stopped responding within [HeartbeatConfig.timeout].
     *
     * The peer may still recover — the detector continues monitoring until
     * [reconnectWindow] expires. React by pausing application message processing.
     *
     * [reason] distinguishes the source of the signal so logs and UX can present
     * appropriate context.
     */
    public data class PeerUnresponsive(
        override val peerId: PeerId,
        override val at: Instant,
        val reason: Reason,
    ) : PartitionEvent

    /**
     * A previously unresponsive peer has resumed sending frames before the
     * [HeartbeatConfig.reconnectWindow] expired.
     *
     * React by resuming application message processing.
     */
    public data class PeerRecovered(
        override val peerId: PeerId,
        override val at: Instant,
    ) : PartitionEvent

    /**
     * The [HeartbeatConfig.reconnectWindow] expired without recovery.
     *
     * After emitting this event the detector stops re-emitting for the peer —
     * the peer's slot in the roster should be considered permanently vacated.
     */
    public data class PeerLost(
        override val peerId: PeerId,
        override val at: Instant,
    ) : PartitionEvent

    /**
     * The source of a [PeerUnresponsive] signal.
     *
     * Every send-path failure surfaces with a labeled reason: this keeps logs
     * and UI able to distinguish a silent WiFi drop ([Timeout]) from a
     * misbehaving peer that stopped reading ([Backpressure]) from a clean
     * disconnect ([TransportClosed]).
     */
    public enum class Reason {
        /** No pong received within [HeartbeatConfig.timeout]. */
        Timeout,

        /** Per-peer outbound buffer exceeded the configured ceiling. */
        Backpressure,

        /** The underlying [us.tractat.kuilt.core.Seam] was closed. */
        TransportClosed,
    }
}
