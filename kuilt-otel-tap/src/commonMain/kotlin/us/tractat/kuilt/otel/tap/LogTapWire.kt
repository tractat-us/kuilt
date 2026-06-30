@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.otel.tap

import kotlinx.serialization.KSerializer
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.otel.LogRecord

/**
 * The binary codec used on the tap's replication wire.
 *
 * [LogRecord] carries [kotlinx.io.bytestring.ByteString] fields (record/trace/span
 * ids). `alwaysUseByteString` makes CBOR encode them as native byte strings — the
 * same setting the on-device log buffer uses — so a record round-trips byte-for-byte
 * between the device and the joining peer.
 */
internal val LogTapCbor: Cbor = Cbor { alwaysUseByteString = true }

/**
 * The serializer for the replicated log CRDT — an [Rga] of [LogRecord]s. Threaded
 * through the replicator's message serializer; uses [Rga.wireSerializer] so the
 * element type survives CBOR transport.
 */
internal fun logRgaSerializer(): KSerializer<Rga<LogRecord>> =
    Rga.wireSerializer(LogRecord.serializer())
