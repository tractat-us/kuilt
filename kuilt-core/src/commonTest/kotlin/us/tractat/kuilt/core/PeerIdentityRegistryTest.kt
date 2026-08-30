package us.tractat.kuilt.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The identity rules [PeerIdentityRegistry] states once for every fabric.
 *
 * Devices are modelled by a distinct identity token (a boxed [Any]); two tokens sharing one
 * [PeerId] simulate the pathological display-name collision that produced #1494, and prove the
 * guard keeps the failure mode to "refuse + survive", never "evict the wrong peer".
 *
 * The refusal arms ([REFUSED_BLANK][PeerIdentityRegistry.BindResult.REFUSED_BLANK] and
 * [REFUSED_SELF][PeerIdentityRegistry.BindResult.REFUSED_SELF], #1821) are paired with the
 * [BOUND][PeerIdentityRegistry.BindResult.BOUND] arms above them, which is what stops a registry
 * that refused *every* id from satisfying them.
 */
class PeerIdentityRegistryTest {
    private val self = PeerId("me")
    private val id = PeerId("iPhone#collision")

    // Distinct object identities standing in for two distinct devices that (pathologically)
    // advertise the same display name.
    private val deviceA = Any()
    private val deviceB = Any()

    private fun registry() = PeerIdentityRegistry<Any>(self)

    @Test
    fun `first device to claim an id is bound`() {
        val registry = registry()
        assertEquals(PeerIdentityRegistry.BindResult.BOUND, registry.bind(id, deviceA))
        assertEquals(setOf(id), registry.peers)
    }

    @Test
    fun `same device re-announcing is idempotent`() {
        val registry = registry()
        registry.bind(id, deviceA)
        assertEquals(PeerIdentityRegistry.BindResult.ALREADY_BOUND, registry.bind(id, deviceA))
        assertEquals(setOf(id), registry.peers)
    }

    @Test
    fun `a second distinct device on one id is a refused collision rather than a merge`() {
        val registry = registry()
        registry.bind(id, deviceA)
        assertEquals(PeerIdentityRegistry.BindResult.COLLISION, registry.bind(id, deviceB))
        // The incumbent still holds the id — it was not reassigned.
        assertEquals(setOf(id), registry.peers)
        assertEquals(deviceA, registry.holderOf(id), "the incumbent still holds the id")
    }

    @Test
    fun `the incumbent survives a colliding newcomer's disconnect`() {
        val registry = registry()
        registry.bind(id, deviceA)
        registry.bind(id, deviceB) // refused

        // deviceB (which never held the id) drops: must NOT evict deviceA.
        assertFalse(registry.unbind(id, deviceB), "a non-holder drop must not remove the binding")
        assertEquals(setOf(id), registry.peers, "the incumbent was wrongly evicted")
    }

    @Test
    fun `the holder's disconnect removes the id`() {
        val registry = registry()
        registry.bind(id, deviceA)
        assertTrue(registry.unbind(id, deviceA))
        assertTrue(registry.peers.isEmpty())
    }

    @Test
    fun `distinct ids are tracked independently`() {
        val registry = registry()
        val idA = PeerId("iPhone#aaaa")
        val idB = PeerId("iPhone#bbbb")
        registry.bind(idA, deviceA)
        registry.bind(idB, deviceB)
        assertEquals(setOf(idA, idB), registry.peers)

        // One dropping never affects the other — the core #1494 property.
        registry.unbind(idA, deviceA)
        assertEquals(setOf(idB), registry.peers)
    }

    @Test
    fun `clear drops every binding at once`() {
        val registry = registry()
        registry.bind(PeerId("iPhone#aaaa"), deviceA)
        registry.bind(PeerId("iPhone#bbbb"), deviceB)

        registry.clear()

        assertTrue(registry.peers.isEmpty(), "clear must leave no binding behind")
    }

    @Test
    fun `a disconnect arriving after clear cannot republish a surviving peer`() {
        // The #1851 shape. MCSessionLink recomputes its roster as `registry.peers + selfId` on
        // every `.notConnected`, and `close()` disconnects the session — so MC fires one such
        // callback per peer AFTER the seam has torn down. If the tear left the bindings in place,
        // the first of those callbacks would republish everyone still bound onto a Torn seam.
        val registry = registry()
        val idA = PeerId("iPhone#aaaa")
        val idB = PeerId("iPhone#bbbb")
        registry.bind(idA, deviceA)
        registry.bind(idB, deviceB)

        registry.clear() // the seam tears down

        // A's post-teardown `.notConnected` lands; recomputing the roster must not resurrect B.
        registry.unbind(idA, deviceA)
        assertTrue(registry.peers.isEmpty(), "a stale disconnect resurrected a peer after teardown")
    }

    // ── #1821: the two refusals every fabric was re-deriving for itself ───────

    @Test
    fun `a blank id is refused whatever device announces it`() {
        val registry = registry()
        assertEquals(PeerIdentityRegistry.BindResult.REFUSED_BLANK, registry.bind(PeerId(""), deviceA))
        assertEquals(PeerIdentityRegistry.BindResult.REFUSED_BLANK, registry.bind(PeerId("   "), deviceB))
        assertTrue(registry.peers.isEmpty(), "a blank id must never reach the roster")
    }

    /**
     * The refusal must not depend on arrival order — a blank id announced *after* a healthy roster
     * exists is refused just the same, and leaves that roster untouched.
     */
    @Test
    fun `a blank id announced onto a live roster changes nothing`() {
        val registry = registry()
        registry.bind(id, deviceA)

        assertEquals(PeerIdentityRegistry.BindResult.REFUSED_BLANK, registry.bind(PeerId(""), deviceB))
        assertEquals(setOf(id), registry.peers, "the live roster is untouched by a refused blank id")
    }

    @Test
    fun `this peer's own id is refused as a remote`() {
        val registry = registry()
        assertEquals(PeerIdentityRegistry.BindResult.REFUSED_SELF, registry.bind(self, deviceA))
        assertTrue(registry.peers.isEmpty(), "selfId must never be registered as a remote")
        assertNull(registry.holderOf(self), "nothing holds selfId")
    }

    /**
     * The #1466 mechanism end-to-end: a self-dial forms and then drops. The drop must be a no-op —
     * an unbind of an id nothing ever held — so it cannot evict anything, and in particular cannot
     * take a live peer with it.
     */
    @Test
    fun `a refused self-dial's later disconnect evicts nobody`() {
        val registry = registry()
        registry.bind(id, deviceA)
        registry.bind(self, deviceB) // refused

        assertFalse(registry.unbind(self, deviceB), "nothing was bound, so nothing is removed")
        assertEquals(setOf(id), registry.peers, "the live peer survived the self-link's drop")
    }

    @Test
    fun `idHeldBy is the inverse of holderOf`() {
        val registry = registry()
        registry.bind(id, deviceA)

        assertEquals(id, registry.idHeldBy(deviceA))
        assertNull(registry.idHeldBy(deviceB), "a device holding nothing reports no id")
    }
}
