@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.otel.tap

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.otel.MetricCatalog
import us.tractat.kuilt.otel.MetricKey
import us.tractat.kuilt.otel.MetricKind
import us.tractat.kuilt.quilter.MonotonicMillis
import us.tractat.kuilt.quilter.QuiltMessage
import us.tractat.kuilt.quilter.Quilter
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * What #2715 is actually about for the metric tap, read off a real [Quilter] under the tap's own
 * [TapCbor]: the root announced to a new peer. If two pullers' merge orders reach those bytes, their
 * roots never match and every anti-entropy round ships the whole catalog.
 *
 * `:kuilt-otel`'s `MetricCatalogCanonicalEncodingTest` pins the bytes on the default format; this is
 * the consequence, in the format production uses.
 */
class MetricCatalogRootTest {

    /** `"Aa"`, `"BB"` and `"C#"` share one `String.hashCode`, so the fold order shows on the JVM too. */
    private val operands = listOf("Aa", "BB", "C#").map { name ->
        MetricCatalog(sums = mapOf(MetricKey(name, MetricKind.SUM) to GCounter.of(ReplicaId("r") to 1L)))
    }

    private fun fold(from: List<MetricCatalog>): MetricCatalog =
        from.fold(MetricCatalog()) { merged, next -> merged.piece(next) }

    /** The control keeps it honest: a root that ignored the state would match for free. */
    @Test
    fun peersThatMergedInDifferentOrdersAnnounceTheSameRoot() =
        runTest(UnconfinedTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val forward = announcedRoot(fold(operands))
            val backward = announcedRoot(fold(operands.reversed()))
            val empty = announcedRoot(MetricCatalog())
            assertAll(
                { assertEquals(forward, backward, "equal catalogs must announce equal roots") },
                { assertNotEquals(empty, forward, "the root must depend on the state") },
            )
        }

    private fun TestScope.announcedRoot(state: MetricCatalog): Long {
        val serializer = metricCatalogSerializer()
        val seam = FakeSeam(selfId = PeerId("self"))
        Quilter(
            seam = seam,
            initial = state,
            valueSerializer = serializer,
            scope = backgroundScope,
            replica = ReplicaId("self"),
            config = QuilterConfig(expectVirtualTime = true, fullStateRetryLimit = 0),
            clock = MonotonicMillis { testScheduler.currentTime },
            binaryFormat = TapCbor,
            random = Random(11),
        )
        seam.addPeer(PeerId("peer"))
        testScheduler.runCurrent()
        return seam.directed
            .map { (_, frame) -> TapCbor.decodeFromByteArray(QuiltMessage.serializer(serializer), frame) }
            .filterIsInstance<QuiltMessage.RootDigest<MetricCatalog>>()
            .single()
            .root
    }
}
