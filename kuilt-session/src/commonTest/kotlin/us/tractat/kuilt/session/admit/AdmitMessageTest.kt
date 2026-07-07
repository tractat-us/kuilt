package us.tractat.kuilt.session.admit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdmitMessageTest {
    @Test
    fun `encode and decode round-trips Hello`() {
        val original = AdmitMessage.Hello(
            displayName = "Alice",
            sessionId = "session-123",
            deviceId = "device-abc",
        )
        val bytes = AdmitMessage.encode(original)
        val decoded = AdmitMessage.decode(bytes)
        assertEquals(original, decoded)
    }

    @Test
    fun `encode and decode round-trips Welcome`() {
        val original = AdmitMessage.Welcome(
            assignedPeerId = "peer-1",
            displayName = "Alice",
            sessionId = "session-123",
            deviceId = "device-abc",
        )
        val bytes = AdmitMessage.encode(original)
        val decoded = AdmitMessage.decode(bytes)
        assertEquals(original, decoded)
    }

    @Test
    fun `encode and decode round-trips Reject`() {
        val original = AdmitMessage.Reject(reason = "already admitted")
        val bytes = AdmitMessage.encode(original)
        val decoded = AdmitMessage.decode(bytes)
        assertEquals(original, decoded)
    }

    @Test
    fun `Hello without deviceId round-trips`() {
        val original = AdmitMessage.Hello(displayName = "Bob", sessionId = "s-1")
        val decoded = AdmitMessage.decode(AdmitMessage.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `encoded admit frame starts with PREFIX_BYTE`() {
        val bytes = AdmitMessage.encode(AdmitMessage.Hello("Alice", "s-1"))
        assertEquals(AdmitMessage.PREFIX_BYTE, bytes[0])
    }

    @Test
    fun `isAdmitFrame returns true for encoded frame`() {
        val bytes = AdmitMessage.encode(AdmitMessage.Hello("Alice", "s-1"))
        assertTrue(AdmitMessage.isAdmitFrame(bytes))
    }

    @Test
    fun `isAdmitFrame returns false for application frame`() {
        val appFrame = byteArrayOf(0x01, 0x02, 0x03)
        assertTrue(!AdmitMessage.isAdmitFrame(appFrame))
    }

    @Test
    fun `decode returns null for non-admit bytes`() {
        val appFrame = "hello world".encodeToByteArray()
        assertNull(AdmitMessage.decode(appFrame))
    }

    @Test
    fun `decode returns null for empty bytes`() {
        assertNull(AdmitMessage.decode(ByteArray(0)))
    }

    @Test
    fun `decode returns null for malformed CBOR after prefix`() {
        val corrupt = byteArrayOf(AdmitMessage.PREFIX_BYTE, 0x01, 0x02)
        assertNull(AdmitMessage.decode(corrupt))
    }

    @Test
    fun `decoded Hello is type Hello not Welcome`() {
        val hello = AdmitMessage.Hello("Alice", "s-1")
        val decoded = AdmitMessage.decode(AdmitMessage.encode(hello))
        assertNotNull(decoded)
        assertTrue(decoded is AdmitMessage.Hello)
    }

    @Test
    fun `Resume round-trips`() {
        val original = AdmitMessage.Resume(tokenPeerId = "peer-1", tokenRoomId = "room-9", issuedAt = 1_700_000_000_000L)
        assertEquals(original, AdmitMessage.decode(AdmitMessage.encode(original)))
    }

    @Test
    fun `ResumeAck and Goodbye round-trip`() {
        assertEquals(AdmitMessage.ResumeAck, AdmitMessage.decode(AdmitMessage.encode(AdmitMessage.ResumeAck)))
        assertEquals(AdmitMessage.Goodbye, AdmitMessage.decode(AdmitMessage.encode(AdmitMessage.Goodbye)))
    }

    @Test
    fun `Farewell round-trips`() {
        val original = AdmitMessage.Farewell(peerId = "peer-7")
        assertEquals(original, AdmitMessage.decode(AdmitMessage.encode(original)))
    }

    /**
     * Golden vector (backward compat): a Hello captured from the current wire format. Decoding it
     * must yield the exact known value — this pins the wire so a future codec tweak (e.g. adding a
     * field) can't silently change the bytes an older peer emits.
     *
     * Structure: `PREFIX_BYTE` then an indefinite CBOR array `[ "hello", { indefinite map } ]`,
     * ending in `0xFF 0xFF` (map break, array break).
     */
    @Test
    fun `golden Hello vector decodes to known value`() {
        val decoded = AdmitMessage.decode(GOLDEN_HELLO)
        assertEquals(
            AdmitMessage.Hello(displayName = "Alice", sessionId = "session-123", deviceId = "device-abc"),
            decoded,
        )
        // #1172 A2 added Hello.targetRoom. The golden bytes predate it and carry no such key,
        // so an old frame must decode with targetRoom = null (the permissive default). This pins
        // that the new field is wire-safe backward-compatible.
        assertNull((decoded as AdmitMessage.Hello).targetRoom)
    }

    /**
     * Forward compat (the point of #1172 A1): a frame minted by a *newer* sender carries an extra
     * unknown field. An old build must still decode it to the fields it knows — before the
     * `ignoreUnknownKeys = true` change this threw, [AdmitMessage.decode]'s guard swallowed it to
     * null, and the joiner hung (no admit timeout).
     *
     * Crafted deterministically from [GOLDEN_HELLO] by splicing an extra `"futureFlag": true` entry
     * in before the map's terminating break (the first of the two trailing `0xFF`s).
     */
    @Test
    fun `Hello with unknown extra field still decodes`() {
        // Extra map entry: key "futureFlag" (text, 10 chars) -> true (0xF5).
        val extraEntry = byteArrayOf(
            0x6A, // CBOR text string, length 10
            'f'.code.toByte(), 'u'.code.toByte(), 't'.code.toByte(), 'u'.code.toByte(), 'r'.code.toByte(),
            'e'.code.toByte(), 'F'.code.toByte(), 'l'.code.toByte(), 'a'.code.toByte(), 'g'.code.toByte(),
            0xF5.toByte(), // CBOR true
        )
        // GOLDEN_HELLO ends in [.., mapBreak(0xFF), arrayBreak(0xFF)]. Insert the entry before the
        // map break so it lands inside the "hello" field map.
        val body = GOLDEN_HELLO.copyOfRange(0, GOLDEN_HELLO.size - 2)
        val crafted = body + extraEntry + byteArrayOf(0xFF.toByte(), 0xFF.toByte())

        val decoded = AdmitMessage.decode(crafted)
        assertEquals(
            AdmitMessage.Hello(displayName = "Alice", sessionId = "session-123", deviceId = "device-abc"),
            decoded,
        )
    }

    private companion object {
        /** Captured bytes of `Hello("Alice", "session-123", "device-abc")` in the current wire format. */
        val GOLDEN_HELLO = intArrayOf(
            97, 159, 101, 104, 101, 108, 108, 111, 191, 107, 100, 105, 115, 112, 108, 97, 121, 78,
            97, 109, 101, 101, 65, 108, 105, 99, 101, 105, 115, 101, 115, 115, 105, 111, 110, 73,
            100, 107, 115, 101, 115, 115, 105, 111, 110, 45, 49, 50, 51, 104, 100, 101, 118, 105,
            99, 101, 73, 100, 106, 100, 101, 118, 105, 99, 101, 45, 97, 98, 99, 255, 255,
        ).map { it.toByte() }.toByteArray()
    }
}
