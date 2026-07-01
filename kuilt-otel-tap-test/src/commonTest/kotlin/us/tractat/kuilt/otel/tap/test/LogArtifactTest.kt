package us.tractat.kuilt.otel.tap.test

import kotlinx.io.Buffer
import kotlinx.io.bytestring.ByteString
import kotlinx.io.readString
import kotlinx.serialization.json.Json
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.RgaId
import us.tractat.kuilt.otel.LogRecord
import us.tractat.kuilt.otel.tap.StampedLogRecord
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

    private fun stamped(replica: String, lamport: Long, seq: Long, i: Int): StampedLogRecord =
        StampedLogRecord(RgaId(lamport = lamport, replicaId = ReplicaId(replica), seq = seq), record(i))

    @Test
    fun stampedLinesRoundTripCarryingTheStamp() {
        val input = listOf(
            stamped("A", lamport = 1, seq = 1, i = 1),
            stamped("A", lamport = 2, seq = 2, i = 2),
        )

        val lines = stampedLogArtifactLines(input).toList()
        val parsed = lines.map { Json.decodeFromString(StampedLogRecord.serializer(), it) }

        assertAll(
            { assertEquals(2, lines.size) },
            { assertTrue(lines.none { it.contains('\n') }, "each stamped record is a single line") },
            { assertEquals(input, parsed, "record AND stamp survive the round-trip") },
            { assertTrue(lines.first().contains("\"replicaId\""), "the producer id is present in the line") },
        )
    }

    @Test
    fun stampedArtifactsFromTwoDevicesMergeByRgaId() {
        // Two devices' artifacts, each in its own FIFO order; the collector concatenates
        // and sorts on rgaId to recover one cross-device total order.
        val deviceA = Buffer().also {
            writeStampedLogArtifact(listOf(stamped("A", 1, 1, 1), stamped("A", 3, 2, 3)), it)
        }
        val deviceB = Buffer().also {
            writeStampedLogArtifact(listOf(stamped("B", 2, 1, 2), stamped("B", 4, 2, 4)), it)
        }

        val merged = (deviceA.readString() + deviceB.readString())
            .trimEnd('\n').lineSequence()
            .map { Json.decodeFromString(StampedLogRecord.serializer(), it) }
            .sortedBy { it.rgaId }
            .toList()

        assertEquals(
            listOf("log line 1", "log line 2", "log line 3", "log line 4"),
            merged.map { it.record.body },
            "interleaved into one (lamport, replicaId) order, per-producer FIFO intact",
        )
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
