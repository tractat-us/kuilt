package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.ReplicaId

/**
 * Two peers each bootstrap the same root supply and merge their copies. The merge
 * is order-independent, so both sides converge to one ledger.
 */
@Suppress("unused")
internal fun sampleEntitlementLedgerMerge() {
    val root = GroupId("root")
    val alice = ReplicaId("alice")
    val bob = ReplicaId("bob")

    // The same root supply, observed independently on two peers.
    val onAlice = EntitlementLedger.bootstrap(root, mapOf(alice to 100L, bob to 100L))
    val onBob = EntitlementLedger.bootstrap(root, mapOf(alice to 100L, bob to 100L))

    // Merging is idempotent, commutative, and associative — either order agrees.
    check(onAlice.piece(onBob) == onBob.piece(onAlice))

    // Reading one edge's summary is a pure projection (null here — no edges minted yet).
    check(onAlice.edge(AttachmentId("acme")) == null)
}

/** Weights order by exact cross-multiplication — see [Weight]. */
@Suppress("unused")
internal fun sampleWeightOrdering() {
    // Weights are compared by exact cross-multiplication, never floating point.
    check(Weight.of(1, 3) < Weight.of(1, 2))
    check(Weight.of(2, 4) == Weight.of(1, 2))
}
