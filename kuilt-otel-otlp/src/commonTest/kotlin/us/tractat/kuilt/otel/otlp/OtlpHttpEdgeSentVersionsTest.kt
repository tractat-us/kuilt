package us.tractat.kuilt.otel.otlp

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.otel.DrainResult
import us.tractat.kuilt.otel.MetricKey
import us.tractat.kuilt.otel.MetricKind
import us.tractat.kuilt.otel.MetricPoint
import us.tractat.kuilt.otel.OtlpSignal
import us.tractat.kuilt.otel.WarpOtlpBridge
import us.tractat.kuilt.otel.WarpTelemetry
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.store.StoreKey
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The per-endpoint sent-versions map an [OtlpHttpEdge] keeps for metrics (#3292): read once per
 * drain, folded forward only once a POST is acknowledged AND durably written, and pruned to the
 * series the producer still holds.
 *
 * Every test drains through [WarpOtlpBridge], because the map is only a defect when a drain
 * believes it, and each asserts the precondition that makes its property reachable.
 */
class OtlpHttpEdgeSentVersionsTest {

    private val clock = object : Clock { override fun now() = Instant.fromEpochSeconds(1_700_000_000) }

    private fun key(name: String) = MetricKey(name, MetricKind.SUM)

    private val endpoint = "https://c.example:4318"

    /** Counts reads of the metric sent-set, and can refuse its next durable write. */
    private class RiggedStore(private val delegate: DurableStore = InMemoryDurableStore()) : DurableStore {
        var metricReads: Int = 0
            private set
        var refusedWrites: Int = 0
            private set
        var refuseNextMetricWrite: Boolean = false

        private fun StoreKey.isMetricSentSet() = name.startsWith("otlp.sent.metrics@")

        override suspend fun read(key: StoreKey): ByteArray? {
            if (key.isMetricSentSet()) metricReads++
            return delegate.read(key)
        }

        override suspend fun write(key: StoreKey, bytes: ByteArray) {
            if (key.isMetricSentSet() && refuseNextMetricWrite) {
                refuseNextMetricWrite = false
                refusedWrites++
                error("rigged: write refused")
            }
            delegate.write(key, bytes)
        }

        override suspend fun delete(key: StoreKey) = delegate.delete(key)
    }

    /** A collector that answers 200, or 500 once when armed, and records what each request carried. */
    private class Collector {
        var posts: Int = 0
            private set
        var failures: Int = 0
            private set
        var failNext: Boolean = false

        /** Runs once, inside the next POST, before it is answered: a write that lands mid-request. */
        var duringNextPost: (suspend () -> Unit)? = null
        var duringPostFired: Int = 0
            private set

        val client: HttpClient = HttpClient(
            MockEngine {
                posts++
                duringNextPost?.let { during ->
                    duringNextPost = null
                    duringPostFired++
                    during()
                }
                if (failNext) {
                    failNext = false
                    failures++
                    respond("", HttpStatusCode.InternalServerError)
                } else {
                    respond("{}", HttpStatusCode.OK)
                }
            },
        )
    }

    /** What is on disk, read through a fresh edge that has no cache of its own. */
    private suspend fun onDisk(store: DurableStore, collector: Collector): Map<MetricKey, Long> =
        OtlpHttpEdge(collector.client, endpoint, store).metricDigest().versions

    private suspend fun telemetry(maxMetrics: Int = 100): WarpTelemetry =
        WarpTelemetry(ReplicaId("device-3292"), InMemoryDurableStore(), maxMetrics = maxMetrics).also { it.recover() }

    /**
     * The read budget: one read for the digest, and one more just before a write, so a write never
     * builds on a copy another edge may have overwritten (see [aWriteByAnotherEdgeDuringAPostIsKept]).
     * A drain with nothing to send and nothing to prune therefore reads exactly once, and a drain
     * that sends reads at most twice; before #3292 it read twice and decoded both times. Reads are
     * counted rather than decodes because the store is the only seam the test can see; that a
     * matching read skips the decode is shown by the bench's `edge digest ms`, not pinned here.
     */
    @Test
    fun aDrainReadsTheSentSetOnceAndOnceMoreBeforeAWrite() = runTest {
        val store = RiggedStore()
        val collector = Collector()
        val edge = OtlpHttpEdge(collector.client, endpoint, store)
        val t = telemetry()
        val bridge = WarpOtlpBridge(t, clock)
        t.metrics.incrementSum(key("a"))
        t.metrics.incrementSum(key("b"))

        suspend fun drainCountingReads(): Pair<DrainResult, Int> {
            val before = store.metricReads
            val result = bridge.drain(edge)
            return result to store.metricReads - before
        }

        val (first, firstReads) = drainCountingReads()
        t.metrics.incrementSum(key("a"))
        val (second, secondReads) = drainCountingReads()
        val (idle, idleReads) = drainCountingReads()

        assertAll(
            { assertEquals(DrainResult.Success(spansSent = 0, metricPointsSent = 2), first, "precondition: the first drain sent points") },
            { assertEquals(DrainResult.Success(spansSent = 0, metricPointsSent = 1), second, "precondition: the second drain sent a point") },
            {
                assertEquals(
                    DrainResult.Success(spansSent = 0, metricPointsSent = 0),
                    idle,
                    "precondition: the third drain had series to offer and nothing to send",
                )
            },
            { assertTrue(firstReads <= 2, "the first drain read the sent-set $firstReads times") },
            { assertTrue(secondReads <= 2, "the second drain read the sent-set $secondReads times") },
            { assertEquals(1, idleReads, "a drain that writes nothing reads the sent-set once") },
        )
    }

    @Test
    fun anEvictedSeriesLeavesTheMapAfterTheNextDrain() = runTest {
        val store = RiggedStore()
        val collector = Collector()
        val edge = OtlpHttpEdge(collector.client, endpoint, store)
        val t = telemetry(maxMetrics = 2)
        val bridge = WarpOtlpBridge(t, clock)
        t.metrics.incrementSum(key("a"))
        t.metrics.incrementSum(key("b"))
        assertEquals(DrainResult.Success(spansSent = 0, metricPointsSent = 2), bridge.drain(edge))

        // A third series at a cap of two evicts the oldest, `a`.
        t.metrics.incrementSum(key("c"))
        val live = t.metrics.snapshotAll().sums.keys
        val before = onDisk(store, collector)
        assertAll(
            { assertFalse(key("a") in live, "precondition: `a` was evicted from the producer, live = $live") },
            { assertTrue(key("a") in before, "precondition: `a` is in the sent-set before the drain, map = $before") },
        )

        val result = bridge.drain(edge)
        val after = onDisk(store, collector)
        val edgeView = edge.metricDigest().versions
        assertAll(
            { assertEquals(DrainResult.Success(spansSent = 0, metricPointsSent = 1), result, "the drain sends only `c`") },
            { assertFalse(key("a") in after, "an evicted series must leave the sent-set, map = $after") },
            { assertEquals(setOf(key("b"), key("c")), after.keys, "the sent-set is exactly the live series") },
            { assertEquals(after, edgeView, "the edge's own view agrees with disk") },
        )
    }

    /**
     * The cache is observed through a direct [OtlpHttpEdge.sendMetrics] after the failure. A send
     * re-reads disk before it writes, but keeps the cached map when the bytes still match, so a
     * cache that took the failed point while keeping the old bytes would write it into the sent-set
     * here. A cache folded together with new bytes is not visible to any test: the byte check sees
     * the mismatch and decodes disk again, so the fold changes nothing.
     */
    @Test
    fun aFailedPostLeavesDiskAndCacheUnchangedAndTheNextDrainResends() = runTest {
        val store = RiggedStore()
        val collector = Collector()
        val edge = OtlpHttpEdge(collector.client, endpoint, store)
        val t = telemetry()
        val bridge = WarpOtlpBridge(t, clock)
        t.metrics.incrementSum(key("a"))
        assertEquals(DrainResult.Success(spansSent = 0, metricPointsSent = 1), bridge.drain(edge))
        val sent = onDisk(store, collector)

        t.metrics.incrementSum(key("a"))
        collector.failNext = true
        val failed = bridge.drain(edge)
        val diskAfterFailure = onDisk(store, collector)
        edge.sendMetrics(setOf(sumPoint("other", 1L)))
        val diskAfterNextWrite = onDisk(store, collector)

        val retried = bridge.drain(edge)
        assertAll(
            { assertEquals(1, collector.failures, "precondition: exactly one 500 fired") },
            { assertEquals(mapOf(key("a") to 1L), sent, "precondition: the first drain recorded a = 1") },
            { assertIs<DrainResult.Failure>(failed, "the 500 fails the metric leg") },
            { assertEquals(OtlpSignal.METRICS, (failed as DrainResult.Failure).failures.single().signal) },
            { assertEquals(sent, diskAfterFailure, "a 500 must not fold into the sent-set on disk") },
            {
                assertEquals(
                    sent + (key("other") to 1L),
                    diskAfterNextWrite,
                    "a 500 must not fold into the edge's cache, which the next write is built on",
                )
            },
            { assertEquals(DrainResult.Success(spansSent = 0, metricPointsSent = 1), retried, "the next drain resends `a`") },
        )
    }

    /**
     * Observed the same way, with the same blind spot: a cache folded on the acknowledged POST while
     * keeping the bytes it came from would write `a` into the sent-set although the send that
     * carried it recorded nothing. A cache set to the refused write's own bytes is corrected by the
     * next write's byte check, so folding before rather than after the store's write is not
     * observable here.
     */
    @Test
    fun anAcknowledgedPostWhoseWriteIsRefusedDoesNotFoldTheCache() = runTest {
        val store = RiggedStore()
        val collector = Collector()
        val edge = OtlpHttpEdge(collector.client, endpoint, store)
        val empty = edge.metricDigest().versions

        store.refuseNextMetricWrite = true
        val refused = runCatchingCancellable { edge.sendMetrics(setOf(sumPoint("a", 1L))) }
        val diskAfterRefusal = onDisk(store, collector)
        edge.sendMetrics(setOf(sumPoint("b", 1L)))
        val disk = onDisk(store, collector)

        assertAll(
            { assertTrue(empty.isEmpty(), "precondition: nothing sent yet") },
            { assertEquals(1, store.refusedWrites, "precondition: the write rig fired exactly once") },
            { assertEquals(2, collector.posts, "precondition: both POSTs were acknowledged") },
            { assertTrue(refused.isFailure, "a refused write fails the send") },
            { assertEquals(emptyMap(), diskAfterRefusal, "the refused write left nothing on disk") },
            { assertEquals(mapOf(key("b") to 1L), disk, "only the send whose write landed is recorded") },
        )
    }

    private fun sumPoint(name: String, value: Long) =
        MetricPoint.Sum(key(name), value, startEpochNanos = 0L, timeEpochNanos = 1L)

    /**
     * Two edges on one store and one endpoint share one sent-set (#1053), so an edge's cache can go
     * stale under the other's write. A gauge returning to an earlier value is where trusting it would
     * be silent under-delivery: the stale cache says the old value was sent, the collector holds the
     * newer one, and the drain sends nothing.
     */
    @Test
    fun aWriteByAnotherEdgeOnTheSameEndpointIsSeen() = runTest {
        val store = RiggedStore()
        val collector = Collector()
        val first = OtlpHttpEdge(collector.client, endpoint, store)
        val second = OtlpHttpEdge(collector.client, "$endpoint/", store)
        val t = telemetry()
        val bridge = WarpOtlpBridge(t, clock)
        val g = MetricKey("queue.depth", MetricKind.GAUGE)

        t.metrics.setGauge(g, 1.0, timestamp = 1L)
        assertEquals(DrainResult.Success(spansSent = 0, metricPointsSent = 1), bridge.drain(first))
        t.metrics.setGauge(g, 2.0, timestamp = 2L)
        assertEquals(DrainResult.Success(spansSent = 0, metricPointsSent = 1), bridge.drain(second))
        t.metrics.setGauge(g, 1.0, timestamp = 3L)
        val disk = onDisk(store, collector)

        val result = bridge.drain(first)
        assertAll(
            { assertEquals(2.0.toRawBits(), disk[g], "precondition: the collector last received 2.0") },
            { assertEquals(DrainResult.Success(spansSent = 0, metricPointsSent = 1), result, "1.0 is owed to the collector again") },
        )
    }

    /**
     * The same two edges, but the second one's write lands INSIDE the first one's POST, between
     * the first's digest and its write. The first sends only `s`, so its write must keep the gauge
     * the second edge recorded: a write built on the digest-time copy puts back `g = 1.0`, and when
     * the gauge returns to 1.0 the drain believes the collector already has it.
     */
    @Test
    fun aWriteByAnotherEdgeDuringAPostIsKept() = runTest {
        val store = RiggedStore()
        val firstCollector = Collector()
        val secondCollector = Collector()
        val first = OtlpHttpEdge(firstCollector.client, endpoint, store)
        val second = OtlpHttpEdge(secondCollector.client, "$endpoint/", store)
        val t = telemetry()
        val bridge = WarpOtlpBridge(t, clock)
        val g = MetricKey("queue.depth", MetricKind.GAUGE)
        val s = key("s")

        t.metrics.setGauge(g, 1.0, timestamp = 1L)
        t.metrics.incrementSum(s)
        assertEquals(DrainResult.Success(spansSent = 0, metricPointsSent = 2), bridge.drain(first))

        // The first edge's next drain carries only `s`; while its POST is in flight the gauge moves
        // and the second edge delivers it.
        t.metrics.incrementSum(s)
        var inner: DrainResult? = null
        firstCollector.duringNextPost = {
            t.metrics.setGauge(g, 2.0, timestamp = 2L)
            inner = bridge.drain(second)
        }
        val outer = bridge.drain(first)
        val disk = onDisk(store, firstCollector)

        t.metrics.setGauge(g, 1.0, timestamp = 3L)
        val returned = bridge.drain(first)
        assertAll(
            { assertEquals(1, firstCollector.duringPostFired, "precondition: the second edge ran inside the POST") },
            {
                assertEquals(
                    DrainResult.Success(spansSent = 0, metricPointsSent = 2),
                    inner,
                    "precondition: the second edge delivered the gauge (and `s`) mid-POST",
                )
            },
            { assertEquals(DrainResult.Success(spansSent = 0, metricPointsSent = 1), outer, "precondition: the first edge sent `s`") },
            { assertEquals(2.0.toRawBits(), disk[g], "the first edge's write must keep the gauge the second edge recorded") },
            { assertEquals(DrainResult.Success(spansSent = 0, metricPointsSent = 1), returned, "1.0 is owed to the collector again") },
        )
    }
}
