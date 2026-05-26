package us.tractat.kuilt.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SwatchTest {
    @Test
    fun `equal frames with same byte content are equal`() {
        val a = Swatch(byteArrayOf(1, 2, 3))
        val b = Swatch(byteArrayOf(1, 2, 3))
        assertEquals(a, b)
    }

    @Test
    fun `equal frames with same byte content have equal hash codes`() {
        val a = Swatch(byteArrayOf(1, 2, 3))
        val b = Swatch(byteArrayOf(1, 2, 3))
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `frames with different byte content are not equal`() {
        val a = Swatch(byteArrayOf(1, 2, 3))
        val b = Swatch(byteArrayOf(1, 2, 4))
        assertNotEquals(a, b)
    }

    @Test
    fun `different ByteArray references with same content are equal`() {
        // This is the key footgun: Kotlin data class default equals uses
        // referential equality for arrays, not content equality.
        val bytes1 = byteArrayOf(10, 20, 30)
        val bytes2 = byteArrayOf(10, 20, 30)
        assertTrue(bytes1 !== bytes2, "Precondition: different references")
        assertEquals(Swatch(bytes1), Swatch(bytes2))
    }

    @Test
    fun `frames differ when sender differs`() {
        val payload = byteArrayOf(1)
        val a = Swatch(payload, sender = PeerId("alice"))
        val b = Swatch(payload.copyOf(), sender = PeerId("bob"))
        assertNotEquals(a, b)
    }

    @Test
    fun `frames differ when sequence differs`() {
        val payload = byteArrayOf(1)
        val a = Swatch(payload, sequence = 1L)
        val b = Swatch(payload.copyOf(), sequence = 2L)
        assertNotEquals(a, b)
    }

    @Test
    fun `frames with null sender and non-null sender are not equal`() {
        val payload = byteArrayOf(1)
        val a = Swatch(payload, sender = null)
        val b = Swatch(payload.copyOf(), sender = PeerId("alice"))
        assertNotEquals(a, b)
    }

    @Test
    fun `default sender is null and default sequence is zero`() {
        val frame = Swatch(byteArrayOf(42))
        assertEquals(null, frame.sender)
        assertEquals(0L, frame.sequence)
    }

    @Test
    fun `reflexive equality`() {
        val frame = Swatch(byteArrayOf(1, 2, 3), sender = PeerId("x"), sequence = 5)
        assertEquals(frame, frame)
    }
}
