package us.tractat.kuilt.multipeer

import kotlinx.cinterop.ExperimentalForeignApi
import us.tractat.kuilt.multipeer.internal.MultipeerPeerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Integration proof for #1494 at the apple factory layer: the factory bakes a
 * per-device nonce into the advertised `MCPeerID.displayName`, so two devices
 * with the SAME human display name derive DISTINCT wire ids and can no longer
 * evict each other on a disconnect. The pure derivation/guard logic is covered
 * by `MultipeerPeerIdTest` / `PeerIdentityRegistryTest` in commonTest.
 */
@OptIn(ExperimentalForeignApi::class)
class MultipeerPeerIdentityTest {
    @Test
    fun `selfId embeds a per-device nonce rather than the raw display name`() {
        val factory = MultipeerPeerLinkFactory(displayName = "iPhone", serviceType = "kuilt-t1494")
        val wire = factory.localPeerId.displayName
        assertTrue(wire.startsWith("iPhone#"), "advertised name must carry the nonce, got '$wire'")
        assertNotEquals("iPhone", wire, "the raw display name must not be the wire identity")
        assertEquals("iPhone", MultipeerPeerId.humanName(wire), "human name must be recoverable")
    }

    @Test
    fun `two same-named devices advertise distinct ids`() {
        val a = MultipeerPeerLinkFactory(displayName = "iPhone", serviceType = "kuilt-t1494")
        val b = MultipeerPeerLinkFactory(displayName = "iPhone", serviceType = "kuilt-t1494")
        assertNotEquals(
            MultipeerPeerId.peerId(a.localPeerId.displayName),
            MultipeerPeerId.peerId(b.localPeerId.displayName),
            "two default-named devices must not collapse to one PeerId",
        )
    }
}
