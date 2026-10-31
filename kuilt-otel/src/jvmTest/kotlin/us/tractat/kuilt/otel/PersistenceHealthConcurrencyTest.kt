package us.tractat.kuilt.otel

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.newFixedThreadPoolContext // ALLOW-realDispatcher: exercise the new health accounting with overlapping calls on real threads; a virtual-time dispatcher cannot expose lost updates.
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.StoreKey
import us.tractat.kuilt.test.assertAll
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

class PersistenceHealthConcurrencyTest {
    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun concurrentTurnsRetainEveryOutcomeInTheCombinedView() {
        val threads = newFixedThreadPoolContext(4, "persistence-health")
        try {
            runBlocking {
                val writes = AtomicInteger()
                val refusals = AtomicInteger()
                val store = object : DurableStore {
                    override suspend fun read(key: StoreKey): ByteArray? = null
                    override suspend fun delete(key: StoreKey) = Unit
                    override suspend fun write(key: StoreKey, bytes: ByteArray) {
                        yield()
                        writes.incrementAndGet()
                        // One metric kind remains down while the other and spans keep working.
                        if (key.name == "otel.metrics.sums.double") {
                            refusals.incrementAndGet()
                            throw IllegalStateException("double sums refused")
                        }
                    }
                }
                val telemetry = WarpTelemetry(ReplicaId("parallel"), store)
                val key = MetricKey("total", MetricKind.SUM)
                val turns = 40
                val outcomes = (0 until turns).map {
                    async(threads) {
                        val span = telemetry.spans.export(healthSpan())
                        val sum = telemetry.metrics.incrementSum(key)
                        val double = telemetry.metrics.incrementSumDouble(key, 0.5)
                        Triple(span, sum, double)
                    }
                }.awaitAll()
                val health = telemetry.health
                assertAll(
                    { assertEquals(turns, outcomes.size, "every launched turn completed") },
                    { assertEquals(turns, outcomes.count { it.first == ExportResult.Success }) },
                    { assertEquals(turns, outcomes.count { it.second == MetricExportResult.Success }) },
                    { assertEquals(turns, outcomes.count { it.third is MetricExportResult.Failure }) },
                    { assertEquals(turns, refusals.get(), "the refusal rig fired on every double sum") },
                    { assertEquals(turns * 4, writes.get(), "clock, span and both sum writes ran") },
                    { assertEquals(turns.toLong(), health.spans.acknowledged) },
                    { assertEquals(turns.toLong(), health.metrics.acknowledged) },
                    { assertEquals(turns.toLong(), health.metrics.failed) },
                    { assertEquals(turns.toLong(), health.metrics.outstandingFailures) },
                )
            }
        } finally {
            threads.close()
        }
    }
}
