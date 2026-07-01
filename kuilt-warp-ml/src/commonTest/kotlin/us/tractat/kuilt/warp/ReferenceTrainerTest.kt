package us.tractat.kuilt.warp

import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

class ReferenceTrainerTest {

    @Test
    fun `one GD step matches hand-computed weights`() {
        // w=[w0=1.0, b=0.0], examples (x,y): (1,2),(2,3) ; lr=0.1
        // i=0: pred=1*1+0=1, err=1-2=-1 ; i=1: pred=1*2+0=2, err=2-3=-1
        // gw0=(-1*1)+(-1*2)=-3 ; gb=(-1)+(-1)=-2 ; scale=2/2=1.0
        // w0'=1 - 0.1*1.0*(-3)=1.3 ; b'=0 - 0.1*1.0*(-2)=0.2
        val out = ReferenceTrainer.step(
            weights = listOf(1.0, 0.0),
            examples = listOf(1.0 to 2.0, 2.0 to 3.0),
            learnRate = 0.1,
        )
        assertAll(
            { assertEquals(1.3, out[0], absoluteTolerance = 1e-12) },
            { assertEquals(0.2, out[1], absoluteTolerance = 1e-12) },
        )
    }

    @Test
    fun `update converts to a FedAvg contribution`() {
        val update = TrainingUpdate(sampleCount = 2L, weights = listOf(1.3, 0.2))
        val merged = FedAvg.ZERO.piece(update.toContribution(ReplicaId("p")))
        assertAll(
            { assertEquals(1.3, merged.weights[0], absoluteTolerance = 1e-12) },
            { assertEquals(0.2, merged.weights[1], absoluteTolerance = 1e-12) },
        )
    }
}
