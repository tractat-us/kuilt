package us.tractat.kuilt.liveness

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration for [HeartbeatPartitionDetector].
 *
 * Defaults: 5 s ping interval, 15 s timeout, 60 s reconnect window.
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
)
