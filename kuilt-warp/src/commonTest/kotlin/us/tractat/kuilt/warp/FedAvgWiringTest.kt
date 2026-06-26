package us.tractat.kuilt.warp

import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FedAvgWiringTest {

    /**
     * Three peers, each with a different local batch drawn from the same true line y = 2x + 1,
     * each run one reference GD step from a shared start, contribute, and the merged FedAvg moves
     * toward the true weights. Order-independent (CRDT).
     */
    @Test
    fun `three peers' local steps merge toward the true line`() {
        val truth = { x: Double -> 2.0 * x + 1.0 }
        val batches = listOf(
            listOf(0.0, 1.0, 2.0),
            listOf(3.0, 4.0, 5.0),
            listOf(6.0, 7.0, 8.0),
        ).map { xs -> xs.map { it to truth(it) } }

        val start = listOf(0.0, 0.0)   // w0, bias
        val lr = 0.01

        var model = start
        repeat(500) {
            val merged = batches.foldIndexed(FedAvg.ZERO) { i, acc, batch ->
                val updated = ReferenceTrainer.step(model, batch, lr)
                acc.piece(TrainingUpdate(batch.size.toLong(), updated).toContribution(ReplicaId("p$i")))
            }
            model = merged.weights
        }

        assertAll(
            { assertEquals(2.0, model[0], absoluteTolerance = 0.05) },   // slope → 2
            { assertEquals(1.0, model[1], absoluteTolerance = 0.05) },   // bias → 1
        )
    }

    @Test
    fun `merge is order-independent`() {
        val a = TrainingUpdate(2L, listOf(1.0, 4.0)).toContribution(ReplicaId("a"))
        val b = TrainingUpdate(3L, listOf(3.0, 2.0)).toContribution(ReplicaId("b"))
        assertTrue(FedAvg.ZERO.piece(a).piece(b) == FedAvg.ZERO.piece(b).piece(a))
    }
}
