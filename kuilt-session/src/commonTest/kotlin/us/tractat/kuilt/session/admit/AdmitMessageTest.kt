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
}
