package us.tractat.kuilt.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SwatchTest {

    // ── payload allocation guarantee ─────────────────────────────────────────

    @Test
    fun `payload returns backing array directly for a full-array swatch`() {
        val arr = byteArrayOf(1, 2, 3)
        // No copy must occur — same reference.
        assertSame(arr, Swatch(arr).payload)
    }

    @Test
    fun `payload materialises a fresh copy for a dropFirst view`() {
        val raw = byteArrayOf(0xAA.toByte(), 10, 20, 30)
        val viewed = Swatch(raw).dropFirst(1)
        val payloadCopy = viewed.payload
        assertTrue(payloadCopy.contentEquals(byteArrayOf(10, 20, 30)))
        // Must be a distinct array — not the backing raw array.
        assertTrue(payloadCopy !== raw)
    }

    // ── Offset-view: a sliced Swatch equals a freshly-copied one ─────────────

    @Test
    fun `dropFirst view equals a freshly-copied swatch of the same logical bytes`() {
        // header byte 0xAA + payload bytes 1,2,3
        val raw = Swatch(byteArrayOf(0xAA.toByte(), 1, 2, 3))
        val viewed = raw.dropFirst(1)
        val copied = Swatch(byteArrayOf(1, 2, 3))
        assertEquals(copied, viewed)
    }

    @Test
    fun `dropFirst view has the same hash code as an equal freshly-copied swatch`() {
        val raw = Swatch(byteArrayOf(0xBB.toByte(), 10, 20))
        val viewed = raw.dropFirst(1)
        val copied = Swatch(byteArrayOf(10, 20))
        assertEquals(copied.hashCode(), viewed.hashCode())
    }

    @Test
    fun `payload property of a dropFirst view returns only the logical bytes`() {
        val raw = Swatch(byteArrayOf(0xFF.toByte(), 42, 43, 44))
        val viewed = raw.dropFirst(1)
        assertTrue(viewed.payload.contentEquals(byteArrayOf(42, 43, 44)))
    }

    @Test
    fun `dropFirst preserves sender and sequence`() {
        val raw = Swatch(byteArrayOf(0x01, 5, 6), sender = PeerId("alice"), sequence = 7L)
        val viewed = raw.dropFirst(1)
        assertEquals(PeerId("alice"), viewed.sender)
        assertEquals(7L, viewed.sequence)
    }
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
