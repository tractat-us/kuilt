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
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * [NwLoom.weave] behaviour when no peer is discoverable.
 *
 * Fix (#7): a dead weave must surface as an [NwUnreachableException] — a plain exception — NOT the
 * raw [kotlinx.coroutines.TimeoutCancellationException]. The old code rethrew the timeout, a
 * `CancellationException` subtype, so a caller wrapping `weave` in [runCatchingCancellable] saw it as
 * its OWN structured cancellation and rethrew — an unreachable fabric masqueraded as caller cancel.
 */
class NwLoomTest {

    private companion object {
        const val TYPE = "_kuilt._tcp"
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun weaveWithNoPeerFailsWithNwUnreachableAndDoesNotCancelCaller() = runTest(StandardTestDispatcher()) {
        // A lone device on the radio: it advertises and browses but finds no endpoint, so the seam
        // never resolves a peer and the injected (short) weave timeout fires.
        val radio = FakeNwRadio()
        val loom = NwLoom(
            FakeNwApi(radio, deviceId = "solo", serviceName = "solo"),
            serviceType = TYPE,
            random = Random(0),
            weaveTimeout = 1.seconds,
        )

        // Model a real caller: wrap weave in runCatchingCancellable in a child coroutine, so we can
        // prove (a) it fails with NwUnreachableException and (b) the caller is NOT cancelled — the
        // exact regression the fix targets.
        var result: Result<Seam>? = null
        val caller = launch(start = CoroutineStart.UNDISPATCHED) {
            result = runCatchingCancellable { loom.weave(Rendezvous.New(Pattern("solo"))) }
        }
        // Bounded virtual-time advance past the weave timeout (never advanceUntilIdle()).
        testScheduler.advanceTimeBy(1_001)
        testScheduler.runCurrent()

        assertAll(
            { assertTrue(caller.isCompleted && !caller.isCancelled, "caller completed normally, not cancelled") },
            { assertTrue(result?.isFailure == true, "weave failed") },
            { assertIs<NwUnreachableException>(result?.exceptionOrNull(), "failure is NwUnreachableException, not a CancellationException") },
        )
    }

    /**
     * Self-discovery must not become a self-dial nor a self-ghost in the lobby (#1493).
     *
     * A device weaving [Rendezvous.Existing] advertises its own [NwLoom.selfId] as the Bonjour service
     * name and browses the same type, so real Bonjour/mDNS — and [FakeNwRadio] (#1485) — delivers it its
     * OWN endpoint. [NwLoom] must (a) NOT dial that self-endpoint (no self-connection is even attempted)
     * and (b) NOT surface it in [NwLoom.visiblePeers] — the "wait for a friend" roster must stay empty
     * until an actual peer appears. Identity is by advertised service name: for Existing the loom
     * advertised `serviceName == selfId.value`, and selfIds are distinct UUIDs, so only self carries it.
     *
     * A lone device reaches no OTHER peer, so `weave` times out (harmless here — we only assert the
     * pre-timeout self-handling); we cancel it after the observation window.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun existingRendezvousNeitherDialsNorRostersItsOwnEndpoint() = runTest(StandardTestDispatcher()) {
        val radio = FakeNwRadio()
        val selfId = PeerId("self-uuid-1493")
        val api = FakeNwApi(radio, deviceId = "solo", serviceName = "solo")
        val loom = NwLoom(
            api,
            serviceType = TYPE,
            selfId = selfId,
            random = Random(0),
            weaveTimeout = 1.seconds,
        )

        // Spy the device's connectionOpened flow: a self-dial would open a connection and surface here.
        // Subscribe (UNDISPATCHED) BEFORE weaving so no opened event is missed.
        val opened = mutableListOf<NwConnectionOpened>()
        val spy = launch(start = CoroutineStart.UNDISPATCHED) {
            api.connectionOpened.collect { opened += it }
        }

        val weave = launch(start = CoroutineStart.UNDISPATCHED) {
            runCatchingCancellable { loom.join(InMemoryTag(sessionName = "sess", peerKey = "self-uuid-1493")) }
        }
        // Bounded advance: enough for discovery (and, on a broken impl, a self-dial) to occur; never advanceUntilIdle().
        testScheduler.advanceTimeBy(500)
        testScheduler.runCurrent()

        assertAll(
            { assertTrue(loom.visiblePeers.value.isEmpty(), "self-endpoint never retained in visiblePeers, was ${loom.visiblePeers.value}") },
            { assertTrue(opened.isEmpty(), "no self-dial: no connection opened, was $opened") },
        )

        spy.cancel()
        weave.cancel()
    }
}
