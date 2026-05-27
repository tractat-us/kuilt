package us.tractat.kuilt.multipeer

import us.tractat.kuilt.conformance.SeamConformanceSuite
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Tag

/**
 * Verifies that [MultipeerPeerLinkFactory] satisfies every invariant in
 * [SeamConformanceSuite] when backed by a [DeliveringFakeMultipeerNativeLib].
 *
 * ## Why a delivering fake at the JNA boundary?
 * MultipeerConnectivity is architecturally role-split: one factory hosts (advertises +
 * auto-accepts), another joins (browses + connects). One instance cannot play both roles —
 * `open()` and `join()` both `check(activeSession == null)` on the same factory. There is
 * also no in-process real transport (the Apple radio is macOS/device-only with no loopback
 * mode). Per ADR-001 §"Per-fabric harness implications" the CI path routes through a
 * delivering fake at the JNA boundary.
 *
 * The [DeliveringFakeMultipeerNativeLib] (the "bus") is shared between the two factories.
 * It routes `mc_session_broadcast` from the host's session into the joiner's data callback
 * and vice-versa, and fires peer-state connected events once both [BridgePeerLink]
 * constructors have registered their callbacks — completing the virtual MC handshake.
 *
 * ## joinTag override
 * [SeamConformanceSuite.joinTag] must return a [MultipeerAdvertisement] so that
 * `MultipeerPeerLinkFactory.weave(Rendezvous.Existing(...))` does not throw. The handle
 * value is arbitrary — the delivering fake ignores it and always routes to [JOINER_SESSION].
 */
class MultipeerConformanceTest : SeamConformanceSuite() {

    companion object {
        private const val HOST_DISPLAY_NAME = "conformance-host"
        private const val JOINER_DISPLAY_NAME = "conformance-joiner"
        private const val FAKE_SERVICE_TYPE = "kuilt-test"
        private const val FAKE_HOST_HANDLE = "fake-host-handle"
    }

    override fun newLoomPair(): Pair<Loom, Loom> {
        val bus = DeliveringFakeMultipeerNativeLib(
            hostPeerId = HOST_DISPLAY_NAME,
            joinerPeerId = JOINER_DISPLAY_NAME,
        )
        val hostFactory = MultipeerPeerLinkFactory(
            displayName = HOST_DISPLAY_NAME,
            serviceType = FAKE_SERVICE_TYPE,
            injectedLib = bus,
            injectedRuntimeHandle = DeliveringFakeMultipeerNativeLib.HOST_SESSION,
        )
        val joinerFactory = MultipeerPeerLinkFactory(
            displayName = JOINER_DISPLAY_NAME,
            serviceType = FAKE_SERVICE_TYPE,
            injectedLib = bus,
            injectedRuntimeHandle = DeliveringFakeMultipeerNativeLib.JOINER_SESSION,
        )
        return hostFactory to joinerFactory
    }

    /**
     * The joiner must present a [MultipeerAdvertisement] so [MultipeerPeerLinkFactory.weave]
     * can call `mc_runtime_join`. The handle is arbitrary — the delivering fake routes all
     * join calls to [DeliveringFakeMultipeerNativeLib.JOINER_SESSION] regardless.
     */
    override fun joinTag(): Tag = MultipeerAdvertisement(
        handle = FAKE_HOST_HANDLE,
        displayName = HOST_DISPLAY_NAME,
        serviceType = FAKE_SERVICE_TYPE,
    )
}
