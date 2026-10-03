package us.tractat.kuilt.stream

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.EOFException
import kotlinx.io.RawSink
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.fabric.Hello
import us.tractat.kuilt.core.fabric.HelloAbsentException
import us.tractat.kuilt.core.fabric.HelloBadMagicException
import us.tractat.kuilt.core.fabric.HelloEmptyIdException
import us.tractat.kuilt.core.fabric.HelloFormatException
import us.tractat.kuilt.core.fabric.HelloIdLengthMismatchException
import us.tractat.kuilt.core.fabric.HelloInvalidUtf8Exception
import us.tractat.kuilt.core.fabric.HelloSelfConnectionException
import us.tractat.kuilt.core.fabric.HelloTruncatedException
import us.tractat.kuilt.core.fabric.HelloUnencodableIdException
import us.tractat.kuilt.core.fabric.HelloUnsupportedVersionException
import us.tractat.kuilt.core.fabric.handshaking
import us.tractat.kuilt.test.assertAll
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The drift guard for the TCP wire contract (`docs/tcp-wire.md`).
 *
 * Every expected byte here comes from the golden-vector fixture `kuilt-stream/wire/tcp-wire-v1.vectors.json`,
 * whose hex was written by hand from the spec — never produced by [Hello.encode] or [framed], since a
 * reference built from the code under test would only assert that the code agrees with itself. The
 * fixture is embedded as [TCP_WIRE_V1_VECTORS] so every target can read it; `TcpWireVectorsFixtureTest`
 * (jvmTest) fails if the embedded copy and the checked-in file ever differ.
 *
 * Each test asserts its own population is non-empty, so a fixture edit that empties a section reds
 * rather than passing over nothing.
 */
class TcpWireVectorsTest {

    private val vectors: JsonObject = Json.parseToJsonElement(TCP_WIRE_V1_VECTORS).jsonObject

    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content
    private fun JsonObject.arr(key: String): JsonArray = getValue(key).jsonArray
    private fun JsonObject.hexList(key: String): List<String> = arr(key).map { it.jsonPrimitive.content }
    private fun section(key: String): List<JsonObject> = vectors.arr(key).map { it.jsonObject }

    @Test
    fun fixtureDeclaresTheV1Contract() {
        assertAll(
            { assertEquals("kuilt-tcp-wire-vectors", vectors.str("format")) },
            { assertEquals(1, vectors.getValue("wireVersion").jsonPrimitive.int) },
            { assertEquals(DEFAULT_MAX_FRAME_SIZE, vectors.getValue("maxFrameSize").jsonPrimitive.int) },
        )
    }

    /**
     * Each Hello vector's body is exactly what the fixture's own `helloLayout` table composes for its
     * PeerId. This checks the fixture against itself, independently of kuilt's encoder, so a layout
     * edit (a wider field, say) that misses one hand-written vector reds here.
     */
    @Test
    fun helloVectorsFollowTheFixtureLayout() {
        val hellos = section("hello")
        assertTrue(hellos.isNotEmpty(), "no hello vectors")
        hellos.forEach { v ->
            val composed = Buffer()
            section("helloLayout").forEach { field ->
                val id = v.str("peerId").encodeToByteArray()
                when (field.str("field")) {
                    "idLen" -> when (val encoding = field.str("encoding")) {
                        "u16be" -> composed.writeShort(id.size.toShort())
                        "u32be" -> composed.writeInt(id.size)
                        else -> error("unknown idLen encoding '$encoding'")
                    }
                    "id" -> composed.write(id)
                    else -> composed.write(field.str("value").hexToByteArray())
                }
            }
            assertEquals(v.str("body"), composed.readByteArray().toHexString(), v.str("name"))
        }
    }

    @Test
    fun helloEncodesToTheVectorBytes() = runTest {
        val hellos = section("hello")
        assertTrue(hellos.isNotEmpty(), "no hello vectors")
        hellos.forEach { v ->
            val name = v.str("name")
            val body = Hello.encode(PeerId(v.str("peerId")))
            val wire = Buffer()
            framed(source = Buffer(), sink = wire).send(body)
            assertAll(
                { assertEquals(v.str("body"), body.toHexString(), "$name body") },
                { assertEquals(v.str("frame"), wire.readByteArray().toHexString(), "$name frame") },
            )
        }
    }

    @Test
    fun helloDecodesFromTheVectorBytes() = runTest {
        val hellos = section("hello")
        assertTrue(hellos.isNotEmpty(), "no hello vectors")
        hellos.forEach { v ->
            val received = framed(source = bufferOf(v.str("frame")), sink = Buffer()).incoming.toList()
            assertEquals(1, received.size, "${v.str("name")} frame count")
            assertEquals(PeerId(v.str("peerId")), Hello.decode(received.single()), v.str("name"))
        }
    }

    /** A lone surrogate has no UTF-8 form, so encoding one is the sender's error, refused before any byte is written. */
    @Test
    fun aLoneSurrogateIdIsRefusedBeforeSending() {
        assertFailsWith<HelloUnencodableIdException> { Hello.encode(PeerId("a\uD800")) }
    }

    /** Bodies that are valid v1 Hellos although kuilt never sends them: unknown flag bits are ignored. */
    @Test
    fun helloAcceptsIgnoreUnknownFlags() {
        val accepts = section("helloAccepts")
        assertTrue(accepts.isNotEmpty(), "no hello accept vectors")
        accepts.forEach { v ->
            assertEquals(PeerId(v.str("peerId")), Hello.decode(v.str("body").hexToByteArray()), v.str("name"))
        }
    }

    @Test
    fun payloadFramesMatchTheVectorBytesBothWays() = runTest {
        val frames = section("frames")
        assertTrue(frames.isNotEmpty(), "no frame vectors")
        frames.forEach { v ->
            val name = v.str("name")
            val sent = Buffer()
            framed(source = Buffer(), sink = sent).send(v.str("payload").hexToByteArray())
            val received = framed(source = bufferOf(v.str("frame")), sink = Buffer()).incoming.toList()
            assertAll(
                { assertEquals(v.str("frame"), sent.readByteArray().toHexString(), "$name sent") },
                { assertEquals(listOf(v.str("payload")), received.map { it.toHexString() }, "$name received") },
            )
        }
    }

    /**
     * Reads each stream vector to its end and checks both the frames delivered before the end and
     * how it ended. The ending is asserted by exception TYPE, so a clean close cannot pass for a
     * truncation or the reverse.
     */
    @Test
    fun streamVectorsEndAsTheContractSays() = runTest {
        val streams = section("streams")
        assertTrue(streams.isNotEmpty(), "no stream vectors")
        val endings = streams.map { it.str("end") }.toSet()
        assertEquals(setOf("clean-close", "truncated-frame", "truncated-prefix", "frame-too-large"), endings)
        streams.forEach { v ->
            val name = v.str("name")
            val delivered = mutableListOf<String>()
            val conn = framed(source = bufferOf(v.str("bytes")), sink = Buffer())
            val readAll: suspend () -> Unit = { conn.incoming.collect { delivered += it.toHexString() } }
            when (val end = v.str("end")) {
                // kuilt's v1 receiver ends a stream cut inside a length prefix as a clean close; the
                // contract allows that or an error, and forbids only delivering a partial frame.
                "clean-close", "truncated-prefix" -> readAll()
                "truncated-frame" -> assertFailsWith<EOFException>(name) { readAll() }
                "frame-too-large" -> assertFailsWith<FrameTooLargeException>(name) { readAll() }
                else -> error("$name: unknown end '$end'")
            }
            assertEquals(v.hexList("frames"), delivered, "$name frames delivered before the end")
        }
    }

    /**
     * One side of the two-sided transcript at a time: feed side X everything the OTHER side writes,
     * let X's real [handshaking] seam send its payloads, and check that what X wrote is byte-for-byte
     * X's half of the transcript and that X received the other side's payloads, attributed to it.
     */
    @Test
    fun handshakeTranscriptMatchesBothSides() = runTest {
        val transcript = vectors.getValue("handshake").jsonObject
        val a = transcript.getValue("a").jsonObject
        val b = transcript.getValue("b").jsonObject
        listOf(a to b, b to a).forEach { (self, other) ->
            val written = Buffer()
            val seam = handshaking(
                conn = framed(source = bufferOf(other.str("bytes")), sink = written),
                selfId = PeerId(self.str("peerId")),
                dispatcher = StandardTestDispatcher(testScheduler),
            )
            self.hexList("sends").forEach { seam.broadcast(it.hexToByteArray()) }
            // The other side's bytes end at a frame boundary, so the seam tears down and incoming completes.
            val received = seam.incoming.toList()
            val selfName = self.str("peerId")
            assertAll(
                { assertEquals(self.str("bytes"), written.readByteArray().toHexString(), "$selfName wrote") },
                { assertEquals(other.hexList("sends"), received.map { it.toByteArray().toHexString() }, "$selfName received") },
                { assertTrue(received.all { it.sender == PeerId(other.str("peerId")) }, "$selfName senders") },
            )
            seam.close()
        }
    }

    /**
     * A side writes its Hello before reading anything: with a peer that sends nothing at all, the
     * handshake is refused, and the Hello is already on the wire.
     */
    @Test
    fun helloIsWrittenBeforeThePeersHelloIsRead() = runTest {
        val alice = section("hello").first { it.str("peerId") == "alice" }
        val written = Buffer()
        assertFailsWith<HelloAbsentException> {
            handshaking(
                conn = framed(source = Buffer(), sink = written),
                selfId = PeerId("alice"),
                dispatcher = StandardTestDispatcher(testScheduler),
            )
        }
        assertEquals(alice.str("frame"), written.readByteArray().toHexString())
    }

    /**
     * Each refusal vector is refused with the exception the contract names for it, and with no
     * other. Asserting the exact type is what pins the check ORDER: a body that is both short and
     * foreign must come back as bad magic, not as truncated.
     */
    @Test
    fun helloRefusalsAreRefusedByName() {
        val refusals = section("helloRefusals")
        assertTrue(refusals.isNotEmpty(), "no hello refusal vectors")
        assertEquals(helloRefusalTypes.keys, refusals.map { it.str("refusal") }.toSet(), "every refusal kind has a vector")
        refusals.forEach { v ->
            val name = v.str("name")
            val thrown = assertFailsWith<HelloFormatException>(name) { Hello.decode(v.str("body").hexToByteArray()) }
            assertEquals(helloRefusalTypes.getValue(v.str("refusal")), thrown::class, name)
        }
    }

    /**
     * The handshake-level refusals, through the real [handshaking] over [framed]: the peer's Hello
     * names our own id, the peer closes before any frame, or its first frame is not a Hello. Each is
     * refused by name, and each closes the transport: the contract says a refusing peer closes and
     * sends nothing more, and [ClosingSink] counts the close so a refusal that leaves the socket
     * open reds here.
     */
    @Test
    fun handshakeRefusalsAreRefusedAndClose() = runTest {
        val refusals = section("handshakeRefusals")
        assertEquals(handshakeRefusalTypes.keys, refusals.map { it.str("refusal") }.toSet(), "every refusal kind has a vector")
        refusals.forEach { v ->
            val name = v.str("name")
            val sink = ClosingSink()
            val thrown = assertFailsWith<IllegalArgumentException>(name) {
                handshaking(
                    conn = framed(source = bufferOf(v.str("received")), sink = sink.buffered()),
                    selfId = PeerId(v.str("selfId")),
                    dispatcher = StandardTestDispatcher(testScheduler),
                )
            }
            assertAll(
                { assertEquals(handshakeRefusalTypes.getValue(v.str("refusal")), thrown::class, name) },
                { assertEquals(1, sink.closes, "$name closed the transport") },
            )
        }
    }

    /** A sink that records how often it was closed; what is written to it is discarded. */
    private class ClosingSink : RawSink {
        var closes = 0
        override fun write(source: Buffer, byteCount: Long) = source.skip(byteCount)
        override fun flush() = Unit
        override fun close() {
            closes++
        }
    }

    private val helloRefusalTypes: Map<String, KClass<out HelloFormatException>> = mapOf(
        "bad-magic" to HelloBadMagicException::class,
        "unsupported-version" to HelloUnsupportedVersionException::class,
        "truncated" to HelloTruncatedException::class,
        "id-length-mismatch" to HelloIdLengthMismatchException::class,
        "empty-id" to HelloEmptyIdException::class,
        "invalid-utf8" to HelloInvalidUtf8Exception::class,
    )

    private val handshakeRefusalTypes: Map<String, KClass<out IllegalArgumentException>> = mapOf(
        "self-connection" to HelloSelfConnectionException::class,
        "absent" to HelloAbsentException::class,
        "bad-magic" to HelloBadMagicException::class,
    )

    private fun bufferOf(hex: String): Buffer = Buffer().apply { write(hex.hexToByteArray()) }
}
