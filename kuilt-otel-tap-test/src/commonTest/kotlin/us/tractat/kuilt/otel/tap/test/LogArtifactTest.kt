package us.tractat.kuilt.otel.tap.test

import kotlinx.io.Buffer
import kotlinx.io.bytestring.ByteString
import kotlinx.io.readString
import kotlinx.serialization.json.Json
import us.tractat.kuilt.otel.LogRecord
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogArtifactTest {

    private fun record(i: Int): LogRecord =
        LogRecord(
            recordId = ByteString(ByteArray(8) { i.toByte() }),
            severityNumber = 9,
            severityText = "INFO",
            body = "log line $i",
            attributes = mapOf("logger.name" to "Test", "seq" to i.toString()),
        )

    @Test
    fun oneNdjsonLinePerRecordInOrder() {
        val records = (1..3).map { record(it) }

        val lines = logArtifactLines(records).toList()

        assertAll(
            { assertEquals(3, lines.size) },
            { assertTrue(lines.none { it.contains('\n') }, "each record is a single line") },
            { assertEquals(records.map { it.body }, lines.map { Json.decodeFromString(LogRecord.serializer(), it).body }) },
        )
    }

    @Test
    fun emptyRecordsYieldNoLines() {
        assertTrue(logArtifactLines(emptyList()).toList().isEmpty())
    }

    @Test
    fun writeLogArtifactRoundTripsThroughSink() {
        val records = (1..4).map { record(it) }
        val buffer = Buffer()

        writeLogArtifact(records, buffer)

        val text = buffer.readString()
        val parsed = text.trimEnd('\n').lineSequence()
            .map { Json.decodeFromString(LogRecord.serializer(), it) }
            .toList()
        assertAll(
            { assertTrue(text.endsWith('\n'), "newline-terminated") },
            { assertEquals(4, parsed.size) },
            { assertEquals(records.map { it.recordId }, parsed.map { it.recordId }, "byte fields survive") },
            { assertEquals(records, parsed, "full round-trip") },
        )
    }
}
