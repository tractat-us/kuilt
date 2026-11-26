package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece

/**
 * Vet a weight before proposing it, then ask whether activating an edge fits beside the children
 * already active at its parent. A `1/3` fits alone, yet is refused beside a very heavy sibling,
 * because it would triple that sibling's grid step.
 */
@Suppress("unused")
internal fun sampleWeightBudget() {
    // 1/30,719 is the finest weight one full lease fits; one step finer fails at any parent.
    check(weightFitsTheBudget(Weight.of(1L, 30_719L)))
    check(!weightFitsTheBudget(Weight.of(1L, 30_720L)))

    val root = GroupId("root")
    val alice = ReplicaId("alice")
    val toHeavy = AttachmentId("root→heavy")
    val toThird = AttachmentId("root→third")
    val heavy = Weight.of(185_000_000_000_000_000L)
    val third = Weight.of(1L, 3L)
    check(weightFitsTheBudget(heavy) && weightFitsTheBudget(third))

    var ledger = EntitlementLedger.bootstrap(root, mapOf(alice to 100L), nonce = "genesis")
    ledger = ledger.piece(checkNotNull(ledger.prepare(AttachmentRecord(toHeavy, root, GroupId("heavy"), heavy))))
    ledger = ledger.piece(checkNotNull(ledger.activate(toHeavy)))
    ledger = ledger.piece(checkNotNull(ledger.prepare(AttachmentRecord(toThird, root, GroupId("third"), third))))

    // Each fits alone, but not together: ask before proposing the activation, not after.
    check(!ledger.activationFitsTheBudget(toThird))
}
