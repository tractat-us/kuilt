package us.tractat.kuilt.liveness

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration for [HeartbeatPartitionDetector].
 *
 * Defaults: 5 s ping interval, 15 s timeout, 60 s reconnect window, 15 s resume timeout.
 * Override via [RoomConfig] when the application needs different timing — for example, a
 * longer [reconnectWindow] for a long idle period.
 */
public data class HeartbeatConfig(
    /**
     * How often the detector sends a ping frame to the peer.
     *
     * Default: 5 seconds.
     */
    val interval: Duration = 5.seconds,
    /**
     * How long without any inbound frame (ping or application) before the peer
     * is considered unresponsive.
     *
     * Default: 15 seconds.
     */
    val timeout: Duration = 15.seconds,
    /**
     * How long after first becoming unresponsive before the peer is considered lost.
     *
     * Default: 60 seconds.
     */
    val reconnectWindow: Duration = 60.seconds,
    /**
     * Deadline for one attempt of a joiner's reconnect: a Hello awaiting the host's `Welcome` or
     * `Reject`. When it elapses with no verdict the attempt ends as timed out and the reconnect
     * tries again, instead of parking. An attempt whose seam tears first ends at once, without
     * waiting this out: no reply can come back over it.
     *
     * Defaults to [timeout] (15 s): an attempt is a single exchange with the host, so the same
     * single-RPC deadline that governs heartbeat responsiveness fits it — and it is deliberately
     * far shorter than [reconnectWindow] (60 s), which budgets the whole *multi-attempt* reconnect
     * episode, not one reply. Without this bound an attempt against a host that never replies
     * (host gone, link black-holed, reply lost) would spend the whole window.
     *
     * Default: 15 seconds.
     */
    val resumeTimeout: Duration = timeout,
)
