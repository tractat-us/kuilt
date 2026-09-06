package us.tractat.kuilt.multipeer.internal

import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.freshPeerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The identity-derivation half of the #1494 fix, after #1430 made the identity
 * caller-supplied: the peer's own [PeerId] is baked into the advertised display
 * name, so the wire id is collision-resistant AND is exactly the id the caller
 * chose — while every observer of that one string still derives the same value.
 */
class MultipeerPeerIdTest {
    @Test
    fun `decorate embeds the identity after the delimiter`() {
        val wire = MultipeerPeerId.decorate("iPhone", "a1b2c3d4")
        assertEquals("iPhone#a1b2c3d4", wire)
    }

    @Test
    fun `peerId recovers the caller's selfId from the decorated wire name`() {
        val selfId = freshPeerId()
        val wire = MultipeerPeerId.decorate("Iain's iPhone", selfId.value)
        assertEquals(selfId, MultipeerPeerId.peerId(wire), "the wire PeerId must BE the caller's selfId")
    }

    @Test
    fun `peerId recovers the identity even when the human prefix was truncated`() {
        // A UUID selfId leaves only ~26 bytes for the human name, so a long one
        // IS trimmed — the identity suffix must survive that whole.
        val selfId = freshPeerId()
        val wire = MultipeerPeerId.decorate("x".repeat(200), selfId.value)
        assertTrue(
            wire.encodeToByteArray().size <= MultipeerPeerId.MAX_DISPLAY_NAME_BYTES,
            "decorated name is ${wire.encodeToByteArray().size} bytes",
        )
        assertEquals(selfId, MultipeerPeerId.peerId(wire), "truncation must never eat into the identity")
    }

    @Test
    fun `two same-named devices with distinct selfIds derive distinct ids`() {
        val idA = freshPeerId()
        val idB = freshPeerId()
        val a = MultipeerPeerId.peerId(MultipeerPeerId.decorate("iPhone", idA.value))
        val b = MultipeerPeerId.peerId(MultipeerPeerId.decorate("iPhone", idB.value))
        assertNotEquals(a, b, "two default-named devices must not collapse to one PeerId (#1466)")
        assertEquals(idA, a)
        assertEquals(idB, b)
    }

    @Test
    fun `both ends derive the advertiser's selfId from the one observed name`() {
        // The advertiser bakes its identity in; it derives its OWN selfId back out
        // of the name it advertises, and every observer derives the same value from
        // the same `MCPeerID.displayName`. Asserting against `selfId` (not just
        // observer-vs-observer, which is true of any pure function) is what makes
        // this test able to fail.
        val selfId = freshPeerId()
        val advertised = MultipeerPeerId.decorate("iPhone", selfId.value)
        val derivedByAdvertiser = MultipeerPeerId.peerId(advertised)
        val derivedByObserver = MultipeerPeerId.peerId(advertised)
        assertEquals(selfId, derivedByAdvertiser)
        assertEquals(selfId, derivedByObserver)
    }

    @Test
    fun `a delimiter inside the human name is not mistaken for the separator`() {
        val selfId = freshPeerId()
        val wire = MultipeerPeerId.decorate("room#7", selfId.value)
        assertEquals(selfId, MultipeerPeerId.peerId(wire), "the LAST delimiter separates the identity")
        assertEquals("room#7", MultipeerPeerId.humanName(wire))
    }

    @Test
    fun `peerId returns the whole string for an undecorated name`() {
        // A legacy or non-kuilt peer advertising a bare name still yields a
        // sensible, non-blank id (substringAfterLast falls back to the receiver).
        assertEquals(PeerId("iPhone"), MultipeerPeerId.peerId("iPhone"))
    }

    @Test
    fun `peerId returns the whole string when the identity part is empty`() {
        // "foo#" cannot come out of decorate (an empty selfId is refused), so it is
        // a foreign name. Deriving PeerId("") from it would be refused downstream by
        // PeerIdentityRegistry's blank-id guard; fall back to the whole string.
        assertEquals(PeerId("foo#"), MultipeerPeerId.peerId("foo#"))
    }

    @Test
    fun `humanName strips the identity for display`() {
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
        assertTrue(wire.endsWith("#a1b2c3d4"), "the identity must survive truncation whole")
    }

    @Test
    fun `a UUID selfId leaves 26 bytes of human name inside the 63-byte ceiling`() {
        // The squeeze, pinned as a number: '#' + a 36-char UUID costs 37 of 63.
        val selfId = freshPeerId()
        assertEquals(36, selfId.value.length, "freshPeerId mints a 36-char UUID string")
        val wire = MultipeerPeerId.decorate("x".repeat(200), selfId.value)
        assertEquals(63, wire.encodeToByteArray().size)
        assertEquals(26, MultipeerPeerId.humanName(wire).length, "26 bytes of human prefix survive")
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
    fun `decorate rejects an empty selfId`() {
        assertFailsWith<IllegalArgumentException> { MultipeerPeerId.decorate("iPhone", "") }
    }

    @Test
    fun `decorate rejects a selfId containing the delimiter`() {
        val failure = assertFailsWith<IllegalArgumentException> { MultipeerPeerId.decorate("iPhone", "aa#bb") }
        assertTrue(
            failure.message.orEmpty().contains("selfId"),
            "the message must name selfId so a caller knows which value to change; got '${failure.message}'",
        )
    }

    @Test
    fun `decorate rejects a selfId too long to leave room for a name`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                MultipeerPeerId.decorate("iPhone", "y".repeat(MultipeerPeerId.MAX_DISPLAY_NAME_BYTES))
            }
        assertTrue(
            failure.message.orEmpty().contains("selfId"),
            "the message must name selfId; got '${failure.message}'",
        )
    }
}
