@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // testScheduler.runCurrent to pump the radioState collector

package us.tractat.kuilt.nearby

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.TransportCapability
import us.tractat.kuilt.core.TransportRole
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * `NearbySeam.capability` is LIVE (#1543): its ROLES start at the fabric's base
 * [NearbyLoom.NEARBY_BASE_ROLES] and its AVAILABILITY at an honest `Unknown` (nothing has been
 * observed yet, #1712), and both are then driven by the injected [FakeNearbyApi] radio flow —
 * standing in for `GmsNearbyApi`'s Bluetooth/Wi-Fi state broadcasts. Toggling the fake's radios
 * moves the seam's availability AND narrows or widens its medium roles.
 *
 * ## What this proves and what it cannot
 * These tests drive the signal **after** the seam exists, so they distinguish a genuinely reactive
 * capability from one snapshotted at construction — a strictly weaker property that a test reading
 * only an initial value would also pass. What no test here can prove is that the real Android
 * runtime ever *emits*: a fake-injected signal pins the consumer's reaction, never the platform's
 * emission. That half is provable only on a device.
 */
class NearbySeamCapabilityTest {

    private companion object {
        /**
         * The rig's two contrasting radio readings. They are deliberately different in **both**
         * dimensions the fold produces — availability and roles — and [theRigIsNonVacuous] asserts
         * that of itself, so an edit that flattens them onto one verdict fails loudly here instead
         * of quietly making every assertion below tautological.
         */
        val BOTH_RADIOS_ON = NearbyRadioState(
            bluetooth = NearbyRadioStatus.On,
            wifi = NearbyRadioStatus.On,
        )
        val BOTH_RADIOS_OFF = NearbyRadioState(
            bluetooth = NearbyRadioStatus.Off,
            wifi = NearbyRadioStatus.Off,
        )
    }

    private fun TestScope.seamScope(): CoroutineScope =
        CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))

    /** A bare, never-connected seam — enough to own the capability fold, which needs no peers. */
    private fun TestScope.newSeam(): Pair<FakeNearbyApi, NearbySeam> {
        val api = FakeNearbyApi(FakeNearbyRadio())
        val seam = NearbySeam(
            selfId = PeerId("self"),
            endpointPeers = mutableMapOf(),
            endpointPeersMutex = Mutex(),
            api = api,
            sharedPeers = MutableStateFlow(emptySet()),
            scope = seamScope(),
            msgIdCounter = MsgIdCounter(),
        )
        testScheduler.runCurrent() // let the UNDISPATCHED radio collector subscribe
        return api to seam
    }

    /**
     * The rig itself, asserted rather than assumed: both fixtures must fold OFF the `Unknown` floor
     * (so the tracking tests below are reading real verdicts, not two flavours of "don't know"), and
     * they must disagree on availability AND on roles (so observing the second value after the first
     * is genuine evidence of movement).
     */
    @Test
    fun theRigIsNonVacuous() {
        val on = BOTH_RADIOS_ON.toAvailability()
        val off = BOTH_RADIOS_OFF.toAvailability()
        assertAll(
            { assertTrue(on !is FabricAvailability.Unknown, "the on-fixture must be a definite verdict, was $on") },
            { assertTrue(off !is FabricAvailability.Unknown, "the off-fixture must be a definite verdict, was $off") },
            { assertNotEquals(on, off, "the two fixtures must fold to DIFFERENT availabilities") },
            {
                assertNotEquals(
                    BOTH_RADIOS_ON.radioRoles(),
                    BOTH_RADIOS_OFF.radioRoles(),
                    "the two fixtures must fold to DIFFERENT medium roles",
                )
            },
        )
    }

    /**
     * A seam whose observer has published nothing reports the fabric's base role with an `Unknown`
     * availability — never a verdict it has not observed (#1712).
     *
     * This is also what makes [NearbyConformanceTest]'s `emitRadioState` call load-bearing rather
     * than decorative: absent it, a seam sits here forever and the suite's `reportsLiveCapability`
     * branch would await a verdict that never arrives.
     */
    @Test
    fun anUnreportedRadioStateYieldsBaseRolesWithUnknownAvailability() = runTest(StandardTestDispatcher()) {
        val (_, seam) = newSeam()
        val cap = seam.capability.value
        assertAll(
            { assertEquals(NearbyLoom.NEARBY_BASE_ROLES, cap.roles, "no radio is on, so no medium role is claimed") },
            {
                // NOT the api's `availability()`. That answers "is there a Nearby runtime here" — a
                // platform question — and republishing it as a live verdict is the #1712 defect.
                assertIs<FabricAvailability.Unknown>(
                    cap.availability,
                    "with nothing observed, availability must be an honest Unknown",
                )
            },
        )
    }

    /**
     * The load-bearing property: the capability **tracks** the signal. Both readings are driven
     * AFTER construction, so an implementation that snapshotted the radio state once would be stuck
     * at the `Unknown` floor and fail on the very first assertion.
     */
    @Test
    fun capabilityTracksRadioStateDrivenAfterConstruction() = runTest(StandardTestDispatcher()) {
        val (api, seam) = newSeam()

        api.emitRadioState(BOTH_RADIOS_ON)
        testScheduler.runCurrent()
        val poweredUp = seam.capability.value

        api.emitRadioState(BOTH_RADIOS_OFF)
        testScheduler.runCurrent()
        val poweredDown = seam.capability.value

        assertAll(
            { assertEquals(FabricAvailability.Available, poweredUp.availability, "both radios on ⇒ Available") },
            {
                assertEquals(
                    NearbyLoom.NEARBY_BASE_ROLES + setOf(TransportRole.Bluetooth, TransportRole.WifiDirect),
                    poweredUp.roles,
                    "both radios on ⇒ both medium roles atop the base role",
                )
            },
            {
                assertIs<FabricAvailability.Unavailable>(
                    poweredDown.availability,
                    "both radios off ⇒ a definite Unavailable, not a shrug",
                )
            },
            { assertEquals(NearbyLoom.NEARBY_BASE_ROLES, poweredDown.roles, "radios off ⇒ no medium role survives") },
            { assertNotEquals(poweredUp, poweredDown, "the capability MOVED between the two readings") },
        )
    }

    /**
     * One radio off is not the fabric off: Nearby bootstraps over Bluetooth and only upgrades onto
     * Wi-Fi, so either radio alone keeps it [FabricAvailability.Available] while the ROLES narrow to
     * the medium that is actually up. Pins that the fold is a genuine per-radio read rather than an
     * all-or-nothing boolean, which the two-radio test above cannot distinguish.
     */
    @Test
    fun oneRadioOnKeepsTheFabricAvailableWithOnlyThatMediumRole() = runTest(StandardTestDispatcher()) {
        val (api, seam) = newSeam()

        api.emitRadioState(NearbyRadioState(bluetooth = NearbyRadioStatus.On, wifi = NearbyRadioStatus.Off))
        testScheduler.runCurrent()
        val bluetoothOnly = seam.capability.value

        api.emitRadioState(NearbyRadioState(bluetooth = NearbyRadioStatus.Off, wifi = NearbyRadioStatus.On))
        testScheduler.runCurrent()
        val wifiOnly = seam.capability.value

        assertAll(
            { assertEquals(FabricAvailability.Available, bluetoothOnly.availability, "Bluetooth alone suffices") },
            {
                assertEquals(
                    NearbyLoom.NEARBY_BASE_ROLES + TransportRole.Bluetooth,
                    bluetoothOnly.roles,
                    "Wi-Fi is off, so WifiDirect is not claimed",
                )
            },
            { assertEquals(FabricAvailability.Available, wifiOnly.availability, "Wi-Fi alone suffices") },
            {
                assertEquals(
                    NearbyLoom.NEARBY_BASE_ROLES + TransportRole.WifiDirect,
                    wifiOnly.roles,
                    "Bluetooth is off, so Bluetooth is not claimed",
                )
            },
        )
    }

    /**
     * An unreadable radio (the runtime permission guarding `BluetoothAdapter.getState` was not
     * granted) must NOT collapse to a confident "unavailable" — a radio we could not read may well
     * be on. The seam returns to the honest `Unknown` floor instead, which is the #1712 rule applied
     * in the direction it is easiest to get backwards.
     */
    @Test
    fun anUnreadableRadioReturnsTheSeamToUnknownRatherThanUnavailable() = runTest(StandardTestDispatcher()) {
        val (api, seam) = newSeam()

        api.emitRadioState(BOTH_RADIOS_ON)
        testScheduler.runCurrent()
        assertEquals(
            FabricAvailability.Available,
            seam.capability.value.availability,
            "precondition: the seam reached a definite verdict first",
        )

        api.emitRadioState(NearbyRadioState(bluetooth = NearbyRadioStatus.Unknown, wifi = NearbyRadioStatus.Off))
        testScheduler.runCurrent()

        assertIs<FabricAvailability.Unknown>(
            seam.capability.value.availability,
            "an unreadable radio must not be reported as a definite Unavailable",
        )
    }

    /**
     * Dropping back to `null` — the state a binding with no observer at all reports — returns the
     * seam to the floor. Pins that the `null` branch is live rather than reachable only at
     * construction, so a binding whose observer stops reporting cannot leave a stale verdict
     * standing.
     */
    @Test
    fun losingTheObserverReturnsTheSeamToTheUnobservedFloor() = runTest(StandardTestDispatcher()) {
        val (api, seam) = newSeam()

        api.emitRadioState(BOTH_RADIOS_ON)
        testScheduler.runCurrent()
        val observed: TransportCapability = seam.capability.value

        api.emitRadioState(null)
        testScheduler.runCurrent()

        assertAll(
            { assertEquals(FabricAvailability.Available, observed.availability, "precondition: a verdict was reached") },
            {
                assertIs<FabricAvailability.Unknown>(
                    seam.capability.value.availability,
                    "losing the observer must return the seam to Unknown, not freeze the last verdict",
                )
            },
            { assertEquals(NearbyLoom.NEARBY_BASE_ROLES, seam.capability.value.roles, "and to the base roles") },
        )
    }
}
