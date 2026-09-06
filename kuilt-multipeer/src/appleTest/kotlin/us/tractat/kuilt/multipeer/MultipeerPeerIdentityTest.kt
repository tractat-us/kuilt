package us.tractat.kuilt.multipeer

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.freshPeerId
import us.tractat.kuilt.multipeer.internal.MultipeerPeerId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Integration proof for #1494 at the apple factory layer, and for #1430's change to
 * where the identity comes from: the factory bakes the **caller's** [PeerId] into the
 * advertised `MCPeerID.displayName`, so two devices with the SAME human display name
 * still derive DISTINCT wire ids and can no longer evict each other on a disconnect —
 * and a caller now knows its own id before it constructs anything. The pure
 * derivation/guard logic is covered by `MultipeerPeerIdTest` /
 * `PeerIdentityRegistryTest` in commonTest.
 */
@OptIn(ExperimentalForeignApi::class)
class MultipeerPeerIdentityTest {
    @Test
    fun `the advertised name carries the identity and still shows the human name`() {
        val selfId = freshPeerId()
        val factory = MultipeerPeerLinkFactory(displayName = "iPhone", serviceType = "kuilt-t1494", selfId = selfId)
        val wire = factory.localPeerId.displayName
        assertAll(
            { assertTrue(wire.startsWith("iPhone#"), "advertised name must carry the identity, got '$wire'") },
            { assertNotEquals("iPhone", wire, "the raw display name must not be the wire identity") },
            { assertEquals("iPhone", MultipeerPeerId.humanName(wire), "human name must be recoverable") },
            { assertEquals(selfId, MultipeerPeerId.peerId(wire), "the wire PeerId must BE the caller's selfId") },
        )
    }

    @Test
    fun `a woven seam's selfId is the id the caller supplied`() =
        runTest {
            // The end-to-end property #1430 buys: the value handed to the constructor is
            // the value `Seam.selfId` reports, having travelled through `decorate`, a real
            // `MCPeerID`, and `MCSessionLink`'s own derivation — the same derivation every
            // remote applies to the string it observes.
            val selfId = freshPeerId()
            val factory = MultipeerPeerLinkFactory(displayName = "iPhone", serviceType = "kuilt-t1430", selfId = selfId)
            val seam = factory.weave(Rendezvous.New(Pattern("room")))
            assertEquals(selfId, seam.selfId)
            factory.close()
        }

    @Test
    fun `two same-named devices advertise distinct ids`() {
        val idA = freshPeerId()
        val idB = freshPeerId()
        val a = MultipeerPeerLinkFactory(displayName = "iPhone", serviceType = "kuilt-t1494", selfId = idA)
        val b = MultipeerPeerLinkFactory(displayName = "iPhone", serviceType = "kuilt-t1494", selfId = idB)
        assertAll(
            {
                assertNotEquals(
                    MultipeerPeerId.peerId(a.localPeerId.displayName),
                    MultipeerPeerId.peerId(b.localPeerId.displayName),
                    "two default-named devices must not collapse to one PeerId",
                )
            },
            { assertEquals(idA, MultipeerPeerId.peerId(a.localPeerId.displayName)) },
            { assertEquals(idB, MultipeerPeerId.peerId(b.localPeerId.displayName)) },
        )
    }

    @Test
    fun `the default selfId is distinct per factory`() {
        // A caller that supplies nothing still gets #1466's guarantee: `freshPeerId()`
        // carries all the collision resistance the removed device nonce did.
        val a = MultipeerPeerLinkFactory(displayName = "iPhone", serviceType = "kuilt-t1494")
        val b = MultipeerPeerLinkFactory(displayName = "iPhone", serviceType = "kuilt-t1494")
        assertNotEquals(
            MultipeerPeerId.peerId(a.localPeerId.displayName),
            MultipeerPeerId.peerId(b.localPeerId.displayName),
        )
    }

    @Test
    fun `a selfId containing the delimiter is refused at construction rather than at weave`() {
        // `decorate` runs in a property initializer, so the failure lands on the line that
        // builds the loom rather than on a later `weave` — a caller sees it where the bad
        // value was passed. Asserted because the alternative (a lazily-decorated name)
        // would surface as an unexplained weave failure on a device.
        val failure =
            assertFailsWith<IllegalArgumentException> {
                MultipeerPeerLinkFactory(
                    displayName = "iPhone",
                    serviceType = "kuilt-t1430",
                    selfId = PeerId("has#delimiter"),
                )
            }
        assertTrue(failure.message.orEmpty().contains("selfId"), "got '${failure.message}'")
    }

    @Test
    fun `a selfId too long to leave room for a display name is refused at construction`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                MultipeerPeerLinkFactory(
                    displayName = "iPhone",
                    serviceType = "kuilt-t1430",
                    selfId = PeerId("y".repeat(MultipeerPeerId.MAX_DISPLAY_NAME_BYTES)),
                )
            }
        assertTrue(failure.message.orEmpty().contains("selfId"), "got '${failure.message}'")
    }
}
