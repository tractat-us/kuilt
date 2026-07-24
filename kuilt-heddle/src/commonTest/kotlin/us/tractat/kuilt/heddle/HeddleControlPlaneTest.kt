package us.tractat.kuilt.heddle

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.raft.LogEntry
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftNode
import us.tractat.kuilt.raft.RaftRole
import us.tractat.kuilt.raft.test.FakeRaftNode
import us.tractat.kuilt.raft.test.MultiNodeRaftSim
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * H5 acceptance suite (design §15 Phase 5, §9 "the embroidery", §10.13): the Raft-backed control
 * plane — mint and topology reconfiguration serialized on the consensus log, while the spend path
 * stays coordination-free.
 *
 * **Test discipline (repo CLAUDE.md).** Consensus tests run through the canonical `MultiNodeRaftSim`
 * from `:kuilt-raft-test` — never a hand-rolled cluster network: `StandardTestDispatcher`, tight 5 s
 * timeout, node coroutines on `backgroundScope`, per-node seeded election RNG, bounded `await*`
 * helpers only (never `advanceUntilIdle`). The single-node message-accounting test uses a
 * [FakeRaftNode] (also a `:kuilt-raft-test` double) — the data plane's zero-consensus property is
 * structural and needs no convergence.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HeddleControlPlaneTest {

    private val root = GroupId("root")

    // ═══════════════════════════════════════════════════════════════════════════
    // 1. Split-brain mint impossible (§9 #1) — a partitioned minority can never
    //    commit a mint; at most one side mints against a given supply.
    // ═══════════════════════════════════════════════════════════════════════════
    @Test
    fun splitBrainMintImpossible() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val ids = (1..5).map { NodeId("v$it") }
        val sim = MultiNodeRaftSim(nodeIds = ids, scope = this, nodeScope = backgroundScope)
        val sinks = ids.associateWith { InMemoryLedgerControl(EntitlementLedger.ZERO) }
        val planes = ids.associateWith { HeddleControlPlane(sim.nodes.getValue(it), backgroundScope, sinks.getValue(it)) }

        sim.awaitLeader()
        val majority = setOf(NodeId("v1"), NodeId("v2"), NodeId("v3"))
        val minority = setOf(NodeId("v4"), NodeId("v5"))
        sim.partition(majority, minority)
        sim.awaitLeader(among = majority)

        val holder = ReplicaId("acme")
        // Majority proposes a mint — it can reach quorum, so it commits.
        val majMint = backgroundScope.async { planes.getValue(NodeId("v1")).submit(ControlCommand.Mint(MintId("maj"), holder, 100L)) }
        // Minority proposes a mint of its own — it can NEVER reach quorum, so it must never commit
        // (it stays pending on backgroundScope, cancelled at teardown).
        val minMint = backgroundScope.async { planes.getValue(NodeId("v4")).submit(ControlCommand.Mint(MintId("min"), holder, 100L)) }

        // Wait until the mint has committed AND every majority node's apply loop has caught up.
        sim.awaitTrue("majority mint committed and applied across the majority") {
            majMint.isCompleted && majority.all { sinks.getValue(it).snapshot().mintedTotal() == 100L }
        }
        assertIs<ControlOutcome.Applied>(majMint.await())
        assertFalse(minMint.isCompleted, "minority partition committed a mint — split-brain mint")

        // No minority node minted anything — it cannot reach quorum.
        minority.forEach { assertEquals(0L, sinks.getValue(it).snapshot().mintedTotal(), "minority $it minted while partitioned") }

        // Heal: the minority catches up to the one committed mint (its own proposal is discarded).
        sim.heal()
        sim.awaitLeader()
        sim.awaitTrue("all nodes converge to exactly one mint") {
            ids.all { sinks.getValue(it).snapshot().mintedTotal() == 100L }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 2. Overlapping reshapes serialize; the loser surfaces as a structured conflict
    //    (§9 #2, §5.2, §10.11) — not a silent drop, not last-writer-wins.
    // ═══════════════════════════════════════════════════════════════════════════
    @Test
    fun overlappingReshapesSerializeLoserSurfacesConflict() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val ids = (1..3).map { NodeId("v$it") }
        val sim = MultiNodeRaftSim(nodeIds = ids, scope = this, nodeScope = backgroundScope)
        val sinks = ids.associateWith { InMemoryLedgerControl(EntitlementLedger.ZERO) }
        val planes = ids.associateWith { HeddleControlPlane(sim.nodes.getValue(it), backgroundScope, sinks.getValue(it)) }
        sim.awaitLeader()

        // One child `c`, two candidate inbound generations from different parents — an overlapping
        // reshape: two peers each try to attach `c` to a different parent.
        val c = GroupId("c")
        val eA = AttachmentId("eA")
        val eB = AttachmentId("eB")
        val v1 = planes.getValue(NodeId("v1"))
        val v2 = planes.getValue(NodeId("v2"))

        // Both generations are prepared (no conflict at prepare — different ids).
        awaitOutcome(sim, backgroundScope) { v1.submit(ControlCommand.Prepare(AttachmentRecord(eA, GroupId("pA"), c, Weight.ONE, 0L))) }
        awaitOutcome(sim, backgroundScope) { v1.submit(ControlCommand.Prepare(AttachmentRecord(eB, GroupId("pB"), c, Weight.ONE, 0L))) }

        // Two peers concurrently activate a different inbound edge for `c`. The log serializes them.
        val actA = async { v1.submit(ControlCommand.Activate(eA)) }
        val actB = async { v2.submit(ControlCommand.Activate(eB)) }
        sim.awaitTrue("both activates committed") { actA.isCompleted && actB.isCompleted }

        val outcomes = listOf(actA.await(), actB.await())
        val applied = outcomes.filterIsInstance<ControlOutcome.Applied>()
        val conflicts = outcomes.filterIsInstance<ControlOutcome.Conflict>()
        assertEquals(1, applied.size, "exactly one overlapping reshape must win, got $outcomes")
        assertEquals(1, conflicts.size, "the loser must surface as a conflict, got $outcomes")

        val dual = conflicts.single().conflict
        assertIs<ControlConflict.DualInbound>(dual)
        assertEquals(c, dual.child)
        assertTrue(dual.incumbent != dual.rejected)
        assertTrue(dual.rejected in setOf(eA, eB) && dual.incumbent in setOf(eA, eB))

        // The loser was REFUSED, not applied-and-quarantined: `c` has exactly one live inbound edge
        // on every node (a silent-drop-into-quarantine would leave two).
        sim.awaitTrue("child has exactly one live inbound on every node") {
            ids.all { sinks.getValue(it).snapshot().liveInboundEdges(c).size == 1 }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 3. Non-overlapping reshapes commit independently — no contention.
    // ═══════════════════════════════════════════════════════════════════════════
    @Test
    fun nonOverlappingReshapesCommitIndependently() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val ids = (1..3).map { NodeId("v$it") }
        val sim = MultiNodeRaftSim(nodeIds = ids, scope = this, nodeScope = backgroundScope)
        val sinks = ids.associateWith { InMemoryLedgerControl(EntitlementLedger.ZERO) }
        val planes = ids.associateWith { HeddleControlPlane(sim.nodes.getValue(it), backgroundScope, sinks.getValue(it)) }
        sim.awaitLeader()

        // Two DIFFERENT children, each reshaped by a different peer — no shared edge, no contention.
        val c1 = GroupId("c1")
        val c2 = GroupId("c2")
        val e1 = AttachmentId("e1")
        val e2 = AttachmentId("e2")
        val v1 = planes.getValue(NodeId("v1"))
        val v2 = planes.getValue(NodeId("v2"))
        awaitOutcome(sim, backgroundScope) { v1.submit(ControlCommand.Prepare(AttachmentRecord(e1, root, c1, Weight.ONE, 0L))) }
        awaitOutcome(sim, backgroundScope) { v2.submit(ControlCommand.Prepare(AttachmentRecord(e2, root, c2, Weight.ONE, 0L))) }

        val a1 = async { v1.submit(ControlCommand.Activate(e1)) }
        val a2 = async { v2.submit(ControlCommand.Activate(e2)) }
        sim.awaitTrue("both independent activates committed") { a1.isCompleted && a2.isCompleted }

        assertIs<ControlOutcome.Applied>(a1.await())
        assertIs<ControlOutcome.Applied>(a2.await())
        sim.awaitTrue("both children have a live inbound on every node") {
            ids.all {
                val s = sinks.getValue(it).snapshot()
                s.liveInboundEdges(c1).size == 1 && s.liveInboundEdges(c2).size == 1
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 4. Zero consensus messages on the spend path (§10.13 message accounting) — the
    //    coordination-free spend/schedule/reserve path never calls into Raft, even
    //    while a control-plane proposal is in flight. A message-COUNT assertion.
    // ═══════════════════════════════════════════════════════════════════════════
    @Test
    fun spendPathIssuesZeroConsensusMessages() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val fake = FakeRaftNode(selfId = NodeId("solo"), initialRole = RaftRole.Leader)
        val counting = CountingRaftNode(fake)
        val loom = InMemoryLoom()
        val seam: Seam = loom.host(Pattern("heddle-h5-msg"))
        val self = ReplicaId(seam.selfId.value)
        val clock: () -> Instant = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }

        val governed = backgroundScope.heddleGoverned(
            seam = seam, self = self, raft = counting, root = root, clock = clock, config = config(seed = 7),
        )

        // Control-plane setup (these DO consense — mint + reshape ride the log): seed a leaf holding.
        val leaf = GroupId("leaf")
        val eLeaf = AttachmentId("eLeaf")
        assertIs<ControlOutcome.Applied>(governed.mint(self, 1_000L))
        assertIs<ControlOutcome.Applied>(governed.prepare(AttachmentRecord(eLeaf, root, leaf, Weight.ONE, 0L)))
        assertIs<ControlOutcome.Applied>(governed.activate(eLeaf))
        assertTrue(counting.consensusCalls > 0, "control-plane setup must issue consensus messages")
        governed.advertise(eLeaf, Demand(targetOutstanding = 1_000L, maximumUsefulGrant = 1_000L))
        governed.schedule(root) // delegate root supply down to the leaf (data-plane)
        assertTrue(governed.ledger.value.holdings(leaf, self) > 0L, "schedule should have seeded the leaf")

        // Put a control-plane proposal genuinely in flight (it never commits) and hold the baseline.
        fake.proposeBehavior = { awaitCancellation() }
        val inFlight = backgroundScope.async { governed.mint(self, 1L) }
        testScheduler.runCurrent() // let the in-flight proposal reach the raft `propose` (count++), then hang
        assertFalse(inFlight.isCompleted, "the in-flight proposal must still be pending")

        val baseline = counting.consensusCalls
        // The spend/schedule/reserve path — zero consensus messages, even with a proposal in flight.
        governed.advertise(eLeaf, Demand(targetOutstanding = 500L, maximumUsefulGrant = 500L))
        governed.schedule(root)
        val reservation = governed.reserve(leaf, 10L)
        assertNotNull(reservation, "reservation must succeed while a control-plane proposal is in flight")
        governed.complete(reservation, 7L)
        governed.boundMetrics(root)
        governed.earmarked(leaf)

        assertEquals(baseline, counting.consensusCalls, "the data-plane spend path issued consensus messages")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 5. The readIndex()-fenced revocation seam is specified but not shipped (§9 #3).
    // ═══════════════════════════════════════════════════════════════════════════
    @Test
    fun revocationSeamSpecifiedNotShipped() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val fake = FakeRaftNode(selfId = NodeId("solo"), initialRole = RaftRole.Leader)
        val loom = InMemoryLoom()
        val seam: Seam = loom.host(Pattern("heddle-h5-revoke"))
        val self = ReplicaId(seam.selfId.value)
        val governed = backgroundScope.heddleGoverned(
            seam = seam, self = self, raft = fake, root = root,
            clock = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }, config = config(seed = 9),
        )
        assertEquals(RevocationOutcome.NotShipped, governed.revocation.revoke(self, root))
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // harness helpers
    // ─────────────────────────────────────────────────────────────────────────────

    private fun config(seed: Int) = HeddleConfig(
        policy = PolicyConfig(quantum = 10L),
        maxHoldingsPerPeer = 100_000L,
        demandTtl = 30.seconds,
        quilter = QuilterConfig(antiEntropyInterval = 100.milliseconds, fullStateRetryLimit = 0, expectVirtualTime = true),
        heartbeat = HeartbeatConfig(),
        random = Random(seed),
    )

    /** Launch [block] on [scope], pump [sim]'s virtual time until it commits, then return the outcome. */
    private suspend fun awaitOutcome(
        sim: MultiNodeRaftSim,
        scope: CoroutineScope,
        block: suspend () -> ControlOutcome,
    ): ControlOutcome {
        val d = scope.async { block() }
        sim.awaitTrue("control op committed") { d.isCompleted }
        return d.await()
    }

    /** An in-memory [LedgerControl] for control-plane tests — a lock-guarded ledger merged by `piece`. */
    private class InMemoryLedgerControl(initial: EntitlementLedger) : LedgerControl {
        private val lock = reentrantLock()
        private val state = MutableStateFlow(initial)
        override fun mutate(block: (EntitlementLedger) -> Patch<EntitlementLedger>) {
            lock.withLock { state.value = state.value.piece(block(state.value).delta) }
        }
        fun snapshot(): EntitlementLedger = state.value
    }

    /** A [RaftNode] decorator that counts consensus messages (propose/readIndex) on the spend path. */
    private class CountingRaftNode(private val delegate: RaftNode) : RaftNode by delegate {
        var consensusCalls: Int = 0
            private set
        override suspend fun propose(command: ByteArray): LogEntry {
            consensusCalls++
            return delegate.propose(command)
        }
        override suspend fun propose(command: ByteArray, requestId: Long): LogEntry {
            consensusCalls++
            return delegate.propose(command, requestId)
        }
        override suspend fun readIndex(): Long {
            consensusCalls++
            return delegate.readIndex()
        }
    }
}
