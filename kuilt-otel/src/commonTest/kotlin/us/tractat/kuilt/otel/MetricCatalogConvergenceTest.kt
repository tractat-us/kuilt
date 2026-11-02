package us.tractat.kuilt.otel

import us.tractat.kuilt.conformance.lattice.LatticeLawHarness
import us.tractat.kuilt.conformance.lattice.LatticeLawSuite
import us.tractat.kuilt.conformance.lattice.LatticeOp
import us.tractat.kuilt.conformance.lattice.OpKind
import us.tractat.kuilt.conformance.lattice.VacuityFloors
import us.tractat.kuilt.crdt.DDSketch
import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.GCounterDouble
import us.tractat.kuilt.crdt.HyperLogLog
import us.tractat.kuilt.crdt.LWWRegister
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece
import kotlin.random.Random

/** Names that share one `String.hashCode`, plus one that does not. See the binding's KDoc. */
private val NAMES: List<String> = listOf("Aa", "BB", "C#", "d")

private fun Random.key(kind: MetricKind): MetricKey = MetricKey(NAMES.random(this), kind)

private fun replica(replicaIndex: Int): ReplicaId = ReplicaId("R$replicaIndex")

/**
 * [MetricCatalog] under the lattice and byte laws (#2715): the metric tap replicates it through a
 * `Quilter`, whose anti-entropy root is a hash over this encoding.
 *
 * ## What the knobs switch off
 *
 * - **[NAMES].** The byte law can only fail where a map has more than one iteration order. `"Aa"`,
 *   `"BB"` and `"C#"` collide into one JVM `HashMap` bin, which keeps insertion order, so the fold
 *   order shows through on the JVM as well. Ordinary names would leave this suite blind there.
 * - **`labelled`'s attribute order, drawn per replica.** Two replicas write one logical key with its
 *   attributes in opposite orders, so whichever instance survives the merge decides the bytes unless
 *   [MetricKey] canonicalises them itself.
 * - **HyperLogLog precision 4.** The default sketch encodes to about 12 KiB, element by element, and
 *   this suite encodes thousands of pool states per seed. The sketch's size has no bearing on the
 *   property under test, which is the order of the map around it.
 */
internal class MetricCatalogConvergenceTest : LatticeLawSuite<MetricCatalog>() {
    override fun newHarness(): LatticeLawHarness<MetricCatalog> = LatticeLawHarness(
        initial = MetricCatalog(),
        alphabet = listOf(
            LatticeOp("sum", OpKind.ASSERT) { state, replicaIndex, random ->
                val key = random.key(MetricKind.SUM)
                val cell = state.sums[key] ?: GCounter.ZERO
                state.piece(MetricCatalog(sums = mapOf(key to cell.inc(replica(replicaIndex)).delta)))
            },
            LatticeOp("labelled", OpKind.ASSERT) { state, replicaIndex, _ ->
                val pairs = listOf("a" to "1", "b" to "2")
                val key = MetricKey(
                    "labelled",
                    MetricKind.SUM,
                    linkedMapOf(*(if (replicaIndex % 2 == 0) pairs else pairs.reversed()).toTypedArray()),
                )
                val cell = state.sums[key] ?: GCounter.ZERO
                state.piece(MetricCatalog(sums = mapOf(key to cell.inc(replica(replicaIndex)).delta)))
            },
            LatticeOp("doubleSum", OpKind.ASSERT) { state, replicaIndex, random ->
                val key = random.key(MetricKind.SUM)
                val cell = state.doubleSums[key] ?: GCounterDouble.of()
                state.piece(MetricCatalog(doubleSums = mapOf(key to cell.inc(replica(replicaIndex), 1.5).delta)))
            },
            LatticeOp("gauge", OpKind.ASSERT) { state, replicaIndex, random ->
                val key = random.key(MetricKind.GAUGE)
                val cell = LWWRegister.empty<Double>().set(
                    replica(replicaIndex),
                    random.nextLong(1L, 1L shl 40),
                    random.nextInt(100).toDouble(),
                )
                state.piece(MetricCatalog(gauges = mapOf(key to cell)))
            },
            LatticeOp("cardinality", OpKind.ASSERT) { state, _, random ->
                val key = random.key(MetricKind.CARDINALITY)
                val cell = state.cardinalities[key] ?: HyperLogLog.empty(precision = 4)
                state.piece(MetricCatalog(cardinalities = mapOf(key to cell.piece(cell.add("u${random.nextLong()}")))))
            },
            LatticeOp("histogram", OpKind.ASSERT) { state, replicaIndex, random ->
                val key = random.key(MetricKind.EXPONENTIAL_HISTOGRAM)
                val cell = state.histograms[key] ?: DDSketch.empty()
                val value = random.nextInt(1, 1_000).toDouble()
                state.piece(MetricCatalog(histograms = mapOf(key to cell.add(replica(replicaIndex), value).delta)))
            },
        ),
        // No RETIRE op: every field only grows. Eviction is a local cap and is never replicated, as
        // MetricCatalog's own KDoc states, so nothing here takes a value back.
        floors = VacuityFloors.NOTHING_TO_RETIRE,
        serializer = MetricCatalog.serializer(),
        replicaCount = 3,
        opsPerReplica = 8,
    )
}
