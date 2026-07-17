package us.tractat.kuilt.multipeer.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The identity-derivation half of the #1494 fix: a per-device nonce baked into
 * the advertised display name makes the wire [us.tractat.kuilt.core.PeerId]
 * collision-resistant while staying consistent across both ends of a link
 * (both derive the id from the same decorated string).
 */
class MultipeerPeerIdTest {
    @Test
    fun `decorate embeds the nonce after the delimiter`() {
        val wire = MultipeerPeerId.decorate(name = "iPhone", nonce = "a1b2c3d4")
        assertEquals("iPhone#a1b2c3d4", wire)
    }

    @Test
    fun `two same-named devices with distinct nonces derive distinct ids`() {
        val a = MultipeerPeerId.peerId(MultipeerPeerId.decorate("iPhone", "aaaaaaaa"))
        val b = MultipeerPeerId.peerId(MultipeerPeerId.decorate("iPhone", "bbbbbbbb"))
        assertNotEquals(a, b, "distinct nonces must not collapse to one PeerId")
    }

    @Test
    fun `both ends derive the same id from the same decorated name`() {
        // The advertiser bakes the nonce in; every observer sees that exact
        // string as MCPeerID.displayName and derives the same id.
        val advertised = MultipeerPeerId.decorate("iPhone", "deadbeef")
        val observedByHost = MultipeerPeerId.peerId(advertised)
        val observedByJoiner = MultipeerPeerId.peerId(advertised)
        assertEquals(observedByHost, observedByJoiner)
    }

    @Test
    fun `humanName strips the nonce for display`() {
        val wire = MultipeerPeerId.decorate("Iain's iPhone", "a1b2c3d4")
        assertEquals("Iain's iPhone", MultipeerPeerId.humanName(wire))
    }

    @Test
    fun `humanName round-trips a name containing the delimiter`() {
        val wire = MultipeerPeerId.decorate("room#7", "a1b2c3d4")
        assertEquals("room#7", MultipeerPeerId.humanName(wire))
    }

    @Test
    fun `humanName returns an undecorated name unchanged`() {
        assertEquals("iPhone", MultipeerPeerId.humanName("iPhone"))
    }

    @Test
    fun `decorate keeps the wire name within Apple's 63-byte limit`() {
        val longName = "x".repeat(200)
        val wire = MultipeerPeerId.decorate(longName, "a1b2c3d4")
        assertTrue(
            wire.encodeToByteArray().size <= MultipeerPeerId.MAX_DISPLAY_NAME_BYTES,
            "decorated name is ${wire.encodeToByteArray().size} bytes; must be <= ${MultipeerPeerId.MAX_DISPLAY_NAME_BYTES}",
        )
        assertTrue(wire.endsWith("#a1b2c3d4"), "the nonce must survive truncation whole")
    }

    @Test
    fun `decorate never splits a multi-byte character under truncation`() {
        // Each emoji is 4 UTF-8 bytes; truncation must land on a char boundary
        // so the result stays valid text.
        val wire = MultipeerPeerId.decorate("😀".repeat(40), "a1b2c3d4")
        assertTrue(wire.encodeToByteArray().size <= MultipeerPeerId.MAX_DISPLAY_NAME_BYTES)
        // No U+FFFD replacement char from a split surrogate pair.
        assertTrue('�' !in wire, "truncation split a surrogate pair")
    }

    @Test
    fun `decorate rejects an empty nonce`() {
        assertFailsWith<IllegalArgumentException> { MultipeerPeerId.decorate("iPhone", "") }
    }

    @Test
    fun `decorate rejects a nonce containing the delimiter`() {
        assertFailsWith<IllegalArgumentException> { MultipeerPeerId.decorate("iPhone", "aa#bb") }
    }
}
