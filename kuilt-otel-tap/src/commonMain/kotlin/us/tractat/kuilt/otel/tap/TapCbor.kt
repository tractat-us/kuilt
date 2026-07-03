@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.otel.tap

import kotlinx.serialization.cbor.Cbor

/**
 * The binary codec used on every tap's replication wire (log and metric alike).
 *
 * The tapped buffers carry [kotlinx.io.bytestring.ByteString] fields — a [LogRecord][us.tractat.kuilt.otel.LogRecord]'s
 * record/trace/span ids, a metric's `HyperLogLog` register arrays. `alwaysUseByteString`
 * makes CBOR encode them as native byte strings — the same setting the on-device buffers
 * use — so state round-trips byte-for-byte between the device and the joining peer.
 */
internal val TapCbor: Cbor = Cbor { alwaysUseByteString = true }
