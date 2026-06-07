package us.tractat.kuilt.conformance

import us.tractat.kuilt.crdt.PNCounter
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece

/** PNCounter is a product of two GCounter lattices — it obeys every law. */
internal class PNCounterConformanceTest : QuiltedConformanceSuite<PNCounter>() {
    private val a = ReplicaId("A")
    private val b = ReplicaId("B")

    override fun samples(): List<PNCounter> {
        val zero = PNCounter.ZERO
        val afterAInc = zero.piece(zero.increment(a, 3L))
        val afterBInc = zero.piece(zero.increment(b, 5L))
        val afterADec = afterAInc.piece(afterAInc.decrement(a, 1L))
        val mixed = afterAInc.piece(afterBInc).piece(afterADec.decrement(b, 2L))
        return listOf(zero, afterAInc, afterBInc, afterADec, mixed)
    }
}
