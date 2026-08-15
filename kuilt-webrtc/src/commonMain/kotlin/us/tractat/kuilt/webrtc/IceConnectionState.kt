package us.tractat.kuilt.webrtc

import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.TransportRole

/**
 * The live reachability of a WebRTC peer connection's ICE agent — the W3C
 * `RTCPeerConnection.iceConnectionState` vocabulary, lifted out of the browser so the fold into
 * [FabricAvailability] is one platform-neutral function rather than string comparisons scattered
 * through `wasmJsMain`.
 *
 * This is the live environmental signal that turns a seam's
 * [us.tractat.kuilt.core.Seam.capability] from a once-at-connect seed into a reactive value
 * (#1544): as candidate pairs stop responding, the network drops, or the connection recovers, a
 * fresh state is published and the seam's availability follows.
 *
 * The real observer is `BrowserRtcFacade`'s `oniceconnectionstatechange` handler (`wasmJsMain`);
 * tests drive a controllable `MutableStateFlow<IceConnectionState?>` through `PairedFacadeFactory`,
 * so no code under test needs a real `RTCPeerConnection`. A `null` state — the value a facade
 * reports before its binding has observed anything — means "unknown": the seam reports
 * [FabricAvailability.Unknown] rather than guessing, and the loom's static
 * [us.tractat.kuilt.core.Loom.capability] supplies only the ROLES, never a live verdict (#1712).
 */
internal enum class IceConnectionState {
    /** The agent has been created; no candidate pair has been tested yet. */
    New,

    /** Candidate pairs are being tested. Connectivity is not yet established — nor yet ruled out. */
    Checking,

    /** At least one usable candidate pair exists for every component. Media can flow. */
    Connected,

    /** Connected, and the agent has finished checking every candidate pair. */
    Completed,

    /**
     * Connectivity checks have started failing for at least one component. Per the W3C ICE state
     * machine this is a *recoverable* condition — the agent keeps checking and may return to
     * [Connected] without renegotiation — but right now nothing is getting through.
     */
    Disconnected,

    /** The agent gave up: no viable candidate pair remains. Terminal without an ICE restart. */
    Failed,

    /** The agent is shut down and will never check again. */
    Closed,
}

/**
 * Parse a W3C `RTCIceConnectionState` wire string, or `null` when the value is outside the spec's
 * vocabulary.
 *
 * `null` deliberately means "we do not understand this reading", which the caller carries through
 * as [FabricAvailability.Unknown] rather than mapping to a convenient neighbour. A browser that
 * grew a new state would otherwise have its reading silently rounded to a verdict nobody wrote —
 * the #1712 defect arriving through a `when` branch's `else`.
 */
internal fun iceConnectionStateOf(wire: String): IceConnectionState? = when (wire) {
    "new" -> IceConnectionState.New
    "checking" -> IceConnectionState.Checking
    "connected" -> IceConnectionState.Connected
    "completed" -> IceConnectionState.Completed
    "disconnected" -> IceConnectionState.Disconnected
    "failed" -> IceConnectionState.Failed
    "closed" -> IceConnectionState.Closed
    else -> null
}

/**
 * The roles a WebRTC data-channel seam plays, whatever ICE is currently doing: it *is* a WebRTC
 * link and it carries application data.
 *
 * Unlike `kuilt-nearby`, whose media roles narrow when a radio is switched off, WebRTC has no
 * medium to lose — the transport underneath ICE may change from host to relayed candidates without
 * ever ceasing to be a WebRTC data channel. So the ROLES are static here and only the
 * [FabricAvailability] half of [us.tractat.kuilt.core.TransportCapability] is live.
 */
internal val WEBRTC_ROLES: Set<TransportRole> = setOf(TransportRole.WebRtc, TransportRole.Data)

/**
 * The availability of a seam whose binding has observed **no** ICE state yet.
 *
 * An honest floor, not a guess (#1712): a facade that has wired no observer, or one whose browser
 * reported a state outside the spec vocabulary, does not know whether packets are flowing and must
 * say so. Nothing else in this module can produce a verdict — there is no static availability to
 * fall back on, by construction.
 */
internal val UNOBSERVED_ICE_AVAILABILITY: FabricAvailability =
    FabricAvailability.Unknown("no ICE connection state has been observed on this peer connection")

/**
 * Fold a live [IceConnectionState] into the [FabricAvailability] half of a transport capability.
 *
 * The three-way split is the point (see `docs/architecture.md`, *reportsLiveCapability*):
 *  - **Available** — [IceConnectionState.Connected] / [IceConnectionState.Completed]: a usable
 *    candidate pair exists, which is exactly the question `Seam.capability` asks.
 *  - **Unknown** — [IceConnectionState.New] / [IceConnectionState.Checking]: the agent has not
 *    finished deciding. Reporting `Unavailable` here would be a fabricated verdict during the
 *    perfectly ordinary seconds before a connection comes up.
 *  - **Unavailable** — [IceConnectionState.Disconnected] / [IceConnectionState.Failed] /
 *    [IceConnectionState.Closed]: the browser has *told* us checks are failing. That is an
 *    observation, not a shrug, so it earns a definite negative — with a reason that distinguishes
 *    the recoverable case from the terminal ones, since a consumer may want to keep waiting on the
 *    first and give up on the others.
 */
internal fun IceConnectionState.toAvailability(): FabricAvailability = when (this) {
    IceConnectionState.Connected, IceConnectionState.Completed ->
        FabricAvailability.Available
    IceConnectionState.New ->
        FabricAvailability.Unknown("ICE has not begun connectivity checks")
    IceConnectionState.Checking ->
        FabricAvailability.Unknown("ICE is still checking candidate pairs")
    IceConnectionState.Disconnected ->
        FabricAvailability.Unavailable(
            "ICE connectivity checks are failing; the connection may recover without renegotiation",
        )
    IceConnectionState.Failed ->
        FabricAvailability.Unavailable("ICE failed: no viable candidate pair remains")
    IceConnectionState.Closed ->
        FabricAvailability.Unavailable("the peer connection is closed")
}
