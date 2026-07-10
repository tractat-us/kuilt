package us.tractat.kuilt.demo

import us.tractat.kuilt.core.PeerId

/**
 * How the reach-in harness finds the relay's telemetry.
 *
 * The relay ([us.tractat.kuilt.demo.relay]) offers its own logs and metrics for a
 * laptop to pull; the `:demo-tap` harness reaches in and pulls them. For that to
 * work the two processes have to agree, byte for byte, on *where* the telemetry
 * lives — which port, which WebSocket paths, and which peer identity is on the far
 * end. Those few shared constants live here, in one place, so the relay and the
 * harness can never drift apart: change an address and both sides change together.
 *
 * There are two separate taps — one for logs, one for metrics — because they carry
 * different shapes of data (an ordered stream of log lines vs. a converged bag of
 * numbers), so each gets its own path and its own server identity on the relay.
 */
public object TapWire {
    /** The port the relay opens its telemetry taps on, distinct from the Patchwork hub port. */
    public const val DEFAULT_PORT: Int = 9191

    /** The WebSocket path the relay hosts its captured-log tap at. */
    public const val LOG_PATH: String = "/patchwork/log-tap"

    /** The WebSocket path the relay hosts its metric tap at. */
    public const val METRIC_PATH: String = "/patchwork/metric-tap"

    /**
     * The relay's fixed peer identity for the log tap. The harness must address the
     * server it joins by the exact id the relay opened with — so it is pinned here
     * rather than minted fresh, the way the live Patchwork relay mints its hub id.
     */
    public val LOG_SERVER_ID: PeerId = PeerId("patchwork-relay-log-tap")

    /** The relay's fixed peer identity for the metric tap. See [LOG_SERVER_ID]. */
    public val METRIC_SERVER_ID: PeerId = PeerId("patchwork-relay-metric-tap")
}
