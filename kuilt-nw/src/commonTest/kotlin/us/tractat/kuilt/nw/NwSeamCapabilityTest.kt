@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // testScheduler.runCurrent to pump the pathState collector

package us.tractat.kuilt.nw

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.TransportRole
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `NwSeam.capability` is LIVE (#1541): its ROLES come from the loom's static role set while its
 * AVAILABILITY starts at an honest `Unknown` (nothing has been observed yet, #1712), and both are then driven
 * by the injected [FakeNwApi] path-monitor flow (standing in for `RealNwApi`'s `nw_path_monitor`). Flipping
 * the fake's path state — down, Local-Network-denied, recovered — moves the seam's availability. The base
 * roles are always [TransportRole.Discovery] + [TransportRole.Data]; the live Wi-Fi medium role split
 * (WifiLan vs WifiDirect) is covered separately by [NwInterfaceRolesTest] (#1554). Runs under a
 * [StandardTestDispatcher].
 */
class NwSeamCapabilityTest {

    private companion object {
        val NW_ROLES = setOf(TransportRole.Discovery, TransportRole.Data)
    }

    private fun TestScope.seamScope(): CoroutineScope =
        CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))

    private fun TestScope.newSeam(): Pair<FakeNwApi, NwSeam> {
        val radio = FakeNwRadio()
        val api = FakeNwApi(radio, deviceId = "dev-0", serviceName = "svc-0")
        val seam = NwSeam(PeerId("peer-0"), api, seamScope(), Random(0))
        testScheduler.runCurrent() // let the UNDISPATCHED collectors subscribe
        return api to seam
    }

    /**
     * A seam whose monitor has published nothing reports the fabric's roles with an `Unknown` availability.
     *
     * **What this pins, precisely:** [FakeNwApi.pathState] starts `null` and the seam's path job is
     * `UNDISPATCHED`, so that `null` is collected inline during construction — meaning the value observed
     * here comes from [pathStateLoop]'s `null` branch, not from the field seed. The two are deliberately
     * identical (both `unobservedCapability`) and the seed is belt-and-braces: no test can observe it,
     * because there is no point after construction at which the loop has not already run. Named for the
     * branch it actually covers so a reader does not mistake it for seed coverage.
     */
    @Test
    fun anUnreportedPathYieldsRolesWithUnknownAvailability() =
        runTest(StandardTestDispatcher()) {
            val (_, seam) = newSeam()
            val cap = seam.capability.value
            assertAll(
                { assertEquals(NW_ROLES, cap.roles, "the fabric's Discovery+Data roles still come through") },
                {
                    // NOT the loom's `api.availability()`. That answers "is this fabric usable on this
                    // runtime" — a platform question — and republishing it as a live path verdict is the
                    // #1712 defect: a seam whose monitor has not reported (or whose binding wired none, e.g.
                    // the JVM dylib bridge) would assert a confident Available with nothing behind it.
                    // Since #1712 the seam takes ROLES only, so there is no availability to fall back on.
                    assertEquals(
                        FabricAvailability.Unknown("no path monitor has reported on this binding"),
                        cap.availability,
                        "with nothing reported, availability must be an honest Unknown",
                    )
                },
            )
        }

    @Test
    fun localNetworkPermissionDenialMakesTheSeamUnavailable() = runTest(StandardTestDispatcher()) {
        val (api, seam) = newSeam()
        api.emitPathState(
            NwPathState(
                status = NwPathStatus.Unsatisfied,
                interfaces = emptySet(),
                isExpensive = false,
                isConstrained = false,
                unsatisfiedReason = NwUnsatisfiedReason.LocalNetworkDenied,
            ),
        )
        testScheduler.runCurrent()
        val cap = seam.capability.value
        assertAll(
            { assertEquals(NW_ROLES, cap.roles, "roles are unchanged by a path/permission transition") },
            {
                assertEquals(
                    FabricAvailability.Unavailable("Local Network permission denied"),
                    cap.availability,
                    "a Local-Network denial surfaces as an actionable Unavailable reason",
                )
            },
        )
    }

    @Test
    fun capabilityFollowsPathDownThenRecovery() = runTest(StandardTestDispatcher()) {
        val (api, seam) = newSeam()

        api.emitPathState(satisfied(NwInterfaceType.WifiLan))
        testScheduler.runCurrent()
        assertEquals(FabricAvailability.Available, seam.capability.value.availability, "a satisfied Wi-Fi path is Available")

        api.emitPathState(
            NwPathState(NwPathStatus.Unsatisfied, emptySet(), isExpensive = false, isConstrained = false, unsatisfiedReason = NwUnsatisfiedReason.NotAvailable),
        )
        testScheduler.runCurrent()
        assertEquals(
            FabricAvailability.Unavailable("no network is available"),
            seam.capability.value.availability,
            "losing the path flips availability to Unavailable",
        )

        api.emitPathState(satisfied(NwInterfaceType.Cellular))
        testScheduler.runCurrent()
        assertEquals(
            FabricAvailability.Available,
            seam.capability.value.availability,
            "the path recovering (now over cellular) flips availability back to Available",
        )
    }

    private fun satisfied(iface: NwInterfaceType) = NwPathState(
        status = NwPathStatus.Satisfied,
        interfaces = setOf(iface),
        isExpensive = iface == NwInterfaceType.Cellular,
        isConstrained = false,
        unsatisfiedReason = null,
    )
}
