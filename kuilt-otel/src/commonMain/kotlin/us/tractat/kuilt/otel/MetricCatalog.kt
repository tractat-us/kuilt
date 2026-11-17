package us.tractat.kuilt.otel

import kotlinx.serialization.Serializable
import us.tractat.kuilt.crdt.CanonicalMapSerializer
import us.tractat.kuilt.crdt.DDSketch
import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.GCounterDouble
import us.tractat.kuilt.crdt.HyperLogLog
import us.tractat.kuilt.crdt.LWWMap
import us.tractat.kuilt.crdt.LWWRegister
import us.tractat.kuilt.crdt.Quilted
import us.tractat.kuilt.crdt.ReplicaId

/**
 * A converged snapshot of every metric series a device holds — counters, gauges,
 * distinct-count estimates, and quantile sketches — bundled into one value that
 * replicates as a whole.
 *
 * This is the metric tap's replication surface: the analogue of the log buffer's
 * `Rga<LogRecord>`. It is a **metrics-internal** composite (seven maps of per-kind
 * CRDTs); it deliberately does **not** bundle logs — signals replicate as separate
 * values muxed over one transport, never a unified CRDT.
 *
 * [piece] unions the keys of each map and joins matching values by that value's own
 * CRDT lattice, so the composite converges by construction. No observed-remove
 * semantics: buffer eviction is a local cap, never a replicated delete, so a plain
 * grow-merge map is sufficient (a key evicted on the device but already pulled lingers
 * on the puller — acceptable for a diagnostic snapshot).
 *
 * Equal catalogs encode to equal bytes, because a `Quilter` hashes this encoding for its anti-entropy
 * root. [piece] builds its maps through a `HashMap`, so each one goes through a canonical serializer,
 * which also leans on [MetricKey] encoding canonically in its own right.
 *
 * The two up-down maps are empty by default and an empty map is not encoded, so a catalog holding
 * none encodes as it did before they existed. One holding any is refused by a reader built before
 * them, as an unknown field.
 */
@Serializable
public class MetricCatalog(
    @Serializable(with = CanonicalMapSerializer::class)
    public val sums: Map<MetricKey, GCounter> = emptyMap(),
    @Serializable(with = CanonicalMapSerializer::class)
    public val doubleSums: Map<MetricKey, GCounterDouble> = emptyMap(),
    @Serializable(with = CanonicalMapSerializer::class)
    public val gauges: Map<MetricKey, LWWRegister<Double>> = emptyMap(),
    @Serializable(with = CanonicalMapSerializer::class)
    public val cardinalities: Map<MetricKey, HyperLogLog> = emptyMap(),
    @Serializable(with = CanonicalMapSerializer::class)
    public val histograms: Map<MetricKey, DDSketch> = emptyMap(),
    @Serializable(with = CanonicalMapSerializer::class)
    public val upDownSums: Map<MetricKey, LWWMap<ReplicaId, Long>> = emptyMap(),
    @Serializable(with = CanonicalMapSerializer::class)
    public val doubleUpDownSums: Map<MetricKey, LWWMap<ReplicaId, Double>> = emptyMap(),
) : Quilted<MetricCatalog> {

    /**
     * The five-map constructor this class had before the up-down maps, kept so code compiled
     * against it still links. Hidden, so new code resolves to the primary constructor, and the
     * serializer uses the primary too.
     */
    @Deprecated("Binary compatibility only; use the primary constructor.", level = DeprecationLevel.HIDDEN)
    public constructor(
        sums: Map<MetricKey, GCounter> = emptyMap(),
        doubleSums: Map<MetricKey, GCounterDouble> = emptyMap(),
        gauges: Map<MetricKey, LWWRegister<Double>> = emptyMap(),
        cardinalities: Map<MetricKey, HyperLogLog> = emptyMap(),
        histograms: Map<MetricKey, DDSketch> = emptyMap(),
    ) : this(sums, doubleSums, gauges, cardinalities, histograms, emptyMap(), emptyMap())

    override fun piece(other: MetricCatalog): MetricCatalog = MetricCatalog(
        sums = mergeMaps(sums, other.sums),
        doubleSums = mergeMaps(doubleSums, other.doubleSums),
        gauges = mergeMaps(gauges, other.gauges),
        cardinalities = mergeMaps(cardinalities, other.cardinalities),
        histograms = mergeMaps(histograms, other.histograms),
        upDownSums = mergeMaps(upDownSums, other.upDownSums),
        doubleUpDownSums = mergeMaps(doubleUpDownSums, other.doubleUpDownSums),
    )

    override fun equals(other: Any?): Boolean =
        other is MetricCatalog && sums == other.sums && doubleSums == other.doubleSums &&
            gauges == other.gauges && cardinalities == other.cardinalities &&
            histograms == other.histograms && upDownSums == other.upDownSums &&
            doubleUpDownSums == other.doubleUpDownSums

    override fun hashCode(): Int {
        var h = sums.hashCode()
        h = 31 * h + doubleSums.hashCode()
        h = 31 * h + gauges.hashCode()
        h = 31 * h + cardinalities.hashCode()
        h = 31 * h + histograms.hashCode()
        h = 31 * h + upDownSums.hashCode()
        h = 31 * h + doubleUpDownSums.hashCode()
        return h
    }

    override fun toString(): String =
        "MetricCatalog(sums=$sums, doubleSums=$doubleSums, gauges=$gauges, " +
            "cardinalities=$cardinalities, histograms=$histograms, upDownSums=$upDownSums, " +
            "doubleUpDownSums=$doubleUpDownSums)"
}

/** Key-union merge of two maps of CRDTs: shared keys join by the value's own lattice. */
private fun <K, S : Quilted<S>> mergeMaps(a: Map<K, S>, b: Map<K, S>): Map<K, S> {
    if (b.isEmpty()) return a
    if (a.isEmpty()) return b
    val out = HashMap<K, S>(a)
    for ((k, v) in b) {
        val current = out[k]
        out[k] = if (current == null) v else current.piece(v)
    }
    return out
}
