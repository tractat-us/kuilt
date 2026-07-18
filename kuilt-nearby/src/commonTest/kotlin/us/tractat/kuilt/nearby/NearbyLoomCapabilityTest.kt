package us.tractat.kuilt.nearby

import us.tractat.kuilt.core.TransportRole
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [NearbyLoom] self-reports the Google Nearby Connections fabric's roles: it links
 * peers over Bluetooth and peer-to-peer Wi-Fi, carrying data across either.
 */
class NearbyLoomCapabilityTest {
    @Test
    fun declaresBluetoothWifiDirectAndDataRoles() {
        val loom = NearbyLoom(FakeNearbyApi(FakeNearbyRadio()))
        assertEquals(
            setOf(TransportRole.Bluetooth, TransportRole.WifiDirect, TransportRole.Data),
            loom.capability().roles,
        )
    }
}
