package us.tractat.kuilt.nw

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import us.tractat.kuilt.core.FabricAvailability

/**
 * Fake [NwApi] for ONE simulated device, backed by a shared [FakeNwRadio].
 *
 * Role-split (fixes #1404): create N of these — one per device — all sharing the same
 * [radio]. Each has its own event flows, so no single instance ever sees both ends of a
 * link. This is the JVM test vehicle that lets `NwSeam`/`NwLoom` transport logic (Tasks
 * 2.5 / 2.7) run under `runTest`'s virtual clock without Network.framework.
 *
 * All `NwApi` operations delegate to the [radio], which routes events back onto the
 * relevant device(s) via the internal `emit*` routers below.
 *
 * ## No-replay flows with a defensive buffer
 * Each event flow is a [MutableSharedFlow] with **no replay** — a subscriber sees only
 * events emitted after it subscribes (so collectors must subscribe before the triggering
 * call). A small [MutableSharedFlow.extraBufferCapacity] defends against back-to-back
 * same-coroutine emits before a collector resumes; `bytesReceived` gets a generous buffer.
 *
 * ## No private scope
 * There is no private [kotlinx.coroutines.CoroutineScope]. Every emit is a `suspend` call
 * on the caller's coroutine, keeping all work on the test dispatcher with no coroutine leaks.
 */
internal class FakeNwApi(
    private val radio: FakeNwRadio,
    val deviceId: String,
    private val serviceName: String,
) : NwApi {

    private val _endpointFound = MutableSharedFlow<NwEndpoint>(extraBufferCapacity = 16)
    private val _connectionOpened = MutableSharedFlow<NwConnectionOpened>(extraBufferCapacity = 16)
    private val _bytesReceived = MutableSharedFlow<NwBytesReceived>(extraBufferCapacity = 64)
    private val _connectionClosed = MutableSharedFlow<NwConnectionClosed>(extraBufferCapacity = 16)

    // Drop-tolerant per-connection latest-value STATE (#1509), matching RealNwApi's MutableStateFlow.
    // Intermediate transitions may coalesce under backpressure, but the LATEST value per connection is
    // never lost — so the seam can reconcile a recovery/loss that a lossy event stream would have dropped.
    private val _connectionViability = MutableStateFlow<Map<NwConnectionId, Boolean>>(emptyMap())

    // Drop-tolerant MONOTONE close markers (#1522), matching RealNwApi's MutableStateFlow. Entries only
    // appear (latched terminal) until FIFO-cap-pruned — so a closure survives even when the connectionClosed
    // EVENT is dropped (the [dropCloseEvents] hook). Driven only from the one test coroutine, so no lock.
    private val _closedConnections = MutableStateFlow<Map<NwConnectionId, String?>>(emptyMap())
    private val closedOrder = ArrayDeque<NwConnectionId>()

    override val endpointFound: Flow<NwEndpoint> = _endpointFound.asSharedFlow()
    override val connectionOpened: Flow<NwConnectionOpened> = _connectionOpened.asSharedFlow()
    override val bytesReceived: Flow<NwBytesReceived> = _bytesReceived.asSharedFlow()
    override val connectionClosed: Flow<NwConnectionClosed> = _connectionClosed.asSharedFlow()
    override val connectionViability: StateFlow<Map<NwConnectionId, Boolean>> = _connectionViability.asStateFlow()
    override val closedConnections: StateFlow<Map<NwConnectionId, String?>> = _closedConnections.asStateFlow()

    init {
        radio.register(this)
    }

    /**
     * Test hook: when `true`, [send] throws instead of delivering, exercising `NwSeam`'s
     * send-failure eviction path (`removeByConn`). Toggle it AFTER the mesh has formed so the
     * identity handshake still succeeds.
     */
    var failSend: Boolean = false

    /**
     * The dropped-close test hook for #1522: when `true`, [emitConnectionClosed] SWALLOWS the close EVENT
     * (simulating a lossy `tryEmit` drop under buffer pressure) while [FakeNwRadio.disconnect] still marks the
     * drop-tolerant STATE via [markConnectionClosed]. This deterministically reproduces the scenario where a
     * peer would be stranded as a zombie if the seam relied on the event alone — the STATE backstop must evict.
     */
    var dropCloseEvents: Boolean = false

    override fun availability(): FabricAvailability = FabricAvailability.Available

    override suspend fun startListening(serviceName: String, serviceType: String) {
        radio.markListening(deviceId, serviceName, serviceType)
    }

    override suspend fun stopListening() {
        radio.markStopListening(deviceId)
    }

    override suspend fun startBrowsing(serviceType: String) {
        radio.markBrowsing(deviceId, serviceType)
    }

    override suspend fun stopBrowsing() {
        radio.markStopBrowsing(deviceId)
    }

    /** Test hook: total outbound [connect] calls issued on this device — lets a test count redials (#1513). */
    var connectCalls: Int = 0
        private set

    override suspend fun connect(endpoint: NwEndpoint) {
        connectCalls += 1
        radio.connect(deviceId, endpoint)
    }

    override suspend fun disconnect(connectionId: NwConnectionId) {
        radio.disconnect(deviceId, connectionId)
    }

    override suspend fun send(connectionId: NwConnectionId, bytes: ByteArray) {
        if (failSend) throw RuntimeException("simulated send failure on device '$deviceId'")
        radio.send(deviceId, connectionId, bytes)
    }

    // ── emit routers (called by the radio) ─────────────────────────────────────

    internal suspend fun emitEndpointFound(event: NwEndpoint) = _endpointFound.emit(event)
    internal suspend fun emitConnectionOpened(event: NwConnectionOpened) = _connectionOpened.emit(event)
    internal suspend fun emitBytesReceived(event: NwBytesReceived) = _bytesReceived.emit(event)

    /**
     * Emit the close EVENT — UNLESS [dropCloseEvents] is set, in which case the event is swallowed (#1522).
     * The drop-tolerant close STATE is marked separately via [markConnectionClosed] (from [FakeNwRadio]), so a
     * dropped event still leaves a positive closure marker for the seam to reconcile.
     */
    internal suspend fun emitConnectionClosed(event: NwConnectionClosed) {
        if (dropCloseEvents) return
        _connectionClosed.emit(event)
    }

    /**
     * Test hook for #1478: drive a Network.framework `ready ⇄ waiting` viability transition on
     * [connectionId] directly under virtual time (the real transition is a native `nw_connection`
     * state change with no injectable clock). `viable=false` simulates a `ready→waiting` path loss;
     * `viable=true` a `waiting→ready` recovery. The connId is the deterministic handle this device
     * sees for the link (`conn-<deviceId>-<n>` — see [FakeNwRadio]).
     */
    internal fun emitConnectionViability(connectionId: NwConnectionId, viable: Boolean) {
        // Set the per-connection LATEST viability state (#1509). `update` is an atomic CAS, so this is
        // safe to call from any thread; the seam reconciles from the latest map value, never losing it.
        _connectionViability.update { it + (connectionId to viable) }
    }

    /**
     * Prune [connectionId]'s viability entry when the connection closes — mirrors `RealNwApi.clearViability`
     * so the fake honours the "a connection absent from the map has never established or has closed" contract
     * (#1509) instead of letting the map grow monotonically with stale keys. Driven by [FakeNwRadio.disconnect].
     */
    internal fun pruneConnectionViability(connectionId: NwConnectionId) {
        _connectionViability.update { it - connectionId }
    }

    /**
     * Mark [connectionId] closed in the drop-tolerant MONOTONE close STATE (#1522), with [reason] (`null` =
     * graceful). Mirrors `RealNwApi.markClosed`: the entry latches until the newest [CLOSED_RETENTION_CAP] cap
     * prunes the oldest. Driven by [FakeNwRadio.disconnect] on BOTH sides of a link (as each side's own
     * `closeConnection` would mark its own connId), so a zombie is evicted via STATE even when the EVENT drops.
     */
    internal fun markConnectionClosed(connectionId: NwConnectionId, reason: String?) {
        closedOrder.addLast(connectionId)
        if (closedOrder.size > CLOSED_RETENTION_CAP) {
            // Hoist the FIFO mutation OUT of the CAS lambda (see RealNwApi.markClosed).
            val evicted = closedOrder.removeFirst()
            _closedConnections.update { it - evicted }
        }
        _closedConnections.update { it + (connectionId to reason) }
    }

    internal companion object {
        /** FIFO retention bound on [closedConnections] — the newest N closures are retained (mirrors RealNwApi, 1024). */
        const val CLOSED_RETENTION_CAP: Int = 1024
    }
}
