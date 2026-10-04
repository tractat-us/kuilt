package us.tractat.kuilt.stream

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.EOFException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.fabric.Connection
import us.tractat.kuilt.core.fabric.handshaking
import us.tractat.kuilt.test.assertAll
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * After a good handshake, every way a stream can end closes the transport from kuilt's side, exactly
 * once, without waiting for the application to close the seam (`docs/tcp-wire.md` sections 5 and 6).
 *
 * Each `streams` vector from the golden fixture is fed to a woven [handshaking] seam right behind the
 * remote's Hello. A cut stream (an oversize length prefix, a truncated body, EOF inside a prefix) must
 * tear the seam down with [CloseReason.Error] carrying the read error by type, so it cannot pass for a
 * clean close; EOF at a frame boundary is the control and must tear down with
 * [CloseReason.RemoteRequested]. Either way the frames before the end are delivered.
 *
 * The transport close is counted at the [Connection] the seam was given ([CountingConnection]), BEFORE
 * the test closes the seam: that is the whole defect, since a later `Seam.close` always closed it.
 */
class TcpWireReadErrorCloseTest {

    private val vectors: JsonObject = Json.parseToJsonElement(TCP_WIRE_V1_VECTORS).jsonObject

    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content
    private fun section(key: String): List<JsonObject> = vectors.getValue(key).jsonArray.map { it.jsonObject }

    private val remoteHello: JsonObject = section("hello").first { it.str("peerId") == "alice" }

    @Test
    fun aCutStreamAfterTheHandshakeClosesTheTransportAndTearsWithTheReadError() = runTest {
        val cut = section("streams").filter { it.str("end") != "clean-close" }
        assertEquals(
            setOf("truncated-frame", "frame-too-large"),
            cut.map { it.str("end") }.toSet(),
            "both error endings have vectors",
        )
        cut.forEach { v ->
            val outcome = driveStream(v)
            val expectedType = errorTypes.getValue(v.str("end"))
            val reason = (outcome.terminal as? SeamState.Torn)?.reason
            assertAll(
                { assertEquals(expectedFrames(v), outcome.delivered, "${outcome.name} frames before the end") },
                { assertEquals(1, outcome.closesBeforeSeamClose, "${outcome.name} closed the transport before Seam.close") },
                { assertEquals(1, outcome.closesAfterSeamClose, "${outcome.name} closed the transport exactly once") },
                {
                    assertTrue(
                        reason is CloseReason.Error && expectedType.isInstance(reason.throwable),
                        "${outcome.name} tears with Error(${expectedType.simpleName}), got $reason",
                    )
                },
            )
        }
    }

    @Test
    fun aCleanCloseAfterTheHandshakeClosesTheTransportAndTearsWithoutAnError() = runTest {
        val clean = section("streams").filter { it.str("end") == "clean-close" }
        assertTrue(clean.isNotEmpty(), "no clean-close stream vectors")
        clean.forEach { v ->
            val outcome = driveStream(v)
            assertAll(
                { assertEquals(expectedFrames(v), outcome.delivered, "${outcome.name} frames before the end") },
                { assertEquals(1, outcome.closesBeforeSeamClose, "${outcome.name} closed the transport before Seam.close") },
                { assertEquals(1, outcome.closesAfterSeamClose, "${outcome.name} closed the transport exactly once") },
                { assertEquals(SeamState.Torn(CloseReason.RemoteRequested), outcome.terminal, outcome.name) },
            )
        }
    }

    /**
     * The same distinction one frame earlier: a Hello frame cut short is a broken stream, so the
     * handshake throws the read error itself rather than reporting the Hello as merely absent, and
     * still closes the transport.
     */
    @Test
    fun aHelloCutShortThrowsTheReadErrorAndCloses() = runTest {
        val cutHello = remoteHello.str("frame").dropLast(4)
        val conn = CountingConnection(framed(source = bufferOf(cutHello), sink = Buffer()))
        assertFailsWith<EOFException> { handshaking(conn, PeerId("zoe"), StandardTestDispatcher(testScheduler)) }
        assertEquals(1, conn.closes, "a refused handshake closes the transport")
    }

    private class Outcome(
        val name: String,
        val delivered: List<String>,
        val terminal: SeamState,
        val closesBeforeSeamClose: Int,
        val closesAfterSeamClose: Int,
    )

    private suspend fun TestScope.driveStream(v: JsonObject): Outcome {
        val conn = CountingConnection(
            framed(source = bufferOf(remoteHello.str("frame") + v.str("bytes")), sink = Buffer()),
        )
        val seam: Seam = handshaking(conn, PeerId("zoe"), StandardTestDispatcher(testScheduler))
        // Precondition: the handshake wove a live seam, so what follows is the post-handshake path.
        assertEquals(SeamState.Woven, seam.state.value, "${v.str("name")} wove")
        val delivered = seam.incoming.toList().map { it.toByteArray().toHexString() }
        val terminal = seam.state.first { it is SeamState.Torn }
        testScheduler.advanceUntilIdle()
        val before = conn.closes
        seam.close()
        testScheduler.advanceUntilIdle()
        return Outcome(v.str("name"), delivered, terminal, before, conn.closes)
    }

    private fun expectedFrames(v: JsonObject): List<String> =
        v.getValue("frames").jsonArray.map { it.jsonPrimitive.content }

    /** Counts [close] calls on the link the seam was handed; delegates everything. */
    private class CountingConnection(private val delegate: Connection) : Connection {
        var closes = 0
        override suspend fun send(frame: ByteArray) = delegate.send(frame)
        override val incoming: Flow<ByteArray> get() = delegate.incoming
        override val maxFrameBytes: Int? get() = delegate.maxFrameBytes
        override suspend fun close() {
            closes++
            delegate.close()
        }
    }

    private val errorTypes: Map<String, KClass<out Throwable>> = mapOf(
        "truncated-frame" to EOFException::class,
        "frame-too-large" to FrameTooLargeException::class,
    )

    private fun bufferOf(hex: String): Buffer = Buffer().apply { write(hex.hexToByteArray()) }
}
