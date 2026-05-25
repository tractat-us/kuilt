package us.tractat.kuilt.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class OpaqueFrameTest {
    @Test
    fun `equal frames with same byte content are equal`() {
        val a = OpaqueFrame(byteArrayOf(1, 2, 3))
        val b = OpaqueFrame(byteArrayOf(1, 2, 3))
        assertEquals(a, b)
    }

    @Test
    fun `equal frames with same byte content have equal hash codes`() {
        val a = OpaqueFrame(byteArrayOf(1, 2, 3))
        val b = OpaqueFrame(byteArrayOf(1, 2, 3))
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `frames with different byte content are not equal`() {
        val a = OpaqueFrame(byteArrayOf(1, 2, 3))
        val b = OpaqueFrame(byteArrayOf(1, 2, 4))
        assertNotEquals(a, b)
    }

    @Test
    fun `different ByteArray references with same content are equal`() {
        // This is the key footgun: Kotlin data class default equals uses
        // referential equality for arrays, not content equality.
        val bytes1 = byteArrayOf(10, 20, 30)
        val bytes2 = byteArrayOf(10, 20, 30)
        assertTrue(bytes1 !== bytes2, "Precondition: different references")
        assertEquals(OpaqueFrame(bytes1), OpaqueFrame(bytes2))
    }

    @Test
    fun `frames differ when sender differs`() {
        val payload = byteArrayOf(1)
        val a = OpaqueFrame(payload, sender = TransportPeerId("alice"))
        val b = OpaqueFrame(payload.copyOf(), sender = TransportPeerId("bob"))
        assertNotEquals(a, b)
    }

    @Test
    fun `frames differ when sequence differs`() {
        val payload = byteArrayOf(1)
        val a = OpaqueFrame(payload, sequence = 1L)
        val b = OpaqueFrame(payload.copyOf(), sequence = 2L)
        assertNotEquals(a, b)
    }

    @Test
    fun `frames with null sender and non-null sender are not equal`() {
        val payload = byteArrayOf(1)
        val a = OpaqueFrame(payload, sender = null)
        val b = OpaqueFrame(payload.copyOf(), sender = TransportPeerId("alice"))
        assertNotEquals(a, b)
    }

    @Test
    fun `default sender is null and default sequence is zero`() {
        val frame = OpaqueFrame(byteArrayOf(42))
        assertEquals(null, frame.sender)
        assertEquals(0L, frame.sequence)
    }

    @Test
    fun `reflexive equality`() {
        val frame = OpaqueFrame(byteArrayOf(1, 2, 3), sender = TransportPeerId("x"), sequence = 5)
        assertEquals(frame, frame)
    }
}
