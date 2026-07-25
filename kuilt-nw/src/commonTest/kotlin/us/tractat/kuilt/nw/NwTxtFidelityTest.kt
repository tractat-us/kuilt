package us.tractat.kuilt.nw

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Bonjour TXT-record fidelity (#1706) — the harness conditions that let the #1660 root-1 bug ship.
 *
 * `RealNwApi.startBrowsing` created its browse descriptor and never called
 * `nw_browse_descriptor_set_include_txt_record`. Network.framework does **not** query TXT by default
 * ("by default, the browser will not automatically query for TXT records" — `browse_descriptor.h`),
 * so the advertised PeerId never arrived, every endpoint took `onBrowseResult`'s documented
 * `?: name` fallback, and under [Rendezvous.New] — where every peer advertises one shared session
 * `serviceName` — the loom's pre-dial self-filter could never match self. The peer dialled its own
 * endpoint, caught only post-connect by the `NwSeam` guard. It passed every test and was found by a
 * two-iPhone AWDL run (4 and 8 `nw.seam.self-connection` drops).
 *
 * `FakeNwRadio` missed it because it modelled TXT delivery as *always succeeding* — it mirrored what
 * the real API **does** rather than what it **contracts**, including what the real API declines to do
 * unless asked. These tests drive the four conditions it never modelled.
 *
 * **Scope, stated honestly:** these run on the JVM against the fake, so they pin the *consequences* of
 * each TXT condition and keep the opt-in load-bearing. They cannot detect `RealNwApi` dropping the
 * native call itself — that lives in appleMain behind Network.framework and is hardware-gated.
 */
class NwTxtFidelityTest {

    private companion object {
        const val TYPE = "_kuilt._tcp"
        const val SHARED_LOBBY = "shared-lobby"
    }

    /**
     * **The case that would have caught the shipped bug.** A browser that never requests TXT is handed
     * no TXT, so identity falls back to `serviceName`; under [Rendezvous.New]'s shared session name that
     * is the *same string* for self and every peer, so the pre-dial self-filter cannot distinguish self
     * and the loom dials its own endpoint.
     *
     * This is a characterization test: it asserts the broken behaviour that follows from omitting the
     * opt-in, which is what makes `nw_browse_descriptor_set_include_txt_record` load-bearing rather than
     * incidental. Its sibling [optedInBrowserResolvesTheTxtPeerIdAndFiltersSelf] asserts the fixed path.
     * Together they say: the opt-in is the difference between self-dialling and not.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aBrowserThatNeverRequestsTxtCannotFilterSelfUnderASharedServiceName() = runTest(StandardTestDispatcher()) {
        val radio = FakeNwRadio()
        val selfId = PeerId("self-uuid-1706")
        // browserIncludesTxtRecord = false — Network.framework's OWN default, i.e. the shipped omission.
        val api = FakeNwApi(
            radio,
            deviceId = "solo",
            serviceName = "solo",
            peerId = selfId.value,
            browserIncludesTxtRecord = false,
        )
        val loom = NwLoom(api, serviceType = TYPE, selfId = selfId, random = Random(0), weaveTimeout = 1.seconds)

        val opened = mutableListOf<NwConnectionOpened>()
        val spy = launch(start = CoroutineStart.UNDISPATCHED) { api.connectionOpened.collect { opened += it } }

        val weave = launch(start = CoroutineStart.UNDISPATCHED) {
            runCatchingCancellable { loom.weave(Rendezvous.New(Pattern(sessionName = SHARED_LOBBY))) }
        }
        testScheduler.advanceTimeBy(500)
        testScheduler.runCurrent()

        assertTrue(
            opened.isNotEmpty(),
            "without the TXT opt-in the loom cannot recognise its own endpoint under a shared serviceName " +
                "and self-dials — this is the #1660 root-1 failure the opt-in prevents",
        )

        spy.cancel()
        weave.cancel()
    }

    /** The fixed path: an opted-in browser resolves the TXT PeerId, so the self-filter fires. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun optedInBrowserResolvesTheTxtPeerIdAndFiltersSelf() = runTest(StandardTestDispatcher()) {
        val radio = FakeNwRadio()
        val selfId = PeerId("self-uuid-1706")
        // browserIncludesTxtRecord defaults to true — the production binding now makes the native call.
        val api = FakeNwApi(radio, deviceId = "solo", serviceName = "solo", peerId = selfId.value)
        val loom = NwLoom(api, serviceType = TYPE, selfId = selfId, random = Random(0), weaveTimeout = 1.seconds)

        val opened = mutableListOf<NwConnectionOpened>()
        val spy = launch(start = CoroutineStart.UNDISPATCHED) { api.connectionOpened.collect { opened += it } }

        val weave = launch(start = CoroutineStart.UNDISPATCHED) {
            runCatchingCancellable { loom.weave(Rendezvous.New(Pattern(sessionName = SHARED_LOBBY))) }
        }
        testScheduler.advanceTimeBy(500)
        testScheduler.runCurrent()

        assertAll(
            { assertTrue(opened.isEmpty(), "opted-in browser resolves the TXT PeerId and filters self, was $opened") },
            { assertTrue(loom.visiblePeers.value.isEmpty(), "self never rostered, was ${loom.visiblePeers.value}") },
        )

        spy.cancel()
        weave.cancel()
    }

    /**
     * `onBrowseResult`'s documented `id = readPeerIdFromTxt(result) ?: name` fallback, previously driven
     * by no test at all. An advertiser publishing no TXT PeerId (absent) — or a blank one (malformed,
     * mirroring `nw_txt_record_find_key_non_empty_value`) — must still be filtered as self under
     * [Rendezvous.Existing], where the loom advertises `serviceName = selfId.value` so the serviceName
     * backstop is a valid discriminator.
     *
     * This pins the backstop as a real, exercised path rather than dead defensive code.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun absentOrMalformedTxtFallsBackToTheServiceNameBackstop() = runTest(StandardTestDispatcher()) {
        // peerId = null models an absent TXT record; "" models a malformed/empty value. Both must yield
        // id = serviceName, which under Existing equals selfId and so is filtered.
        for (txt in listOf(null, "")) {
            val radio = FakeNwRadio()
            val selfId = PeerId("loom-self-1706")
            val api = FakeNwApi(radio, deviceId = "solo", serviceName = "solo", peerId = txt)
            val loom = NwLoom(api, serviceType = TYPE, selfId = selfId, random = Random(0), weaveTimeout = 1.seconds)

            val opened = mutableListOf<NwConnectionOpened>()
            val spy = launch(start = CoroutineStart.UNDISPATCHED) { api.connectionOpened.collect { opened += it } }

            val weave = launch(start = CoroutineStart.UNDISPATCHED) {
                runCatchingCancellable { loom.join(InMemoryTag(sessionName = "sess", peerKey = selfId.value)) }
            }
            testScheduler.advanceTimeBy(500)
            testScheduler.runCurrent()

            assertAll(
                { assertTrue(opened.isEmpty(), "TXT=${txt ?: "absent"}: serviceName backstop filters self, was $opened") },
                { assertTrue(loom.visiblePeers.value.isEmpty(), "TXT=${txt ?: "absent"}: self never rostered, was ${loom.visiblePeers.value}") },
            )

            spy.cancel()
            weave.cancel()
        }
    }

    /**
     * Bonjour renames colliding service names, so `serviceName` is **not stable on the wire**. Hardware
     * capture (2026-07-25, 2 iPhones) showed the same peer arriving as both `fireworks-quickplay` and
     * `fireworks-quickplay (2)` across sightings within one formation.
     *
     * Identity must therefore key on the TXT PeerId: a rename is the SAME peer, not a second one. This
     * is also why the self-filter's `serviceName == selfId` clause is an unreliable discriminator by
     * construction — it is a backstop for the TXT-absent case, not a primary key.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aBonjourRenameIsTheSamePeerNotASecondOne() = runTest(StandardTestDispatcher()) {
        val radio = FakeNwRadio()
        val selfId = PeerId("self-uuid-1706")
        val self = FakeNwApi(radio, deviceId = "self", serviceName = "self-svc", peerId = selfId.value)
        // The peer advertises under the shared session name; mDNS will disambiguate it on collision.
        val peer = FakeNwApi(radio, deviceId = "peer", serviceName = SHARED_LOBBY, peerId = "peer-uuid-1706")
        val loom = NwLoom(self, serviceType = TYPE, selfId = selfId, random = Random(0), weaveTimeout = 1.seconds)

        val weave = launch(start = CoroutineStart.UNDISPATCHED) {
            runCatchingCancellable { loom.weave(Rendezvous.New(Pattern(sessionName = SHARED_LOBBY))) }
        }
        peer.startListening(SHARED_LOBBY, TYPE)
        testScheduler.advanceTimeBy(200)
        testScheduler.runCurrent()

        val idsBeforeRename = loom.visiblePeers.value.map { it.id }.toSet()
        val namesBeforeRename = loom.visiblePeers.value.map { it.serviceName }.toSet()

        // mDNS disambiguates the collision — same peer, new serviceName.
        radio.renameService("peer", "$SHARED_LOBBY (2)")
        testScheduler.advanceTimeBy(200)
        testScheduler.runCurrent()

        // Assert on IDENTITY, not on the whole endpoint: the serviceName changing IS the rename, so
        // comparing endpoints wholesale would assert the rename never happened. The claim is narrower
        // and sharper — the peer keeps one stable id across it, so the roster gains no ghost.
        assertAll(
            {
                assertEquals(
                    idsBeforeRename,
                    loom.visiblePeers.value.map { it.id }.toSet(),
                    "a Bonjour rename is the same peer keyed on its TXT PeerId — the roster must not gain a ghost",
                )
            },
            {
                assertEquals(
                    1,
                    loom.visiblePeers.value.size,
                    "one advertising peer stays one roster entry across a rename, was ${loom.visiblePeers.value}",
                )
            },
            {
                // The rename really did land — otherwise the id-stability assertion above is vacuous.
                assertTrue(
                    loom.visiblePeers.value.map { it.serviceName }.toSet() != namesBeforeRename,
                    "the harness must actually deliver the renamed serviceName, still ${namesBeforeRename}",
                )
            },
        )

        weave.cancel()
    }

    /**
     * Network.framework can deliver the browse `add` BEFORE the TXT record resolves, supplying identity
     * on a later update. In that window the endpoint's id falls back to `serviceName` — which under
     * [Rendezvous.New] is the shared session name, indistinguishable from self.
     *
     * If the loom dials on the `add` rather than waiting for identity, the self-dial returns for exactly
     * as long as TXT takes to resolve. That is the leading suspect for the run-to-run variance seen on
     * hardware (identical builds gave 4/4 self-dials in one formation and 0/0 in the next).
     *
     * Fixed by #1709: [NwLoom] defers the dial of an unresolved endpoint advertised under its OWN
     * serviceName until identity arrives, bounded by [NwLoom.IDENTITY_GRACE]. Here the self endpoint's
     * TXT resolves inside that grace, so the deferral is dropped and the resolved sighting is filtered
     * on its PeerId — no dial ever happens.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun anEndpointWhoseTxtHasNotResolvedYetIsNotDialled() = runTest(StandardTestDispatcher()) {
        val radio = FakeNwRadio()
        val selfId = PeerId("self-uuid-1706")
        // txtResolvedOnAdvertise = false — the browse `add` lands before the TXT record resolves.
        val api = FakeNwApi(
            radio,
            deviceId = "solo",
            serviceName = "solo",
            peerId = selfId.value,
            txtResolvedOnAdvertise = false,
        )
        val loom = NwLoom(api, serviceType = TYPE, selfId = selfId, random = Random(0), weaveTimeout = 1.seconds)

        val opened = mutableListOf<NwConnectionOpened>()
        val spy = launch(start = CoroutineStart.UNDISPATCHED) { api.connectionOpened.collect { opened += it } }

        val weave = launch(start = CoroutineStart.UNDISPATCHED) {
            runCatchingCancellable { loom.weave(Rendezvous.New(Pattern(sessionName = SHARED_LOBBY))) }
        }
        testScheduler.advanceTimeBy(500)
        testScheduler.runCurrent()

        assertTrue(
            opened.isEmpty(),
            "an endpoint whose identity is not yet knowable must not be dialled — it may be self, was $opened",
        )

        // Once TXT resolves the endpoint is identifiable, and self is filtered on its PeerId.
        radio.resolveTxt("solo")
        testScheduler.advanceTimeBy(500)
        testScheduler.runCurrent()

        assertTrue(opened.isEmpty(), "after TXT resolves self is filtered on its PeerId, was $opened")

        spy.cancel()
        weave.cancel()
    }
}
