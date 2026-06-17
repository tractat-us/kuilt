package us.tractat.kuilt.quilter

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import us.tractat.kuilt.crdt.ReplicaId

/**
 * Wire messages exchanged by [BoundedCounterTransferCoordinator] instances.
 *
 * These travel over the coordinator's [us.tractat.kuilt.core.MuxSeam] channel (tag `0x01`),
 * distinct from the replicator's delta/ack/fullState channel (tag `0x00`). The protocol is advisory:
 * a donor may refuse a request (zero surplus), and the requester degrades gracefully to
 * "deny locally" until quota arrives via a future transfer.
 */
@Serializable
public sealed class BoundedCounterCoordMessage {

    /**
     * Sent by a replica whose local quota has dropped below its low-water threshold.
     * Every peer receiving this may independently decide to call
     * [us.tractat.kuilt.crdt.BoundedCounter.transfer] if it has surplus.
     *
     * @param requester the [ReplicaId] asking for quota.
     * @param amount how many units the requester would like.
     */
    @Serializable
    @SerialName("transferRequest")
    public class TransferRequest(
        public val requester: ReplicaId,
        public val amount: Long,
    ) : BoundedCounterCoordMessage()
}
