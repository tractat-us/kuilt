package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.ReplicaId

/** The writer every [LocalHeddle] test reads holdings as. */
internal val LOCAL: ReplicaId = LocalHeddle.SELF

/** One edge `parent → child` at [weight], named after both ends. */
internal fun edgeOf(parent: GroupId, child: GroupId, weight: Long = 1L): AttachmentRecord =
    AttachmentRecord(AttachmentId("${parent.value}>${child.value}"), parent, child, Weight.of(weight))

/** A ledger rooted at [root] with every record in [active] prepared and activated, and [prepared] only prepared. */
internal fun shapedLedger(
    root: GroupId,
    active: List<AttachmentRecord>,
    prepared: List<AttachmentRecord> = emptyList(),
): EntitlementLedger {
    var l = EntitlementLedger.bootstrap(root, emptyMap(), nonce = "genesis")
    for (r in active + prepared) l = l.piece(checkNotNull(l.prepare(r)) { "prepare ${r.id.value}" }.delta)
    for (r in active) l = l.piece(checkNotNull(l.activate(r.id)) { "activate ${r.id.value}" }.delta)
    return l
}

/** An engine over [ledger] holding [supply] at the root, epoch 0 unless told otherwise. */
internal fun engineOver(
    ledger: EntitlementLedger,
    root: GroupId,
    supply: Long,
    policy: LocalPolicy = LocalPolicy(defaultReservation = 1L),
    epoch: Long = 0L,
): LocalHeddle = LocalHeddle.over(ledger, root, policy, epoch).also { if (supply > 0L) it.mintSupply(supply) }

/** Every group any record in [ledger] names, sorted. */
internal fun groupsOf(ledger: EntitlementLedger): List<GroupId> =
    ledger.allEdges().flatMap { e -> listOfNotNull(ledger.record(e)?.parent, ledger.record(e)?.child) }.distinct().sorted()

/** Acquire with [eligible] as both sets, and assert it leased. */
internal fun LocalHeddle.lease(eligible: Set<GroupId>, reservation: Long = 1L, startedAt: Long = 0L): Lease {
    val out = acquire(eligible, eligible, startedAt, reservation)
    check(out is AcquireOutcome.Acquired) { "expected a lease among $eligible, got $out" }
    return out.lease
}
