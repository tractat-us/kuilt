package us.tractat.kuilt.websocket

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import us.tractat.kuilt.core.FabricAvailability

/**
 * What this device's operating system says about its ability to reach *a network* right now — the
 * live environmental signal that takes a WebSocket [us.tractat.kuilt.core.Seam]'s
 * [us.tractat.kuilt.core.Seam.capability] off the [FabricAvailability.Unknown] floor (#1725).
 *
 * ## What this is NOT
 * This is a statement about **this device's own connectivity**, never about the relay at the other
 * end. A phone with five bars talking to a server that has crashed is [Reachable] here, and the
 * dead server is a *peer-liveness* fact for `:kuilt-liveness` / `PartitionDetector` to report — not
 * a capability fact. Folding the two together would make `Room.localFabric` say "your network is
 * down" to a user whose network is fine, which is the fabricated verdict #1712 exists to prevent,
 * pointed the other way.
 *
 * A `null` reading (the value [UnobservedConnectivity] publishes forever) is a *fourth* state and
 * means something different again: no observer is wired at all on this binding. [Indeterminate]
 * means an observer is wired and reported, and its answer was "I cannot tell".
 */
public enum class NetworkReachability {
    /** The platform reports a usable network path off this device. */
    Reachable,

    /** The platform reports no usable network path — this device cannot reach a relay. */
    Unreachable,

    /**
     * An observer is wired and has reported, but its signal cannot distinguish reachable from not.
     *
     * The browser is the reason this value exists: `navigator.onLine === true` means only that the
     * user agent believes it has *an* interface, not that anything is reachable across it — it is
     * famously `true` on a captive portal, on a LAN with no uplink, and on a machine whose VPN just
     * died. Reporting [Reachable] from it would be a confident lie, so `BrowserConnectivityObserver`
     * reports this instead, and [toAvailability] carries it through as [FabricAvailability.Unknown].
     * The *negative* direction is trustworthy, so `navigator.onLine === false` is [Unreachable].
     */
    Indeterminate,
}

/**
 * A live source of [NetworkReachability] readings for the WebSocket fabric.
 *
 * Bindings: `androidConnectivityObserver` (a `ConnectivityManager.NetworkCallback`) and
 * `browserConnectivityObserver` (`navigator.onLine` plus the `online`/`offline` events). The
 * desktop JVM has **no** binding on purpose — there is no portable OS reachability observer there,
 * and synthesising one from socket state would report the relay's health as the device's, the exact
 * conflation [NetworkReachability] forbids. A JVM caller therefore leaves the default
 * [UnobservedConnectivity] in place and the seam honestly reports [FabricAvailability.Unknown].
 * Apple targets have no binding yet either — see
 * [#2413](https://github.com/tractat-us/kuilt/issues/2413).
 */
public interface ConnectivityObserver {
    /**
     * Latest reachability reading, or `null` when nothing has been observed yet — including
     * forever, on a binding that wires no observer at all.
     */
    public val reachability: StateFlow<NetworkReachability?>
}

/**
 * The no-observer [ConnectivityObserver]: publishes `null` forever, so a seam built on it reports
 * the roleless-availability [FabricAvailability.Unknown] floor and never a fabricated verdict.
 *
 * This is the **default** on every loom, so wiring nothing changes nothing — it is the identity
 * element of this feature, not a tuning knob that suppresses a working observer.
 *
 * Exposed through [asStateFlow] for the same reason `StaticUnknownCapability` is in `:kuilt-core`:
 * without it a consumer could downcast this one process-wide value to [MutableStateFlow] and move
 * every unobserved seam's capability at once.
 */
public object UnobservedConnectivity : ConnectivityObserver {
    override val reachability: StateFlow<NetworkReachability?> =
        MutableStateFlow<NetworkReachability?>(null).asStateFlow()
}

/**
 * Fold a reachability reading into the [FabricAvailability] half of a transport capability.
 *
 * Both "cannot tell" arms land on [FabricAvailability.Unknown] but carry **different reasons**, so
 * a consumer surfacing the text can tell "this fabric watches nothing" from "the browser watched
 * and shrugged" — and so that the mapping stays injective, which is what makes the scope-free
 * derived `StateFlow` in `WebSocketSeam` a legitimate `StateFlow` (conflation is preserved only if
 * distinct inputs stay distinct).
 */
internal fun NetworkReachability?.toAvailability(): FabricAvailability = when (this) {
    NetworkReachability.Reachable -> FabricAvailability.Available
    NetworkReachability.Unreachable ->
        FabricAvailability.Unavailable("this device reports no usable network path")
    NetworkReachability.Indeterminate ->
        FabricAvailability.Unknown("this device's connectivity signal cannot confirm reachability")
    null -> FabricAvailability.Unknown("no live path observer on this fabric")
}
