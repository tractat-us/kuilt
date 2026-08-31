package us.tractat.kuilt.otel

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext // ALLOW-realDispatcher: `clear()` is fenced against a concurrent `export()` by `writeMutex`, and an export that encoded the pre-clear state landing its bytes AFTER the clear is only reachable when the two genuinely overlap on separate OS threads — under a virtual-time dispatcher the clear and the exports interleave only at suspension points, which is the interleaving `writeMutex` already excludes.
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.store.StoreKey
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Two arms, because neither is sufficient alone.
 *
 * The **deterministic** arm asserts a clear actually empties the store. The **concurrent** arm
 * asserts the store and the live buffer still *agree* when a clear races in-flight exports —
 * which is the race property, and is deliberately not "the store is empty": an export
 * serialized after the clear legitimately survives, and which ones those are is not
 * predictable. `writeMutex` is what makes agreement hold — a turn builds its actions and
 * applies them inside one critical section, so a clear cannot interleave between an export's
 * encode and its write and have the stale bytes land afterwards.
 *
 * The agreement assertion alone is satisfied by a `clear()` that drops nothing (live and
 * recovered would agree on all of them), which is why the first arm exists.
 */
class WarpLogRecordExporterClearConcurrencyTest {

    private fun record(i: Int) = LogRecord(
        recordId = ByteString(ByteArray(8) { b -> (i shr (8 * b)).toByte() }),
        body = "body-$i",
        observedEpochNanos = 1_000L + i,
    )

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

    @Test
    fun aClearAfterEveryExportHasCompletedLeavesTheStoreEmpty() = kotlinx.coroutines.test.runTest {
        // The deterministic arm. Without it the concurrent arm below is satisfiable by a
        // clear() that drops nothing — live and recovered would simply agree on everything.
        val store = InMemoryDurableStore()
        val exporter = WarpLogRecordExporter(ReplicaId("A"), store, segmentOps = 4)
        repeat(CONCURRENT) { i -> exporter.export(record(i)) }

        exporter.clear()

        val recovered = WarpLogRecordExporter(ReplicaId("A"), store, segmentOps = 4)
        recovered.recover()
        assertEquals(emptyList(), recovered.snapshot().toList(), "a clear must actually empty the store")
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun theStoreAndTheBufferAgreeWhenAClearRacesConcurrentExports() {
        val dispatcher = newFixedThreadPoolContext(THREADS, "otel-log-clear-stress")
        try {
            runBlocking {
                repeat(REPEATS) { iter ->
                    val store = VariableLatencyStore(iter)
                    val exporter = WarpLogRecordExporter(ReplicaId("A"), store, segmentOps = 4)

                    val exports = (0 until CONCURRENT).map { i ->
                        launch(dispatcher) { exporter.export(record(i)) }
                    }
                    exporter.clear()
                    exports.joinAll()

                    // Every record that survives must be one whose export was serialized AFTER
                    // the clear. None of them may be recoverable from a segment the clear retired.
                    val recovered = WarpLogRecordExporter(ReplicaId("A"), store, segmentOps = 4)
                    recovered.recover()
                    val live = exporter.snapshot().toList().map { it.recordId }.toSet()
                    assertEquals(
                        live,
                        recovered.snapshot().toList().map { it.recordId }.toSet(),
                        "iter $iter: the store and the live buffer must agree after a concurrent clear",
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
    }
}
