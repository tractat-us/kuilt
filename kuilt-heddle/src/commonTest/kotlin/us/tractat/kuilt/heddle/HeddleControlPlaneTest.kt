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
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.runCatchingCancellable
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
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
 * from `:kuilt-raft-test` — never a hand-rolled cluster network: `StandardTestDispatcher`, a generous
 * `TEST_WEDGE_BACKSTOP` wedge ceiling (never a tight real-time cap, #1739), node coroutines on
 * `backgroundScope`, per-node seeded election RNG, bounded `await*`
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
    fun gateIgnoresGossipMergedAheadState() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val fake = FakeRaftNode(selfId = NodeId("solo"), initialRole = RaftRole.Leader)
        val sink = RecordingSink()
        val plane = HeddleControlPlane(
            raft = fake, self = ReplicaId("solo"), scope = backgroundScope,
            sink = sink, membership = NO_REMONITOR, barrier = NO_BARRIER, initial = EntitlementLedger.ZERO, incarnation = "boot-1",
        )
        val c = GroupId("c")
        val eA = AttachmentId("eA")
        val eB = AttachmentId("eB")

        assertIs<ControlOutcome.Applied>(plane.submit(ControlCommand.Prepare(AttachmentRecord(eA, GroupId("pA"), c, Weight.ONE))))
        assertIs<ControlOutcome.Applied>(plane.submit(ControlCommand.Prepare(AttachmentRecord(eB, GroupId("pB"), c, Weight.ONE))))
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
    fun splitBrainMintImpossible() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
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
    fun overlappingReshapesSerializeLoserSurfacesConflict() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
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
        awaitOutcome(sim, backgroundScope) { v1.submit(ControlCommand.Prepare(AttachmentRecord(eA, GroupId("pA"), c, Weight.ONE))) }
        awaitOutcome(sim, backgroundScope) { v1.submit(ControlCommand.Prepare(AttachmentRecord(eB, GroupId("pB"), c, Weight.ONE))) }

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
    fun nonOverlappingReshapesCommitIndependently() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
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
        awaitOutcome(sim, backgroundScope) { v1.submit(ControlCommand.Prepare(AttachmentRecord(e1, root, c1, Weight.ONE))) }
        awaitOutcome(sim, backgroundScope) { v2.submit(ControlCommand.Prepare(AttachmentRecord(e2, root, c2, Weight.ONE))) }

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
    fun mintIdentitySurvivesRestartWithoutCollision() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val fake = FakeRaftNode(selfId = NodeId("solo"), initialRole = RaftRole.Leader)
        val durable = RecordingSink() // the durable replicated ledger, shared across the "restart"
        val holder = ReplicaId("acme")

        // Incarnation A mints 100.
        val a = HeddleControlPlane(fake, ReplicaId("solo"), backgroundScope, durable, NO_REMONITOR, NO_BARRIER, EntitlementLedger.ZERO, "boot-A")
        assertIs<ControlOutcome.Applied>(a.submit(ControlCommand.Mint(holder, 100L)))
        assertEquals(100L, durable.snapshot().mintedTotal())

        // "Restart": a fresh control plane with a FRESH injected incarnation over the same log/ledger,
        // replaying the committed log, then minting 40. A reused incarnation would regenerate `#0` and
        // max-collide the 40 into the 100 (a lost mint); a fresh incarnation keeps them distinct.
        val b = HeddleControlPlane(fake, ReplicaId("solo"), backgroundScope, durable, NO_REMONITOR, NO_BARRIER, EntitlementLedger.ZERO, "boot-B")
        runCurrent() // let B replay the committed mint
        assertIs<ControlOutcome.Applied>(b.submit(ControlCommand.Mint(holder, 40L)))
        assertEquals(140L, durable.snapshot().mintedTotal(), "the second mint must not collide with the first")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BLOCKER 2b (conservation): a retry after LeadershipLost commits exactly one mint —
    // the apply loop dedups the re-committed entry on its stable requestKey.
    // ═══════════════════════════════════════════════════════════════════════════
    @Test
    fun retryAfterLeadershipLossMintsExactlyOnce() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
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
        val plane = HeddleControlPlane(fake, ReplicaId("solo"), backgroundScope, sink, NO_REMONITOR, NO_BARRIER, EntitlementLedger.ZERO, "boot-3")

        val outcome = plane.submit(ControlCommand.Mint(ReplicaId("acme"), 100L))
        assertIs<ControlOutcome.Applied>(outcome)
        assertEquals(100L, sink.snapshot().mintedTotal(), "the retry must not double-mint")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BLOCKER 2 (ergonomics): a bounded submit surfaces a leader crash as a timeout
    // instead of hanging forever.
    // ═══════════════════════════════════════════════════════════════════════════
    @Test
    fun submitTimeoutSurfacesLeaderCrash() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val fake = FakeRaftNode(selfId = NodeId("solo"), initialRole = RaftRole.Leader)
        fake.proposeBehavior = { awaitCancellation() } // a forwarded proposal that never commits (leader crash)
        val plane = HeddleControlPlane(fake, ReplicaId("solo"), backgroundScope, RecordingSink(), NO_REMONITOR, NO_BARRIER, EntitlementLedger.ZERO, "boot-4")
        assertFailsWith<TimeoutCancellationException> {
            plane.submit(ControlCommand.Mint(ReplicaId("acme"), 100L), timeout = 1.seconds)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 4. Zero consensus messages on the spend path (§10.13 message accounting).
    // ═══════════════════════════════════════════════════════════════════════════
    @Test
    fun spendPathIssuesZeroConsensusMessages() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
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
        // Enrolling self is what opens the §6.5.3 boot gate; until then reserve/schedule refuse.
        val leaf = GroupId("leaf")
        val eLeaf = AttachmentId("eLeaf")
        assertFalse(governed.isWritable, "a governed node boots closed to writes")
        assertIs<ControlOutcome.Applied>(governed.enroll(self))
        assertTrue(governed.isWritable, "the applied self-enroll opens the boot gate")
        assertIs<ControlOutcome.Applied>(governed.mint(self, 1_000L))
        assertIs<ControlOutcome.Applied>(governed.prepare(AttachmentRecord(eLeaf, root, leaf, Weight.ONE)))
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
    fun revocationSeamSpecifiedNotShipped() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
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
    fun prepareConflictingRecordIsRefusedNotSilentlyApplied() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val fake = FakeRaftNode(selfId = NodeId("solo"), initialRole = RaftRole.Leader)
        val plane = HeddleControlPlane(fake, ReplicaId("solo"), backgroundScope, RecordingSink(), NO_REMONITOR, NO_BARRIER, EntitlementLedger.ZERO, "boot-prep")
        val id = AttachmentId("e")
        val rec = AttachmentRecord(id, root, GroupId("c"), Weight.ONE)
        val differentRec = AttachmentRecord(id, GroupId("otherParent"), GroupId("c"), Weight.ONE)

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
    fun activateAndCloseOfMissingOrRetiredEdgeAreRefused() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val fake = FakeRaftNode(selfId = NodeId("solo"), initialRole = RaftRole.Leader)
        val plane = HeddleControlPlane(fake, ReplicaId("solo"), backgroundScope, RecordingSink(), NO_REMONITOR, NO_BARRIER, EntitlementLedger.ZERO, "boot-refuse")
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
        assertIs<ControlOutcome.Applied>(plane.submit(ControlCommand.Prepare(AttachmentRecord(e, root, GroupId("c"), Weight.ONE))))
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
    fun governedRetireDrainGate() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val fake = FakeRaftNode(selfId = NodeId("solo"), initialRole = RaftRole.Leader)
        val loom = InMemoryLoom()
        val seam: Seam = loom.host(Pattern("heddle-h5-retire"))
        val self = ReplicaId(seam.selfId.value)
        val governed = backgroundScope.heddleGoverned(
            seam = seam, self = self, raft = fake, root = root,
            clock = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }, config = config(seed = 11),
            incarnation = "boot-retire", epoch = 0L,
        )
        assertIs<ControlOutcome.Applied>(governed.enroll(self))
        assertIs<ControlOutcome.Applied>(governed.mint(self, 1_000L))

        // A drained edge (activated, never delegated → outstanding 0) retires once CLOSING.
        val drained = AttachmentId("drained")
        assertIs<ControlOutcome.Applied>(governed.prepare(AttachmentRecord(drained, root, GroupId("d"), Weight.ONE)))
        assertIs<ControlOutcome.Applied>(governed.activate(drained))
        assertIs<ControlOutcome.Applied>(governed.close(drained))
        assertIs<ControlOutcome.Applied>(governed.retire(drained))
        assertEquals(Lifecycle.RETIRED, governed.ledger.value.lifecycle(drained))

        // A non-drained edge (delegated down via schedule → outstanding > 0) is refused locally.
        val live = AttachmentId("live")
        val leaf = GroupId("leaf")
        assertIs<ControlOutcome.Applied>(governed.prepare(AttachmentRecord(live, root, leaf, Weight.ONE)))
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
    fun reconcileClearsRacedRetireStrandAcrossAllPeers() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        // The three raft nodes ARE the three data-plane replicas here, so each plane's barrier reads
        // and acks its own slots — which is what the fence quantifies over.
        val ids = listOf(NodeId("p1"), NodeId("p2"), NodeId("p3"))
        val sim = MultiNodeRaftSim(nodeIds = ids, scope = this, nodeScope = backgroundScope)
        val sinks = ids.associateWith { RecordingSink() }
        val planes = ids.associateWith { id ->
            fencedPlane(sim.nodes.getValue(id), id, sinks.getValue(id), backgroundScope)
        }
        sim.awaitLeader()

        val g = GroupId("g")
        val h = GroupId("h")
        val p3 = ReplicaId("p3")
        val e1 = AttachmentId("e1") // root → g (stranded by the raced retire)
        val e2 = AttachmentId("e2") // g    → h
        val e3 = AttachmentId("e3") // root → g (the legal reparent generation)
        val v1 = planes.getValue(NodeId("p1"))
        fun rec(id: AttachmentId, parent: GroupId, child: GroupId) = AttachmentRecord(id, parent, child, Weight.ONE)

        // Every peer enrolls: the fence's ack set is exactly the enrolled set at the barrier's index.
        for (id in ids) {
            awaitOutcome(sim, backgroundScope) {
                planes.getValue(id).submit(ControlCommand.Enroll(ReplicaId(id.value)))
            }
        }
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

        // A Reconcile BEFORE the barrier is refused — the strand stays standing rather than being
        // drained to zero headroom on a magnitude nobody has promised (§6.2 step 4).
        val premature = awaitOutcome(sim, backgroundScope) { v1.submit(ControlCommand.Reconcile(g)) }
        assertIs<ControlOutcome.Conflict>(premature)
        assertIs<ControlConflict.Refused>(premature.conflict)

        // Open the barrier. Each peer applies it, marks e1 unwritable, and answers with its own finals.
        assertIs<ControlOutcome.Applied>(awaitOutcome(sim, backgroundScope) { v1.submit(ControlCommand.Quiesce(e1)) })
        sim.awaitTrue("every enrolled peer acked the barrier over e1") {
            ids.all { planes.getValue(it).pendingAcks(e1)?.isEmpty() == true }
        }

        // The Reconcile carries only the child — the patch is DERIVED from the acked finals at apply.
        val outcome = awaitOutcome(sim, backgroundScope) { v1.submit(ControlCommand.Reconcile(g)) }
        assertIs<ControlOutcome.Applied>(outcome)

        // Every peer converges to the same cleared, conserved state.
        sim.awaitTrue("every peer cleared its conflicts after the governed reconcile") {
            ids.all { sinks.getValue(it).snapshot().validate().isEmpty() }
        }
        val converged = sinks.getValue(NodeId("p1")).snapshot()
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
        // Idempotence at the apply gate (§5.4 iii): the fenced edge now reads drained on log-pure
        // state, so a second Reconcile is refused — no re-inflation, deterministically.
        val second = awaitOutcome(sim, backgroundScope) { v1.submit(ControlCommand.Reconcile(g)) }
        assertIs<ControlOutcome.Conflict>(second)
        assertEquals(converged, sinks.getValue(NodeId("p1")).snapshot(), "a refused second reconcile publishes nothing")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // #1693 (relocation design §6.3): the DERIVED patch never writes a slot the control
    // plane does not own — in particular never the live edge's base `issued` slot, which
    // the data plane writes concurrently (finding 1's silently-erased contended slot).
    // Structural rather than validated: with the proposer sending no magnitudes at all,
    // there is no witness to smuggle anything through.
    // ═══════════════════════════════════════════════════════════════════════════
    @Test
    fun theDerivedReconcileNeverWritesTheLiveEdgesBaseIssuedSlot() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val fake = FakeRaftNode(selfId = NodeId("p3"), initialRole = RaftRole.Leader)
        val sink = RecordingSink()
        val p3 = ReplicaId("p3")
        val plane = HeddleControlPlane(
            raft = fake, self = p3, scope = backgroundScope, sink = sink, membership = NO_REMONITOR,
            barrier = ControlBarrierSink { edge -> sink.snapshot().baseFinalsOn(edge, p3) },
            initial = EntitlementLedger.ZERO, incarnation = "boot-reloc-gate",
        )
        val child = GroupId("g")
        val old = AttachmentId("eOld") // root → g, retired
        val live = AttachmentId("eNew") // root → g, the reparent generation

        assertIs<ControlOutcome.Applied>(plane.submit(ControlCommand.Enroll(p3)))
        assertIs<ControlOutcome.Applied>(plane.submit(ControlCommand.Prepare(AttachmentRecord(old, root, child, Weight.ONE))))
        assertIs<ControlOutcome.Applied>(plane.submit(ControlCommand.Activate(old)))
        assertIs<ControlOutcome.Applied>(plane.submit(ControlCommand.Close(old)))
        assertIs<ControlOutcome.Applied>(plane.submit(ControlCommand.Retire(old, witness = null)))
        assertIs<ControlOutcome.Applied>(plane.submit(ControlCommand.Prepare(AttachmentRecord(live, root, child, Weight.ONE))))
        assertIs<ControlOutcome.Applied>(plane.submit(ControlCommand.Activate(live)))
        // The data plane: p3 delegated 10 down the old edge and charged 3 of service through it.
        sink.forceMerge(
            EntitlementLedger.of(
                issued = mapOf(old to GCounter.of(p3 to 10L)),
                rollupSpent = mapOf(old to GCounter.of(p3 to 3L)),
            ),
        )

        // Unfenced: refused, and nothing published.
        val unfenced = plane.submit(ControlCommand.Reconcile(child))
        assertIs<ControlOutcome.Conflict>(unfenced)
        assertIs<ControlConflict.Refused>(unfenced.conflict)
        assertTrue(sink.snapshot().issuedRelocInEdges().isEmpty(), "an unfenced reconcile must publish nothing")

        assertIs<ControlOutcome.Applied>(plane.submit(ControlCommand.Quiesce(old)))
        runCurrent() // let the barrier's own QuiesceAck commit and apply
        assertEquals(emptySet<ReplicaId>(), plane.pendingAcks(old), "the solo peer acked its own barrier")
        assertIs<ControlOutcome.Applied>(plane.submit(ControlCommand.Reconcile(child)))

        val published = sink.snapshot()
        assertAll(
            { assertEquals(10L, published.effectiveIssued(live, p3), "the re-home credits the live edge") },
            {
                assertTrue(
                    live !in published.issuedEdges(),
                    "the live edge's contended base `issued` slot must never be written; wrote ${published.issuedEdges()}",
                )
            },
            { assertEquals(3L, published.edge(live)?.spent, "the through-service charge re-homes with the issuance") },
            { assertEquals(0L, published.edge(old)?.spent, "the fenced edge's effective spend nets to zero") },
            { assertEquals(0L, published.edge(old)?.outstanding, "the fenced edge is drained") },
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // #1693 §6.1/§13.1: a QuiesceAck is SELF-SERVICE. An ack shrinks what the barrier is
    // still waiting for, so only the acking replica may assert it — exactly the Depart
    // asymmetry. A third-party ack would let the survivors declare a peer done while it
    // still holds an unreplicated reservation: finding 2 through a side door.
    // ═══════════════════════════════════════════════════════════════════════════
    @Test
    fun aThirdPartyQuiesceAckIsRefusedAndDoesNotCompleteTheFence() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val absent = ReplicaId("absent")
        val solo = ReplicaId("solo")
        val fake = FakeRaftNode(selfId = NodeId("solo"), initialRole = RaftRole.Leader)
        val sink = RecordingSink()
        val plane = HeddleControlPlane(
            raft = fake, self = solo, scope = backgroundScope, sink = sink, membership = NO_REMONITOR,
            barrier = NO_BARRIER, initial = EntitlementLedger.ZERO, incarnation = "boot-ack-gate",
        )
        val edge = AttachmentId("e")
        assertIs<ControlOutcome.Applied>(plane.submit(ControlCommand.Enroll(solo)))
        assertIs<ControlOutcome.Applied>(plane.submit(ControlCommand.Enroll(absent)))
        assertIs<ControlOutcome.Applied>(plane.submit(ControlCommand.Prepare(AttachmentRecord(edge, root, GroupId("c"), Weight.ONE))))
        assertIs<ControlOutcome.Applied>(plane.submit(ControlCommand.Activate(edge)))
        assertIs<ControlOutcome.Applied>(plane.submit(ControlCommand.Close(edge)))
        assertIs<ControlOutcome.Applied>(plane.submit(ControlCommand.Retire(edge, witness = null)))

        // A barrier over a LIVE edge is refused outright — a peer cannot promise never to write an
        // edge the data plane may still legitimately use.
        val live = AttachmentId("live")
        assertIs<ControlOutcome.Applied>(plane.submit(ControlCommand.Prepare(AttachmentRecord(live, root, GroupId("c2"), Weight.ONE))))
        assertIs<ControlOutcome.Applied>(plane.submit(ControlCommand.Activate(live)))
        val onLive = plane.submit(ControlCommand.Quiesce(live))
        assertIs<ControlOutcome.Conflict>(onLive)
        assertIs<ControlConflict.Refused>(onLive.conflict)
        assertNull(plane.pendingAcks(live), "a refused Quiesce opens no barrier")

        assertIs<ControlOutcome.Applied>(plane.submit(ControlCommand.Quiesce(edge)))
        runCurrent() // solo's own barrier acks for itself
        assertEquals(setOf(absent), plane.pendingAcks(edge), "the fence still waits on the absent peer")

        // `solo` tries to ack ON BEHALF OF `absent` — refused, and the fence stays open.
        val impersonated = plane.submit(ControlCommand.QuiesceAck(edge, absent, SlotFinals(99L, 0L, 0L, 0L)))
        assertIs<ControlOutcome.Conflict>(impersonated)
        assertAll(
            { assertIs<ControlConflict.Refused>(impersonated.conflict) },
            { assertEquals(setOf(absent), plane.pendingAcks(edge), "a refused ack must not complete the fence") },
        )
        // An ack for an unbarriered edge promises nothing, and is refused too.
        val unbarriered = plane.submit(ControlCommand.QuiesceAck(AttachmentId("never-fenced"), solo, SlotFinals.ZERO))
        assertIs<ControlOutcome.Conflict>(unbarriered)
        assertIs<ControlConflict.Refused>(unbarriered.conflict)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // #1665 (§9 #3): reconcile is fenced by readIndex() — a deposed/non-leader proposer
    // is refused BEFORE it computes a witness from its (unfenced) data-plane view, so a
    // partitioned ex-leader can never drive recovery from stale authority.
    // ═══════════════════════════════════════════════════════════════════════════
    @Test
    fun reconcileReadIndexFenceRefusesANonLeaderProposer() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
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

    // ═══════════════════════════════════════════════════════════════════════════
    // #1752: the #1713 defect is gone by construction, so its fence is gone too.
    // A null front used to be ambiguous in a way that mattered — a genuine first
    // generation OR a view whose applied prefix is behind the committed log — because
    // the seat was frozen into the committed bytes and every peer applied the same
    // wrong value permanently. A record carries no seat now: a stale proposer's
    // Prepare is inert, so the act is admitted and nothing is left to be wrong about.
    // ═══════════════════════════════════════════════════════════════════════════
    @Test
    fun prepareOnAStaleEmptyViewIsAdmittedBecauseNoSeatIsFrozen() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val fake = FakeRaftNode(selfId = NodeId("solo"), initialRole = RaftRole.Leader)
        // The #1713 stale-view shape: the committed log is AHEAD of what this peer has folded.
        // readIndex fences at 42 while the control plane's applied prefix is still 0, i.e. the
        // siblings' Prepare/Activate entries are committed but not yet applied here — so `front`
        // reads null for a parent that, in log order, already has children.
        fake.readIndexBehavior = { 42L }
        val loom = InMemoryLoom()
        val seam: Seam = loom.host(Pattern("heddle-h5-neutral-stale"))
        val self = ReplicaId(seam.selfId.value)
        val governed = backgroundScope.heddleGoverned(
            seam = seam, self = self, raft = fake, root = root,
            clock = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }, config = config(seed = 17),
            incarnation = "boot-neutral-stale", epoch = 0L,
        )

        val newborn = AttachmentId("newborn")
        assertNull(governed.parentVirtualTime(root), "the stale view must show no front at all")

        assertIs<ControlOutcome.Applied>(governed.prepareNeutral(newborn, root, GroupId("child"), Weight.ONE))
        val record = governed.ledger.value.record(newborn)
        assertNotNull(record)
        assertAll(
            {
                assertEquals(
                    AttachmentRecord(newborn, root, GroupId("child"), Weight.ONE),
                    record,
                    "the committed record is the caller's intent and nothing else — no seat rode along",
                )
            },
            {
                assertNull(
                    governed.ledger.value.gauge(newborn),
                    "and no seat was written either: the edge stays unseated until a scheduler bumps it " +
                        "at the front it is actually joining",
                )
            },
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // #1752: the scheduler's seat bump, end to end. The first generation under a
    // parent that has never run is seated at the origin, which IS that parent's
    // virtual-time origin. A LATER joiner is seated at the front it is joining — as an
    // exact Rational, since a Gauge floor never has to be rounded to ⌈V⌉ (§7.2, #1688).
    // ═══════════════════════════════════════════════════════════════════════════
    @Test
    fun theSchedulerSeatsAJoinerAtTheFrontItIsJoining() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val fake = FakeRaftNode(selfId = NodeId("solo"), initialRole = RaftRole.Leader)
        val loom = InMemoryLoom()
        val seam: Seam = loom.host(Pattern("heddle-h5-neutral-origin"))
        val self = ReplicaId(seam.selfId.value)
        val governed = backgroundScope.heddleGoverned(
            seam = seam, self = self, raft = fake, root = root,
            clock = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }, config = config(seed = 19),
            incarnation = "boot-neutral-origin", epoch = 0L,
        )
        assertIs<ControlOutcome.Applied>(governed.enroll(self))
        assertIs<ControlOutcome.Applied>(governed.mint(self, 1_000L))

        // The first generation. Scheduled with nothing advertised, so the seat bump runs but no
        // grant does — the stored gauge is the seat itself, not a later `delegate` checkpoint.
        val first = AttachmentId("first")
        assertNull(governed.parentVirtualTime(root), "a parent with no active children has no front")
        assertIs<ControlOutcome.Applied>(governed.prepareNeutral(first, root, GroupId("leaf"), Weight.ONE))
        assertIs<ControlOutcome.Applied>(governed.activate(first))
        governed.schedule(root)
        assertEquals(
            Gauge(Rational.ZERO, folded = 0L),
            governed.ledger.value.gauge(first),
            "the first generation is seated at the parent's origin — there was no front to join",
        )

        // Now let it render service, so the parent's front advances past the origin.
        governed.advertise(first, Demand(targetOutstanding = 100L, maximumUsefulGrant = 100L))
        governed.schedule(root)
        val front = assertNotNull(governed.parentVirtualTime(root))
        assertTrue(front > Rational.ZERO, "the parent has rendered service, so its front has advanced, was $front")

        // A second generation joins. It is unseated until the scheduler bumps it, and the bump takes
        // the front with the joiner itself excluded — which, `first` being the only other active
        // edge, is exactly `first`'s virtual service at that moment. Again scheduled with nothing
        // newly advertised for it, so the gauge read back is the seat and not a checkpoint.
        val second = AttachmentId("second")
        assertIs<ControlOutcome.Applied>(governed.prepareNeutral(second, root, GroupId("leaf2"), Weight.ONE))
        assertIs<ControlOutcome.Applied>(governed.activate(second))
        assertNull(governed.ledger.value.gauge(second), "a freshly activated edge carries no seat yet")

        val joiningFront = assertNotNull(governed.ledger.value.virtualService(first))
        governed.schedule(root)
        assertEquals(
            Gauge(joiningFront, folded = 0L),
            governed.ledger.value.gauge(second),
            "a joiner is seated at the front it is joining, exactly — never at 0, and never at a rounded ⌈V⌉",
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // #1713 / #1700 doc correction: two proposers of one id do NOT starve the child on
    // the governed path. The log orders them first-wins and answers the loser with a
    // structured Refused ("refuse, don't lie Applied"), so the id resolves —
    // deterministically, on every peer. Starvation is real only on the UNGOVERNED path,
    // where the per-id set union retains both records; that contrast is asserted here
    // because it is where the warning belongs (HeddleNode.prepare).
    //
    // #1752 narrowed what two honest proposers can even disagree about. Two peers whose
    // views disagreed on V used to produce records differing ONLY in the seat, and that
    // was the *unavoidable* half of the hazard — a seat is a reading, and two readings
    // of a moving front are legitimately different. With the seat out of the record,
    // that half dissolves: identical intent now yields byte-identical records, which
    // union to a set of one. What survives is divergent INTENT — a different weight —
    // which is a caller error rather than an artefact of distribution, and that is what
    // this test now contests over.
    // ═══════════════════════════════════════════════════════════════════════════
    @Test
    fun twoProposersOfOneIdAreOrderedFirstWinsAndDoNotStarveTheChild() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val fake = FakeRaftNode(selfId = NodeId("solo"), initialRole = RaftRole.Leader)
        val plane = HeddleControlPlane(
            fake, ReplicaId("solo"), backgroundScope, RecordingSink(), NO_REMONITOR, NO_BARRIER, EntitlementLedger.ZERO, "boot-two-fronts",
        )
        val id = AttachmentId("contested")
        val child = GroupId("c")
        // Divergent intent — the surviving hazard. Same id and edge, different weight.
        val fromPeerA = AttachmentRecord(id, root, child, Weight.ONE)
        val fromPeerB = AttachmentRecord(id, root, child, Weight.of(3))

        assertIs<ControlOutcome.Applied>(plane.submit(ControlCommand.Prepare(fromPeerA)))
        val loser = plane.submit(ControlCommand.Prepare(fromPeerB))
        assertIs<ControlOutcome.Conflict>(loser)
        val conflict = loser.conflict
        assertIs<ControlConflict.Refused>(conflict)

        // The ungoverned merge of the same two records, for contrast: a divergent set, no record.
        val patchA = EntitlementLedger.ZERO.prepare(fromPeerA)
        val patchB = EntitlementLedger.ZERO.prepare(fromPeerB)
        assertNotNull(patchA)
        assertNotNull(patchB)
        val ungoverned = EntitlementLedger.ZERO.piece(patchA.delta).piece(patchB.delta)

        assertAll(
            { assertTrue(conflict.reason.contains(id.value), "the refusal must name the bound id, was: ${conflict.reason}") },
            {
                assertEquals(
                    fromPeerA,
                    plane.projectionSnapshot().record(id),
                    "first-wins: the id resolves to the first proposal, so the child is NOT starved",
                )
            },
            {
                assertNull(
                    ungoverned.record(id),
                    "ungoverned: the per-id set union retains both records and the id resolves to nothing",
                )
            },
            {
                // The half #1752 dissolved: two proposers agreeing on intent now agree byte for
                // byte, so even the ungoverned union resolves. Under the frozen seat these two
                // carried different fronts and starved the child.
                val sameIntent = EntitlementLedger.ZERO
                    .piece(assertNotNull(EntitlementLedger.ZERO.prepare(fromPeerA)).delta)
                    .piece(assertNotNull(EntitlementLedger.ZERO.prepare(AttachmentRecord(id, root, child, Weight.ONE))).delta)
                assertEquals(
                    fromPeerA,
                    sameIntent.record(id),
                    "two proposers with the same intent can no longer diverge on a seat, so the union is a set of one",
                )
            },
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // #1717: an entry that does not decode as a ControlEnvelope is SKIPPED — no outcome,
    // no projection change — while the roster's applied index still advances over it, so
    // the prefix marker `enrolledAt` answers against keeps tracking the DELIVERED log.
    // Both halves are load-bearing and pinned here. The skip is now also logged at `warn`
    // with the entry index; the log line itself has no assertable sink in commonTest, so
    // only the behavioural half is asserted.
    // ═══════════════════════════════════════════════════════════════════════════
    @Test
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    fun undecodableEntryIsSkippedAndStillAdvancesTheAppliedIndex() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val fake = FakeRaftNode(selfId = NodeId("solo"), initialRole = RaftRole.Leader)
        val sink = RecordingSink()
        val plane = HeddleControlPlane(
            fake, ReplicaId("solo"), backgroundScope, sink, NO_REMONITOR, NO_BARRIER, EntitlementLedger.ZERO, "boot-1717",
        )

        assertIs<ControlOutcome.Applied>(plane.submit(ControlCommand.Mint(ReplicaId("acme"), 100L)))
        val projectionBefore = plane.projectionSnapshot()
        val indexBefore = plane.rosterSnapshot().appliedIndex

        // Bytes that are not a ControlEnvelope. `0xFF` is CBOR's break byte, so it can never open a
        // valid encoding. This is what a non-heddle entry looks like — and equally what an older
        // heddle entry stranded by a ControlEnvelope/ControlCommand schema change would look like on
        // the replay-from-index-1 every governed node performs at boot.
        val bytes = byteArrayOf(0xFF.toByte(), 0x00, 0x42)
        assertTrue(
            runCatchingCancellable { Cbor.decodeFromByteArray(ControlEnvelope.serializer(), bytes) }.isFailure,
            "fixture non-vacuity: the bytes must genuinely fail to decode as a ControlEnvelope",
        )
        val undecodable = fake.pushCommitted(bytes)
        runCurrent()

        assertAll(
            { assertTrue(undecodable.index > indexBefore, "sanity: the undecodable entry lands past the mint") },
            { assertEquals(projectionBefore, plane.projectionSnapshot(), "the log-pure projection must be untouched") },
            { assertEquals(100L, sink.snapshot().mintedTotal(), "no patch may be published to the data plane") },
            {
                assertEquals(
                    undecodable.index,
                    plane.rosterSnapshot().appliedIndex,
                    "the applied index must advance over a skipped entry — it marks the delivered prefix",
                )
            },
        )
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
        HeddleControlPlane(raft, ReplicaId(id.value), scope, sink, NO_REMONITOR, NO_BARRIER, EntitlementLedger.ZERO, "inc-${id.value}")

    /**
     * A plane whose §6.2 barrier answers from its **own** data-plane view for its **own** replica —
     * the shape `HeddleNode.asBarrierSink()` has in production, modelled over a [RecordingSink].
     */
    private fun fencedPlane(raft: RaftNode, id: NodeId, sink: RecordingSink, scope: CoroutineScope): HeddleControlPlane {
        val replica = ReplicaId(id.value)
        return HeddleControlPlane(
            raft, replica, scope, sink, NO_REMONITOR,
            ControlBarrierSink { edge -> sink.snapshot().baseFinalsOn(edge, replica) },
            EntitlementLedger.ZERO, "inc-${id.value}",
        )
    }

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

    /** …nor the §6.2 peer-local quiesce barrier — see `HeddleFenceTest`. */
    private val NO_BARRIER = ControlBarrierSink { SlotFinals.ZERO }

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
