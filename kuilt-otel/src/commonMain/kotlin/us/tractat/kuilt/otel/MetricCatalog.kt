package us.tractat.kuilt.otel

import kotlinx.serialization.Serializable
import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.GCounterDouble
import us.tractat.kuilt.crdt.HyperLogLog
import us.tractat.kuilt.crdt.LWWRegister
import us.tractat.kuilt.crdt.Quilted

/**
 * A converged snapshot of every metric series a device holds — counters, gauges, and
 * distinct-count estimates — bundled into one value that replicates as a whole.
 *
 * This is the metric tap's replication surface: the analogue of the log buffer's
 * `Rga<LogRecord>`. It is a **metrics-internal** composite (four maps of per-kind
 * CRDTs); it deliberately does **not** bundle logs — signals replicate as separate
 * values muxed over one transport, never a unified CRDT.
 *
 * [piece] unions the keys of each map and joins matching values by that value's own
 * CRDT lattice, so the composite converges by construction. No observed-remove
 * semantics: buffer eviction is a local cap, never a replicated delete, so a plain
 * grow-merge map is sufficient (a key evicted on the device but already pulled lingers
 * on the puller — acceptable for a diagnostic snapshot).
 */
@Serializable
public class MetricCatalog(
    public val sums: Map<MetricKey, GCounter> = emptyMap(),
    public val doubleSums: Map<MetricKey, GCounterDouble> = emptyMap(),
    public val gauges: Map<MetricKey, LWWRegister<Double>> = emptyMap(),
    public val cardinalities: Map<MetricKey, HyperLogLog> = emptyMap(),
) : Quilted<MetricCatalog> {

    override fun piece(other: MetricCatalog): MetricCatalog = MetricCatalog(
        sums = mergeMaps(sums, other.sums),
        doubleSums = mergeMaps(doubleSums, other.doubleSums),
        gauges = mergeMaps(gauges, other.gauges),
        cardinalities = mergeMaps(cardinalities, other.cardinalities),
    )

    override fun equals(other: Any?): Boolean =
        other is MetricCatalog && sums == other.sums && doubleSums == other.doubleSums &&
            gauges == other.gauges && cardinalities == other.cardinalities

    override fun hashCode(): Int {
        var h = sums.hashCode()
        h = 31 * h + doubleSums.hashCode()
        h = 31 * h + gauges.hashCode()
        h = 31 * h + cardinalities.hashCode()
        return h
    }

    override fun toString(): String =
        "MetricCatalog(sums=$sums, doubleSums=$doubleSums, gauges=$gauges, cardinalities=$cardinalities)"

    private companion object {
        fun <K, S : Quilted<S>> mergeMaps(a: Map<K, S>, b: Map<K, S>): Map<K, S> {
            if (b.isEmpty()) return a
            if (a.isEmpty()) return b
            val out = HashMap<K, S>(a)
            for ((k, v) in b) {
                val current = out[k]
                out[k] = if (current == null) v else current.piece(v)
            }
            return out
        }
    }
}
