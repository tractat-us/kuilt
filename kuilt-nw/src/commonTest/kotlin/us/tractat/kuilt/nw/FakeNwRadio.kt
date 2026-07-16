package us.tractat.kuilt.nw

/**
 * In-memory switchboard wiring together N distinct [FakeNwApi] devices — the JVM
 * test vehicle that lets the transport-logic TCK run without Network.framework.
 *
 * ## Role-split (fixes #1404): one [FakeNwApi] per simulated device
 * Unlike a single-pair fake where one instance serves BOTH host and join roles,
 * this radio routes between N genuinely-distinct [FakeNwApi] instances — one per
 * simulated device, each with its own event flows. There is no shared-state crutch
 * where the same loom sees both ends of a link, so cross-device roster/identity/dedup
 * bugs surface on the JVM exactly as they would across two phones.
 *
 * ## Discovery
 * Each device may [markListening] (advertise + accept, under a `serviceName`/`serviceType`)
 * and/or [markBrowsing] (under a `serviceType`). When a device starts browsing type `T`,
 * every device already listening on `T` is delivered to it as an
 * [NwEndpoint]; symmetrically, when a device starts listening on `T`, it is delivered to
 * every device already browsing `T`. The endpoint `id` encodes the LISTENING device's
 * id (`"ep-<listenerDeviceId>"`) so [connect] can map an endpoint back to its device.
 *
 * A device that both advertises AND browses type `T` is delivered its OWN endpoint — real
 * Bonjour/mDNS returns a device's own advertisement to its own browser, so the fake must too
 * (fixes #1485). That self-endpoint is what drives the self-dial the `NwSeam` guard must drop;
 * omitting it is why the #1466 self-connection bug went uncaught until hardware.
 *
 * ## Connect — a distinct [NwConnectionId] per side
 * When device A dials an endpoint, the radio resolves the endpoint to its listening device B,
 * allocates two distinct handles (`"conn-<A>-<n>"` for A, `"conn-<B>-<n>"` for B — deterministic
 * counters, NO RNG), and records the bidirectional link `(A, connIdA) ↔ (B, connIdB)`. A (the
 * dialler) sees `connectionOpened(connIdA, endpoint = <the dialled endpoint>)`; B (the accepter)
 * sees `connectionOpened(connIdB, endpoint = null)` — an inbound connection has no dialled endpoint.
 *
 * Because a full mesh has every device both advertising AND browsing, each unordered pair forms
 * TWO connections (A dials B, B dials A) — a **double-dial**. That is intended and faithful: the
 * radio produces both; deduping the redundant link is `NwSeam`'s job (Task 2.5), not the radio's.
 *
 * ## Send / close
 * [send] looks up the link, finds the OTHER end's `(device, connId)`, and delivers the bytes there.
 * [disconnect] delivers `connectionClosed` to the OTHER device only (a peer that cancels its own
 * side tears it down directly; only the remote observes the close) and drops the link.
 *
 * ## Threading
 * Driven under `runTest`'s single virtual thread; every emit is a `suspend` call on the caller's
 * coroutine (no private [kotlinx.coroutines.CoroutineScope]). The registry/link maps are only ever
 * touched from that one test coroutine, so plain mutable maps are correct here and need no lock.
 */
internal class FakeNwRadio {

    /** Per-device advertise state. */
    private data class Listening(val serviceName: String, val serviceType: String)

    /** One end of an open link: which device, and the handle that device sees. */
    private data class LinkEnd(val deviceId: String, val connectionId: NwConnectionId)

    private val devices = mutableMapOf<String, FakeNwApi>()
    private val listening = mutableMapOf<String, Listening>()
    private val browsing = mutableMapOf<String, String>() // deviceId -> serviceType

    /** connId string of one end -> the OTHER end. Populated for BOTH directions. */
    private val links = mutableMapOf<String, LinkEnd>()

    /** Per-device monotonic connection-handle counter (deterministic ids, no RNG). */
    private val connCounters = mutableMapOf<String, Int>()

    /** Register a device on construction. Ids must be distinct. */
    fun register(api: FakeNwApi) {
        require(api.deviceId !in devices) { "device '${api.deviceId}' already registered" }
        devices[api.deviceId] = api
    }

    private fun endpointIdFor(listenerDeviceId: String) = "ep-$listenerDeviceId"

    private fun listenerDeviceIdOf(endpointId: String): String =
        endpointId.removePrefix("ep-")

    private fun nextConnId(deviceId: String): NwConnectionId {
        val n = (connCounters[deviceId] ?: 0)
        connCounters[deviceId] = n + 1
        return NwConnectionId("conn-$deviceId-$n")
    }

    // ── discovery ────────────────────────────────────────────────────────────

    suspend fun markListening(deviceId: String, serviceName: String, serviceType: String) {
        listening[deviceId] = Listening(serviceName, serviceType)
        // Announce this new listener to every device already browsing the type — INCLUDING
        // itself if it also browses `serviceType` (real mDNS returns self; see class KDoc / #1485).
        for ((browserId, browseType) in browsing) {
            if (browseType != serviceType) continue
            devices.getValue(browserId).emitEndpointFound(
                NwEndpoint(id = endpointIdFor(deviceId), serviceName = serviceName),
            )
        }
    }

    fun markStopListening(deviceId: String) {
        listening.remove(deviceId)
    }

    suspend fun markBrowsing(deviceId: String, serviceType: String) {
        browsing[deviceId] = serviceType
        // Deliver every device already listening on the type to this browser — INCLUDING
        // itself if it also advertises `serviceType` (real mDNS returns self; see class KDoc / #1485).
        for ((listenerId, l) in listening) {
            if (l.serviceType != serviceType) continue
            devices.getValue(deviceId).emitEndpointFound(
                NwEndpoint(id = endpointIdFor(listenerId), serviceName = l.serviceName),
            )
        }
    }

    fun markStopBrowsing(deviceId: String) {
        browsing.remove(deviceId)
    }

    // ── connect / data / close ─────────────────────────────────────────────────

    suspend fun connect(dialerDeviceId: String, endpoint: NwEndpoint) {
        val accepterId = listenerDeviceIdOf(endpoint.id)
        require(accepterId in devices) { "no device for endpoint '${endpoint.id}'" }
        val connIdDialer = nextConnId(dialerDeviceId)
        val connIdAccepter = nextConnId(accepterId)

        // Record the link in both directions.
        links[connIdDialer.value] = LinkEnd(accepterId, connIdAccepter)
        links[connIdAccepter.value] = LinkEnd(dialerDeviceId, connIdDialer)

        // Dialler carries the dialled endpoint; accepter has none.
        devices.getValue(dialerDeviceId)
            .emitConnectionOpened(NwConnectionOpened(connIdDialer, endpoint))
        devices.getValue(accepterId)
            .emitConnectionOpened(NwConnectionOpened(connIdAccepter, endpoint = null))
    }

    suspend fun send(fromDeviceId: String, connectionId: NwConnectionId, bytes: ByteArray) {
        val other = links[connectionId.value] ?: return // link already gone; drop
        devices.getValue(other.deviceId)
            .emitBytesReceived(NwBytesReceived(other.connectionId, bytes))
    }

    suspend fun disconnect(fromDeviceId: String, connectionId: NwConnectionId) {
        val other = links.remove(connectionId.value) ?: return
        // Drop the reverse mapping too so the link is fully torn down.
        links.remove(other.connectionId.value)
        // Only the REMOTE side observes the close.
        devices.getValue(other.deviceId)
            .emitConnectionClosed(NwConnectionClosed(other.connectionId, reason = null))
    }
}
