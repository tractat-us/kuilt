package us.tractat.kuilt.nw

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * #2449: a listener that fails **asynchronously** must be re-listened, with bounded back-off.
 *
 * The field shape this pins: two phones mid-game, one screen-locks, and both peers log a listener
 * failure ~99 s apart. Neither ever listens again and the session never re-forms. The reason is
 * structural rather than a missed branch — [NwApi.startListening] is `suspend fun … : Unit`, the OS
 * decides the bind on a GCD callback *after* it returns, and [NwLoom.weave]'s `runCatchingCancellable`
 * can only catch a synchronous throw. **There was no retry because there was no signal.**
 *
 * So these tests drive [NwApi.listenerState] — the signal — and assert the loom acts on it: it
 * re-listens after a back-off, it stops after a bounded number of consecutive failures rather than
 * advertising into a dead path forever, and a device-path change re-arms a campaign that had given up.
 *
 * What they do NOT prove, deliberately: that `RealNwApi` *emits* the failure. A fake-driven signal
 * proves the consumer's reaction, never the real transport's emission — that half is confirmed against
 * the hardware reproducer, per `docs/debugging-process.md`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NwListenerRetryTest {

    private companion object {
        const val TYPE = "_kuilt._tcp"

        /**
         * `kDNSServiceErr_DefunctConnection` (`dns_sd.h`) — the DNS-domain code Apple's own
         * `com.apple.network:listener` log reported on BOTH field devices, 7 ms and 32 ms before kuilt's
         * line, as `reporting state failed (DNS Error: DefunctConnection)`.
         *
         * Used here rather than an `EADDRINUSE` so the fixture drives the failure that actually happens.
         * The distinction is not cosmetic: this is a Bonjour registration going defunct behind an
         * interface-set change, with no bind or address conflict in it at all.
         */
        const val DNS_DEFUNCT_CONNECTION = -65569

        /** Comfortably past a whole give-up campaign (0.5 + 1 + 2 + 4 + 8 s), so the plateau is the loom's, not the clock's. */
        val PAST_A_WHOLE_CAMPAIGN = 60.seconds

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
     * A woven pair over one [FakeNwRadio]. Both looms are symmetric joiners (roles are symmetric on this
     * fabric); [weave] returns only once each has resolved the other, so the returned seams are live and
     * each loom's listen supervisor is running on a seam scope that is not about to be cancelled.
     */
    private class WovenPair(val apiA: FakeNwApi, val apiB: FakeNwApi, val seamA: Seam, val seamB: Seam)

    private suspend fun TestScope.wovenPair(): WovenPair {
        val radio = FakeNwRadio()
        val apiA = FakeNwApi(radio, deviceId = "dev-0", serviceName = "peer-A", peerId = "peer-A")
        val apiB = FakeNwApi(radio, deviceId = "dev-1", serviceName = "peer-B", peerId = "peer-B")
        val loomA = NwLoom(apiA, serviceType = TYPE, selfId = PeerId("peer-A"), random = Random(0), weaveTimeout = 10.seconds)
        val loomB = NwLoom(apiB, serviceType = TYPE, selfId = PeerId("peer-B"), random = Random(1), weaveTimeout = 10.seconds)

        var seamA: Seam? = null
        var seamB: Seam? = null
        launch(start = CoroutineStart.UNDISPATCHED) { seamA = loomA.join(InMemoryTag(sessionName = "lobby", peerKey = "peer-A")) }
        launch(start = CoroutineStart.UNDISPATCHED) { seamB = loomB.join(InMemoryTag(sessionName = "lobby", peerKey = "peer-B")) }
        assertTrue(
            pumpUntil { seamA?.peers?.value?.size == 2 && seamB?.peers?.value?.size == 2 },
            "both looms wove to 2 peers: A=${seamA?.peers?.value} B=${seamB?.peers?.value}",
        )
        return WovenPair(apiA, apiB, seamA!!, seamB!!)
    }

    /**
     * The headline: a listener that was up and then died — the screen-lock case — is re-listened once the
     * back-off elapses, and comes back [NwListenerState.Ready].
     *
     * The pre-back-off assertion is load-bearing in the other direction: it pins that the retry actually
     * *waits*, so a green here cannot be produced by a loom that re-listens in a tight loop.
     */
    @Test
    fun anAsynchronousListenerFailureIsRelistenedAfterTheBackoff() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val pair = wovenPair()
            val listensAtWeave = pair.apiA.startListeningCalls

            // The phone locks: an interface-set change races the Bonjour registration and the OS reports the
            // listener failed — dns(2)/DefunctConnection, per Apple's own listener log for this session.
            pair.apiA.emitListenerFailed(domain = NW_ERROR_DOMAIN_DNS, code = DNS_DEFUNCT_CONNECTION)
            testScheduler.runCurrent()
            val listensBeforeBackoff = pair.apiA.startListeningCalls

            testScheduler.advanceTimeBy(NwLoom.INITIAL_LISTEN_BACKOFF.inWholeMilliseconds + 1)
            testScheduler.runCurrent()

            assertAll(
                { assertEquals(1, listensAtWeave, "weave listens exactly once") },
                { assertEquals(listensAtWeave, listensBeforeBackoff, "no re-listen before the back-off elapses — the retry waits") },
                { assertEquals(listensAtWeave + 1, pair.apiA.startListeningCalls, "the failed listener was re-listened after the back-off") },
                { assertEquals(NwListenerState.Ready, pair.apiA.listenerState.value, "the re-listen brought the listener back up") },
            )
        }

    /**
     * Consecutive failures are BOUNDED. With every re-listen refused ([FakeNwApi.listenFailure]) the
     * campaign spends its attempts and then stops, rather than advertising into a path that cannot bind
     * for the life of the seam.
     *
     * The second window is what makes this a bound rather than a rate: the count must be identical after
     * a *further* whole campaign's worth of virtual time.
     */
    @Test
    fun consecutiveListenerFailuresStopAfterTheAttemptBound() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val pair = wovenPair()
            val listensAtWeave = pair.apiA.startListeningCalls

            // Every subsequent bind is refused too, so nothing resets the consecutive-failure counter.
            pair.apiA.listenFailure = NwListenerState.Failed(NW_ERROR_DOMAIN_DNS, DNS_DEFUNCT_CONNECTION)
            pair.apiA.emitListenerFailed(domain = NW_ERROR_DOMAIN_DNS, code = DNS_DEFUNCT_CONNECTION)
            testScheduler.runCurrent()

            testScheduler.advanceTimeBy(PAST_A_WHOLE_CAMPAIGN.inWholeMilliseconds)
            testScheduler.runCurrent()
            val afterOneCampaign = pair.apiA.startListeningCalls

            testScheduler.advanceTimeBy(PAST_A_WHOLE_CAMPAIGN.inWholeMilliseconds)
            testScheduler.runCurrent()

            assertAll(
                {
                    assertEquals(
                        listensAtWeave + NwLoom.MAX_LISTEN_ATTEMPTS - 1,
                        afterOneCampaign,
                        "the campaign re-listens on each failure up to the bound, then gives up",
                    )
                },
                {
                    assertEquals(
                        afterOneCampaign,
                        pair.apiA.startListeningCalls,
                        "a campaign that gave up stays given up — no further listen without a new trigger",
                    )
                },
                {
                    assertTrue(
                        pair.apiA.listenerState.value is NwListenerState.Failed,
                        "the listener is left visibly Failed, not silently Unknown — was ${pair.apiA.listenerState.value}",
                    )
                },
            )
        }

    /**
     * A device-path change re-arms a campaign that had given up.
     *
     * This is the free trigger the field capture proves was on the table and unwatched: `pathState` fired
     * dozens of times through the dead window, including a real cellular↔Wi-Fi flap 70 s before the host's
     * bind failed. A path change is exactly the event that can turn "this path can never bind" into "this
     * one can", so it is worth more than another timer.
     */
    @Test
    fun aDevicePathChangeReArmsAListenCampaignThatGaveUp() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val pair = wovenPair()
            pair.apiA.listenFailure = NwListenerState.Failed(NW_ERROR_DOMAIN_DNS, DNS_DEFUNCT_CONNECTION)
            pair.apiA.emitListenerFailed(domain = NW_ERROR_DOMAIN_DNS, code = DNS_DEFUNCT_CONNECTION)
            testScheduler.advanceTimeBy(PAST_A_WHOLE_CAMPAIGN.inWholeMilliseconds)
            testScheduler.runCurrent()
            val gaveUpAt = pair.apiA.startListeningCalls

            // The radio swaps underneath us — a satisfied Wi-Fi path where there was none.
            pair.apiA.emitPathState(
                NwPathState(
                    status = NwPathStatus.Satisfied,
                    interfaces = setOf(NwInterfaceType.WifiLan),
                    isExpensive = false,
                    isConstrained = false,
                    unsatisfiedReason = null,
                ),
            )
            testScheduler.runCurrent()

            assertEquals(
                gaveUpAt + 1,
                pair.apiA.startListeningCalls,
                "the path change re-armed the campaign and it listened again immediately",
            )
        }

    /**
     * A re-listen that throws SYNCHRONOUSLY still advances the campaign.
     *
     * This is the failure mode a supervisor built only on [NwApi.listenerState] walks into: a throwing
     * `startListening` publishes no verdict, so awaiting one parks forever — the campaign would wedge at
     * its first retry and silently restore the pre-#2449 behaviour, while every other test here stayed
     * green. The bound is the same one the asynchronous path uses; what is proven is that it is *reached*.
     */
    @Test
    fun aSynchronouslyThrowingRelistenStillAdvancesTheCampaign() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val pair = wovenPair()
            val listensAtWeave = pair.apiA.startListeningCalls

            pair.apiA.listenThrows = true
            pair.apiA.emitListenerFailed(domain = NW_ERROR_DOMAIN_DNS, code = DNS_DEFUNCT_CONNECTION)
            testScheduler.advanceTimeBy(PAST_A_WHOLE_CAMPAIGN.inWholeMilliseconds)
            testScheduler.runCurrent()

            assertEquals(
                listensAtWeave + NwLoom.MAX_LISTEN_ATTEMPTS - 1,
                pair.apiA.startListeningCalls,
                "a throwing re-listen counts as a failure and the campaign runs to its bound — it does not park",
            )
        }

    /**
     * [NwApi.listenerState] has a NON-UPDATING DEFAULT, so an existing binding that has not wired a
     * listener signal keeps compiling and keeps its pre-#2449 behaviour (no signal ⇒ no retry).
     *
     * This is a compile-time guard as much as a runtime one. [MinimalNwApi] implements only the members
     * that were abstract before this change; making `listenerState` abstract — the shape that has twice
     * landed a broken `main` here, where one PR adds an interface member while a sibling PR adds an
     * implementor — stops this file compiling instead of breaking `BridgeNwApi` on merge.
     */
    @Test
    fun listenerStateDefaultsToUnknownForABindingThatWiresNoListenerSignal() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            assertEquals(NwListenerState.Unknown, MinimalNwApi().listenerState.value)
        }

    /** An [NwApi] implementing ONLY the members that carry no default — see the test above. */
    private class MinimalNwApi : NwApi {
        override fun availability(): FabricAvailability = FabricAvailability.Available
        override suspend fun startListening(serviceName: String, serviceType: String) = Unit
        override suspend fun stopListening() = Unit
        override suspend fun startBrowsing(serviceType: String) = Unit
        override suspend fun stopBrowsing() = Unit
        override suspend fun connect(endpoint: NwEndpoint) = Unit
        override suspend fun disconnect(connectionId: NwConnectionId) = Unit
        override suspend fun send(connectionId: NwConnectionId, bytes: ByteArray) = Unit
        override val endpointFound: Flow<NwEndpoint> = kotlinx.coroutines.flow.emptyFlow()
        override val connectionOpened: Flow<NwConnectionOpened> = kotlinx.coroutines.flow.emptyFlow()
        override val bytesReceived: Flow<NwBytesReceived> = kotlinx.coroutines.flow.emptyFlow()
        override val connectionClosed: Flow<NwConnectionClosed> = kotlinx.coroutines.flow.emptyFlow()

        // Referenced only so an unused-import sweep cannot quietly drop the StateFlow import this file
        // needs for the assertion above; the default getter is what the test actually reads.
        val listenerStateType: StateFlow<NwListenerState> get() = listenerState
    }
}
