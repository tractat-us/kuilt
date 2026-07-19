@file:OptIn(ExperimentalForeignApi::class)

package spike.nw

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.Foundation.NSDate
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.writeToFile
import platform.Foundation.timeIntervalSince1970
import platform.Network.NW_PARAMETERS_DEFAULT_CONFIGURATION
import platform.Network.nw_advertise_descriptor_create_bonjour_service
import platform.Network.nw_connection_create
import platform.Network.nw_connection_receive
import platform.Network.nw_connection_send
import platform.Network.nw_connection_set_queue
import platform.Network.nw_connection_set_state_changed_handler
import platform.Network.nw_connection_start
import platform.Network.nw_browse_descriptor_create_bonjour_service
import platform.Network.nw_browse_result_copy_endpoint
import platform.Network.nw_browser_create
import platform.Network.nw_browser_cancel
import platform.Network.nw_browser_set_browse_results_changed_handler
import platform.Network.nw_browser_set_queue
import platform.Network.nw_browser_start
import platform.Network.nw_browser_t
import platform.Network.nw_connection_cancel
import platform.Network.nw_listener_cancel
import platform.Network.nw_listener_t
import platform.Network.nw_content_context_create
import platform.Network.nw_connection_state_cancelled
import platform.Network.nw_connection_state_failed
import platform.Network.nw_connection_state_ready
import platform.Network.nw_connection_state_waiting
import platform.Network.nw_connection_t
import platform.Network.nw_listener_create
import platform.Network.nw_listener_set_advertise_descriptor
import platform.Network.nw_listener_set_new_connection_handler
import platform.Network.nw_listener_set_queue
import platform.Network.nw_listener_set_state_changed_handler
import platform.Network.nw_listener_start
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
import platform.posix.uint32_t
import kotlinx.cinterop.COpaquePointerVar

/**
 * Phase-0 connectivity spike binding. Two phones run the same app: one taps Host
 * (advertise + accept + echo), the other taps Join (connect by Bonjour name over
 * AWDL P2P + ping). Proves NW P2P connects and round-trips Wi-Fi-off where MC
 * stalls. Keeper code — seeds RealNwApi.
 *
 * Single strong-ref set for live connections (Network.framework cancels a
 * connection whose last ref drops). Everything runs on one serial dispatch queue.
 */
public class SpikeNw {
    private val queue = dispatch_queue_create("us.tractat.spike.nw", null)
    private val connections = mutableListOf<nw_connection_t>()
    private var onLog: ((String) -> Unit)? = null
    private var pingSentAt: Double = 0.0

    // Scenario-1 hooks (#1467): the connectivity suite drives the raw path as its transport control.
    // The JOIN fires this with the measured RTT on its first round-trip; the HOST fires it with 0 the
    // first time it echoes an inbound frame (host has no RTT of its own). Fired at most once.
    private var onFirstRoundTrip: ((Int) -> Unit)? = null
    private var firstRoundTripFired = false
    private var listener: nw_listener_t? = null
    private var browser: nw_browser_t? = null

    public fun setOnLog(cb: (String) -> Unit) {
        onLog = cb
    }

    public fun setOnFirstRoundTrip(cb: (Int) -> Unit) {
        onFirstRoundTrip = cb
    }

    private fun fireFirstRoundTrip(rttMs: Int) {
        if (firstRoundTripFired) return
        firstRoundTripFired = true
        onFirstRoundTrip?.invoke(rttMs)
    }

    /** Cancel all live connections + the listener/browser so the next scenario starts clean. */
    public fun stop() {
        connections.toList().forEach { nw_connection_cancel(it) }
        connections.clear()
        listener?.let { nw_listener_cancel(it) }; listener = null
        browser?.let { nw_browser_cancel(it) }; browser = null
        liveConn = null
    }

    /**
     * Single entry point the harness drives. Stamps a run-id FIRST so the harness
     * can prove THIS launch actually started (vs. reading a stale on-device log).
     */
    public fun start(role: String, runId: String) {
        log("START run=$runId role=$role")
        when (role) {
            "host" -> startHost()
            "join" -> startJoin()
            else -> log("START: unknown role '$role'")
        }
    }

    private val lines = mutableListOf<String>()
    private val logPath = NSHomeDirectory() + "/Documents/nw.log"

    private fun log(msg: String) {
        val stamped = "${NSDate().timeIntervalSince1970} $msg"
        println("[nw] $stamped") // stdout for `devicectl --console` when reachable
        lines.add(stamped)
        // Persist to the app container so the Wi-Fi-OFF window (when devicectl can't
        // reach the phone) is recoverable via `devicectl device copy from` afterward.
        (lines.joinToString("\n") as NSString)
            .writeToFile(logPath, atomically = true, encoding = NSUTF8StringEncoding, error = null)
        onLog?.invoke(msg)
    }

    // ── PSK params (place 3 of 3 for includePeerToPeer) ──────────────────────
    private fun secureParams(): nw_parameters_t? {
        val params = nw_parameters_create_secure_tcp(
            configure_tls = { options: nw_protocol_options_t? ->
                val sec = nw_tls_copy_sec_protocol_options(options)
                sec_protocol_options_add_pre_shared_key(sec, toDispatchData(PSK), toDispatchData(PSK_ID))
            },
            configure_tcp = NW_PARAMETERS_DEFAULT_CONFIGURATION,
        )
        nw_parameters_set_include_peer_to_peer(params, true)
        return params
    }

    // ── Host ─────────────────────────────────────────────────────────────────
    public fun startHost() {
        val listener = nw_listener_create(secureParams()).also { this.listener = it }
        nw_listener_set_queue(listener, queue)
        nw_listener_set_advertise_descriptor(
            listener,
            nw_advertise_descriptor_create_bonjour_service(HOST_NAME, SERVICE_TYPE, null),
        )
        nw_listener_set_state_changed_handler(listener) { _, _ -> }
        nw_listener_set_new_connection_handler(listener) { connection ->
            log("host: inbound connection")
            connection?.let { retainAndStart(it, isHost = true) }
        }
        nw_listener_start(listener)
        log("host: advertising $HOST_NAME.$SERVICE_TYPE (P2P, TLS-PSK)")
    }

    // ── Join ─────────────────────────────────────────────────────────────────
    // MUST browse (not synthesize a Bonjour name): only an active NWBrowser
    // brings up AWDL. Connect to the browser-provided endpoint so the connection
    // rides the AWDL interface the browser discovered.
    public fun startJoin() {
        val descriptor = nw_browse_descriptor_create_bonjour_service(SERVICE_TYPE, null)
        val browser = nw_browser_create(descriptor, secureParams()).also { this.browser = it }
        nw_browser_set_queue(browser, queue)
        nw_browser_set_browse_results_changed_handler(browser) { _, newResult, _ ->
            if (newResult != null) {
                joinEndpoint = nw_browse_result_copy_endpoint(newResult) // may update to the AWDL endpoint when Wi-Fi drops
                dialJoin()
            }
        }
        nw_browser_start(browser)
        log("join: browsing $SERVICE_TYPE over P2P (activates AWDL)")
    }

    private var joinEndpoint: platform.Network.nw_endpoint_t? = null
    private var liveConn: nw_connection_t? = null
    private var joinAttempt = 0

    private fun dialJoin() {
        if (liveConn != null) return // already connected/connecting
        val ep = joinEndpoint ?: return
        joinAttempt += 1
        log("join: attempt $joinAttempt — dialing discovered endpoint")
        val connection = nw_connection_create(ep, secureParams())
        liveConn = connection
        connection?.let { retainAndStart(it, isHost = false) }
    }

    // ── shared connection lifecycle ──────────────────────────────────────────
    private fun retainAndStart(connection: nw_connection_t, isHost: Boolean) {
        connections.add(connection) // strong ref — NW cancels a connection whose last ref drops
        nw_connection_set_queue(connection, queue)
        nw_connection_set_state_changed_handler(connection) { state, _ ->
            when (state) {
                nw_connection_state_ready -> {
                    log("${role(isHost)}: READY")
                    receiveLoop(connection, isHost)
                    if (!isHost) sendPing(connection)
                }
                nw_connection_state_waiting -> log("${role(isHost)}: waiting")
                nw_connection_state_failed -> {
                    log("${role(isHost)}: FAILED")
                    connections.remove(connection)
                    if (!isHost) { liveConn = null; dialJoin() } // re-attempt over AWDL
                }
                nw_connection_state_cancelled -> {
                    log("${role(isHost)}: cancelled")
                    connections.remove(connection)
                    if (!isHost) { liveConn = null; dialJoin() }
                }
                else -> Unit
            }
        }
        nw_connection_start(connection)
    }

    private fun receiveLoop(connection: nw_connection_t, isHost: Boolean) {
        nw_connection_receive(connection, 1u, (64 * 1024).toUInt()) { content, _, isComplete, error ->
            // BOUNDARY INSTRUMENTATION: prove whether the receive completion fires at all.
            log("${role(isHost)}: recv-fired content=${content != null} err=${error != null} complete=$isComplete")
            if (content != null) {
                val bytes = fromDispatchData(content)
                val text = bytes.decodeToString()
                if (isHost) {
                    log("host: recv '$text' → echoing")
                    fireFirstRoundTrip(0) // host has no RTT; signal "inbound data echoed"
                    send(connection, bytes, isHost) // echo
                } else {
                    val rttMs = ((NSDate().timeIntervalSince1970 - pingSentAt) * 1000).toInt()
                    log("join: recv '$text'  RTT=${rttMs}ms")
                    fireFirstRoundTrip(rttMs)
                    sendPing(connection)
                }
            }
            if (error == null) receiveLoop(connection, isHost) // re-arm only if not errored
        }
    }

    private fun sendPing(connection: nw_connection_t) {
        pingSentAt = NSDate().timeIntervalSince1970
        send(connection, "ping".encodeToByteArray(), isHost = false)
    }

    private fun send(connection: nw_connection_t, bytes: ByteArray, isHost: Boolean) {
        val context = nw_content_context_create("spike")
        nw_connection_send(
            connection,
            toDispatchData(bytes),
            context,
            true,
        ) { error ->
            // BOUNDARY INSTRUMENTATION: did the send actually complete, and with an error?
            log("${role(isHost)}: send-done bytes=${bytes.size} err=${error != null}")
        }
    }

    private fun role(isHost: Boolean) = if (isHost) "host" else "join"

    // ── dispatch_data <-> ByteArray ──────────────────────────────────────────
    private fun toDispatchData(bytes: ByteArray): dispatch_data_t =
        bytes.usePinned { pinned ->
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
        const val SERVICE_TYPE = "_spikenw._tcp"
        const val HOST_NAME = "spikehost"
        val PSK = "kuilt-nw-spike-psk".encodeToByteArray()
        val PSK_ID = "spike".encodeToByteArray()
    }
}
