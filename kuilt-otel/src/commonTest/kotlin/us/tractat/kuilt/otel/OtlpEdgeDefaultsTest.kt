package us.tractat.kuilt.otel

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OtlpEdgeDefaultsTest {
    /** Implements only the spans surface; everything else must default. */
    private class SpanOnlyEdge : OtlpEdge {
        override suspend fun digest(): SpanDigest = SpanDigest(emptySet())
        override suspend fun send(spans: Set<SpanRecord>, links: List<SpanLink>) = Unit
    }

    @Test
    fun logAndMetricMembersDefaultToEmpty() = runTest {
        val edge = SpanOnlyEdge()
        assertTrue(edge.logDigest().recordIds.isEmpty())
        assertTrue(edge.metricDigest().versions.isEmpty())
        // default no-ops must not throw
        edge.sendLogs(emptySet())
        edge.sendMetrics(emptySet())
    }

    @Test
    fun sendLinksDefaultsToEmpty() = runTest {
        var received: List<SpanLink>? = null
        val edge = object : OtlpEdge {
            override suspend fun digest(): SpanDigest = SpanDigest(emptySet())
            override suspend fun send(spans: Set<SpanRecord>, links: List<SpanLink>) { received = links }
        }
        edge.send(emptySet()) // omit links → default empty
        assertEquals(emptyList(), received)
    }
}
