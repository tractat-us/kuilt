package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece

/**
 * Two peers each bootstrap the same root supply and merge their copies. The merge
 * is order-independent, so both sides converge to one ledger.
 */
@Suppress("unused")
internal fun sampleEntitlementLedgerMerge() {
    val root = GroupId("root")
    val alice = ReplicaId("alice")
    val bob = ReplicaId("bob")

    // The same mint act (same nonce) observed independently on two peers.
    val onAlice = EntitlementLedger.bootstrap(root, mapOf(alice to 100L, bob to 100L), nonce = "genesis")
    val onBob = EntitlementLedger.bootstrap(root, mapOf(alice to 100L, bob to 100L), nonce = "genesis")

    // Merging is idempotent, commutative, and associative — either order agrees.
    check(onAlice.piece(onBob) == onBob.piece(onAlice))

    // Reading one edge's summary is a pure projection (null here — no edges minted yet).
    check(onAlice.edge(AttachmentId("acme")) == null)
}

/**
 * An edge climbs its lifecycle chain under strict generation-and-drain: prepare (no
 * entitlement crosses) → activate (delegation opens) → close (no *new* delegation, still
 * drains) → retire (only once fully drained). A retire is refused while entitlement is
 * still outstanding.
 */
@Suppress("unused")
internal fun sampleEntitlementLedgerLifecycle() {
    val root = GroupId("root")
    val leaf = GroupId("leaf")
    val alice = ReplicaId("alice")
    val e = AttachmentId("root→leaf")

    var ledger = EntitlementLedger.bootstrap(root, mapOf(alice to 100L), nonce = "genesis")
    // prepare then activate the edge, then delegate 10 units down it.
    ledger = ledger.piece(checkNotNull(ledger.prepare(AttachmentRecord(e, root, leaf, Weight.ONE, 0L))))
    ledger = ledger.piece(checkNotNull(ledger.activate(e)))
    ledger = ledger.piece(checkNotNull(ledger.delegate(alice, e, 10L)))

    // Close the edge, then try to retire it while entitlement is still outstanding — refused.
    ledger = ledger.piece(checkNotNull(ledger.close(e)))
    check(ledger.retire(e) == null) // 10 units still outstanding

    // Drain it (return the grant), then retire succeeds; the register now reads RETIRED.
    ledger = ledger.piece(checkNotNull(ledger.release(alice, e, 10L)))
    ledger = ledger.piece(checkNotNull(ledger.retire(e)))
    check(ledger.lifecycle(e) == Lifecycle.RETIRED)
}

/** Weights order by exact cross-multiplication — see [Weight]. */
@Suppress("unused")
internal fun sampleWeightOrdering() {
    // Weights are compared by exact cross-multiplication, never floating point.
    check(Weight.of(1, 3) < Weight.of(1, 2))
    check(Weight.of(2, 4) == Weight.of(1, 2))
}
