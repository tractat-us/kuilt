package us.tractat.kuilt.nearby

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
 *
 * ## No private scope — emit directly
 * All event delivery is done via `suspend` emit calls on the caller's coroutine,
 * not via a private [kotlinx.coroutines.CoroutineScope]. This ensures all work
 * runs on the test dispatcher under `runTest`'s virtual clock, with no coroutine
 * leaks between tests.
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

    // Shared api reference (set when FakeNearbyApi is created).
    private lateinit var api: FakeNearbyApi

    internal fun bind(api: FakeNearbyApi) { this.api = api }

    suspend fun onStartAdvertising(displayName: String) {
        advertisingDisplayName = displayName
        tryAutoConnect()
    }

    suspend fun onStartDiscovery() {
        discoveryStarted = true
        tryAutoConnect()
    }

    /**
     * Once both sides are active, deliver [EndpointFound] to the discoverer.
     * The discoverer will then call [requestConnection].
     */
    private suspend fun tryAutoConnect() {
        if (advertisingDisplayName == null || !discoveryStarted) return
        val displayName = advertisingDisplayName ?: return
        api.emit(EndpointFound(DISCOVERER_SEES_ADVERTISER_AS, displayName))
    }

    /**
     * Called when one side calls [requestConnection].
     * Emits [ConnectionInitiated] on BOTH sides (advertiser side first, per the
     * ordering contract that lets the host's first-seen state machine capture its ID).
     */
    suspend fun onRequestConnection(displayName: String, endpointId: String) {
        // Advertiser side: sees the discoverer arrive.
        api.emit(ConnectionInitiated(ADVERTISER_SEES_DISCOVERER_AS, displayName))
        // Discoverer side: sees the advertiser respond.
        api.emit(ConnectionInitiated(DISCOVERER_SEES_ADVERTISER_AS, advertisingDisplayName ?: "advertiser"))
    }

    /**
     * Called when either side accepts. Fires [ConnectionResult] success on BOTH sides
     * once two accepts have been received.
     */
    suspend fun onAcceptConnection(endpointId: String) {
        acceptCount++
        if (acceptCount == 2) {
            acceptCount = 0
            api.emit(ConnectionResult(ADVERTISER_SEES_DISCOVERER_AS, success = true))
            api.emit(ConnectionResult(DISCOVERER_SEES_ADVERTISER_AS, success = true))
        }
    }

    /**
     * Route a bytes payload: flips the endpoint ID so the receiver sees it from
     * its own perspective.
     */
    suspend fun onSendBytesPayload(endpointId: String, bytes: ByteArray) {
        val deliverAsEndpointId = when (endpointId) {
            ADVERTISER_SEES_DISCOVERER_AS -> DISCOVERER_SEES_ADVERTISER_AS
            DISCOVERER_SEES_ADVERTISER_AS -> ADVERTISER_SEES_DISCOVERER_AS
            else -> return // unknown endpoint, ignore
        }
        api.emit(PayloadReceived(deliverAsEndpointId, bytes))
    }

    suspend fun onDisconnect(endpointId: String) {
        val deliverAsEndpointId = when (endpointId) {
            ADVERTISER_SEES_DISCOVERER_AS -> DISCOVERER_SEES_ADVERTISER_AS
            DISCOVERER_SEES_ADVERTISER_AS -> ADVERTISER_SEES_DISCOVERER_AS
            else -> return
        }
        api.emit(EndpointDisconnected(deliverAsEndpointId))
    }
}

/**
 * Fake [NearbyApi] backed by a [FakeNearbyRadio].
 *
 * Create one instance per loom; both [open] and [join] on the same loom use
 * this single api. The radio partitions events by endpoint ID so both
 * state machines can coexist without interference.
 *
 * Shared flows use [extraBufferCapacity] = 1 as a defensive buffer for
 * sequential same-coroutine emit patterns (e.g. two ConnectionInitiated events
 * emitted back-to-back before any collector resumes). The flows still have no
 * replay — subscribers only see events after they subscribe.
 */
internal class FakeNearbyApi(radio: FakeNearbyRadio) : NearbyApi {

    private val _radio = radio.also { it.bind(this) }

    // Small buffer defends against back-to-back emits on the same coroutine
    // before a collector can resume, while still having no replay.
    private val _endpointFound = MutableSharedFlow<EndpointFound>(extraBufferCapacity = 1)
    private val _connectionInitiated = MutableSharedFlow<ConnectionInitiated>(extraBufferCapacity = 2)
    private val _connectionResult = MutableSharedFlow<ConnectionResult>(extraBufferCapacity = 2)
    private val _payloadReceived = MutableSharedFlow<PayloadReceived>(extraBufferCapacity = 64)
    private val _endpointDisconnected = MutableSharedFlow<EndpointDisconnected>(extraBufferCapacity = 1)

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
