package us.tractat.kuilt.heddle

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.raft.LeadershipLostException
import us.tractat.kuilt.raft.LogEntry
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftNode
import us.tractat.kuilt.raft.RaftRole
import us.tractat.kuilt.raft.test.FakeRaftNode
import us.tractat.kuilt.raft.test.MultiNodeRaftSim
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * H5 acceptance + consensus-safety suite (design §15 Phase 5, §9 "the embroidery", §10.13): the
 * Raft-backed control plane — mint and topology reconfiguration serialized on the consensus log,
 * gated against a **log-pure control-state projection** (never the gossip-merged data plane), while
 * the spend path stays coordination-free.
 *
 * **Test discipline (repo CLAUDE.md).** Consensus tests run through the canonical `MultiNodeRaftSim`
 * from `:kuilt-raft-test` — never a hand-rolled cluster network: `StandardTestDispatcher`, tight 5 s
 * timeout, node coroutines on `backgroundScope`, per-node seeded election RNG, bounded `await*`
 * helpers only (never `advanceUntilIdle`). Single-node determinism/idempotence tests use a
 * [FakeRaftNode] double.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HeddleControlPlaneTest {

    private val root = GroupId("root")

    // ═══════════════════════════════════════════════════════════════════════════
    // BLOCKER 1 (consensus safety): the gate reads LOG-ORDER state, not gossip-merged
    // state. A data-plane view that has merged the converged state (eA RETIRED) must
    // NOT let the out-of-log-order Activate(eB) slip past — the projection has eA still
    // ACTIVE at that index, so eB loses. (Fable's executed kill chain; §5.4.3.)
    // ═══════════════════════════════════════════════════════════════════════════
    @Test
    fun gateIgnoresGossipMergedAheadState() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val fake = FakeRaftNode(selfId = NodeId("solo"), initialRole = RaftRole.Leader)
        val sink = RecordingSink()
        val plane = HeddleControlPlane(
            raft = fake, self = ReplicaId("solo"), scope = backgroundScope,
            sink = sink, membership = NO_REMONITOR, initial = EntitlementLedger.ZERO, incarnation = "boot-1",
        )
        val c = GroupId("c")
        val eA = AttachmentId("eA")
        val eB = AttachmentId("eB")

        assertIs<ControlOutcome.Applied>(plane.submit(ControlCommand.Prepare(AttachmentRecord(eA, GroupId("pA"), c, Weight.ONE, 0L))))
        assertIs<ControlOutcome.Applied>(plane.submit(ControlCommand.Prepare(AttachmentRecord(eB, GroupId("pB"), c, Weight.ONE, 0L))))
        assertIs<ControlOutcome.Applied>(plane.submit(ControlCommand.Activate(eA)))

        // Simulate the data plane having gossip-merged the CONVERGED state ahead of the log: eA
        // closed+retired, so in the *merged* view `c` has no live inbound. A gate that read this
        // (the BLOCKER-1 bug) would see no incumbent and wrongly ADMIT eB.
        sink.forceMerge(
            EntitlementLedger.ZERO
                .piece(EntitlementLedger.of(lifecycle = mapOf(eA to Lifecycle.CLOSING)))
                .piece(EntitlementLedger.of(lifecycle = mapOf(eA to Lifecycle.RETIRED))),
        )
        assertEquals(0, sink.snapshot().liveInboundEdges(c).size, "the merged data-plane view shows no live inbound")

        // The projection still has eA ACTIVE at eB's log index, so eB LOSES — deterministically.
        val outcome = plane.submit(ControlCommand.Activate(eB))
        assertIs<ControlOutcome.Conflict>(outcome)
        val conflict = outcome.conflict
        assertIs<ControlConflict.DualInbound>(conflict)
        assertEquals(c, conflict.child)
        assertEquals(eA, conflict.incumbent)
        assertEquals(eB, conflict.rejected)
        assertEquals(listOf(eA), plane.projectionSnapshot().liveInboundEdges(c), "log-order state kept exactly one inbound")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 1. Split-brain mint impossible (§9 #1) — a partitioned minority can never commit.
    // ═══════════════════════════════════════════════════════════════════════════
    @Test
    fun splitBrainMintImpossible() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val ids = (1..5).map { NodeId("v$it") }
        val sim = MultiNodeRaftSim(nodeIds = ids, scope = this, nodeScope = backgroundScope)
        val sinks = ids.associateWith { RecordingSink() }
        val planes = ids.associateWith { plane(sim.nodes.getValue(it), it, sinks.getValue(it), backgroundScope) }

        sim.awaitLeader()
        val majority = setOf(NodeId("v1"), NodeId("v2"), NodeId("v3"))
        val minority = setOf(NodeId("v4"), NodeId("v5"))
        sim.partition(majority, minority)
        sim.awaitLeader(among = majority)

        val holder = ReplicaId("acme")
        val majMint = backgroundScope.async { planes.getValue(NodeId("v1")).submit(ControlCommand.Mint(holder, 100L)) }
        val minMint = backgroundScope.async { planes.getValue(NodeId("v4")).submit(ControlCommand.Mint(holder, 100L)) }

        sim.awaitTrue("majority mint committed and applied across the majority") {
            majMint.isCompleted && majority.all { sinks.getValue(it).snapshot().mintedTotal() == 100L }
        }
        assertIs<ControlOutcome.Applied>(majMint.await())
        assertFalse(minMint.isCompleted, "minority partition committed a mint — split-brain mint")
        minority.forEach { assertEquals(0L, sinks.getValue(it).snapshot().mintedTotal(), "minority $it minted while partitioned") }

        // The minority proposer gives up (its forwarded proposal never committed). Post-heal it would
        // be a *legitimate* second mint, not split-brain — cancel it so convergence-to-one is deterministic.
        minMint.cancel()
        sim.heal()
        sim.awaitLeader()
        sim.awaitTrue("all nodes converge to exactly one mint") {
            ids.all { sinks.getValue(it).snapshot().mintedTotal() == 100L }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 2. Overlapping reshapes serialize; the loser surfaces as a structured conflict.
    // ═══════════════════════════════════════════════════════════════════════════
    @Test
    fun overlappingReshapesSerializeLoserSurfacesConflict() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val ids = (1..3).map { NodeId("v$it") }
        val sim = MultiNodeRaftSim(nodeIds = ids, scope = this, nodeScope = backgroundScope)
        val sinks = ids.associateWith { RecordingSink() }
        val planes = ids.associateWith { plane(sim.nodes.getValue(it), it, sinks.getValue(it), backgroundScope) }
        sim.awaitLeader()

        val c = GroupId("c")
        val eA = AttachmentId("eA")
        val eB = AttachmentId("eB")
        val v1 = planes.getValue(NodeId("v1"))
        val v2 = planes.getValue(NodeId("v2"))
        awaitOutcome(sim, backgroundScope) { v1.submit(ControlCommand.Prepare(AttachmentRecord(eA, GroupId("pA"), c, Weight.ONE, 0L))) }
        awaitOutcome(sim, backgroundScope) { v1.submit(ControlCommand.Prepare(AttachmentRecord(eB, GroupId("pB"), c, Weight.ONE, 0L))) }

        val actA = async { v1.submit(ControlCommand.Activate(eA)) }
        val actB = async { v2.submit(ControlCommand.Activate(eB)) }
        sim.awaitTrue("both activates committed") { actA.isCompleted && actB.isCompleted }

        val outcomes = listOf(actA.await(), actB.await())
        assertEquals(1, outcomes.filterIsInstance<ControlOutcome.Applied>().size, "exactly one winner, got $outcomes")
        val conflicts = outcomes.filterIsInstance<ControlOutcome.Conflict>()
        assertEquals(1, conflicts.size, "the loser must surface as a conflict, got $outcomes")
        val dual = conflicts.single().conflict
        assertIs<ControlConflict.DualInbound>(dual)
        assertEquals(c, dual.child)
        assertTrue(dual.incumbent != dual.rejected && dual.incumbent in setOf(eA, eB) && dual.rejected in setOf(eA, eB))

        // Every node's LOG-ORDER projection AND its published data-plane view keep exactly one inbound.
        sim.awaitTrue("child has exactly one live inbound on every node (projection + data plane)") {
            ids.all {
                planes.getValue(it).projectionSnapshot().liveInboundEdges(c).size == 1 &&
                    sinks.getValue(it).snapshot().liveInboundEdges(c).size == 1
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 3. Non-overlapping reshapes commit independently — no contention.
    // ═══════════════════════════════════════════════════════════════════════════
    @Test
    fun nonOverlappingReshapesCommitIndependently() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val ids = (1..3).map { NodeId("v$it") }
        val sim = MultiNodeRaftSim(nodeIds = ids, scope = this, nodeScope = backgroundScope)
        val sinks = ids.associateWith { RecordingSink() }
        val planes = ids.associateWith { plane(sim.nodes.getValue(it), it, sinks.getValue(it), backgroundScope) }
        sim.awaitLeader()

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
                val p = planes.getValue(it).projectionSnapshot()
                p.liveInboundEdges(c1).size == 1 && p.liveInboundEdges(c2).size == 1
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BLOCKER 2a (conservation): mint identity is unique per act AND restart-safe —
    // a re-created node's regenerated ids never collide, so no committed mint evaporates.
    // ═══════════════════════════════════════════════════════════════════════════
    @Test
    fun mintIdentitySurvivesRestartWithoutCollision() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val fake = FakeRaftNode(selfId = NodeId("solo"), initialRole = RaftRole.Leader)
        val durable = RecordingSink() // the durable replicated ledger, shared across the "restart"
        val holder = ReplicaId("acme")

        // Incarnation A mints 100.
        val a = HeddleControlPlane(fake, ReplicaId("solo"), backgroundScope, durable, NO_REMONITOR, EntitlementLedger.ZERO, "boot-A")
        assertIs<ControlOutcome.Applied>(a.submit(ControlCommand.Mint(holder, 100L)))
        assertEquals(100L, durable.snapshot().mintedTotal())

        // "Restart": a fresh control plane with a FRESH injected incarnation over the same log/ledger,
        // replaying the committed log, then minting 40. A reused incarnation would regenerate `#0` and
        // max-collide the 40 into the 100 (a lost mint); a fresh incarnation keeps them distinct.
        val b = HeddleControlPlane(fake, ReplicaId("solo"), backgroundScope, durable, NO_REMONITOR, EntitlementLedger.ZERO, "boot-B")
        runCurrent() // let B replay the committed mint
        assertIs<ControlOutcome.Applied>(b.submit(ControlCommand.Mint(holder, 40L)))
        assertEquals(140L, durable.snapshot().mintedTotal(), "the second mint must not collide with the first")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BLOCKER 2b (conservation): a retry after LeadershipLost commits exactly one mint —
    // the apply loop dedups the re-committed entry on its stable requestKey.
    // ═══════════════════════════════════════════════════════════════════════════
    @Test
    fun retryAfterLeadershipLossMintsExactlyOnce() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val fake = FakeRaftNode(selfId = NodeId("solo"), initialRole = RaftRole.Leader)
        // The leader commits the entry BUT the caller's forwarded proposal is rejected (LeadershipLost)
        // on the first attempt — the "outcome unknown" case — so submit retries, re-committing the SAME
        // requestKey. Two committed entries, one logical mint.
        var firstAttempt = true
        fake.proposeBehavior = { command ->
            val entry = fake.pushCommitted(command)
            if (firstAttempt) { firstAttempt = false; throw LeadershipLostException() }
            entry
        }
        val sink = RecordingSink()
        val plane = HeddleControlPlane(fake, ReplicaId("solo"), backgroundScope, sink, NO_REMONITOR, EntitlementLedger.ZERO, "boot-3")

        val outcome = plane.submit(ControlCommand.Mint(ReplicaId("acme"), 100L))
        assertIs<ControlOutcome.Applied>(outcome)
        assertEquals(100L, sink.snapshot().mintedTotal(), "the retry must not double-mint")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BLOCKER 2 (ergonomics): a bounded submit surfaces a leader crash as a timeout
    // instead of hanging forever.
    // ═══════════════════════════════════════════════════════════════════════════
    @Test
    fun submitTimeoutSurfacesLeaderCrash() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val fake = FakeRaftNode(selfId = NodeId("solo"), initialRole = RaftRole.Leader)
        fake.proposeBehavior = { awaitCancellation() } // a forwarded proposal that never commits (leader crash)
        val plane = HeddleControlPlane(fake, ReplicaId("solo"), backgroundScope, RecordingSink(), NO_REMONITOR, EntitlementLedger.ZERO, "boot-4")
        assertFailsWith<TimeoutCancellationException> {
            plane.submit(ControlCommand.Mint(ReplicaId("acme"), 100L), timeout = 1.seconds)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 4. Zero consensus messages on the spend path (§10.13 message accounting).
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
            incarnation = "boot-msg", epoch = 0L,
        )

        // Control-plane setup (these DO consense — mint + reshape ride the log): seed a leaf holding.
        val leaf = GroupId("leaf")
        val eLeaf = AttachmentId("eLeaf")
        assertIs<ControlOutcome.Applied>(governed.mint(self, 1_000L))
        assertIs<ControlOutcome.Applied>(governed.prepare(AttachmentRecord(eLeaf, root, leaf, Weight.ONE, 0L)))
        assertIs<ControlOutcome.Applied>(governed.activate(eLeaf))
        assertTrue(counting.consensusCalls > 0, "control-plane setup must issue consensus messages")
        governed.advertise(eLeaf, Demand(targetOutstanding = 1_000L, maximumUsefulGrant = 1_000L))
        governed.schedule(root)
        assertTrue(governed.ledger.value.holdings(leaf, self) > 0L, "schedule should have seeded the leaf")

        // Put a control-plane proposal genuinely in flight (it never commits) and hold the baseline.
        fake.proposeBehavior = { awaitCancellation() }
        val inFlight = backgroundScope.async { governed.mint(self, 1L) }
        testScheduler.runCurrent()
        assertFalse(inFlight.isCompleted, "the in-flight proposal must still be pending")

        val baseline = counting.consensusCalls
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
            incarnation = "boot-revoke", epoch = 0L,
        )
        assertEquals(RevocationOutcome.NotShipped, governed.revocation.revoke(self, root))
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FIX 3b: a second Prepare under an existing id with a DIFFERENT record is a
    // structured Refused, not a lying Applied. Same record stays idempotent-Applied.
    // ═══════════════════════════════════════════════════════════════════════════
    @Test
    fun prepareConflictingRecordIsRefusedNotSilentlyApplied() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val fake = FakeRaftNode(selfId = NodeId("solo"), initialRole = RaftRole.Leader)
        val plane = HeddleControlPlane(fake, ReplicaId("solo"), backgroundScope, RecordingSink(), NO_REMONITOR, EntitlementLedger.ZERO, "boot-prep")
        val id = AttachmentId("e")
        val rec = AttachmentRecord(id, root, GroupId("c"), Weight.ONE, 0L)
        val differentRec = AttachmentRecord(id, GroupId("otherParent"), GroupId("c"), Weight.ONE, 0L)

        assertIs<ControlOutcome.Applied>(plane.submit(ControlCommand.Prepare(rec)))
        assertIs<ControlOutcome.Applied>(plane.submit(ControlCommand.Prepare(rec))) // idempotent: same record
        val conflict = plane.submit(ControlCommand.Prepare(differentRec))
        assertIs<ControlOutcome.Conflict>(conflict)
        assertIs<ControlConflict.Refused>(conflict.conflict)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // H7 coverage: Activate/Close of an unknown edge — and Activate of a RETIRED edge —
    // surface as a structured `Refused`, never a lying `Applied`. Exercises the three
    // local-refusal branches in decideAndApply (activate-unknown, activate-retired,
    // close-refused) that the DualInbound/Prepare tests never reach.
    // ═══════════════════════════════════════════════════════════════════════════
    @Test
    fun activateAndCloseOfMissingOrRetiredEdgeAreRefused() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val fake = FakeRaftNode(selfId = NodeId("solo"), initialRole = RaftRole.Leader)
        val plane = HeddleControlPlane(fake, ReplicaId("solo"), backgroundScope, RecordingSink(), NO_REMONITOR, EntitlementLedger.ZERO, "boot-refuse")
        val unknown = AttachmentId("never-prepared")

        // Activate/Close of an edge the projection has never seen: refused, not applied.
        val activateUnknown = plane.submit(ControlCommand.Activate(unknown))
        assertIs<ControlOutcome.Conflict>(activateUnknown)
        assertIs<ControlConflict.Refused>(activateUnknown.conflict)

        val closeUnknown = plane.submit(ControlCommand.Close(unknown))
        assertIs<ControlOutcome.Conflict>(closeUnknown)
        assertIs<ControlConflict.Refused>(closeUnknown.conflict)

        // Drive an edge all the way to RETIRED, then re-activating it is refused (retired/divergent) —
        // a closed generation can never be resurrected (design §5.4.3 / module.md closure-wins rule).
        val e = AttachmentId("e")
        assertIs<ControlOutcome.Applied>(plane.submit(ControlCommand.Prepare(AttachmentRecord(e, root, GroupId("c"), Weight.ONE, 0L))))
        assertIs<ControlOutcome.Applied>(plane.submit(ControlCommand.Activate(e)))
        assertIs<ControlOutcome.Applied>(plane.submit(ControlCommand.Close(e)))
        assertIs<ControlOutcome.Applied>(plane.submit(ControlCommand.Retire(e))) // projection has no outstanding
        assertEquals(Lifecycle.RETIRED, plane.projectionSnapshot().lifecycle(e))

        val reactivateRetired = plane.submit(ControlCommand.Activate(e))
        assertIs<ControlOutcome.Conflict>(reactivateRetired)
        assertIs<ControlConflict.Refused>(reactivateRetired.conflict)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FIX 1: governed retire's advisory drain gate — a drained CLOSING edge retires;
    // a non-drained edge is refused LOCALLY (retiring it would strand entitlement).
    // ═══════════════════════════════════════════════════════════════════════════
    @Test
    fun governedRetireDrainGate() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val fake = FakeRaftNode(selfId = NodeId("solo"), initialRole = RaftRole.Leader)
        val loom = InMemoryLoom()
        val seam: Seam = loom.host(Pattern("heddle-h5-retire"))
        val self = ReplicaId(seam.selfId.value)
        val governed = backgroundScope.heddleGoverned(
            seam = seam, self = self, raft = fake, root = root,
            clock = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }, config = config(seed = 11),
            incarnation = "boot-retire", epoch = 0L,
        )
        assertIs<ControlOutcome.Applied>(governed.mint(self, 1_000L))

        // A drained edge (activated, never delegated → outstanding 0) retires once CLOSING.
        val drained = AttachmentId("drained")
        assertIs<ControlOutcome.Applied>(governed.prepare(AttachmentRecord(drained, root, GroupId("d"), Weight.ONE, 0L)))
        assertIs<ControlOutcome.Applied>(governed.activate(drained))
        assertIs<ControlOutcome.Applied>(governed.close(drained))
        assertIs<ControlOutcome.Applied>(governed.retire(drained))
        assertEquals(Lifecycle.RETIRED, governed.ledger.value.lifecycle(drained))

        // A non-drained edge (delegated down via schedule → outstanding > 0) is refused locally.
        val live = AttachmentId("live")
        val leaf = GroupId("leaf")
        assertIs<ControlOutcome.Applied>(governed.prepare(AttachmentRecord(live, root, leaf, Weight.ONE, 0L)))
        assertIs<ControlOutcome.Applied>(governed.activate(live))
        governed.advertise(live, Demand(targetOutstanding = 500L, maximumUsefulGrant = 500L))
        governed.schedule(root)
        val liveEdge = governed.ledger.value.edge(live)
        assertNotNull(liveEdge)
        assertTrue(liveEdge.outstanding > 0L, "live edge should carry outstanding entitlement")
        assertIs<ControlOutcome.Applied>(governed.close(live))
        val refused = governed.retire(live)
        assertIs<ControlOutcome.Conflict>(refused)
        assertEquals(ControlOutcome.NOT_COMMITTED, refused.index)
        assertIs<ControlConflict.Refused>(refused.conflict)
        assertEquals(Lifecycle.CLOSING, governed.ledger.value.lifecycle(live), "the refused retire left the edge CLOSING, not RETIRED")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // #1665 (D1): a raced advisory-retire + legal reparent leaves permanent conflicts;
    // a governed reconcile re-homes the stranded budget through the log, clearing them
    // deterministically on EVERY peer while conserving supply. MultiNodeRaftSim for the
    // consensus apply; RecordingSink+forceMerge models the gossip-merged data plane.
    // ═══════════════════════════════════════════════════════════════════════════
    @Test
    fun reconcileClearsRacedRetireStrandAcrossAllPeers() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val ids = (1..3).map { NodeId("v$it") }
        val sim = MultiNodeRaftSim(nodeIds = ids, scope = this, nodeScope = backgroundScope)
        val sinks = ids.associateWith { RecordingSink() }
        val planes = ids.associateWith { plane(sim.nodes.getValue(it), it, sinks.getValue(it), backgroundScope) }
        sim.awaitLeader()

        val g = GroupId("g")
        val h = GroupId("h")
        val p3 = ReplicaId("p3")
        val e1 = AttachmentId("e1") // root → g (stranded by the raced retire)
        val e2 = AttachmentId("e2") // g    → h
        val e3 = AttachmentId("e3") // root → g (the legal reparent generation)
        val v1 = planes.getValue(NodeId("v1"))
        fun rec(id: AttachmentId, parent: GroupId, child: GroupId) = AttachmentRecord(id, parent, child, Weight.ONE, 0L)

        // Control-plane topology: e1,e2 active; then close+retire e1 with a LAGGED (empty) drain witness —
        // the projection has no data-plane counters, so its outstanding reads 0 and the retire is admitted.
        awaitOutcome(sim, backgroundScope) { v1.submit(ControlCommand.Mint(p3, 10L)) }
        awaitOutcome(sim, backgroundScope) { v1.submit(ControlCommand.Prepare(rec(e1, root, g))) }
        awaitOutcome(sim, backgroundScope) { v1.submit(ControlCommand.Activate(e1)) }
        awaitOutcome(sim, backgroundScope) { v1.submit(ControlCommand.Prepare(rec(e2, g, h))) }
        awaitOutcome(sim, backgroundScope) { v1.submit(ControlCommand.Activate(e2)) }
        awaitOutcome(sim, backgroundScope) { v1.submit(ControlCommand.Close(e1)) }
        assertIs<ControlOutcome.Applied>(awaitOutcome(sim, backgroundScope) { v1.submit(ControlCommand.Retire(e1, witness = null)) })
        // Legal reparent: e1 is RETIRED (not live), so activating e3 passes the dual-inbound gate.
        awaitOutcome(sim, backgroundScope) { v1.submit(ControlCommand.Prepare(rec(e3, root, g))) }
        assertIs<ControlOutcome.Applied>(awaitOutcome(sim, backgroundScope) { v1.submit(ControlCommand.Activate(e3)) })

        // The real data plane gossips in AFTER the retire: p3 delegated 10↓e1 and 6↓e2, then the +6 at h
        // was spent through [e3,e2]. Model the converged data-plane view by merging those counters into
        // every peer's sink.
        val dataCounters = EntitlementLedger.of(
            issued = mapOf(e1 to GCounter.of(p3 to 10L), e2 to GCounter.of(p3 to 6L)),
            leafSpent = mapOf(e2 to GCounter.of(p3 to 6L)),
            rollupSpent = mapOf(e3 to GCounter.of(p3 to 6L)),
        )
        sinks.values.forEach { it.forceMerge(dataCounters) }

        // Every peer converges (each applies the committed log at its own virtual-time cadence) on the
        // three permanent conflicts, identically.
        val d1Conflicts = listOf(
            LedgerConflict.PerEdgeSafety(e3),
            LedgerConflict.PersistentNegativeHoldings(g, p3),
            LedgerConflict.ClosureViolation(e1),
        )
        sim.awaitTrue("every peer converges on the D1 conflicts pre-reconcile") {
            ids.all { sinks.getValue(it).snapshot().validate() == d1Conflicts }
        }
        ids.forEach { assertEquals(-6L, sinks.getValue(it).snapshot().holdings(g, p3), "peer $it holdings(g,p3) permanently negative") }

        // Governed reconcile: compute the conserving re-home witness on the leader's data-plane view (exactly
        // what GovernedHeddleNode.reconcile does) and submit it through the log.
        val dataView = sinks.getValue(NodeId("v1")).snapshot()
        val rehome = dataView.reconcileStranded(g)
        assertNotNull(rehome, "the leader's data view has a strand to reconcile")
        val liveEdge = dataView.liveInboundEdges(g).single()
        val outcome = awaitOutcome(sim, backgroundScope) {
            v1.submit(ControlCommand.Reconcile(g, liveEdge, rehome.delta))
        }
        assertIs<ControlOutcome.Applied>(outcome)

        // Every peer converges to the same cleared, conserved state.
        sim.awaitTrue("every peer cleared its conflicts after the governed reconcile") {
            ids.all { sinks.getValue(it).snapshot().validate().isEmpty() }
        }
        val converged = sinks.getValue(NodeId("v1")).snapshot()
        ids.forEach {
            val v = sinks.getValue(it).snapshot()
            assertEquals(converged, v, "peer $it must converge to the identical reconciled ledger")
            assertTrue(v.holdings(g, p3) >= 0L, "peer $it holdings(g,p3) non-negative")
        }
        assertEquals(4L, converged.holdings(g, p3), "g re-homes to the un-spent remainder (10 − 6)")
        // Conservation: supply unchanged, and Σ holdings + leafSpent == minted restored.
        var sumHoldings = 0L
        for (grp in listOf(root, g, h)) sumHoldings += converged.holdings(grp, p3)
        assertEquals(10L, converged.mintedTotal(), "reconciliation minted nothing")
        assertEquals(converged.mintedTotal(), sumHoldings + converged.leafSpentTotal(), "conservation restored across the cluster")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // #1691 (slice 1 of #1665, relocation design §6.3): the apply gate refuses ANY
    // reconciliation witness that writes a slot the control plane does not own — a base
    // `issued` slot on the LIVE edge (finding 1's contended slot), or a spend-relocation
    // counter (through-service relocation, which stays gated until the §6 fence ships).
    // Structural, not advisory: a buggy or hostile proposer cannot smuggle either past it.
    // ═══════════════════════════════════════════════════════════════════════════
    @Test
    fun reconcileWitnessTouchingAContendedOrUnfencedSlotIsRefusedAtApply() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val fake = FakeRaftNode(selfId = NodeId("solo"), initialRole = RaftRole.Leader)
        val sink = RecordingSink()
        val plane = HeddleControlPlane(
            raft = fake, self = ReplicaId("solo"), scope = backgroundScope,
            sink = sink, membership = NO_REMONITOR, initial = EntitlementLedger.ZERO, incarnation = "boot-reloc-gate",
        )
        val child = GroupId("g")
        val p3 = ReplicaId("p3")
        val old = AttachmentId("eOld") // root → g, retired
        val live = AttachmentId("eNew") // root → g, the reparent generation

        assertIs<ControlOutcome.Applied>(plane.submit(ControlCommand.Prepare(AttachmentRecord(old, root, child, Weight.ONE, 0L))))
        assertIs<ControlOutcome.Applied>(plane.submit(ControlCommand.Activate(old)))
        assertIs<ControlOutcome.Applied>(plane.submit(ControlCommand.Close(old)))
        assertIs<ControlOutcome.Applied>(plane.submit(ControlCommand.Retire(old, witness = null)))
        assertIs<ControlOutcome.Applied>(plane.submit(ControlCommand.Prepare(AttachmentRecord(live, root, child, Weight.ONE, 0L))))
        assertIs<ControlOutcome.Applied>(plane.submit(ControlCommand.Activate(live)))

        // Finding 1's shape: a base `issued(live)[p3]` absolute, on a slot p3's own `delegate`
        // writes concurrently. Under per-slot max-join one of the two writers is silently erased.
        val contendedWitness = EntitlementLedger.of(
            returned = mapOf(old to GCounter.of(p3 to 10L)),
            issued = mapOf(live to GCounter.of(p3 to 10L)),
        )
        val contended = plane.submit(ControlCommand.Reconcile(child, live, contendedWitness))
        assertIs<ControlOutcome.Conflict>(contended)
        assertIs<ControlConflict.Refused>(contended.conflict)
        assertTrue(sink.snapshot().issuedEdges().isEmpty(), "a refused witness must publish nothing")

        // Through-service relocation is NOT un-gated in slice 1: a witness carrying spend
        // relocation is refused even though the representation can express it, because moving
        // an already-charged spend safely needs the per-peer quiesce fence that is not built.
        val spendRelocationWitness = EntitlementLedger.of(
            returned = mapOf(old to GCounter.of(p3 to 10L)),
            issuedRelocIn = mapOf(live to GCounter.of(p3 to 10L)),
            rollupRelocOut = mapOf(old to GCounter.of(p3 to 3L)),
            rollupRelocIn = mapOf(live to GCounter.of(p3 to 3L)),
        )
        val relocating = plane.submit(ControlCommand.Reconcile(child, live, spendRelocationWitness))
        assertIs<ControlOutcome.Conflict>(relocating)
        assertIs<ControlConflict.Refused>(relocating.conflict)

        // The well-formed shape — a base `returned` drain on the retired edge plus an
        // `issuedRelocIn` credit on the live one — is the ONLY thing that gets through.
        val wellFormed = EntitlementLedger.of(
            returned = mapOf(old to GCounter.of(p3 to 10L)),
            issuedRelocIn = mapOf(live to GCounter.of(p3 to 10L)),
        )
        assertIs<ControlOutcome.Applied>(plane.submit(ControlCommand.Reconcile(child, live, wellFormed)))
        assertEquals(10L, sink.snapshot().effectiveIssued(live, p3), "the accepted re-home credits the live edge")
        assertTrue(sink.snapshot().issuedEdges().isEmpty(), "…and still never writes a base issued slot")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // #1665 (§9 #3): reconcile is fenced by readIndex() — a deposed/non-leader proposer
    // is refused BEFORE it computes a witness from its (unfenced) data-plane view, so a
    // partitioned ex-leader can never drive recovery from stale authority.
    // ═══════════════════════════════════════════════════════════════════════════
    @Test
    fun reconcileReadIndexFenceRefusesANonLeaderProposer() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val fake = FakeRaftNode(selfId = NodeId("solo"), initialRole = RaftRole.Leader)
        fake.readIndexBehavior = { throw us.tractat.kuilt.raft.NotLeaderException("deposed") }
        val loom = InMemoryLoom()
        val seam: Seam = loom.host(Pattern("heddle-h5-reconcile-fence"))
        val self = ReplicaId(seam.selfId.value)
        val governed = backgroundScope.heddleGoverned(
            seam = seam, self = self, raft = fake, root = root,
            clock = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }, config = config(seed = 13),
            incarnation = "boot-reconcile-fence", epoch = 0L,
        )
        val outcome = governed.reconcile(GroupId("anything"))
        assertIs<ControlOutcome.Conflict>(outcome)
        assertEquals(ControlOutcome.NOT_COMMITTED, outcome.index)
        val conflict = outcome.conflict
        assertIs<ControlConflict.Refused>(conflict)
        assertTrue(conflict.reason.contains("readIndex"), "the refusal must name the §9 #3 fence, was: ${conflict.reason}")
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

    private fun plane(raft: RaftNode, id: NodeId, sink: ControlLedgerSink, scope: CoroutineScope) =
        HeddleControlPlane(raft, ReplicaId(id.value), scope, sink, NO_REMONITOR, EntitlementLedger.ZERO, "inc-${id.value}")

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

    /** These suites do not exercise the node-side enrollment effect (#1652) — see `HeddleRosterTest`. */
    private val NO_REMONITOR = ControlMembershipSink { }

    /** A [ControlLedgerSink] that accumulates published patches into a ledger — the data-plane view. */
    private class RecordingSink : ControlLedgerSink {
        private val lock = reentrantLock()
        private val state = MutableStateFlow(EntitlementLedger.ZERO)
        override fun publish(patch: Patch<EntitlementLedger>) {
            lock.withLock { state.value = state.value.piece(patch.delta) }
        }
        /** Simulate gossip merging state ahead of the log order (independent transport). */
        fun forceMerge(other: EntitlementLedger) {
            lock.withLock { state.value = state.value.piece(other) }
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
