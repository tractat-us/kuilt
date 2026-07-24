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
import platform.Network.nw_error_get_error_code
import platform.Network.nw_error_get_error_domain
import platform.Network.nw_error_t
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
import platform.Network.nw_interface_get_name
import platform.Network.nw_interface_get_type
import platform.Network.nw_interface_type_cellular
import platform.Network.nw_interface_type_loopback
import platform.Network.nw_interface_type_other
import platform.Network.nw_interface_type_wifi
import platform.Network.nw_interface_type_wired
import platform.Network.nw_path_enumerate_interfaces
import platform.Network.nw_parameters_create_secure_tcp
import platform.Network.nw_parameters_set_include_peer_to_peer
import platform.Network.nw_parameters_t
import platform.Network.nw_path_get_status
import platform.Network.nw_path_get_unsatisfied_reason
import platform.Network.nw_path_is_constrained
import platform.Network.nw_path_is_expensive
import platform.Network.nw_path_monitor_cancel
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_monitor_t
import platform.Network.nw_path_status_satisfiable
import platform.Network.nw_path_status_satisfied
import platform.Network.nw_path_status_unsatisfied
import platform.Network.nw_path_t
import platform.Network.nw_path_unsatisfied_reason_cellular_denied
import platform.Network.nw_path_unsatisfied_reason_local_network_denied
import platform.Network.nw_path_unsatisfied_reason_not_available
import platform.Network.nw_path_unsatisfied_reason_vpn_inactive
import platform.Network.nw_path_unsatisfied_reason_wifi_denied
import platform.Network.nw_path_uses_interface_type
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
 * A decoded `nw_error` observed on a connection's FAILED (or path-lost WAITING) transition (#1560).
 *
 * [domain] is the raw `nw_error_domain_t` value (invalid=0/posix=1/dns=2/tls=3, matching the commonMain
 * `NW_ERROR_DOMAIN_*` numbering); [code] is the domain-specific error code — for a TLS-domain failure the
 * TLS alert / OSStatus that names WHY a handshake failed. Observability only, surfaced via
 * [RealNwApi.lastConnectionFailure]; it is not part of the [NwApi] fabric contract.
 */
internal data class NwConnectionFailure(
    val id: NwConnectionId,
    val domain: Int,
    val code: Int,
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
 * backstopped by the drop-tolerant [connectionStates] `Closed` STATE (see below); the other three have no such backstop.
 *
 * ## Lifecycle is drop-tolerant STATE, not just an event (#1509/#1522/#1539)
 * [connectionStates] is the ONE drop-tolerant per-connection [NwConnState] signal, unifying the former
 * separate viability and closed-markers maps. It is a [MutableStateFlow] keyed by [NwConnectionId]:
 *  - each `ready`/`waiting` transition atomically updates the entry to [NwConnState.Viable]/[NwConnState.PathLost]
 *    via [setViability] (a CAS `update{}`, safe from the GCD queue); a connection's path is a *level*, so
 *    intermediate transitions may coalesce under backpressure but the LATEST value is never lost — a dropped
 *    recovery can never strand the seam's grace timer (a spurious tear) and a dropped loss can never leave a
 *    zombie peer;
 *  - a close latches [NwConnState.Closed] via [markClosed] (a MONOTONE, terminal marker: id → reason) BEFORE
 *    the lossy `connectionClosed` tryEmit, so the seam reconciles the closure whether or not the event
 *    survives. Closed is **dominant** — [setViability] refuses to overwrite it — so a late `ready`/`waiting`
 *    for a closed id cannot revert it and a closure can never be conflated away (the failure mode a boolean
 *    live-set would recreate under the same starvation that drops the event). Closed entries only appear until
 *    the FIFO cap ([CLOSED_RETENTION_CAP]) prunes the oldest.
 */
internal class RealNwApi(
    private val pskMaterial: NwPskMaterial,
    private val loopback: NwLoopbackConfig? = null,
) : NwApi {

    private val queue = dispatch_queue_create("us.tractat.kuilt.nw", null)

    private val _endpointFound = MutableSharedFlow<NwEndpoint>(extraBufferCapacity = EVENT_BUFFER)
    private val _endpointLost = MutableSharedFlow<NwEndpoint>(extraBufferCapacity = EVENT_BUFFER)
    private val _connectionOpened = MutableSharedFlow<NwConnectionOpened>(extraBufferCapacity = EVENT_BUFFER)
    private val _bytesReceived = MutableSharedFlow<NwBytesReceived>(extraBufferCapacity = BYTES_BUFFER)
    private val _connectionClosed = MutableSharedFlow<NwConnectionClosed>(extraBufferCapacity = EVENT_BUFFER)

    // Per-connection LATEST lifecycle state, as the ONE drop-tolerant STATE signal (#1539) unifying the former
    // separate viability (#1509) and closed-markers (#1522) maps. A `ready`/`waiting` transition atomically
    // updates the entry to [NwConnState.Viable]/[NwConnState.PathLost] via [MutableStateFlow.update] (a CAS,
    // safe from the GCD queue); a close latches [NwConnState.Closed] (monotone + dominant). The seam reconciles
    // the latest map value. Because the latest value per connection is never lost (only intermediate viability
    // transitions coalesce) and Closed is dominant, a recovery can never strand the seam's grace timer, a loss
    // can never leave a zombie peer, and a close can never be conflated away — the failure modes of a lossy event.
    private val _connectionStates = MutableStateFlow<Map<NwConnectionId, NwConnState>>(emptyMap())

    // FIFO of connIds latched Closed in [_connectionStates], guarded by [lock], for the [CLOSED_RETENTION_CAP] prune.
    private val closedOrder = ArrayDeque<NwConnectionId>()

    // Live device network path (#1541), driven by an `nw_path_monitor` started lazily on first [pathState] read.
    // Latest-value STATE (not a lossy event) so a late subscriber always sees the current path; `null` until the
    // monitor delivers its first update. The seam folds this into its live capability.
    private val _pathState = MutableStateFlow<NwPathState?>(null)

    // The single lazily-started `nw_path_monitor`, guarded by [lock] (its `nw_*` calls run OUTSIDE the lock, like
    // the listener/browser handles). `null` until [ensurePathMonitor] starts it; nulled by [cancelPathMonitor].
    private var pathMonitor: nw_path_monitor_t? = null

    // Start-once latch for the path monitor: the CAS winner creates+starts it exactly once, so no `nw_*` call is
    // needed under [lock] to serialize starts (the repo's no-nw_*-under-lock discipline).
    private val pathMonitorStarted = atomic(false)

    // #1618 Track A device-path self-loss edge state (guarded by [lock]): whether the LAST device path update was
    // `unsatisfied`, and the connIds we demoted to PathLost on that down-edge — so the matching recovery restores
    // exactly those (and only those) to Viable, never reverting a connection lost by its own `waiting` transition.
    private var devicePathUnsatisfied = false
    private val devicePathLostConns = mutableSetOf<NwConnectionId>()

    override val endpointFound: Flow<NwEndpoint> = _endpointFound.asSharedFlow()
    override val endpointLost: Flow<NwEndpoint> = _endpointLost.asSharedFlow()
    override val connectionOpened: Flow<NwConnectionOpened> = _connectionOpened.asSharedFlow()
    override val bytesReceived: Flow<NwBytesReceived> = _bytesReceived.asSharedFlow()
    override val connectionClosed: Flow<NwConnectionClosed> = _connectionClosed.asSharedFlow()
    override val connectionStates: StateFlow<Map<NwConnectionId, NwConnState>> = _connectionStates.asStateFlow()

    private val pathStateFlow: StateFlow<NwPathState?> = _pathState.asStateFlow()

    /**
     * The device's live network path (#1541). Starts the `nw_path_monitor` lazily on first read (so a binding
     * that never reports capability — e.g. an inert-connection unit test — spins up no OS monitor), then reports
     * every `nw_path_monitor` update as latest-value STATE. Cancel via [cancelPathMonitor] at teardown so the
     * monitor's queue callback does not outlive the binding (tests MUST cancel it; production holds it for the
     * fabric's lifetime, there being no `NwApi` close hook).
     */
    override val pathState: StateFlow<NwPathState?>
        get() {
            ensurePathMonitor()
            return pathStateFlow
        }

    /**
     * Latch [id]'s close into [connectionStates] as [NwConnState.Closed] with [reason] (`null` = graceful) —
     * the drop-tolerant, terminal, monotone+dominant close STATE (#1522/#1539). Under [lock] (no `nw_*`/suspend
     * call — matches the seam-wide discipline; the caller [closeConnection] already released the connection
     * ref): append to the FIFO, prune the oldest past [CLOSED_RETENTION_CAP], then publish the latest map via a
     * CAS `update{}`. Bypasses the lossy `connectionClosed` tryEmit entirely. Because [setViability] refuses to
     * overwrite a `Closed` entry, this latch is dominant: a late `ready`/`waiting` for a closed id is ignored.
     *
     * **Cap-prune caveat (bounded, seam-harmless).** Once an id's `Closed` entry has been FIFO-pruned past
     * [CLOSED_RETENTION_CAP], a *very*-late [setViability] for that same id would find no `Closed` guard and
     * could write a `Viable` entry that nothing later removes. This is the same class as the existing
     * pruned-before-observed cap risk, and it is harmless to the seam: by the time an id is that stale the
     * connection is long torn and tombstoned (not in `conns`), so a spurious `Viable` never arms a grace timer
     * nor resurrects a peer. The cap is sized far above the in-flight reorder window so this cannot happen in practice.
     */
    private fun markClosed(id: NwConnectionId, reason: String?) {
        lock.withLock {
            closedOrder.addLast(id)
            if (closedOrder.size > CLOSED_RETENTION_CAP) {
                // Hoist the FIFO mutation OUT of the CAS lambda: `update{}` may re-run its lambda on
                // contention, and a `removeFirst()` inside it would pop twice while removing one — the
                // correctness must be local, not emergent from the surrounding lock (repo policy).
                val evicted = closedOrder.removeFirst()
                _connectionStates.update { it - evicted }
            }
            _connectionStates.update { it + (id to NwConnState.Closed(reason)) }
        }
    }

    /**
     * Publish [id]'s LATEST path state (#1509/#1539): `ready` ⇒ [NwConnState.Viable], `waiting` ⇒
     * [NwConnState.PathLost]. **Closed-dominance:** if the entry is already [NwConnState.Closed] this is a
     * no-op — a late viability change for a terminally-closed connection must never revert it to a live state
     * (the terminal-closed-wins-over-late-viability latch, #1522). The `is Closed` check runs INSIDE the CAS
     * `update{}` lambda, so it re-evaluates on retry: a [markClosed] that lands mid-CAS still wins.
     */
    private fun setViability(id: NwConnectionId, viable: Boolean) {
        _connectionStates.update { cur ->
            if (cur[id] is NwConnState.Closed) cur // terminal Closed dominates — never revert to a live state
            else cur + (id to if (viable) NwConnState.Viable else NwConnState.PathLost)
        }
    }

    // The LATEST decoded nw_error observed on a FAILED (or path-lost WAITING) transition (#1560). Kept as
    // drop-tolerant STATE so a test — or a diagnostic reader — can inspect the last handshake/link failure's
    // domain+code without racing the info-level log. Null until the first error-bearing transition.
    private val _lastConnectionFailure = MutableStateFlow<NwConnectionFailure?>(null)

    /**
     * Test/diagnostic window onto the last decoded connection failure (#1560): the [NwConnectionId], the raw
     * `nw_error_domain_t` (invalid=0/posix=1/dns=2/tls=3, mirroring [NW_ERROR_DOMAIN_TLS] et al.) and the
     * domain-specific `code` — for a TLS-domain failure the TLS alert / OSStatus. Not part of the fabric
     * contract — for observability only; do not build behaviour on it.
     */
    internal val lastConnectionFailure: StateFlow<NwConnectionFailure?> = _lastConnectionFailure.asStateFlow()

    /**
     * Decode [error] (if present) into its (domain, code) primitives and record it via [captureFailure].
     * Mirrors the receive-path shim's numbering (nwshim.def: invalid=0/posix=1/dns=2/tls=3) so a FAILED /
     * path-lost WAITING transition is logged with the SAME vocabulary as [handleReceiveError] (#1560). No-op
     * when [error] is null (a graceful/quiet transition), so the close semantics are behaviorally unchanged.
     */
    private fun recordFailure(id: NwConnectionId, error: nw_error_t?, phase: String) {
        if (error == null) return
        val domain = nw_error_get_error_domain(error).toInt()
        val code = nw_error_get_error_code(error)
        captureFailure(id, domain, code, phase)
    }

    /**
     * Log (info) and record the decoded connection-failure [domain]/[code] for [id] observed in [phase]
     * (`FAILED`/`WAITING`). Split from [recordFailure] so the capture+log plumbing is unit-testable with
     * injected primitives (no synthesizable real `nw_error_t`) via [driveFailureForTest] (#1560).
     */
    private fun captureFailure(id: NwConnectionId, domain: Int, code: Int, phase: String) {
        log.info { "nw.api.state.error id=${id.value} $phase nw_error domain=${nwErrorDomainName(domain)}($domain) code=$code" }
        _lastConnectionFailure.value = NwConnectionFailure(id, domain, code)
    }

    /** A human tag for the raw `nw_error_domain_t` value, mirroring the [NW_ERROR_DOMAIN_TLS] et al. numbering. */
    private fun nwErrorDomainName(domain: Int): String = when (domain) {
        NW_ERROR_DOMAIN_INVALID -> "invalid"
        NW_ERROR_DOMAIN_POSIX -> "posix"
        NW_ERROR_DOMAIN_DNS -> "dns"
        NW_ERROR_DOMAIN_TLS -> "tls"
        else -> "unknown"
    }

    /**
     * Start the single `nw_path_monitor` exactly once (#1541). The [pathMonitorStarted] CAS elects one starter,
     * so no `nw_*` call runs under [lock] to serialize starts. The update handler fires on the shared [queue] and
     * publishes the latest [NwPathState] (a thread-safe `MutableStateFlow.value` write, like the viability path).
     * The handle is stashed under [lock] (a plain store, no `nw_*` under the lock) for [cancelPathMonitor]; start
     * runs OUTSIDE the lock, mirroring the listener/browser swap discipline.
     */
    private fun ensurePathMonitor() {
        if (!pathMonitorStarted.compareAndSet(expect = false, update = true)) return
        val monitor = nw_path_monitor_create()
        if (monitor == null) {
            // Could not create — let a later reader retry rather than latch a dead "started" state.
            pathMonitorStarted.value = false
            log.debug { "nw.path.monitor.create-failed" }
            return
        }
        nw_path_monitor_set_queue(monitor, queue)
        nw_path_monitor_set_update_handler(monitor) { path ->
            val state = readPath(path)
            // #1618 Commit 1 (instrumentation): log EVERY device-path update — status, interface types, and the
            // unsatisfied reason — at info so the morning airplane-toggle can measure the path-unsatisfied latency
            // (identities+state, never sizes). Previously this handler logged nothing, silently discarding the one
            // fast local signal a radios-off drop produces.
            log.info {
                "nw.path.update status=${state.status} interfaces=${state.interfaces} " +
                    "reason=${state.unsatisfiedReason} expensive=${state.isExpensive} constrained=${state.isConstrained}"
            }
            _pathState.value = state
            onDevicePathState(state)
        }
        lock.withLock { pathMonitor = monitor }
        nw_path_monitor_start(monitor)
        log.debug { "nw.path.monitor.started" }
    }

    /**
     * Cancel the `nw_path_monitor` and drop the handle (#1541). Idempotent. The `nw_path_monitor_cancel` call runs
     * OUTSIDE [lock] (no `nw_*` under the lock). Tests MUST call this at teardown so the monitor's queue callback
     * does not outlive the shared K/N test process; production has no `NwApi` close hook, so the monitor lives for
     * the binding's lifetime (a single, cheap device-path observer).
     */
    internal fun cancelPathMonitor() {
        val doomed = lock.withLock { pathMonitor.also { pathMonitor = null } } ?: return
        nw_path_monitor_cancel(doomed)
        log.debug { "nw.path.monitor.cancelled" }
    }

    /**
     * #1618 Track A — fast self-detection of a radios-off drop. When the device's `NWPathMonitor` reports the
     * path is **unsatisfied** (airplane mode / all radios off), drive EVERY live connection to
     * [NwConnState.PathLost] via [setViability]`(false)`, so the seam self-observes the loss near its #1478
     * grace window instead of only inferring it from the peer's 15 s silence 15–75 s later.
     *
     * ## Reuses the #1478 grace — deliberately NOT an immediate tear
     * `NWPathMonitor` fires `unsatisfied` on every transient path reshuffle (a brief total-loss blip). Marking a
     * connection [NwConnState.PathLost] arms the seam's existing `wovenPathGrace` timer, NOT an eviction: a device
     * path that returns to satisfied within the grace restores viability (below) and the tear never fires —
     * false-positive-safe by construction. Detection latency ≈ the grace (tunable), not ~1 s.
     *
     * ## Down-edge / up-edge, restoring only what we demoted
     * On the satisfied→unsatisfied EDGE we snapshot the live connIds and demote them, remembering the set in
     * [devicePathLostConns]. On the unsatisfied→satisfied EDGE we restore exactly those to [NwConnState.Viable]
     * (the [setViability] Closed-dominance guard leaves any that closed meanwhile terminal), so a device-path blip
     * is a net no-op and we never revert a connection that lost its OWN path via `waiting`. Reads `connections`
     * under [lock]; [setViability] (a [MutableStateFlow] CAS, no `nw_*` call) runs OUTSIDE it.
     *
     * ## Coverage limit
     * This catches **airplane mode / all radios off**, where the whole device path goes unsatisfied. It does NOT
     * catch walking a single peer out of AWDL range while another interface stays up (e.g. cellular): the device
     * path stays *satisfied*, so no unsatisfied edge fires. That case is still governed by the connection's own
     * `ready→waiting` (#1478) or the peer-silence timeout above the seam.
     */
    private fun onDevicePathState(state: NwPathState) {
        val unsatisfied = state.status == NwPathStatus.Unsatisfied
        var demote: List<NwConnectionId> = emptyList()
        var restore: List<NwConnectionId> = emptyList()
        lock.withLock {
            when {
                unsatisfied && !devicePathUnsatisfied -> {
                    devicePathUnsatisfied = true
                    demote = connections.keys.toList()
                    devicePathLostConns.clear()
                    devicePathLostConns.addAll(demote)
                }
                !unsatisfied && devicePathUnsatisfied -> {
                    devicePathUnsatisfied = false
                    restore = devicePathLostConns.toList()
                    devicePathLostConns.clear()
                }
            }
        }
        for (id in demote) setViability(id, viable = false)
        for (id in restore) setViability(id, viable = true)
        if (demote.isNotEmpty()) {
            log.info {
                "nw.path.self-loss device-path unsatisfied → setViability(false) for ${demote.size} live conn(s) " +
                    "${demote.map { it.value }} (#1478 grace governs the tear)"
            }
        }
        if (restore.isNotEmpty()) {
            log.info {
                "nw.path.self-recover device-path satisfied → setViability(true) restoring ${restore.size} conn(s) " +
                    "${restore.map { it.value }}"
            }
        }
    }

    /** Read an `nw_path_t` snapshot into a platform-neutral [NwPathState] (#1541). Pure — no `nw_*` mutation. */
    private fun readPath(path: nw_path_t?): NwPathState {
        if (path == null) {
            return NwPathState(NwPathStatus.Invalid, emptySet(), isExpensive = false, isConstrained = false, unsatisfiedReason = null)
        }
        val status = when (nw_path_get_status(path)) {
            nw_path_status_satisfied -> NwPathStatus.Satisfied
            nw_path_status_satisfiable -> NwPathStatus.Satisfiable
            nw_path_status_unsatisfied -> NwPathStatus.Unsatisfied
            else -> NwPathStatus.Invalid
        }
        val interfaces = buildSet {
            if (nw_path_uses_interface_type(path, nw_interface_type_cellular)) add(NwInterfaceType.Cellular)
            if (nw_path_uses_interface_type(path, nw_interface_type_wired)) add(NwInterfaceType.Wired)
            if (nw_path_uses_interface_type(path, nw_interface_type_loopback)) add(NwInterfaceType.Loopback)
            if (nw_path_uses_interface_type(path, nw_interface_type_other)) add(NwInterfaceType.Other)
            // Wi-Fi (#1554): nw_path_uses_interface_type collapses infra Wi-Fi and AWDL into one `wifi` type, so
            // enumerate the path's interfaces and split each Wi-Fi one by BSD name (classifyWifiInterface). This
            // recovers WifiLan vs WifiDirect, which then drives the seam's live capability roles.
            if (nw_path_uses_interface_type(path, nw_interface_type_wifi)) addAll(wifiInterfaceTypes(path))
        }
        val reason = if (status == NwPathStatus.Unsatisfied) {
            when (nw_path_get_unsatisfied_reason(path)) {
                nw_path_unsatisfied_reason_cellular_denied -> NwUnsatisfiedReason.CellularDenied
                nw_path_unsatisfied_reason_wifi_denied -> NwUnsatisfiedReason.WifiDenied
                nw_path_unsatisfied_reason_local_network_denied -> NwUnsatisfiedReason.LocalNetworkDenied
                nw_path_unsatisfied_reason_vpn_inactive -> NwUnsatisfiedReason.VpnInactive
                nw_path_unsatisfied_reason_not_available -> NwUnsatisfiedReason.NotAvailable
                else -> NwUnsatisfiedReason.Unknown
            }
        } else {
            null
        }
        return NwPathState(status, interfaces, nw_path_is_expensive(path), nw_path_is_constrained(path), reason)
    }

    /**
     * The Wi-Fi [NwInterfaceType]s present on [path] (#1554), split infra vs peer-to-peer by BSD interface name.
     * `nw_path_enumerate_interfaces` invokes its block SYNCHRONOUSLY for each interface (it returns before the
     * enumerate call completes — no async callback outliving this frame, unlike the receive hot path, so no
     * StableRef/shim is needed). Each Wi-Fi-type interface is classified by [classifyWifiInterface] over its
     * `nw_interface_get_name` (`en0` ⇒ WifiLan, `awdl0`/`llw0` ⇒ WifiDirect). A path may carry both at once, so
     * the result is a set. Fallback: if the path reports Wi-Fi usage but enumeration surfaces no Wi-Fi interface
     * (a name we cannot classify), conservatively return [NwInterfaceType.WifiLan] — never over-claim AWDL.
     */
    private fun wifiInterfaceTypes(path: nw_path_t): Set<NwInterfaceType> {
        val types = mutableSetOf<NwInterfaceType>()
        nw_path_enumerate_interfaces(path) { iface ->
            if (nw_interface_get_type(iface) == nw_interface_type_wifi) {
                types.add(classifyWifiInterface(nw_interface_get_name(iface)?.toKString()))
            }
            true
        }
        return types.ifEmpty { setOf(NwInterfaceType.WifiLan) }
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
     * Test-only (#1539): drive a LATE [setViability] for [id] directly, bypassing the `connections`-entry
     * gate that [driveReadyTransitionForTest]/[driveWaitingForTest] require — so a test can prove the
     * Closed-dominance latch: a viability update for a connection already latched [NwConnState.Closed] must be
     * ignored (terminal-closed-wins-over-late-viability). Not part of the fabric contract.
     */
    internal fun driveSetViabilityForTest(id: NwConnectionId, viable: Boolean) = setViability(id, viable)

    /**
     * Test-only: drive the failure-capture path for [id] with injected [domain]/[code], exercising the exact
     * production [captureFailure] plumbing a FAILED/WAITING transition runs — but bypassing the real
     * `nw_error_t` decode (no `nw_error_t` is synthesizable in a unit test). Asserts the (domain, code) is
     * logged and exposed via [lastConnectionFailure] (#1560). Not part of the fabric contract.
     */
    internal fun driveFailureForTest(id: NwConnectionId, domain: Int, code: Int, phase: String = "FAILED") =
        captureFailure(id, domain, code, phase)

    /**
     * Test-only (#1522): drive [id]'s close on an INERT connection — the SAME [closeConnection] path the
     * `cancelled`/`failed` state handlers use, with NO `nw_*` call — so appleTest can unit-prove that the
     * closure is latched into [connectionStates] (as [NwConnState.Closed]) with the correctly-mapped reason. [failed] mirrors
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
        nw_browser_set_browse_results_changed_handler(newBrowser) { oldResult, newResult, _ ->
            // Network.framework delivers a browse change as (old, new): an ADD carries new (old is nil), an
            // UPDATE carries both, a REMOVAL carries only old (new is nil). Treat a present `new` as
            // add/update (existing onBrowseResult path) and an old-only change as a removal so a departed
            // endpoint is pruned from a discovery roster (#1447 item 2).
            when {
                newResult != null -> onBrowseResult(newResult)
                oldResult != null -> onBrowseResultRemoved(oldResult)
            }
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

    /**
     * **Fire-and-forget** (the [NwApi.send] best-effort contract, #1419). This NEVER throws to report a link
     * failure: `nw_connection_send` returns immediately and any error surfaces only asynchronously in the
     * completion block below (logged, not propagated). A broken link is therefore reported to `NwSeam` via
     * [connectionClosed]/[connectionStates] (the `failed`/`cancelled` state → [closeConnection] path), never
     * by a throw here. Consequence: `NwSeam`'s send-path eviction (`removeByConn` on a `send` throw) is
     * exercised ONLY by `FakeNwApi`'s synchronous-throw hook — against the real transport it is dead weight,
     * kept as idempotent best-effort. Eviction against reality is driven entirely by the close route.
     */
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
        // Thread the second block param — the nw_error_t — into onState (previously dropped as `_`), so a
        // FAILED handshake's actual reason (TLS alert / OSStatus) is captured instead of thrown away (#1560).
        nw_connection_set_state_changed_handler(connection) { state, error -> onState(id, connection, state, error) }
        nw_connection_start(connection)
    }

    private fun onState(id: NwConnectionId, connection: nw_connection_t, state: nw_connection_state_t?, error: nw_error_t?) {
        when (state) {
            nw_connection_state_ready -> onReady(id, connection)
            // WAITING is the path-lost limbo (unsatisfied route) that fires NO close — the #1478 wedge.
            // NW attaches an nw_error explaining WHY the path is unsatisfied; capture it if present (#1560).
            nw_connection_state_waiting -> { recordFailure(id, error, "WAITING"); onWaiting(id) }
            nw_connection_state_preparing -> log.debug { "nw.api.state id=${id.value} PREPARING" }
            // FAILED carries the terminal nw_error — for a TLS handshake failure its code is the TLS alert /
            // OSStatus. Decode+log it BEFORE closing so the reason is observable, not thrown away (#1560).
            nw_connection_state_failed -> {
                recordFailure(id, error, "FAILED")
                log.info { "nw.api.state id=${id.value} FAILED → closeConnection(failed=true)" }
                closeConnection(id, failed = true)
            }
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
     * connection's [connectionStates] latest value to [NwConnState.Viable] (path up), and — on the FIRST ready —
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
        // #1522/#1539: latch the closure into the drop-tolerant [connectionStates] as [NwConnState.Closed] —
        // the authoritative teardown signal the seam reconciles even when the `connectionClosed` tryEmit below
        // is DROPPED under buffer pressure (the fixed zombie: a dropped close used to strand a peer forever).
        // Closed supersedes any prior Viable/PathLost entry for this id, so there is no separate viability-clear.
        markClosed(id, reason)
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

    private fun onBrowseResultRemoved(result: nw_browse_result_t) {
        val ep = nw_browse_result_copy_endpoint(result) ?: return
        // Only a named Bonjour endpoint can be matched back to what onBrowseResult added (which keys on the
        // service name); a nameless removal has no roster entry to prune, so drop it. Best-effort, like the
        // other event streams — a missed removal simply leaves the roster one entry stale until re-browsed.
        val name = nw_endpoint_get_bonjour_service_name(ep)?.toKString() ?: return
        lock.withLock { endpointsById.remove(name) }
        _endpointLost.tryEmit(NwEndpoint(id = name, serviceName = name))
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
         * FIFO retention bound on [connectionStates] `Closed` entries (#1522): the newest N close markers are retained,
         * the oldest pruned. An in-flight close reconciles within milliseconds of the mark, so retaining
         * the last N is far more than enough while keeping the map from growing on a long-lived churny fabric.
         * Matched to `NwSeam.TOMBSTONE_CAP` (1024): a pruned-before-observed close marker recreates the
         * *permanent* zombie this signal exists to kill — a strictly worse symptom than a missed tombstone's
         * bounded one-frame misparse — so retention is at least as deep as the tombstone bound.
         */
        private const val CLOSED_RETENTION_CAP: Int = 1024
    }
}
