package us.tractat.kuilt.heddle

import kotlinx.coroutines.CoroutineScope
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece
import kotlin.time.Instant

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

/**
 * Bootstrap a node over a live [Seam] with a fixed roster, advertise appetite, run an
 * allocation round, then reserve and complete leaf work. Every peer that calls
 * [heddleStatic] with the same root, mint, and topology begins from an identical ledger
 * and stays in step over the fabric.
 */
@Suppress("unused")
internal fun CoroutineScope.sampleHeddleNode(seam: Seam) {
    val root = GroupId("root")
    val leaf = GroupId("leaf")
    val self = ReplicaId(seam.selfId.value)
    val e = AttachmentRecord(AttachmentId("root→leaf"), root, leaf, Weight.ONE, initialVirtualTime = 0L)

    val node = heddleStatic(
        seam = seam,
        self = self,
        root = root,
        mint = mapOf(self to 100L),        // this peer starts holding 100 units at the root
        topology = listOf(e),              // root → leaf, prepared and active at bootstrap
        clock = { Instant.fromEpochMilliseconds(0L) },
        config = HeddleConfig(policy = PolicyConfig(quantum = 10L), maxHoldingsPerPeer = 1_000L),
        epoch = 1L,                        // a persisted monotonic boot counter — bumped every restart
    )

    // The leaf wants work; one scheduling round delegates entitlement down toward it.
    node.advertise(e.id, Demand(targetOutstanding = 100L, maximumUsefulGrant = 100L))
    node.schedule(root)

    // Leaf work reserves a slice, runs, then completes — completing twice charges once.
    val reservation = node.reserve(leaf, maximumCost = 10L)
    if (reservation != null) {
        node.complete(reservation, actualCost = 7L)
        node.complete(reservation, actualCost = 7L) // idempotent no-op
    }
}

/**
 * Bootstrap a **Raft-governed** node: the same data plane as [heddleStatic], but supply and topology
 * are created at runtime through the consensus log, so a split-brain can never both mint and two
 * overlapping reshapes serialize (the loser surfaces as a [ControlConflict]). The spend path
 * (`schedule`/`reserve`/`complete`) never touches the log. Here `raft` is any [RaftNode] over the
 * cluster; in tests it comes from `MultiNodeRaftSim`.
 */
@Suppress("unused")
internal suspend fun CoroutineScope.sampleHeddleGoverned(seam: Seam, raft: us.tractat.kuilt.raft.RaftNode) {
    val root = GroupId("root")
    val leaf = GroupId("leaf")
    val self = ReplicaId(seam.selfId.value)
    val edge = AttachmentId("root→leaf")

    val node = heddleGoverned(
        seam = seam,
        self = self,
        raft = raft,
        root = root,
        clock = { Instant.fromEpochMilliseconds(0L) },
        config = HeddleConfig(policy = PolicyConfig(quantum = 10L), maxHoldingsPerPeer = 1_000L),
        incarnation = "boot-2026-07-24T00:00:00Z", // fresh per process incarnation — a boot id / epoch / UUID
        epoch = 1L,                                // numeric per-boot counter — bumped every restart
    )

    // Enrolling self is what opens this node's write gate: until it applies, `reserve` returns null
    // and `schedule` delegates nothing, so an unenrolled peer can never author entitlement (#1693).
    check(node.enroll(self) is ControlOutcome.Applied)

    // Mint and reshape are serialized through the Raft log — each returns a structured outcome.
    check(node.mint(self, 100L) is ControlOutcome.Applied)
    node.prepare(AttachmentRecord(edge, root, leaf, Weight.ONE, initialVirtualTime = 0L))
    node.activate(edge)

    // The spend path is coordination-free — it issues no consensus messages.
    node.advertise(edge, Demand(targetOutstanding = 100L, maximumUsefulGrant = 100L))
    node.schedule(root)
    node.reserve(leaf, maximumCost = 10L)?.let { node.complete(it, actualCost = 7L) }
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
        record = AttachmentRecord(AttachmentId(id), GroupId("root"), GroupId(id), weight, initialVirtualTime = 0L),
        summary = EdgeSummary(AttachmentId(id), issued = issued, returned = 0L, spent = issued),
        demand = Demand(targetOutstanding = 100L, maximumUsefulGrant = 100L),
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
                AttachmentRecord(AttachmentId("idle"), GroupId("root"), GroupId("idle"), Weight.ONE, 0L),
                EdgeSummary(AttachmentId("idle"), 0L, 0L, 0L),
                Demand.NONE,
            ),
        ),
        config = PolicyConfig(quantum = 6L),
        localHoldings = 1_000L,
    )
    check(idle == null)
}
