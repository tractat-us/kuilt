package us.tractat.kuilt.heddle

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * [EntitlementLedger]'s own serializer, for every ledger a peer sends: it writes the same bytes, and
 * it refuses to decode a ledger that has forgotten edges (#3192, design §5.5, §10 invariant 2).
 *
 * Forgetting is one writer's act. `LocalHeddle` is its only caller, and a ledger peers exchange never
 * holds a fold, so a frame carrying one is a corrupt or foreign frame. Refusing it here, as a decode
 * failure, keeps it out of [EntitlementLedger.piece], which throws on a fold that is not the
 * receiver's own. That throw would land inside a replicator's long-lived pump, ending it, and on
 * Kotlin/Native ending the process. A decode failure is what a fold was before `forgotten` existed,
 * when the field was unknown: the replicator logs the frame and drops it, and the control log reads
 * the entry as unreadable.
 *
 * The descriptor is the ledger's own, so nothing that reads it, or the bytes, can tell the two apart.
 */
internal object ReplicatedLedgerSerializer : KSerializer<EntitlementLedger> {
    private val ledger: KSerializer<EntitlementLedger> = EntitlementLedger.serializer()

    override val descriptor: SerialDescriptor get() = ledger.descriptor

    override fun serialize(encoder: Encoder, value: EntitlementLedger): Unit = ledger.serialize(encoder, value)

    override fun deserialize(decoder: Decoder): EntitlementLedger {
        val value = ledger.deserialize(decoder)
        if (value.hasForgotten()) {
            throw SerializationException("a replicated ledger never carries forgotten edges: forgetting is one writer's act")
        }
        return value
    }
}
