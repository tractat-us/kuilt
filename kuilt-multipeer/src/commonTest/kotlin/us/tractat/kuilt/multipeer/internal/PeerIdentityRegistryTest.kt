package us.tractat.kuilt.multipeer.internal

import us.tractat.kuilt.core.PeerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The collision-guard half of the #1494 fix. Devices are modelled by a distinct
 * identity token (here a boxed [Any]); two tokens sharing one [PeerId] simulate
 * the pathological display-name collision the nonce is designed to prevent, and
 * prove the guard keeps the failure mode to "refuse + survive", never
 * "evict the wrong peer".
 */
class PeerIdentityRegistryTest {
    private val id = PeerId("iPhone#collision")

    // Distinct object identities standing in for two distinct MCPeerID devices
    // that (pathologically) advertise the same display name.
    private val deviceA = Any()
    private val deviceB = Any()

    @Test
    fun `first device to claim an id is bound`() {
        val registry = PeerIdentityRegistry<Any>()
        assertEquals(PeerIdentityRegistry.BindResult.BOUND, registry.bind(id, deviceA))
        assertEquals(setOf(id), registry.peers)
    }

    @Test
    fun `same device re-announcing is idempotent`() {
        val registry = PeerIdentityRegistry<Any>()
        registry.bind(id, deviceA)
        assertEquals(PeerIdentityRegistry.BindResult.ALREADY_BOUND, registry.bind(id, deviceA))
        assertEquals(setOf(id), registry.peers)
    }

    @Test
    fun `a second distinct device on one id is a refused collision rather than a merge`() {
        val registry = PeerIdentityRegistry<Any>()
        registry.bind(id, deviceA)
        assertEquals(PeerIdentityRegistry.BindResult.COLLISION, registry.bind(id, deviceB))
        // The incumbent still holds the id — it was not reassigned.
        assertEquals(setOf(id), registry.peers)
    }

    @Test
    fun `the incumbent survives a colliding newcomer's disconnect`() {
        val registry = PeerIdentityRegistry<Any>()
        registry.bind(id, deviceA)
        registry.bind(id, deviceB) // refused

        // deviceB (which never held the id) drops: must NOT evict deviceA.
        assertFalse(registry.unbind(id, deviceB), "a non-holder drop must not remove the binding")
        assertEquals(setOf(id), registry.peers, "the incumbent was wrongly evicted")
    }

    @Test
    fun `the holder's disconnect removes the id`() {
        val registry = PeerIdentityRegistry<Any>()
        registry.bind(id, deviceA)
        assertTrue(registry.unbind(id, deviceA))
        assertTrue(registry.peers.isEmpty())
    }

    @Test
    fun `distinct ids are tracked independently`() {
        val registry = PeerIdentityRegistry<Any>()
        val idA = PeerId("iPhone#aaaa")
        val idB = PeerId("iPhone#bbbb")
        registry.bind(idA, deviceA)
        registry.bind(idB, deviceB)
        assertEquals(setOf(idA, idB), registry.peers)

        // One dropping never affects the other — the core #1494 property.
        registry.unbind(idA, deviceA)
        assertEquals(setOf(idB), registry.peers)
    }
}
