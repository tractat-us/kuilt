package us.tractat.kuilt.multipeer

import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.TransportRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * `MultipeerPeerLinkFactory`'s **pre-connect** self-report on Apple platforms (#1746).
 *
 * The roles are a static fact — MultipeerConnectivity is compiled in here and it really does
 * discover, carry data, and use Wi-Fi Direct and Bluetooth. The *availability* was not: the actual
 * hardcoded [FabricAvailability.Available] while reading neither the Local Network permission nor
 * any radio's state, which is precisely the guess the issue body names ("a multipeer loom that has
 * not read `CBCentralManager` state"). Since `Loom.capability()` is the surface a consuming app
 * turns into pre-connect guidance (#1530), a fabricated `Available` there is an authoritative false
 * negative — the same shape #1712 Track A removed one layer down on `Seam.capability`.
 *
 * [FabricAvailability.Unknown] rather than [FabricAvailability.Unavailable]: nothing here says the
 * fabric is unusable, only that this loom has not established that it is usable.
 */
class MultipeerAppleCapabilityTest {

    @Test
    fun rolesAreAStaticFactAndSurviveTheAvailabilityFloor() {
        val capability = MultipeerPeerLinkFactory("probe-device", "kuilt-probe").capability()
        assertEquals(
            setOf(
                TransportRole.Discovery,
                TransportRole.Data,
                TransportRole.WifiDirect,
                TransportRole.Bluetooth,
            ),
            capability.roles,
            "the roles half is established by construction — MultipeerConnectivity is compiled in here",
        )
    }

    @Test
    fun availabilityIsUnknownBecauseNeitherPermissionNorRadioIsProbed() {
        val unknown = assertIs<FabricAvailability.Unknown>(
            MultipeerPeerLinkFactory("probe-device", "kuilt-probe").availability(),
            "a loom that reads neither the Local Network permission nor a radio must not claim Available",
        )
        assertEquals(
            "MultipeerConnectivity is compiled in, but neither the Local Network permission " +
                "nor the Wi-Fi/Bluetooth radio state is probed",
            unknown.reason,
            "the reason names what was not established, so a consuming app can surface it",
        )
    }
}
