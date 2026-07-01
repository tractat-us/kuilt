@file:Suppress("ForbiddenImport") // deliberate real-threading regression test: the concurrent-export re-mint + spans lost-update races are only observable on a genuine multi-threaded dispatcher, which virtual-time runTest cannot provide — the production-dispatcher-in-tests ban is exempted here per the module's coroutine-determinism policy.

package us.tractat.kuilt.otel

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.ReplicaId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Concurrency regression for #1053. `WarpSpanExporter` advertises correctness under a
 * real multi-threaded dispatcher, and [WarpSpanExporter.export] asserts the crash-window
 * invariant *durable clock seq ≥ every durable span dot* unconditionally. Before the
 * durable-write section was serialized by an ordering `Mutex`, that invariant held only
 * for serial calls: two concurrent `export()`s could interleave so an older clock
 * snapshot's store-write landed last (clock < a durable span's dot → re-mint on recover),
 * and a stale encoded spans snapshot could drop a concurrently-added span.
 *
 * This stress loop drives many concurrent `export()`s on a fixed real thread pool, then
 * recovers from the durable store as a fresh process would and asserts (a) no re-minted
 * dot and (b) no dropped span. It fails reliably without the `Mutex` and passes with it.
 */
class WarpSpanExporterConcurrencyTest {

    private val replicaA = ReplicaId("A")
    private fun traceId(i: Int) = ByteString(ByteArray(16) { i.toByte() })
    private fun spanId(i: Int) = ByteString(ByteArray(8) { i.toByte() })
    private fun span(i: Int) = SpanRecord(
        traceId = traceId(i),
        spanId = spanId(i),
        parentSpanId = null,
        name = "op",
        kind = SpanKind.INTERNAL,
        startEpochNanos = 1_000L,
        endEpochNanos = 2_000L,
        causalStamp = null,
    )

    /**
     * A store whose `write` suspends a **variable** number of times before committing,
     * modelling a real WAL/IndexedDB where durable-write latency varies per call. The
     * variable suspension decouples write-completion order from export start order — the
     * interleaving that lets a stale snapshot (or an older clock seq) commit last. A
     * never-suspending [InMemoryDurableStore] hides this; a fixed single yield mostly
     * preserves start-order and hides it too. Seeded for reproducibility.
     */
    private class VariableLatencyStore(seed: Int) : DurableStore {
        private val backing = InMemoryDurableStore()
        private val rng = kotlin.random.Random(seed)
        override suspend fun read(key: StoreKey): ByteArray? = backing.read(key)
        override suspend fun write(key: StoreKey, bytes: ByteArray) {
            repeat(rng.nextInt(MAX_YIELDS)) { yield() }
            backing.write(key, bytes)
        }
        override suspend fun delete(key: StoreKey) = backing.delete(key)

        private companion object {
            private const val MAX_YIELDS = 6
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun concurrentExportsNeverRemintDotNorDropSpan() {
        val dispatcher = newFixedThreadPoolContext(THREADS, "otel-export-stress")
        try {
            runBlocking {
                repeat(REPEATS) { iter ->
                    val store = VariableLatencyStore(seed = iter)
                    val clock = WarpCausalClock(replicaA)
                    val exp = WarpSpanExporter(replicaA, store, causalClock = clock)

                    (0 until CONCURRENT).map { i ->
                        launch(dispatcher) { exp.export(span(i)) }
                    }.joinAll()

                    // Recover from the durable store exactly as a restarted process would.
                    val clock2 = WarpCausalClock(replicaA)
                    clock2.recover(store)
                    val exp2 = WarpSpanExporter(replicaA, store, causalClock = clock2)
                    exp2.recover()

                    val durable = exp2.snapshot().elements
                    val maxDurableDotSeq = durable
                        .mapNotNull { it.causalStamp?.dot?.seq }
                        .maxOrNull() ?: 0L
                    val nextSeq = clock2.tick().dot.seq

                    assertTrue(
                        nextSeq > maxDurableDotSeq,
                        "iter $iter: re-minted dot — next tick seq=$nextSeq is not > " +
                            "max durable span dot seq=$maxDurableDotSeq",
                    )
                    assertEquals(
                        CONCURRENT,
                        durable.size,
                        "iter $iter: lost update — ${durable.size} of $CONCURRENT spans survived",
                    )
                }
            }
        } finally {
            dispatcher.close()
        }
    }

    private companion object {
        private const val THREADS = 4
        private const val REPEATS = 200
        private const val CONCURRENT = 32
    }
}
