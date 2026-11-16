package us.tractat.kuilt.heddle

import us.tractat.kuilt.math.Int128
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The origin's three public reads survive step B, deprecated, because 0.7.5 published them
 * (design §7.1). Compiled against them as a caller would be, so a removal fails to compile and a
 * change of meaning reds here.
 */
@Suppress("DEPRECATION")
class DeprecatedOriginReadsTest {

    private val root = GroupId("root")
    private val id = AttachmentId("e")
    private val record = AttachmentRecord(id, root, GroupId("e"), Weight.ONE)

    /**
     * The pure policy still reads [PolicyEdge.origin] as 0.7.5 did: every position less
     * `⌊O · w · G⌋`. An origin of 2 units at weight 1 is 32 ticks, so a gauge at 100 reads 68.
     */
    @Test
    fun aPolicyEdgeOriginIsReadAsBefore() {
        val edge = PolicyEdge(record, EdgeSummary(id, 0L, 0L, 0L), Demand.NONE, gaugeAt(Int128.of(100L), folded = 0L), 0L, Int128.ZERO, Int128.of(2L))
        val plain = edge.copy(origin = Int128.ZERO)
        assertAll(
            { assertEquals(Int128.of(2L), edge.origin, "the read survives") },
            { assertEquals(Rational.of(68L, 16L), HeddlePolicy.virtualService(edge), "a position less the origin's share") },
            { assertEquals(Rational.of(100L, 16L), HeddlePolicy.virtualService(plain), "and none at zero") },
        )
    }

    /** [EntitlementLedger.origin] reads zero, and `of(origins =)` translates as the format 1 reader does. */
    @Test
    fun theLedgerOriginReadsZeroAndOfTranslates() {
        val third = AttachmentRecord(id, root, GroupId("e"), Weight.of(1L, 3L))
        val ledger = EntitlementLedger.of(
            records = mapOf(id to setOf(third)),
            gauges = mapOf(id to gaugeAt(Int128.of(32L), folded = 0L)),
            origins = mapOf(root to Int128.of(5L)),
        )
        assertAll(
            { assertEquals(Int128.ZERO, ledger.origin(root), "no ledger holds an origin") },
            // 5 · 16 / 3 is 26⅔ ticks: 32 − 26 + 27 = 33.
            { assertEquals(gaugeAt(Int128.of(33L), folded = 0L), ledger.gauge(id), "of(origins =) puts the share back rounded up") },
        )
    }
}
