package us.tractat.kuilt.core.fabric

import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The v1 [Hello] preamble body: `kuil` magic, version, `u32` big-endian id length, UTF-8 id.
 *
 * Every malformed body is built by [helloBody], a width-unconstrained surrogate for [Hello.encode]
 * (which refuses to emit most of them), so a layout change is one edit there.
 * [encodesTheV1LayoutByteForByte] is the one deliberate exception: a literal, so a change to the
 * production constants cannot quietly move the expectation with it.
 */
class HelloTest {

    @Test
    fun encodeDecodeRoundTrips() {
        val id = PeerId("node-42")
        assertEquals(id, Hello.decode(Hello.encode(id)))
    }

    @Test
    fun encodesTheV1LayoutByteForByte() {
        assertContentEquals(
            bytes(0x6B, 0x75, 0x69, 0x6C, 0x01, 0x00, 0x00, 0x00, 0x02, 0x61, 0x62),
            Hello.encode(PeerId("ab")),
        )
    }

    /** The surrogate's receipt: at default arguments it is byte-identical to the real encoder. */
    @Test
    fun theTestSurrogateMatchesTheRealEncoder() {
        assertContentEquals(Hello.encode(PeerId("node-42")), helloBody(id = "node-42"))
    }

    /** The id length counts UTF-8 bytes, not chars: `é` is two bytes, `🧵` is four. */
    @Test
    fun idLengthCountsUtf8BytesNotChars() {
        val id = PeerId("é🧵")
        val body = Hello.encode(id)
        assertAll(
            {
                assertEquals(
                    6L,
                    body.readUnsignedBe(HELLO_ID_LENGTH_OFFSET, HELLO_ID_LENGTH_BYTES),
                )
            },
            { assertEquals(id, Hello.decode(body)) },
        )
    }

    @Test
    fun encodeRefusesAnEmptyId() {
        assertFailsWith<IllegalArgumentException> { Hello.encode(PeerId("")) }
    }

    /**
     * A pre-v1 peer sends its id as bare UTF-8. It must be refused as a foreign peer, never misread
     * as a [PeerId] — at any length, including one shorter than the magic.
     */
    @Test
    fun aPreV1BareUtf8IdIsRefusedAsBadMagic() {
        assertAll(
            { assertFailsWith<HelloBadMagicException> { Hello.decode("node-42".encodeToByteArray()) } },
            { assertFailsWith<HelloBadMagicException> { Hello.decode("ab".encodeToByteArray()) } },
            {
                assertFailsWith<HelloBadMagicException> {
                    Hello.decode(helloBody(magic = "kuim".encodeToByteArray(), id = "node-42"))
                }
            },
        )
    }

    /** A pre-v1 id that happens to start with `kuil` passes the magic, so the bytes after it are the version. */
    @Test
    fun aPreV1IdStartingWithTheMagicIsRefusedByVersion() {
        assertFailsWith<HelloUnsupportedVersionException> {
            Hello.decode("kuilt-peer".encodeToByteArray())
        }
    }

    @Test
    fun anUnsupportedVersionNamesBothVersions() {
        val refused = assertFailsWith<HelloUnsupportedVersionException> {
            Hello.decode(helloBody(version = 2, id = "a"))
        }
        val message = refused.message.orEmpty()
        assertAll(
            { assertEquals(2, refused.receivedVersion) },
            { assertEquals(HELLO_WIRE_VERSION, refused.supportedVersion) },
            { assertTrue("v2" in message, "message names the received version: $message") },
            { assertTrue("v$HELLO_WIRE_VERSION" in message, "message names the supported version: $message") },
        )
    }

    /** Every prefix of a valid header that still matches the magic is refused as truncated. */
    @Test
    fun aBodyTooShortForTheHeaderIsRefusedAsTruncated() {
        val header = helloBody(id = "").copyOf(HELLO_HEADER_BYTES)
        assertAll(
            *(0 until HELLO_HEADER_BYTES).map { size ->
                {
                    assertFailsWith<HelloTruncatedException>("$size-byte body") { Hello.decode(header.copyOf(size)) }
                    Unit
                }
            }.toTypedArray(),
        )
    }

    /** v1 is strict: `idLen` must account for every byte after the header, no more and no fewer. */
    @Test
    fun anIdLengthDisagreeingWithTheBodyIsRefused() {
        assertAll(
            // Declares 3, carries 2.
            { assertFailsWith<HelloIdLengthMismatchException> { Hello.decode(helloBody(idLen = 3, id = "ab")) } },
            // Declares 1, carries 2: a trailing byte.
            { assertFailsWith<HelloIdLengthMismatchException> { Hello.decode(helloBody(idLen = 1, id = "ab")) } },
            // Top bit set: read unsigned, so a mismatch rather than a negative length.
            {
                val refused = assertFailsWith<HelloIdLengthMismatchException> {
                    Hello.decode(helloBody(idLen = 0xFFFF_FFFFL, id = "a"))
                }
                assertEquals(0xFFFF_FFFFL, refused.declaredIdLength)
            },
        )
    }

    @Test
    fun anEmptyIdIsRefused() {
        assertFailsWith<HelloEmptyIdException> { Hello.decode(helloBody(id = "")) }
    }

    /** `0xC3 0x28` is an invalid two-byte sequence; a lenient decode would yield `U+FFFD(`. */
    @Test
    fun invalidUtf8IsRefusedNotReplaced() {
        assertFailsWith<HelloInvalidUtf8Exception> { Hello.decode(helloBody(idBytes = bytes(0xC3, 0x28))) }
    }

    /** Each malformation is refused by its own named type, all under one sealed parent. */
    @Test
    fun eachMalformationHasItsOwnNamedRefusal() {
        val refusals = listOf(
            "node-42".encodeToByteArray(),
            ByteArray(0),
            helloBody(version = 9, id = "a"),
            helloBody(idLen = 5, id = "a"),
            helloBody(id = ""),
            helloBody(idBytes = bytes(0xFF)),
        )
        assertEquals(
            listOf(
                HelloBadMagicException::class,
                HelloTruncatedException::class,
                HelloUnsupportedVersionException::class,
                HelloIdLengthMismatchException::class,
                HelloEmptyIdException::class,
                HelloInvalidUtf8Exception::class,
            ),
            refusals.map { frame -> assertFailsWith<HelloFormatException> { Hello.decode(frame) }::class },
        )
    }

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}

/**
 * A [Hello] body with any field forced to any value — the surrogate every malformed-body test builds
 * through. Field widths and offsets come from the production `HELLO_*` constants; only the values
 * are free. [idLen] defaults to the true length of [idBytes].
 */
internal fun helloBody(
    magic: ByteArray = HELLO_MAGIC,
    version: Int = HELLO_WIRE_VERSION,
    idBytes: ByteArray,
    idLen: Long = idBytes.size.toLong(),
): ByteArray {
    require(magic.size == HELLO_MAGIC_BYTES) { "the surrogate varies field values, not the magic's width" }
    return ByteArray(HELLO_HEADER_BYTES + idBytes.size).also { buf ->
        magic.copyInto(buf)
        buf.writeUnsignedBe(version.toLong(), HELLO_VERSION_OFFSET, HELLO_VERSION_BYTES)
        buf.writeUnsignedBe(idLen, HELLO_ID_LENGTH_OFFSET, HELLO_ID_LENGTH_BYTES)
        idBytes.copyInto(buf, destinationOffset = HELLO_HEADER_BYTES)
    }
}

/** [helloBody] with a UTF-8 [id]. */
internal fun helloBody(
    magic: ByteArray = HELLO_MAGIC,
    version: Int = HELLO_WIRE_VERSION,
    id: String,
    idLen: Long = id.encodeToByteArray().size.toLong(),
): ByteArray = helloBody(magic = magic, version = version, idBytes = id.encodeToByteArray(), idLen = idLen)
