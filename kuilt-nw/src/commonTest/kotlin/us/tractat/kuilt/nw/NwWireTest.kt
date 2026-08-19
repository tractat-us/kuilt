package us.tractat.kuilt.nw

import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The self-describing `:kuilt-nw` frame body (#2425 slice 1) — a leading TYPE byte, plus a VERSION
 * byte inside the `NwHello`.
 *
 * Before this, hello-vs-data was **positional**: the first frame on a connection was assumed to be
 * the hello and everything after it data. Nothing on the wire said which was which, so a `GOODBYE`
 * had nowhere to live and two states — a hello arriving late, a data frame arriving early — were
 * silently possible rather than refusable.
 *
 * The type byte lives **inside** the payload `encodeFrame` wraps, so the framing itself
 * (`[len_be32][payload]`, byte-identical to `:kuilt-stream`'s `framed()`) is untouched.
 */
class NwWireTest {

    // ── the discriminator itself ─────────────────────────────────────────────────

    @Test
    fun everyFrameTypeCarriesAnExplicitStableCodeThatIsNotItsOrdinal() {
        assertAll(
            { assertEquals(0x01, NwFrameType.Hello.code.toInt(), "HELLO is 0x01 on the wire, forever") },
            { assertEquals(0x02, NwFrameType.Data.code.toInt(), "DATA is 0x02 on the wire, forever") },
            { assertEquals(0x03, NwFrameType.Goodbye.code.toInt(), "GOODBYE is 0x03 on the wire, forever") },
            {
                assertEquals(
                    NwFrameType.entries.size,
                    NwFrameType.entries.map { it.code }.toSet().size,
                    "two types sharing one code would make the discriminator ambiguous",
                )
            },
            {
                // The receipt for "do not rely on ordinal": if a code merely COINCIDED with its
                // ordinal, an ordinal-derived encoder would be indistinguishable from this one, and
                // reordering the enum would silently renumber the wire.
                assertTrue(
                    NwFrameType.entries.none { it.code.toInt() == it.ordinal },
                    "no type's code may equal its ordinal — codes are declared, never derived",
                )
            },
        )
    }

    @Test
    fun eachFrameTypeRoundTripsThroughItsOwnEncoder() {
        val id = PeerId("peer-alice")
        val nonce = ByteArray(NONCE_BYTES) { (it * 5).toByte() }
        val payload = "the-consumer-payload".encodeToByteArray()
        val dataFrame = NwWire.encodeData(payload)

        assertAll(
            { assertEquals(NwWireFrame.Hello(NwHello(id, nonce)), NwWire.decode(NwWire.encodeHello(id, nonce))) },
            { assertIs<NwWireFrame.Data>(NwWire.decode(dataFrame)) },
            { assertEquals(NwWireFrame.Goodbye, NwWire.decode(NwWire.encodeGoodbye())) },
            // The DATA body is the payload verbatim, after exactly one type byte. Pinned
            // byte-for-byte rather than via a decode, because the seam strips it zero-copy
            // (`Swatch.dropFirst(TYPE_BYTES)`) and never materialises it — so the constant, not a
            // second extractor, is what the two sides agree on.
            { assertContentEquals(byteArrayOf(NwFrameType.Data.code) + payload, dataFrame) },
            { assertEquals(1, NwWire.TYPE_BYTES, "one byte of type, and the seam strips exactly that many") },
        )
    }

    @Test
    fun theFramingItselfIsUntouched() {
        // The whole point of putting the type byte INSIDE the payload: `encodeFrame` still writes
        // `[len_be32][payload]`, so the framing stays wire-compatible with `:kuilt-stream`'s
        // `framed()` and `NwFramer` needs no change at all.
        val payload = "abc".encodeToByteArray()
        val framed = encodeFrame(NwWire.encodeData(payload))
        val frames = NwFramer().decode(framed)

        assertAll(
            {
                assertContentEquals(
                    byteArrayOf(0, 0, 0, (payload.size + NwWire.TYPE_BYTES).toByte()),
                    framed.copyOfRange(0, Int.SIZE_BYTES),
                    "still a plain 4-byte big-endian length prefix — the type byte is counted BY it, not before it",
                )
            },
            { assertEquals(1, frames.size, "one frame in, one frame out") },
            { assertIs<NwWireFrame.Data>(NwWire.decode(frames.single())) },
        )
    }

    // ── refusals ─────────────────────────────────────────────────────────────────

    @Test
    fun anUnknownTypeByteIsRefusedNamingTheOffendingCode() {
        listOf(0x04, 0x05, 0x7F, 0x80, 0xFF).forEach { code ->
            val failure = assertFailsWith<NwUnknownFrameTypeException>(
                "type code 0x${code.toString(16)} is unassigned and must be refused, not guessed at",
            ) {
                NwWire.decode(byteArrayOf(code.toByte()) + "body".encodeToByteArray())
            }
            assertEquals(code.toByte(), failure.code, "the refusal must name the byte it could not classify")
        }
    }

    @Test
    fun aPreFlagDayUntypedHelloIsRefusedAndSaysWhy() {
        // THE MIRROR DIRECTION of the flag day: a NEW peer receiving an OLD peer's hello. The old
        // format was `[idLen_be32][id][nonce]` with no type byte, and the first byte of a
        // big-endian id length is 0x00 for every id shorter than 16 MiB — so the old hello reads as
        // frame type 0x00 here. It must fail with a message that NAMES the version break rather
        // than a confusing "malformed frame".
        val legacy = legacyUntypedHello(PeerId("peer-from-an-old-build"), ByteArray(NONCE_BYTES) { 9 })
        val failure = assertFailsWith<NwUnknownFrameTypeException> { NwWire.decode(legacy) }

        assertAll(
            { assertEquals(0, failure.code.toInt(), "an old hello's leading byte is the id length's top byte") },
            {
                assertTrue(
                    failure.message.orEmpty().contains(LEGACY_HINT),
                    "the refusal must say the peer is on an older, pre-typed wire — got: ${failure.message}",
                )
            },
        )
    }

    @Test
    fun aHelloWhoseWireVersionThisBuildDoesNotKnowIsRefusedNamingBothVersions() {
        listOf(0, NW_WIRE_VERSION + 1, 0xFF).forEach { version ->
            val failure = assertFailsWith<NwUnsupportedWireVersionException>("version $version must be refused") {
                NwWire.decode(helloAtVersion(version, PeerId("peer-1"), ByteArray(NONCE_BYTES) { 1 }))
            }
            assertAll(
                { assertEquals(version, failure.remoteVersion, "the refusal must name what the remote speaks") },
                { assertEquals(NW_WIRE_VERSION, failure.localVersion, "…and what this build speaks") },
                {
                    assertTrue(
                        failure.message.orEmpty().let { "v$version" in it && "v$NW_WIRE_VERSION" in it },
                        "both versions belong in the message — got: ${failure.message}",
                    )
                },
            )
        }
    }

    @Test
    fun aFrameTooShortToHoldTheTypeByteIsRefused() {
        assertFailsWith<NwTruncatedFrameException>("an empty frame has no type byte to read") {
            NwWire.decode(ByteArray(0))
        }
    }

    @Test
    fun aHelloWithNoVersionByteIsRefused() {
        // The version byte is the FIRST thing in the hello body, so its absence is a truncation and
        // not an unknown version — there is nothing to name.
        assertFailsWith<NwTruncatedFrameException> { NwWire.decode(byteArrayOf(NwFrameType.Hello.code)) }
    }

    // ── the shapes that must stay legal ──────────────────────────────────────────

    @Test
    fun aDataFrameOfZeroLengthPayloadIsLegalAndIsNotAnEmptyFrame() {
        val frame = NwWire.encodeData(ByteArray(0))
        assertAll(
            { assertEquals(NwWire.TYPE_BYTES, frame.size, "a zero-length payload is exactly the type byte") },
            { assertIs<NwWireFrame.Data>(NwWire.decode(frame), "an empty payload is a frame, not a fault") },
            // …and it stays distinguishable from a frame carrying no type byte at all, which is not.
            { assertFailsWith<NwTruncatedFrameException> { NwWire.decode(ByteArray(0)) } },
        )
    }

    @Test
    fun aGoodbyeIsExactlyOneByteAndTolerantOfATrailingBody() {
        assertAll(
            { assertContentEquals(byteArrayOf(NwFrameType.Goodbye.code), NwWire.encodeGoodbye()) },
            { assertEquals(NwWireFrame.Goodbye, NwWire.decode(NwWire.encodeGoodbye())) },
            {
                assertEquals(
                    NwWireFrame.Goodbye,
                    NwWire.decode(byteArrayOf(NwFrameType.Goodbye.code, 7, 7, 7)),
                    "a GOODBYE body is reserved: a later slice may add a reason, and a build that " +
                        "does not know it must ignore it rather than tear the link over it",
                )
            },
        )
    }

    // ---------------------------------------------------------------- fixtures

    /**
     * A hello frame declaring [version] — assembled by hand, because [NwHello.encode] always writes
     * [NW_WIRE_VERSION] and so cannot produce the frame a differently-versioned peer sends.
     */
    private fun helloAtVersion(version: Int, id: PeerId, nonce: ByteArray): ByteArray {
        val idBytes = id.value.encodeToByteArray()
        return byteArrayOf(NwFrameType.Hello.code, version.toByte()) +
            beInt(idBytes.size) + idBytes + nonce
    }

    /** Exactly what a pre-#2425 build put on the wire: `[idLen_be32][id][nonce]`, untyped, unversioned. */
    private fun legacyUntypedHello(id: PeerId, nonce: ByteArray): ByteArray {
        val idBytes = id.value.encodeToByteArray()
        return beInt(idBytes.size) + idBytes + nonce
    }

    private fun beInt(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private companion object {
        /** The substring that makes a legacy peer's refusal self-explaining rather than merely red. */
        const val LEGACY_HINT = "older build"
    }
}
