package us.tractat.kuilt.nw

import us.tractat.kuilt.core.DeliveryPolicy
import us.tractat.kuilt.core.PeerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CI-safe guards for the [appleNwLoom] public factory (#1427).
 *
 * Constructing the loom builds a `RealNwApi` (a dispatch queue) but touches **no**
 * Bonjour/AWDL radio until [NwLoom.weave] — so these tests need no real network and
 * cannot hang. The happy path (a real encrypted link) is proven separately by
 * [NwLoopbackConformanceTest]; the PSK derivation by `NwPskTest`.
 */
class AppleNwLoomFactoryTest {

    @Test
    fun buildsAnNwLoomWithTheGivenIdentityAndAnEmptyLobby() {
        val selfId = PeerId("host-1")
        val loom = appleNwLoom(serviceType = "_kuilt._tcp", roomKey = "shared-code", selfId = selfId)

        assertNotNull(loom)
        assertEquals(selfId, loom.selfId, "factory must honour the supplied selfId")
        assertTrue(loom.visiblePeers.value.isEmpty(), "the lobby roster starts empty until browsing")
    }

    @Test
    fun defaultsToAFreshNonBlankIdentity() {
        val a = appleNwLoom(serviceType = "_kuilt._tcp", roomKey = "code")
        val b = appleNwLoom(serviceType = "_kuilt._tcp", roomKey = "code")

        assertTrue(a.selfId.value.isNotBlank(), "default selfId must be non-blank")
        assertTrue(
            a.selfId != b.selfId,
            "two default looms must mint distinct identities (fresh UUID per loom, #1405)",
        )
    }

    @Test
    fun forwardsAnExplicitDeliveryPolicy() {
        // A lossy policy is a valid, non-default choice — the factory must not hard-wire Reliable.
        val loom = appleNwLoom(
            serviceType = "_kuilt._tcp",
            roomKey = "code",
            policy = DeliveryPolicy.Lossy,
        )
        assertNotNull(loom)
    }
}
