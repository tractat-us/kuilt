@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.otel.tap

import kotlinx.serialization.KSerializer
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.otel.MetricCatalog

/**
 * The binary codec used on the metric tap's replication wire.
 *
 * `alwaysUseByteString` matches the on-device metric buffer's CBOR settings so the
 * `HyperLogLog` register arrays round-trip byte-for-byte between the device and the
 * joining peer.
 */
internal val MetricTapCbor: Cbor = Cbor { alwaysUseByteString = true }

/**
 * The serializer for the replicated metric composite — a [MetricCatalog]. Threaded
 * through the replicator's message serializer.
 */
internal fun metricCatalogSerializer(): KSerializer<MetricCatalog> = MetricCatalog.serializer()
