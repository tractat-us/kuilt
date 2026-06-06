package us.tractat.kuilt.conformance

import us.tractat.kuilt.crdt.BoundedCounter
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece

/** BoundedCounter is the product of two GCounter lattices — it obeys every law. */
internal class BoundedCounterConformanceTest : QuiltedConformanceSuite<BoundedCounter>() {
    private val a = ReplicaId("A")
    private val b = ReplicaId("B")

    override fun samples(): List<BoundedCounter> {
        val start = BoundedCounter.init(mapOf(a to 5L, b to 5L))
        val afterASpend = start.piece(start.trySpend(a, 2L)!!)
        val afterTransfer = start.piece(start.transfer(a, b, 3L)!!)
        val afterBothSpends = afterASpend.piece(afterASpend.trySpend(b, 3L)!!)
        return listOf(BoundedCounter.EMPTY, start, afterASpend, afterTransfer, afterBothSpends)
    }
}
