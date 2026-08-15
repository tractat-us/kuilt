@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // testScheduler.runCurrent, to pump the ICE collector

package us.tractat.kuilt.webrtc

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.TransportCapability
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.webrtc.internal.WebRTCPeerLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * `WebRTCPeerLink.capability` is LIVE (#1544): its availability starts at an honest `Unknown`
 * (nothing has been observed yet, #1712) and is thereafter driven by the injected
 * [PairedFacadeFactory]'s ICE flow — standing in for `BrowserRtcFacade`'s
 * `oniceconnectionstatechange` handler. Moving the fake's ICE state moves the seam's availability.
 *
 * ## What this proves and what it cannot
 * Every reading here is driven **after** the seam exists, so these tests distinguish a genuinely
 * reactive capability from one snapshotted at construction — a strictly weaker property that a test
 * reading only an initial value would also pass. What no test here can prove is that a real
 * `RTCPeerConnection` ever *emits*: a fake-injected signal pins the consumer's reaction, never the
 * platform's emission. That half is provable only in a browser with real ICE.
 *
 * ## One surface at a time
 * These tests read `capability` and nothing else — never `incoming`, `peers` or `state`. A test
 * that collected several of a seam's flows at once could pass because some *other* surface pumped
 * the scheduler, hiding a capability that only moves as a side effect of unrelated traffic.
 */
class WebRTCPeerLinkCapabilityTest {

    /**
     * A bare seam over one side of the paired fake — enough to own the capability fold, which needs
     * no handshake, no peer and no traffic. Returns the factory so the test can drive ICE.
     *
     * The dispatcher is the test's own [StandardTestDispatcher], so every fold is pumped explicitly
     * by [TestScope.runCurrent] rather than racing a real one.
     */
    private fun TestScope.newSeam(): Pair<PairedFacadeFactory, WebRTCPeerLink> {
        val (factory, _) = PairedFacadeFactory.pair()
        val seam = WebRTCPeerLink(
            selfId = PeerId("self"),
            remoteId = PeerId("remote"),
            facade = factory.create(IceConfig.NoServers, hostInitiated = true),
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        testScheduler.runCurrent() // let the UNDISPATCHED ICE collector subscribe
        return factory to seam
    }

    private fun TestScope.runCurrent() = testScheduler.runCurrent()

    /**
     * The rig itself, asserted rather than assumed. Both fixtures used by the tracking tests below
     * must fold OFF the `Unknown` floor — so those tests read real verdicts rather than two
     * flavours of "don't know" — and they must fold to DIFFERENT verdicts, so observing the second
     * after the first is genuine evidence of movement rather than a value that never changed.
     *
     * Without this, an edit that flattened [IceConnectionState.Failed] onto `Unknown` (or onto
     * `Available`) would leave every assertion below tautological and nothing would go red.
     */
    @Test
    fun theRigIsNonVacuous() {
        val connected = IceConnectionState.Connected.toAvailability()
        val failed = IceConnectionState.Failed.toAvailability()
        assertAll(
            {
                assertTrue(
                    connected !is FabricAvailability.Unknown,
                    "the connected fixture must be a definite verdict, was $connected",
                )
            },
            {
                assertTrue(
                    failed !is FabricAvailability.Unknown,
                    "the failed fixture must be a definite verdict, was $failed",
                )
            },
            { assertNotEquals(connected, failed, "the two fixtures must fold to DIFFERENT availabilities") },
        )
    }

    /**
     * A seam whose facade has observed no ICE state reports the fabric's roles with an `Unknown`
     * availability — never a verdict it has not observed (#1712).
     *
     * This is also what makes [WebRTCConformanceTest]'s `emitIceConnectionState` call load-bearing
     * rather than decorative: absent it, a seam sits here forever and the suite's
     * `reportsLiveCapability` branch would await a verdict that never arrives.
     */
    @Test
    fun anUnobservedIceStateYieldsTheStaticRolesWithUnknownAvailability() = runTest {
        val (_, seam) = newSeam()
        val capability = seam.capability.value
        assertAll(
            { assertEquals(WEBRTC_ROLES, capability.roles, "a WebRTC seam always plays the WebRtc and Data roles") },
            {
                assertIs<FabricAvailability.Unknown>(
                    capability.availability,
                    "with no ICE reading, availability must be an honest Unknown",
                )
            },
        )
    }

    /**
     * The load-bearing property: the capability **tracks** the signal. Both readings are driven
     * AFTER construction, so an implementation that snapshotted the ICE state once would be stuck
     * at the `Unknown` floor and fail on the very first assertion.
     */
    @Test
    fun capabilityTracksIceStateDrivenAfterConstruction() = runTest {
        val (factory, seam) = newSeam()

        factory.emitIceConnectionState(IceConnectionState.Connected)
        runCurrent()
        val connected: TransportCapability = seam.capability.value

        factory.emitIceConnectionState(IceConnectionState.Failed)
        runCurrent()
        val failed: TransportCapability = seam.capability.value

        assertAll(
            { assertEquals(FabricAvailability.Available, connected.availability, "a connected ICE agent ⇒ Available") },
            {
                assertIs<FabricAvailability.Unavailable>(
                    failed.availability,
                    "a failed ICE agent ⇒ a definite Unavailable, not a shrug",
                )
            },
            { assertNotEquals(connected, failed, "the capability MOVED between the two readings") },
        )
    }

    /**
     * A waiter on the flow is actually woken — the property the conformance suite depends on.
     *
     * `wovenSeamCapabilityIsHonest`'s `reportsLiveCapability` branch blocks on
     * `capability.first { it.availability !is Unknown }`, so the seam owes a genuine *emission*,
     * not merely a `value` that reads differently on the next poll. The await is started BEFORE the
     * ICE state moves, so a capability that only recomputed on read would leave it suspended.
     */
    @Test
    fun aWaiterOnTheCapabilityFlowIsWokenByAnIceTransition() = runTest {
        val (factory, seam) = newSeam()

        val awaited = async { seam.capability.first { it.availability !is FabricAvailability.Unknown } }
        runCurrent()
        assertTrue(awaited.isActive, "precondition: the waiter must still be suspended on the Unknown floor")

        factory.emitIceConnectionState(IceConnectionState.Connected)
        runCurrent()

        assertTrue(awaited.isCompleted, "the ICE transition must WAKE the waiter, not just change what a poll reads")
        assertEquals(FabricAvailability.Available, awaited.await().availability)
    }

    /**
     * The two mid-handshake ICE states fold to `Unknown`, not to `Unavailable`.
     *
     * Pins that the fold is a genuine three-way read rather than a connected/not-connected boolean,
     * which [capabilityTracksIceStateDrivenAfterConstruction] cannot distinguish. Reporting a
     * definite "unavailable" during the ordinary seconds while candidate pairs are still being
     * tested would be a fabricated verdict — #1712 in the direction that is easiest to get
     * backwards, since the connection genuinely is not usable yet.
     *
     * **Each `Unknown` is reached FROM a definite verdict, deliberately.** Asserted straight off
     * the seam's initial state these assertions would be vacuous: `Unknown` is also what a seam
     * that ignores ICE entirely reports, so they would pass against no implementation at all —
     * as they did when this file was first written, before the driver existed. Arriving from
     * `Available` makes each one a statement about the *transition*.
     */
    @Test
    fun midHandshakeIceStatesAreNotYetAVerdict() = runTest {
        val (factory, seam) = newSeam()

        factory.emitIceConnectionState(IceConnectionState.Connected)
        runCurrent()
        val beforeNew = seam.capability.value.availability
        factory.emitIceConnectionState(IceConnectionState.New)
        runCurrent()
        val fresh = seam.capability.value.availability

        factory.emitIceConnectionState(IceConnectionState.Connected)
        runCurrent()
        val beforeChecking = seam.capability.value.availability
        factory.emitIceConnectionState(IceConnectionState.Checking)
        runCurrent()
        val checking = seam.capability.value.availability

        assertAll(
            { assertEquals(FabricAvailability.Available, beforeNew, "precondition: a definite verdict was reached") },
            { assertIs<FabricAvailability.Unknown>(fresh, "ICE has not started checking — nothing is known yet") },
            { assertEquals(FabricAvailability.Available, beforeChecking, "precondition: and reached again") },
            { assertIs<FabricAvailability.Unknown>(checking, "ICE is mid-check — still nothing is known") },
        )
    }

    /**
     * Dropping back to `null` — the state a facade with no observer at all reports — returns the
     * seam to the floor. Pins that the `null` branch is live rather than reachable only at
     * construction, so a binding whose observer stops reporting cannot leave a stale verdict
     * standing.
     */
    @Test
    fun losingTheObservationReturnsTheSeamToTheUnobservedFloor() = runTest {
        val (factory, seam) = newSeam()

        factory.emitIceConnectionState(IceConnectionState.Connected)
        runCurrent()
        val observed = seam.capability.value.availability

        factory.emitIceConnectionState(null)
        runCurrent()

        assertAll(
            { assertEquals(FabricAvailability.Available, observed, "precondition: a verdict was reached first") },
            {
                assertIs<FabricAvailability.Unknown>(
                    seam.capability.value.availability,
                    "losing the observation must return the seam to Unknown, not freeze the last verdict",
                )
            },
        )
    }

    /**
     * The ROLES are deliberately static while the availability moves.
     *
     * `kuilt-nearby` narrows its medium roles when a radio switches off, because Bluetooth and
     * Wi-Fi really are separate media it can lose. WebRTC has no such split: a seam whose ICE agent
     * has failed is still a WebRTC data channel — `capability().roles` answers "what kind of
     * transport is this", and only `availability` answers "is it working". This test is the guard
     * on that decision, so a future edit that copied nearby's live-roles fold has to argue with it.
     */
    @Test
    fun theRolesDoNotNarrowWhenIceFails() = runTest {
        val (factory, seam) = newSeam()

        factory.emitIceConnectionState(IceConnectionState.Connected)
        runCurrent()
        val connectedRoles = seam.capability.value.roles

        factory.emitIceConnectionState(IceConnectionState.Failed)
        runCurrent()

        assertAll(
            { assertEquals(WEBRTC_ROLES, connectedRoles, "a connected seam plays the WebRTC roles") },
            { assertEquals(WEBRTC_ROLES, seam.capability.value.roles, "and a failed one still plays them") },
        )
    }
}
