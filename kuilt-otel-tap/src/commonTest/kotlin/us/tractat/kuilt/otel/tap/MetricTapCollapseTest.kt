package us.tractat.kuilt.otel.tap

import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.otel.MetricCatalog
import us.tractat.kuilt.otel.MetricKey
import us.tractat.kuilt.otel.MetricKind
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

class MetricTapCollapseTest {

    /**
     * A sum series whose replicas' slots add up past [Long.MAX_VALUE] (#3318): one replica's slot
     * near the ceiling and a merged slot from another. It is left out of the snapshot, and every
     * other series is still reported, so `pull()` and `tail()` keep working.
     */
    @Test
    fun anOverflowingSumSeriesIsLeftOutAndTheOthersAreReported() {
        val big = MetricKey("big", MetricKind.SUM)
        val small = MetricKey("small", MetricKind.SUM)
        val bigCounter = GCounter.of(ReplicaId("device") to Long.MAX_VALUE - 1L)
            .piece(GCounter.of(ReplicaId("other") to 2L))
        val catalog = MetricCatalog(
            sums = mapOf(big to bigCounter, small to GCounter.of(ReplicaId("device") to 3L)),
        )

        val snapshot = collapse(catalog, LeftOutSums())

        assertAll(
            { assertEquals(null, bigCounter.valueOrNull(), "the rig: big really overflows") },
            { assertEquals(mapOf(small to 3L), snapshot.sums) },
        )
    }

    /** Collapsing twice reports a left-out series once: the second sighting is not a first. */
    @Test
    fun aLeftOutSeriesIsReportedOnceAcrossCollapses() {
        val big = MetricKey("big", MetricKind.SUM)
        val bigCounter = GCounter.of(ReplicaId("device") to Long.MAX_VALUE - 1L)
            .piece(GCounter.of(ReplicaId("other") to 2L))
        val catalog = MetricCatalog(sums = mapOf(big to bigCounter))
        val leftOut = LeftOutSums()

        collapse(catalog, leftOut)
        val secondCollapseIsFirst = leftOut.firstSighting(big, catalog.sums.keys)
        collapse(catalog, leftOut)

        assertAll(
            { assertEquals(false, secondCollapseIsFirst, "the first collapse already recorded it") },
            { assertEquals(false, leftOut.firstSighting(big, catalog.sums.keys)) },
            { assertEquals(true, LeftOutSums().firstSighting(big, catalog.sums.keys), "a fresh ledger sees it first") },
        )
    }
}
