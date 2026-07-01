package us.tractat.kuilt.warp

import us.tractat.kuilt.crdt.ReplicaId

/** @suppress sample for [FedAvgKernelCodec]. */
public fun sampleFedAvgKernelCodec() {
    // A peer encodes its local batch + the shared model, runs the kernel (omitted), and folds the
    // decoded update into FedAvg. Here we use the reference trainer in place of the wasm run.
    val model = listOf(0.0, 0.0)
    val batch = listOf(1.0 to 3.0, 2.0 to 5.0)            // y = 2x + 1
    val input = FedAvgKernelCodec.encodeInput(model, batch, learnRate = 0.05)
    require(input.isNotEmpty())

    val updatedWeights = ReferenceTrainer.step(model, batch, 0.05)
    val update = TrainingUpdate(batch.size.toLong(), updatedWeights)
    val contribution = update.toContribution(ReplicaId("alice"))
    require(contribution.weights.size == 2)
}
