package us.tractat.kuilt.heddle

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Seam
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
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * #1692 (slice 2 of #1665) — the **log-known roster**: `Enroll`/`Depart` as Raft-committed control
 * commands, with membership a deterministic function of the committed log prefix
 * (`heddle-ledger-relocation-design.md` §6.2 prerequisite).
 *
 * The property under test throughout is **log-purity**: two peers that have applied the same log
 * prefix hold the *same* roster, and `enrolledAt(index)` answers identically on every peer — the
 * quantifier the §6.2 fence needs for "every enrolled peer has acked". Nothing here relocates, and
 * no roster act touches the replicated entitlement ledger.
 *
 * **Test discipline (repo CLAUDE.md).** Consensus tests run through the canonical `MultiNodeRaftSim`
 * from `:kuilt-raft-test` — never a hand-rolled cluster network: `StandardTestDispatcher`, a generous
 * `TEST_WEDGE_BACKSTOP` wedge ceiling (never a tight real-time cap, #1739), node coroutines on
 * `backgroundScope`, per-node seeded election RNG, bounded `await*`
 * helpers only (never `advanceUntilIdle`). Single-node determinism tests use a [FakeRaftNode].
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HeddleRosterTest {

    private val root = GroupId("root")
    private val a = ReplicaId("a")
    private val b = ReplicaId("b")

    // ═══════════════════════════════════════════════════════════════════════════
    // The fold itself — log-purity is structural, provable with no Raft at all.
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun rosterIsADeterministicFunctionOfTheLogPrefix() {
        // Two independent folds of the SAME prefix, built by different call sites, must be equal —
        // value-equal, not merely set-equal, so "same prefix ⇒ same roster" is exact (Raft §5.4.3).
        fun fold() = EnrolledRoster.EMPTY
            .advancedTo(3L).enroll(a)!!
            .advancedTo(7L).enroll(b)!!
            .advancedTo(9L).depart(a)!!
        val one = fold()
        val two = fold()
        assertAll(
            { assertEquals(one, two, "the same prefix must fold to the same roster") },
            { assertEquals(one.hashCode(), two.hashCode()) },
            { assertEquals(setOf(b), one.enrolled) },
            { assertEquals(9L, one.appliedIndex) },
        )
    }

    @Test
    fun enrolledAtExcludesReplicasEnrolledLater() {
        // §6.2's commit-index quantifier: the ack set for a barrier committed at index i is the
        // enrolled set AT i — a joiner that enrolls at a later index is excluded from that fence.
        val roster = EnrolledRoster.EMPTY
            .advancedTo(3L).enroll(a)!!
            .advancedTo(5L) // the barrier commits here; b is not yet enrolled
            .advancedTo(7L).enroll(b)!!
        assertAll(
            { assertEquals(emptySet<ReplicaId>(), roster.enrolledAt(2L), "nothing enrolled before a's enroll") },
            { assertEquals(setOf(a), roster.enrolledAt(3L), "enrolledAt is inclusive of its own index") },
            { assertEquals(setOf(a), roster.enrolledAt(5L), "the mid-fence joiner is excluded") },
            { assertEquals(setOf(a, b), roster.enrolledAt(7L)) },
        )
    }

    @Test
    fun enrolledAtBeyondTheAppliedPrefixFailsFast() {
        // Asking about a prefix this peer has not applied is a caller bug, not a defensible answer:
        // silently returning the short fold would let a fence quantify over a roster it cannot know.
        val roster = EnrolledRoster.EMPTY.advancedTo(4L).enroll(a)!!
        assertFailsWith<IllegalArgumentException> { roster.enrolledAt(5L) }
    }

    @Test
    fun idempotentEnrollAndDepartAppendNoTransition() {
        val enrolled = EnrolledRoster.EMPTY.advancedTo(1L).enroll(a)!!
        assertAll(
            { assertNull(enrolled.advancedTo(2L).enroll(a), "re-enrolling an enrolled replica is a no-op") },
            { assertNull(EnrolledRoster.EMPTY.advancedTo(1L).depart(a), "departing a non-member is a no-op") },
        )
    }

    @Test
    fun reEnrollAfterDepartRestoresMembership() {
        val roster = EnrolledRoster.EMPTY
            .advancedTo(1L).enroll(a)!!
            .advancedTo(2L).depart(a)!!
            .advancedTo(3L).enroll(a)!!
        assertAll(
            { assertEquals(setOf(a), roster.enrolled) },
            { assertEquals(emptySet<ReplicaId>(), roster.enrolledAt(2L), "the departed window still reads empty") },
        )
    }

    @Test
    fun theAppliedIndexMustAdvanceStrictly() {
        // The apply loop's contract (RaftNode.committedFrom: "exactly once, in index order"). A
        // repeat or a regression would silently corrupt the quantifier, so it fails loud.
        val roster = EnrolledRoster.EMPTY.advancedTo(5L)
        assertAll(
            { assertFailsWith<IllegalArgumentException> { roster.advancedTo(5L) } },
            { assertFailsWith<IllegalArgumentException> { roster.advancedTo(4L) } },
            { assertEquals(6L, roster.advancedTo(6L).appliedIndex, "a gap (a withheld no-op) is legal") },
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // The control plane — Enroll/Depart as committed acts.
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun enrollAppliesFromTheLogAndIsIdempotent() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val plane = soloPlane(backgroundScope, RecordingSink())
        assertIs<ControlOutcome.Applied>(plane.submit(ControlCommand.Enroll(a)))
        assertIs<ControlOutcome.Applied>(plane.submit(ControlCommand.Enroll(a))) // idempotent, still Applied
        assertIs<ControlOutcome.Applied>(plane.submit(ControlCommand.Enroll(b)))
        assertEquals(setOf(a, b), plane.rosterSnapshot().enrolled)
    }

    @Test
    fun departOfAnotherReplicaIsRefused() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        // Depart SHRINKS the fence quantifier, so only the departing replica may promise it will
        // never author another slot (§6.1: "only the promiser can make it"). A third party cannot
        // retire a peer's authority — that is the unshipped RevocationSeam's problem (§6.5 #1).
        val plane = soloPlane(backgroundScope, RecordingSink())
        assertIs<ControlOutcome.Applied>(plane.submit(ControlCommand.Enroll(a)))

        val refused = plane.submit(ControlCommand.Depart(a)) // proposer is "solo", not a
        assertIs<ControlOutcome.Conflict>(refused)
        assertAll(
            { assertIs<ControlConflict.Refused>(refused.conflict) },
            { assertEquals(setOf(a), plane.rosterSnapshot().enrolled, "the refused depart left the roster intact") },
        )
    }

    @Test
    fun departOfSelfLeavesTheRoster() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val plane = soloPlane(backgroundScope, RecordingSink())
        val solo = ReplicaId("solo")
        assertIs<ControlOutcome.Applied>(plane.submit(ControlCommand.Enroll(solo)))
        assertIs<ControlOutcome.Applied>(plane.submit(ControlCommand.Depart(solo)))
        assertIs<ControlOutcome.Applied>(plane.submit(ControlCommand.Depart(solo))) // idempotent
        assertEquals(emptySet<ReplicaId>(), plane.rosterSnapshot().enrolled)
    }

    @Test
    fun rosterActsNeverTouchTheEntitlementLedger() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        // Slice 2 relocates nothing: the roster lives BESIDE the projection (design §8 "fence state
        // beside the projection"), so no roster act publishes a patch or moves a counter.
        val sink = RecordingSink()
        val plane = soloPlane(backgroundScope, sink)
        val solo = ReplicaId("solo")
        assertIs<ControlOutcome.Applied>(plane.submit(ControlCommand.Enroll(solo)))
        assertIs<ControlOutcome.Applied>(plane.submit(ControlCommand.Depart(solo)))
        assertAll(
            { assertEquals(EntitlementLedger.ZERO, sink.snapshot(), "a roster act published into the data plane") },
            { assertEquals(EntitlementLedger.ZERO, plane.projectionSnapshot(), "a roster act moved the projection") },
        )
    }

    @Test
    fun aDifferentlyIdentifiedObserverReplaysTheIdenticalRoster() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            // The roster is a function of the LOG, not of who reads it: a fresh plane with a
            // different `self`, replaying the same committed prefix, derives the identical roster —
            // including the self-service Depart, which is decided from the committed proposer.
            val fake = FakeRaftNode(selfId = NodeId("solo"), initialRole = RaftRole.Leader)
            val solo = ReplicaId("solo")
            val author = HeddleControlPlane(
                fake, solo, backgroundScope, RecordingSink(), NO_REMONITOR, NO_BARRIER, EntitlementLedger.ZERO, "boot-author",
            )
            assertIs<ControlOutcome.Applied>(author.submit(ControlCommand.Enroll(a)))
            assertIs<ControlOutcome.Applied>(author.submit(ControlCommand.Enroll(solo)))
            assertIs<ControlOutcome.Applied>(author.submit(ControlCommand.Depart(solo)))

            val observer = HeddleControlPlane(
                fake, ReplicaId("observer"), backgroundScope, RecordingSink(), NO_REMONITOR, NO_BARRIER, EntitlementLedger.ZERO, "boot-obs",
            )
            runCurrent() // let the observer replay the committed prefix
            assertEquals(author.rosterSnapshot(), observer.rosterSnapshot())
        }

    @Test
    fun enrolledAtACommitIndexExcludesAMidFenceJoiner() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        // The §6.2 quantifier against real log indices: a barrier committed at index `barrier` acks
        // over the set enrolled at `barrier`; `b`, enrolling afterwards, is not in that set. (Mint
        // stands in for the slice-3 `Quiesce` — this slice ships the quantifier, not the fence.)
        val plane = soloPlane(backgroundScope, RecordingSink())
        assertIs<ControlOutcome.Applied>(plane.submit(ControlCommand.Enroll(a)))
        val barrier = plane.submit(ControlCommand.Mint(root, a, 1L))
        assertIs<ControlOutcome.Applied>(barrier)
        assertIs<ControlOutcome.Applied>(plane.submit(ControlCommand.Enroll(b)))

        val roster = plane.rosterSnapshot()
        assertAll(
            { assertEquals(setOf(a), roster.enrolledAt(barrier.index), "b joined after the barrier committed") },
            { assertEquals(setOf(a, b), roster.enrolled) },
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Multi-node: every peer applying the same prefix agrees — the headline property.
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun everyPeerDerivesTheIdenticalRosterFromTheSameLogPrefix() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val ids = (1..3).map { NodeId("v$it") }
            val sim = MultiNodeRaftSim(nodeIds = ids, scope = this, nodeScope = backgroundScope)
            val planes = ids.associateWith { plane(sim.nodes.getValue(it), it, RecordingSink(), backgroundScope) }
            sim.awaitLeader()

            val r = ids.associateWith { ReplicaId(it.value) }
            // Sequenced so the log order — and therefore every index below — is deterministic.
            val e1 = applied(sim, backgroundScope) { planes.getValue(ids[0]).submit(ControlCommand.Enroll(r.getValue(ids[0]))) }
            val e2 = applied(sim, backgroundScope) { planes.getValue(ids[1]).submit(ControlCommand.Enroll(r.getValue(ids[1]))) }
            val e3 = applied(sim, backgroundScope) { planes.getValue(ids[2]).submit(ControlCommand.Enroll(r.getValue(ids[2]))) }
            // v2 departs itself — the only replica permitted to shrink the quantifier by v2.
            val d2 = applied(sim, backgroundScope) { planes.getValue(ids[1]).submit(ControlCommand.Depart(r.getValue(ids[1]))) }

            val expected = setOf(r.getValue(ids[0]), r.getValue(ids[2]))
            sim.awaitTrue("every peer converges on one roster value") {
                val rosters = ids.map { planes.getValue(it).rosterSnapshot() }
                rosters.distinct().size == 1 && rosters.first().enrolled == expected
            }
            // …and every peer answers the AS-OF question identically, at every index — the fence's
            // quantifier is the same set on every peer, not merely the same eventual membership.
            for (id in ids) {
                val roster = planes.getValue(id).rosterSnapshot()
                assertAll(
                    { assertEquals(setOf(r.getValue(ids[0])), roster.enrolledAt(e1.index), "$id at e1") },
                    { assertEquals(setOf(r.getValue(ids[0]), r.getValue(ids[1])), roster.enrolledAt(e2.index), "$id at e2") },
                    { assertEquals(r.values.toSet(), roster.enrolledAt(e3.index), "$id at e3") },
                    { assertEquals(expected, roster.enrolledAt(d2.index), "$id at d2") },
                )
            }
        }

    @Test
    fun aPartitionedMinorityCannotShrinkTheRoster() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        // A departing peer must reach quorum to leave: a minority-side Depart never commits, so the
        // majority's fence keeps quantifying over it (safe direction — the fence refuses, never
        // completes without a promise it needed).
        val ids = (1..5).map { NodeId("v$it") }
        val sim = MultiNodeRaftSim(nodeIds = ids, scope = this, nodeScope = backgroundScope)
        val planes = ids.associateWith { plane(sim.nodes.getValue(it), it, RecordingSink(), backgroundScope) }
        sim.awaitLeader()

        val v4 = ReplicaId("v4")
        applied(sim, backgroundScope) { planes.getValue(NodeId("v1")).submit(ControlCommand.Enroll(v4)) }

        val majority = setOf(NodeId("v1"), NodeId("v2"), NodeId("v3"))
        val minority = setOf(NodeId("v4"), NodeId("v5"))
        sim.partition(majority, minority)
        sim.awaitLeader(among = majority)

        val depart = backgroundScope.async { planes.getValue(NodeId("v4")).submit(ControlCommand.Depart(v4)) }
        sim.settle()
        assertTrue(
            majority.all { setOf(v4) == planes.getValue(it).rosterSnapshot().enrolled },
            "the minority shrank the majority's roster",
        )
        depart.cancel()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // #1652 — rejoin is membership: a committed Enroll is what re-monitors a peer.
    // (The node-side effect itself is covered by HeddleNodeTest's lost→rejoin test.)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun everyAppliedEnrollSignalsTheNodeIncludingAnIdempotentOne() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            // A peer that crashed is ALREADY enrolled, so its re-enroll on restart appends no roster
            // transition — yet that act is exactly the one that must re-attach its detector. The
            // signal therefore rides the applied *command*, not the fold's delta.
            val enrolled = mutableListOf<ReplicaId>()
            val plane = soloPlane(backgroundScope, RecordingSink(), ControlMembershipSink { enrolled += it })
            assertIs<ControlOutcome.Applied>(plane.submit(ControlCommand.Enroll(a)))
            assertIs<ControlOutcome.Applied>(plane.submit(ControlCommand.Enroll(a)))
            assertEquals(listOf(a, a), enrolled, "the idempotent re-enroll must still signal the node")
        }

    @Test
    fun departAndRefusedActsSignalNothingToTheNode() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        // A departed peer's entitlement stays stranded like a crashed peer's (§8.1), so it must keep
        // counting toward the §8.2 bound — dropping it from the node's roster would understate the
        // bound exactly when its divergence risk is highest. And a refused act changes nothing at all.
        val enrolled = mutableListOf<ReplicaId>()
        val solo = ReplicaId("solo")
        val plane = soloPlane(backgroundScope, RecordingSink(), ControlMembershipSink { enrolled += it })
        assertIs<ControlOutcome.Applied>(plane.submit(ControlCommand.Enroll(solo)))
        assertIs<ControlOutcome.Applied>(plane.submit(ControlCommand.Depart(solo)))
        assertIs<ControlOutcome.Conflict>(plane.submit(ControlCommand.Depart(a))) // third-party: refused
        assertEquals(listOf(solo), enrolled, "only an applied Enroll signals the node")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // The governed public verbs.
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun governedEnrollAndDepartVerbs() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val fake = FakeRaftNode(selfId = NodeId("solo"), initialRole = RaftRole.Leader)
        val loom = InMemoryLoom()
        val seam: Seam = loom.host(Pattern("heddle-roster-governed"))
        val self = ReplicaId(seam.selfId.value)
        val governed = backgroundScope.heddleGoverned(
            seam = seam, self = self, raft = fake, root = root,
            clock = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }, config = config(seed = 13),
            incarnation = "boot-roster", epoch = 0L,
        )
        assertEquals(emptySet<ReplicaId>(), governed.enrolledReplicas(), "a governed node does not self-enroll at bootstrap")
        assertIs<ControlOutcome.Applied>(governed.enroll(self))
        assertEquals(setOf(self), governed.enrolledReplicas())
        assertIs<ControlOutcome.Applied>(governed.depart())
        assertEquals(emptySet<ReplicaId>(), governed.enrolledReplicas())
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // harness helpers
    // ─────────────────────────────────────────────────────────────────────────────

    private val NO_REMONITOR = ControlMembershipSink { }

    /** These suites do not exercise the §6.2 peer-local barrier — see `HeddleFenceTest`. */
    private val NO_BARRIER = ControlBarrierSink { SlotFinals.ZERO }

    private fun soloPlane(
        scope: CoroutineScope,
        sink: ControlLedgerSink,
        membership: ControlMembershipSink = NO_REMONITOR,
    ) = HeddleControlPlane(
        raft = FakeRaftNode(selfId = NodeId("solo"), initialRole = RaftRole.Leader),
        self = ReplicaId("solo"), scope = scope, sink = sink, membership = membership, barrier = NO_BARRIER,
        initial = EntitlementLedger.ZERO, incarnation = "boot-roster",
    )

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
        fun snapshot(): EntitlementLedger = state.value
    }
}
