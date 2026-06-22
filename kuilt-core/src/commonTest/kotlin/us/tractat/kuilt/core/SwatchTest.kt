package us.tractat.kuilt.core

import kotlinx.serialization.BinaryFormat
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class SwatchTest {

    // ── toByteArray — always allocates ───────────────────────────────────────

    @Test
    fun `toByteArray returns correct bytes for a full-array swatch`() {
        val arr = byteArrayOf(1, 2, 3)
        assertTrue(Swatch(arr).toByteArray().contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `toByteArray always returns a distinct array`() {
        val arr = byteArrayOf(1, 2, 3)
        val swatch = Swatch(arr)
        // toByteArray must never hand out the live backing array.
        assertNotSame(arr, swatch.toByteArray())
        // Two successive calls return independent copies.
        assertNotSame(swatch.toByteArray(), swatch.toByteArray())
    }

    @Test
    fun `toByteArray returns correct bytes for a dropFirst view`() {
        val raw = byteArrayOf(0xAA.toByte(), 10, 20, 30)
        val viewed = Swatch(raw).dropFirst(1)
        assertTrue(viewed.toByteArray().contentEquals(byteArrayOf(10, 20, 30)))
    }

    // ── decodeToString ────────────────────────────────────────────────────────

    @Test
    fun `decodeToString decodes a full-array swatch`() {
        val swatch = Swatch("hello".encodeToByteArray())
        assertEquals("hello", swatch.decodeToString())
    }

    @Test
    fun `decodeToString decodes only the logical slice of a dropFirst view`() {
        val raw = Swatch("Xhello".encodeToByteArray())
        val viewed = raw.dropFirst(1)
        assertEquals("hello", viewed.decodeToString())
    }

    // ── decode(BinaryFormat, DeserializationStrategy) ─────────────────────────

    @Test
    fun `decode hands the backing array directly for a full-array swatch`() {
        val bytes = byteArrayOf(1, 2, 3)
        val swatch = Swatch(bytes)
        val format = CapturingFormat()
        swatch.decode(format, ByteArraySerializer())
        // For a full-array swatch the format receives the same reference — zero-copy.
        assertTrue(format.capturedBytes === bytes)
    }

    @Test
    fun `decode hands a correctly-sliced copy for a dropFirst view`() {
        val raw = byteArrayOf(0xFF.toByte(), 42, 43, 44)
        val swatch = Swatch(raw).dropFirst(1)
        val format = CapturingFormat()
        swatch.decode(format, ByteArraySerializer())
        // Sub-view: the format must receive exactly the logical slice [42, 43, 44].
        assertTrue(format.capturedBytes.contentEquals(byteArrayOf(42, 43, 44)))
    }

    @Test
    fun `decode returns value produced by the format`() {
        // Verify that decode delegates to the format and returns its result.
        val bytes = byteArrayOf(10, 20, 30)
        val swatch = Swatch(bytes)
        // CapturingFormat returns the passed bytes as-is (as T = ByteArray).
        val result: ByteArray = swatch.decode(CapturingFormat(), ByteArraySerializer())
        assertTrue(result.contentEquals(bytes))
    }

    // ── Offset-view equality ─────────────────────────────────────────────────

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
    fun `toByteArray of a dropFirst view returns only the logical bytes`() {
        val raw = Swatch(byteArrayOf(0xFF.toByte(), 42, 43, 44))
        val viewed = raw.dropFirst(1)
        assertTrue(viewed.toByteArray().contentEquals(byteArrayOf(42, 43, 44)))
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
        val bytes1 = byteArrayOf(10, 20, 30)
        val bytes2 = byteArrayOf(10, 20, 30)
        assertTrue(bytes1 !== bytes2, "Precondition: different references")
        assertEquals(Swatch(bytes1), Swatch(bytes2))
    }

    @Test
    fun `frames differ when sender differs`() {
        val bytes = byteArrayOf(1)
        val a = Swatch(bytes, sender = PeerId("alice"))
        val b = Swatch(bytes.copyOf(), sender = PeerId("bob"))
        assertNotEquals(a, b)
    }

    @Test
    fun `frames differ when sequence differs`() {
        val bytes = byteArrayOf(1)
        val a = Swatch(bytes, sequence = 1L)
        val b = Swatch(bytes.copyOf(), sequence = 2L)
        assertNotEquals(a, b)
    }

    @Test
    fun `frames with null sender and non-null sender are not equal`() {
        val bytes = byteArrayOf(1)
        val a = Swatch(bytes, sender = null)
        val b = Swatch(bytes.copyOf(), sender = PeerId("alice"))
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

    // ── byteAt and payloadSize ────────────────────────────────────────────────

    @Test
    fun `payloadSize matches logical length`() {
        assertEquals(3, Swatch(byteArrayOf(1, 2, 3)).payloadSize)
        assertEquals(2, Swatch(byteArrayOf(0, 10, 20)).dropFirst(1).payloadSize)
    }

    @Test
    fun `byteAt returns correct logical bytes`() {
        val swatch = Swatch(byteArrayOf(0xFF.toByte(), 7, 8)).dropFirst(1)
        assertEquals(7, swatch.byteAt(0))
        assertEquals(8, swatch.byteAt(1))
    }
}

/**
 * A [BinaryFormat] spy that captures the exact array reference passed to
 * [decodeFromByteArray]. Returns the bytes cast to [T] — only use with [T] = ByteArray.
 */
private class CapturingFormat : BinaryFormat {

    lateinit var capturedBytes: ByteArray

    override val serializersModule get() = kotlinx.serialization.modules.EmptySerializersModule()

    override fun <T> encodeToByteArray(serializer: SerializationStrategy<T>, value: T): ByteArray =
        throw UnsupportedOperationException("CapturingFormat is receive-only")

    override fun <T> decodeFromByteArray(deserializer: DeserializationStrategy<T>, bytes: ByteArray): T {
        capturedBytes = bytes
        @Suppress("UNCHECKED_CAST")
        return bytes as T
    }
}
