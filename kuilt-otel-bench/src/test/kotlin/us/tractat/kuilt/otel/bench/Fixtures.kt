@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.otel.bench

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.LWWRegister
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.otel.MetricKey
import us.tractat.kuilt.otel.MetricKind
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.StoreKey

val BENCH_REPLICA: ReplicaId = ReplicaId("bench-replica")

const val SUM_NAME: String = "http.server.requests"
const val GAUGE_NAME: String = "queue.depth"

/** Series `i` of the sum arm: one name, two attributes, the shape a server's request counter has. */
fun sumKey(i: Int): MetricKey = MetricKey(SUM_NAME, MetricKind.SUM, sumAttributes(i))

fun sumAttributes(i: Int): Map<String, String> = mapOf("http.route" to "/api/r$i", "http.status_code" to "200")

fun gaugeKey(i: Int): MetricKey = MetricKey(GAUGE_NAME, MetricKind.GAUGE, mapOf("queue" to "q$i"))

/**
 * The encoding `WarpMetricExporter` persists with — the same `Cbor` configuration and map
 * serializers. The bench uses it twice: to seed a store with N series in O(N) (seeding through
 * `incrementSum` costs O(N²), which at 10k series is minutes), and to price one whole-map encode
 * on its own.
 *
 * It is a copy, so it can drift. What catches that: every arm recovers the exporter from the seeded
 * store and checks it then holds exactly N series. A changed store key or wire shape yields zero, and
 * the arm fails before it measures anything.
 */
object MetricEncoding {
    val cbor: Cbor = Cbor { alwaysUseByteString = true }
    val sums = MapSerializer(MetricKey.serializer(), GCounter.serializer())
    val gauges = MapSerializer(MetricKey.serializer(), LWWRegister.serializer(Double.serializer()))

    // WarpMetricExporter's private store keys.
    val SUM_STORE_KEY: StoreKey = StoreKey("otel.metrics.sums")
    val GAUGE_STORE_KEY: StoreKey = StoreKey("otel.metrics.gauges")
}

/** A sum counter holding [count] on [BENCH_REPLICA]. */
fun counterOf(count: Long): GCounter = GCounter.ZERO.inc(BENCH_REPLICA, count).delta

/** Sum series `0 until n`, series `i` holding `values(i)`. */
fun sumsMap(n: Int, values: (Int) -> Long = { 1L }): Map<MetricKey, GCounter> =
    (0 until n).associateTo(LinkedHashMap()) { sumKey(it) to counterOf(values(it)) }

fun gaugesMap(n: Int): Map<MetricKey, LWWRegister<Double>> =
    (0 until n).associateTo(LinkedHashMap()) { gaugeKey(it) to LWWRegister.empty<Double>().set(BENCH_REPLICA, 1L, 0.0) }

suspend fun seedSums(store: DurableStore, sums: Map<MetricKey, GCounter>) {
    store.write(MetricEncoding.SUM_STORE_KEY, MetricEncoding.cbor.encodeToByteArray(MetricEncoding.sums, sums))
}

suspend fun seedGauges(store: DurableStore, gauges: Map<MetricKey, LWWRegister<Double>>) {
    store.write(MetricEncoding.GAUGE_STORE_KEY, MetricEncoding.cbor.encodeToByteArray(MetricEncoding.gauges, gauges))
}
