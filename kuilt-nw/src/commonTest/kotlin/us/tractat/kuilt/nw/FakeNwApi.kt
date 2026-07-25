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
    // The stable identity this device publishes in its Bonjour TXT record (Option A, #1502/#1660) —
    // the fake twin of `RealNwApi`'s constructor `selfId`. Non-null ⇒ the emitted [NwEndpoint.id] is
    // this peerId; null (or blank — malformed) ⇒ the id derives from the advertised serviceName (the
    // pre-fix backstop). A test models the Option A fix by passing `peerId = selfId.value`.
    private val peerId: String? = null,
    /**
     * Whether this device's BROWSER opts in to Bonjour TXT records — the fake twin of
     * `nw_browse_descriptor_set_include_txt_record(descriptor, true)` in `RealNwApi.startBrowsing`
     * (#1706). Defaults to `true` because the production binding now makes that call; a test models
     * the shipped omission (the #1660 root-1 bug) by passing `false`, which drops this browser back to
     * Network.framework's OWN default of never querying TXT — every endpoint then falls back to
     * `id = serviceName` and the loom's pre-dial self-filter can no longer fire.
     *
     * Note what this knob can and cannot prove. It makes the *consequence* of skipping the opt-in
     * testable on the JVM, and pins the opt-in as load-bearing; it cannot detect `RealNwApi` dropping
     * the native call, which lives in appleMain behind Network.framework.
     */
    private val browserIncludesTxtRecord: Boolean = true,
) : NwApi {

    private val _endpointFound = MutableSharedFlow<NwEndpoint>(extraBufferCapacity = 16)
    private val _endpointLost = MutableSharedFlow<NwEndpoint>(extraBufferCapacity = 16)
    private val _connectionOpened = MutableSharedFlow<NwConnectionOpened>(extraBufferCapacity = 16)
    private val _bytesReceived = MutableSharedFlow<NwBytesReceived>(extraBufferCapacity = 64)
    private val _connectionClosed = MutableSharedFlow<NwConnectionClosed>(extraBufferCapacity = 16)

    // The ONE drop-tolerant per-connection [NwConnState] STATE (#1539), matching RealNwApi's MutableStateFlow
    // and unifying the former separate viability (#1509) and closed-markers (#1522) maps. Viability sets
    // Viable/PathLost (Closed-dominant); a close latches Closed (monotone, terminal), which survives even when
    // the connectionClosed EVENT is dropped (the [dropCloseEvents] hook). Driven only from the one test coroutine.
    private val _connectionStates = MutableStateFlow<Map<NwConnectionId, NwConnState>>(emptyMap())
    private val closedOrder = ArrayDeque<NwConnectionId>()

    // Controllable live network-path state — the test vehicle for the reactive-capability path (#1541),
    // standing in for RealNwApi's nw_path_monitor. Defaults to null ("unknown"); a test flips it to drive
    // the seam's capability under virtual time. Latest-value STATE, matching RealNwApi's MutableStateFlow.
    private val _pathState = MutableStateFlow<NwPathState?>(null)

    // #1618 Track A: the live connIds this device currently holds (open→closed), mirroring RealNwApi's
    // `connections` registry — the set a device-path-unsatisfied event demotes to PathLost. Added on
    // [emitConnectionOpened], removed on [markConnectionClosed]. Touched only from the one test coroutine.
    private val liveConnIds = mutableSetOf<NwConnectionId>()

    // #1618 Track A edge-tracking, mirroring RealNwApi.onDevicePathState: whether the last device path was
    // unsatisfied, and the connIds we demoted on that edge so the matching recovery restores exactly those.
    private var devicePathUnsatisfied = false
    private val devicePathLostConns = mutableSetOf<NwConnectionId>()

    override val endpointFound: Flow<NwEndpoint> = _endpointFound.asSharedFlow()
    override val endpointLost: Flow<NwEndpoint> = _endpointLost.asSharedFlow()
    override val connectionOpened: Flow<NwConnectionOpened> = _connectionOpened.asSharedFlow()
    override val bytesReceived: Flow<NwBytesReceived> = _bytesReceived.asSharedFlow()
    override val connectionClosed: Flow<NwConnectionClosed> = _connectionClosed.asSharedFlow()
    override val connectionStates: StateFlow<Map<NwConnectionId, NwConnState>> = _connectionStates.asStateFlow()
    override val pathState: StateFlow<NwPathState?> = _pathState.asStateFlow()

    /**
     * Test hook for #1541: drive a live `NWPathMonitor` transition (path up/down, Wi-Fi↔cellular, a
     * Local-Network-permission denial) directly under virtual time. Sets the latest-value path STATE; the
     * seam folds it into its live [us.tractat.kuilt.core.Seam.capability]. `null` restores "unknown".
     *
     * ## #1618 Track A — mirrors `RealNwApi.onDevicePathState`
     * A device-path **unsatisfied** transition also drives every LIVE connection to [NwConnState.PathLost]
     * (fast self-loss), so the seam self-observes a radios-off drop near its #1478 grace window instead of
     * waiting 15–75s for the peer's silence. The matching **satisfied** recovery restores exactly the
     * connections that edge demoted (a reshuffle blip is a no-op — false-positive-safe by construction).
     * This is the fake twin of the production hook; the real emission is proven on hardware.
     */
    internal fun emitPathState(state: NwPathState?) {
        _pathState.value = state
        val unsatisfied = state?.status == NwPathStatus.Unsatisfied
        when {
            unsatisfied && !devicePathUnsatisfied -> {
                devicePathUnsatisfied = true
                devicePathLostConns.clear()
                devicePathLostConns.addAll(liveConnIds)
                devicePathLostConns.forEach { emitConnectionViability(it, viable = false) }
            }
            !unsatisfied && devicePathUnsatisfied -> {
                devicePathUnsatisfied = false
                val restore = devicePathLostConns.toList()
                devicePathLostConns.clear()
                restore.forEach { emitConnectionViability(it, viable = true) }
            }
        }
    }

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
        radio.markListening(deviceId, serviceName, serviceType, peerId)
    }

    /** Test hook: total [stopListening] calls — lets a test prove `NwSeam.close()` stops advertising (#1419). */
    var stopListeningCalls: Int = 0
        private set

    override suspend fun stopListening() {
        stopListeningCalls += 1
        radio.markStopListening(deviceId)
    }

    override suspend fun startBrowsing(serviceType: String) {
        // Mirrors RealNwApi.startBrowsing's explicit nw_browse_descriptor_set_include_txt_record call:
        // the radio (like Network.framework) delivers no TXT unless the browser asks (#1706).
        radio.markBrowsing(deviceId, serviceType, includeTxtRecord = browserIncludesTxtRecord)
    }

    /** Test hook: total [stopBrowsing] calls — lets a test prove `NwSeam.close()` stops browsing (#1419). */
    var stopBrowsingCalls: Int = 0
        private set

    override suspend fun stopBrowsing() {
        stopBrowsingCalls += 1
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
    internal suspend fun emitEndpointLost(event: NwEndpoint) = _endpointLost.emit(event)
    internal suspend fun emitConnectionOpened(event: NwConnectionOpened) {
        liveConnIds += event.connectionId // #1618: track liveness for the device-path self-loss demotion
        _connectionOpened.emit(event)
    }
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
        // Set the per-connection LATEST path state (#1509/#1539): Viable or PathLost. `update` is an atomic CAS,
        // safe from any thread; the seam reconciles from the latest map value, never losing it. Closed-dominant:
        // a viability change for an already-[NwConnState.Closed] id is ignored (mirrors RealNwApi.setViability).
        _connectionStates.update { cur ->
            if (cur[connectionId] is NwConnState.Closed) cur
            else cur + (connectionId to if (viable) NwConnState.Viable else NwConnState.PathLost)
        }
    }

    /**
     * Latch [connectionId] into the drop-tolerant [connectionStates] as [NwConnState.Closed] (#1522/#1539), with
     * [reason] (`null` = graceful) — terminal, monotone and dominant, so it supersedes any prior Viable/PathLost
     * entry and a later [emitConnectionViability] cannot revert it. Mirrors `RealNwApi.markClosed`: the entry
     * latches until the newest [CLOSED_RETENTION_CAP] cap prunes the oldest. Driven by [FakeNwRadio.disconnect]
     * on BOTH sides of a link (as each side's own `closeConnection` would mark its own connId), so a zombie is
     * evicted via STATE even when the close EVENT drops.
     */
    internal fun markConnectionClosed(connectionId: NwConnectionId, reason: String?) {
        liveConnIds -= connectionId // #1618: a closed conn is no longer demotable by a device-path event
        closedOrder.addLast(connectionId)
        if (closedOrder.size > CLOSED_RETENTION_CAP) {
            // Hoist the FIFO mutation OUT of the CAS lambda (see RealNwApi.markClosed).
            val evicted = closedOrder.removeFirst()
            _connectionStates.update { it - evicted }
        }
        _connectionStates.update { it + (connectionId to NwConnState.Closed(reason)) }
    }

    internal companion object {
        /** FIFO retention bound on [connectionStates] `Closed` entries — the newest N are retained (mirrors RealNwApi, 1024). */
        const val CLOSED_RETENTION_CAP: Int = 1024
    }
}
