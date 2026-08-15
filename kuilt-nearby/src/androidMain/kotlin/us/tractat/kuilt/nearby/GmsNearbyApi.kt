package us.tractat.kuilt.nearby

import android.annotation.SuppressLint
import android.content.Context
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.tasks.await
import us.tractat.kuilt.core.DeliveryPolicy
import us.tractat.kuilt.core.FabricAvailability
import kotlin.time.Duration
import com.google.android.gms.common.ConnectionResult as GmsConnectionResult

/**
 * Real Google Nearby Connections binding for [NearbyApi] (Android-only).
 *
 * This is the thin layer the Phase 3 spike validates the contract against: it adapts
 * the callback-based `ConnectionsClient` API onto the suspend/`Flow` [NearbyApi]
 * surface that the pure-`commonMain` adapter ([NearbyLoom]) consumes. Strategy is
 * `P2P_STAR`. The chunking codec, connect state machine, and `Seam` logic all live in
 * `commonMain` — this class adds no protocol logic, only the SDK adaptation.
 *
 * Runtime permissions (Bluetooth / Wi-Fi / location / `NEARBY_WIFI_DEVICES`) are the
 * **consuming app's** responsibility — they're out of scope for this conformance spike.
 * Hence the class-level `@SuppressLint("MissingPermission")`.
 *
 * It also carries the fabric's **live** capability signal ([radioState], #1543): an
 * [AndroidRadioObserver] watching the Bluetooth and Wi-Fi state broadcasts, started at construction
 * and folded by [NearbySeam] into [us.tractat.kuilt.core.Seam.capability]. That observer — not
 * [availability], which answers the *static* platform-support question — is what takes a woven
 * Nearby seam off the [FabricAvailability.Unknown] floor.
 */
@SuppressLint("MissingPermission")
public class GmsNearbyApi(context: Context) : NearbyApi {

    private val appContext: Context = context.applicationContext
    private val client: ConnectionsClient = Nearby.getConnectionsClient(appContext)
    private val strategy: Strategy = Strategy.P2P_STAR

    private val _endpointFound = MutableSharedFlow<EndpointFound>(extraBufferCapacity = 64)
    private val _connectionInitiated = MutableSharedFlow<ConnectionInitiated>(extraBufferCapacity = 64)
    private val _connectionResult = MutableSharedFlow<ConnectionResult>(extraBufferCapacity = 64)
    private val _payloadReceived = MutableSharedFlow<PayloadReceived>(extraBufferCapacity = 64)
    private val _endpointDisconnected = MutableSharedFlow<EndpointDisconnected>(extraBufferCapacity = 64)

    override val endpointFound: Flow<EndpointFound> = _endpointFound.asSharedFlow()
    override val connectionInitiated: Flow<ConnectionInitiated> = _connectionInitiated.asSharedFlow()
    override val connectionResult: Flow<ConnectionResult> = _connectionResult.asSharedFlow()
    override val payloadReceived: Flow<PayloadReceived> = _payloadReceived.asSharedFlow()
    override val endpointDisconnected: Flow<EndpointDisconnected> = _endpointDisconnected.asSharedFlow()

    // The #1543 live-capability observer. Started eagerly so a seam woven immediately after
    // construction already has a verdict to fold rather than the unobserved floor; see
    // [AndroidRadioObserver] for why this watches the radio-power broadcasts rather than a
    // ConnectivityManager.NetworkCallback, and why the Play-services half is polled.
    private val radioObserver = AndroidRadioObserver(appContext).also { it.start() }

    override val radioState: StateFlow<NearbyRadioState?> = radioObserver.radioState

    /**
     * Release the radio observer's broadcast receiver. Optional: it is registered against the
     * application context, so leaving it for the process lifetime leaks nothing. Provided for a
     * consumer that constructs this class directly (rather than via [nearbyLoom]) and wants to stop
     * observing early. Idempotent.
     */
    public fun close() {
        radioObserver.close()
    }

    private val lifecycleCallback =
        object : ConnectionLifecycleCallback() {
            override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
                _connectionInitiated.tryEmit(ConnectionInitiated(endpointId, info.endpointName))
            }

            override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
                val ok = resolution.status.isSuccess
                _connectionResult.tryEmit(
                    ConnectionResult(
                        endpointId = endpointId,
                        success = ok,
                        reason = if (ok) null else resolution.status.statusMessage,
                    ),
                )
            }

            override fun onDisconnected(endpointId: String) {
                _endpointDisconnected.tryEmit(EndpointDisconnected(endpointId))
            }
        }

    private val payloadCallback =
        object : PayloadCallback() {
            override fun onPayloadReceived(endpointId: String, payload: Payload) {
                if (payload.type == Payload.Type.BYTES) {
                    val bytes = payload.asBytes() ?: return
                    _payloadReceived.tryEmit(PayloadReceived(endpointId, bytes))
                }
            }

            // BYTES payloads arrive whole, so transfer progress needs no handling.
            override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
        }

    private val discoveryCallback =
        object : EndpointDiscoveryCallback() {
            override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                _endpointFound.tryEmit(EndpointFound(endpointId, info.endpointName))
            }

            override fun onEndpointLost(endpointId: String) {}
        }

    override fun availability(): FabricAvailability {
        val status = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(appContext)
        return if (status == GmsConnectionResult.SUCCESS) {
            FabricAvailability.Available
        } else {
            FabricAvailability.Unavailable("Google Play Services unavailable (code $status)")
        }
    }

    override suspend fun startAdvertising(displayName: String, serviceId: String) {
        val options = AdvertisingOptions.Builder().setStrategy(strategy).build()
        client.startAdvertising(displayName, serviceId, lifecycleCallback, options).await()
    }

    override suspend fun stopAdvertising() {
        client.stopAdvertising()
    }

    override suspend fun startDiscovery(serviceId: String) {
        val options = DiscoveryOptions.Builder().setStrategy(strategy).build()
        client.startDiscovery(serviceId, discoveryCallback, options).await()
    }

    override suspend fun stopDiscovery() {
        client.stopDiscovery()
    }

    override suspend fun requestConnection(displayName: String, endpointId: String) {
        client.requestConnection(displayName, endpointId, lifecycleCallback).await()
    }

    override suspend fun acceptConnection(endpointId: String) {
        client.acceptConnection(endpointId, payloadCallback).await()
    }

    override suspend fun disconnect(endpointId: String) {
        client.disconnectFromEndpoint(endpointId)
    }

    override suspend fun sendBytesPayload(endpointId: String, bytes: ByteArray) {
        client.sendPayload(endpointId, Payload.fromBytes(bytes)).await()
    }
}

/**
 * Construct a [NearbyLoom] backed by the real Google Nearby Connections SDK.
 * Android entry point for the fabric; the consuming app supplies a [Context] and
 * (per [GmsNearbyApi]) the Nearby runtime permissions.
 *
 * Follows the uniform `Loom`-construction convention (#1430): the fabric's own required
 * parameter first, then the universal knobs this fabric can genuinely honour, then its
 * remaining fabric-specific tuning. Two of the convention's universal knobs are
 * **deliberately absent** rather than accepted and ignored:
 *
 * - **`selfId`** — one [NearbyLoom] weaves both ends of a session, so identity is minted
 *   per weave and a loom-level id would collide the two seams. See [NearbyLoom].
 * - **`weaveTimeout`** — nothing here bounds a rendezvous; [handshakeTimeout] bounds a
 *   single connection instead. See [NearbyLoom.DEFAULT_HANDSHAKE_TIMEOUT].
 *
 * @param context           Android [Context]; only its `applicationContext` is retained.
 * @param serviceId         Nearby Connections service ID. Must match on both devices.
 * @param policy            Delivery policy for every woven seam's inbound buffer.
 * @param handshakeTimeout  Ceiling on one connection's handshake — not a weave timeout.
 * @param maxChunkPayload   Per-chunk payload cap forwarded to [ChunkCodec].
 */
public fun nearbyLoom(
    context: Context,
    serviceId: String = NearbyLoom.DEFAULT_SERVICE_ID,
    policy: DeliveryPolicy = DeliveryPolicy.Reliable,
    handshakeTimeout: Duration = NearbyLoom.DEFAULT_HANDSHAKE_TIMEOUT,
    maxChunkPayload: Int = ChunkCodec.MAX_CHUNK_PAYLOAD,
): NearbyLoom = NearbyLoom(
    api = GmsNearbyApi(context),
    serviceId = serviceId,
    policy = policy,
    handshakeTimeout = handshakeTimeout,
    maxChunkPayload = maxChunkPayload,
)
