@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // testScheduler.runCurrent to pump the pathState collector

package us.tractat.kuilt.nw

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.TransportRole
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #1554: the BSD interface-name heuristic that disambiguates infrastructure Wi-Fi ([NwInterfaceType.WifiLan])
 * from peer-to-peer AWDL/low-latency-Wi-Fi ([NwInterfaceType.WifiDirect]), and the folding of that live
 * interface type into `NwSeam.capability`'s ROLES. The classifier is a pure commonMain function so the
 * heuristic is unit-testable off-device; the seam wiring is driven through [FakeNwApi.emitPathState].
 */
class NwInterfaceRolesTest {

    private companion object {
        val BASE_ROLES = setOf(TransportRole.Discovery, TransportRole.Data)
    }

    // ── the pure name→type heuristic (off-device) ───────────────────────────────

    @Test
    fun awdlAndLlwNamesClassifyAsPeerToPeerWifiDirect() = assertAll(
        { assertEquals(NwInterfaceType.WifiDirect, classifyWifiInterface("awdl0"), "awdl0 is AWDL peer-to-peer") },
        { assertEquals(NwInterfaceType.WifiDirect, classifyWifiInterface("awdl1"), "awdl1 (any awdl*) is peer-to-peer") },
        { assertEquals(NwInterfaceType.WifiDirect, classifyWifiInterface("llw0"), "llw0 is low-latency Wi-Fi peer-to-peer") },
    )

    @Test
    fun infrastructureNamesClassifyAsWifiLan() = assertAll(
        { assertEquals(NwInterfaceType.WifiLan, classifyWifiInterface("en0"), "en0 is infrastructure Wi-Fi") },
        { assertEquals(NwInterfaceType.WifiLan, classifyWifiInterface("en1"), "en1 is infrastructure Wi-Fi") },
    )

    @Test
    fun unreadableNameFallsBackToWifiLanConservatively() =
        assertEquals(
            NwInterfaceType.WifiLan,
            classifyWifiInterface(null),
            "an unreadable interface name falls back to the conservative infrastructure-LAN default",
        )

    // ── roles fold into the live seam capability ────────────────────────────────

    private fun TestScope.seamScope(): CoroutineScope =
        CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))

    private fun TestScope.newSeam(): Pair<FakeNwApi, NwSeam> {
        val radio = FakeNwRadio()
        val api = FakeNwApi(radio, deviceId = "dev-0", serviceName = "svc-0")
        val seam = NwSeam(PeerId("peer-0"), api, seamScope(), Random(0))
        testScheduler.runCurrent()
        return api to seam
    }

    private fun satisfied(iface: NwInterfaceType) = NwPathState(
        status = NwPathStatus.Satisfied,
        interfaces = setOf(iface),
        isExpensive = false,
        isConstrained = false,
        unsatisfiedReason = null,
    )

    @Test
    fun peerToPeerPathAddsWifiDirectRole() = runTest(StandardTestDispatcher()) {
        val (api, seam) = newSeam()
        api.emitPathState(satisfied(NwInterfaceType.WifiDirect))
        testScheduler.runCurrent()
        assertEquals(
            BASE_ROLES + TransportRole.WifiDirect,
            seam.capability.value.roles,
            "an AWDL peer-to-peer path adds the WifiDirect role atop Discovery+Data",
        )
    }

    @Test
    fun infrastructurePathAddsWifiLanRole() = runTest(StandardTestDispatcher()) {
        val (api, seam) = newSeam()
        api.emitPathState(satisfied(NwInterfaceType.WifiLan))
        testScheduler.runCurrent()
        assertEquals(
            BASE_ROLES + TransportRole.WifiLan,
            seam.capability.value.roles,
            "an infrastructure Wi-Fi path adds the WifiLan role atop Discovery+Data",
        )
    }

    @Test
    fun rolesReactAsInterfaceChangesAndRevertOffWifi() = runTest(StandardTestDispatcher()) {
        val (api, seam) = newSeam()

        api.emitPathState(satisfied(NwInterfaceType.WifiLan))
        testScheduler.runCurrent()
        assertEquals(BASE_ROLES + TransportRole.WifiLan, seam.capability.value.roles, "starts on infrastructure Wi-Fi")

        api.emitPathState(satisfied(NwInterfaceType.WifiDirect))
        testScheduler.runCurrent()
        assertEquals(
            BASE_ROLES + TransportRole.WifiDirect,
            seam.capability.value.roles,
            "switching to AWDL flips the medium role to WifiDirect",
        )

        api.emitPathState(satisfied(NwInterfaceType.Cellular))
        testScheduler.runCurrent()
        assertEquals(
            BASE_ROLES,
            seam.capability.value.roles,
            "a non-Wi-Fi path carries no medium role — roles revert to the Discovery+Data base",
        )
    }
}
