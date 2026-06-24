package us.tractat.kuilt.otel

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.ReplicaId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WarpOtlpBridgeTest {

    private val replica = ReplicaId("test")

    // ---- helpers ----

    private fun traceId(id: Byte): ByteString = ByteString(ByteArray(16) { id })
    private fun spanId(id: Byte): ByteString = ByteString(ByteArray(8) { id })

    private fun span(id: Byte, name: String = "op", startNanos: Long = 1_000L) = SpanRecord(
        traceId = traceId(id),
        spanId = spanId(id),
        parentSpanId = null,
        name = name,
        kind = SpanKind.INTERNAL,
        startEpochNanos = startNanos,
        endEpochNanos = startNanos + 1_000L,
    )

    private fun emptyExporter(): WarpSpanExporter =
        WarpSpanExporter(replica = replica, store = InMemoryDurableStore())

    // ---- A5: reconcile sends only the delta (spans the edge does not have) ----

    @Test
    fun drainSendsNothingWhenEdgeAlreadyHasAllSpans() = runTest {
        val s1 = span(1)
        val exporter = emptyExporter().also { it.export(s1) }
        val edge = RecordingEdge(knownSpanIds = setOf(s1.spanId))

        val bridge = WarpOtlpBridge(exporter = exporter)
        bridge.drain(edge)

        assertTrue(edge.sentBatches.isEmpty(), "edge already has the span; nothing should be sent")
    }

    @Test
    fun drainSendsOnlySpansMissingFromEdge() = runTest {
        val s1 = span(1)
        val s2 = span(2)
        val exporter = emptyExporter().also { it.export(s1); it.export(s2) }
        // Edge already has s1, missing s2.
        val edge = RecordingEdge(knownSpanIds = setOf(s1.spanId))

        val bridge = WarpOtlpBridge(exporter = exporter)
        bridge.drain(edge)

        val sent = edge.sentBatches.flatten().toSet()
        assertEquals(setOf(s2), sent, "only the missing span should be sent")
    }

    @Test
    fun drainSendsAllSpansWhenEdgeHasNone() = runTest {
        val s1 = span(1)
        val s2 = span(2)
        val exporter = emptyExporter().also { it.export(s1); it.export(s2) }
        val edge = RecordingEdge(knownSpanIds = emptySet())

        val bridge = WarpOtlpBridge(exporter = exporter)
        bridge.drain(edge)

        val sent = edge.sentBatches.flatten().toSet()
        assertEquals(setOf(s1, s2), sent)
    }

    // ---- idempotency: a resend cannot double-count ----

    @Test
    fun drainingTwiceDoesNotDoubleCount() = runTest {
        val s1 = span(1)
        val exporter = emptyExporter().also { it.export(s1) }
        // After first drain, edge has s1. A second drain must send nothing.
        val edge = AccumulatingEdge()

        val bridge = WarpOtlpBridge(exporter = exporter)
        bridge.drain(edge) // first drain — sends s1
        bridge.drain(edge) // second drain — edge now knows s1; nothing sent

        val allSent = edge.sentBatches.flatten()
        val distinctSpans = allSent.map { it.spanId }.toSet()
        assertEquals(1, distinctSpans.size, "same span should appear exactly once across both drains")
    }

    @Test
    fun reconnectDoesNotDoubleCount() = runTest {
        // Simulates a disconnect-reconnect: after reconnect the edge still has the spans
        // from the previous session. The bridge must not re-send them.
        val s1 = span(1)
        val s2 = span(2)
        val exporter = emptyExporter().also { it.export(s1); it.export(s2) }
        val edge = AccumulatingEdge()

        val bridge = WarpOtlpBridge(exporter = exporter)
        bridge.drain(edge) // first session — sends s1, s2

        // Simulate reconnect: both spans are now at the edge.
        bridge.drain(edge) // second session — nothing new

        val allSent = edge.sentBatches.flatten()
        assertEquals(2, allSent.size, "total send count across both sessions must be 2, not 4")
    }

    // ---- offline-then-reconnect convergence ----

    @Test
    fun offlineSpansAreDeliveredOnReconnect() = runTest {
        val s1 = span(1)
        val s2 = span(2)
        val exporter = emptyExporter()

        // Export s1 while offline (edge never contacted yet).
        exporter.export(s1)

        val edge = AccumulatingEdge()
        val bridge = WarpOtlpBridge(exporter = exporter)

        // Come online — s1 is delivered.
        bridge.drain(edge)

        // Export s2 offline again.
        exporter.export(s2)

        // Come online again — only s2 is new.
        bridge.drain(edge)

        val allSent = edge.sentBatches.flatten()
        assertEquals(setOf(s1, s2), allSent.toSet())
        // Crucially, each span appears exactly once.
        assertEquals(2, allSent.size)
    }

    // ---- edge send failures are best-effort (logged, not thrown) ----

    @Test
    fun drainSurvivesEdgeSendFailure() = runTest {
        val s1 = span(1)
        val exporter = emptyExporter().also { it.export(s1) }

        val bridge = WarpOtlpBridge(exporter = exporter)
        // Must not throw — failures are best-effort.
        bridge.drain(FailingEdge)
    }

    @Test
    fun drainSurvivesEdgeDigestFailure() = runTest {
        val s1 = span(1)
        val exporter = emptyExporter().also { it.export(s1) }

        val bridge = WarpOtlpBridge(exporter = exporter)
        // Must not throw — digest failure is best-effort.
        bridge.drain(DigestFailingEdge)
    }

    // ---- DrainResult ----

    @Test
    fun drainResultSuccessReportsSpanCount() = runTest {
        val s1 = span(1)
        val s2 = span(2)
        val exporter = emptyExporter().also { it.export(s1); it.export(s2) }
        val edge = RecordingEdge(knownSpanIds = emptySet())

        val bridge = WarpOtlpBridge(exporter = exporter)
        val result = bridge.drain(edge)

        assertIs<DrainResult.Success>(result)
        assertEquals(2, result.spansSent)
    }

    @Test
    fun drainResultNothingToSendWhenEdgeUpToDate() = runTest {
        val s1 = span(1)
        val exporter = emptyExporter().also { it.export(s1) }
        val edge = RecordingEdge(knownSpanIds = setOf(s1.spanId))

        val bridge = WarpOtlpBridge(exporter = exporter)
        val result = bridge.drain(edge)

        assertIs<DrainResult.Success>(result)
        assertEquals(0, result.spansSent)
    }

    @Test
    fun drainResultFailureOnEdgeError() = runTest {
        val s1 = span(1)
        val exporter = emptyExporter().also { it.export(s1) }

        val bridge = WarpOtlpBridge(exporter = exporter)
        val result = bridge.drain(FailingEdge)

        assertIs<DrainResult.Failure>(result)
    }

    @Test
    fun drainResultDigestFailureIsFailure() = runTest {
        val s1 = span(1)
        val exporter = emptyExporter().also { it.export(s1) }

        val bridge = WarpOtlpBridge(exporter = exporter)
        val result = bridge.drain(DigestFailingEdge)

        assertIs<DrainResult.Failure>(result)
    }

    @Test
    fun drainResultSuccessWhenNoLocalSpans() = runTest {
        val exporter = emptyExporter()
        val edge = RecordingEdge(knownSpanIds = emptySet())

        val bridge = WarpOtlpBridge(exporter = exporter)
        val result = bridge.drain(edge)

        assertIs<DrainResult.Success>(result)
        assertEquals(0, result.spansSent)
        assertFalse(edge.digestCalled, "digest should be skipped when there is nothing to send")
    }

    // ---- test doubles ----

    /** Edge that starts with [knownSpanIds] and accumulates spans passed to [send]. */
    private class RecordingEdge(
        knownSpanIds: Set<ByteString>,
    ) : OtlpEdge {
        private val lock = reentrantLock()
        private val known: MutableSet<ByteString> = knownSpanIds.toMutableSet()
        val sentBatches: MutableList<List<SpanRecord>> = mutableListOf()
        var digestCalled: Boolean = false

        override suspend fun digest(): SpanDigest {
            digestCalled = true
            return SpanDigest(lock.withLock { known.toSet() })
        }

        override suspend fun send(spans: Set<SpanRecord>) {
            lock.withLock {
                sentBatches.add(spans.toList())
                known.addAll(spans.map { it.spanId })
            }
        }
    }

    /** Edge that starts empty and accumulates all sent spans into its known set. */
    private class AccumulatingEdge : OtlpEdge {
        private val lock = reentrantLock()
        private val known: MutableSet<ByteString> = mutableSetOf()
        val sentBatches: MutableList<List<SpanRecord>> = mutableListOf()

        override suspend fun digest(): SpanDigest = SpanDigest(lock.withLock { known.toSet() })

        override suspend fun send(spans: Set<SpanRecord>) {
            lock.withLock {
                sentBatches.add(spans.toList())
                known.addAll(spans.map { it.spanId })
            }
        }
    }

    /** Edge whose [send] always throws. */
    private object FailingEdge : OtlpEdge {
        override suspend fun digest(): SpanDigest = SpanDigest(emptySet())
        override suspend fun send(spans: Set<SpanRecord>): Unit = throw RuntimeException("network error")
    }

    /** Edge whose [digest] always throws. */
    private object DigestFailingEdge : OtlpEdge {
        override suspend fun digest(): SpanDigest = throw RuntimeException("digest fetch failed")
        override suspend fun send(spans: Set<SpanRecord>) = Unit
    }
}
