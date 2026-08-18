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
 * [connect] maps an endpoint back to its listening device through [endpointOwners] (the id → advertisers
 * registry populated on [markListening]); a manually-constructed endpoint that was never advertised
 * falls back to the `"ep-<deviceId>"` naming convention ([listenerDeviceIdOf]). When SEVERAL devices
 * advertise one id the name is ambiguous, as it is on real mDNS, and [resolutionBias] decides which
 * advertiser this dial lands on (#2416).
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
 * ## In-flight bytes — [holdSends] / [releaseSends] and the [sentFrames] ledger (#2425)
 * By default a [send] is delivered in the same virtual instant it is issued, so the radio has no
 * state in which bytes exist but have not arrived. A real transport does: `nw_connection_send`
 * accepts bytes, and cancelling that connection destroys whatever it has not yet put on the wire.
 * Two things in `NwSeam` are only observable through that state, and both are #2425:
 *
 *  - **Which link the seam RESOLVES on** is decided by which remote [NwHello] arrives first, and
 *    that is independent of which link the dedup then KEEPS (a function of the two nonces, i.e. of
 *    each seam's seeded `random`). Holding one link's traffic separates the two, so a test can drive
 *    the field's discriminating shape — *the seam resolves on the link the dedup then discards* —
 *    without serialising the two dials, which would beg the question by construction.
 *  - **A frame written into the publish-then-swap window** is lost with the link it was written to.
 *    Delivered-instantly bytes can never show that; held bytes can, and [SendFate.DiscardedOnClose]
 *    names it.
 *
 * [holdSends] is keyed on the SENDING end's [NwConnectionId], which is per-device unique, so a hold
 * is inherently DIRECTIONAL — holding `conn-A-0` silences A→B on that link and leaves B→A alone.
 * Every [send] is recorded in [sentFrames] with the fate it ended up having, so "where did that
 * frame go?" is a question the harness answers rather than one a test infers from a side effect.
 *
 * A hold also splits [send]'s "there is no link" case in two, and the halves must stay divergent
 * ([SendFate.Refused] vs [SendFate.DroppedLinkGone]) — the argument is on [deliverOrDrop].
 *
 * ## Threading
 * Driven under `runTest`'s single virtual thread; every emit is a `suspend` call on the caller's
 * coroutine (no private [kotlinx.coroutines.CoroutineScope]). The registry/link maps are only ever
 * touched from that one test coroutine, so plain mutable maps are correct here and need no lock.
 */
/**
 * What became of one [FakeNwRadio.send] (#2425). A frame's fate is decided when it is issued and may
 * change ONCE, when a held frame is later released or destroyed — never afterwards.
 */
internal enum class SendFate {
    /** Handed to the receiving device's `bytesReceived` flow. */
    Delivered,

    /** Queued by [FakeNwRadio.holdSends] — accepted by the transport, not yet at the far end. */
    InFlight,

    /** Was [InFlight] when its link was torn down: the bytes died with the socket and no error was raised. */
    DiscardedOnClose,

    /**
     * Released onto a link that had gone by the time the queue drained — lost, with **nobody told**.
     *
     * Reachable only from [FakeNwRadio.releaseSends]: the frame was handed off while the link was live,
     * so `send` had already returned normally and there is no caller left to raise anything to. Its
     * counterpart on the fresh-send path is [Refused], and the two are separate precisely because they
     * differ in whether the failure was reportable — which is the only part the seam can act on.
     */
    DroppedLinkGone,

    /**
     * Refused outright: [FakeNwRadio.send] threw [NwSendFailedException] because the addressed link was
     * already gone when the caller asked (#2455/#2459). The frame was never handed to the transport and
     * **the caller was told**, which is what drives `NwSeam`'s `removeByConn`.
     */
    Refused,
}

/**
 * One [FakeNwRadio.send] and what became of it (#2425).
 *
 * The ledger exists because "the consumer wrote a frame and it was silently lost" has no other
 * observable: the seam's `sendTo` returns normally, the transport reports nothing, and the receiving
 * end simply never sees it. Asserting on the RECEIVER cannot tell "lost" from "not sent yet"; this
 * record can, and it names WHICH link the write went out on, which is the whole question in a
 * publish-then-swap.
 *
 * [fate] and [wasHeld] are written only by [FakeNwRadio]. [wasHeld] is deliberately NOT derivable
 * from [fate] — a released frame ends [SendFate.Delivered], so without it a test could not prove its
 * hold ever fired, and a hold that silently stopped holding would pass quietly.
 */
internal class SentFrame(
    val fromDeviceId: String,
    val connectionId: NwConnectionId,
    val bytes: ByteArray,
) {
    var fate: SendFate = SendFate.Delivered
        internal set

    /** Whether this frame was ever queued by a [FakeNwRadio.holdSends] — the hold's own rig receipt. */
    var wasHeld: Boolean = false
        internal set

    override fun toString(): String =
        "SentFrame(from=$fromDeviceId conn=${connectionId.value} bytes=${bytes.size} fate=$fate held=$wasHeld)"
}

/**
 * Both ends of one link the radio opened, in the order it opened them (#2425).
 *
 * A test that drives a double dial has to name its two links — to hold one, to assert which one
 * survived — and the only handle it had before was the `"conn-<device>-<n>"` string convention,
 * which is a private implementation detail of [FakeNwRadio.nextConnId] that a test should not have
 * to reproduce. [FakeNwRadio.openedLinks] hands the identities back instead.
 */
internal data class Link(
    val dialerDeviceId: String,
    val dialerConnectionId: NwConnectionId,
    val accepterDeviceId: String,
    val accepterConnectionId: NwConnectionId,
    /** The [NwEndpoint.id] this link was dialled on. */
    val dialledEndpointId: String,
) {
    /** This link's end on [deviceId], or `null` if [deviceId] is not one of its two ends. */
    fun endOn(deviceId: String): NwConnectionId? = when (deviceId) {
        dialerDeviceId -> dialerConnectionId
        accepterDeviceId -> accepterConnectionId
        else -> null
    }
}

/**
 * The two links of one [FakeNwRadio.injectDoubleDial], named by direction from the dialling device's
 * point of view (#2425): [outbound] is the link that device dialled, [inbound] the one it accepted.
 *
 * Which of the two a seam RESOLVES on and which one its dedup KEEPS are independent, and the field
 * failure is precisely the case where they differ.
 */
internal data class DoubleDial(val outbound: Link, val inbound: Link)

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
     * Emitted-endpoint-id → the device ids advertising it, in advertise order, populated on
     * [markListening] (#1502/#2416). This is the fake twin of `RealNwApi.endpointsById`: when two devices
     * advertise the SAME id (a shared `serviceName` with no TXT `peerId`) the id COLLIDES — the collapse
     * the Option A fix exists to prevent. [connect] resolves through this map first, then the
     * `"ep-<deviceId>"` fallback.
     *
     * The value is a LIST rather than a single owner because a collided name is genuinely **ambiguous**
     * (#2416): real mDNS re-resolves an instance name at connect time and may land on any current
     * advertiser. Storing one owner modelled a collision as "the second advertiser cleanly replaces the
     * first" — deterministic, and always correct — so the divergence between the dialled id and the
     * accepting device could not be expressed at all. [resolutionBias] picks among the candidates.
     *
     * BOTH candidate ids are registered per listener — the TXT `peerId` AND the `serviceName` (#1706) —
     * because the id a browser sees depends on whether IT requested TXT: an opted-in browser dials the
     * `peerId`, a non-opted-in browser dials the `serviceName`, and both must resolve back to a listening
     * device.
     */
    private val endpointOwners = mutableMapOf<String, MutableList<String>>()

    /**
     * Which advertiser a name resolves to when SEVERAL hold it (#2416). Real mDNS re-resolves a name
     * at connect time and may land on any current advertiser; the default picks the first registered,
     * so every existing single-advertiser test is unchanged and deterministic.
     *
     * This exists because `endpointOwners[name] = deviceId` silently collapsed a collision to
     * last-writer-wins, making the ambiguity — the whole of #2416 — unreachable from a test. A
     * reference harness that cannot reach a failure guarantees no property is written for it.
     */
    var resolutionBias: (name: String, candidates: List<String>) -> String = { _, c -> c.first() }

    /** connId string of one end -> the OTHER end. Populated for BOTH directions. */
    private val links = mutableMapOf<String, LinkEnd>()

    /** Per-device monotonic connection-handle counter (deterministic ids, no RNG). */
    private val connCounters = mutableMapOf<String, Int>()

    /**
     * Every link the radio has EVER opened, in open order — one per successful [connect], never
     * removed (#2390/#2425). [openedLinkCount] is its size.
     *
     * A test naming links positionally (`openedLinks[0]` is the first dial) is reading the same list
     * the counter counts, so the identity surface and the rig receipt cannot drift apart.
     */
    private val opened = mutableListOf<Link>()

    /** @see opened */
    val openedLinks: List<Link> get() = opened

    /**
     * Links the radio has EVER opened — one per successful [connect], never decremented (#2390).
     *
     * The rig receipt for any [liveLinkCount] assertion. A settled mesh that never double-dialled
     * would satisfy "one live link per pair" vacuously, so a test that asserts the live count must
     * also assert that MORE links than that were opened in the first place: on a full mesh built by
     * dialling every ordered pair, `openedLinkCount == 2 * liveLinkCount` is the double-dial firing.
     */
    val openedLinkCount: Int get() = opened.size

    /** Sending ends currently held by [holdSends] — a [send] on one of these queues instead of delivering. */
    private val heldEnds = mutableSetOf<String>()

    /** Held sending end → the frames queued on it, in issue order. Drained by [releaseSends], destroyed by a close. */
    private val inFlight = mutableMapOf<String, MutableList<SentFrame>>()

    private val sent = mutableListOf<SentFrame>()

    /**
     * Every [send] this radio has been asked to make, in issue order, each carrying its [SentFrame.fate]
     * (#2425). The ledger a test reads to answer "which link did that frame go out on, and did it
     * arrive?" — neither of which the receiving end can distinguish from "nothing was sent".
     */
    val sentFrames: List<SentFrame> get() = sent

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
     * (#1706) — so an opted-in and a non-opted-in browser both resolve back to [deviceId]. A second
     * advertiser of the same id JOINS the candidate list rather than replacing its predecessor (#2416),
     * which is what makes the collision ambiguous instead of quietly deterministic. Re-registering an id
     * this device already holds (e.g. [resolveTxt]) keeps its original position.
     */
    private fun registerOwnership(deviceId: String, l: Listening) {
        fun claim(key: String) {
            val owners = endpointOwners.getOrPut(key) { mutableListOf() }
            if (deviceId !in owners) owners += deviceId
        }
        claim(l.serviceName)
        l.txtPeerId?.let(::claim)
    }

    /** Drop this device from the ids it advertised — a collided id survives while another advertiser holds it. */
    private fun releaseOwnership(deviceId: String, l: Listening) {
        fun release(key: String) {
            val owners = endpointOwners[key] ?: return
            owners -= deviceId
            if (owners.isEmpty()) endpointOwners.remove(key)
        }
        release(l.serviceName)
        l.txtPeerId?.let(::release)
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
        val owners = endpointOwners[endpoint.id]
        val accepterId = when {
            owners.isNullOrEmpty() -> listenerDeviceIdOf(endpoint.id)
            owners.size == 1 -> owners.single()
            // AMBIGUOUS: several devices advertise this name. Real mDNS picks one at connect time.
            else -> resolutionBias(endpoint.id, owners.toList())
        }
        require(accepterId in devices) { "no device for endpoint '${endpoint.id}'" }
        val connIdDialer = nextConnId(dialerDeviceId)
        val connIdAccepter = nextConnId(accepterId)
        opened += Link(dialerDeviceId, connIdDialer, accepterId, connIdAccepter, endpoint.id)

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
     * Form BOTH links of a **double dial** between [deviceId] and [peerDeviceId] (#2425), returning
     * them named by direction *from [deviceId]'s point of view*: [DoubleDial.outbound] is the link
     * [deviceId] dialled, [DoubleDial.inbound] the one it accepted.
     *
     * A full mesh produces this for every unordered pair — both peers advertise and browse, so both
     * dial — and it is the shape every #2425 scenario starts from. Two things make it worth a named
     * primitive rather than two [connect] calls:
     *
     *  - **Direction is the vocabulary of the failure.** The field capture reads "the seam resolved on
     *    the INBOUND link and the dedup kept the OUTBOUND one"; a test that has to work that out from
     *    `"conn-<device>-<n>"` counters is one renumbering away from silently asserting the mirror
     *    image of what it says it asserts.
     *  - **Nothing is pumped in between.** Both links exist, and neither seam has run a single
     *    collector, so the caller can install [holdSends] on either end BEFORE any [NwHello] moves.
     *    That is what lets arrival order be chosen rather than inherited from dial order — and dial
     *    order is exactly the variable a "dial one, settle, dial the other" setup would confound it
     *    with.
     *
     * Endpoints use the `"ep-<deviceId>"` convention [listenerDeviceIdOf] resolves, so neither device
     * has to be advertising; `serviceName` is carried for display only ([connect] routes on
     * [NwEndpoint.id] alone).
     */
    suspend fun injectDoubleDial(deviceId: String, peerDeviceId: String): DoubleDial {
        require(deviceId != peerDeviceId) { "a double dial needs two distinct devices, got '$deviceId' twice" }
        val before = opened.size
        connect(deviceId, NwEndpoint(id = endpointIdFor(peerDeviceId), serviceName = peerDeviceId))
        connect(peerDeviceId, NwEndpoint(id = endpointIdFor(deviceId), serviceName = deviceId))
        return DoubleDial(outbound = opened[before], inbound = opened[before + 1])
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

    /**
     * Inject a **misresolved dial**: a dial that carries the REMOTE peer's endpoint id but lands on the
     * LOCAL device. This is the connect-time hazard of #2416, and it is distinct from [injectSelfDial],
     * where the dialled id is the device's own.
     *
     * Under `Rendezvous.New` every peer advertises the SAME Bonjour instance name, so identity and dial
     * target are keyed on different things: the browse result identifies the peer by its TXT `PeerId`,
     * while the dial goes to that shared NAME, which mDNS re-resolves at connect time. Inside the window
     * before mDNS conflict-resolution renames one advertiser to `… (2)`, resolving that name can land on
     * the local device — so the connection carries `endpoint.id == <the real peer's id>` while both ends
     * resolve to this device's own `selfId`.
     *
     * [connect] cannot express this: it routes by `endpoint.id` (via `endpointOwners`, with
     * [resolutionBias] choosing among a contended name's advertisers), so the accepter is always a device
     * that ACTUALLY advertises the dialled id. Landing on a device that never advertised it — which is what
     * a name re-resolving mid-rename does — stays outside that. That disagreement IS the bug, which is why
     * the fake has to model it explicitly — a fake that only reproduces the substrate's happy path stops
     * testing every failure the real substrate can produce (the lesson of #1485, where omitting
     * self-discovery is exactly why the #1466 self-dial shipped uncaught).
     */
    suspend fun injectDialLandingOnSelf(
        deviceId: String,
        dialledEndpointId: String,
        identityResolved: Boolean = true,
    ) {
        require(deviceId in devices) { "no device '$deviceId' to dial from" }
        val connIdDialer = nextConnId(deviceId)
        val connIdAccepter = nextConnId(deviceId)
        opened += Link(deviceId, connIdDialer, deviceId, connIdAccepter, dialledEndpointId)
        links[connIdDialer.value] = LinkEnd(deviceId, connIdAccepter)
        links[connIdAccepter.value] = LinkEnd(deviceId, connIdDialer)
        // Dialler carries the dialled endpoint — the REAL peer's id; accepter has none (inbound).
        devices.getValue(deviceId).emitConnectionOpened(
            NwConnectionOpened(
                connIdDialer,
                NwEndpoint(id = dialledEndpointId, serviceName = deviceId, identityResolved = identityResolved),
            ),
        )
        devices.getValue(deviceId).emitConnectionOpened(NwConnectionOpened(connIdAccepter, endpoint = null))
    }

    /**
     * Destroy every link [deviceId] holds and tell **nobody** — no close EVENT, no [NwConnState.Closed]
     * STATE, on either side (#2455).
     *
     * The field shape behind #2425: a peer destroyed its end of a link and the notification never reached
     * the other side, which went on believing the connection was live and wrote a frame onto it. Every
     * other teardown hook here is a *notified* one — [disconnect] emits the close event and latches the
     * drop-tolerant STATE on both ends, and [FakeNwApi.dropCloseEvents] drops only the event while the
     * STATE still lands — so each of them evicts the peer through a route that is not the send path, and
     * none of them can leave a seam holding a connection that no longer exists. This one can: it is the
     * ONLY way to reach the state where the *send* is the first and only thing able to discover the loss.
     *
     * Link ends are found by the `conn-<deviceId>-<n>` naming [nextConnId] mints, the same convention
     * [listenerDeviceIdOf] leans on for endpoints; both ends of each link are removed, so [liveLinkCount]'s
     * even-parity invariant still holds.
     */
    fun severLinksSilently(deviceId: String) {
        require(deviceId in devices) { "no device '$deviceId' whose links could be severed" }
        val doomed = links.keys.filter { it.startsWith("conn-$deviceId-") }
        require(doomed.isNotEmpty()) { "device '$deviceId' holds no live link to sever" }
        for (end in doomed) {
            links.remove(end)?.let { links.remove(it.connectionId.value) }
        }
    }

    /**
     * Stop delivering what [connectionId]'s OWNER sends on it (#2425): each subsequent [send] is
     * recorded [SendFate.InFlight] and queued instead. Directional — [connectionId] is one device's
     * own handle for one link, so the reverse direction is untouched.
     *
     * Held bytes are in the state a real transport keeps them in between accepting a send and putting
     * it on the wire: [releaseSends] puts them there, and tearing the link down destroys them
     * ([SendFate.DiscardedOnClose]) with no error reported to anybody — which is exactly what a
     * consumer frame written into `NwSeam`'s publish-then-swap window suffers.
     *
     * Idempotent; may be called before the link exists (connIds are allocated deterministically, but
     * the intended idiom is to dial first and take the handle from [openedLinks]).
     */
    fun holdSends(connectionId: NwConnectionId) {
        heldEnds += connectionId.value
    }

    /**
     * Undo [holdSends] on [connectionId] and deliver everything queued on it, in issue order. A frame
     * whose link has since gone becomes [SendFate.DroppedLinkGone] rather than being delivered, and
     * **must not throw** — see [deliverOrDrop], which is where that obligation lives. No-op for an end
     * that was never held.
     */
    suspend fun releaseSends(connectionId: NwConnectionId) {
        heldEnds -= connectionId.value
        val queued = inFlight.remove(connectionId.value) ?: return
        for (record in queued) deliverOrDrop(record)
    }

    /** Frames currently queued on [connectionId] by [holdSends] — the hold's live rig receipt. */
    fun inFlightOn(connectionId: NwConnectionId): List<SentFrame> = inFlight[connectionId.value].orEmpty()

    /** Whether the radio still holds a link for [connectionId] — how a test names the dedup's SURVIVOR. */
    fun isLive(connectionId: NwConnectionId): Boolean = connectionId.value in links

    /**
     * Deliver [record] to the far end, or mark it [SendFate.DroppedLinkGone] if its link has gone —
     * **never throwing**, which is the whole difference between this and [send]'s own liveness check.
     *
     * The two reach the same "there is no link" state from call sites with opposite obligations, and
     * `NwApi.send`'s contract mandates both directions:
     *
     *  - [send] is asked while its caller is on the stack and nothing has been handed to the transport
     *    yet — an **immediately-known** failure, which MUST throw ([SendFate.Refused]). That throw is
     *    what drives `NwSeam`'s `removeByConn`, so making it a silent return is exactly the #2455
     *    defect the reference used to carry.
     *  - This is reached from [releaseSends], long after [send] returned normally on a link that was
     *    live at the time. The frame was already **handed off**, there is no caller left to tell, and
     *    the contract is explicit that such a failure is routed through the teardown signals "rather
     *    than inventing a late throw nobody is waiting to catch". Throwing here would surface an
     *    exception in a coroutine that owns nothing.
     *
     * That asymmetry is why [SendFate.Refused] and [SendFate.DroppedLinkGone] are separate fates
     * rather than one: they differ in whether anybody was told, which is the only thing the seam can
     * act on. Since #2459 this branch is the sole way a *send* is lost with nobody informed while the
     * link's disappearance was never announced — [discardInFlight] covers the announced close — so it
     * is the fake's remaining model of a silent loss, and deliberately so.
     */
    private suspend fun deliverOrDrop(record: SentFrame) {
        val other = links[record.connectionId.value]
        if (other == null) {
            record.fate = SendFate.DroppedLinkGone
            return
        }
        record.fate = SendFate.Delivered
        devices.getValue(other.deviceId).emitBytesReceived(NwBytesReceived(other.connectionId, record.bytes))
    }

    /**
     * An unknown/closed handle is an IMMEDIATELY-KNOWN failure and THROWS, mirroring `RealNwApi.send`
     * (#2455/#2459). This used to be `?: return` — the fake carried the very defect it stands in for, so
     * the seam's send-failure eviction was reachable here only through [FakeNwApi.failSend], a flag that
     * fails EVERY send regardless of the handle it names and so cannot distinguish "the fabric reports a
     * dead handle" from "the fabric was told to break".
     *
     * The refusal is recorded in [sentFrames] BEFORE it is raised, so a throw still leaves a ledger
     * entry: "this frame was refused" is an answer to *where did it go?*, and a test asserting the
     * fates of everything a device wrote would otherwise have a hole exactly where the interesting
     * case is.
     */
    suspend fun send(fromDeviceId: String, connectionId: NwConnectionId, bytes: ByteArray) {
        val record = SentFrame(fromDeviceId, connectionId, bytes)
        sent += record
        // Liveness FIRST, before the hold: a stale hold must never launder a dead link into a live
        // queue, and the caller is owed the refusal whether or not this end happens to be held.
        if (connectionId.value !in links) {
            record.fate = SendFate.Refused
            throw NwSendFailedException(
                "no live link for '${connectionId.value}' on device '$fromDeviceId' — unknown or already closed",
            )
        }
        if (connectionId.value in heldEnds) {
            record.fate = SendFate.InFlight
            record.wasHeld = true
            inFlight.getOrPut(connectionId.value) { mutableListOf() } += record
            return
        }
        deliverOrDrop(record)
    }

    /** The bytes a torn-down socket destroys: everything [holdSends] still had queued on [connectionId]. */
    private fun discardInFlight(connectionId: NwConnectionId) {
        inFlight.remove(connectionId.value)?.forEach { it.fate = SendFate.DiscardedOnClose }
    }

    suspend fun disconnect(fromDeviceId: String, connectionId: NwConnectionId) {
        val other = links.remove(connectionId.value) ?: return
        // Drop the reverse mapping too so the link is fully torn down.
        links.remove(other.connectionId.value)
        // Bytes still in flight on EITHER end die with the link, silently — no error reaches the sender
        // and nothing reaches the receiver (#2425). Both ends, because a cancelled connection destroys
        // the queues at both, not only at the peer that asked for the close.
        discardInFlight(connectionId)
        discardInFlight(other.connectionId)
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
