package us.tractat.kuilt.heddle

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftRole
import us.tractat.kuilt.raft.test.FakeRaftNode
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * **#2600 — does the documented recovery actually work, and what does it take to reach the freeze
 * at all?** The issue's "recoverable, not permanent" framing rests on one sentence: *"The only
 * thing that clears it is the donor rejoining and re-acking."* #2610 measured the freeze's cost and
 * explicitly did **not** verify that sentence — *"Not verified: that a departed peer can in practice
 * rejoin and re-ack a quiesced edge. The test shows the derivation clears once the ack set contains
 * her; the production roster/fence path for getting her back into that set is untested."*
 *
 * Every arm in `EntitlementLedgerReconcileTest` reaches the freeze by handing
 * [EntitlementLedger.relocationPatch] an ack map with the donor filtered out, and recovers it by
 * handing back one with her put in. That proves the *derivation* and nothing about the protocol.
 * This suite drives the same freeze and the same recovery through [HeddleControlPlane] — real
 * `Quiesce`/`QuiesceAck`/`Enroll`/`Depart`/`Reconcile` acts on a committed log, with the ack
 * produced by a returning peer's own barrier rather than assembled by the test.
 *
 * ## What it establishes
 *
 * | question | answer | arm |
 * |---|---|---|
 * | can a departed donor re-ack an edge quiesced while she was gone? | **yes, and with no operator action** — restarting replays the log, which re-fires the barrier and self-submits the ack | [aDepartedDonorRejoiningReAcksTheBarrierAndClearsTheFrozenMove] |
 * | must she re-enroll first? | **no**, and re-enrolling afterwards reopens nothing | [aReturningDonorNeedsNoReEnrollmentAndReEnrollingReopensNoBarrier] |
 * | can a **crashed** peer reach this refusal? | **no** — `Depart` is self-service, so she is still enrolled and the *earlier* fence gate refuses, naming her | [aCrashedDonorIsRefusedOneGateEarlierAndTheFenceNamesHer] |
 * | is departing enough? | **no** — a departed donor that stays online acks like anyone else | [aDepartedDonorThatStaysOnlineAcksTheBarrierAndNothingFreezes] |
 * | does the departure have to precede the barrier? | **yes** — departing after it changes nothing, she has already acked | [aDonorThatDepartsAfterTheBarrierHasAlreadyAckedAndNothingFreezes] |
 * | can a survivor unblock it? | **no** — re-opening the barrier re-fires it on peers that are present, and she is not | [noSurvivorCanUnblockTheFrozenMoveByReopeningTheBarrier] |
 * | is the recovery *safe*? | **not unconditionally** — the same restart-and-re-ack silently reassigns a row it fails to declare | [anAmnesiacRejoinerClearsTheFreezeWhileSilentlyReassigningTheRecipientsCredit] |
 *
 * Together the middle four say the reachable route is exactly one: **a self-service `Depart` that
 * commits before the `Quiesce`, followed by the peer leaving the apply loop.** That is
 * [GovernedHeddleNode.depart]'s documented graceful exit followed by shutdown — narrow, in that no
 * crash and no partition can produce it, but not exotic.
 *
 * ## Why the fixture drives [HeddleControlPlane] and not [GovernedHeddleNode]
 *
 * The #1665 premise is a **raced** advisory retire, which [GovernedHeddleNode.retire] refuses
 * locally by design — the same reason `HeddleFenceTest.Fixture` assembles its peer by hand. Every
 * governed verb this exercise is *about* ([GovernedHeddleNode.quiesce], [GovernedHeddleNode.enroll],
 * [GovernedHeddleNode.depart], [GovernedHeddleNode.reconcile], [GovernedHeddleNode.pendingAcks]) is
 * a one-line delegation to the act submitted here, so nothing about the fence is modelled away.
 *
 * Data-plane counters arrive through [DataPlane.forceMerge] because `transfer`/`delegate` are
 * ledger-level and reach a peer as gossip, exactly as `HeddleFenceTest` does it. The barrier read is
 * `baseFinalsOn(edge, self)` on that view — `HeddleNode.quiesceLocally`'s own read half verbatim.
 *
 * **Test discipline (repo CLAUDE.md).** One [FakeRaftNode] with several [HeddleControlPlane]s over
 * it — the `HeddleFenceTest.Fixture.peerPlane` pattern for a peer returning on the same committed
 * log — never a hand-rolled cluster network. `StandardTestDispatcher`, a generous
 * [TEST_WEDGE_BACKSTOP] rather than a tight real-time cap, bounded `runCurrent()` pumping and never
 * `advanceUntilIdle()`, and every peer coroutine on a child of `backgroundScope`.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HeddleDepartedDonorRecoveryTest {

    private val root = GroupId("root")
    private val g = GroupId("g")
    private val h = GroupId("h")
    private val e1 = AttachmentId("e1") // root → g
    private val e2 = AttachmentId("e2") // g → h, generation 1 — stranded by race 1
    private val e4 = AttachmentId("e4") // g → h, generation 2 — stranded by race 2, the FROZEN key
    private val e8 = AttachmentId("e8") // g → h, generation 3 — live
    private val alice = ReplicaId("alice") // the hand-off donor who leaves
    private val bob = ReplicaId("bob") // the recipient, and the peer that drives the control plane

    /**
     * The pockets at `h` once `e4` is live and funded — the value every recovery arm must restore.
     * `alice` keeps `MINT − CARRIED − LATE_HANDOFF`; `bob` holds both hand-offs plus his own
     * `OWN_SUPPLY`, so he is simultaneously the recipient and a peer with an unrelated stake.
     */
    private val funded = mapOf(
        alice to MINT - CARRIED - LATE_HANDOFF,
        bob to CARRIED + LATE_HANDOFF + OWN_SUPPLY,
    )

    /** The three conflicts the frozen state leaves on the ledger (#2610's measurement, #2677's arm). */
    private val frozenDiagnosis = listOf(
        LedgerConflict.ClosureViolation(e4),
        LedgerConflict.OrphanedTransferPath(PathKey.of(e4)),
        LedgerConflict.FrozenCarriedHandoff(PathKey.of(e4), alice, CARRIED),
    )

    // ═══════════════════════════════════════════════════════════════════════════
    // THE HEADLINE. The freeze, reproduced end-to-end on the production control plane, then
    // cleared by the donor coming back — with no operator action of any kind. Restarting her
    // control plane is the whole of it: `committedFrom(1)` replays the barrier,
    // `HeddleControlPlane` re-runs it locally on every applied `Quiesce`, and the ack it submits
    // is self-proposed, which is the only thing `QuiesceAck`'s gate asks for.
    // ═══════════════════════════════════════════════════════════════════════════
    @Test
    fun aDepartedDonorRejoiningReAcksTheBarrierAndClearsTheFrozenMove() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val rig = throughFirstMove()
            val before = pockets(rig.bobView.snapshot(), h)
            val honestFinals = rig.aliceView.snapshot().baseFinalsOn(e4, alice)
            rig.aliceDepartsAndGoesOffline()
            rig.race2()

            val frozen = rig.bob.submit(ControlCommand.Reconcile(h))
            runCurrent()
            val refusal = assertIs<ControlConflict.Refused>(assertIs<ControlOutcome.Conflict>(frozen).conflict)
            val frozenView = rig.bobView.snapshot()

            // She comes back: a fresh incarnation of her control plane on the same log, holding the
            // data plane she left with. Nothing else is called — no enroll, no quiesce, no ack verb.
            rig.aliceReturns(rig.aliceView, "alice-2")
            val rosterAtRecovery = rig.bob.rosterSnapshot().enrolled
            val recovered = rig.bob.submit(ControlCommand.Reconcile(h))
            runCurrent()
            val healed = rig.bobView.snapshot()

            assertAll(
                // ── the rig. Each picks out a state the freeze needs; without them the zeros below
                // are satisfied by a fixture that never held anything, which is this repo's
                // most-repeated vacuity shape.
                { assertEquals(funded, before, "rig: both peers hold a non-zero pocket at h while e4 is live") },
                {
                    assertEquals(
                        SlotFinals(0L, 0L, 0L, 0L, transfers = mapOf(bob to LATE_HANDOFF)),
                        honestFinals,
                        "rig: her HONEST declaration at the frozen key is non-zero, so an ack that " +
                            "merely arrives is not the same as an ack that tells the truth",
                    )
                },
                {
                    assertEquals(
                        CARRIED,
                        frozenView.carriedResidualOn(e4, alice, bob),
                        "rig: an earlier move carried her first hand-off onto e4 and nothing has cancelled it",
                    )
                },
                {
                    assertEquals(
                        setOf(bob),
                        rig.bob.rosterSnapshot().enrolled,
                        "rig: she has left the log-known roster, so the fence does not quantify over her",
                    )
                },
                {
                    assertEquals(
                        emptySet<ReplicaId>(),
                        rig.bob.pendingAcks(e4),
                        "rig: the fence gate PASSES — `pendingAcks` names nobody, so this is #2600's " +
                            "refusal and not the ordinary incomplete-fence one",
                    )
                },

                // ── the freeze, on the production surface.
                {
                    assertTrue(
                        alice.value in refusal.reason && e4.value in refusal.reason,
                        "the refusal names the donor and the frozen key: ${refusal.reason}",
                    )
                },
                {
                    assertEquals(
                        mapOf(alice to 0L, bob to 0L),
                        pockets(frozenView, h),
                        "every pocket at the group reads zero, the recipient's included",
                    )
                },
                {
                    assertEquals(
                        frozenDiagnosis,
                        frozenView.validate(),
                        "…and the diagnosis an operator holds is the measured one (#2610, #2677)",
                    )
                },

                // ── the recovery. This is the sentence #2600 rests on and #2610 could not check.
                {
                    assertEquals(
                        setOf(bob),
                        rosterAtRecovery,
                        "she re-acked while still OUT of the roster — `QuiesceAck`'s gate is " +
                            "proposer-identity plus an open barrier, and consults membership nowhere",
                    )
                },
                { assertIs<ControlOutcome.Applied>(recovered, "the move must go through once she has acked") },
                { assertEquals(before, pockets(healed, h), "every pocket returns at its exact pre-retire value") },
                { assertEquals(emptyList(), healed.validate(), "…and no conflict is left standing") },
                // Not merely re-reported: the restored credit is spendable.
                {
                    val spent = healed.spend(bob, h, funded.getValue(bob))?.let { healed.piece(it.delta) }
                    assertEquals(0L, spent?.holdings(h, bob), "the recipient can spend the whole restored pocket")
                },
            )
        }

    // ═══════════════════════════════════════════════════════════════════════════
    // The roster half of the same question, both directions. Enrolling is not a precondition of
    // the ack (above), and it is not a hazard either: `EnrolledRoster.enrolledAt` replays the
    // transitions up to the BARRIER's commit index, so a re-enroll cannot retroactively put her
    // back into a fence she was outside of and re-block a move that had just cleared.
    // ═══════════════════════════════════════════════════════════════════════════
    @Test
    fun aReturningDonorNeedsNoReEnrollmentAndReEnrollingReopensNoBarrier() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val rig = throughFirstMove()
            rig.aliceDepartsAndGoesOffline()
            rig.race2()
            assertIs<ControlOutcome.Conflict>(rig.bob.submit(ControlCommand.Reconcile(h)))
            runCurrent()

            val returned = rig.aliceReturns(rig.aliceView, "alice-2")
            val enrolled = returned.submit(ControlCommand.Enroll(alice))
            runCurrent()
            val outcome = rig.bob.submit(ControlCommand.Reconcile(h))
            runCurrent()

            assertAll(
                { assertIs<ControlOutcome.Applied>(enrolled, "rig: the re-enroll must land, or nothing is exercised") },
                {
                    assertEquals(
                        setOf(bob, alice),
                        rig.bob.rosterSnapshot().enrolled,
                        "rig: …and she must really be back in the roster",
                    )
                },
                {
                    assertEquals(
                        emptySet<ReplicaId>(),
                        rig.bob.pendingAcks(e4),
                        "the barrier she re-acked stays closed — a later enroll is not in `enrolledAt(barrier)`",
                    )
                },
                {
                    assertEquals(
                        emptySet<ReplicaId>(),
                        rig.bob.pendingAcks(e2),
                        "…and neither is the older barrier reopened",
                    )
                },
                { assertIs<ControlOutcome.Applied>(outcome, "the move still goes through after the re-enroll") },
                {
                    assertEquals(
                        funded,
                        pockets(rig.bobView.snapshot(), h),
                        "…re-homing exactly what it re-homed without one",
                    )
                },
            )
        }

    // ═══════════════════════════════════════════════════════════════════════════
    // ORDERING 1 — a CRASH cannot reach this refusal at all. `Depart` is self-service only, so a
    // peer that stops without proposing one stays in the roster, `enrolledAt(barrier)` still
    // contains her, and the fence gate refuses one step BEFORE `relocationPatch` runs. Same
    // frozen state, but the refusal names her and `pendingAcks` names her too — strictly the
    // better diagnosis. So "the donor left for good" is not sufficient for #2600's arm.
    // ═══════════════════════════════════════════════════════════════════════════
    @Test
    fun aCrashedDonorIsRefusedOneGateEarlierAndTheFenceNamesHer() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val rig = throughFirstMove()
            rig.aliceGoesOffline() // she crashes — no `Depart` is ever proposed
            rig.race2()
            val frozen = rig.bob.submit(ControlCommand.Reconcile(h))
            runCurrent()
            val refusal = assertIs<ControlConflict.Refused>(assertIs<ControlOutcome.Conflict>(frozen).conflict)
            val frozenView = rig.bobView.snapshot()
            // Both read while she is still away — after the return below they are the recovered values.
            val rosterWhileAway = rig.bob.rosterSnapshot().enrolled
            val pendingWhileAway = rig.bob.pendingAcks(e4)

            rig.aliceReturns(rig.aliceView, "alice-2")
            val recovered = rig.bob.submit(ControlCommand.Reconcile(h))
            runCurrent()

            assertAll(
                {
                    assertEquals(
                        setOf(bob, alice),
                        rosterWhileAway,
                        "rig: a crashed peer is still enrolled — nobody else may depart her",
                    )
                },
                {
                    assertEquals(
                        setOf(alice),
                        pendingWhileAway,
                        "the fence gate blocks first, and it NAMES her — which #2600's gate does not",
                    )
                },
                {
                    assertTrue(
                        "fence over ${e4.value} is incomplete" in refusal.reason,
                        "…so the refusal is the incomplete-fence one, not the carried-row one: ${refusal.reason}",
                    )
                },
                {
                    assertEquals(
                        mapOf(alice to 0L, bob to 0L),
                        pockets(frozenView, h),
                        "the freeze itself is identical: every pocket at the group reads zero",
                    )
                },
                {
                    assertEquals(
                        frozenDiagnosis,
                        frozenView.validate(),
                        "…down to the same three conflicts on the ledger",
                    )
                },
                { assertIs<ControlOutcome.Applied>(recovered, "and the same return clears it") },
                { assertEquals(funded, pockets(rig.bobView.snapshot(), h), "…restoring the same pockets") },
            )
        }

    // ═══════════════════════════════════════════════════════════════════════════
    // ORDERING 2 — departing is not enough either. `Quiesce`'s apply branch runs the local
    // barrier and self-submits the ack on EVERY peer that applies it, with no roster check
    // anywhere, so a peer that formally departed and then kept running acks like anybody else.
    // ═══════════════════════════════════════════════════════════════════════════
    @Test
    fun aDepartedDonorThatStaysOnlineAcksTheBarrierAndNothingFreezes() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val rig = throughFirstMove()
            val departed = rig.aliceDeparts()
            rig.race2()
            // Read before the move, which cancels the row it carries — afterwards this is the drained value.
            val residualBeforeTheMove = rig.bobView.snapshot().carriedResidualOn(e4, alice, bob)
            val rosterBeforeTheMove = rig.bob.rosterSnapshot().enrolled
            val outcome = rig.bob.submit(ControlCommand.Reconcile(h))
            runCurrent()

            assertAll(
                { assertIs<ControlOutcome.Applied>(departed, "rig: her self-service departure must land") },
                {
                    assertEquals(
                        setOf(bob),
                        rosterBeforeTheMove,
                        "rig: …and really remove her from the log-known roster",
                    )
                },
                {
                    assertEquals(
                        CARRIED,
                        residualBeforeTheMove,
                        "rig: her uncancelled carried row is present — the refusal's own precondition",
                    )
                },
                { assertIs<ControlOutcome.Applied>(outcome, "a departed-but-running donor does not freeze the move") },
                { assertEquals(funded, pockets(rig.bobView.snapshot(), h), "…every pocket survives untouched") },
                { assertEquals(emptyList(), rig.bobView.snapshot().validate(), "…and no conflict is raised") },
            )
        }

    // ═══════════════════════════════════════════════════════════════════════════
    // ORDERING 3 — and the departure has to come BEFORE the barrier. Depart after it and her
    // ack is already recorded; `FenceState.acks` never shrinks, so leaving cannot withdraw it.
    // With orderings 1 and 2 this closes the enumeration: the only route to #2600's refusal is
    // a self-service `Depart` committed before the `Quiesce`, followed by leaving the apply loop.
    // ═══════════════════════════════════════════════════════════════════════════
    @Test
    fun aDonorThatDepartsAfterTheBarrierHasAlreadyAckedAndNothingFreezes() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val rig = throughFirstMove()
            rig.race2() // the barrier commits while she is enrolled AND online, so she acks it
            val ackedBeforeLeaving = rig.bob.pendingAcks(e4)
            rig.aliceDepartsAndGoesOffline()
            val outcome = rig.bob.submit(ControlCommand.Reconcile(h))
            runCurrent()

            assertAll(
                {
                    assertEquals(
                        emptySet<ReplicaId>(),
                        ackedBeforeLeaving,
                        "rig: she acked while present — the ordering this arm is about",
                    )
                },
                {
                    assertEquals(
                        setOf(bob),
                        rig.bob.rosterSnapshot().enrolled,
                        "rig: …and then departed for good",
                    )
                },
                { assertIs<ControlOutcome.Applied>(outcome, "a departure after the barrier withdraws nothing") },
                { assertEquals(funded, pockets(rig.bobView.snapshot(), h), "…so every pocket is re-homed") },
            )
        }

    // ═══════════════════════════════════════════════════════════════════════════
    // There is no survivor-side escape hatch, and the two candidate routes are worth separating.
    // `GovernedHeddleNode.reconcile` opens a barrier only where `pendingAcks(edge) == null`, so it
    // never re-asks for an already-quiesced edge; a survivor must call `quiesce(e4)` explicitly.
    // That act is Applied and deliberately re-fires the barrier — on every peer PRESENT to apply
    // it, which is exactly the set the departed donor is not in. So the recovery really is hers
    // alone to perform, as #2600 says, and the fence's stated liveness trade is intact.
    // ═══════════════════════════════════════════════════════════════════════════
    @Test
    fun noSurvivorCanUnblockTheFrozenMoveByReopeningTheBarrier() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val rig = throughFirstMove()
            rig.aliceDepartsAndGoesOffline()
            rig.race2()
            val first = assertIs<ControlOutcome.Conflict>(rig.bob.submit(ControlCommand.Reconcile(h)))
            runCurrent()

            val reopened = rig.bob.submit(ControlCommand.Quiesce(e4))
            runCurrent()
            val second = rig.bob.submit(ControlCommand.Reconcile(h))
            runCurrent()

            assertAll(
                { assertIs<ControlOutcome.Applied>(reopened, "rig: re-opening the barrier is itself admitted") },
                {
                    assertEquals(
                        emptySet<ReplicaId>(),
                        rig.bob.pendingAcks(e4),
                        "rig: …and the fence still reads complete, because she is not in its quantifier",
                    )
                },
                {
                    assertEquals(
                        assertIs<ControlConflict.Refused>(first.conflict).reason,
                        assertIs<ControlConflict.Refused>(
                            assertIs<ControlOutcome.Conflict>(second).conflict,
                        ).reason,
                        "the refusal is byte-identical — re-fencing changes nothing a survivor can use",
                    )
                },
                {
                    assertEquals(
                        mapOf(alice to 0L, bob to 0L),
                        pockets(rig.bobView.snapshot(), h),
                        "…and the pockets stay frozen",
                    )
                },
            )
        }

    // ═══════════════════════════════════════════════════════════════════════════
    // THE RECOVERY WORKS, BUT IT IS NOT SAFE BY ITSELF — and this arm is what stops the headline
    // above being a green about nothing. `unackedCarriedDonors` asks only whether the donor is IN
    // the ack set; it never reads what she declared. A rejoiner whose data plane has not come back
    // therefore clears the freeze in full while under-declaring her own base row, and the recipient
    // silently loses it: `LATE_HANDOFF` units move from bob's pocket to alice's, `validate()` is
    // empty, and nothing anywhere reports it. That is #2366's defect verbatim, reached by the very
    // mechanism #2600 nominates as the remedy.
    //
    // It is not a wiped-store exotic. `heddleGoverned` bootstraps every node from an EMPTY ledger
    // and its data-plane counters return only by anti-entropy, while the control plane replays and
    // re-acks immediately — and #1782 is open precisely because there is no "one full exchange
    // completed" signal to gate the ack behind. `SlotFinals.transfers`'s KDoc is where the hand-off
    // half of this is written down; this is its production-surface receipt.
    // ═══════════════════════════════════════════════════════════════════════════
    @Test
    fun anAmnesiacRejoinerClearsTheFreezeWhileSilentlyReassigningTheRecipientsCredit() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val rig = throughFirstMove()
            val before = pockets(rig.bobView.snapshot(), h)
            rig.aliceDepartsAndGoesOffline()
            rig.race2()
            assertIs<ControlOutcome.Conflict>(rig.bob.submit(ControlCommand.Reconcile(h)))
            runCurrent()

            // She returns before anti-entropy has delivered her own writes back to her.
            val amnesiac = DataPlane()
            rig.aliceReturns(amnesiac, "alice-amnesiac")
            val declared = amnesiac.snapshot().baseFinalsOn(e4, alice)
            val outcome = rig.bob.submit(ControlCommand.Reconcile(h))
            runCurrent()
            val settled = rig.bobView.snapshot()

            assertAll(
                {
                    assertEquals(
                        emptyMap(),
                        declared.transfers,
                        "rig: she declares no hand-off at the frozen key, because she holds none to declare",
                    )
                },
                {
                    assertEquals(
                        LATE_HANDOFF,
                        rig.bobView.snapshot().transfersAt(PathKey.of(e4))[alice]?.count(bob),
                        "rig: …while the row is right there on every surviving peer's view",
                    )
                },
                { assertIs<ControlOutcome.Applied>(outcome, "the under-declared ack clears the freeze in full") },
                {
                    assertEquals(
                        mapOf(alice to before.getValue(alice) + LATE_HANDOFF, bob to before.getValue(bob) - LATE_HANDOFF),
                        pockets(settled, h),
                        "…and $LATE_HANDOFF units move from the recipient back to the donor who gave them away",
                    )
                },
                {
                    assertEquals(
                        emptyList(),
                        settled.validate(),
                        "…with the ledger reporting nothing at all — the loud outcome became the silent one",
                    )
                },
            )
        }

    // ─────────────────────────────────────────────────────────────────────────────
    // harness
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * One peer's data-plane view: the [ControlLedgerSink] the control plane publishes into, plus
     * [forceMerge] for the counters a *data-plane* mutator would have written and gossip delivered
     * (`delegate`, `transfer`) — `HeddleFenceTest`'s `RecordingSink` shape, and for its reason.
     */
    private class DataPlane : ControlLedgerSink {
        private val lock = reentrantLock()
        private val state = MutableStateFlow(EntitlementLedger.ZERO)
        override fun publish(patch: Patch<EntitlementLedger>) {
            lock.withLock { state.value = state.value.piece(patch.delta) }
        }

        /** Merge counters the control plane never authored — what anti-entropy would deliver. */
        fun forceMerge(other: EntitlementLedger) {
            lock.withLock { state.value = state.value.piece(other) }
        }

        fun snapshot(): EntitlementLedger = state.value
    }

    /**
     * The cluster under test: one committed log, `bob`'s control plane driving it, and `alice`'s on
     * a cancellable child scope so she can be taken offline and brought back as a fresh incarnation
     * replaying the same log.
     */
    private inner class Rig(
        val scope: TestScope,
        val raft: FakeRaftNode,
        val genesis: EntitlementLedger,
        val bob: HeddleControlPlane,
        val bobView: DataPlane,
        val aliceView: DataPlane,
        var aliceScope: CoroutineScope,
    ) {
        suspend fun applied(command: ControlCommand): ControlOutcome.Applied {
            val outcome = bob.submit(command)
            scope.runCurrent()
            return assertIs(outcome, "expected Applied for $command")
        }

        /** `alice`'s self-service departure, proposed by her own live plane — the gate requires it. */
        suspend fun aliceDeparts(): ControlOutcome {
            val plane = plane(alice, aliceView, aliceScope, "alice-depart", genesis, raft)
            scope.runCurrent()
            val outcome = plane.submit(ControlCommand.Depart(alice))
            scope.runCurrent()
            return outcome
        }

        /** Stop applying the committed log here — a crash, a stop, or a partition from Raft. */
        fun aliceGoesOffline() {
            aliceScope.cancel()
            scope.runCurrent()
        }

        suspend fun aliceDepartsAndGoesOffline() {
            assertIs<ControlOutcome.Applied>(aliceDeparts(), "fixture: the departure must land")
            aliceGoesOffline()
        }

        /**
         * A fresh incarnation of `alice`'s control plane on the same log, over [view]. Constructing
         * it is the whole of the recovery: `committedFrom(1)` replays every committed barrier here,
         * `HeddleControlPlane` re-runs it locally on each applied `Quiesce`, and self-submits the ack.
         */
        fun aliceReturns(view: DataPlane, incarnation: String): HeddleControlPlane {
            aliceScope = CoroutineScope(scope.backgroundScope.coroutineContext + Job())
            val plane = plane(alice, view, aliceScope, incarnation, genesis, raft)
            scope.runCurrent()
            return plane
        }

        /** Race 1: retire the still-outstanding `e2`, reparent `h` onto `e4`, and fence it. */
        suspend fun race1() {
            applied(ControlCommand.Close(e2))
            applied(ControlCommand.Retire(e2, witness = null))
            applied(ControlCommand.Prepare(rec(e4, g, h)))
            applied(ControlCommand.Activate(e4))
            applied(ControlCommand.Quiesce(e2))
            scope.runCurrent()
        }

        /** Race 2: the same thing one generation on — retire `e4`, reparent onto `e8`, fence it. */
        suspend fun race2() {
            applied(ControlCommand.Close(e4))
            applied(ControlCommand.Retire(e4, witness = null))
            applied(ControlCommand.Prepare(rec(e8, g, h)))
            applied(ControlCommand.Activate(e8))
            applied(ControlCommand.Quiesce(e4))
            scope.runCurrent()
        }
    }

    private fun rec(id: AttachmentId, parent: GroupId, child: GroupId) =
        AttachmentRecord(id, parent, child, Weight.ONE)

    private fun plane(
        self: ReplicaId,
        view: DataPlane,
        scope: CoroutineScope,
        incarnation: String,
        genesis: EntitlementLedger,
        raft: FakeRaftNode,
    ): HeddleControlPlane = HeddleControlPlane(
        raft = raft,
        self = self,
        scope = scope,
        sink = view,
        membership = ControlMembershipSink { },
        // `HeddleNode.quiesceLocally`'s read half verbatim: this peer's own base slots on the edge,
        // taken off the view it holds at the moment it marks the edge unwritable.
        barrier = ControlBarrierSink { edge -> view.snapshot().baseFinalsOn(edge, self) },
        initial = genesis,
        incarnation = incarnation,
    )

    /** Alice's and Bob's pockets at [group] — the surface a consumer actually reads. */
    private fun pockets(l: EntitlementLedger, group: GroupId): Map<ReplicaId, Long> =
        mapOf(alice to l.holdings(group, alice), bob to l.holdings(group, bob))

    /**
     * Genesis, topology `root →e1 g →e2 h`, alice's [MINT] delegated down to `h`, and her first
     * hand-off of [CARRIED] to `bob` there.
     *
     * The knobs, and what each one switches **off** if moved — all three measured, not reasoned:
     *
     *  - [CARRIED] is strictly between 0 and [MINT]. At 0 there is no transfer row, so no earlier
     *    move carries one, `unackedCarriedDonors` has nothing to enumerate and #2600 is unreachable;
     *    at [MINT] her own pocket is 0 and every "all pockets freeze" arm goes vacuous on her side.
     *  - [LATE_HANDOFF] is the second hand-off, made while `e4` is `h`'s **live** inbound, so it
     *    lands as a **base** row at `e4`'s key and her honest ack on the fenced edge is non-zero.
     *    **At 0 it is #2610's fixture**, where `baseFinalsOn(e4, alice)` is `SlotFinals.ZERO` and an
     *    amnesiac ack is byte-identical to an honest one — so
     *    [anAmnesiacRejoinerClearsTheFreezeWhileSilentlyReassigningTheRecipientsCredit] would pass
     *    while measuring nothing, and the headline arm's recovery would prove only that *some* ack
     *    arrived. Measured: at 0 both arms recover 60/40 and cannot be told apart.
     *  - [MINT] is 100 so `holdings` at `h` splits 45/55 rather than 0/x.
     */
    private suspend fun TestScope.fund(): Rig {
        val raft = FakeRaftNode(selfId = NodeId("solo"), initialRole = RaftRole.Leader)
        val genesis = EntitlementLedger.bootstrap(root, emptyMap(), nonce = "departed-donor-genesis")
        val bobView = DataPlane()
        val aliceView = DataPlane()
        val bobPlane = plane(bob, bobView, backgroundScope, "bob-1", genesis, raft)
        val aliceScope = CoroutineScope(backgroundScope.coroutineContext + Job())
        plane(alice, aliceView, aliceScope, "alice-1", genesis, raft)
        runCurrent()
        val rig = Rig(this, raft, genesis, bobPlane, bobView, aliceView, aliceScope)

        rig.applied(ControlCommand.Enroll(bob))
        rig.applied(ControlCommand.Enroll(alice))
        rig.applied(ControlCommand.Mint(root, alice, MINT))
        rig.applied(ControlCommand.Prepare(rec(e1, root, g)))
        rig.applied(ControlCommand.Activate(e1))
        rig.applied(ControlCommand.Prepare(rec(e2, g, h)))
        rig.applied(ControlCommand.Activate(e2))
        // The data-plane half, as gossip delivers it: `delegate` root→g→h, then `transfer` at `h`,
        // whose row is written at the path key of h's live inbound generation.
        rig.gossip(
            EntitlementLedger.of(
                issued = mapOf(e1 to GCounter.of(alice to MINT), e2 to GCounter.of(alice to MINT)),
                transfers = mapOf(PathKey.of(e2) to mapOf(alice to GCounter.of(bob to CARRIED))),
            ),
        )
        return rig
    }

    private fun Rig.gossip(delta: EntitlementLedger) {
        bobView.forceMerge(delta)
        aliceView.forceMerge(delta)
    }

    /**
     * [fund] plus race 1 completed honestly — both peers ack `e2`'s barrier and the move carries
     * alice's first hand-off onto `e4` as a **control-plane** `transferRelocIn` row — then her
     * second hand-off across the now-live `e4`.
     *
     * The carried row is #2600's precondition: it is on no peer's ack, so a donor whose only mark on
     * the dead key is such a row is invisible to the fence's own enumeration. The base row is what
     * gives her a non-zero honest declaration there — see [fund]'s knobs.
     */
    private suspend fun TestScope.throughFirstMove(): Rig {
        val rig = fund()
        rig.race1()
        rig.applied(ControlCommand.Reconcile(h))
        rig.applied(ControlCommand.Mint(root, bob, OWN_SUPPLY))
        rig.gossip(
            EntitlementLedger.of(
                // bob's own supply, delegated down across the now-live e4 — his stake in the strand
                // that owes nothing to anybody's hand-off.
                issued = mapOf(
                    e1 to GCounter.of(alice to MINT, bob to OWN_SUPPLY),
                    e4 to GCounter.of(bob to OWN_SUPPLY),
                ),
                transfers = mapOf(PathKey.of(e4) to mapOf(alice to GCounter.of(bob to LATE_HANDOFF))),
            ),
        )
        assertEquals(
            CARRIED,
            rig.bobView.snapshot().carriedResidualOn(e4, alice, bob),
            "fixture: the first move must leave an uncancelled carried row at e4, or #2600 is unreachable",
        )
        return rig
    }

    private companion object {
        /** Root supply minted to `alice`. */
        const val MINT: Long = 100L

        /** Her hand-off across `e2` — the row an earlier move CARRIES onto `e4`, and freezes there. */
        const val CARRIED: Long = 40L

        /** Her hand-off across the live `e4` — the BASE row her ack on the fenced edge declares. */
        const val LATE_HANDOFF: Long = 15L

        /**
         * `bob`'s own supply, delegated across the live `e4` — a counter slot on the frozen strand
         * that owes nothing to any hand-off. It is what makes the refusal's *absence* observable:
         * with no drainable slot on `e4` the derivation returns `Relocation.Nothing` whether the
         * `unackedCarriedDonors` guard fires or not, so a fixture without it can only tell the two
         * apart by the refusal's wording (measured — see the mutation table on the PR).
         */
        const val OWN_SUPPLY: Long = 30L
    }
}
