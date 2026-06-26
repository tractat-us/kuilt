package us.tractat.kuilt.warp

import us.tractat.kuilt.crdt.ReplicaId

/**
 * One peer's decoded training result: how many examples it trained on and the updated weight
 * vector. Bridges a kernel run (or [ReferenceTrainer.step]) to [FedAvg].
 */
public data class TrainingUpdate(
    public val sampleCount: Long,
    public val weights: List<Double>,
) {
    /** This update as a single-peer [FedAvg] contribution for [peer] at [epoch]. */
    public fun toContribution(peer: ReplicaId, epoch: Long = 1L): FedAvg =
        FedAvg.contribution(peer, sampleCount, weights, epoch)
}
