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
 * ### TXT delivery is a CONTRACT, not a courtesy (#1706)
 * A browser only receives TXT records **if it asked for them**. Network.framework's default is
 * NOT to query TXT (`browse_descriptor.h`: "by default, the browser will not automatically query
 * for TXT records") — [markBrowsing]'s `includeTxtRecord` therefore defaults to `false`, matching
 * the real descriptor, and only a browser that opted in is delivered the advertised `peerId`. This
 * is the exact axis the harness was blind to before #1706: it modelled the real API's *success*
 * behaviour (a `peerId` was handed over because [markListening] was given one) rather than its
 * *contract* (including what the real API declines to do unless asked). `RealNwApi.startBrowsing`
 * omitted `nw_browse_descriptor_set_include_txt_record`, so no TXT ever arrived, every endpoint
 * fell back to `id = serviceName`, and the pre-dial self-filter could never fire — a bug that
 * passed every in-harness test and was caught only by a 2-iPhone AWDL run.
 *
 * Three further real-wire conditions the fake now models, each previously assumed away:
 *  - **TXT absent / malformed** — a `null` (or blank, mirroring `nw_txt_record_find_key_non_empty_value`)
 *    `peerId` falls back to `id = serviceName`, the documented `?: name` backstop.
 *  - **TXT late** — [markListening] with `txtResolved = false` models the browse `add` arriving
 *    BEFORE the TXT record resolves (id falls back to `serviceName` even for an opted-in browser);
 *    [resolveTxt] then delivers the identity on a later browse *update*. Every fallback id is emitted
 *    with `identityResolved = false` so a consumer can tell a real identity from the backstop (#1709).
 *  - **Bonjour name collision / rename** — [renameService] models mDNS disambiguating two peers that
 *    advertise the same `serviceName` (`"lobby"` → `"lobby (2)"`), so the SAME peer is seen under
 *    BOTH names across sightings. Identity must therefore key on the TXT `peerId`, never `serviceName`.
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
     * Bonjour TXT record (Option A, #1502): when non-null (and non-blank — mirroring
     * `nw_txt_record_find_key_non_empty_value`) it becomes the emitted [NwEndpoint.id] **for a
     * browser that requested TXT**; otherwise the id derives from [serviceName] (the service-name
     * backstop). [txtResolved] models whether the TXT record has resolved yet: Network.framework
     * can deliver the browse `add` before TXT arrives and supply it on a later update (#1706), so
     * `false` means "advertised, identity not yet knowable" until [resolveTxt] fires.
     */
    private data class Listening(
        val serviceName: String,
        val serviceType: String,
        val peerId: String?,
        val txtResolved: Boolean,
    ) {
        /** The advertised TXT PeerId, or `null` when absent OR malformed (empty value). */
        val txtPeerId: String? get() = peerId?.takeIf { it.isNotEmpty() }
    }

    /**
     * Per-device browse state. [includeTxtRecord] is the fake twin of
     * `nw_browse_descriptor_set_include_txt_record` — when `false` (Network.framework's DEFAULT)
     * this browser is never delivered a TXT record, so every endpoint it sees falls back to
     * `id = serviceName` (#1706).
     */
    private data class Browsing(val serviceType: String, val includeTxtRecord: Boolean)

    /** One end of an open link: which device, and the handle that device sees. */
    private data class LinkEnd(val deviceId: String, val connectionId: NwConnectionId)

    private val devices = mutableMapOf<String, FakeNwApi>()
    private val listening = mutableMapOf<String, Listening>()
    private val browsing = mutableMapOf<String, Browsing>()

    /**
     * Emitted-endpoint-id → owning device id, populated on [markListening] (#1502). This is the fake
     * twin of `RealNwApi.endpointsById`: when two devices advertise the SAME id (a shared `serviceName`
     * with no TXT `peerId`), the later registration OVERWRITES the earlier — the id collapse the Option A
     * fix exists to prevent. [connect] resolves through this map first, then the `"ep-<deviceId>"` fallback.
     *
     * BOTH candidate ids are registered per listener — the TXT `peerId` AND the `serviceName` (#1706) —
     * because the id a browser sees now depends on whether IT requested TXT: an opted-in browser dials
     * the `peerId`, a non-opted-in browser dials the `serviceName`, and both must resolve back to the same
     * listening device. The shared-`serviceName` overwrite (and hence the self/peer collapse) is preserved
     * exactly, since the `serviceName` key is still last-writer-wins.
     */
    private val endpointOwners = mutableMapOf<String, String>()

    /** connId string of one end -> the OTHER end. Populated for BOTH directions. */
    private val links = mutableMapOf<String, LinkEnd>()

    /** Per-device monotonic connection-handle counter (deterministic ids, no RNG). */
    private val connCounters = mutableMapOf<String, Int>()

    /**
     * Links the radio has EVER opened — one per successful [connect], never decremented (#2390).
     *
     * The rig receipt for any [liveLinkCount] assertion. A settled mesh that never double-dialled
     * would satisfy "one live link per pair" vacuously, so a test that asserts the live count must
     * also assert that MORE links than that were opened in the first place: on a full mesh built by
     * dialling every ordered pair, `openedLinkCount == 2 * liveLinkCount` is the double-dial firing.
     */
    var openedLinkCount: Int = 0
        private set

    /**
     * Links currently open on the switchboard — the fake's stand-in for live sockets/file
     * descriptors (#2390).
     *
     * This is the ONLY place a surviving duplicate link is observable. `NwSeam.broadcast`/`sendTo`
     * fan out over its `registry`, one connection per peer, so a duplicate link that dedup failed
     * to disconnect is a *wasted* socket rather than a *duplicating* one: it changes no frame any
     * receiver sees, and it cannot change `Seam.peers`, which is a `Set<PeerId>`. Deleting
     * `NwSeam.resolveIdentity`'s dedup outright therefore reddened nothing in this module until a
     * test read this counter.
     *
     * [links] holds both ends of every link under their own connId, so the count is half its size;
     * the parity [check] fails loudly rather than silently halving an odd map if that ever stops
     * holding.
     */
    val liveLinkCount: Int
        get() {
            check(links.size % 2 == 0) { "links map holds ${links.size} ends — every link must have exactly two" }
            return links.size / 2
        }

    /** Register a device on construction. Ids must be distinct. */
    fun register(api: FakeNwApi) {
        require(api.deviceId !in devices) { "device '${api.deviceId}' already registered" }
        devices[api.deviceId] = api
    }

    private fun endpointIdFor(listenerDeviceId: String) = "ep-$listenerDeviceId"

    /**
     * The [NwEndpoint] a browser that did-or-didn't request TXT sees for listener [l], mirroring
     * [RealNwApi.onBrowseResult]'s `id = readPeerIdFromTxt(result) ?: name` (#1502/#1706).
     *
     * Three independent conditions each collapse the id back onto the [Listening.serviceName] backstop:
     *  1. the browser never asked for TXT (`browserWantsTxt == false` — Network.framework's default);
     *  2. the advertiser published no TXT PeerId, or a malformed/empty one ([Listening.txtPeerId]);
     *  3. the TXT record has not resolved yet ([Listening.txtResolved] — the add-before-TXT ordering).
     *
     * Under `Rendezvous.New` every peer shares one `serviceName`, so ANY of the three makes self and
     * peer collide on the same id — the blind spot Option A closes by publishing a distinct `peerId`
     * per peer AND opting the browser in to receive it.
     */
    private fun endpointFor(l: Listening, browserWantsTxt: Boolean): NwEndpoint {
        val txtId = l.txtPeerId?.takeIf { browserWantsTxt && l.txtResolved }
        // identityResolved mirrors RealNwApi.onBrowseResult: true iff the id came from a TXT record
        // this browser actually received, false when it is the serviceName backstop (#1709). All three
        // conditions above collapse it to false, exactly as they collapse the id.
        return NwEndpoint(
            id = txtId ?: l.serviceName,
            serviceName = l.serviceName,
            identityResolved = txtId != null,
        )
    }

    /**
     * Register BOTH ids this listener can be dialled under — the TXT `peerId` and the `serviceName`
     * (#1706) — so an opted-in and a non-opted-in browser both resolve back to [deviceId]. Last writer
     * wins per key, preserving the shared-`serviceName` collapse.
     */
    private fun registerOwnership(deviceId: String, l: Listening) {
        endpointOwners[l.serviceName] = deviceId
        l.txtPeerId?.let { endpointOwners[it] = deviceId }
    }

    /** Release only the ids this device still owns — a collided id may have been taken over by a later listener. */
    private fun releaseOwnership(deviceId: String, l: Listening) {
        if (endpointOwners[l.serviceName] == deviceId) endpointOwners.remove(l.serviceName)
        l.txtPeerId?.let { if (endpointOwners[it] == deviceId) endpointOwners.remove(it) }
    }

    private fun listenerDeviceIdOf(endpointId: String): String =
        endpointId.removePrefix("ep-")

    private fun nextConnId(deviceId: String): NwConnectionId {
        val n = (connCounters[deviceId] ?: 0)
        connCounters[deviceId] = n + 1
        return NwConnectionId("conn-$deviceId-$n")
    }

    // ── discovery ────────────────────────────────────────────────────────────

    /**
     * Advertise [serviceName] on [serviceType], optionally publishing [peerId] in the Bonjour TXT record.
     *
     * [txtResolved] models WHEN that TXT record becomes readable by a browser: `true` (the default) is the
     * simultaneous case — the browse `add` already carries TXT; `false` is the **add-before-TXT** ordering
     * Network.framework also produces, where the endpoint is discoverable but its identity is not yet
     * knowable and only a later [resolveTxt] supplies it (#1706).
     */
    suspend fun markListening(
        deviceId: String,
        serviceName: String,
        serviceType: String,
        peerId: String? = null,
        txtResolved: Boolean = true,
    ) {
        val l = Listening(serviceName, serviceType, peerId, txtResolved)
        listening[deviceId] = l
        // Register the id → device mappings (the fake twin of RealNwApi.endpointsById). Under a shared
        // serviceName with no peerId, a later listener OVERWRITES an earlier one on the same id — the
        // self/peer collapse Option A prevents.
        registerOwnership(deviceId, l)
        // Announce this new listener to every device already browsing the type — INCLUDING
        // itself if it also browses `serviceType` (real mDNS returns self; see class KDoc / #1485).
        // The id each browser sees depends on whether IT requested TXT (#1706).
        for ((browserId, b) in browsing) {
            if (b.serviceType != serviceType) continue
            devices.getValue(browserId).emitEndpointFound(endpointFor(l, b.includeTxtRecord))
        }
    }

    /**
     * Deliver [deviceId]'s TXT record on a LATER browse update, after its `add` already went out with the
     * identity unresolved (`markListening(txtResolved = false)`). Network.framework re-fires the
     * results-changed handler with `(old, new)` both present; `RealNwApi.onBrowseResult` treats a present
     * `new` as add-or-update and re-emits `endpointFound` — now with the TXT-derived id (#1706). No-op for
     * a device that is not listening, that published no TXT PeerId, or whose TXT already resolved.
     */
    suspend fun resolveTxt(deviceId: String) {
        val l = listening[deviceId] ?: return
        if (l.txtResolved || l.txtPeerId == null) return
        val resolved = l.copy(txtResolved = true)
        listening[deviceId] = resolved
        registerOwnership(deviceId, resolved)
        for ((browserId, b) in browsing) {
            if (b.serviceType != l.serviceType) continue
            devices.getValue(browserId).emitEndpointFound(endpointFor(resolved, b.includeTxtRecord))
        }
    }

    /**
     * Model mDNS **name-collision disambiguation** (#1706): [deviceId] keeps advertising the same TXT
     * `peerId` but its Bonjour instance name changes (`"lobby"` → `"lobby (2)"`) because a peer already
     * claimed the original. Real hardware showed exactly this under `Rendezvous.New`, where every peer
     * advertises the SAME `serviceName` — and the SAME peer then appeared under BOTH names across
     * sightings. A browser observes the old name removed and the new name added; for a TXT-opted-in
     * browser BOTH sightings carry the same `id`, which is precisely why identity must key on the TXT
     * `peerId` and never on `serviceName`. No-op if the device is not listening.
     */
    suspend fun renameService(deviceId: String, newServiceName: String) {
        val old = listening[deviceId] ?: return
        if (old.serviceName == newServiceName) return
        releaseOwnership(deviceId, old)
        val renamed = old.copy(serviceName = newServiceName)
        listening[deviceId] = renamed
        registerOwnership(deviceId, renamed)
        for ((browserId, b) in browsing) {
            if (b.serviceType != old.serviceType) continue
            val browser = devices.getValue(browserId)
            browser.emitEndpointLost(endpointFor(old, b.includeTxtRecord))
            browser.emitEndpointFound(endpointFor(renamed, b.includeTxtRecord))
        }
    }

    suspend fun markStopListening(deviceId: String) {
        val gone = listening.remove(deviceId) ?: return
        // Only relinquish ownership of ids this device still owns — a collided id may have been
        // overwritten by another listener, whose entry must survive this device's departure.
        releaseOwnership(deviceId, gone)
        // Symmetric with [markListening]: a listener that stops advertising is reported as REMOVED to every
        // device still browsing its type — real Bonjour/mDNS fires the browser's removed-result callback,
        // which RealNwApi surfaces as [NwApi.endpointLost]. This is what prunes a departed ghost from a
        // discovery roster (#1447 item 2).
        for ((browserId, b) in browsing) {
            if (b.serviceType != gone.serviceType) continue
            devices.getValue(browserId).emitEndpointLost(endpointFor(gone, b.includeTxtRecord))
        }
    }

    /**
     * Begin browsing [serviceType]. [includeTxtRecord] is the fake twin of
     * `nw_browse_descriptor_set_include_txt_record` and defaults to **`false`** — Network.framework's own
     * default ("by default, the browser will not automatically query for TXT records"). A browser that
     * does not opt in never receives an advertised `peerId`, so every endpoint it sees falls back to
     * `id = serviceName` (#1706). `RealNwApi.startBrowsing` opts in explicitly, and so does [FakeNwApi] —
     * a test models the omission by constructing the device with `browserIncludesTxtRecord = false`.
     */
    suspend fun markBrowsing(deviceId: String, serviceType: String, includeTxtRecord: Boolean = false) {
        browsing[deviceId] = Browsing(serviceType, includeTxtRecord)
        // Deliver every device already listening on the type to this browser — INCLUDING
        // itself if it also advertises `serviceType` (real mDNS returns self; see class KDoc / #1485).
        for ((_, l) in listening) {
            if (l.serviceType != serviceType) continue
            devices.getValue(deviceId).emitEndpointFound(endpointFor(l, includeTxtRecord))
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
        openedLinkCount += 1
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
