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
 * ## Discovery — endpoint id models the Bonjour TXT PeerId (Option A, #1502/#1660)
 * Each device may [markListening] (advertise + accept, under a `serviceName`/`serviceType`,
 * plus an optional per-peer `peerId`) and/or [markBrowsing] (under a `serviceType`). When a
 * device starts browsing type `T`, every device already listening on `T` is delivered to it as
 * an [NwEndpoint]; symmetrically, when a device starts listening on `T`, it is delivered to
 * every device already browsing `T`.
 *
 * The emitted [NwEndpoint.id] mirrors production ([RealNwApi.onBrowseResult]): when the listener
 * advertises a stable `peerId` in its Bonjour **TXT record** the id is that `peerId`; absent a
 * `peerId` the id derives from the advertised **`serviceName`** (the Bonjour service name
 * backstop). This is the faithful model that closes the #1502 blind spot: the harness previously
 * keyed every endpoint on a per-device-unique token (`"ep-<listenerDeviceId>"`), so two devices
 * advertising the SAME shared `serviceName` (as every peer does under `Rendezvous.New`) never
 * collided on one id — the exact self-vs-peer collision that only reproduced on AWDL hardware.
 * With `id = peerId ?: serviceName`, a shared-`serviceName` lobby with no TXT peerId collapses
 * self and peer onto one id (as real Bonjour + `RealNwApi.endpointsById` do), and the Option A
 * fix — advertising a distinct `peerId` per peer — separates them again.
 *
 * [connect] maps an endpoint back to its listening device through [endpointOwners] (the id → device
 * registry populated on [markListening]); a manually-constructed endpoint that was never advertised
 * falls back to the `"ep-<deviceId>"` naming convention ([listenerDeviceIdOf]).
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

    /**
     * Per-device advertise state. [peerId] models the stable identity a device publishes in its
     * Bonjour TXT record (Option A, #1502): when non-null it becomes the emitted [NwEndpoint.id];
     * when null the id derives from [serviceName] (the service-name backstop).
     */
    private data class Listening(val serviceName: String, val serviceType: String, val peerId: String?)

    /** One end of an open link: which device, and the handle that device sees. */
    private data class LinkEnd(val deviceId: String, val connectionId: NwConnectionId)

    private val devices = mutableMapOf<String, FakeNwApi>()
    private val listening = mutableMapOf<String, Listening>()
    private val browsing = mutableMapOf<String, String>() // deviceId -> serviceType

    /**
     * Emitted-endpoint-id → owning device id, populated on [markListening] (#1502). This is the fake
     * twin of `RealNwApi.endpointsById`: when two devices advertise the SAME id (a shared `serviceName`
     * with no TXT `peerId`), the later registration OVERWRITES the earlier — the id collapse the Option A
     * fix exists to prevent. [connect] resolves through this map first, then the `"ep-<deviceId>"` fallback.
     */
    private val endpointOwners = mutableMapOf<String, String>()

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

    /**
     * The [NwEndpoint.id] a listener advertises, mirroring [RealNwApi.onBrowseResult]: the TXT-record
     * [peerId] when present, else the Bonjour [serviceName] (#1502). Under `Rendezvous.New` every peer
     * shares one [serviceName], so a null [peerId] makes self and peer collide on the same id — the
     * blind spot Option A closes by publishing a distinct [peerId] per peer.
     */
    private fun advertisedEndpointId(serviceName: String, peerId: String?): String = peerId ?: serviceName

    private fun listenerDeviceIdOf(endpointId: String): String =
        endpointId.removePrefix("ep-")

    private fun nextConnId(deviceId: String): NwConnectionId {
        val n = (connCounters[deviceId] ?: 0)
        connCounters[deviceId] = n + 1
        return NwConnectionId("conn-$deviceId-$n")
    }

    // ── discovery ────────────────────────────────────────────────────────────

    suspend fun markListening(deviceId: String, serviceName: String, serviceType: String, peerId: String? = null) {
        listening[deviceId] = Listening(serviceName, serviceType, peerId)
        val endpointId = advertisedEndpointId(serviceName, peerId)
        // Register the id → device mapping (the fake twin of RealNwApi.endpointsById). Under a shared
        // serviceName with no peerId, a later listener OVERWRITES an earlier one on the same id — the
        // self/peer collapse Option A prevents.
        endpointOwners[endpointId] = deviceId
        // Announce this new listener to every device already browsing the type — INCLUDING
        // itself if it also browses `serviceType` (real mDNS returns self; see class KDoc / #1485).
        for ((browserId, browseType) in browsing) {
            if (browseType != serviceType) continue
            devices.getValue(browserId).emitEndpointFound(
                NwEndpoint(id = endpointId, serviceName = serviceName),
            )
        }
    }

    suspend fun markStopListening(deviceId: String) {
        val gone = listening.remove(deviceId) ?: return
        val endpointId = advertisedEndpointId(gone.serviceName, gone.peerId)
        // Only relinquish ownership if this device still owns the id — a collided id may have been
        // overwritten by another listener, whose entry must survive this device's departure.
        if (endpointOwners[endpointId] == deviceId) endpointOwners.remove(endpointId)
        // Symmetric with [markListening]: a listener that stops advertising is reported as REMOVED to every
        // device still browsing its type — real Bonjour/mDNS fires the browser's removed-result callback,
        // which RealNwApi surfaces as [NwApi.endpointLost]. This is what prunes a departed ghost from a
        // discovery roster (#1447 item 2).
        for ((browserId, browseType) in browsing) {
            if (browseType != gone.serviceType) continue
            devices.getValue(browserId).emitEndpointLost(
                NwEndpoint(id = endpointId, serviceName = gone.serviceName),
            )
        }
    }

    suspend fun markBrowsing(deviceId: String, serviceType: String) {
        browsing[deviceId] = serviceType
        // Deliver every device already listening on the type to this browser — INCLUDING
        // itself if it also advertises `serviceType` (real mDNS returns self; see class KDoc / #1485).
        for ((listenerId, l) in listening) {
            if (l.serviceType != serviceType) continue
            devices.getValue(deviceId).emitEndpointFound(
                NwEndpoint(id = advertisedEndpointId(l.serviceName, l.peerId), serviceName = l.serviceName),
            )
        }
    }

    fun markStopBrowsing(deviceId: String) {
        browsing.remove(deviceId)
    }

    // ── connect / data / close ─────────────────────────────────────────────────

    suspend fun connect(dialerDeviceId: String, endpoint: NwEndpoint) {
        // Resolve the endpoint back to its listening device through the id → device registry
        // (populated on [markListening]); fall back to the `"ep-<deviceId>"` naming for a
        // manually-constructed endpoint that was never advertised (the direct-connect seam tests).
        val accepterId = endpointOwners[endpoint.id] ?: listenerDeviceIdOf(endpoint.id)
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

    /**
     * Inject a **self-dial** (#1466 / #1490): make [deviceId] dial its OWN advertised endpoint —
     * exactly what a symmetric advertise+browse device does when the radio returns its own endpoint to
     * its own browser (the self-endpoint delivery of #1485). Both resulting connections resolve to the
     * same device's `selfId`, which the `NwSeam` self-connection guard must drop. Used by the
     * conformance harness's `injectSelfDial` hook to prove the guard on a *live* seam.
     */
    suspend fun injectSelfDial(deviceId: String) {
        require(deviceId in devices) { "no device '$deviceId' to self-dial" }
        connect(deviceId, NwEndpoint(id = endpointIdFor(deviceId), serviceName = deviceId))
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
        // #1522/#1539: latch the closure into the drop-tolerant [connectionStates] as Closed on BOTH sides —
        // mirrors RealNwApi, where each side's own `closeConnection` marks its own connId closed. Closed
        // supersedes any prior Viable/PathLost entry (no separate viability prune needed), and this STATE is
        // what evicts a zombie peer even when the remote's connectionClosed EVENT is dropped (`dropCloseEvents`).
        devices[fromDeviceId]?.markConnectionClosed(connectionId, reason = null)
        devices.getValue(other.deviceId).markConnectionClosed(other.connectionId, reason = null)
        // Only the REMOTE side observes the close EVENT (the fast reason-carrying path); it may be dropped.
        devices.getValue(other.deviceId)
            .emitConnectionClosed(NwConnectionClosed(other.connectionId, reason = null))
    }
}
