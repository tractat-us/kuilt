@file:Suppress("ForbiddenImport") // deliberate real-threading regression test: WarpLogRecordExporter threads mutable derived state (tail id, visible count, dedup map) forward across export() calls under an explicit lock, and a lost update there is only observable on a genuine multi-threaded dispatcher, which virtual-time runTest cannot provide — the production-dispatcher-in-tests ban is exempted here per the module's coroutine-determinism policy.

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

/**
 * `WarpLogRecordExporter` advertises correctness under a real multi-threaded dispatcher, and
 * it now carries mutable derived state across calls — the tail [us.tractat.kuilt.crdt.RgaId],
 * the visible-record count that gates eviction, and the `recordId → RgaId` dedup map. All three
 * live under the same `reentrantLock` as the `Rga` itself, so a concurrent `export()` can never
 * observe them out of step with the log.
 *
 * This stress loop drives many concurrent `export()`s on a fixed real thread pool and asserts
 * the three invariants a lost update would break: the buffer cap holds exactly, no record is
 * dropped when there is room for all of them, and a record exported concurrently from many
 * threads lands exactly once.
 */
class WarpLogRecordExporterConcurrencyTest {

    private val replicaA = ReplicaId("A")

    private fun record(i: Int) = LogRecord(
        recordId = ByteString(ByteArray(8) { i.toByte() }),
        body = "body-$i",
        observedEpochNanos = 1_000L + i,
    )

    /** Yields a variable number of times before committing, so writes finish out of start order. */
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
    fun concurrentExportsKeepDerivedStateInStepWithTheLog() {
        val dispatcher = newFixedThreadPoolContext(THREADS, "otel-log-export-stress")
        try {
            runBlocking {
                repeat(REPEATS) { iter ->
                    // (a) Uncapped: every distinct record must survive — a lost dedup-map or
                    // tail update would drop or misplace one.
                    val uncapped = WarpLogRecordExporter(replicaA, VariableLatencyStore(iter))
                    (0 until CONCURRENT).map { i ->
                        launch(dispatcher) { uncapped.export(record(i)) }
                    }.joinAll()
                    assertEquals(
                        CONCURRENT,
                        uncapped.snapshot().toList().size,
                        "iter $iter: lost update — records dropped with no buffer cap in play",
                    )

                    // (b) Capped: the visible count gates eviction, so a lost decrement or
                    // increment shows up as a buffer that overshoots or undershoots the cap.
                    val capped = WarpLogRecordExporter(
                        replica = replicaA,
                        store = VariableLatencyStore(iter),
                        maxRecords = CAP,
                        bufferPolicy = BufferPolicy.DROP_OLDEST,
                    )
                    (0 until CONCURRENT).map { i ->
                        launch(dispatcher) { capped.export(record(i)) }
                    }.joinAll()
                    assertEquals(
                        CAP,
                        capped.snapshot().toList().size,
                        "iter $iter: buffer cap violated",
                    )

                    // (c) The same record exported from every thread must land exactly once.
                    val deduping = WarpLogRecordExporter(replicaA, VariableLatencyStore(iter))
                    val same = record(0)
                    (0 until CONCURRENT).map {
                        launch(dispatcher) { deduping.export(same) }
                    }.joinAll()
                    assertEquals(
                        1,
                        deduping.snapshot().toList().size,
                        "iter $iter: concurrent re-export of one record was not deduplicated",
                    )
                }
            }
        } finally {
            dispatcher.close()
        }
    }

    private companion object {
        private const val THREADS = 4
        private const val REPEATS = 50
        private const val CONCURRENT = 32
        private const val CAP = 8
    }
}
