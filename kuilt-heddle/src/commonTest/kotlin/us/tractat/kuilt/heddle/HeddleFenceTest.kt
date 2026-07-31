package us.tractat.kuilt.heddle

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * #1693 (slice 3 of #1665) — the **quiesce/ack fence**: the barrier that makes relocating an
 * already-charged spend safe, and the through-service un-gate that rides on it
 * (`docs/heddle-ledger-relocation-design.md` §6.2–§6.5).
 *
 * ## What is under test
 *
 * The adversarial review (design §11) found that the first revision's fence did not fence: a causal
 * stability wait bounds writes that *exist* when the frontier is taken, and says nothing about a peer
 * that has not yet **applied** the barrier. Because the relocation drains the dead edge to zero
 * headroom, one straggler charge afterwards leaves a *permanently unclearable* `PerEdgeSafety` —
 * finding 2, the highest-severity finding and the reason the design was re-cut. The redesign replaces
 * the frontier with a **per-peer promise**, and this suite is the case-by-case walk of the
 * interleavings that promise has to survive:
 *
 * | § | interleaving | covered by |
 * |---|---|---|
 * | 1 | concurrent `delegate` on the move's target edge | `EntitlementLedgerRelocationTest` (no contended slot exists) |
 * | 2 | the barrier racing a completion — the step-2 lock | [preBarrierCompletionLandsInsideTheAckedFinals] / [postBarrierCompletionRehomesOffTheDeadEdge] |
 * | 3 | a pre-ack in-flight delta racing the move | [aPreAckInFlightDeltaIsAbsorbedByTheAckedFinals] |
 * | 4 | the proposer deposed mid-fence | [aDeposedProposerDoesNotStallTheFenceAnyLeaderFinishesIt] |
 * | 5 | a joiner enrolling mid-fence | [aJoinerEnrollingMidFenceIsExcludedFromTheAckSet] |
 * | 6 | a double `Reconcile` | `HeddleControlPlaneTest.reconcileClearsRacedRetireStrandAcrossAllPeers` |
 * | 7 | a straggler `release` on the fenced edge | [aStragglerReleaseOnAFencedEdgeHasNoDataPlaneWriterAtAll] |
 * | 8 | a re-homed charge whose landing edge is itself fenced before `Reconcile` (#1895) | [aReHomedChargeWhoseLandingEdgeIsThenFencedIsFundedFromItsSiblingFencedEdge] |
 *
 * plus the two things the fence deliberately does **not** deliver: liveness hostage to every enrolled
 * peer ([anAbsentEnrolledPeerBlocksTheFenceAndTheStrandStaysStanding]) and the boot gate that makes
 * enrollment a structural precondition for authoring ([theBootGateRefusesWritesUntilSelfEnrollApplies]).
 *
 * **Test discipline (repo CLAUDE.md).** Consensus tests run through the canonical `MultiNodeRaftSim`
 * from `:kuilt-raft-test` — never a hand-rolled cluster network: `StandardTestDispatcher`, tight 5 s
 * timeout, node coroutines on `backgroundScope`, per-node seeded election RNG, bounded `await*`
 * helpers only (never `advanceUntilIdle`). Single-node tests use a [FakeRaftNode] double.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HeddleFenceTest {

    private val root = GroupId("root")
    private val g = GroupId("g")
    private val e1 = AttachmentId("e1") // root → g, stranded by the raced retire
    private val e3 = AttachmentId("e3") // root → g, the legal reparent generation

    // ═══════════════════════════════════════════════════════════════════════════
    // FINDING 2, the acceptance reproducer. A reservation captured across `e1` completes
    // AFTER that peer applied the barrier. Under the pre-fence design the charge landed on
    // the drained edge and left PerEdgeSafety(e1) + ClosureViolation(e1) permanently, with
    // outstanding(e1) < 0 failing both the clearing iteration and the n ≥ sp precondition —
    // unrecoverable. It must now re-home to the live lineage and never touch e1.
    // ═══════════════════════════════════════════════════════════════════════════
    @Test
    fun postBarrierCompletionRehomesOffTheDeadEdge() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val f = fixture("fence-finding2")
        f.mintAndDelegateDownE1()
        val issued = f.node.ledger.value.edge(e1)!!.issued
        assertTrue(issued >= 3L, "fixture: the leaf must be funded, was $issued")

        // The straggler's reservation, captured across e1 while it was still live (design §4.4).
        val reservation = assertNotNull(f.node.reserve(g, 3L), "reserve against the funded leaf")

        f.racedRetireAndReparent()
        assertIs<ControlOutcome.Applied>(f.plane.submit(ControlCommand.Quiesce(e1)))
        runCurrent() // the barrier fires on this peer and its QuiesceAck commits
        assertEquals(emptySet<ReplicaId>(), f.plane.pendingAcks(e1), "the only enrolled peer has acked")
        assertIs<ControlOutcome.Applied>(f.plane.submit(ControlCommand.Reconcile(g)))

        // NOW the straggler completes — after the barrier, against the drained edge.
        f.node.complete(reservation, 3L)
        val converged = f.node.ledger.value

        assertAll(
            { assertEquals(0L, converged.storedSlot(CounterFamily.LEAF_SPENT, e1, f.self), "the dead edge was never charged") },
            { assertEquals(3L, converged.edge(e3)?.spent, "the charge re-homed to the live generation") },
            { assertEquals(0L, converged.edge(e1)?.outstanding, "the fenced edge stays drained") },
            { assertTrue(converged.validate().isEmpty(), "no violation, permanent or transient: ${converged.validate()}") },
            {
                assertEquals(
                    10L,
                    converged.holdings(root, f.self) + converged.holdings(g, f.self) + converged.leafSpentTotal(),
                    "conservation holds across the whole interleaving",
                )
            },
        )

        // Non-vacuity: the SAME charge on the dead edge is exactly the permanent disease, so the
        // re-home is doing the work — this is not a fixture that could not have failed.
        val ifChargedToTheDeadEdge = converged.piece(
            EntitlementLedger.of(leafSpent = mapOf(e1 to GCounter.of(f.self to 3L))),
        )
        assertAll(
            {
                assertTrue(
                    ifChargedToTheDeadEdge.validate().contains(LedgerConflict.PerEdgeSafety(e1)),
                    "fixture non-vacuity: charging the drained edge DOES re-create finding 2's violation",
                )
            },
            {
                assertTrue(
                    ifChargedToTheDeadEdge.edge(e1)!!.outstanding < 0L,
                    "…with outstanding < 0, which no later reconcile can clear (n ≥ sp fails)",
                )
            },
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FINDING 2 on the adversarial PoC's own topology: a MULTI-HOP captured path
    // `[e1, e2]` where only the first hop is quiesced. The re-home rewrites that hop and
    // leaves the rest of the path alone, so the roll-up charge lands on the live generation
    // while the leaf charge still lands on the generation the work was admitted under.
    // ═══════════════════════════════════════════════════════════════════════════
    @Test
    fun postBarrierCompletionOnAMultiHopPathRehomesOnlyTheQuiescedHop() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val h = GroupId("h")
            val e2 = AttachmentId("e2") // g → h
            val f = fixture("fence-multihop")
            f.mintAndDelegateDownE1()
            // …and on down into the leaf h, so the captured path is two hops.
            assertIs<ControlOutcome.Applied>(f.plane.submit(ControlCommand.Prepare(f.rec(e2, g, h))))
            assertIs<ControlOutcome.Applied>(f.plane.submit(ControlCommand.Activate(e2)))
            f.node.advertise(e2, Demand(targetOutstanding = 10L, maximumUsefulGrant = 10L))
            f.node.schedule(g)
            val downE2 = f.node.ledger.value.edge(e2)!!.issued
            assertTrue(downE2 >= 2L, "fixture: the leaf must be funded, was $downE2")

            val reservation = assertNotNull(f.node.reserve(h, 2L), "reserve at the two-hop leaf")
            assertEquals(listOf(e1, e2), f.node.ledger.value.lineageOf(h), "fixture: the captured path is [e1, e2]")

            f.racedRetireAndReparent()
            assertIs<ControlOutcome.Applied>(f.plane.submit(ControlCommand.Quiesce(e1)))
            runCurrent()
            assertIs<ControlOutcome.Applied>(f.plane.submit(ControlCommand.Reconcile(g)))

            f.node.complete(reservation, 2L)
            val converged = f.node.ledger.value
            assertAll(
                { assertEquals(0L, converged.storedSlot(CounterFamily.ROLLUP_SPENT, e1, f.self), "the quiesced hop is never charged") },
                { assertEquals(2L, converged.storedSlot(CounterFamily.ROLLUP_SPENT, e3, f.self), "…the live generation carries it instead") },
                { assertEquals(2L, converged.storedSlot(CounterFamily.LEAF_SPENT, e2, f.self), "the un-quiesced hop still charges its own generation") },
                { assertTrue(converged.validate().isEmpty(), "no violation: ${converged.validate()}") },
                {
                    assertEquals(
                        10L,
                        converged.holdings(root, f.self) + converged.holdings(g, f.self) +
                            converged.holdings(h, f.self) + converged.leafSpentTotal(),
                        "conservation holds across the two-hop re-home",
                    )
                },
            )
        }

    // ═══════════════════════════════════════════════════════════════════════════
    // INTERLEAVING 2, the other side of the step-2 lock. A completion that finishes
    // BEFORE the barrier is a local write, so it is inside the finals the barrier reads —
    // and the relocation therefore moves it. The lock is what makes these the only two
    // cases: there is no window in which a charge lands after the read and before the mark.
    // ═══════════════════════════════════════════════════════════════════════════
    @Test
    fun preBarrierCompletionLandsInsideTheAckedFinals() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val f = fixture("fence-prebarrier")
        f.mintAndDelegateDownE1()
        val issued = f.node.ledger.value.edge(e1)!!.issued
        val reservation = assertNotNull(f.node.reserve(g, 3L))
        f.racedRetireAndReparent()

        // Completes BEFORE the barrier: the charge lands on e1's own base leafSpent slot.
        f.node.complete(reservation, 3L)
        assertEquals(3L, f.node.ledger.value.storedSlot(CounterFamily.LEAF_SPENT, e1, f.self))

        assertIs<ControlOutcome.Applied>(f.plane.submit(ControlCommand.Quiesce(e1)))
        runCurrent()
        assertIs<ControlOutcome.Applied>(f.plane.submit(ControlCommand.Reconcile(g)))
        val converged = f.node.ledger.value

        assertAll(
            { assertEquals(0L, converged.edge(e1)?.spent, "the pre-barrier charge was relocated off e1") },
            { assertEquals(3L, converged.edge(e3)?.spent, "…and onto the live generation") },
            { assertEquals(0L, converged.edge(e1)?.outstanding, "e1 drains cleanly") },
            { assertEquals(issued - 3L, converged.holdings(g, f.self), "g keeps what it did not spend") },
            { assertTrue(converged.validate().isEmpty(), "no violation: ${converged.validate()}") },
            {
                assertEquals(
                    10L,
                    converged.holdings(root, f.self) + converged.holdings(g, f.self) + converged.leafSpentTotal(),
                    "conservation holds",
                )
            },
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // The Close→Activate window (§6.2 step 2.1): a completion whose captured path is
    // quiesced but whose child has no live inbound generation *yet* is BUFFERED, never
    // dropped and never charged to the dead edge. It flushes when one activates.
    // ═══════════════════════════════════════════════════════════════════════════
    @Test
    fun aChargeWithNoLiveGenerationToRehomeOntoIsBufferedAndFlushesOnActivate() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val f = fixture("fence-buffer")
            f.mintAndDelegateDownE1()
            val issued = f.node.ledger.value.edge(e1)!!.issued
            val reservation = assertNotNull(f.node.reserve(g, 3L))

            // Raced retire, but NO reparent yet — g has no live inbound at all.
            assertIs<ControlOutcome.Applied>(f.plane.submit(ControlCommand.Close(e1)))
            assertIs<ControlOutcome.Applied>(f.plane.submit(ControlCommand.Retire(e1, witness = null)))
            assertIs<ControlOutcome.Applied>(f.plane.submit(ControlCommand.Quiesce(e1)))
            runCurrent()

            f.node.complete(reservation, 3L)
            val buffered = f.node.ledger.value
            assertAll(
                { assertEquals(0L, buffered.storedSlot(CounterFamily.LEAF_SPENT, e1, f.self), "the dead edge is never charged") },
                { assertEquals(0L, buffered.leafSpentTotal(), "the charge is held, not applied anywhere yet") },
            )

            // The reparent activates: the buffered charge flushes onto the new generation.
            assertIs<ControlOutcome.Applied>(f.plane.submit(ControlCommand.Prepare(f.rec(e3, root, g))))
            assertIs<ControlOutcome.Applied>(f.plane.submit(ControlCommand.Activate(e3)))
            val flushed = f.node.ledger.value
            assertAll(
                { assertEquals(3L, flushed.edge(e3)?.spent, "the buffered charge lands on the live generation") },
                { assertEquals(0L, flushed.storedSlot(CounterFamily.LEAF_SPENT, e1, f.self), "still never on the dead edge") },
            )

            // …and the relocation then reconciles the strand as usual.
            assertIs<ControlOutcome.Applied>(f.plane.submit(ControlCommand.Reconcile(g)))
            val converged = f.node.ledger.value
            assertAll(
                { assertTrue(converged.validate().isEmpty(), "no violation: ${converged.validate()}") },
                { assertEquals(issued - 3L, converged.holdings(g, f.self)) },
                {
                    assertEquals(
                        10L,
                        converged.holdings(root, f.self) + converged.holdings(g, f.self) + converged.leafSpentTotal(),
                        "conservation holds through the buffered window",
                    )
                },
            )
        }

    // ═══════════════════════════════════════════════════════════════════════════
    // INTERLEAVING 3: a delta a peer emitted BEFORE its ack, still in flight when the move
    // commits, carries per-slot absolutes ≤ its acked finals (slots are monotone in time at
    // their writer), so max-join absorbs it into the already-relocated values. Harmless.
    // ═══════════════════════════════════════════════════════════════════════════
    @Test
    fun aPreAckInFlightDeltaIsAbsorbedByTheAckedFinals() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val f = fixture("fence-inflight")
        f.mintAndDelegateDownE1()
        val issued = f.node.ledger.value.edge(e1)!!.issued
        // The delta this peer emitted just before it acked — the same absolutes the ack declared.
        val inFlight = f.node.ledger.value.projectEdge(e1)

        f.racedRetireAndReparent()
        assertIs<ControlOutcome.Applied>(f.plane.submit(ControlCommand.Quiesce(e1)))
        runCurrent()
        assertIs<ControlOutcome.Applied>(f.plane.submit(ControlCommand.Reconcile(g)))
        val converged = f.node.ledger.value

        // It arrives late, after the move. Every slot it carries is ≤ what the acked finals recorded,
        // so the join is a strict no-op — the relocated state is unchanged and stays valid.
        val afterLateDelivery = converged.piece(inFlight)
        assertAll(
            { assertEquals(converged, afterLateDelivery, "a pre-ack in-flight delta must be a max-join no-op") },
            { assertTrue(afterLateDelivery.validate().isEmpty(), "…and cannot re-create a violation: ${afterLateDelivery.validate()}") },
            { assertEquals(issued, afterLateDelivery.edge(e3)?.issued, "the re-homed credit is untouched") },
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // INTERLEAVING 7 (and design §12.2): a straggler `release` across the fenced edge has no
    // data-plane writer AT ALL — `release` refuses any edge that is not the child's live
    // inbound, which a RETIRED edge never is. That asymmetry is exactly why the `returned`
    // drain needs no fence while the SPEND relocation does: `spendCaptured` can still charge
    // a retired edge, which is what the barrier exists to stop.
    // ═══════════════════════════════════════════════════════════════════════════
    @Test
    fun aStragglerReleaseOnAFencedEdgeHasNoDataPlaneWriterAtAll() {
        val p3 = ReplicaId("p3")
        var l = EntitlementLedger.ZERO.piece(EntitlementLedger.bootstrap(root, mapOf(p3 to 10L), nonce = "genesis"))
        l = l.piece(l.prepare(AttachmentRecord(e1, root, g, Weight.ONE, 0L))!!.delta)
        l = l.piece(l.activate(e1)!!.delta)
        l = l.piece(l.delegate(p3, e1, 10L)!!.delta)
        assertNotNull(l.release(p3, e1, 1L), "while e1 is live, release across it is legal")

        l = l.piece(l.close(e1)!!.delta)
        assertNotNull(l.release(p3, e1, 1L), "a CLOSING edge still drains — that is what closing is for")

        l = l.piece(EntitlementLedger.of(lifecycle = mapOf(e1 to Lifecycle.RETIRED)))
        assertNull(l.release(p3, e1, 1L), "a RETIRED edge is never a live inbound, so release refuses it")
        // …whereas a captured-path charge across the same retired edge is NOT refused, which is the
        // whole reason the spend side needs the barrier and the returned side does not.
        assertNotNull(
            l.spendCaptured(p3, listOf(e1), 1L),
            "spendCaptured still charges a retired edge — the writer the fence has to stop",
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // §6.5 residual 1: fence liveness is DELIBERATELY hostage to every enrolled peer. An
    // enrolled peer that never acks blocks the reconcile forever, and the strand simply
    // stays standing — safe and recoverable. "Proceeding without a slow peer" is exactly
    // the hole finding 2 came through, so there is no way to do it.
    // ═══════════════════════════════════════════════════════════════════════════
    @Test
    fun anAbsentEnrolledPeerBlocksTheFenceAndTheStrandStaysStanding() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val f = fixture("fence-absent")
        val absent = ReplicaId("absent-peer")
        assertIs<ControlOutcome.Applied>(f.plane.submit(ControlCommand.Enroll(absent)))
        f.mintAndDelegateDownE1()
        f.racedRetireAndReparent()
        val strandedConflicts = f.node.ledger.value.validate()
        assertTrue(strandedConflicts.isNotEmpty(), "fixture: the strand's conflicts stand pre-fence")

        assertIs<ControlOutcome.Applied>(f.plane.submit(ControlCommand.Quiesce(e1)))
        runCurrent()
        assertEquals(setOf(absent), f.plane.pendingAcks(e1), "the barrier names the peer it is waiting on")

        val refused = f.plane.submit(ControlCommand.Reconcile(g))
        assertIs<ControlOutcome.Conflict>(refused)
        val conflict = refused.conflict
        assertIs<ControlConflict.Refused>(conflict)
        assertAll(
            { assertTrue(conflict.reason.contains(absent.value), "the refusal names the blocker, was: ${conflict.reason}") },
            {
                assertEquals(
                    strandedConflicts,
                    f.node.ledger.value.validate(),
                    "a blocked fence leaves the strand exactly as it was — never drained to zero headroom",
                )
            },
        )

        // The absent peer returns: a fresh incarnation replaying the committed log re-applies the
        // barrier, re-runs it locally, and acks for itself (§6.5 residual 2's re-ack). Nobody else
        // could have made that promise on its behalf.
        f.peerPlane(absent)
        runCurrent()
        assertEquals(emptySet<ReplicaId>(), f.plane.pendingAcks(e1), "the returning peer closed the fence itself")
        assertIs<ControlOutcome.Applied>(f.plane.submit(ControlCommand.Reconcile(g)))
        assertTrue(f.node.ledger.value.validate().isEmpty(), "the strand clears once every promise is in")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // INTERLEAVING 5: a joiner that enrolls DURING an open fence is excluded from that
    // fence's ack set — the quantifier is `enrolledAt(barrier's commit index)`, not the
    // roster now. It cannot have authored a slot on the edge before a barrier it was not
    // yet a member for, and the boot gate stops it authoring one afterwards.
    // ═══════════════════════════════════════════════════════════════════════════
    @Test
    fun aJoinerEnrollingMidFenceIsExcludedFromTheAckSet() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val f = fixture("fence-joiner")
        f.mintAndDelegateDownE1()
        f.racedRetireAndReparent()
        assertIs<ControlOutcome.Applied>(f.plane.submit(ControlCommand.Quiesce(e1)))
        runCurrent()
        assertEquals(emptySet<ReplicaId>(), f.plane.pendingAcks(e1), "the fence over the enrolled-at-barrier set is closed")

        // A joiner arrives while the fence is open. It joins the ROSTER, but not this barrier.
        val joiner = ReplicaId("joiner")
        assertIs<ControlOutcome.Applied>(f.plane.submit(ControlCommand.Enroll(joiner)))
        assertAll(
            { assertTrue(joiner in f.plane.rosterSnapshot().enrolled, "the joiner is enrolled now") },
            { assertEquals(emptySet<ReplicaId>(), f.plane.pendingAcks(e1), "…but is not in THIS barrier's ack set") },
        )
        assertIs<ControlOutcome.Applied>(f.plane.submit(ControlCommand.Reconcile(g)))
        assertTrue(f.node.ledger.value.validate().isEmpty(), "the mid-fence joiner does not block the move")

        // A barrier opened AFTER the joiner enrolled does wait for it — the quantifier is per-barrier.
        val e4 = AttachmentId("e4")
        assertIs<ControlOutcome.Applied>(f.plane.submit(ControlCommand.Prepare(f.rec(e4, root, GroupId("other")))))
        assertIs<ControlOutcome.Applied>(f.plane.submit(ControlCommand.Activate(e4)))
        assertIs<ControlOutcome.Applied>(f.plane.submit(ControlCommand.Close(e4)))
        assertIs<ControlOutcome.Applied>(f.plane.submit(ControlCommand.Retire(e4, witness = null)))
        assertIs<ControlOutcome.Applied>(f.plane.submit(ControlCommand.Quiesce(e4)))
        runCurrent()
        assertEquals(setOf(joiner), f.plane.pendingAcks(e4), "a LATER barrier does quantify over the joiner")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // INTERLEAVING 4: the fence is log-pure, so it does not belong to the proposer that
    // opened it. Depose the peer that quiesced, elect a new leader, and any peer can finish
    // the reconcile — deriving the identical patch, because the inputs are the log prefix.
    // ═══════════════════════════════════════════════════════════════════════════
    @Test
    fun aDeposedProposerDoesNotStallTheFenceAnyLeaderFinishesIt() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val ids = listOf(NodeId("p1"), NodeId("p2"), NodeId("p3"), NodeId("p4"), NodeId("p5"))
        val sim = MultiNodeRaftSim(nodeIds = ids, scope = this, nodeScope = backgroundScope)
        val sinks = ids.associateWith { RecordingSink() }
        val planes = ids.associateWith { id ->
            val replica = ReplicaId(id.value)
            HeddleControlPlane(
                sim.nodes.getValue(id), replica, backgroundScope, sinks.getValue(id), ControlMembershipSink { },
                ControlBarrierSink { edge -> sinks.getValue(id).snapshot().baseFinalsOn(edge, replica) },
                EntitlementLedger.ZERO, "inc-${id.value}",
            )
        }
        sim.awaitLeader()
        val p3 = ReplicaId("p3")
        val opener = planes.getValue(NodeId("p1"))

        for (id in ids) {
            applied(sim, backgroundScope) { planes.getValue(id).submit(ControlCommand.Enroll(ReplicaId(id.value))) }
        }
        applied(sim, backgroundScope) { opener.submit(ControlCommand.Mint(p3, 10L)) }
        applied(sim, backgroundScope) { opener.submit(ControlCommand.Prepare(AttachmentRecord(e1, root, g, Weight.ONE, 0L))) }
        applied(sim, backgroundScope) { opener.submit(ControlCommand.Activate(e1)) }
        applied(sim, backgroundScope) { opener.submit(ControlCommand.Close(e1)) }
        applied(sim, backgroundScope) { opener.submit(ControlCommand.Retire(e1, witness = null)) }
        applied(sim, backgroundScope) { opener.submit(ControlCommand.Prepare(AttachmentRecord(e3, root, g, Weight.ONE, 0L))) }
        applied(sim, backgroundScope) { opener.submit(ControlCommand.Activate(e3)) }
        sinks.values.forEach { it.forceMerge(EntitlementLedger.of(issued = mapOf(e1 to GCounter.of(p3 to 10L)))) }

        // p1 opens the barrier; every peer acks.
        applied(sim, backgroundScope) { opener.submit(ControlCommand.Quiesce(e1)) }
        sim.awaitTrue("every enrolled peer acked") {
            ids.all { planes.getValue(it).pendingAcks(e1)?.isEmpty() == true }
        }

        // Depose p1 into a minority; a new leader emerges among the rest and finishes the fence.
        val majority = setOf(NodeId("p2"), NodeId("p3"), NodeId("p4"))
        sim.partition(majority, setOf(NodeId("p1"), NodeId("p5")))
        sim.awaitLeader(among = majority)
        val finisher = planes.getValue(NodeId("p2"))
        assertIs<ControlOutcome.Applied>(applied(sim, backgroundScope) { finisher.submit(ControlCommand.Reconcile(g)) })

        sim.awaitTrue("the majority converges on the reconciled state") {
            majority.all { sinks.getValue(it).snapshot().holdings(g, p3) == 10L }
        }
        val reference = sinks.getValue(NodeId("p2")).snapshot()
        majority.forEach {
            assertEquals(reference, sinks.getValue(it).snapshot(), "peer $it derived a different patch")
            assertTrue(sinks.getValue(it).snapshot().validate().isEmpty(), "peer $it still reports a conflict")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // §6.5 residual 3 + §13.2: the boot gate. A governed node may not author entitlement
    // until its OWN enroll has committed and applied here — which both makes enrollment a
    // structural precondition (an unenrolled writer is a writer no barrier waits for) and
    // guarantees every quiesce mark a restart lost has been replayed first.
    // ═══════════════════════════════════════════════════════════════════════════
    @Test
    fun theBootGateRefusesWritesUntilSelfEnrollApplies() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val fake = FakeRaftNode(selfId = NodeId("solo"), initialRole = RaftRole.Leader)
        val loom = InMemoryLoom()
        val seam: Seam = loom.host(Pattern("heddle-boot-gate"))
        val self = ReplicaId(seam.selfId.value)
        val governed = backgroundScope.heddleGoverned(
            seam = seam, self = self, raft = fake, root = root,
            clock = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }, config = config(seed = 23),
            incarnation = "boot-gate", epoch = 0L,
        )
        val leaf = GroupId("leaf")
        assertIs<ControlOutcome.Applied>(governed.mint(self, 1_000L))
        assertIs<ControlOutcome.Applied>(governed.prepare(AttachmentRecord(e1, root, leaf, Weight.ONE, 0L)))
        assertIs<ControlOutcome.Applied>(governed.activate(e1))
        governed.advertise(e1, Demand(targetOutstanding = 500L, maximumUsefulGrant = 500L))

        assertAll(
            { assertFalse(governed.isWritable, "a fresh incarnation boots closed to writes") },
            { assertEquals(0, governed.schedule(root), "schedule delegates nothing while the gate is closed") },
            { assertEquals(0L, governed.ledger.value.edge(e1)?.issued, "…and authors no counter slot") },
            { assertNull(governed.reserve(leaf, 1L), "reserve refuses while the gate is closed") },
        )

        assertIs<ControlOutcome.Applied>(governed.enroll(self))
        assertTrue(governed.isWritable, "the applied self-enroll opens the gate")
        assertTrue(governed.schedule(root) > 0, "and the ordinary data plane runs")
        assertNotNull(governed.reserve(leaf, 1L))

        // Departing closes it again: the promise a departure makes is "I author nothing more".
        assertIs<ControlOutcome.Applied>(governed.depart())
        assertAll(
            { assertFalse(governed.isWritable, "a departed peer is not a writer") },
            { assertNull(governed.reserve(leaf, 1L)) },
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // THE EIGHTH INTERLEAVING (#1895). The seven above are all single-round. This one
    // runs the founding #1665 race TWICE, and it is the re-home itself that arms it.
    //
    // The re-home moves only the CHARGE half of the conserving move onto the live edge at
    // charge time; the CREDIT half (`issuedRelocIn`) can only arrive with a committed
    // `Reconcile`, because §6.3 reserves the relocation families to the control plane. So
    // between a post-barrier completion and the recovery, the live edge carries spend with
    // zero issuance. Race-retire and quiesce THAT edge before any `Reconcile` and the
    // per-edge `n ≥ sp` precondition refused the whole child — deterministically, forever.
    //
    // The fix: `n ≥ sp` is a per-(child, replica) property aggregated across the fenced
    // edges of one derivation, not a per-edge one. Here Σn = 10 (all on e1) against
    // Σsp = 3 (all on e3), so the charge is fundable from its own sibling's surplus —
    // which is exactly where the entitlement came from before the re-home split them.
    // ═══════════════════════════════════════════════════════════════════════════
    @Test
    fun aReHomedChargeWhoseLandingEdgeIsThenFencedIsFundedFromItsSiblingFencedEdge() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val e4 = AttachmentId("e4") // root → g, the SECOND legal reparent generation
            val f = fixture("fence-eighth")
            f.mintAndDelegateDownE1()
            val reservation = assertNotNull(f.node.reserve(g, 3L), "reserve against the funded leaf")

            // Round 1: the raced retire of e1, reparent onto e3, fence e1.
            f.racedRetireAndReparent()
            assertIs<ControlOutcome.Applied>(f.plane.submit(ControlCommand.Quiesce(e1)))
            runCurrent()

            // The straggler completes BEFORE any Reconcile, so the charge re-homes onto the LIVE
            // edge e3 — which holds no issuance for this replica. This transient is the arming step.
            f.node.complete(reservation, 3L)
            assertEquals(3L, f.node.ledger.value.edge(e3)?.spent, "the charge re-homed onto the live edge")

            // Round 2: the same founding race again, now on e3 — retire it, reparent onto e4, fence it.
            assertIs<ControlOutcome.Applied>(f.plane.submit(ControlCommand.Close(e3)))
            assertIs<ControlOutcome.Applied>(f.plane.submit(ControlCommand.Retire(e3, witness = null)))
            assertIs<ControlOutcome.Applied>(f.plane.submit(ControlCommand.Prepare(f.rec(e4, root, g))))
            assertIs<ControlOutcome.Applied>(f.plane.submit(ControlCommand.Activate(e4)))
            assertIs<ControlOutcome.Applied>(f.plane.submit(ControlCommand.Quiesce(e3)))
            runCurrent()

            // Before #1895 this refused with "outstanding on e3 for peer-1 is negative (n=0 < spent=3)",
            // identically on every peer and on every retry — the recovery permanently defeated.
            assertIs<ControlOutcome.Applied>(
                f.plane.submit(ControlCommand.Reconcile(g)),
                "the child's recovery must not be wedged by its own re-home",
            )

            val end = f.node.ledger.value
            assertAll(
                { assertTrue(end.validate().isEmpty(), "no violation survives the recovery: ${end.validate()}") },
                { assertEquals(0L, end.edge(e1)?.outstanding, "e1 drained") },
                { assertEquals(0L, end.edge(e3)?.outstanding, "e3 drained — its spend moved to the live edge") },
                { assertEquals(7L, end.edge(e4)?.outstanding, "the live edge carries 10 issued less the 3 spent") },
                {
                    assertEquals(
                        10L,
                        end.holdings(root, f.self) + end.holdings(g, f.self) + end.leafSpentTotal(),
                        "conservation holds across both rounds",
                    )
                },
            )
        }

    // ═══════════════════════════════════════════════════════════════════════════
    // #1916 — THE PARENT PRECONDITION. Every interleaving above reparents `g` under the
    // SAME parent, and the §5.2 telescoping quietly depends on that: the `+n` a parent
    // recovers when `netInflow(s)` drains is supposed to be the same `−n` it takes on
    // `issuedRelocIn(t)`. Reparent across parents — a legal reshape — and the two terms land
    // on different groups. Nothing is dishonest here and nothing has crashed: `a` recovers
    // the whole mint, the debit lands on `b`, which never held it, and `g` is credited too.
    //
    // The conservation IDENTITY is intact at every step (b's unenforceable −10 offsets a's
    // phantom credit), so no check fires — which is why the fence must refuse the move rather
    // than rely on anything downstream noticing. The arithmetic, and the 20-units-of-service-
    // from-a-10-unit-mint it buys, are pinned in `EntitlementLedgerReconcileTest`.
    // ═══════════════════════════════════════════════════════════════════════════
    @Test
    fun aReconcileAcrossACrossParentReparentIsRefused() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val a = GroupId("a")
        val b = GroupId("b")
        val ea = AttachmentId("ea") // root → a
        val eb = AttachmentId("eb") // root → b
        val e5 = AttachmentId("e5") // a → g, stranded by the raced retire
        val e6 = AttachmentId("e6") // b → g, the CROSS-PARENT reparent generation
        val self = ReplicaId("p1")
        val sink = RecordingSink()
        val plane = HeddleControlPlane(
            raft = FakeRaftNode(selfId = NodeId("solo"), initialRole = RaftRole.Leader),
            self = self, scope = backgroundScope, sink = sink, membership = ControlMembershipSink { },
            barrier = ControlBarrierSink { edge -> sink.snapshot().baseFinalsOn(edge, self) },
            initial = EntitlementLedger.bootstrap(root, emptyMap(), nonce = "cross-parent-genesis"),
            incarnation = "cross-parent",
        )
        fun rec(id: AttachmentId, parent: GroupId, child: GroupId) =
            AttachmentRecord(id, parent, child, Weight.ONE, 0L)
        suspend fun commit(command: ControlCommand) =
            assertIs<ControlOutcome.Applied>(plane.submit(command), "expected Applied for $command")

        commit(ControlCommand.Enroll(self))
        commit(ControlCommand.Mint(self, 10L))
        for ((id, parent, child) in listOf(Triple(ea, root, a), Triple(eb, root, b), Triple(e5, a, g))) {
            commit(ControlCommand.Prepare(rec(id, parent, child)))
            commit(ControlCommand.Activate(id))
        }
        // The data plane delegates the whole mint root → a → g. (The control projection holds no
        // counters by design, so the delegation reaches it the way gossip would: as merged state.)
        sink.forceMerge(
            EntitlementLedger.of(issued = mapOf(ea to GCounter.of(self to 10L), e5 to GCounter.of(self to 10L))),
        )
        // The #1665 race, then a reparent onto a DIFFERENT parent — both individually legal acts.
        commit(ControlCommand.Close(e5))
        commit(ControlCommand.Retire(e5, witness = null))
        commit(ControlCommand.Prepare(rec(e6, b, g)))
        commit(ControlCommand.Activate(e6))

        // The fence closes HONESTLY: the only enrolled peer marks e5 unwritable and acks its real finals.
        commit(ControlCommand.Quiesce(e5))
        runCurrent()
        assertEquals(emptySet<ReplicaId>(), plane.pendingAcks(e5), "the fence is complete — no ack is missing")

        val outcome = plane.submit(ControlCommand.Reconcile(g))
        val conflict = assertIs<ControlConflict.Refused>(assertIs<ControlOutcome.Conflict>(outcome).conflict)
        val end = sink.snapshot()
        val enforceable = listOf(root, a, b, g).sumOf { maxOf(0L, end.holdings(it, self)) } + end.leafSpentTotal()
        assertAll(
            { assertTrue(conflict.reason.contains(e5.value), "the refusal names the retired edge: ${conflict.reason}") },
            { assertTrue(conflict.reason.contains(e6.value), "…and the live edge: ${conflict.reason}") },
            {
                assertEquals(
                    0L,
                    end.storedSlot(CounterFamily.ISSUED_RELOC_IN, e6, self),
                    "fail-closed: a refused reconcile publishes nothing",
                )
            },
            {
                assertTrue(
                    enforceable <= end.mintedTotal(),
                    "enforceable supply (Σ max(0, holdings) + Σ effLeafSpent = $enforceable) must never " +
                        "exceed the mint (${end.mintedTotal()}) — the strand simply stays standing",
                )
            },
        )
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // harness
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * One peer wired the way `heddleGoverned` wires it — a real [HeddleNode] data plane behind a
     * [HeddleControlPlane] — but assembled by hand so a test can submit the **raced** `Retire` that
     * the governed verb's advisory local drain check would refuse. That race is the premise of #1665,
     * so a fixture that cannot express it cannot test the fence.
     */
    private class Fixture(
        val self: ReplicaId,
        val node: HeddleNode,
        val plane: HeddleControlPlane,
        private val raft: RaftNode,
        private val initial: EntitlementLedger,
        private val scope: CoroutineScope,
        private val root: GroupId,
        private val g: GroupId,
        private val e1: AttachmentId,
        private val e3: AttachmentId,
    ) {
        fun rec(id: AttachmentId, parent: GroupId, child: GroupId) =
            AttachmentRecord(id, parent, child, Weight.ONE, 0L)

        /**
         * A **second peer** on the same committed log: its own control plane, its own data-plane view,
         * its own barrier. Creating it models that peer coming (back) online — it replays the log from
         * index 1, so every committed barrier re-fires there and it acks for itself.
         */
        fun peerPlane(replica: ReplicaId): HeddleControlPlane = HeddleControlPlane(
            raft = raft, self = replica, scope = scope, sink = ControlLedgerSink { },
            membership = ControlMembershipSink { }, barrier = ControlBarrierSink { SlotFinals.ZERO },
            initial = initial, incarnation = "peer-${replica.value}",
        )

        /** Mint 10 at the root and let the ordinary scheduler delegate it down `e1` into the leaf `g`. */
        suspend fun mintAndDelegateDownE1() {
            check(plane.submit(ControlCommand.Enroll(self)) is ControlOutcome.Applied)
            check(plane.submit(ControlCommand.Mint(self, 10L)) is ControlOutcome.Applied)
            check(plane.submit(ControlCommand.Prepare(rec(e1, root, g))) is ControlOutcome.Applied)
            check(plane.submit(ControlCommand.Activate(e1)) is ControlOutcome.Applied)
            node.advertise(e1, Demand(targetOutstanding = 10L, maximumUsefulGrant = 10L))
            node.schedule(root)
        }

        /** The #1665 premise: a raced advisory retire of a non-drained `e1`, then a legal reparent. */
        suspend fun racedRetireAndReparent() {
            check(plane.submit(ControlCommand.Close(e1)) is ControlOutcome.Applied)
            // Submitted straight to the log: the projection's counters are empty, so the log-order
            // gate sees a drained CLOSING edge and admits it — exactly the gossip-lagged proposer.
            check(plane.submit(ControlCommand.Retire(e1, witness = null)) is ControlOutcome.Applied)
            check(plane.submit(ControlCommand.Prepare(rec(e3, root, g))) is ControlOutcome.Applied)
            check(plane.submit(ControlCommand.Activate(e3)) is ControlOutcome.Applied)
        }
    }

    private suspend fun TestScope.fixture(pattern: String): Fixture {
        val fake = FakeRaftNode(selfId = NodeId("solo"), initialRole = RaftRole.Leader)
        val loom = InMemoryLoom()
        val seam: Seam = loom.host(Pattern(pattern))
        val self = ReplicaId(seam.selfId.value)
        val initial = EntitlementLedger.bootstrap(root, emptyMap(), nonce = "fence-genesis")
        val node = HeddleNode(
            scope = backgroundScope, seam = seam, self = self, initialLedger = initial,
            clock = { Instant.fromEpochMilliseconds(testScheduler.currentTime) },
            config = config(seed = 31), epoch = 0L,
        )
        val plane = HeddleControlPlane(
            raft = fake, self = self, scope = backgroundScope, sink = node.asControlSink(),
            membership = node.asMembershipSink(), barrier = node.asBarrierSink(),
            initial = initial, incarnation = "fence-boot",
        )
        return Fixture(self, node, plane, fake, initial, backgroundScope, root, g, e1, e3)
    }

    private fun config(seed: Int) = HeddleConfig(
        policy = PolicyConfig(quantum = 10L),
        maxHoldingsPerPeer = 100_000L,
        demandTtl = 30.seconds,
        quilter = QuilterConfig(antiEntropyInterval = 100.milliseconds, fullStateRetryLimit = 0, expectVirtualTime = true),
        heartbeat = HeartbeatConfig(),
        random = Random(seed),
    )

    /** Launch [block] on [scope], pump [sim]'s virtual time until it commits, and require it Applied. */
    private suspend fun applied(
        sim: MultiNodeRaftSim,
        scope: CoroutineScope,
        block: suspend () -> ControlOutcome,
    ): ControlOutcome.Applied {
        val d = scope.async { block() }
        sim.awaitTrue("control op committed") { d.isCompleted }
        val outcome = d.await()
        assertIs<ControlOutcome.Applied>(outcome, "expected Applied, got $outcome")
        return outcome
    }

    /** A [ControlLedgerSink] that accumulates published patches into a ledger — the data-plane view. */
    private class RecordingSink : ControlLedgerSink {
        private val lock = reentrantLock()
        private val state = MutableStateFlow(EntitlementLedger.ZERO)
        override fun publish(patch: Patch<EntitlementLedger>) {
            lock.withLock { state.value = state.value.piece(patch.delta) }
        }
        /** Simulate gossip merging data-plane counters the control plane never authored. */
        fun forceMerge(other: EntitlementLedger) {
            lock.withLock { state.value = state.value.piece(other) }
        }
        fun snapshot(): EntitlementLedger = state.value
    }
}
