package us.tractat.kuilt.nearby

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import us.tractat.kuilt.core.FabricAvailability

/**
 * Shared default for [NearbyApi.radioState] — a single immutable, never-updated [StateFlow] holding
 * `null` ("radios unknown") so the default getter allocates nothing per call. A binding that has
 * wired no radio observer inherits "unknown", and the seam reports
 * [us.tractat.kuilt.core.FabricAvailability.Unknown] rather than guessing — [NearbyLoom]'s static
 * report supplies only the ROLES, never a live verdict (#1712/#1543).
 */
private val EMPTY_RADIO_STATE: StateFlow<NearbyRadioState?> =
    MutableStateFlow<NearbyRadioState?>(null).asStateFlow()

/**
 * Abstracts the slice of Google Nearby Connections needed by [NearbyLoom].
 *
 * Implementations: [FakeNearbyApi] (tests, commonTest) and the real
 * `GmsNearbyApi` (androidMain, dispatched separately).
 *
 * All callbacks surface as [Flow]s. The [endpointId] used in these events
 * is a local-namespace opaque string assigned by the runtime — it is NOT a
 * stable peer identity across sessions. Stable identity is exchanged as a
 * payload during the handshake.
 */
public interface NearbyApi {

    /**
     * Reports whether the underlying fabric is usable on this runtime — a **one-shot, static**
     * platform-support question ("is there a Nearby runtime here at all"), answered once when
     * [NearbyLoom.capability] is asked. It is deliberately NOT the live signal: see [radioState],
     * which is what makes a woven seam's capability reactive.
     */
    public fun availability(): FabricAvailability

    // ── advertising ──────────────────────────────────────────────────────────

    /** Begin advertising under [displayName] with service id [serviceId]. */
    public suspend fun startAdvertising(displayName: String, serviceId: String)

    /** Stop advertising. No-op if not advertising. */
    public suspend fun stopAdvertising()

    // ── discovery ────────────────────────────────────────────────────────────

    /** Begin discovering endpoints with the given [serviceId]. */
    public suspend fun startDiscovery(serviceId: String)

    /** Stop discovery. No-op if not discovering. */
    public suspend fun stopDiscovery()

    // ── connection lifecycle ──────────────────────────────────────────────────

    /**
     * Request a connection to a discovered [endpointId].
     * The local [displayName] is sent to the remote during the handshake.
     */
    public suspend fun requestConnection(displayName: String, endpointId: String)

    /**
     * Accept an incoming connection from [endpointId].
     * Called after an [EndpointEvent.ConnectionInitiated] event is received.
     */
    public suspend fun acceptConnection(endpointId: String)

    /** Disconnect from [endpointId]. No-op if already disconnected. */
    public suspend fun disconnect(endpointId: String)

    // ── data ─────────────────────────────────────────────────────────────────

    /** Send a raw bytes payload to [endpointId]. */
    public suspend fun sendBytesPayload(endpointId: String, bytes: ByteArray)

    // ── event flows ──────────────────────────────────────────────────────────

    /**
     * Emits when a remote endpoint is found during discovery.
     * Carries the endpointId and the remote's display name.
     */
    public val endpointFound: Flow<EndpointFound>

    /**
     * Emits when a connection initiation is signalled (either side triggered it).
     * The receiver should call [acceptConnection] to proceed.
     */
    public val connectionInitiated: Flow<ConnectionInitiated>

    /**
     * Emits the result of a connection request — success or failure.
     */
    public val connectionResult: Flow<ConnectionResult>

    /** Emits when a bytes payload is received from an endpoint. */
    public val payloadReceived: Flow<PayloadReceived>

    /** Emits when an endpoint disconnects. */
    public val endpointDisconnected: Flow<EndpointDisconnected>

    /**
     * The device's live radio state, or `null` while unknown. This is what makes a seam's
     * [us.tractat.kuilt.core.Seam.capability] reactive (#1543): as Bluetooth is toggled, Wi-Fi
     * returns, or the Play services runtime hosting the fabric goes away, a fresh
     * [NearbyRadioState] appears here and the seam folds its [toAvailability] into the live
     * capability — and its [radioRoles] into the live ROLES.
     *
     * Modelled as latest-value **[StateFlow] state** (not a lossy event stream) — the current radio
     * state is a level, and a late subscriber must see the latest value, never miss it. Defaults to
     * a never-updated `null` ("unknown") so a binding without a real observer inherits "unknown"
     * and the seam reports [us.tractat.kuilt.core.FabricAvailability.Unknown] rather than guessing;
     * [NearbyLoom]'s static report supplies only the ROLES, never a live verdict (#1712). Wiring an
     * observer here is therefore what takes a binding's seam OFF that floor; nothing else can.
     * `GmsNearbyApi` (androidMain) drives it from the platform's Bluetooth/Wi-Fi state broadcasts;
     * the test fake exposes a controllable `MutableStateFlow` so no test touches a system service.
     *
     * **Not a Play-services listener, because there is none.** `ConnectionsClient` exposes only
     * per-operation callbacks and `GoogleApiAvailability` is a one-shot poll, so the GMS half of
     * the verdict is re-read on each radio transition rather than observed — see `GmsNearbyApi`.
     */
    public val radioState: StateFlow<NearbyRadioState?>
        get() = EMPTY_RADIO_STATE
}

// ── event types ──────────────────────────────────────────────────────────────

/** A discoverable endpoint appeared. */
public data class EndpointFound(
    val endpointId: String,
    val displayName: String,
)

/** A connection handshake was initiated (either side) on [endpointId]. */
public data class ConnectionInitiated(
    val endpointId: String,
    val displayName: String,
)

/** Result of a connection attempt on [endpointId]. */
public data class ConnectionResult(
    val endpointId: String,
    val success: Boolean,
    val reason: String? = null,
)

/** A bytes payload from [endpointId]. */
public data class PayloadReceived(
    val endpointId: String,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PayloadReceived) return false
        return endpointId == other.endpointId && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = endpointId.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}

/** The endpoint [endpointId] has disconnected. */
public data class EndpointDisconnected(val endpointId: String)
