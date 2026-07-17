package us.tractat.kuilt.nw

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
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

        /** Bounded pump: run current-virtual-time tasks until [cond] or the cap. Never hangs. */
        fun TestScope.pumpUntil(maxPumps: Int = 500, cond: () -> Boolean): Boolean {
            repeat(maxPumps) {
                if (cond()) return true
                testScheduler.runCurrent()
            }
            return cond()
        }
    }

    /**
     * #1513 Part B: after a peer drops, [NwLoom]'s redial loop dials the still-discoverable endpoint again
     * and the seam re-weaves — end-to-end proof that redial reconnects. Two looms (both joiners; roles are
     * symmetric) converge over one radio; a path-loss grace expiry evicts the peer AND tears the link (so
     * BOTH seams re-form to Weaving), then each loom's redial dials the peer again and both return to Woven.
     * Asserts a *second* connect was issued (the redial) and the seam recovered to Woven.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun redialReconnectsAfterAPeerDropReturningTheSeamToWoven() = runTest(StandardTestDispatcher()) {
        val grace = 1.seconds
        val radio = FakeNwRadio()
        val apiA = FakeNwApi(radio, deviceId = "dev-0", serviceName = "peer-A")
        val apiB = FakeNwApi(radio, deviceId = "dev-1", serviceName = "peer-B")
        val loomA = NwLoom(apiA, serviceType = TYPE, selfId = PeerId("peer-A"), random = Random(0), weaveTimeout = 10.seconds, wovenPathGrace = grace)
        val loomB = NwLoom(apiB, serviceType = TYPE, selfId = PeerId("peer-B"), random = Random(1), weaveTimeout = 10.seconds, wovenPathGrace = grace)

        var seamA: Seam? = null
        var seamB: Seam? = null
        // Weave concurrently so the pair can discover + dial each other while each awaits its first peer.
        launch(start = CoroutineStart.UNDISPATCHED) { seamA = loomA.join(InMemoryTag(sessionName = "lobby", peerKey = "peer-A")) }
        launch(start = CoroutineStart.UNDISPATCHED) { seamB = loomB.join(InMemoryTag(sessionName = "lobby", peerKey = "peer-B")) }
        assertTrue(
            pumpUntil { seamA?.peers?.value?.size == 2 && seamB?.peers?.value?.size == 2 },
            "both looms wove to 2 peers: A=${seamA?.peers?.value} B=${seamB?.peers?.value}",
        )
        val a = seamA!!
        val connectsBefore = apiA.connectCalls

        // Drop A's live link via a path-loss grace expiry. A's link to B is one of A's two handles
        // (conn-dev-0-0 / conn-dev-0-1 — the other was the dedup loser, already gone); flag both so the
        // live one arms. onGraceExpired evicts B (A re-forms Weaving) AND disconnects the link, so B
        // observes the close and re-forms too — both then redial.
        apiA.emitConnectionViability(NwConnectionId("conn-dev-0-0"), viable = false)
        apiA.emitConnectionViability(NwConnectionId("conn-dev-0-1"), viable = false)
        testScheduler.runCurrent()
        // Advance past the grace so the timer fires: the peer is evicted (both seams re-form to Weaving)
        // and each loom's redial loop dials the still-discoverable endpoint again, re-weaving to Woven.
        testScheduler.advanceTimeBy(grace.inWholeMilliseconds + 1)
        assertTrue(pumpUntil { a.state.value is SeamState.Woven && a.peers.value.size == 2 }, "A redialed and re-wove to 2 peers (state=${a.state.value}, peers=${a.peers.value})")

        assertAll(
            { assertTrue(a.state.value is SeamState.Woven, "A recovered to Woven via redial — was ${a.state.value}") },
            { assertEquals(setOf(PeerId("peer-A"), PeerId("peer-B")), a.peers.value, "A regained B") },
            { assertTrue(apiA.connectCalls > connectsBefore, "A issued a redial connect (was $connectsBefore, now ${apiA.connectCalls})") },
        )
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
