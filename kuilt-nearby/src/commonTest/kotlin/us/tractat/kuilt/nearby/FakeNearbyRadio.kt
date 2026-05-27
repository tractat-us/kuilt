package us.tractat.kuilt.nearby

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import us.tractat.kuilt.core.FabricAvailability

/**
 * In-memory Nearby Connections radio supporting a single advertiser↔discoverer pair.
 *
 * Designed for a single [FakeNearbyApi] that handles BOTH roles (advertising and
 * discovery) for the same [NearbyLoom] instance — matching the conformance suite's
 * `open()` + `join()` on one loom.
 *
 * ## Endpoint ID convention (deterministic, both sides see different IDs)
 * - The advertiser sees the discoverer as **[ADVERTISER_SEES_DISCOVERER_AS]**.
 * - The discoverer sees the advertiser as **[DISCOVERER_SEES_ADVERTISER_AS]**.
 *
 * This allows each state machine to filter on its own endpoint ID and correctly
 * partition events even though both roles share a single [NearbyApi] flow.
 *
 * ## Payload routing
 * `sendBytesPayload(endpointId, bytes)` routes by endpoint ID:
 * - Sending to [ADVERTISER_SEES_DISCOVERER_AS] → deliver as
 *   `PayloadReceived([DISCOVERER_SEES_ADVERTISER_AS], bytes)`.
 * - Sending to [DISCOVERER_SEES_ADVERTISER_AS] → deliver as
 *   `PayloadReceived([ADVERTISER_SEES_DISCOVERER_AS], bytes)`.
 */
internal class FakeNearbyRadio {

    companion object {
        /** Endpoint ID the ADVERTISER uses to refer to the DISCOVERER. */
        const val ADVERTISER_SEES_DISCOVERER_AS = "ep-discoverer"

        /** Endpoint ID the DISCOVERER uses to refer to the ADVERTISER. */
        const val DISCOVERER_SEES_ADVERTISER_AS = "ep-advertiser"
    }

    private var advertisingDisplayName: String? = null
    private var discoveryStarted = false
    private var acceptCount = 0

    // A single scope for background event delivery; uses the current dispatcher
    // (overridden to UnconfinedTestDispatcher in runTest).
    private val scope = CoroutineScope(SupervisorJob())

    // ── event delivery ────────────────────────────────────────────────────────

    // Shared api reference (set when FakeNearbyApi is created).
    private lateinit var api: FakeNearbyApi

    internal fun bind(api: FakeNearbyApi) { this.api = api }

    fun onStartAdvertising(displayName: String) {
        advertisingDisplayName = displayName
        tryAutoConnect()
    }

    fun onStartDiscovery() {
        discoveryStarted = true
        tryAutoConnect()
    }

    /**
     * Once both sides are active, deliver [EndpointFound] to the discoverer.
     * The discoverer will then call [requestConnection].
     */
    private fun tryAutoConnect() {
        if (advertisingDisplayName == null || !discoveryStarted) return
        val displayName = advertisingDisplayName ?: return
        scope.launch {
            api.emit(EndpointFound(DISCOVERER_SEES_ADVERTISER_AS, displayName))
        }
    }

    /**
     * Called when one side calls [requestConnection].
     * Emits [ConnectionInitiated] on BOTH sides (advertiser side first, per the
     * ordering contract that lets the host's first-seen state machine capture its ID).
     */
    fun onRequestConnection(displayName: String, endpointId: String) {
        scope.launch {
            // Advertiser side: sees the discoverer arrive.
            api.emit(ConnectionInitiated(ADVERTISER_SEES_DISCOVERER_AS, displayName))
            // Discoverer side: sees the advertiser respond.
            api.emit(ConnectionInitiated(DISCOVERER_SEES_ADVERTISER_AS, advertisingDisplayName ?: "advertiser"))
        }
    }

    /**
     * Called when either side accepts. Fires [ConnectionResult] success on BOTH sides
     * once two accepts have been received.
     */
    fun onAcceptConnection(endpointId: String) {
        acceptCount++
        if (acceptCount == 2) {
            acceptCount = 0
            scope.launch {
                api.emit(ConnectionResult(ADVERTISER_SEES_DISCOVERER_AS, success = true))
                api.emit(ConnectionResult(DISCOVERER_SEES_ADVERTISER_AS, success = true))
            }
        }
    }

    /**
     * Route a bytes payload: flips the endpoint ID so the receiver sees it from
     * its own perspective.
     */
    fun onSendBytesPayload(endpointId: String, bytes: ByteArray) {
        val deliverAsEndpointId = when (endpointId) {
            ADVERTISER_SEES_DISCOVERER_AS -> DISCOVERER_SEES_ADVERTISER_AS
            DISCOVERER_SEES_ADVERTISER_AS -> ADVERTISER_SEES_DISCOVERER_AS
            else -> return // unknown endpoint, ignore
        }
        scope.launch {
            api.emit(PayloadReceived(deliverAsEndpointId, bytes))
        }
    }

    fun onDisconnect(endpointId: String) {
        val deliverAsEndpointId = when (endpointId) {
            ADVERTISER_SEES_DISCOVERER_AS -> DISCOVERER_SEES_ADVERTISER_AS
            DISCOVERER_SEES_ADVERTISER_AS -> ADVERTISER_SEES_DISCOVERER_AS
            else -> return
        }
        scope.launch {
            api.emit(EndpointDisconnected(deliverAsEndpointId))
        }
    }
}

/**
 * Fake [NearbyApi] backed by a [FakeNearbyRadio].
 *
 * Create one instance per loom; both [open] and [join] on the same loom use
 * this single api. The radio partitions events by endpoint ID so both
 * state machines can coexist without interference.
 */
internal class FakeNearbyApi(radio: FakeNearbyRadio) : NearbyApi {

    private val _radio = radio.also { it.bind(this) }

    // ── shared flows (no replay — consumers only see events after subscription) ──

    private val _endpointFound = MutableSharedFlow<EndpointFound>()
    private val _connectionInitiated = MutableSharedFlow<ConnectionInitiated>()
    private val _connectionResult = MutableSharedFlow<ConnectionResult>()
    private val _payloadReceived = MutableSharedFlow<PayloadReceived>()
    private val _endpointDisconnected = MutableSharedFlow<EndpointDisconnected>()

    override val endpointFound: Flow<EndpointFound> = _endpointFound.asSharedFlow()
    override val connectionInitiated: Flow<ConnectionInitiated> = _connectionInitiated.asSharedFlow()
    override val connectionResult: Flow<ConnectionResult> = _connectionResult.asSharedFlow()
    override val payloadReceived: Flow<PayloadReceived> = _payloadReceived.asSharedFlow()
    override val endpointDisconnected: Flow<EndpointDisconnected> = _endpointDisconnected.asSharedFlow()

    override fun availability(): FabricAvailability = FabricAvailability.Available

    override suspend fun startAdvertising(displayName: String, serviceId: String) {
        _radio.onStartAdvertising(displayName)
    }

    override suspend fun stopAdvertising() { /* no-op in fake */ }

    override suspend fun startDiscovery(serviceId: String) {
        _radio.onStartDiscovery()
    }

    override suspend fun stopDiscovery() { /* no-op in fake */ }

    override suspend fun requestConnection(displayName: String, endpointId: String) {
        _radio.onRequestConnection(displayName, endpointId)
    }

    override suspend fun acceptConnection(endpointId: String) {
        _radio.onAcceptConnection(endpointId)
    }

    override suspend fun disconnect(endpointId: String) {
        _radio.onDisconnect(endpointId)
    }

    override suspend fun sendBytesPayload(endpointId: String, bytes: ByteArray) {
        _radio.onSendBytesPayload(endpointId, bytes)
    }

    // ── emit router (called by the radio) ─────────────────────────────────────

    internal suspend fun emit(event: EndpointFound) = _endpointFound.emit(event)
    internal suspend fun emit(event: ConnectionInitiated) = _connectionInitiated.emit(event)
    internal suspend fun emit(event: ConnectionResult) = _connectionResult.emit(event)
    internal suspend fun emit(event: PayloadReceived) = _payloadReceived.emit(event)
    internal suspend fun emit(event: EndpointDisconnected) = _endpointDisconnected.emit(event)
}
