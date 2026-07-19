package us.tractat.kuilt.nw

import us.tractat.kuilt.core.FabricAvailability

/**
 * A snapshot of the device's current network path, as reported by Apple's `NWPathMonitor`
 * (`nw_path_monitor_*`). This is the live environmental signal that turns a seam's
 * [us.tractat.kuilt.core.Seam.capability] from a once-at-connect seed into a reactive value:
 * as Wi-Fi drops, cellular takes over, or the Local-Network permission is denied, a fresh
 * [NwPathState] is published and the seam's availability follows.
 *
 * The real monitor lives in `RealNwApi` (appleMain); tests drive a controllable
 * `MutableStateFlow<NwPathState?>` through `FakeNwApi`, so no code under test touches the OS path
 * monitor. A `null` path state (the default on a binding that has not wired a monitor — e.g. the
 * JVM bridge) means "unknown": the seam keeps its static capability seed rather than guessing.
 *
 * @param status the overall reachability [NwPathStatus].
 * @param interfaces which physical interface types the path currently uses (Wi-Fi / cellular / wired…).
 *   Best-effort: the monitor cannot distinguish infrastructure Wi-Fi from peer-to-peer AWDL, so this is
 *   observability/telemetry, not a role source (see the module notes on why roles stay Discovery+Data).
 * @param isExpensive the OS flags the path as expensive (e.g. cellular, personal hotspot).
 * @param isConstrained the OS flags the path as constrained (Low Data Mode).
 * @param unsatisfiedReason when [status] is [NwPathStatus.Unsatisfied], why — including
 *   [NwUnsatisfiedReason.LocalNetworkDenied], the Local-Network-permission signal. `null` otherwise.
 */
public data class NwPathState(
    public val status: NwPathStatus,
    public val interfaces: Set<NwInterfaceType>,
    public val isExpensive: Boolean,
    public val isConstrained: Boolean,
    public val unsatisfiedReason: NwUnsatisfiedReason?,
)

/** The overall reachability of a network path, mirroring `nw_path_status_t`. */
public enum class NwPathStatus {
    /** The path is up and a connection can be established immediately (`nw_path_status_satisfied`). */
    Satisfied,

    /** No path now, but one could be established on demand (e.g. a VPN-on-demand or cellular that needs consent). */
    Satisfiable,

    /** No usable path — see [NwPathState.unsatisfiedReason] (`nw_path_status_unsatisfied`). */
    Unsatisfied,

    /** The monitor has not yet reported ground truth (`nw_path_status_invalid`). */
    Invalid,
}

/** A physical interface type a path can traverse, mirroring `nw_interface_type_t`. */
public enum class NwInterfaceType { Wifi, Cellular, Wired, Loopback, Other }

/**
 * Why a path is [NwPathStatus.Unsatisfied], mirroring `nw_path_unsatisfied_reason_t`.
 * [LocalNetworkDenied] is the Local-Network-permission denial — the actionable signal a consumer
 * turns into "grant Local Network access" guidance instead of a silent stall.
 */
public enum class NwUnsatisfiedReason {
    /** No interface available at all (radios off / airplane mode). */
    NotAvailable,

    /** Cellular data is denied for this app/path. */
    CellularDenied,

    /** Wi-Fi is denied for this app/path. */
    WifiDenied,

    /** The Local-Network permission has been denied — the actionable permission signal. */
    LocalNetworkDenied,

    /** A required on-demand VPN is inactive. */
    VpnInactive,

    /** The OS reported an unsatisfied reason this version does not model. */
    Unknown,
}

/**
 * Fold a live [NwPathState] into the [FabricAvailability] half of a transport capability. A satisfied
 * path is [FabricAvailability.Available]; an unsatisfied one is [FabricAvailability.Unavailable] with a
 * human-readable reason (Local-Network denial called out explicitly); a satisfiable/invalid path is
 * [FabricAvailability.Unknown] (best-effort — the platform is not reporting ground truth yet).
 */
internal fun NwPathState.toAvailability(): FabricAvailability = when (status) {
    NwPathStatus.Satisfied -> FabricAvailability.Available
    NwPathStatus.Unsatisfied -> when (unsatisfiedReason) {
        NwUnsatisfiedReason.LocalNetworkDenied ->
            FabricAvailability.Unavailable("Local Network permission denied")
        NwUnsatisfiedReason.WifiDenied -> FabricAvailability.Unavailable("Wi-Fi access denied")
        NwUnsatisfiedReason.CellularDenied -> FabricAvailability.Unavailable("cellular access denied")
        NwUnsatisfiedReason.VpnInactive -> FabricAvailability.Unavailable("required VPN is inactive")
        NwUnsatisfiedReason.NotAvailable -> FabricAvailability.Unavailable("no network is available")
        NwUnsatisfiedReason.Unknown, null -> FabricAvailability.Unavailable("no usable network path")
    }
    NwPathStatus.Satisfiable -> FabricAvailability.Unknown("network path is satisfiable but not currently up")
    NwPathStatus.Invalid -> FabricAvailability.Unknown("network path status not yet known")
}
