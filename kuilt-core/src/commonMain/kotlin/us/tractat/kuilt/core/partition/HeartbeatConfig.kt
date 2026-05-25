package us.tractat.kuilt.core.partition

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration for [HeartbeatPartitionDetector].
 *
 * Defaults follow D-007 (5 s ping interval, 15 s timeout) and D-005 (60 s reconnect window).
 * Override via [RoomConfig] when the application needs different timing — for example, a
 * longer [reconnectWindow] for a "lunch break" scenario (D-005).
 */
public data class HeartbeatConfig(
    /**
     * How often the detector sends a ping frame to the peer.
     *
     * D-007 default: 5 seconds.
     */
    val interval: Duration = 5.seconds,
    /**
     * How long without any inbound frame (ping or application) before the peer
     * is considered unresponsive.
     *
     * D-007 default: 15 seconds.
     */
    val timeout: Duration = 15.seconds,
    /**
     * How long after first becoming unresponsive before the peer is considered lost.
     *
     * D-005 default: 60 seconds.
     */
    val reconnectWindow: Duration = 60.seconds,
)
