@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.otel.tap

import kotlinx.serialization.KSerializer
import us.tractat.kuilt.otel.MetricCatalog

/**
 * The serializer for the replicated metric composite — a [MetricCatalog]. Threaded
 * through the replicator's message serializer.
 */
internal fun metricCatalogSerializer(): KSerializer<MetricCatalog> = MetricCatalog.serializer()
