package us.tractat.kuilt.heddle

import kotlinx.serialization.Serializable
import us.tractat.kuilt.crdt.CanonicalMapSerializer
import us.tractat.kuilt.crdt.CanonicalSetSerializer
import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.math.Int128

/**
 * An [EntitlementLedger] as [LocalHeddleSnapshot] format 1 holds it, frozen: field for field, in
 * wire order, with the serializers the ledger had when 0.7.5 shipped the format. Format 1 reads its
 * ledger through this, not through the live type, so the live ledger can drop a field without
 * changing what format 1's bytes mean.
 *
 * It holds the one field the live ledger no longer has, [parents]: each parent's origin, which
 * 0.7.5 raised when it rebased a busy parent. Read, each origin is translated into its children's
 * positions ([EntitlementLedger.of]'s `origins`, design §7.1 "0.7.6 reads format 1 by
 * translating"), and discarded. Written, [parents] is always empty, and an empty map is elided, so
 * 0.7.6 writes format 1 in a layout 0.7.5 already reads.
 *
 * **Never edit a field here to make a test pass.** The goldens in `LocalHeddleSnapshotGolden` are
 * what this must keep reading, the one 0.7.5 wrote with origins in it included.
 */
@Serializable
internal class LedgerFormat1(
    @Serializable(with = CanonicalMapSerializer::class)
    val records: Map<AttachmentId, @Serializable(with = CanonicalSetSerializer::class) Set<AttachmentRecord>>,
    @Serializable(with = CanonicalMapSerializer::class)
    val minted: Map<MintId, MintRecord>,
    @Serializable(with = CanonicalMapSerializer::class)
    val issued: Map<AttachmentId, GCounter>,
    @Serializable(with = CanonicalMapSerializer::class)
    val returned: Map<AttachmentId, GCounter>,
    @Serializable(with = CanonicalMapSerializer::class)
    val leafSpent: Map<AttachmentId, GCounter>,
    @Serializable(with = CanonicalMapSerializer::class)
    val rollupSpent: Map<AttachmentId, GCounter>,
    @Serializable(with = CanonicalMapSerializer::class)
    val transfers: Map<PathKey, @Serializable(with = CanonicalMapSerializer::class) Map<ReplicaId, GCounter>>,
    @Serializable(with = CanonicalMapSerializer::class)
    val lifecycle: Map<AttachmentId, Lifecycle> = emptyMap(),
    @Serializable(with = CanonicalMapSerializer::class)
    val issuedRelocIn: Map<AttachmentId, GCounter> = emptyMap(),
    @Serializable(with = CanonicalMapSerializer::class)
    val leafRelocIn: Map<AttachmentId, GCounter> = emptyMap(),
    @Serializable(with = CanonicalMapSerializer::class)
    val leafRelocOut: Map<AttachmentId, GCounter> = emptyMap(),
    @Serializable(with = CanonicalMapSerializer::class)
    val rollupRelocIn: Map<AttachmentId, GCounter> = emptyMap(),
    @Serializable(with = CanonicalMapSerializer::class)
    val rollupRelocOut: Map<AttachmentId, GCounter> = emptyMap(),
    @Serializable(with = CanonicalMapSerializer::class)
    val gauges: Map<AttachmentId, Gauge> = emptyMap(),
    @Serializable(with = CanonicalMapSerializer::class)
    val transferRelocIn: Map<PathKey, @Serializable(with = CanonicalMapSerializer::class) Map<ReplicaId, GCounter>> = emptyMap(),
    @Serializable(with = CanonicalMapSerializer::class)
    val transferRelocOut: Map<PathKey, @Serializable(with = CanonicalMapSerializer::class) Map<ReplicaId, GCounter>> = emptyMap(),
    @Serializable(with = CanonicalMapSerializer::class)
    val parents: Map<GroupId, ParentFormat1> = emptyMap(),
    val forgotten: ForgottenEdges = ForgottenEdges.NONE,
) {
    /** The ledger, each origin in [parents] translated into its children's positions. */
    @OptIn(HeddleInternalApi::class)
    fun toLedger(): EntitlementLedger = EntitlementLedger.of(
        records = records,
        minted = minted,
        issued = issued,
        returned = returned,
        leafSpent = leafSpent,
        rollupSpent = rollupSpent,
        transfers = transfers,
        lifecycle = lifecycle,
        issuedRelocIn = issuedRelocIn,
        leafRelocIn = leafRelocIn,
        leafRelocOut = leafRelocOut,
        rollupRelocIn = rollupRelocIn,
        rollupRelocOut = rollupRelocOut,
        gauges = gauges,
        transferRelocIn = transferRelocIn,
        transferRelocOut = transferRelocOut,
        forgottenOutflow = forgotten.outflow,
        forgottenLeafSpend = forgotten.leafSpend,
        forgottenGenerationFloor = forgotten.generationFloor,
        origins = parents.mapValues { (_, record) -> record.origin },
    )
}

/**
 * One parent's record in format 1, as 0.7.5 wrote it: its origin in whole units of virtual time,
 * two words, each elided at zero, never negative.
 */
@Serializable
internal class ParentFormat1(val originHigh: Long = 0L, val originLow: Long = 0L) {
    init {
        nonNegativeWide(originHigh, originLow, "an origin")
    }

    /** The origin in whole virtual-time units. */
    val origin: Int128 get() = Int128.fromTwosComplement(originHigh, originLow)
}
