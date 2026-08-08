package us.tractat.kuilt.crdt

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The bulk siblings of [Rga.insertAfter] / [Rga.removeAt] must be *indistinguishable*
 * from the per-element loop they replace — same visible sequence, same op-set, same
 * ids, same Lamport clock — while paying one `ops + newOps` copy instead of one per
 * element (#2194).
 *
 * Equivalence is the load-bearing property: `WarpLogRecordExporter` persists these
 * ops verbatim and gossips them, so an id or a Lamport value that differed from the
 * per-element path would be a wire-format change, not an optimisation.
 */
class RgaBulkMutatorTest {

    private val replica = ReplicaId("r1")

    private fun perElementAppend(values: List<String>): Pair<Rga<String>, List<RgaOp.Insert<String>>> {
        var state = Rga.empty<String>()
        var after = RgaId.HEAD
        val ops = mutableListOf<RgaOp.Insert<String>>()
        values.forEach { value ->
            val (next, op) = state.insertAfter(replica = replica, after = after, value = value)
            state = next
            after = op.id
            ops += op
        }
        return state to ops
    }

    @Test
    fun appendingAsARunIsIndistinguishableFromAppendingOneAtATime() {
        val values = listOf("a", "b", "c", "d")
        val (looped, loopedOps) = perElementAppend(values)
        val (bulk, bulkOps) = Rga.empty<String>().insertAllAfter(replica, RgaId.HEAD, values)

        assertAll(
            { assertEquals(values, bulk.toList()) },
            { assertEquals(looped.toList(), bulk.toList()) },
            { assertEquals(loopedOps.map { it.id }, bulkOps.map { it.id }, "ids must match the per-element path") },
            { assertEquals(loopedOps.map { it.after }, bulkOps.map { it.after }, "the chain must be after-linked") },
            { assertEquals(looped.lamport, bulk.lamport) },
            { assertEquals(looped.opCount, bulk.opCount) },
        )
    }

    @Test
    fun appendingARunOntoANonEmptyLogChainsOffTheGivenPredecessor() {
        val (seeded, seededOps) = Rga.empty<String>().insertAllAfter(replica, RgaId.HEAD, listOf("a", "b"))
        val (grown, grownOps) = seeded.insertAllAfter(replica, seededOps.last().id, listOf("c", "d"))

        assertAll(
            { assertEquals(listOf("a", "b", "c", "d"), grown.toList()) },
            { assertEquals(seededOps.last().id, grownOps.first().after) },
        )
    }

    @Test
    fun appendingAnEmptyRunIsIdentity() {
        val (seeded, _) = Rga.empty<String>().insertAllAfter(replica, RgaId.HEAD, listOf("a"))
        val (after, ops) = seeded.insertAllAfter(replica, RgaId.HEAD, emptyList())

        assertAll(
            { assertTrue(after === seeded, "an empty run must return the same instance, not a copy") },
            { assertEquals(emptyList(), ops) },
        )
    }

    @Test
    fun removingALeadingRunIsIndistinguishableFromRemovingOneAtATime() {
        val (seeded, _) = Rga.empty<String>().insertAllAfter(replica, RgaId.HEAD, listOf("a", "b", "c", "d"))

        var looped = seeded
        repeat(2) { looped = looped.removeAt(0)!!.first }
        val (bulk, removes) = seeded.removeFirst(2)

        assertAll(
            { assertEquals(listOf("c", "d"), bulk.toList()) },
            { assertEquals(looped.toList(), bulk.toList()) },
            { assertEquals(looped.tombstones, bulk.tombstones) },
            { assertEquals(2, removes.size) },
            { assertEquals(seeded.sequence.take(2), removes.map { it.id }, "removes must name the leading visible ids") },
        )
    }

    @Test
    fun removingZeroIsIdentityAndRemovingMoreThanIsVisibleFails() {
        val (seeded, _) = Rga.empty<String>().insertAllAfter(replica, RgaId.HEAD, listOf("a", "b"))

        assertAll(
            { assertTrue(seeded.removeFirst(0).first === seeded) },
            { assertTrue(seeded.removeFirst(-1).first === seeded) },
            { assertFailsWith<IllegalArgumentException> { seeded.removeFirst(3) } },
        )
    }

    /**
     * Tombstones are skipped, not counted: `removeFirst` takes the first `count`
     * **visible** elements, so a log that already carries a tombstone at the head
     * removes the same records the per-element loop would.
     */
    @Test
    fun removingALeadingRunSkipsExistingTombstones() {
        val (seeded, _) = Rga.empty<String>().insertAllAfter(replica, RgaId.HEAD, listOf("a", "b", "c"))
        val (tombstoned, _) = seeded.removeFirst(1)
        val (bulk, removes) = tombstoned.removeFirst(1)

        assertAll(
            { assertEquals(listOf("c"), bulk.toList()) },
            { assertEquals(1, removes.size) },
        )
    }
}
