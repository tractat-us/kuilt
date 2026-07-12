@file:OptIn(ExperimentalForeignApi::class)

package us.tractat.kuilt.nw

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
import platform.darwin.dispatch_data_create
import platform.darwin.dispatch_data_create_map
import platform.darwin.dispatch_data_t
import platform.darwin.dispatch_queue_create
import platform.posix.size_tVar
import us.tractat.kuilt.core.FabricAvailability

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
 * Each of the four flows is fed by exactly ONE callback source, so it is **single-collection** —
 * two collectors would each receive every event, duplicating delivery. The GCD handlers run off
 * the dispatch queue (not a coroutine), so they publish via [MutableSharedFlow.tryEmit] onto a
 * buffered, no-replay flow. A full buffer therefore DROPS the event under `tryEmit` (bounded
 * backpressure — the known head-of-line concern for this phase; not "fixed" here).
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

    override val endpointFound: Flow<NwEndpoint> = _endpointFound.asSharedFlow()
    override val connectionOpened: Flow<NwConnectionOpened> = _connectionOpened.asSharedFlow()
    override val bytesReceived: Flow<NwBytesReceived> = _bytesReceived.asSharedFlow()
    override val connectionClosed: Flow<NwConnectionClosed> = _connectionClosed.asSharedFlow()

    /** One live connection: its strong-ref'd handle, the dialled endpoint (null inbound), and the local-close flag. */
    private class ConnectionEntry(
        val connection: nw_connection_t,
        val endpoint: NwEndpoint?,
        var closing: Boolean = false,
    )

    // Guards [connections], [endpointsById], [listener], and [browser]. NO nw_* call runs under it.
    private val lock = reentrantLock()
    private val connections = mutableMapOf<NwConnectionId, ConnectionEntry>()
    private val endpointsById = mutableMapOf<String, nw_endpoint_t>()
    private var listener: nw_listener_t? = null
    private var browser: nw_browser_t? = null

    private val connectionCounter = atomic(0L)

    override fun availability(): FabricAvailability = FabricAvailability.Available

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
                // only symptom is a downstream weave timeout, an opaque flake on the required CI gate.
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
            log.debug { "nw.connect unknown endpoint id=${endpoint.id}" }
            return
        }
        val connection = nw_connection_create(ep, secureParams())
        if (connection == null) {
            log.debug { "nw.connect create failed endpoint id=${endpoint.id}" }
            return
        }
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
        nw_connection_set_queue(connection, queue)
        nw_connection_set_state_changed_handler(connection) { state, _ -> onState(id, connection, state) }
        nw_connection_start(connection)
    }

    private fun onState(id: NwConnectionId, connection: nw_connection_t, state: nw_connection_state_t?) {
        when (state) {
            nw_connection_state_ready -> {
                val endpoint = lock.withLock { connections[id]?.endpoint }
                _connectionOpened.tryEmit(NwConnectionOpened(id, endpoint))
                receiveLoop(id, connection)
            }
            nw_connection_state_waiting -> log.debug { "nw.conn waiting id=${id.value}" }
            nw_connection_state_failed -> closeConnection(id, failed = true)
            nw_connection_state_cancelled -> closeConnection(id, failed = false)
            else -> Unit
        }
    }

    /**
     * Drop the strong ref for [id] and emit `connectionClosed`. Reason mapping: a locally-initiated
     * cancel ([ConnectionEntry.closing] set by [disconnect]/[stopListening]) is graceful (reason
     * null); a `failed`, or a `cancelled` we did not initiate, carries a non-null reason.
     */
    private fun closeConnection(id: NwConnectionId, failed: Boolean) {
        val entry = lock.withLock { connections.remove(id) } ?: return // already dropped — idempotent
        val reason = when {
            failed -> "connection failed"
            entry.closing -> null // our own graceful cancel
            else -> "connection cancelled remotely"
        }
        _connectionClosed.tryEmit(NwConnectionClosed(id, reason))
    }

    private fun receiveLoop(id: NwConnectionId, connection: nw_connection_t) {
        nw_connection_receive(connection, RECEIVE_MIN_LENGTH, RECEIVE_MAX_LENGTH) { content, _, _, error ->
            if (content != null) _bytesReceived.tryEmit(NwBytesReceived(id, fromDispatchData(content)))
            if (error == null) receiveLoop(id, connection) // re-arm only while healthy
        }
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

    private fun fromDispatchData(data: dispatch_data_t): ByteArray = memScoped {
        val ptr = alloc<COpaquePointerVar>()
        val size = alloc<size_tVar>()
        dispatch_data_create_map(data, ptr.ptr, size.ptr)
        val len = size.value.toInt()
        if (len == 0) ByteArray(0) else ptr.value!!.readBytes(len)
    }

    private companion object {
        /** Stable discovery id for the single synthesized loopback peer (loopback mode has no Bonjour name). */
        private const val LOOPBACK_PEER_ID = "loopback-peer"
        /** The loopback interface the joiner dials the host's ephemeral port on. */
        private const val LOOPBACK_HOST = "127.0.0.1"
        private const val EVENT_BUFFER = 16
        private const val BYTES_BUFFER = 64
        private const val RECEIVE_MIN_LENGTH: UInt = 1u
        private const val RECEIVE_MAX_LENGTH: UInt = 65_536u // 64 KiB
    }
}
