package us.tractat.kuilt.heddle

import kotlinx.coroutines.CoroutineScope
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.crdt.ReplicaId
import kotlin.time.Instant

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
    val e = AttachmentRecord(AttachmentId("root→leaf"), root, leaf, Weight.ONE)

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
    val outcome = node.reserve(leaf, maximumCost = 10L)
    if (outcome is ReserveOutcome.Reserved) {
        node.complete(outcome.id, actualCost = 7L)
        node.complete(outcome.id, actualCost = 7L) // idempotent no-op
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

    // Before enrolling, the write gate is closed and both spend verbs say so rather than looking idle.
    check(node.schedule(root) is GateClosed)

    // Enrolling self is what opens this node's write gate: until it applies, `reserve` and `schedule`
    // answer GateClosed, so an unenrolled peer can never author entitlement (#1693, #1892).
    check(node.enroll(self) is ControlOutcome.Applied)

    // Mint and reshape are serialized through the Raft log — each returns a structured outcome.
    check(node.mint(self, 100L) is ControlOutcome.Applied)
    node.prepare(AttachmentRecord(edge, root, leaf, Weight.ONE))
    node.activate(edge)

    // The spend path is coordination-free — it issues no consensus messages.
    node.advertise(edge, Demand(targetOutstanding = 100L, maximumUsefulGrant = 100L))
    node.schedule(root) // Delegated, or NothingToDelegate if no holdings can move
    when (val outcome = node.reserve(leaf, maximumCost = 10L)) {
        is ReserveOutcome.Reserved -> node.complete(outcome.id, actualCost = 7L)
        ReserveOutcome.NoHoldings -> Unit // lane exhausted — try again once entitlement flows in
        is GateClosed -> error("enrolled above, yet the write gate is closed: ${outcome.reason}")
    }
}
