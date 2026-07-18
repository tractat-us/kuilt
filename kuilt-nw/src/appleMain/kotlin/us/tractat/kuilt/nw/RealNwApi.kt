@file:OptIn(ExperimentalForeignApi::class)

package us.tractat.kuilt.nw

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.convert
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import platform.Network.NW_PARAMETERS_DEFAULT_CONFIGURATION
import platform.Network.nw_advertise_descriptor_create_bonjour_service
import platform.Network.nw_browse_descriptor_create_bonjour_service
import platform.Network.nw_browse_result_copy_endpoint
import platform.Network.nw_browse_result_t
import platform.Network.nw_browser_cancel
import platform.Network.nw_browser_create
import platform.Network.nw_browser_set_browse_results_changed_handler
import platform.Network.nw_browser_set_queue
import platform.Network.nw_browser_start
import platform.Network.nw_browser_t
import platform.Network.nw_connection_cancel
import platform.Network.nw_connection_create
import platform.Network.nw_connection_receive
import platform.Network.nw_connection_send
import platform.Network.nw_connection_set_queue
import platform.Network.nw_connection_set_state_changed_handler
import platform.Network.nw_connection_start
import platform.Network.nw_connection_state_cancelled
import platform.Network.nw_connection_state_failed
import platform.Network.nw_connection_state_preparing
import platform.Network.nw_connection_state_ready
import platform.Network.nw_connection_state_t
import platform.Network.nw_connection_state_waiting
import platform.Network.nw_connection_t
import platform.Network.nw_content_context_create
import platform.Network.nw_endpoint_create_host
import platform.Network.nw_endpoint_get_bonjour_service_name
import platform.Network.nw_endpoint_t
import platform.Network.nw_listener_cancel
import platform.Network.nw_listener_create
import platform.Network.nw_listener_get_port
import platform.Network.nw_listener_set_advertise_descriptor
import platform.Network.nw_listener_set_new_connection_handler
import platform.Network.nw_listener_set_queue
import platform.Network.nw_listener_set_state_changed_handler
import platform.Network.nw_listener_start
import platform.Network.nw_listener_state_failed
import platform.Network.nw_listener_state_ready
import platform.Network.nw_listener_t
import platform.Network.nw_parameters_create_secure_tcp
import platform.Network.nw_parameters_set_include_peer_to_peer
import platform.Network.nw_parameters_t
import platform.Network.nw_protocol_options_t
import platform.Network.nw_tls_copy_sec_protocol_options
import platform.Security.sec_protocol_options_add_pre_shared_key
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_after
import platform.darwin.dispatch_data_create
import platform.darwin.dispatch_data_t
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_time
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.nw.cinterop.kuilt_nw_connection_receive

private val log = KotlinLogging.logger("us.tractat.kuilt.nw.RealNwApi")

/**
 * In-process port rendezvous shared by a loopback host/joiner [RealNwApi] pair.
 *
 * The host binds an **ephemeral** listener (OS-assigned port) and, once that listener reaches
 * `ready`, publishes its REAL bound port into [hostPort]. The joiner `await`s [hostPort] before
 * synthesizing the `127.0.0.1:port` endpoint it dials. Because the listener owns its port
 * continuously — there is no pre-allocated number and no probe socket that is closed and rebound —
 * there is no TOCTOU window: the joiner always dials a port that is genuinely bound.
 */
internal class NwLoopbackRendezvous {
    /** Completed with the host listener's real bound port (host byte order) once it is `ready`. */
    val hostPort: CompletableDeferred<Int> = CompletableDeferred()
}

/**
 * Direct-loopback configuration for [RealNwApi] — the CI conformance path that bypasses Bonjour.
 *
 * Both sides bind an ephemeral `127.0.0.1` listener with no Bonjour advertise and share one
 * [rendezvous]. The HOST ([dial]` == false`) publishes its real bound port into the rendezvous on
 * listener `ready` and never dials. The JOINER ([dial]` == true`) `await`s the rendezvous, then
 * synthesizes and dials the host endpoint — the SAME [connect] path as a Bonjour-discovered peer.
 * This yields exactly one host↔joiner link over TLS-PSK on the loopback interface, driven by
 * `NwLoopbackConformanceTest`.
 */
internal class NwLoopbackConfig(
    val dial: Boolean,
    val rendezvous: NwLoopbackRendezvous,
)

/**
 * The real Apple Network.framework binding behind [NwApi] — the appleMain (iOS/macOS)
 * counterpart of the JVM `FakeNwApi`. Advertises + browses over Bonjour and dials
 * discovered peers over `includePeerToPeer` (AWDL), securing every link with the
 * out-of-band-derived TLS-PSK ([NwPskMaterial]). Raw byte movement only — framing,
 * handshake, and mesh dedup live in `NwSeam`/`NwLoom` above.
 *
 * ## Two modes — peer-to-peer (default) and direct loopback
 * The default mode is P2P (Bonjour advertise + browse + `includePeerToPeer` over AWDL). A
 * second **loopback** mode ([NwLoopbackConfig], non-null [loopback]) binds a direct **ephemeral**
 * `127.0.0.1` listener and dials the host's REAL bound port (discovered via an in-process
 * [NwLoopbackRendezvous]) instead of discovering over Bonjour — the CI-runnable path that lets
 * `NwLoopbackConformanceTest` prove the TLS-PSK link on the macOS runner (closes the
 * `securesTransport` gap for this fabric). Only [secureParams],
 * [startListening], and [startBrowsing] branch on the mode; the connection lifecycle
 * ([connect]/[retainAndStart]/[onState]/…) is shared verbatim — loopback just stores a host
 * endpoint in [endpointsById] and emits the matching [NwEndpoint], so [connect] dials it
 * through the identical path. The TLS-PSK block in [secureParams] is byte-for-byte identical in
 * both modes — securing the loopback link is the whole point of the CI proof. Loopback sets
 * `includePeerToPeer(false)` (it is exempt from Local Network Privacy and must not raise AWDL).
 *
 * ## The connection registry — the load-bearing part
 * Network.framework cancels any `nw_connection_t` whose last reference drops, so every live
 * connection is held by a **strong ref** in [connections], keyed by a minted [NwConnectionId].
 * The registry (plus the browse-result endpoint map and the listener/browser handles) is
 * guarded by a single [lock]. **No `nw_*` call is ever made while holding [lock]:** the NW
 * state/receive/new-connection handlers re-enter on the shared dispatch [queue], so calling
 * `nw_connection_*` under the lock would self-deadlock. Every operation takes the ref it needs
 * out of the map under the lock, releases the lock, and only then touches Network.framework.
 *
 * ## Close reasons via the `closing` flag
 * When *we* initiate a connection teardown ([disconnect]) we set the entry's
 * [ConnectionEntry.closing] flag before cancelling. The subsequent `cancelled` state then maps
 * to a graceful close (`NwConnectionClosed.reason == null`); a `failed` state, or a `cancelled`
 * we did not initiate, carries a non-null reason. (Precedent: `MCSessionLink` in `:kuilt-multipeer`.)
 *
 * ## Single-collection event flows
 * The four *event* flows ([endpointFound]/[connectionOpened]/[bytesReceived]/[connectionClosed]) are
 * each fed by exactly ONE callback source, so each is **single-collection** — two collectors would each
 * receive every event, duplicating delivery. The GCD handlers run off the dispatch queue (not a
 * coroutine), so they publish via [MutableSharedFlow.tryEmit] onto a buffered, no-replay flow. A full
 * buffer therefore DROPS the event under `tryEmit` (bounded backpressure — the known head-of-line concern
 * for these event streams). For [connectionClosed] a dropped event would strand a zombie peer, so it is
 * backstopped by drop-tolerant [closedConnections] STATE (see below); the other three have no such backstop.
 *
 * ## Closure is drop-tolerant monotone STATE, not just an event (#1522)
 * [connectionClosed] above is lossy: a dropped `failed`/`cancelled` close used to strand a peer forever
 * (nothing else evicts it once its viability key is also cleared). [closedConnections] is the drop-tolerant
 * backstop — a MONOTONE map of latched-terminal close markers (id → reason). [closeConnection] latches the
 * closure there via [markClosed] BEFORE the lossy `tryEmit`, so the seam reconciles the closure whether or
 * not the event survives. Entries only appear (until the FIFO cap prunes the oldest), so a closure can never
 * be conflated away — the failure mode a StateFlow live-set/seen-ready bit would recreate under the same
 * starvation that drops the event.
 *
 * ## Viability is drop-tolerant STATE, not an event (#1509)
 * [connectionViability] is deliberately NOT one of those lossy event flows: a connection's viability is a
 * *level* (path up / path lost), inherently latest-wins state. It is a [MutableStateFlow] keyed by
 * [NwConnectionId]; each `ready`/`waiting` transition atomically updates that connection's entry, and the
 * seam reconciles the latest map value. Intermediate transitions may coalesce under backpressure, but the
 * LATEST value per connection is never lost — so a dropped recovery can never strand the seam's grace
 * timer (a spurious tear) and a dropped loss can never leave a zombie peer.
 */
internal class RealNwApi(
    private val pskMaterial: NwPskMaterial,
    private val loopback: NwLoopbackConfig? = null,
) : NwApi {

    private val queue = dispatch_queue_create("us.tractat.kuilt.nw", null)

    private val _endpointFound = MutableSharedFlow<NwEndpoint>(extraBufferCapacity = EVENT_BUFFER)
    private val _connectionOpened = MutableSharedFlow<NwConnectionOpened>(extraBufferCapacity = EVENT_BUFFER)
    private val _bytesReceived = MutableSharedFlow<NwBytesReceived>(extraBufferCapacity = BYTES_BUFFER)
    private val _connectionClosed = MutableSharedFlow<NwConnectionClosed>(extraBufferCapacity = EVENT_BUFFER)

    // Per-connection LATEST viability, as drop-tolerant STATE — NOT a lossy tryEmit event stream (#1509).
    // Each `ready`/`waiting` transition atomically updates this connection's entry via [MutableStateFlow.update]
    // (a CAS, safe from the GCD queue); the seam reconciles the latest map value. Because the latest value
    // per connection is never lost (only intermediate transitions coalesce), a recovery (`true`) can never
    // be dropped and strand the seam's grace timer, and a loss (`false`) can never be dropped and leave a
    // zombie peer — the two asymmetric failure modes of the old `tryEmit` event flow.
    private val _connectionViability = MutableStateFlow<Map<NwConnectionId, Boolean>>(emptyMap())

    // Per-connection LATCHED-TERMINAL close markers, as drop-tolerant MONOTONE STATE — the drop-tolerant
    // backstop for the lossy `connectionClosed` tryEmit event (#1522). A `failed`/`cancelled` close dropped
    // from the event buffer would otherwise strand a zombie peer that no signal evicts; a close is reflected
    // HERE (id → reason, null = graceful) whether or not the event survives. Entries only appear (until the
    // FIFO cap prunes the oldest), so a closure can never be conflated away by presence-then-absence.
    private val _closedConnections = MutableStateFlow<Map<NwConnectionId, String?>>(emptyMap())

    // FIFO of connIds present in [_closedConnections], guarded by [lock], for the [CLOSED_RETENTION_CAP] prune.
    private val closedOrder = ArrayDeque<NwConnectionId>()

    override val endpointFound: Flow<NwEndpoint> = _endpointFound.asSharedFlow()
    override val connectionOpened: Flow<NwConnectionOpened> = _connectionOpened.asSharedFlow()
    override val bytesReceived: Flow<NwBytesReceived> = _bytesReceived.asSharedFlow()
    override val connectionClosed: Flow<NwConnectionClosed> = _connectionClosed.asSharedFlow()
    override val connectionViability: StateFlow<Map<NwConnectionId, Boolean>> = _connectionViability.asStateFlow()
    override val closedConnections: StateFlow<Map<NwConnectionId, String?>> = _closedConnections.asStateFlow()

    /**
     * Latch [id]'s close into the drop-tolerant monotone close STATE (#1522) with [reason] (`null` = graceful).
     * Under [lock] (no `nw_*`/suspend call — matches the seam-wide discipline; the caller [closeConnection]
     * already released the connection ref): append to the FIFO, prune the oldest past [CLOSED_RETENTION_CAP],
     * then publish the latest map via a CAS `update{}`. Bypasses the lossy `connectionClosed` tryEmit entirely.
     */
    private fun markClosed(id: NwConnectionId, reason: String?) {
        lock.withLock {
            closedOrder.addLast(id)
            if (closedOrder.size > CLOSED_RETENTION_CAP) {
                _closedConnections.update { it - closedOrder.removeFirst() }
            }
            _closedConnections.update { it + (id to reason) }
        }
    }

    /** Publish [id]'s LATEST path viability (#1509): `true` = path up (`ready`), `false` = path lost (`waiting`). */
    private fun setViability(id: NwConnectionId, viable: Boolean) {
        _connectionViability.update { it + (id to viable) }
    }

    /** Drop [id]'s viability entry once the connection is gone (bounds the map to live connections). */
    private fun clearViability(id: NwConnectionId) {
        _connectionViability.update { it - id }
    }

    /**
     * One live connection: its strong-ref'd handle, the dialled endpoint (null inbound), the
     * local-close flag, and [wasReady] — set the first time the connection reaches `ready`.
     * [wasReady] gates two things (#1478): (1) a `ready → waiting` transition emits a viability-lost
     * signal only if the connection HAD been ready (excludes normal initial-dial `preparing → waiting`
     * churn); (2) `connectionOpened` + the receive loop are started only on the FIRST `ready`, so a
     * `waiting → ready` recovery does not double-arm a second receive loop / duplicate the handshake.
     *
     * The receive-error split (#1479) adds four more fields:
     *  - [viable] — is the path CURRENTLY up (`ready`, not path-lost `waiting`)? Set `true` on every
     *    `ready`, `false` on a `ready → waiting` loss. A transient receive error re-arms only while
     *    [viable]; while not viable it stops and lets #1478's grace timer govern.
     *  - [receiveRetries] — transient-retry budget consumed since the last healthy receive (reset to 0
     *    on any successful chunk). Exhausting it WHILE viable escalates to a terminal close.
     *  - [receiveStoppedForRecovery] — a transient error stopped the loop while the path was down; the
     *    `waiting → ready` recovery re-arms the loop iff this is set (never double-arming a live loop).
     *  - [failedEscalation]/[failReason] — set by [escalateClose] before it cancels, so the resulting
     *    `cancelled` handler maps to `closeConnection(failed = true)` with [failReason] — preserving
     *    "`onState` is the sole ref-drop site" while surfacing a non-graceful `receive:<code>` reason.
     */
    private class ConnectionEntry(
        val connection: nw_connection_t,
        val endpoint: NwEndpoint?,
        var closing: Boolean = false,
        var wasReady: Boolean = false,
        var viable: Boolean = false,
        var receiveRetries: Int = 0,
        var receiveStoppedForRecovery: Boolean = false,
        var failedEscalation: Boolean = false,
        var failReason: String? = null,
    )

    /** The action a transient receive error resolves to (decided under [lock], acted on outside it). */
    private enum class TransientAction { Gone, StopUntilRecovery, Escalate, Retry }

    // Guards [connections], [endpointsById], [listener], and [browser]. NO nw_* call runs under it.
    private val lock = reentrantLock()
    private val connections = mutableMapOf<NwConnectionId, ConnectionEntry>()
    private val endpointsById = mutableMapOf<String, nw_endpoint_t>()
    private var listener: nw_listener_t? = null
    private var browser: nw_browser_t? = null

    private val connectionCounter = atomic(0L)

    override fun availability(): FabricAvailability = FabricAvailability.Available

    /**
     * Test-only window into the strong-ref connection registry: how many `nw_connection_t` handles
     * this binding is currently holding alive. Reads only [connections] (the sole leak surface — the
     * single ref-drop site is [closeConnection]); the listener/browser handles are separate. Used by
     * `NwConnectionDrainTest` to prove the registry drains to empty on seam close (no leaked
     * connection). Not part of the fabric contract — do not build behaviour on it.
     */
    internal fun liveConnectionCount(): Int = lock.withLock { connections.size }

    /**
     * Test-only: register an INERT connection entry ([endpoint], `wasReady=false`) and return its id.
     * The `nw_connection` is created but **never started, never queued, and never receives** — it is a
     * pure registry token, so nothing arms an async GCD callback that could outlive the test (an armed
     * `receiveLoop` on a started connection leaks a completion that fires later and aborts the shared
     * K/N test process). `NwConnectionViabilityTest` drives the observable `ready`/`waiting` emission
     * logic via [driveReadyTransitionForTest]/[driveWaitingForTest] — neither touches the connection —
     * to prove the #1478 viability mapping and the first-ready double-arm guard with no live socket.
     * Not part of the fabric contract — do not build behaviour on it.
     */
    internal fun registerInertConnectionForTest(endpoint: NwEndpoint?): NwConnectionId {
        val ep = nw_endpoint_create_host(LOOPBACK_HOST, "1")
        val connection = nw_connection_create(ep, secureParams()) ?: error("test connection create failed")
        val id = NwConnectionId("nw-test-${connectionCounter.incrementAndGet()}")
        lock.withLock { connections[id] = ConnectionEntry(connection, endpoint) }
        return id
    }

    /** Test-only: drive the observable half of a synthetic `ready` for [id]; returns whether it was the FIRST ready. */
    internal fun driveReadyTransitionForTest(id: NwConnectionId): Boolean = emitReadyTransition(id)

    /** Test-only: drive a synthetic `waiting` for [id] ([onWaiting]'s viability mapping — no `nw_*` call). */
    internal fun driveWaitingForTest(id: NwConnectionId) = onWaiting(id)

    /**
     * Test-only (#1522): drive [id]'s close on an INERT connection — the SAME [closeConnection] path the
     * `cancelled`/`failed` state handlers use, with NO `nw_*` call — so appleTest can unit-prove that the
     * closure is latched into [closedConnections] with the correctly-mapped reason. [failed] mirrors
     * [onState]'s `failed`/escalation argument. Pair with [markClosingForTest]/[markEscalationForTest] to
     * exercise the graceful-null and `receive:<code>` reason branches. Not part of the fabric contract.
     */
    internal fun driveCloseForTest(id: NwConnectionId, failed: Boolean) = closeConnection(id, failed)

    /** Test-only: set [id]'s entry to gracefully-closing (as [disconnect] would), no `nw_*` call — for the reason proof. */
    internal fun markClosingForTest(id: NwConnectionId) {
        lock.withLock { connections[id]?.closing = true }
    }

    /** Test-only: set [id]'s entry to a terminal receive escalation carrying [reason] (as [escalateClose] would), no `nw_*` call. */
    internal fun markEscalationForTest(id: NwConnectionId, reason: String) {
        lock.withLock {
            connections[id]?.let { entry ->
                entry.failedEscalation = true
                entry.failReason = reason
            }
        }
    }

    // ── host role ────────────────────────────────────────────────────────────

    override suspend fun startListening(serviceName: String, serviceType: String) {
        // Both modes bind an OS-assigned ephemeral port (nw_listener_create; no fixed port). Loopback
        // omits the Bonjour advertise and, once ready, publishes its REAL bound port to the rendezvous
        // so the joiner dials a port that is genuinely bound — no pre-allocated number, no TOCTOU.
        val newListener = nw_listener_create(secureParams())
        nw_listener_set_queue(newListener, queue)
        if (loopback == null) {
            nw_listener_set_advertise_descriptor(
                newListener,
                nw_advertise_descriptor_create_bonjour_service(serviceName, serviceType, null),
            )
        }
        nw_listener_set_state_changed_handler(newListener) { state, _ ->
            when (state) {
                nw_listener_state_ready ->
                    // Host loopback side only: publish the OS-assigned bound port (nw_listener_get_port
                    // returns host byte order — no hand swap) so the joiner's await unblocks. The
                    // joiner's own (unused) listener does NOT publish.
                    if (loopback != null && !loopback.dial) {
                        val port = nw_listener_get_port(newListener).toInt()
                        loopback.rendezvous.hostPort.complete(port)
                        log.debug { "nw.listen loopback host ready on 127.0.0.1:$port (TLS-PSK)" }
                    }
                // Surface a listener that never comes up (e.g. a bind failure) LOUDLY — otherwise the
                // only symptom is a downstream weave timeout, an opaque flake in the loopback
                // conformance run (the Apple nightly lane, not the per-PR ci-required check).
                nw_listener_state_failed ->
                    log.error { "nw.listen FAILED (bind unavailable?) loopback=${loopback != null}" }
                else -> Unit
            }
        }
        nw_listener_set_new_connection_handler(newListener) { connection ->
            // Inbound accept: no dialled endpoint.
            connection?.let { retainAndStart(it, endpoint = null) }
        }
        // Swap in the new handle and cancel any superseded one OUTSIDE the lock (no nw_* under it):
        // a re-start would otherwise leave the previous listener advertising over Bonjour forever.
        val superseded = lock.withLock { listener.also { listener = newListener } }
        superseded?.let { nw_listener_cancel(it) }
        nw_listener_start(newListener)
        if (loopback != null) {
            log.debug { "nw.listen loopback ephemeral 127.0.0.1 (no Bonjour, TLS-PSK)" }
        } else {
            log.debug { "nw.listen advertising name=$serviceName type=$serviceType (P2P, TLS-PSK)" }
        }
    }

    override suspend fun stopListening() {
        val doomed = lock.withLock { listener.also { listener = null } } ?: return
        nw_listener_cancel(doomed)
    }

    // ── join role ────────────────────────────────────────────────────────────

    override suspend fun startBrowsing(serviceType: String) {
        if (loopback != null) {
            // No nw_browser: synthesize the single peer endpoint directly. A host side (dial == false)
            // discovers nothing; a joiner awaits the host's real bound port from the rendezvous, then
            // emits the endpoint so NwLoom auto-dials it through the SAME connect() path as a Bonjour
            // peer. The await is a suspend call — it MUST run OUTSIDE `lock` (no nw_* under the lock).
            if (loopback.dial) {
                val port = loopback.rendezvous.hostPort.await()
                val ep = nw_endpoint_create_host(LOOPBACK_HOST, port.toString())
                if (ep != null) {
                    lock.withLock { endpointsById[LOOPBACK_PEER_ID] = ep }
                    _endpointFound.tryEmit(NwEndpoint(id = LOOPBACK_PEER_ID, serviceName = LOOPBACK_PEER_ID))
                }
                log.debug { "nw.browse loopback dial 127.0.0.1:$port" }
            } else {
                log.debug { "nw.browse loopback host (no dial)" }
            }
            return
        }
        val descriptor = nw_browse_descriptor_create_bonjour_service(serviceType, null)
        val newBrowser = nw_browser_create(descriptor, secureParams())
        nw_browser_set_queue(newBrowser, queue)
        nw_browser_set_browse_results_changed_handler(newBrowser) { _, newResult, _ ->
            if (newResult != null) onBrowseResult(newResult)
        }
        // Swap in the new handle and cancel any superseded one OUTSIDE the lock (no nw_* under it):
        // a re-start would otherwise leave the previous browser holding AWDL up forever.
        val superseded = lock.withLock { browser.also { browser = newBrowser } }
        superseded?.let { nw_browser_cancel(it) }
        nw_browser_start(newBrowser)
        log.debug { "nw.browse type=$serviceType over P2P (activates AWDL)" }
    }

    override suspend fun stopBrowsing() {
        val doomed = lock.withLock { browser.also { browser = null } } ?: return
        nw_browser_cancel(doomed)
    }

    override suspend fun connect(endpoint: NwEndpoint) {
        val ep = lock.withLock { endpointsById[endpoint.id] }
        if (ep == null) {
            log.debug { "nw.api.connect.unknown-endpoint id=${endpoint.id}" }
            return
        }
        val connection = nw_connection_create(ep, secureParams())
        if (connection == null) {
            log.debug { "nw.api.connect.create-failed endpoint id=${endpoint.id}" }
            return
        }
        log.debug { "nw.api.connect.dial endpoint=${endpoint.id} (outbound)" }
        retainAndStart(connection, endpoint = endpoint)
    }

    override suspend fun disconnect(connectionId: NwConnectionId) {
        // Mark our intent (graceful close), take the ref OUT under the lock, then cancel OUTSIDE it.
        // The ref is dropped by the resulting `cancelled` state handler (cancel-first-then-drop):
        // the entry lives until cancel lands so its `closing` flag maps the close to reason=null.
        val connection = lock.withLock {
            val entry = connections[connectionId] ?: return
            entry.closing = true
            entry.connection
        }
        log.debug { "nw.api.disconnect id=${connectionId.value} (local graceful cancel)" }
        nw_connection_cancel(connection)
    }

    // ── data ─────────────────────────────────────────────────────────────────

    override suspend fun send(connectionId: NwConnectionId, bytes: ByteArray) {
        val connection = lock.withLock { connections[connectionId]?.connection }
        if (connection == null) {
            log.debug { "nw.send to closed/unknown connection id=${connectionId.value}" }
            return
        }
        // Explicit content context — the NW_CONNECTION_*_CONTEXT constants mis-bridge under K/N.
        val context = nw_content_context_create("kuilt")
        nw_connection_send(connection, toDispatchData(bytes), context, true) { error ->
            if (error != null) log.debug { "nw.send-done err id=${connectionId.value} bytes=${bytes.size}" }
        }
    }

    // ── connection lifecycle ───────────────────────────────────────────────────

    /**
     * Register [connection] (strong ref) and start it. Called from BOTH the listener's
     * new-connection handler (inbound, [endpoint] == null) and [connect] (outbound). The strong
     * ref is taken here (under the lock) and dropped exactly once, in [onState] on
     * `failed`/`cancelled` — the sole ref-drop site, which guarantees cancel always precedes drop.
     */
    private fun retainAndStart(connection: nw_connection_t, endpoint: NwEndpoint?) {
        val id = NwConnectionId("nw-${connectionCounter.incrementAndGet()}")
        lock.withLock { connections[id] = ConnectionEntry(connection, endpoint) } // strong ref
        log.debug { "nw.api.retain-start id=${id.value} endpoint=${endpoint?.id ?: "<inbound>"} dir=${if (endpoint == null) "inbound" else "outbound"}" }
        nw_connection_set_queue(connection, queue)
        nw_connection_set_state_changed_handler(connection) { state, _ -> onState(id, connection, state) }
        nw_connection_start(connection)
    }

    private fun onState(id: NwConnectionId, connection: nw_connection_t, state: nw_connection_state_t?) {
        when (state) {
            nw_connection_state_ready -> onReady(id, connection)
            // WAITING is the path-lost limbo (unsatisfied route) that fires NO close — the #1478 wedge.
            nw_connection_state_waiting -> onWaiting(id)
            nw_connection_state_preparing -> log.debug { "nw.api.state id=${id.value} PREPARING" }
            nw_connection_state_failed -> { log.info { "nw.api.state id=${id.value} FAILED → closeConnection(failed=true)" }; closeConnection(id, failed = true) }
            nw_connection_state_cancelled -> {
                // A cancel we escalated for a terminal receive error (#1479) maps to a FAILED close so
                // the reason is non-graceful (receive:<code>); a plain local/remote cancel stays failed=false.
                val escalated = lock.withLock { connections[id]?.failedEscalation == true }
                log.debug { "nw.api.state id=${id.value} CANCELLED escalated=$escalated → closeConnection(failed=$escalated)" }
                closeConnection(id, failed = escalated)
            }
            else -> log.debug { "nw.api.state id=${id.value} other=$state" }
        }
    }

    /**
     * A `ready` transition. On the FIRST ready [emitReadyTransition] emits `connectionOpened` and
     * returns `true`, so we start the receive loop (identical to pre-#1478 behavior). On a LATER
     * ready — a `waiting → ready` recovery (#1478) — the connection is already open and receiving, so
     * re-emitting `connectionOpened` / re-arming `receiveLoop` would double-arm (duplicate NwHello + a
     * second concurrent receive loop); [emitReadyTransition] emits viability recovery and returns
     * `false`, so `receiveLoop` is NOT re-armed. (NW may redeliver `ready`; this makes every ready
     * after the first idempotent.)
     */
    private fun onReady(id: NwConnectionId, connection: nw_connection_t) {
        if (emitReadyTransition(id)) {
            receiveLoop(id, connection) // FIRST ready: arm the loop
        } else {
            // A `waiting → ready` recovery (#1478). If a transient receive error had stopped the loop
            // while the path was down (#1479), re-arm it now — but ONLY then, so a plain redelivered
            // `ready` never double-arms a loop that is still running. Reset the retry budget on restart.
            val restart = lock.withLock {
                val entry = connections[id] ?: return@withLock false
                if (entry.receiveStoppedForRecovery) {
                    entry.receiveStoppedForRecovery = false
                    entry.receiveRetries = 0
                    true
                } else {
                    false
                }
            }
            if (restart) {
                log.info { "nw.api.receive-restart id=${id.value} (waiting→ready recovery) → re-arm receiveLoop" }
                receiveLoop(id, connection)
            }
        }
    }

    /**
     * The observable half of a `ready` transition — pure registry bookkeeping + state/flow update, with NO
     * `nw_*` call (arming the receive loop is [onReady]'s job). Marks [ConnectionEntry.wasReady], sets this
     * connection's [connectionViability] latest value to `true` (path up), and — on the FIRST ready —
     * emits `connectionOpened` and returns `true` (caller must arm the receive loop); on a `waiting →
     * ready` recovery returns `false`; if the entry is already gone, returns `false`. Split out so the
     * ready/viability mapping and the first-ready double-arm guard are unit-testable without a live
     * `nw_connection`.
     */
    private fun emitReadyTransition(id: NwConnectionId): Boolean {
        val outcome = lock.withLock {
            val entry = connections[id] ?: return@withLock null
            val firstReady = !entry.wasReady
            entry.wasReady = true
            entry.viable = true // ready ⇒ path is currently up (gates the #1479 transient re-arm)
            entry.endpoint to firstReady
        }
        if (outcome == null) {
            log.debug { "nw.api.state id=${id.value} READY but entry already dropped (ignored)" }
            return false
        }
        val (endpoint, firstReady) = outcome
        // Ready ⇒ this connection's path is up. Publish the LATEST viability state either way (#1509) —
        // idempotent for the seam (a `true` with no armed timer is a no-op), and it makes the viability
        // map a faithful per-connection latest-state from the first `ready` onward.
        setViability(id, viable = true)
        return if (firstReady) {
            log.debug { "nw.api.state id=${id.value} READY endpoint=${endpoint?.id ?: "<inbound>"} → emit connectionOpened + start receiveLoop" }
            _connectionOpened.tryEmit(NwConnectionOpened(id, endpoint))
            true
        } else {
            log.info { "nw.api.state id=${id.value} READY again (waiting→ready recovery) → viability(viable=true)" }
            false
        }
    }

    /**
     * A `waiting` transition. On an ESTABLISHED connection (it HAD been ready) this is a path loss
     * (#1478) that fires NO close — signal `viable=false` so `NwSeam` arms its grace tear. An
     * initial-dial `preparing → waiting` (never been ready) is normal churn and is NOT reported.
     */
    private fun onWaiting(id: NwConnectionId) {
        val wasReady = lock.withLock {
            val entry = connections[id] ?: return@withLock false
            entry.viable = false // path is down ⇒ a transient receive error must NOT re-arm (#1479)
            entry.wasReady
        }
        if (wasReady) {
            log.info { "nw.api.state id=${id.value} WAITING after ready (path lost — no close will fire) → viability(viable=false)" }
            setViability(id, viable = false)
        } else {
            log.debug { "nw.api.state id=${id.value} WAITING (initial dial, not yet ready — no viability signal)" }
        }
    }

    /**
     * Drop the strong ref for [id] and emit `connectionClosed`. Reason mapping: a locally-initiated
     * cancel ([ConnectionEntry.closing] set by [disconnect]/[stopListening]) is graceful (reason
     * null); a `failed`, or a `cancelled` we did not initiate, carries a non-null reason.
     */
    private fun closeConnection(id: NwConnectionId, failed: Boolean) {
        val entry = lock.withLock { connections.remove(id) }
        if (entry == null) {
            log.debug { "nw.api.close id=${id.value} already-dropped (idempotent)" }
            return // already dropped — idempotent
        }
        val reason = when {
            entry.failedEscalation -> entry.failReason ?: "receive error (terminal)" // #1479 escalation
            failed -> "connection failed"
            entry.closing -> null // our own graceful cancel
            else -> "connection cancelled remotely"
        }
        // #1522: latch the closure into the drop-tolerant MONOTONE close STATE FIRST — this is the authoritative
        // teardown signal that the seam reconciles even when the `connectionClosed` tryEmit below is DROPPED
        // under buffer pressure (the fixed zombie: a dropped close used to strand a peer forever). Then drop the
        // stale per-conn viability latest-value (bounds the viability map; independent of close delivery).
        markClosed(id, reason)
        clearViability(id)
        log.info { "nw.api.close id=${id.value} failed=$failed closing=${entry.closing} → mark closed STATE + emit connectionClosed(reason=${reason ?: "null/graceful"})" }
        _connectionClosed.tryEmit(NwConnectionClosed(id, reason))
    }

    /**
     * The inbound receive loop, self-re-arming while healthy. A receive error is no longer a
     * permanent give-up (#1479): it is classified ([classifyReceiveError]) into
     *  - **Terminal** — the peer/link is gone; [escalateClose] cancels the connection so the resulting
     *    `cancelled` funnels through `closeConnection → connectionClosed → NwSeam` (the SAME evict+tear
     *    path as every other close — the receive loop never independently tears);
     *  - **Transient** — a bounded [onTransientReceiveError] retry (short backoff, [RECEIVE_RETRY_BUDGET]),
     *    unless the path has left `ready`, in which case the loop stops and #1478's grace timer governs
     *    (a `waiting → ready` recovery re-arms it via [onReady]).
     * A healthy chunk resets the retry budget, so intermittent transients never accumulate to escalation.
     */
    private fun receiveLoop(id: NwConnectionId, connection: nw_connection_t) {
        // #1516: arm the receive through the C shim (nwshim.def), NOT via a Kotlin lambda bridged to an
        // Obj-C block. Under load, Kotlin/Native's block trampoline (blockToKotlinImp →
        // Kotlin_Interop_refFromObjC) intermittently threw an uncaught NSException when NW invoked the
        // completion on the serial GCD queue — most reliably the final ECANCELED receive on the close
        // path — aborting the process. The shim installs a pure C block and calls back through a plain C
        // function pointer ([receiveCompletion]), so that trampoline is never on the receive hot path.
        //
        // Each receive fires its completion exactly once, so a fresh StableRef per arming is disposed by
        // the callback (no leak). The strong ref to [connection] in [connections] outlives this — the
        // StableRef only carries the context needed to route the one completion.
        val ctx = StableRef.create(ReceiveContext(this, id, connection))
        kuilt_nw_connection_receive(
            connection,
            RECEIVE_MIN_LENGTH,
            RECEIVE_MAX_LENGTH,
            ctx.asCPointer(),
            receiveCompletion,
        )
    }

    /**
     * Handle one receive completion, unpacked to primitives by the C shim (#1516). [bytes] points into a
     * mapped dispatch_data region that ARC keeps alive until strictly after this returns, so the
     * [readBytes] copy is safe. Mirrors the old in-lambda logic: emit any chunk, then re-arm on success
     * (resetting the retry budget) or route the error through [handleReceiveError].
     */
    private fun onReceiveComplete(
        id: NwConnectionId,
        connection: nw_connection_t,
        bytes: COpaquePointer?,
        len: Int,
        hasError: Boolean,
        errDomain: Int,
        errCode: Int,
    ) {
        if (bytes != null && len > 0) _bytesReceived.tryEmit(NwBytesReceived(id, bytes.readBytes(len)))
        if (!hasError) {
            lock.withLock { connections[id]?.receiveRetries = 0 } // healthy ⇒ any transient blip cleared
            receiveLoop(id, connection) // re-arm only while healthy
        } else {
            handleReceiveError(id, connection, errDomain, errCode)
        }
    }

    /**
     * Classify a receive error and route it to escalate (Terminal) or bounded retry (Transient). #1479.
     * [domain] is the raw `nw_error_domain_t` value the shim read (invalid=0/posix=1/dns=2/tls=3), which
     * is byte-for-byte the commonMain `NW_ERROR_DOMAIN_*` numbering — passed straight to [classifyReceiveError].
     */
    private fun handleReceiveError(id: NwConnectionId, connection: nw_connection_t, domain: Int, code: Int) {
        when (classifyReceiveError(domain, code)) {
            ReceiveErrorClass.ExpectedCancel ->
                // Our own nw_connection_cancel failed the pending receive (ECANCELED). The `cancelled`
                // state handler already drives the (graceful) close — ignore this, do NOT escalate
                // (escalating would clobber `closing` and turn a reason=null close into a failed one).
                log.debug { "nw.api.receive-cancel id=${id.value} code=$code (self-cancel; cancelled handler will close) → ignored" }
            ReceiveErrorClass.Terminal -> {
                log.info { "nw.api.receive-terminal id=${id.value} domain=$domain code=$code → escalateClose (→ connectionClosed → NwSeam tear)" }
                escalateClose(id, "receive:$code")
            }
            ReceiveErrorClass.Transient -> onTransientReceiveError(id, connection, domain, code)
        }
    }

    /**
     * A transient receive error (#1479). Decide under [lock] (then act outside it):
     *  - the entry is gone → nothing to do;
     *  - the path is down (`!viable`, i.e. `ready → waiting`) → STOP and mark [receiveStoppedForRecovery];
     *    #1478's grace timer governs and [onReady] re-arms on recovery;
     *  - the retry budget is exhausted WHILE still `ready` → treat as Terminal ([escalateClose]);
     *  - otherwise consume one retry and re-arm after a short backoff.
     */
    private fun onTransientReceiveError(id: NwConnectionId, connection: nw_connection_t, domain: Int, code: Int) {
        var retry = 0
        val action = lock.withLock {
            val entry = connections[id] ?: return@withLock TransientAction.Gone
            when {
                !entry.viable -> { entry.receiveStoppedForRecovery = true; TransientAction.StopUntilRecovery }
                entry.receiveRetries >= RECEIVE_RETRY_BUDGET -> TransientAction.Escalate
                else -> { entry.receiveRetries += 1; retry = entry.receiveRetries; TransientAction.Retry }
            }
        }
        when (action) {
            TransientAction.Gone ->
                log.debug { "nw.api.receive-transient id=${id.value} domain=$domain code=$code but entry already dropped (ignored)" }
            TransientAction.StopUntilRecovery ->
                log.info { "nw.api.receive-transient id=${id.value} domain=$domain code=$code — path not ready → stop; #1478 grace timer governs, re-arm on recovery" }
            // A dialled endpoint is not redialled for the seam's life (see #1513), so an escalate→evict
            // here is permanent — hence the wide, ~${RECEIVE_RETRY_MAX_MS}ms transient window before we give up.
            TransientAction.Escalate -> {
                log.info { "nw.api.receive-transient id=${id.value} domain=$domain code=$code — retry budget ($RECEIVE_RETRY_BUDGET, ~${RECEIVE_RETRY_MAX_MS}ms) exhausted while ready → escalateClose" }
                escalateClose(id, "receive:$code")
            }
            TransientAction.Retry -> {
                val backoffMs = backoffMsFor(retry)
                log.debug { "nw.api.receive-transient id=${id.value} domain=$domain code=$code → retry $retry/$RECEIVE_RETRY_BUDGET re-arm after ${backoffMs}ms" }
                rearmAfterBackoff(id, connection, backoffMs)
            }
        }
    }

    /**
     * Exponential backoff for transient-receive retry [retry] (1-based): `BASE_MS * 2^(retry-1)`, capped
     * at [RECEIVE_RETRY_CAP_MS]. Widening from a flat delay to exponential (#1479 review): with no
     * per-seam redial (#1513) an escalate is permanent, so the budget must tolerate a real transient
     * storm (~${RECEIVE_RETRY_MAX_MS}ms total while `ready`) rather than the old ~250ms.
     */
    private fun backoffMsFor(retry: Int): Int {
        val shift = (retry - 1).coerceIn(0, RECEIVE_RETRY_MAX_SHIFT)
        return (RECEIVE_RETRY_BASE_MS shl shift).coerceAtMost(RECEIVE_RETRY_CAP_MS)
    }

    /** Re-arm the receive loop after [backoffMs] on the shared [queue] (transient exponential backoff). */
    private fun rearmAfterBackoff(id: NwConnectionId, connection: nw_connection_t, backoffMs: Int) {
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, backoffMs.toLong() * NANOS_PER_MS), queue) {
            receiveLoop(id, connection)
        }
    }

    /**
     * Escalate a terminal receive error (or an exhausted transient budget) into a connection close
     * (#1479). Sets [ConnectionEntry.failedEscalation] + a non-graceful [ConnectionEntry.failReason],
     * then cancels — so the resulting `cancelled` handler ([onState]) maps to `closeConnection(failed =
     * true)` and emits `connectionClosed(reason = "receive:<code>")`. This preserves the "`onState` is the
     * SOLE ref-drop site" invariant (the receive loop never removes the entry itself) and funnels through
     * the SAME `connectionClosed → NwSeam` evict+tear path as every other close. Idempotent — a second
     * escalation on an already-escalating entry is a no-op.
     *
     * A connection ALREADY gracefully cancelling ([ConnectionEntry.closing] set by
     * [disconnect]/[stopListening]) is NEVER escalated: the `ECANCELED` it raises is [ExpectedCancel] and
     * already ignored upstream, but this guard is belt-and-suspenders — escalating would clobber `closing`
     * and turn the contractual `reason = null` graceful close into a spurious `failed` one (#1479 review).
     */
    private fun escalateClose(id: NwConnectionId, reason: String) {
        val connection = lock.withLock {
            val entry = connections[id] ?: return
            if (entry.closing) return // already gracefully cancelling — never escalate/clobber the reason
            if (entry.failedEscalation) return // already escalating — idempotent
            entry.failedEscalation = true
            entry.failReason = reason
            entry.connection
        }
        log.info { "nw.api.escalate-close id=${id.value} reason=$reason → cancel (cancelled handler maps → closeConnection failed=true)" }
        nw_connection_cancel(connection)
    }

    private fun onBrowseResult(result: nw_browse_result_t) {
        val ep = nw_browse_result_copy_endpoint(result) ?: return
        val name = nw_endpoint_get_bonjour_service_name(ep)?.toKString()
            ?: "nw-ep-${connectionCounter.incrementAndGet()}"
        lock.withLock { endpointsById[name] = ep } // keep the latest endpoint (may swap to AWDL)
        _endpointFound.tryEmit(NwEndpoint(id = name, serviceName = name))
    }

    // ── params ─────────────────────────────────────────────────────────────────

    /**
     * Secure-TCP params with the TLS-PSK installed. `includePeerToPeer` is on for the P2P mode (the
     * AWDL discovery surface) and off for loopback (a direct `127.0.0.1` link is exempt from Local
     * Network Privacy and must not raise AWDL). The `configure_tls` PSK block is IDENTICAL in both
     * modes — CI-covering the `sec_protocol_options_add_pre_shared_key` path is the point of the
     * loopback conformance run.
     */
    private fun secureParams(): nw_parameters_t? {
        val params = nw_parameters_create_secure_tcp(
            configure_tls = { options: nw_protocol_options_t? ->
                val sec = nw_tls_copy_sec_protocol_options(options)
                sec_protocol_options_add_pre_shared_key(
                    sec,
                    toDispatchData(pskMaterial.psk),
                    toDispatchData(pskMaterial.identity),
                )
            },
            configure_tcp = NW_PARAMETERS_DEFAULT_CONFIGURATION,
        )
        nw_parameters_set_include_peer_to_peer(params, loopback == null)
        return params
    }

    // ── dispatch_data <-> ByteArray (lifted verbatim from the spike) ────────────

    private fun toDispatchData(bytes: ByteArray): dispatch_data_t =
        bytes.usePinned { pinned ->
            // null destructor => NW copies the bytes, so the pinned buffer need not outlive the call.
            dispatch_data_create(pinned.addressOf(0), bytes.size.convert(), null, null)
        }

    /**
     * The context routed to the one-shot receive completion (#1516). Carried as a [StableRef] through
     * the C shim's opaque `ctx` pointer so the pure-C completion block can call back into Kotlin without
     * any Obj-C→Kotlin bridge. Holds a strong ref to [connection] for the completion's lifetime (the
     * registry's ref is independent); the callback disposes the [StableRef] the moment it fires.
     */
    private class ReceiveContext(
        val api: RealNwApi,
        val id: NwConnectionId,
        val connection: nw_connection_t,
    )

    private companion object {
        /**
         * The C function pointer the shim invokes when a receive completes (#1516). Non-capturing (a
         * [staticCFunction] requirement): everything it needs is recovered from the [StableRef] in `ctx`,
         * which it disposes before dispatching to [onReceiveComplete]. `bytes` is valid only for the
         * duration of this call (ARC frees the mapped region once the C block returns).
         */
        private val receiveCompletion =
            staticCFunction<COpaquePointer?, COpaquePointer?, Int, Boolean, Boolean, Int, Int, Unit> {
                ctx, bytes, len, _, hasError, errDomain, errCode ->
                val ref = ctx!!.asStableRef<ReceiveContext>()
                val rc = ref.get()
                ref.dispose()
                rc.api.onReceiveComplete(rc.id, rc.connection, bytes, len, hasError, errDomain, errCode)
            }

        /** Stable discovery id for the single synthesized loopback peer (loopback mode has no Bonjour name). */
        private const val LOOPBACK_PEER_ID = "loopback-peer"
        /** The loopback interface the joiner dials the host's ephemeral port on. */
        private const val LOOPBACK_HOST = "127.0.0.1"
        private const val EVENT_BUFFER = 16
        private const val BYTES_BUFFER = 64
        private const val RECEIVE_MIN_LENGTH: UInt = 1u
        private const val RECEIVE_MAX_LENGTH: UInt = 65_536u // 64 KiB

        private const val NANOS_PER_MS: Long = 1_000_000L

        // #1479 transient-receive-error retry: EXPONENTIAL backoff (BASE·2^(n-1), capped) with a budget
        // sized so a real transient storm gets ~seconds of tolerance before we escalate to a permanent
        // close — deliberately wide because a dialled endpoint is not redialled for the seam's life
        // (#1513), so a false escalate→evict is irreversible. Schedule (budget 8): 50,100,200,400,500,
        // 500,500,500 ms ⇒ ~2.75s total while `ready`, vs the old flat 5×50ms ≈ 250ms.
        private const val RECEIVE_RETRY_BUDGET: Int = 8
        private const val RECEIVE_RETRY_BASE_MS: Int = 50
        private const val RECEIVE_RETRY_CAP_MS: Int = 500
        private const val RECEIVE_RETRY_MAX_SHIFT: Int = 16 // guards `shl` against overflow
        /** Approx total transient tolerance while `ready` before escalating (for logs/docs only). */
        private const val RECEIVE_RETRY_MAX_MS: Int = 2750

        /**
         * FIFO retention bound on [closedConnections] (#1522): the newest N close markers are retained,
         * the oldest pruned. An in-flight close reconciles within milliseconds of the mark, so retaining
         * the last N is far more than enough while keeping the map from growing on a long-lived churny fabric.
         */
        private const val CLOSED_RETENTION_CAP: Int = 256
    }
}
