package us.tractat.kuilt.core.fabric

import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The v1 [Hello] preamble body: `kuil` magic, `u8` version, `u16` flags, `u16` id length, UTF-8 id.
 *
 * Every malformed body is built by [helloBody], a surrogate for [Hello.encode] (which refuses to
 * emit most of them) whose receipt is [theTestSurrogateMatchesTheRealEncoder]. The one deliberate
 * exception is [encodesTheV1LayoutByteForByte], a literal, so a change to the production constants
 * cannot quietly move its expectation with it.
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
            { assertEquals(6L, body.readUnsignedBe(HELLO_ID_LENGTH_OFFSET, HELLO_ID_LENGTH_BYTES)) },
            { assertEquals(id, Hello.decode(body)) },
        )
    }

    /**
     * `idLen` is UNSIGNED: an id of 0x8000 bytes or more sets its top bit, so a signed read would
     * see a negative length and refuse the largest ids v1 promises to carry.
     */
    @Test
    fun idsWithTheTopLengthBitSetRoundTrip() {
        assertAll(
            *listOf(0x8000, HELLO_MAX_ID_BYTES).map { size ->
                {
                    val id = PeerId("x".repeat(size))
                    assertEquals(id, Hello.decode(Hello.encode(id)), "$size-byte id")
                }
            }.toTypedArray(),
        )
    }

    /** Flags are must-ignore: a v1 receiver accepts a body whatever bits are set. */
    @Test
    fun unknownFlagBitsAreIgnored() {
        assertAll(
            { assertEquals(PeerId("a"), Hello.decode(helloBody(flags = 0x8000, id = "a"))) },
            { assertEquals(PeerId("a"), Hello.decode(helloBody(flags = 0xFFFF, id = "a"))) },
            { assertEquals(PeerId("a"), Hello.decode(helloBody(flags = 0x0001, id = "a"))) },
        )
    }

    @Test
    fun encodeRefusesAnIdThatDoesNotFitV1() {
        assertAll(
            { assertFailsWith<IllegalArgumentException> { Hello.encode(PeerId("")) } },
            { assertFailsWith<IllegalArgumentException> { Hello.encode(PeerId("x".repeat(HELLO_MAX_ID_BYTES + 1))) } },
            { assertEquals(HELLO_MAX_ID_BYTES + HELLO_HEADER_BYTES, Hello.encode(PeerId("x".repeat(HELLO_MAX_ID_BYTES))).size) },
        )
    }

    /** A lone surrogate would be encoded leniently as U+FFFD, so the peer would learn a different id. */
    @Test
    fun encodeRefusesAnIdThatIsNotValidUtf16() {
        assertFailsWith<HelloUnencodableIdException> { Hello.encode(PeerId("a\uD800b")) }
    }

    /**
     * A pre-v1 peer sends its id as bare UTF-8. It must be refused as a foreign peer, never misread
     * as a [PeerId] — at any length, including one shorter than the magic — and the refusal shows
     * the bytes that stood where the magic should be.
     */
    @Test
    fun aPreV1BareUtf8IdIsRefusedAsBadMagic() {
        assertAll(
            {
                val refused = assertFailsWith<HelloBadMagicException> { Hello.decode("node-42".encodeToByteArray()) }
                assertEquals("6e6f6465", refused.receivedHex)
                assertTrue("6e6f6465" in refused.message.orEmpty(), refused.message)
            },
            {
                val refused = assertFailsWith<HelloBadMagicException> { Hello.decode("ab".encodeToByteArray()) }
                assertEquals("6162", refused.receivedHex)
            },
            {
                assertFailsWith<HelloBadMagicException> {
                    Hello.decode(helloBody(magic = "kuim".encodeToByteArray(), id = "node-42"))
                }
            },
        )
    }

    /** A body framed twice opens with its inner length prefix, which the refusal makes visible. */
    @Test
    fun aDoublyFramedBodyIsRefusedAsBadMagicShowingTheLengthPrefix() {
        val body = Hello.encode(PeerId("a"))
        val doublyFramed = ByteArray(4).also { it.writeUnsignedBe(body.size.toLong(), 0, 4) } + body
        val refused = assertFailsWith<HelloBadMagicException> { Hello.decode(doublyFramed) }
        assertEquals("0000000a", refused.receivedHex)
    }

    /** A pre-v1 id that happens to start with `kuil` passes the magic, so the byte after it is the version. */
    @Test
    fun aPreV1IdStartingWithTheMagicIsRefusedByVersion() {
        assertFailsWith<HelloUnsupportedVersionException> { Hello.decode("kuilt-peer".encodeToByteArray()) }
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

    /**
     * The version is checked as soon as its byte has arrived, before the rest of the header: a short
     * body from a different version is named as a version mismatch, not as truncation. A later
     * version may well have a shorter header.
     */
    @Test
    fun theVersionIsRefusedBeforeTheHeaderIsComplete() {
        val body = helloBody(version = 2, id = "a")
        assertAll(
            *(HELLO_VERSION_OFFSET + HELLO_VERSION_BYTES until HELLO_HEADER_BYTES).map { size ->
                {
                    assertFailsWith<HelloUnsupportedVersionException>("$size-byte body") { Hello.decode(body.copyOf(size)) }
                    Unit
                }
            }.toTypedArray(),
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
            // Declares one more than it carries.
            {
                val refused = assertFailsWith<HelloIdLengthMismatchException> { Hello.decode(helloBody(idLen = 3, id = "ab")) }
                assertEquals(3, refused.declaredIdLength)
                assertEquals(2, refused.remainingBytes)
            },
            // Declares one fewer: a trailing byte.
            { assertFailsWith<HelloIdLengthMismatchException> { Hello.decode(helloBody(idLen = 1, id = "ab")) } },
            // Declares zero but carries bytes: the length mismatch is named before the empty id.
            { assertFailsWith<HelloIdLengthMismatchException> { Hello.decode(helloBody(idLen = 0, id = "ab")) } },
            // The largest declarable length, against a one-byte id.
            { assertFailsWith<HelloIdLengthMismatchException> { Hello.decode(helloBody(idLen = 0xFFFF, id = "a")) } },
        )
    }

    @Test
    fun anEmptyIdIsRefused() {
        assertFailsWith<HelloEmptyIdException> { Hello.decode(helloBody(id = "")) }
    }

    /**
     * Strict UTF-8: overlong encodings (`C0 80`, `E0 80 80`), a UTF-16 surrogate (`ED A0 80`), a code
     * point past U+10FFFF (`F4 90 80 80`), a lone continuation byte (`80`), and a truncated sequence
     * (`E2 82`). A lenient decode would fold each onto U+FFFD, so distinct ids would collide.
     */
    @Test
    fun invalidUtf8IsRefusedNotReplaced() {
        val invalid = listOf(
            bytes(0xC0, 0x80),
            bytes(0xE0, 0x80, 0x80),
            bytes(0xED, 0xA0, 0x80),
            bytes(0xF4, 0x90, 0x80, 0x80),
            bytes(0x80),
            bytes(0xE2, 0x82),
        )
        assertAll(
            *invalid.map { id ->
                {
                    assertFailsWith<HelloInvalidUtf8Exception>(id.joinToString(" ") { (it.toInt() and 0xff).toString(16) }) {
                        Hello.decode(helloBody(idBytes = id))
                    }
                    Unit
                }
            }.toTypedArray(),
        )
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
    flags: Int = HELLO_V1_FLAGS,
    idBytes: ByteArray,
    idLen: Int = idBytes.size,
): ByteArray {
    require(magic.size == HELLO_MAGIC_BYTES) { "the surrogate varies field values, not the magic's width" }
    return ByteArray(HELLO_HEADER_BYTES + idBytes.size).also { buf ->
        magic.copyInto(buf)
        buf.writeUnsignedBe(version.toLong(), HELLO_VERSION_OFFSET, HELLO_VERSION_BYTES)
        buf.writeUnsignedBe(flags.toLong(), HELLO_FLAGS_OFFSET, HELLO_FLAGS_BYTES)
        buf.writeUnsignedBe(idLen.toLong(), HELLO_ID_LENGTH_OFFSET, HELLO_ID_LENGTH_BYTES)
        idBytes.copyInto(buf, destinationOffset = HELLO_HEADER_BYTES)
    }
}

/** [helloBody] with a UTF-8 [id]. */
internal fun helloBody(
    magic: ByteArray = HELLO_MAGIC,
    version: Int = HELLO_WIRE_VERSION,
    flags: Int = HELLO_V1_FLAGS,
    id: String,
    idLen: Int = id.encodeToByteArray().size,
): ByteArray = helloBody(magic = magic, version = version, flags = flags, idBytes = id.encodeToByteArray(), idLen = idLen)
