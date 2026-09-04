package us.tractat.kuilt.webrtc

import us.tractat.kuilt.conformance.JoinerRosterOrigin
import us.tractat.kuilt.conformance.SeamCapabilities
import us.tractat.kuilt.conformance.SeamConformanceSuite
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Tag

/**
 * Verifies that [WebRTCPeerLinkFactory] satisfies every invariant in [SeamConformanceSuite]
 * when backed by the in-memory [PairedFacadeFactory] / [PairedSignalingChannels] harness.
 *
 * Two factories share a fixed room name and are wired via paired fakes so open()/join()
 * can proceed concurrently without a real WebRTC environment. The paired fake already
 * drives both roles — it is the same harness used by [WebRTCPeerLinkFactoryTest].
 *
 * Per ADR-001 §Real-loopback-first: a real RTCPeerConnection loopback is possible only
 * if the wasmJs test runner provides WebRTC. The existing paired-fake harness is the
 * correct CI path and is used here.
 *
 * **The [ICE_CONNECTED] emissions in [newLoomPair] are load-bearing — do not delete them.** The
 * `reportsLiveCapability = true` flag selects the AWAITING branch of
 * [SeamConformanceSuite.wovenSeamCapabilityIsHonest], which blocks on
 * `capability.first { it.availability !is Unknown }`, and the ICE observer is the ONLY thing that
 * can satisfy it — there is no static availability seed ([WebRTCPeerLinkFactory]'s static report
 * supplies the ROLES only). [PairedFacadeFactory] starts from a `null` ICE state, so an unseeded
 * seam publishes `Unknown` forever: the await would never complete and the test would die on
 * `runTest`'s wall-clock backstop. [WebRTCPeerLinkCapabilityTest] pins that unseeded-floor
 * behaviour directly, so the claim this comment rests on is asserted rather than merely
 * asserted-about.
 */
class WebRTCConformanceTest : SeamConformanceSuite() {

    private companion object {
        /**
         * An ICE agent with a working candidate pair — the reading that folds to
         * [us.tractat.kuilt.core.FabricAvailability.Available]. Published on both paired fakes in
         * [newLoomPair] to drive the #1544 observer loop. Required setup, not decoration.
         */
        val ICE_CONNECTED = IceConnectionState.Connected
    }

    private val room = "conformance-room"

    override fun newLoomPair(): Pair<Loom, Loom> {
        val (hostFacFactory, joinerFacFactory) = PairedFacadeFactory.pair()
        val (hostSig, joinerSig) = PairedSignalingChannels.pair()
        // LOAD-BEARING (#1544/#1712): the ICE observer is the only source of a non-Unknown
        // availability, and the suite's reportsLiveCapability branch AWAITS one. Delete these and
        // the suite hangs to timeout.
        hostFacFactory.emitIceConnectionState(ICE_CONNECTED)
        joinerFacFactory.emitIceConnectionState(ICE_CONNECTED)
        val host = WebRTCPeerLinkFactory(
            signaling = hostSig,
            room = room,
            iceConfig = IceConfig.NoServers,
            facadeFactory = hostFacFactory,
        )
        val joiner = WebRTCPeerLinkFactory(
            signaling = joinerSig,
            room = room,
            iceConfig = IceConfig.NoServers,
            facadeFactory = joinerFacFactory,
        )
        return host to joiner
    }

    /**
     * The joiner's tag. [WebRTCPeerLinkFactory] ignores [Tag.peerKey] and uses its
     * own pinned [room]; [Tag.sessionName] is used only for peer-id labelling.
     */
    override fun joinTag(): Tag = object : Tag {
        override val sessionName = "host"
        override val peerKey = room
    }

    /**
     * RTCDataChannel is DTLS-encrypted; meshDelivery is vacuously true (a strictly
     * 2-peer PeerLink, no third peer to relay to — Task 1.8 / #1408 meshEvidence:
     * 2-peer vacuity).
     *
     * `supportsSendTo = true` (#1409): the resolved remote `PeerId` is reconciled into
     * the roster once the ID-exchange completes, and `sendTo` awaits that resolution,
     * so `sendTo(actualPeerId)` delivers to the named peer.
     *
     * `reportsLiveCapability = true` (#1544): [WebRTCPeerLink] drives its
     * [us.tractat.kuilt.core.Seam.capability] from the peer connection's ICE connection state,
     * whose browser binding is a real `oniceconnectionstatechange` observer in `BrowserRtcFacade` —
     * so this fabric is off the [us.tractat.kuilt.core.FabricAvailability.Unknown] floor. What this
     * harness proves is the seam's *reaction* to a signal; that a real `RTCPeerConnection` actually
     * *emits* one is provable only against real ICE.
     *
     * `collapsesPeersOnTear = true` (#1853): **both** tear paths collapse the roster to `{ selfId }`
     * *before* latching [us.tractat.kuilt.core.SeamState.Torn] — the remote data-channel close and
     * the local [us.tractat.kuilt.core.Seam.close] share one teardown. Until #1853 only the remote
     * path did, so a locally-closed link reported its pre-close roster forever.
     */
    override fun capabilities(): SeamCapabilities = SeamCapabilities.FULL

    override fun capabilityGaps(): Map<String, String> = emptyMap()

    /**
     * #2591/#2605: the joiner's roster holds two entries before a byte moves, so the joiner arm
     * cannot fail here — and #2605 triaged whether a real join-path fixture is constructible. It is
     * not, and the reason is worth reading, because this fabric is the one where "the fixture hands
     * the joiner its counterparty" is **false** and the arm is unfalsifiable anyway.
     */
    override fun joinerRosterOrigin(): JoinerRosterOrigin =
        JoinerRosterOrigin.FilledByConstruction(
            "a seeded roster, seeded with a FICTION rather than with the counterparty: " +
            "WebRTCPeerLinkFactory.buildLink mints guessedRemoteId = PeerId(randomToken(\"peer\")) on BOTH " +
            "roles and WebRTCPeerLink opens at MutableStateFlow(setOf(selfId, guessedRemoteId)), so the " +
            "joiner's roster has two entries from birth and the second one names nobody. Nothing in this " +
            "harness is handed to the joiner - both ids are local mints - which is exactly why no fixture " +
            "change reaches the arm: even a totally dead ID exchange leaves peers.size == 2. The real id " +
            "DOES arrive through the join path (the first data-channel frame resolves senderIdDeferred and " +
            "the background reconcile swaps it into the roster), but this obligation is a snapshot of " +
            "size, so it cannot see the difference. Converting it needs WebRTCPeerLink itself to open at " +
            "{ selfId } and grow on that reconcile - a fabric change with knock-on effects on sendTo's " +
            "resolvedRoster() await and on survivorStopsAdvertisingADepartedPeer (#2304), tracked at " +
            "https://github.com/tractat-us/kuilt/issues/2618. Triage recorded at " +
            "https://github.com/tractat-us/kuilt/issues/2605.",
        )
}
