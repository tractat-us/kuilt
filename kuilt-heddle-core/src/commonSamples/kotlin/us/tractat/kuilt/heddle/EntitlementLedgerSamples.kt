package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.Patch
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
    ledger = ledger.piece(checkNotNull(ledger.prepare(AttachmentRecord(e, root, leaf, Weight.ONE))))
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

/**
 * The pure EEVDF policy picks which child to delegate the next quantum to. Two
 * saturated siblings weighted 3:1 both want service; the heavier one wins the
 * first grant, and over many rounds their committed service converges to 3:1.
 */
@Suppress("unused")
internal fun samplePolicyPick() {
    fun edge(id: String, weight: Weight, issued: Long) = PolicyEdge(
        record = AttachmentRecord(AttachmentId(id), GroupId("root"), GroupId(id), weight),
        summary = EdgeSummary(AttachmentId(id), issued = issued, returned = 0L, spent = issued),
        demand = Demand(targetOutstanding = 100L, maximumUsefulGrant = 100L),
        gauge = null,                      // nothing has seated it yet, so it reads from its own origin
        baseIssued = issued,               // the gauge's fold axis — the base counter, not the effective one
    )

    // Both start level (no service yet); the heavier-weighted child has the earliest
    // virtual deadline, so it is served first.
    val grant = HeddlePolicy.pick(
        edges = listOf(edge("heavy", Weight.of(3), issued = 0L), edge("light", Weight.of(1), issued = 0L)),
        config = PolicyConfig(quantum = 6L),
        localHoldings = 1_000L,
    )
    check(grant == Grant(AttachmentId("heavy"), 6L))

    // A child with no appetite advertises Demand.NONE and is never a candidate.
    val idle = HeddlePolicy.pick(
        edges = listOf(
            PolicyEdge(
                AttachmentRecord(AttachmentId("idle"), GroupId("root"), GroupId("idle"), Weight.ONE),
                EdgeSummary(AttachmentId("idle"), 0L, 0L, 0L),
                Demand.NONE,
                gauge = null,
                baseIssued = 0L,
            ),
        ),
        config = PolicyConfig(quantum = 6L),
        localHoldings = 1_000L,
    )
    check(idle == null)
}

/**
 * Fair share in one process, with no network: keep the ledger in a `var`, hand it to a
 * [FairShareAllocator] as a [LedgerCell], and schedule. Two children weighted 3:1 both want
 * everything, so the root's 400 units split 300 / 100.
 */
@Suppress("unused")
internal fun sampleFairShareAllocator() {
    val root = GroupId("root")
    val self = ReplicaId("self")
    val heavy = AttachmentRecord(AttachmentId("heavy"), root, GroupId("heavy"), Weight.of(3))
    val light = AttachmentRecord(AttachmentId("light"), root, GroupId("light"), Weight.ONE)

    // The whole "network": the latest ledger, and one write step that decides on what it lands on.
    val ledger = object : LedgerCell {
        override var current = EntitlementLedger.bootstrap(root, mapOf(self to 400L), nonce = "genesis")
        override fun mutateOrSkip(transform: (EntitlementLedger) -> Patch<EntitlementLedger>?): Boolean {
            val patch = transform(current) ?: return false
            current = current.piece(patch.delta)
            return true
        }
    }
    for (record in listOf(heavy, light)) {
        ledger.mutateOrSkip { it.prepare(record) }
        ledger.mutateOrSkip { it.activate(record.id) }
    }

    val allocator = FairShareAllocator(self, PolicyConfig(quantum = 10L))
    val wantsEverything = Demand(targetOutstanding = 1_000L, maximumUsefulGrant = 10L)
    allocator.schedule(root, ledger, earmarked = 0L, demand = { wantsEverything })

    check(ledger.current.edge(heavy.id)?.issued == 300L)
    check(ledger.current.edge(light.id)?.issued == 100L)
}
