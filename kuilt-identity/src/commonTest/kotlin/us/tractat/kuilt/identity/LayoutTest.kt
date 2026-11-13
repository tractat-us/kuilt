package us.tractat.kuilt.identity

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The byte layout every signed statement is made of. The expected bytes in each golden vector are
 * written out by hand, field by field — never derived from the encoder, which would make the vector
 * assert `x == x` — and every vector carries a negative control: the same bytes with one header or
 * length byte changed must decode to `null`, so the comparison is shown to be able to fail.
 */
class LayoutTest {
    @Test
    fun encodesTheDocumentedBytes() {
        val fields = listOf(byteArrayOf(1, 2), byteArrayOf())
        val expected = (
            "0007" + // tag length, u16 big-endian
                "6b75696c742f74" + // "kuilt/t", ASCII
                "01" + // version
                "0002" + "0102" + // field 0: length, bytes
                "0000" // field 1: length, no bytes
            ).hexToByteArray()
        assertVector(TAG, 1, fields, expected, mutateAt = VERSION_OFFSET)
    }

    @Test
    fun encodesBothBytesOfALengthPrefix() {
        // 300 = 0x012c: an encoder writing only the low byte, or little-endian, gives another prefix.
        val fields = listOf(ByteArray(300) { 0xab.toByte() })
        val expected = ("0007" + "6b75696c742f74" + "01" + "012c" + "ab".repeat(300)).hexToByteArray()
        assertVector(TAG, 1, fields, expected, mutateAt = FIRST_FIELD_OFFSET)
    }

    @Test
    fun encodesAStatementWithNoFields() {
        val expected = ("0007" + "6b75696c742f74" + "02").hexToByteArray()
        assertVector(TAG, 2, emptyList(), expected, mutateAt = 1)
    }

    @Test
    fun decodeInvertsEncode() {
        val cases = listOf(
            emptyList(),
            listOf(byteArrayOf()),
            listOf(byteArrayOf(0), byteArrayOf(-1, 0, 1), byteArrayOf()),
            listOf(ByteArray(Layout.MAX_FIELD_SIZE) { it.toByte() }),
        )
        assertAll(
            *cases.map { fields ->
                {
                    val decoded = Layout.decode(Layout.encode("kuilt/round-trip", 7, fields), "kuilt/round-trip", 7, fields.size)
                    assertNotNull(decoded, "decode of ${fields.size} fields")
                    assertEquals(fields.map { it.toHexString() }, decoded.map { it.toHexString() })
                }
            }.toTypedArray(),
        )
    }

    @Test
    fun decodeRejects() {
        val bytes = Layout.encode(TAG, 1, listOf(byteArrayOf(1, 2), byteArrayOf()))
        assertAll(
            // The precondition: every case below differs from this one in exactly the way it names.
            { assertNotNull(Layout.decode(bytes, TAG, 1, 2), "the unaltered bytes decode") },
            { assertNull(Layout.decode(bytes, "kuilt/u", 1, 2), "another tag, same length") },
            { assertNull(Layout.decode(bytes, "kuilt/tt", 1, 2), "another tag, longer") },
            { assertNull(Layout.decode(bytes, "kuilt/", 1, 2), "another tag, a prefix of this one") },
            { assertNull(Layout.decode(bytes.withByte(1, 0x06), TAG, 1, 2), "a wrong tag length prefix") },
            { assertNull(Layout.decode(bytes, TAG, 2, 2), "another version") },
            { assertNull(Layout.decode(bytes.withByte(VERSION_OFFSET, 0x02), TAG, 1, 2), "a wrong version byte") },
            { assertNull(Layout.decode(bytes, TAG, 1, 1), "a field count one short") },
            { assertNull(Layout.decode(bytes, TAG, 1, 3), "a field count one long") },
            { assertNull(Layout.decode(bytes.copyOf(bytes.size - 1), TAG, 1, 2), "a truncated length prefix") },
            { assertNull(Layout.decode(bytes.withByte(bytes.size - 1, 0x01), TAG, 1, 2), "a length running past the end") },
            { assertNull(Layout.decode(bytes.withByte(FIRST_FIELD_OFFSET, 0x01), TAG, 1, 2), "a high length byte running past the end") },
            { assertNull(Layout.decode(bytes + 0, TAG, 1, 2), "one trailing byte") },
            { assertNull(Layout.decode(bytes.copyOf(VERSION_OFFSET), TAG, 1, 2), "a header missing its version") },
            { assertNull(Layout.decode(byteArrayOf(0), TAG, 1, 2), "half a tag length prefix") },
            { assertNull(Layout.decode(byteArrayOf(), TAG, 1, 0), "no bytes at all") },
        )
    }

    @Test
    fun encodeRefusesAnOversizeField() {
        assertAll(
            { assertFailsWith<IllegalArgumentException> { Layout.encode(TAG, 1, listOf(ByteArray(Layout.MAX_FIELD_SIZE + 1))) } },
            // The control: one byte smaller is the largest field a u16 prefix can carry, and encodes.
            { assertEquals(HEADER_SIZE + 2 + 65_535, Layout.encode(TAG, 1, listOf(ByteArray(65_535))).size) },
        )
    }

    @Test
    fun encodeRefusesAHeaderItCannotWrite() {
        assertAll(
            { assertFailsWith<IllegalArgumentException>("non-ASCII tag") { Layout.encode("kuilt/é", 1, emptyList()) } },
            { assertFailsWith<IllegalArgumentException>("version 256") { Layout.encode(TAG, 256, emptyList()) } },
            { assertFailsWith<IllegalArgumentException>("version -1") { Layout.encode(TAG, -1, emptyList()) } },
            { assertEquals(HEADER_SIZE, Layout.encode(TAG, 255, emptyList()).size, "version 255 is the largest byte") },
        )
    }

    @Test
    fun splitSeparatesTheTrailingSignature() {
        val layout = "0007 6b75696c742f74 01".replace(" ", "").hexToByteArray()
        val signature = ByteArray(Layout.SIGNATURE_SIZE) { (it + 1).toByte() }
        val split = Layout.split(layout + signature)
        assertNotNull(split)
        assertAll(
            { assertContentEquals(layout, split.first, "layout") },
            { assertContentEquals(signature, split.second, "signature") },
            { assertEquals(64, Layout.SIGNATURE_SIZE, "raw r‖s for P-256") },
        )
    }

    @Test
    fun splitRefusesAWireShorterThanASignature() {
        assertAll(
            { assertNull(Layout.split(ByteArray(63)), "63 bytes") },
            { assertNull(Layout.split(byteArrayOf()), "no bytes") },
            // The control: exactly one signature is a split with an empty layout, which decode then refuses.
            {
                val split = assertNotNull(Layout.split(ByteArray(64)), "64 bytes")
                assertAll(
                    { assertEquals(0, split.first.size) },
                    { assertEquals(64, split.second.size) },
                )
            },
        )
    }

    private fun assertVector(tag: String, version: Int, fields: List<ByteArray>, expected: ByteArray, mutateAt: Int) {
        val encoded = Layout.encode(tag, version, fields)
        val mutated = expected.withByte(mutateAt, expected[mutateAt] + 1)
        assertAll(
            { assertEquals(expected.toHexString(), encoded.toHexString(), "encoded bytes") },
            {
                val decoded = Layout.decode(expected, tag, version, fields.size)
                assertNotNull(decoded, "the vector decodes")
                assertEquals(fields.map { it.toHexString() }, decoded.map { it.toHexString() }, "decoded fields")
            },
            // The negative control: one byte changed, and both directions notice.
            { assertFalse(mutated.contentEquals(encoded), "the mutated vector differs from the encoding") },
            { assertNull(Layout.decode(mutated, tag, version, fields.size), "the mutated vector does not decode") },
        )
    }

    private fun ByteArray.withByte(index: Int, value: Int): ByteArray = copyOf().also { it[index] = value.toByte() }

    private companion object {
        const val TAG = "kuilt/t"

        /** `u16` tag length + 7 tag bytes. */
        const val VERSION_OFFSET = 2 + 7

        /** The header, then the first field's length prefix starts. */
        const val HEADER_SIZE = VERSION_OFFSET + 1
        const val FIRST_FIELD_OFFSET = HEADER_SIZE
    }
}
