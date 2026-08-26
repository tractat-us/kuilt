// deliberate real-threading test: clear() is fenced against a concurrent mutation by writeMutex, and a stale encoded snapshot landing after the clear is only observable on a genuine multi-threaded dispatcher, which virtual-time runTest cannot provide.

package us.tractat.kuilt.otel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.store.StoreKey
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins that [WarpMetricExporter.clear] is fenced against a concurrent mutation (#2232).
 *
 * Every other metric test is single-threaded `runTest`, which is exactly why this race went
 * unseen: the exporter encoded its whole map under a non-suspending lock and then persisted
 * **outside** it, so a mutation could encode the pre-clear map, suspend, and land its stale
 * bytes after a [WarpMetricExporter.clear] had already deleted the keys.
 *
 * **Why that had to be fixed here rather than left as the pre-existing defect it was.** Before
 * `clear()` existed the same stale write was self-healing — the next mutation re-encodes the
 * whole map and overwrites it. After a clear, nothing writes that key again unless the same
 * metric kind is used afresh, so the resurrection is permanent and a restart brings back every
 * pre-clear series.
 *
 * Two arms. The **gated** arm drives the exact interleaving deterministically. The **stochastic**
 * arm runs real concurrent mutations across several kinds and asserts the store and the live
 * exporter still agree, which catches shapes the gate does not model.
 */
class WarpMetricExporterClearConcurrencyTest {

    private val replicaA = ReplicaId("A")
    private val sumKey = MetricKey("requests", MetricKind.SUM)

    /**
     * Parks the first [write] until released, and reports when the first [delete] is seen.
     *
     * The delete is [WarpMetricExporter.clear]'s first store interaction, so observing one means
     * the clear got past the mutation this store is holding hostage — which is precisely the
     * interleaving under test.
     */
    private class GatedWriteStore : DurableStore {
        private val backing = InMemoryDurableStore()
        private val gate = CompletableDeferred<Unit>()
        val firstWriteEntered: CompletableDeferred<Unit> = CompletableDeferred()
        val firstDeleteObserved: CompletableDeferred<Unit> = CompletableDeferred()
        private var gated = true

        fun releaseWrite() {
            gate.complete(Unit)
        }

        override suspend fun read(key: StoreKey): ByteArray? = backing.read(key)

        override suspend fun write(key: StoreKey, bytes: ByteArray) {
            if (gated) {
                gated = false
                firstWriteEntered.complete(Unit)
                gate.await()
            }
            backing.write(key, bytes)
        }

        override suspend fun delete(key: StoreKey) {
            firstDeleteObserved.complete(Unit)
            backing.delete(key)
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun aMutationThatEncodedBeforeAClearCannotLandItsStaleBytesAfterIt() {
        val dispatcher = newFixedThreadPoolContext(THREADS, "otel-metric-clear-gate")
        try {
            runBlocking {
                val store = GatedWriteStore()
                val exporter = WarpMetricExporter(replica = replicaA, store = store)

                // Encodes the whole map, then parks inside the store's write.
                val mutation = launch(dispatcher) { exporter.incrementSum(sumKey, by = 5L) }
                store.firstWriteEntered.await()

                val clearing = launch(dispatcher) { exporter.clear() }
                // Release the parked write once the clear has reached its deletes — or, if the
                // clear is properly fenced and therefore never will, after a bounded wait. This
                // is a scheduling nudge, not an assertion: it cannot false-red, because a longer
                // wait only makes the fenced path more certainly correct.
                withTimeoutOrNull(GATE_BOUND_MS) { store.firstDeleteObserved.await() }
                store.releaseWrite()

                mutation.join()
                clearing.join()

                val recovered = WarpMetricExporter(replica = replicaA, store = store)
                recovered.recover()
                assertEquals(
                    0L,
                    recovered.sumValue(sumKey),
                    "a mutation that encoded the pre-clear map must not land after the clear " +
                        "deleted the keys — nothing rewrites that key afterwards, so the " +
                        "resurrection would be permanent",
                )
            }
        } finally {
            dispatcher.close()
        }
    }

    private class VariableLatencyStore(seed: Int) : DurableStore {
        private val backing = InMemoryDurableStore()
        private val rng = kotlin.random.Random(seed)
        override suspend fun read(key: StoreKey): ByteArray? = backing.read(key)
        override suspend fun write(key: StoreKey, bytes: ByteArray) {
            repeat(rng.nextInt(MAX_YIELDS)) { yield() }
            backing.write(key, bytes)
        }
        override suspend fun delete(key: StoreKey) = backing.delete(key)
        private companion object { private const val MAX_YIELDS = 6 }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun theStoreAndTheLiveExporterAgreeWhenAClearRacesConcurrentMutations() {
        val dispatcher = newFixedThreadPoolContext(THREADS, "otel-metric-clear-stress")
        try {
            runBlocking {
                repeat(REPEATS) { iter ->
                    val store = VariableLatencyStore(iter)
                    val exporter = WarpMetricExporter(replica = replicaA, store = store)

                    // Several kinds, because a stale write to one key can be healed by a later
                    // mutation of that same kind; spreading the run across kinds leaves the
                    // damage somewhere no later write covers.
                    val mutations = (0 until CONCURRENT).map { i ->
                        launch(dispatcher) {
                            when (i % KINDS) {
                                0 -> exporter.incrementSum(MetricKey("sum-$i", MetricKind.SUM), by = 1L)
                                1 -> exporter.setGauge(
                                    MetricKey("gauge-$i", MetricKind.GAUGE),
                                    value = i.toDouble(),
                                    timestamp = 1_000L + i,
                                )
                                else -> exporter.addCardinality(
                                    MetricKey("card-$i", MetricKind.CARDINALITY),
                                    "element-$i",
                                )
                            }
                        }
                    }
                    exporter.clear()
                    mutations.joinAll()

                    val recovered = WarpMetricExporter(replica = replicaA, store = store)
                    recovered.recover()
                    assertEquals(
                        exporter.snapshotAll(),
                        recovered.snapshotAll(),
                        "iter $iter: the store and the live exporter must agree after a concurrent clear",
                    )
                }
            }
        } finally {
            dispatcher.close()
        }
    }

    private companion object {
        private const val THREADS = 4
        private const val CONCURRENT = 16
        private const val REPEATS = 20
        private const val KINDS = 3
        private const val GATE_BOUND_MS = 2_000L
    }
}
