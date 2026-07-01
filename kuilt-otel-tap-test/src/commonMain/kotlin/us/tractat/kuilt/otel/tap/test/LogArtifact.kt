package us.tractat.kuilt.otel.tap.test

import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.readLine
import kotlinx.io.writeString
import kotlinx.serialization.json.Json
import us.tractat.kuilt.otel.LogRecord
import us.tractat.kuilt.otel.tap.StampedLogRecord

/**
 * Turn a device's extracted logs into a saveable file — one record per line, so a
 * failed CI or simulator run can carry the device's own logs as an artifact you can
 * open, grep, or replay later.
 *
 * The format is **NDJSON** (newline-delimited JSON): every line is one complete
 * [LogRecord] as JSON, and a blank input simply yields no lines. Because each line
 * stands alone, the file streams and appends cleanly, and a downstream tool can read
 * it a record at a time without parsing the whole file.
 */
private val artifactJson: Json = Json {
    // Drop absent (null) fields so each line stays compact and readable; present
    // fields still round-trip back to a LogRecord.
    explicitNulls = false
}

/**
 * The NDJSON lines for [records]: each element is one [LogRecord] serialized to a
 * single line of JSON, in the given order. No trailing newline is added — the
 * sink-writing [writeLogArtifact] wrapper appends the line separators.
 *
 * This is the pure, sink-free core: hand it to any writer, fold it into a string, or
 * assert on it directly in a test.
 */
public fun logArtifactLines(records: List<LogRecord>): Sequence<String> =
    records.asSequence().map { artifactJson.encodeToString(LogRecord.serializer(), it) }

/**
 * Write [records] to [sink] as an NDJSON artifact: one JSON [LogRecord] per line,
 * newline-terminated, in order. One call per booted device produces one file.
 *
 * Uses a kotlinx-io [Sink] so the same writer runs on every target — a file on the
 * JVM/Android/Native, an in-memory buffer in a test. The sink is flushed before
 * returning; closing it remains the caller's responsibility (the caller owns the
 * file/connection lifecycle).
 */
public fun writeLogArtifact(records: List<LogRecord>, sink: Sink) {
    for (line in logArtifactLines(records)) {
        sink.writeString(line)
        sink.writeString("\n")
    }
    sink.flush()
}

/**
 * The **stamped** NDJSON lines for [stamped]: each element is one [StampedLogRecord]
 * — the log record plus its ordering stamp (producer [us.tractat.kuilt.crdt.ReplicaId]
 * and cross-device total-order key) — serialized to a single line of JSON, in order.
 *
 * This is the merge-oriented artifact form. Where [logArtifactLines] writes the plain,
 * OTLP-shaped record for a single device, this variant additionally carries the
 * [StampedLogRecord.rgaId] each line needs to be interleaved with lines from *other*
 * devices into one timeline. A collector concatenates every device's stamped artifact
 * and sorts the union on [StampedLogRecord.rgaId]. No trailing newline is added.
 */
public fun stampedLogArtifactLines(stamped: List<StampedLogRecord>): Sequence<String> =
    stamped.asSequence().map { artifactJson.encodeToString(StampedLogRecord.serializer(), it) }

/**
 * Write [stamped] to [sink] as a stamped NDJSON artifact: one JSON [StampedLogRecord]
 * per line, newline-terminated, in order. The stamped counterpart of [writeLogArtifact]
 * — use it when the artifact will be merged across devices (see [stampedLogArtifactLines]).
 *
 * The sink is flushed before returning; closing it remains the caller's responsibility.
 */
public fun writeStampedLogArtifact(stamped: List<StampedLogRecord>, sink: Sink) {
    for (line in stampedLogArtifactLines(stamped)) {
        sink.writeString(line)
        sink.writeString("\n")
    }
    sink.flush()
}

/**
 * Decode stamped NDJSON [lines] back into [StampedLogRecord]s, the inverse of
 * [stampedLogArtifactLines] (`parseStampedLogArtifactLines(stampedLogArtifactLines(x)) == x`).
 * Blank lines are skipped, so the trailing newline [writeStampedLogArtifact] emits does
 * not decode as a phantom record.
 *
 * This is the pure, source-free core: hand it any sequence of lines — a file read
 * elsewhere, the union of several devices' artifacts, or literal test strings.
 */
public fun parseStampedLogArtifactLines(lines: Sequence<String>): List<StampedLogRecord> =
    lines.filter { it.isNotBlank() }
        .map { artifactJson.decodeFromString(StampedLogRecord.serializer(), it) }
        .toList()

/**
 * Read a stamped NDJSON artifact from [source]: the inverse of [writeStampedLogArtifact],
 * returning the [StampedLogRecord]s in file order. Reads to end-of-source a line at a
 * time, so a consumer merging across devices decodes each `*.ndjson` back without
 * touching [StampedLogRecord.serializer] directly.
 *
 * Reading is the caller's boundary to own the file/connection lifecycle — the [source]
 * is drained but not closed.
 */
public fun readStampedLogArtifact(source: Source): List<StampedLogRecord> =
    parseStampedLogArtifactLines(generateSequence { source.readLine() })
