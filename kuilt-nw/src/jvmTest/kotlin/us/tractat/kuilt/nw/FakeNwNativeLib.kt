package us.tractat.kuilt.nw

import com.sun.jna.Memory
import com.sun.jna.Pointer

/**
 * A [NwNativeLib] fake that models a 2-peer host↔joiner Network.framework link
 * **without the dylib**, so the JVM-side [BridgeNwApi] callback → channel → flow
 * wiring is exercised on the Linux CI runner. Mirrors
 * `DeliveringFakeMultipeerNativeLib`.
 *
 * ## Two runtimes on one fake
 * The two [BridgeNwApi] instances are constructed directly with [HOST] and
 * [JOINER] as their handles; the fake routes by handle:
 *  - [nw_start_browsing]`(JOINER, …)` fires the joiner's `endpointFound` for the
 *    single synthetic host endpoint ([ENDPOINT_ID] / [SERVICE_NAME]).
 *  - [nw_connect]`(JOINER, endpointId)` fires `connectionOpened` on BOTH sides —
 *    the joiner with the dialled endpoint, the host inbound (empty endpoint).
 *  - [nw_send] routes the bytes to the OTHER runtime's `bytesReceived` callback,
 *    preserving call order (synchronous, in-thread).
 *  - [nw_disconnect] fires `connectionClosed` on both sides.
 *
 * Fidelity is deliberately minimal — a 2-peer deliver-through is enough to prove
 * the wiring; full-mesh conformance is the gated real-dylib test (Task 4.2).
 *
 * [sendFailsFor] lets a test force `nw_send` to return `-1` for a chosen
 * connection id, exercising [BridgeNwApi.send]'s throw-on-`<0` path.
 */
internal class FakeNwNativeLib(
    private val sendFailsFor: String? = null,
) : NwNativeLib {

    /** How many times [nw_runtime_destroy] was invoked — for the close()-idempotency test. */
    var destroyCount: Int = 0
        private set

    companion object {
        val HOST: Pointer = Pointer(0x10L)
        val JOINER: Pointer = Pointer(0x11L)

        const val ENDPOINT_ID: String = "fake-endpoint"
        const val SERVICE_NAME: String = "fake-host"

        /** Connection id the joiner sees for the dialled link. */
        const val JOINER_CONN: String = "conn-joiner"

        /** Connection id the host sees for the accepted (inbound) link. */
        const val HOST_CONN: String = "conn-host"

        private val RUNTIME: Pointer = Pointer(0x01L)
        private val RENDEZVOUS: Pointer = Pointer(0x02L)
    }

    private val endpointFoundCbs = mutableMapOf<Pointer, NwNativeLib.EndpointFoundCallback>()
    private val connectionOpenedCbs = mutableMapOf<Pointer, NwNativeLib.ConnectionOpenedCallback>()
    private val bytesReceivedCbs = mutableMapOf<Pointer, NwNativeLib.BytesReceivedCallback>()
    private val connectionClosedCbs = mutableMapOf<Pointer, NwNativeLib.ConnectionClosedCallback>()

    override fun kuilt_protocol_version(): Int = NwNativeLib.EXPECTED_PROTOCOL_VERSION

    override fun nw_runtime_create(psk: ByteArray, pskLen: Int, identity: ByteArray, identityLen: Int): Pointer =
        RUNTIME

    override fun nw_runtime_destroy(handle: Pointer?) {
        destroyCount++
    }

    // The loopback ABI is exercised only by the real-dylib NwBridgeLoopbackConformanceTest; this
    // P2P-routing fake models the Bonjour path, so the loopback entry points are inert stubs here.
    override fun nw_loopback_rendezvous_create(): Pointer = RENDEZVOUS

    override fun nw_loopback_rendezvous_destroy(handle: Pointer?) {}

    override fun nw_runtime_create_loopback(
        psk: ByteArray,
        pskLen: Int,
        identity: ByteArray,
        identityLen: Int,
        rendezvous: Pointer?,
        dial: Int,
    ): Pointer = if (dial == 0) HOST else JOINER

    override fun nw_set_endpoint_found_callback(handle: Pointer?, cb: NwNativeLib.EndpointFoundCallback) {
        if (handle != null) endpointFoundCbs[handle] = cb
    }

    override fun nw_set_connection_opened_callback(handle: Pointer?, cb: NwNativeLib.ConnectionOpenedCallback) {
        if (handle != null) connectionOpenedCbs[handle] = cb
    }

    override fun nw_set_bytes_received_callback(handle: Pointer?, cb: NwNativeLib.BytesReceivedCallback) {
        if (handle != null) bytesReceivedCbs[handle] = cb
    }

    override fun nw_set_connection_closed_callback(handle: Pointer?, cb: NwNativeLib.ConnectionClosedCallback) {
        if (handle != null) connectionClosedCbs[handle] = cb
    }

    override fun nw_start_listening(handle: Pointer?, serviceName: String, serviceType: String): Int = 0

    override fun nw_stop_listening(handle: Pointer?): Int = 0

    override fun nw_start_browsing(handle: Pointer?, serviceType: String): Int {
        if (handle == JOINER) endpointFoundCbs[JOINER]?.invoke(ENDPOINT_ID, SERVICE_NAME)
        return 0
    }

    override fun nw_stop_browsing(handle: Pointer?): Int = 0

    override fun nw_connect(handle: Pointer?, endpointId: String): Int {
        // The joiner dials → both ends see a connection open. The joiner carries the dialled
        // endpoint; the host's accept is inbound (empty endpoint strings).
        connectionOpenedCbs[JOINER]?.invoke(JOINER_CONN, endpointId, SERVICE_NAME)
        connectionOpenedCbs[HOST]?.invoke(HOST_CONN, "", "")
        return 0
    }

    override fun nw_disconnect(handle: Pointer?, connectionId: String): Int {
        connectionClosedCbs[HOST]?.invoke(HOST_CONN, "")
        connectionClosedCbs[JOINER]?.invoke(JOINER_CONN, "")
        return 0
    }

    override fun nw_send(handle: Pointer?, connectionId: String, data: ByteArray, len: Int): Int {
        if (connectionId == sendFailsFor) return -1
        val target = if (handle == HOST) JOINER else HOST
        val targetConn = if (handle == HOST) JOINER_CONN else HOST_CONN
        // Copy into JNA-managed memory so the callback receives a real Pointer (as the dylib would).
        val mem = Memory(maxOf(len, 1).toLong())
        if (len > 0) mem.write(0, data, 0, len)
        bytesReceivedCbs[target]?.invoke(targetConn, mem, len)
        return 0
    }
}
