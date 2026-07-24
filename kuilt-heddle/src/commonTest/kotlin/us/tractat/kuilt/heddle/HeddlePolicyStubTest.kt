package us.tractat.kuilt.heddle

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * TDD stub for H3 (#1606) — proves the policy front door exists before the real
 * suites land. Replaced by the full acceptance suites.
 */
class HeddlePolicyStubTest {
    @Test
    fun pickReturnsTheOnlyDemandingChild() {
        val record = AttachmentRecord(
            id = AttachmentId("a"),
            parent = GroupId("root"),
            child = GroupId("a"),
            weight = Weight.ONE,
            initialVirtualTime = 0L,
        )
        val edge = PolicyEdge(
            record = record,
            summary = EdgeSummary(AttachmentId("a"), issued = 0L, returned = 0L, spent = 0L),
            demand = Demand(targetOutstanding = 10L, maximumUsefulGrant = 10L),
        )
        val grant = HeddlePolicy.pick(listOf(edge), PolicyConfig(quantum = 5L), localHoldings = 100L)
        assertEquals(Grant(AttachmentId("a"), 5L), grant)
    }
}
