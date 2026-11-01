@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.nearby

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A host keeps accepting for as long as it advertises, and no exit from that leaves a `join` waiting
 * on it (#2811).
 *
 * Two rigs, for two different questions:
 *
 *  - [FakeNearbyRadio] plays both ends on one loom, so its handshake cannot complete unless the
 *    host's machine is alive and accepts. That is what "a joiner was accepted" needs, and it is
 *    **blind to the `join` hang**: with the host's machine dead the fake's second accept never
 *    arrives, so the joiner fails on its own clock and never reaches the wait after its handshake.
 *  - [ScriptedNearbyApi] plays a **different device**, which completes a dial with no help from this
 *    loom's host. Only that reaches the wait a `join` used to do on the loom's own host.
 *
 * The bound in [bounded] is the failure signal for a hang: it reds with a message naming the wait,
 * rather than running into the [TEST_WEDGE_BACKSTOP] that would report a wedge and nothing else.
 */
class NearbyHostAcceptanceTest {

    /** What the remote device does when this loom dials it. */
    private enum class FarDevice {
        /** Connects, and answers this peer's identity with its own. */
        COMPLETES,

        /** Reports the connection as refused once this side accepts. */
        REFUSES,

        /** Takes the connection and then says nothing. */
        STALLS,
    }

    /**
     * A [NearbyApi] with no second loom behind it: the test emits the events a remote joiner would
     * cause, and [far] scripts a remote host. It refuses nothing and records everything, so each test
     * below can read what the loom actually did to the radio.
     */
    private class ScriptedNearbyApi(
        private val far: FarDevice? = null,
        private val failStartAdvertising: Throwable? = null,
        private val failInitiatedStream: Throwable? = null,
    ) : NearbyApi {
        val found = MutableSharedFlow<EndpointFound>(extraBufferCapacity = 16)
        val connInit = MutableSharedFlow<ConnectionInitiated>(extraBufferCapacity = 16)
        val connResult = MutableSharedFlow<ConnectionResult>(extraBufferCapacity = 16)
        val payloads = MutableSharedFlow<PayloadReceived>(extraBufferCapacity = 16)
        val disconnects = MutableSharedFlow<EndpointDisconnected>(extraBufferCapacity = 16)

        val accepted = mutableListOf<String>()
        val disconnected = mutableListOf<String>()
        val acceptFailures = mutableMapOf<String, Throwable>()
        var requestFailure: suspend () -> Unit = {}

        var advertisingStarts = 0
        var advertisingStops = 0
        var discoveryStops = 0

        /** [payloads]' subscribers at the moment of the dial — the rig for "the seam was live". */
        var payloadSubscribersDuringDial = -1

        override val endpointFound: Flow<EndpointFound> = found
        override val connectionInitiated: Flow<ConnectionInitiated> =
            if (failInitiatedStream == null) connInit else flow { throw failInitiatedStream }
        override val connectionResult: Flow<ConnectionResult> = connResult
        override val payloadReceived: Flow<PayloadReceived> = payloads
        override val endpointDisconnected: Flow<EndpointDisconnected> = disconnects

        override fun availability(): FabricAvailability = FabricAvailability.Available

        override suspend fun startAdvertising(displayName: String, serviceId: String) {
            advertisingStarts++
            failStartAdvertising?.let { throw it }
        }

        override suspend fun stopAdvertising() {
            advertisingStops++
        }

        override suspend fun startDiscovery(serviceId: String) {
            if (far != null) found.emit(EndpointFound(FAR_ENDPOINT, "far"))
        }

        override suspend fun stopDiscovery() {
            discoveryStops++
        }

        override suspend fun requestConnection(displayName: String, endpointId: String) {
            payloadSubscribersDuringDial = payloads.subscriptionCount.value
            requestFailure()
            connInit.emit(ConnectionInitiated(endpointId, "far"))
        }

        override suspend fun acceptConnection(endpointId: String) {
            accepted += endpointId
            acceptFailures[endpointId]?.let { throw it }
            if (endpointId != FAR_ENDPOINT) return
            when (far) {
                FarDevice.COMPLETES -> connResult.emit(ConnectionResult(endpointId, success = true))
                FarDevice.REFUSES ->
                    connResult.emit(ConnectionResult(endpointId, success = false, reason = "refused"))
                FarDevice.STALLS, null -> Unit
            }
        }

        override suspend fun disconnect(endpointId: String) {
            disconnected += endpointId
        }

        override suspend fun sendBytesPayload(endpointId: String, bytes: ByteArray) {
            if (endpointId == FAR_ENDPOINT && far == FarDevice.COMPLETES) {
                payloads.emit(PayloadReceived(endpointId, FAR_PEER.value.encodeToByteArray()))
            }
        }
    }

    /** Drive one remote joiner through a whole handshake with the host, a dispatch at a time. */
    private suspend fun TestScope.admitJoiner(api: ScriptedNearbyApi, endpoint: String, id: PeerId) {
        api.connInit.emit(ConnectionInitiated(endpoint, "joiner"))
        runCurrent()
        api.connResult.emit(ConnectionResult(endpoint, success = true))
        runCurrent()
        api.payloads.emit(PayloadReceived(endpoint, id.value.encodeToByteArray()))
        runCurrent()
    }

    /** Fails on the bound rather than hanging, naming what never returned. */
    private suspend fun <T> bounded(what: String, block: suspend () -> T): T =
        withTimeoutOrNull(HANG_BOUND) { block() }
            ?: fail("$what did not return within $HANG_BOUND of virtual time — it is hanging")

    private suspend fun NearbyLoom.joinFar(): Seam = join(NearbyTag("device", FAR_ENDPOINT))

    // ── a host keeps accepting ────────────────────────────────────────────────────────────────────

    /**
     * The headline. The handshake clock used to start when the host opened, so a joiner arriving a
     * moment after [TIMEOUT] met a host that had silently stopped listening while still advertising
     * and still `Weaving`.
     */
    @Test
    fun aJoinerArrivingLongAfterTheHandshakeTimeoutIsAccepted() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val loom = NearbyLoom(FakeNearbyApi(FakeNearbyRadio()), handshakeTimeout = TIMEOUT)
            val host = loom.host(Pattern("device"))

            advanceTimeBy(TIMEOUT * 10)
            runCurrent()
            val arrivedAt = currentTime
            val joiner = bounded("join") { loom.join(NearbyTag("device", HOST_ENDPOINT)) }
            runCurrent()

            assertAll(
                { assertEquals(TIMEOUT.inWholeMilliseconds * 10, arrivedAt, "rig: the joiner really came late") },
                { assertTrue(joiner.selfId in host.peers.value, "the late joiner is on the host's roster") },
                { assertIs<SeamState.Woven>(host.state.value, "and the host has woven") },
            )
        }

    @Test
    fun anIdleHostStaysWeavingAndAdvertising() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val api = ScriptedNearbyApi()
            val host = NearbyLoom(api, handshakeTimeout = TIMEOUT).host(Pattern("device"))

            advanceTimeBy(TIMEOUT * 10)
            runCurrent()

            assertAll(
                { assertIs<SeamState.Weaving>(host.state.value, "nobody came, so nothing has changed") },
                { assertEquals(1, api.advertisingStarts, "rig: the host really did start advertising") },
                { assertEquals(0, api.advertisingStops, "and it has not stopped: nothing ended the wait") },
            )
        }

    // ── a failed attempt costs that attempt, and nothing else ─────────────────────────────────────

    /**
     * Every way one attempt can fail, followed by the same proof: a *different* joiner gets in. The
     * second endpoint is what makes this more than "the host did not crash": it is accepted only if
     * something is still listening.
     */
    private suspend fun TestScope.assertHostSurvives(
        api: ScriptedNearbyApi,
        host: Seam,
        failedEndpoint: String,
    ) {
        val acceptedAfterFailure = api.accepted.toList()
        val disconnectedAfterFailure = api.disconnected.toList()
        val stateAfterFailure = host.state.value
        val peersAfterFailure = host.peers.value
        val stopsAfterFailure = api.advertisingStops

        val second = PeerId("second-joiner")
        admitJoiner(api, SECOND_ENDPOINT, second)

        assertAll(
            { assertTrue(failedEndpoint in acceptedAfterFailure, "rig: the failed attempt really reached accept") },
            { assertEquals(listOf(failedEndpoint), disconnectedAfterFailure, "a failed attempt releases its link") },
            { assertIs<SeamState.Weaving>(stateAfterFailure, "a failed attempt does not end the session") },
            { assertEquals(0, stopsAfterFailure, "a failed attempt does not touch the advertiser") },
            { assertEquals(setOf(host.selfId), peersAfterFailure, "and it never became a peer") },
            { assertTrue(SECOND_ENDPOINT in api.accepted, "the host is still accepting after the failure") },
            { assertTrue(second in host.peers.value, "and the next joiner gets in") },
            { assertIs<SeamState.Woven>(host.state.value, "and the host weaves to it") },
        )
    }

    @Test
    fun aRefusedConnectionLeavesTheHostAccepting() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val api = ScriptedNearbyApi()
            val host = NearbyLoom(api, handshakeTimeout = TIMEOUT).host(Pattern("device"))

            api.connInit.emit(ConnectionInitiated(FIRST_ENDPOINT, "joiner"))
            runCurrent()
            api.connResult.emit(ConnectionResult(FIRST_ENDPOINT, success = false, reason = "rejected"))
            runCurrent()

            assertHostSurvives(api, host, FIRST_ENDPOINT)
        }

    @Test
    fun aRefusedIdentityLeavesTheHostAccepting() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val api = ScriptedNearbyApi()
            val host = NearbyLoom(api, handshakeTimeout = TIMEOUT).host(Pattern("device"))

            api.connInit.emit(ConnectionInitiated(FIRST_ENDPOINT, "joiner"))
            runCurrent()
            api.connResult.emit(ConnectionResult(FIRST_ENDPOINT, success = true))
            runCurrent()
            // Not valid UTF-8, so the identity is refused — on a link that really is connected.
            api.payloads.emit(PayloadReceived(FIRST_ENDPOINT, byteArrayOf(0xC3.toByte())))
            runCurrent()

            assertHostSurvives(api, host, FIRST_ENDPOINT)
        }

    @Test
    fun anAcceptThatThrowsLeavesTheHostAccepting() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val api = ScriptedNearbyApi()
            api.acceptFailures[FIRST_ENDPOINT] = IllegalArgumentException("radio refused the accept")
            val host = NearbyLoom(api, handshakeTimeout = TIMEOUT).host(Pattern("device"))

            api.connInit.emit(ConnectionInitiated(FIRST_ENDPOINT, "joiner"))
            runCurrent()

            assertHostSurvives(api, host, FIRST_ENDPOINT)
        }

    /** The same again, with a callee-minted cancellation: it fails the attempt, not the host. */
    @Test
    fun anAcceptThatMintsACancellationLeavesTheHostAccepting() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val api = ScriptedNearbyApi()
            api.acceptFailures[FIRST_ENDPOINT] = mintedTimeout()
            val host = NearbyLoom(api, handshakeTimeout = TIMEOUT).host(Pattern("device"))

            api.connInit.emit(ConnectionInitiated(FIRST_ENDPOINT, "joiner"))
            runCurrent()

            assertHostSurvives(api, host, FIRST_ENDPOINT)
        }

    // ── the bound did not vanish ──────────────────────────────────────────────────────────────────

    /**
     * Moving the clock must not have removed it. The host idles for longer than the bound first, so
     * the two possible clock starts disagree: from the open, this attempt would already be late.
     */
    @Test
    fun aHandshakeThatStallsTimesOutOnItsOwnClockAndTheHostAcceptsAgain() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val api = ScriptedNearbyApi()
            val host = NearbyLoom(api, handshakeTimeout = TIMEOUT).host(Pattern("device"))

            advanceTimeBy(TIMEOUT * 10)
            runCurrent()
            api.connInit.emit(ConnectionInitiated(FIRST_ENDPOINT, "joiner"))
            runCurrent()
            assertEquals(listOf(FIRST_ENDPOINT), api.accepted, "rig: the host took this attempt on")

            advanceTimeBy(TIMEOUT - 1.milliseconds)
            runCurrent()
            assertEquals(emptyList(), api.disconnected, "one millisecond short of the bound, it is still pending")

            advanceTimeBy(1.milliseconds)
            runCurrent()

            assertHostSurvives(api, host, FIRST_ENDPOINT)
        }

    // ── a join never waits on a host that owes it nothing ─────────────────────────────────────────

    /**
     * Reachable on one real device: host a room, leave it, join somebody else's. Closing the host
     * cancelled the coroutine that was to complete the deferred, so the `join` completed its handshake
     * with the other device and then waited forever.
     */
    @Test
    fun aJoinAfterTheHostClosedDoesNotHang() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val api = ScriptedNearbyApi(far = FarDevice.COMPLETES)
            val loom = NearbyLoom(api, handshakeTimeout = TIMEOUT)
            val host = loom.host(Pattern("mine"))
            host.close()
            assertIs<SeamState.Torn>(host.state.value, "rig: the host really is closed")

            val joiner = bounded("join after the host closed") { loom.joinFar() }

            assertTrue(FAR_PEER in joiner.peers.value, "the join reached the other device")
        }

    /** The same hang, by the route the issue names: a host the old clock had already stopped. */
    @Test
    fun aJoinAfterTheHostOutlivedTheTimeoutDoesNotHang() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val api = ScriptedNearbyApi(far = FarDevice.COMPLETES)
            val loom = NearbyLoom(api, handshakeTimeout = TIMEOUT)
            loom.host(Pattern("mine"))
            advanceTimeBy(TIMEOUT * 10)
            runCurrent()

            val joiner = bounded("join after the host's wait passed the timeout") { loom.joinFar() }

            assertTrue(FAR_PEER in joiner.peers.value, "the join reached the other device")
        }

    /**
     * A device hosting one room and dialling another: both connections report through the same
     * callback, so the host's first-come claim used to take this loom's OWN dial as an incoming
     * joiner and weave the host to a device that never asked to join it.
     */
    @Test
    fun aHostDoesNotClaimAConnectionThisLoomIsDialling() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val api = ScriptedNearbyApi(far = FarDevice.COMPLETES)
            val loom = NearbyLoom(api, handshakeTimeout = TIMEOUT)
            val host = loom.host(Pattern("mine"))

            val joiner = bounded("join while hosting") { loom.joinFar() }
            runCurrent()

            assertAll(
                { assertTrue(FAR_PEER in joiner.peers.value, "rig: the dial really did complete") },
                { assertEquals(listOf(FAR_ENDPOINT), api.accepted, "only the dial accepted it, once") },
                { assertEquals(setOf(host.selfId), host.peers.value, "the host did not weave to it") },
                { assertIs<SeamState.Weaving>(host.state.value, "and it is still waiting for its own joiner") },
            )

            // …and the dial did not spend the host: its own joiner still gets in.
            val own = PeerId("own-joiner")
            admitJoiner(api, FIRST_ENDPOINT, own)
            assertTrue(own in host.peers.value, "the host is still accepting")
        }

    /**
     * What the wait after the handshake is still FOR: when this loom's host is mid-handshake, a `join`
     * returns only once that handshake has settled, which is what lets one loom play both ends and
     * find both rosters populated. It lasts no longer than that handshake's own bound.
     */
    @Test
    fun aJoinWaitsForAnInFlightHostHandshakeAndNoLonger() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val api = ScriptedNearbyApi(far = FarDevice.COMPLETES)
            val loom = NearbyLoom(api, handshakeTimeout = TIMEOUT)
            loom.host(Pattern("mine"))
            api.connInit.emit(ConnectionInitiated(FIRST_ENDPOINT, "joiner"))
            runCurrent()

            val join = backgroundScope.async { loom.joinFar() }
            runCurrent()
            assertAll(
                { assertTrue(FAR_ENDPOINT in api.accepted, "rig: the dial itself has finished") },
                { assertTrue(join.isActive, "a join waits for the host's in-flight handshake") },
            )

            advanceTimeBy(TIMEOUT)
            runCurrent()

            assertTrue(join.isCompleted, "and stops waiting when that handshake reaches its bound")
            assertTrue(FAR_PEER in join.await().peers.value)
        }

    // ── a joiner cleans up after itself ───────────────────────────────────────────────────────────

    /**
     * The seam lives on a parentless scope, so a `join` that throws without closing it leaves its
     * collectors subscribed for the life of the process. Nothing hands that seam back, so the only
     * thing to read is the radio's own subscriber count, and the count taken during the dial is the
     * proof that there was something to clean up.
     */
    @Test
    fun aJoinerHandshakeFailureClosesItsSeam() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val api = ScriptedNearbyApi(far = FarDevice.REFUSES)
            val loom = NearbyLoom(api, handshakeTimeout = TIMEOUT)

            assertFailsWith<ConnectionFailedException> { loom.joinFar() }
            runCurrent()

            assertAll(
                { assertTrue(api.payloadSubscribersDuringDial > 0, "rig: the seam was listening during the dial") },
                { assertEquals(0, api.payloads.subscriptionCount.value, "nothing is listening now") },
                { assertEquals(0, api.disconnects.subscriptionCount.value, "…on any stream") },
                { assertEquals(listOf(FAR_ENDPOINT), api.disconnected, "the half-made link is released") },
                { assertEquals(1, api.discoveryStops, "and discovery is not left running") },
            )
        }

    @Test
    fun aJoinerHandshakeThatStallsFailsAndClosesItsSeam() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val api = ScriptedNearbyApi(far = FarDevice.STALLS)
            val loom = NearbyLoom(api, handshakeTimeout = TIMEOUT)

            val startedAt = currentTime
            assertFailsWith<ConnectionFailedException> { loom.joinFar() }
            val elapsed = currentTime - startedAt
            runCurrent()

            assertAll(
                { assertEquals(TIMEOUT.inWholeMilliseconds, elapsed, "it failed at the bound, not before") },
                { assertTrue(FAR_ENDPOINT in api.accepted, "rig: the handshake really started") },
                { assertEquals(0, api.payloads.subscriptionCount.value, "and nothing is left listening") },
            )
        }

    /** The caller walking away is an exit too, and it arrives through the same unreturned seam. */
    @Test
    fun cancellingAJoinClosesItsSeam() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val api = ScriptedNearbyApi() // nothing is ever found, so the join waits in discovery
            val loom = NearbyLoom(api, handshakeTimeout = TIMEOUT)

            val join = backgroundScope.launch { loom.joinFar() }
            runCurrent()
            assertTrue(api.payloads.subscriptionCount.value > 0, "rig: the seam exists and is listening")

            join.cancelAndJoin()
            runCurrent()

            assertAll(
                { assertEquals(0, api.payloads.subscriptionCount.value, "nothing is left listening") },
                { assertEquals(1, api.discoveryStops, "and discovery is not left running") },
            )
        }

    /**
     * [us.tractat.kuilt.core.Loom.weave] may let a cancellation out only for the caller's own cancel:
     * a callee-minted one cancels the caller rather than failing it, with no handler and no trace.
     */
    @Test
    fun aMintedCancellationFromTheRadioFailsAJoinInsteadOfCancellingIt() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val api = ScriptedNearbyApi(far = FarDevice.STALLS)
            api.requestFailure = { withTimeout(1.milliseconds) { delay(1.seconds) } }
            val loom = NearbyLoom(api, handshakeTimeout = TIMEOUT)

            assertFailsWith<ConnectionFailedException> { loom.joinFar() }
            runCurrent()

            assertEquals(0, api.payloads.subscriptionCount.value, "and the seam is closed behind it")
        }

    // ── the advertiser's lifetime ─────────────────────────────────────────────────────────────────

    /** Accepting and advertising end together, or a closed host stays visible to every joiner. */
    @Test
    fun closingAHostStopsItsAdvertisingBeforeCloseReturns() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val api = ScriptedNearbyApi()
            val host = NearbyLoom(api, handshakeTimeout = TIMEOUT).host(Pattern("device"))
            assertEquals(0, api.advertisingStops, "rig: it was advertising up to here")

            host.close()

            // Deliberately no runCurrent(): `close(); host()` on one radio must not have the old
            // host's stop land after the new host's start, where it would silence the new host.
            assertEquals(1, api.advertisingStops)
        }

    /**
     * This fabric admits one joiner per session, so once it has one there is nothing left to accept —
     * and a host still advertising then would be inviting joiners it will never answer.
     */
    @Test
    fun aLinkedHostStopsAdvertisingExactlyOnce() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val api = ScriptedNearbyApi()
            val host = NearbyLoom(api, handshakeTimeout = TIMEOUT).host(Pattern("device"))

            admitJoiner(api, FIRST_ENDPOINT, PeerId("joiner"))
            assertEquals(1, api.advertisingStops, "the session has its joiner")

            host.close()
            runCurrent()
            assertEquals(1, api.advertisingStops, "and a later close does not stop some newer host's advertiser")
        }

    @Test
    fun aHostThatCannotStartAdvertisingFailsAndLeavesNothingBehind() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val api = ScriptedNearbyApi(failStartAdvertising = IllegalArgumentException("no radio"))
            val loom = NearbyLoom(api, handshakeTimeout = TIMEOUT)

            assertFailsWith<IllegalArgumentException> { loom.host(Pattern("device")) }
            runCurrent()

            assertAll(
                { assertEquals(1, api.advertisingStarts, "rig: the start really was attempted") },
                { assertEquals(0, api.payloads.subscriptionCount.value, "nothing is left listening") },
                { assertEquals(0, api.connInit.subscriptionCount.value, "…including the acceptance") },
            )
        }

    /**
     * The one failure that is not one attempt's: the event stream itself is gone, so no joiner can
     * ever be seen again. Retrying would spin, and carrying on `Weaving` is the very lie this fixes,
     * so the host tears, where a consumer can see it.
     */
    @Test
    fun aHostWhoseEventStreamFailsTearsInsteadOfWeavingForever() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val api = ScriptedNearbyApi(failInitiatedStream = IllegalArgumentException("runtime went away"))
            val host = NearbyLoom(api, handshakeTimeout = TIMEOUT).host(Pattern("device"))
            runCurrent()

            assertAll(
                { assertIs<SeamState.Torn>(host.state.value) },
                { assertEquals(1, api.advertisingStops, "and it stopped advertising what it cannot answer") },
            )
        }

    /** A real `TimeoutCancellationException`, off this test's virtual clock, with nobody cancelled. */
    private suspend fun mintedTimeout(): Throwable =
        try {
            withTimeout(1.milliseconds) { delay(1.seconds) }
            fail("rig: the bound never fired")
        } catch (minted: TimeoutCancellationException) {
            minted
        }

    private companion object {
        val TIMEOUT = 3.seconds

        /** Well past any wait the fix permits; expiring it is what a hang looks like here. */
        val HANG_BOUND = 120.seconds

        /** The endpoint id [FakeNearbyRadio] hands the discoverer for the advertiser. */
        const val HOST_ENDPOINT = "ep-advertiser"

        const val FAR_ENDPOINT = "ep-far"
        val FAR_PEER = PeerId("far-host")
        const val FIRST_ENDPOINT = "ep-first"
        const val SECOND_ENDPOINT = "ep-second"
    }
}
