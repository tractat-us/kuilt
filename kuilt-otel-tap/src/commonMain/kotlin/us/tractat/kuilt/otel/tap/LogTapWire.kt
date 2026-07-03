@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.otel.tap

import kotlinx.serialization.KSerializer
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.otel.LogRecord

/**
 * The serializer for the replicated log CRDT — an [Rga] of [LogRecord]s. Threaded
 * through the replicator's message serializer; uses [Rga.wireSerializer] so the
 * element type survives CBOR transport.
 */
internal fun logRgaSerializer(): KSerializer<Rga<LogRecord>> =
    Rga.wireSerializer(LogRecord.serializer())
