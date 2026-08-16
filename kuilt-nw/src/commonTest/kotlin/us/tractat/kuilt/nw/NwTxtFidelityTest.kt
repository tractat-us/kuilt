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
     * **Re-premised by ADR-005 / #2416.** This test used to be "the case that would have caught the
     * shipped bug": a browser that never requests TXT is handed no TXT, so identity fell back to
     * `serviceName`, and under [Rendezvous.New]'s *shared session name* that was the same string for self
     * and every peer — the pre-dial self-filter could not distinguish self, and the loom eventually
     * self-dialled once [NwLoom.IDENTITY_GRACE] expired.
     *
     * ADR-005 makes [NwLoom] advertise `selfId.value` under [Rendezvous.New] too, and that deletes the
     * state this test characterized: the `serviceName` backstop is now **the peer's own `PeerId`**, so an
     * unresolved sighting already names the right machine under the right key. Self is filtered on the
     * backstop id alone, with no TXT record and no grace — the ADR's "the pre-TXT window stops being
     * dangerous" consequence, and the reason the rejected composite `"<sessionName>-<selfId>"` form would
     * NOT have been equivalent.
     *
     * So the pair with [optedInBrowserResolvesTheTxtPeerIdAndFiltersSelf] no longer reads "the opt-in is
     * the difference between self-dialling and not" — on the `New` path the *name* is now that difference.
     * What the opt-in still buys is a real per-peer identity when the name is NOT the id: a peer mDNS
     * renamed to `"<id> (2)"`, or one still on a build that advertises a shared lobby name.
     *
     * The `identityResolved == false` precondition below is load-bearing: this test's claim is an
     * absence (`opened.isEmpty()`), which a fixture that discovered nothing at all would satisfy
     * vacuously. Asserting the unresolved sighting really arrived is what makes the absence evidence.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aBrowserThatNeverRequestsTxtStillFiltersSelfBecauseTheNameIsNowTheIdentity() = runTest(StandardTestDispatcher()) {
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
        // The rig receipt: prove the TXT-less sighting really was delivered AND really was unresolved.
        val seen = mutableListOf<NwEndpoint>()
        val browse = launch(start = CoroutineStart.UNDISPATCHED) { api.endpointFound.collect { seen += it } }

        val weave = launch(start = CoroutineStart.UNDISPATCHED) {
            runCatchingCancellable { loom.weave(Rendezvous.New(Pattern(sessionName = SHARED_LOBBY))) }
        }
        // Past the identity grace, so this is "never dialled", not "not dialled yet".
        testScheduler.advanceTimeBy(500 + NwLoom.IDENTITY_GRACE.inWholeMilliseconds)
        testScheduler.runCurrent()

        assertAll(
            {
                assertTrue(
                    seen.any { !it.identityResolved && it.id == selfId.value },
                    "rig: a browser with no TXT opt-in must still have seen our own advert on the " +
                        "serviceName backstop, which ADR-005 makes equal to selfId — saw $seen",
                )
            },
            {
                assertTrue(
                    opened.isEmpty(),
                    "ADR-005 makes the instance name the identity, so the backstop id IS selfId and the " +
                        "pre-dial self-filter fires with no TXT record at all — no self-dial, not even " +
                        "after the identity grace expires, was $opened",
                )
            },
            { assertTrue(loom.visiblePeers.value.isEmpty(), "self never rostered, was ${loom.visiblePeers.value}") },
        )

        spy.cancel()
        browse.cancel()
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

    /**
     * **Re-premised by ADR-005 / #2416: this is now the MIXED-VERSION interop case.**
     *
     * The fixture is unchanged — a peer advertising the bare shared lobby name and publishing no TXT
     * PeerId — but since ADR-005 that is no longer "a peer sharing our name". It models a peer still
     * running a build that advertises `pattern.sessionName`, met by this peer, which advertises
     * `selfId.value`. A real fleet upgrades one device at a time, so this pairing is worth keeping.
     *
     * What changes is the timing, and it is the whole point of the ADR. The #1709 deferral fires only on
     * a sighting under the name THIS loom advertises; the mixed-version peer's name is `"shared-lobby"`
     * and ours is now our `PeerId`, so the sighting can no longer be our own advertisement and is armed
     * **immediately** — no grace, no coin-flip dial. (That deferral is deliberately NOT retired here; it
     * still guards a loom whose own advert arrives unresolved. ADR-005 leaves its retirement to its own
     * PR so a revert-check can isolate either half.)
     *
     * The liveness claim #1709 exists for survives intact and is in fact stronger: a peer that publishes
     * no TXT is still reached, and now without waiting out [NwLoom.IDENTITY_GRACE] first.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aMixedVersionPeerStillOnTheSharedLobbyNameIsDialledWithNoDeferral() = runTest(StandardTestDispatcher()) {
        val radio = FakeNwRadio()
        val selfId = PeerId("self-uuid-1709")
        // peerId = null on BOTH devices: no TXT record is ever advertised, so no sighting can resolve.
        val self = FakeNwApi(radio, deviceId = "self", serviceName = "self-svc", peerId = null)
        val peer = FakeNwApi(radio, deviceId = "peer", serviceName = SHARED_LOBBY, peerId = null)
        val loom = NwLoom(self, serviceType = TYPE, selfId = selfId, random = Random(0), weaveTimeout = 30.seconds)

        val opened = mutableListOf<NwConnectionOpened>()
        val spy = launch(start = CoroutineStart.UNDISPATCHED) { self.connectionOpened.collect { opened += it } }

        val weave = launch(start = CoroutineStart.UNDISPATCHED) {
            runCatchingCancellable { loom.weave(Rendezvous.New(Pattern(sessionName = SHARED_LOBBY))) }
        }
        // The peer advertises the bare session name — what a pre-ADR-005 build does. Ours is selfId, so
        // the two no longer collide and this sighting cannot be mistaken for our own advertisement.
        peer.startListening(SHARED_LOBBY, TYPE)
        // Well INSIDE the identity grace: if the deferral still fired here, no dial would have happened.
        testScheduler.advanceTimeBy(200)
        testScheduler.runCurrent()

        assertAll(
            {
                assertTrue(
                    opened.isNotEmpty(),
                    "a mixed-version peer on the shared lobby name is armed immediately — its name is not " +
                        "ours since ADR-005, so the #1709 deferral does not apply and it is never starved",
                )
            },
            {
                // The rig: the dialled sighting really is the UNRESOLVED kind the deferral used to hold.
                // Without this the assertion above would pass just as well on a resolved endpoint, and the
                // claim "unresolved, yet not deferred" would be vacuous.
                assertEquals(
                    false,
                    opened.first().endpoint?.identityResolved,
                    "rig: the dialled endpoint must be on the serviceName backstop, was ${opened.first().endpoint}",
                )
            },
            {
                assertEquals(
                    setOf(NwEndpoint(id = SHARED_LOBBY, serviceName = SHARED_LOBBY)),
                    loom.visiblePeers.value,
                    "and it is rostered rather than held back, was ${loom.visiblePeers.value}",
                )
            },
        )

        spy.cancel()
        weave.cancel()
    }

    /**
     * [Rendezvous.Existing] must not regress: there the loom advertises `serviceName = selfId.value`, so a
     * remote peer's serviceName is never ours and the fallback id IS a genuine per-peer identity (that
     * peer's own advertised name). The #1709 deferral must therefore be inert on that path — an unresolved
     * peer is dialled immediately, with no grace.
     *
     * This is the case the deferral could most plausibly have broken: the endpoint here is unresolved,
     * exactly like the deferred one, and only the serviceName discriminator separates them.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun underExistingRendezvousAnUnresolvedPeerIsDialledWithNoDeferral() = runTest(StandardTestDispatcher()) {
        val radio = FakeNwRadio()
        val selfId = PeerId("self-uuid-1709-existing")
        val self = FakeNwApi(radio, deviceId = "self", serviceName = "self-svc", peerId = null)
        // A peer advertising its own name and no TXT — id falls back to "peer-uuid-1709", which under
        // Existing is a real per-peer identity, not a shared label.
        val peer = FakeNwApi(radio, deviceId = "peer", serviceName = "peer-uuid-1709", peerId = null)
        val loom = NwLoom(self, serviceType = TYPE, selfId = selfId, random = Random(0), weaveTimeout = 30.seconds)

        val opened = mutableListOf<NwConnectionOpened>()
        val spy = launch(start = CoroutineStart.UNDISPATCHED) { self.connectionOpened.collect { opened += it } }

        val weave = launch(start = CoroutineStart.UNDISPATCHED) {
            runCatchingCancellable { loom.join(InMemoryTag(sessionName = "sess", peerKey = selfId.value)) }
        }
        peer.startListening("peer-uuid-1709", TYPE)
        // Well inside the identity grace: if the deferral fired here the dial would not have happened yet.
        testScheduler.advanceTimeBy(200)
        testScheduler.runCurrent()

        assertAll(
            {
                assertTrue(
                    opened.isNotEmpty(),
                    "Rendezvous.Existing dials an unresolved peer immediately — its fallback id is a real identity",
                )
            },
            {
                assertEquals(
                    setOf(NwEndpoint(id = "peer-uuid-1709", serviceName = "peer-uuid-1709")),
                    loom.visiblePeers.value,
                    "and rosters it, was ${loom.visiblePeers.value}",
                )
            },
        )

        spy.cancel()
        weave.cancel()
    }
}
