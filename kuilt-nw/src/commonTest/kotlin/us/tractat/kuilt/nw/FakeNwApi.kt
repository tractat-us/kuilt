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
    /**
     * Whether this device's ADVERTISED TXT record is readable the instant the endpoint becomes
     * discoverable (#1706). `true` is the simultaneous case; `false` models Network.framework delivering
     * the browse `add` BEFORE the TXT record resolves, so browsers first see the endpoint with its
     * identity unknown (`id = serviceName`) and only a later [FakeNwRadio.resolveTxt] supplies it.
     */
    private val txtResolvedOnAdvertise: Boolean = true,
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

    // Controllable inbound-listener state (#2449), standing in for RealNwApi's nw_listener state-changed
    // handler. [startListening] drives Starting → (Ready | [listenFailure]); [emitListenerFailed] drives the
    // ASYNCHRONOUS mid-session death that is the actual field shape — a listener that was up for minutes and
    // then failed with the app suspended. Latest-value STATE, matching RealNwApi's MutableStateFlow.
    private val _listenerState = MutableStateFlow<NwListenerState>(NwListenerState.Unknown)

    // The Bonjour instance name this device is ACTUALLY advertising (#2420), standing in for RealNwApi's
    // nw_listener_set_advertised_endpoint_changed_handler. Set by [startListening] to the name that was
    // asked for; [emitAdvertisedRename] drives the mDNS conflict rename ("alice" → "alice (2)") that makes
    // the requested and effective names diverge. Latest-value STATE, matching RealNwApi.
    private val _advertisedName = MutableStateFlow<String?>(null)

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
    override val listenerState: StateFlow<NwListenerState> = _listenerState.asStateFlow()
    override val advertisedName: StateFlow<String?> = _advertisedName.asStateFlow()

    /**
     * Test hook for #2420: model mDNS resolving an instance-name collision by renaming **this** device's
     * advertisement (`"alice"` → `"alice (2)"`), which on hardware arrives on
     * `nw_listener_set_advertised_endpoint_changed_handler` some time after the listener came up.
     *
     * This is the fake's model of the fact a device could not previously observe about ITSELF: in the
     * 2026-08-15 field session the rename landed 6 s after the fatal dial and was visible only from the
     * other handset. Note what it can and cannot prove — it makes the *consequence* (a formation dump whose
     * requested and effective names disagree) assertable on the JVM; it cannot detect `RealNwApi` failing to
     * wire the native handler, which lives in appleMain behind Network.framework.
     */
    internal fun emitAdvertisedRename(name: String?) {
        _advertisedName.value = name
    }

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
     *
     * **A blunt instrument, and not the interesting failure (#2455).** It fails EVERY send regardless of
     * which handle the send names, so it can only prove the seam REACTS to a throw — never that a send
     * addressed to a connection that is genuinely gone produces one. The handle-shaped failure lives in
     * [FakeNwRadio.send], which now throws for an unknown/closed link exactly as `RealNwApi` does;
     * [FakeNwRadio.severLinksSilently] is how a test reaches that state.
     */
    var failSend: Boolean = false

    /**
     * The dropped-close test hook for #1522: when `true`, [emitConnectionClosed] SWALLOWS the close EVENT
     * (simulating a lossy `tryEmit` drop under buffer pressure) while [FakeNwRadio.disconnect] still marks the
     * drop-tolerant STATE via [markConnectionClosed]. This deterministically reproduces the scenario where a
     * peer would be stranded as a zombie if the seam relied on the event alone — the STATE backstop must evict.
     */
    var dropCloseEvents: Boolean = false

    /**
     * The INVERSE of [dropCloseEvents], and the one this fake could not previously express: when `false`
     * this device publishes no [NwConnState.Closed] STATE at all, so the lossy close EVENT is the only
     * teardown signal it ever produces.
     *
     * That is not a hypothetical binding. [NwApi.connectionStates] carries a **shared empty default**, so
     * an implementation that never wires the state callback is a supported one by construction — and for
     * it, every teardown obligation `NwSeam` fulfils through [NwConnState.Closed] must ALSO be fulfilled
     * by [NwApi.connectionClosed]. Without this knob the two paths are indistinguishable in the harness:
     * `connectionClosedLoop`'s half can be deleted outright and every test stays green, because
     * `reconcileStates` → `removeByConn` silently covers for it (#2425).
     */
    var reportsCloseStates: Boolean = true

    override fun availability(): FabricAvailability = FabricAvailability.Available

    /**
     * Test hook for #2449: the verdict this device's NEXT (and every subsequent) [startListening] receives.
     * `null` — the default — models a bind that succeeds, publishing [NwListenerState.Ready]; a non-null
     * value models a bind the OS refuses, publishing that [NwListenerState.Failed] instead.
     *
     * The fake must be able to REFUSE, or "does the loom retry a failing listener?" would be answered by a
     * fake that cannot fail twice: every retry would come up Ready, the consecutive-failure counter would
     * reset each round, and the bounded-give-up property could never be exercised at all.
     */
    var listenFailure: NwListenerState.Failed? = null

    /** Test hook: total [startListening] calls — lets a test count re-listens after a listener failure (#2449). */
    var startListeningCalls: Int = 0
        private set

    /**
     * Test hook for #2449: when `true`, [startListening] THROWS after publishing
     * [NwListenerState.Starting] — a binding that fails synchronously and therefore never publishes a
     * verdict at all. The interesting case precisely because the campaign has nothing to await: a
     * supervisor that only ever waits on [listenerState] parks here forever.
     */
    var listenThrows: Boolean = false

    /**
     * Test hook for #2449: when `false`, this device NEVER publishes a listener state — [listenerState] sits
     * at its [NwListenerState.Unknown] default forever, modelling a binding (`BridgeNwApi`) that wires no
     * listener signal.
     *
     * The case that must stay INERT. The supervisor bounds how long it will wait for a verdict, and the
     * whole point of that bound is to convert a silent stall into a retry — so a binding whose silence is
     * permanent and expected has to be excluded explicitly, or the bound would turn every such binding into
     * a perpetual re-listen loop. That is a behaviour regression a test elsewhere in this file cannot see.
     */
    var reportsListenerState: Boolean = true

    override suspend fun startListening(serviceName: String, serviceType: String) {
        startListeningCalls += 1
        // Mirrors RealNwApi.startListening: publish Starting BEFORE the listener can report, so a watcher
        // never reads the previous listener's terminal Failed as this attempt's verdict.
        if (reportsListenerState) _listenerState.value = NwListenerState.Starting
        if (listenThrows) throw RuntimeException("simulated synchronous listen failure on device '$deviceId'")
        radio.markListening(deviceId, serviceName, serviceType, peerId, txtResolvedOnAdvertise)
        if (reportsListenerState) _listenerState.value = listenFailure ?: NwListenerState.Ready
        // The uncontested case: the OS advertises the name that was asked for. A test drives the contested
        // one with [emitAdvertisedRename]. Deliberately AFTER the verdict, mirroring the real ordering —
        // the advertised-endpoint callback fires once the listener is up, never before.
        if (reportsListenerState) _advertisedName.value = serviceName
    }

    /** Test hook for #2449: drive `nw_listener_state_waiting` — a non-verdict the campaign must not park on. */
    internal fun emitListenerWaiting(domain: Int, code: Int) {
        _listenerState.value = NwListenerState.Waiting(domain, code)
    }

    /**
     * Test hook for #2449: drive an ASYNCHRONOUS listener failure on an already-[NwListenerState.Ready]
     * listener — the real field shape, where the bind succeeded at weave and the OS tore it down minutes
     * later while the app was suspended. Distinct from [listenFailure], which fails the bind itself.
     */
    internal fun emitListenerFailed(domain: Int, code: Int) {
        _listenerState.value = NwListenerState.Failed(domain, code)
    }

    /** Test hook: total [stopListening] calls — lets a test prove `NwSeam.close()` stops advertising (#1419). */
    var stopListeningCalls: Int = 0
        private set

    override suspend fun stopListening() {
        stopListeningCalls += 1
        _advertisedName.value = null // nothing is advertised any more; "unknown" is the honest answer
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
        if (failSend) throw NwSendFailedException("simulated send failure on device '$deviceId'")
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
        // A binding that wires no state callback publishes nothing here — see [reportsCloseStates]. The
        // liveConnIds bookkeeping above still runs: that models the transport's own view of the link,
        // not the signal it chooses to publish.
        if (!reportsCloseStates) return
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
