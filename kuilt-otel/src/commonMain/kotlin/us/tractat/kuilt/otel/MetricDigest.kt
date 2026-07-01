package us.tractat.kuilt.otel

/**
 * The value-version this producer last delivered for each metric series.
 *
 * Metrics differ from spans and logs: a series' value moves (a [MetricKind.SUM]
 * climbs, a [MetricKind.GAUGE] overwrites), so identity ([MetricKey]) is not enough
 * — the digest also carries a hash of the last-delivered value
 * ([MetricPoint.valueHash]). A series is re-sent only when its current value-hash
 * differs from [versions].
 *
 * Producer-local, same as [LogDigest] and [SpanDigest]: it records what this producer
 * has already delivered to this endpoint, not what the collector holds.
 *
 * @param versions the last-delivered [MetricPoint.valueHash] per [MetricKey].
 */
public class MetricDigest(public val versions: Map<MetricKey, Long>)
