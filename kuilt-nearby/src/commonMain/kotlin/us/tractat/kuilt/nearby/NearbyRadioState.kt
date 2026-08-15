package us.tractat.kuilt.nearby

import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.TransportRole

/**
 * A snapshot of the radios Google Nearby Connections runs over on this device. This is the live
 * environmental signal that turns a seam's [us.tractat.kuilt.core.Seam.capability] from a
 * once-at-connect seed into a reactive value: as Bluetooth is switched off, Wi-Fi comes back, or
 * the Play services runtime that hosts the fabric goes away, a fresh [NearbyRadioState] is
 * published and the seam's availability follows.
 *
 * The real observer lives in `GmsNearbyApi` (androidMain); tests drive a controllable
 * `MutableStateFlow<NearbyRadioState?>` through `FakeNearbyApi`, so no code under test touches an
 * Android system service. A `null` radio state (the default on a binding that has wired no
 * observer) means "unknown": the seam reports [FabricAvailability.Unknown] rather than guessing,
 * and [NearbyLoom]'s static report supplies only the ROLES, never a live verdict (#1712/#1543).
 *
 * @param bluetooth whether the Bluetooth radio is usable by this fabric — Nearby's `P2P_STAR`
 *   bootstraps discovery and the initial connection over BLE and Bluetooth Classic.
 * @param wifi whether the Wi-Fi radio is usable by this fabric — Nearby upgrades an established
 *   connection onto Wi-Fi Direct / a Wi-Fi hotspot for bandwidth.
 */
public data class NearbyRadioState(
    public val bluetooth: NearbyRadioStatus,
    public val wifi: NearbyRadioStatus,
)

/**
 * Whether one radio is usable **by the Nearby Connections fabric** — not a raw hardware reading.
 *
 * The distinction matters for [Unsupported]: a device whose Play services runtime is missing or
 * out of date has perfectly good radios that this fabric nonetheless cannot drive, and the honest
 * report is that they are not usable here. `GmsNearbyApi` folds that check in before reading either
 * radio, so a `NearbyRadioState` is always "what can Nearby actually do right now".
 */
public enum class NearbyRadioStatus {
    /** Powered and usable by Nearby Connections. */
    On,

    /** Present and usable in principle, but currently switched off. */
    Off,

    /** This device cannot use the radio for Nearby — no hardware, or no Play services runtime. */
    Unsupported,

    /** Not readable right now — e.g. the runtime permission guarding it has not been granted. */
    Unknown,
}

/**
 * Fold a live [NearbyRadioState] into the [FabricAvailability] half of a transport capability.
 *
 * Nearby needs **either** radio to be usable, not both — it bootstraps over Bluetooth and upgrades
 * onto Wi-Fi — so one [NearbyRadioStatus.On] is enough to report [FabricAvailability.Available].
 * With neither on, an unreadable radio ([NearbyRadioStatus.Unknown]) forces
 * [FabricAvailability.Unknown] rather than a confident "unavailable": a radio we were not permitted
 * to read may well be on, and claiming otherwise would be the #1712 defect in the opposite
 * direction. Only when every radio is definitely off or unusable do we report
 * [FabricAvailability.Unavailable], and the reason names both readings so a consumer can act on it.
 */
internal fun NearbyRadioState.toAvailability(): FabricAvailability = when {
    bluetooth == NearbyRadioStatus.On || wifi == NearbyRadioStatus.On ->
        FabricAvailability.Available
    bluetooth == NearbyRadioStatus.Unknown || wifi == NearbyRadioStatus.Unknown ->
        FabricAvailability.Unknown("no Nearby radio reads as on (bluetooth=$bluetooth, wifi=$wifi)")
    bluetooth == NearbyRadioStatus.Unsupported && wifi == NearbyRadioStatus.Unsupported ->
        FabricAvailability.Unavailable("Nearby Connections is not usable on this device")
    else ->
        FabricAvailability.Unavailable("no Nearby radio is on (bluetooth=$bluetooth, wifi=$wifi)")
}

/**
 * The transport-medium [TransportRole]s this radio state currently supports: an on Bluetooth radio
 * contributes [TransportRole.Bluetooth], an on Wi-Fi radio contributes [TransportRole.WifiDirect]
 * (Nearby's Wi-Fi leg is peer-to-peer — Wi-Fi Direct / hotspot — never an infrastructure LAN, so
 * [TransportRole.WifiLan] is never claimed).
 *
 * A device may carry both at once, so this is a set. Folded onto the fabric's base role
 * ([NearbyLoom.NEARBY_BASE_ROLES] = Data) by [NearbySeam]'s live-capability driver — which is why
 * the *live* role set narrows when a radio goes off, while [NearbyLoom.capability]'s static report
 * keeps naming both media: that answers "what can this fabric do", not "what is on right now".
 */
internal fun NearbyRadioState.radioRoles(): Set<TransportRole> = buildSet {
    if (bluetooth == NearbyRadioStatus.On) add(TransportRole.Bluetooth)
    if (wifi == NearbyRadioStatus.On) add(TransportRole.WifiDirect)
}
