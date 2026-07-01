package us.tractat.kuilt.otel

import kotlinx.io.bytestring.ByteString

/**
 * Which log records the producer has already delivered to an [OtlpEdge], keyed by
 * [LogRecord.recordId].
 *
 * Mirrors [SpanDigest]. Like spans, a [LogRecord] is immutable once written and
 * content-addressed by its 8-byte record id, so the digest is a flat id-set and the
 * drain delta is `local recordIds ∖ digest.recordIds`.
 *
 * ## Producer-local
 *
 * The digest is **not** a query of what the collector holds (OTLP/HTTP is
 * write-only). It is what *this* producer has already POSTed to *this* endpoint,
 * persisted locally by the edge. The collector deduplicates by record id, so a
 * lost or stale digest costs bandwidth, never correctness.
 *
 * @param recordIds the raw 8-byte record ids ([LogRecord.recordId]) already delivered.
 */
public class LogDigest(public val recordIds: Set<ByteString>)
