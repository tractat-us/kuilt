package us.tractat.kuilt.heddle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * [HeddleNode.reconcileOwn] step by step (#3649): the #3176 reproduction on one static peer, each
 * outcome as the reweight's steps arrive, a late completion after the repair, a node booted from a
 * repaired ledger, the stale-finals residual a static restart can leave, and each refusal. The
 * many-peer property is `HeddleNodeReconcileOwnPropertyTest`.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HeddleNodeReconcileOwnTest {

    /**
     * #3176's reproduction under [heddleStatic]: spend at the lane, reweight `acme`, and its holdings
     * read the spend as negative until the peer repairs. The sample reads every outcome on the way.
     */
    @Test
    fun theReweightStrandIsRepairedAndEachStepAnswersWhereItStands() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val h = staticCluster(ROOT, peers = 1, mint = mapOf(0 to 100L), topology = TOPOLOGY)
            val node = h.peers[0].node
            spend(node, 7L)
            h.pump()
            val beforeTheRetire = node.sampleReconcileOwn(ACME)
            check(node.prepare(NEW_RECORD) && node.close(OLD) && node.retire(OLD)) { "rig: the old edge retires" }
            val beforeTheActivate = node.sampleReconcileOwn(ACME)
            check(node.activate(NEW)) { "rig: the new edge activates" }
            val stranded = node.ledger.value
            val repaired = node.sampleReconcileOwn(ACME)
            val again = node.sampleReconcileOwn(ACME)
            h.pump()
            val l = node.ledger.value
            assertAll(
                { assertEquals(-7L, stranded.holdings(ACME, node.self), "the reproduction: acme reads the spend as negative") },
                { assertTrue(LedgerConflict.PersistentNegativeHoldings(ACME, node.self) in stranded.validate(), "and validate reports it") },
                { assertEquals("no retired edge yet", beforeTheRetire) },
                { assertEquals("no live edge yet", beforeTheActivate) },
                { assertEquals("moved 7 credit and 7 spent onto ${NEW.value}", repaired) },
                { assertEquals("nothing of mine to move", again) },
                { assertEquals(0L, l.holdings(ACME, node.self)) },
                { assertEquals(emptyList(), l.validate()) },
                { assertEquals(7L, checkNotNull(l.edge(NEW)).spent, "the spend rides the new edge") },
                { assertEquals(null, l.conservationShortfallOver(GROUPS, listOf(node.self))) },
            )
        }

    /**
     * A reservation taken across the old edge and completed after its peer's repair charges the new
     * edge, not the drained old one: the repair marked the old edge for that peer. Peer 1 reserves
     * while cut off, peer 0 reweights without seeing the grant, and after the heal peer 1 repairs,
     * then completes.
     */
    @Test
    fun aCompletionAfterTheRepairChargesTheNewEdge() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val late = lateReservationAcrossTheRetire()
            val peer1 = late.h.peers[1].node
            assertIs<ReconcileOwnOutcome.Repaired>(late.h.peers[0].node.reconcileOwn(ACME))
            val own = assertIs<ReconcileOwnOutcome.Repaired>(peer1.reconcileOwn(ACME), "peer 1 repairs with its reservation open")
            late.h.pump(1_000L)
            val before = peer1.ledger.value
            peer1.complete(late.id, LATE_COST)
            late.h.pump(1_000L)
            val after = peer1.ledger.value
            assertAll(
                { assertEquals(LATE_COST, own.credit, "rig: the open reservation's grant moved with the repair") },
                { assertEquals(0L, own.spent, "rig: nothing of it was spent before the repair") },
                {
                    assertEquals(
                        before.storedSlot(CounterFamily.ROLLUP_SPENT, NEW, peer1.self) + LATE_COST,
                        after.storedSlot(CounterFamily.ROLLUP_SPENT, NEW, peer1.self),
                        "the charge rolls up through the new edge",
                    )
                },
                { assertEquals(before.edge(OLD), after.edge(OLD), "the drained old edge is not charged") },
                { assertEquals(emptyList(), after.validate()) },
                { assertEquals(ReconcileOwnOutcome.NothingToRepair, peer1.reconcileOwn(ACME)) },
                { assertEquals(null, after.conservationShortfallOver(GROUPS, late.h.peers.map { it.id })) },
            )
        }

    /**
     * The repair lives in the ledger, not in the marks: a node booted from a ledger that already
     * holds its repair has no marks, answers `NothingToRepair`, reserves on the new edge, and never
     * charges the retired one.
     *
     * This is **not** how a [heddleStatic] node restarts. That boots from its mint and gets its earlier
     * writes back by anti-entropy, and what that can leave is
     * [aRepairFromStaleFinalsLeavesALateChargeStranded].
     */
    @Test
    fun aNodeBootedFromARepairedLedgerNeedsNoMarks() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val late = lateReservationAcrossTheRetire()
            val peer1 = late.h.peers[1].node
            assertIs<ReconcileOwnOutcome.Repaired>(late.h.peers[0].node.reconcileOwn(ACME))
            assertIs<ReconcileOwnOutcome.Repaired>(peer1.reconcileOwn(ACME))
            late.h.pump(1_000L)
            val saved = peer1.ledger.value
            late.scope.cancel()

            val booted = bootSecondPeer(saved)
            check(booted.self == peer1.self) { "rig: the same identity, ${booted.self} vs ${peer1.self}" }
            val again = booted.reconcileOwn(ACME)
            val fresh = assertIs<ReserveOutcome.Reserved>(booted.reserve(LANE, LATE_COST), "the booted node reserves its holdings")
            booted.complete(fresh.id, LATE_COST)
            val l = booted.ledger.value
            assertAll(
                { assertEquals(ReconcileOwnOutcome.NothingToRepair, again) },
                { assertEquals(saved.edge(OLD), l.edge(OLD), "the retired edge never moves again") },
                { assertEquals(5L + LATE_COST, checkNotNull(l.edge(NEW)).spent, "both peers' spends ride the new edge") },
                { assertEquals(emptyList(), l.validate()) },
                { assertEquals(null, l.conservationShortfallOver(GROUPS, late.h.peers.map { it.id })) },
            )
        }

    /**
     * What a static restart can leave (relocation design §6.5 residual 2, on the static path). A peer
     * completes a late charge across the retired edge before repairing, and that write reaches the
     * others but not its own restarted view. It repairs from finals without the charge, and when the
     * charge comes back it sits on the drained edge: `PerEdgeSafety`, and every later call answers
     * the relocation refusal.
     */
    @Test
    fun aRepairFromStaleFinalsLeavesALateChargeStranded() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val loom = InMemoryLoom()
            val seam = loom.host(Pattern("reconcile-own-stale"))
            val self = ReplicaId(seam.selfId.value)
            val other = ReplicaId("other-peer")
            val start = buildInitialLedger(ROOT, mapOf(self to 100L, other to 100L), TOPOLOGY)
            // The other peer spends through the old edge and, not seeing this peer's grant, reweights.
            val theirs = start.steps({ it.delegate(other, OLD, 5L) }, { it.delegate(other, LANE_EDGE, 5L) }, { it.spend(other, LANE, 5L) })
            val reweighted = theirs.steps({ it.prepare(NEW_RECORD) }, { it.close(OLD) }, { it.retire(OLD) }, { it.activate(NEW) })
            // This peer took a grant across the old edge first; its view is the two merged.
            val seen = reweighted.piece(start.steps({ it.delegate(self, OLD, LATE_COST) }, { it.delegate(self, LANE_EDGE, LATE_COST) }))
            val lateCharge = checkNotNull(seen.spendCaptured(self, listOf(OLD, LANE_EDGE), LATE_COST))
            val node = HeddleNode(
                scope = backgroundScope, seam = seam, self = self, initialLedger = seen,
                clock = { Instant.fromEpochMilliseconds(testScheduler.currentTime) },
                config = heddleTestConfig(seed = 13), epoch = 1L,
            )
            val stale = node.reconcileOwn(ACME)
            node.asControlSink().publish(lateCharge) // its own earlier write, back by anti-entropy
            val conflicts = node.ledger.value.validate()
            val after = node.reconcileOwn(ACME)
            assertAll(
                { assertEquals(ReconcileOwnOutcome.Repaired(NEW, listOf(OLD), credit = LATE_COST, spent = 0L), stale, "rig: the move missed the charge") },
                { assertTrue(LedgerConflict.PerEdgeSafety(OLD) in conflicts, "the charge sits on the drained edge: $conflicts") },
                { assertIs<ReconcileOwnOutcome.Refused>(after, "and a later call cannot move it") },
            )
        }

    /**
     * A recipient that spent credit another peer handed it cannot repair its own row (#3649's other
     * unverified point): the donor's row is not in its finals, so the move finds its spend
     * uncovered. `HeddleNode` has no `transfer`, so the state is built on the ledger directly; this
     * pins what the verb answers if one ever reaches it.
     */
    @Test
    fun aRecipientThatSpentHandedCreditIsRefused() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val loom = InMemoryLoom()
            val seam = loom.host(Pattern("reconcile-own-transfer"))
            val self = ReplicaId(seam.selfId.value)
            val donor = ReplicaId("donor")
            val l = buildInitialLedger(ROOT, mapOf(donor to 100L, self to 0L), TOPOLOGY).steps(
                { it.delegate(donor, OLD, 10L) },
                { it.transfer(ACME, donor, self, 10L) },
                { it.delegate(self, LANE_EDGE, 10L) },
                { it.spend(self, LANE, 10L) },
                { it.prepare(NEW_RECORD) },
                { it.close(OLD) },
                { it.retire(OLD) },
                { it.activate(NEW) },
            )
            val node = HeddleNode(
                scope = backgroundScope, seam = seam, self = self, initialLedger = l,
                clock = { Instant.fromEpochMilliseconds(testScheduler.currentTime) },
                config = heddleTestConfig(seed = 7), epoch = 0L,
            )
            val outcome = node.reconcileOwn(ACME)
            assertAll(
                { assertIs<ReconcileOwnOutcome.Refused>(outcome) },
                { assertEquals(l, node.ledger.value, "nothing was written") },
            )
        }

    /** A reparent strands a group too, and a move cannot conserve across two parents, so it is refused. */
    @Test
    fun aRetiredEdgeFromAnotherParentIsRefused() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val h = staticCluster(ROOT, peers = 1, mint = mapOf(0 to 100L), topology = TOPOLOGY)
            val node = h.peers[0].node
            spend(node, 4L)
            check(node.prepare(UNDER_OTHER) && node.close(OLD) && node.retire(OLD) && node.activate(UNDER_OTHER.id)) { "rig: acme moves under other" }
            val before = node.ledger.value
            val outcome = node.reconcileOwn(ACME)
            assertAll(
                { assertEquals(ReconcileOwnOutcome.CrossParent(OTHER, mapOf(OLD to ROOT)), outcome) },
                { assertEquals(before, node.ledger.value, "nothing was written") },
            )
        }

    /**
     * One retired edge from the live parent and one from another: nothing moves, not even the
     * same-parent one, so `NothingToRepair` never stands for a strand left in place.
     */
    @Test
    fun oneForeignRetiredEdgeRefusesTheWholeMove() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val h = staticCluster(ROOT, peers = 1, mint = mapOf(0 to 100L), topology = TOPOLOGY)
            val node = h.peers[0].node
            spend(node, 4L)
            check(node.prepare(UNDER_OTHER) && node.close(OLD) && node.retire(OLD) && node.activate(UNDER_OTHER.id)) { "rig: acme moves under other" }
            val reweighted = AttachmentRecord(AttachmentId("other/acme@2"), OTHER, ACME, Weight.of(2L))
            check(node.prepare(reweighted) && node.close(UNDER_OTHER.id) && node.retire(UNDER_OTHER.id) && node.activate(reweighted.id)) {
                "rig: then acme is reweighted under other"
            }
            val before = node.ledger.value
            val outcome = node.reconcileOwn(ACME)
            assertAll(
                { assertEquals(setOf(OLD, UNDER_OTHER.id), before.retiredInboundEdges(ACME).toSet(), "rig: two retired edges") },
                { assertEquals(ReconcileOwnOutcome.CrossParent(OTHER, mapOf(OLD to ROOT)), outcome) },
                { assertEquals(before, node.ledger.value, "nothing was written") },
            )
        }

    /** Two live inbound edges leave no one edge to move onto; the answer names them and nothing moves. */
    @Test
    fun twoLiveInboundEdgesAreNamed() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val h = staticCluster(ROOT, peers = 1, mint = mapOf(0 to 100L), topology = TOPOLOGY)
            val node = h.peers[0].node
            spend(node, 4L)
            check(node.prepare(NEW_RECORD) && node.close(OLD) && node.retire(OLD) && node.activate(NEW)) { "rig: the reweight" }
            val third = AttachmentRecord(AttachmentId("root/acme@3"), ROOT, ACME, Weight.of(3L))
            check(node.prepare(third) && node.activate(third.id)) { "rig: a second live edge into acme" }
            val before = node.ledger.value
            val outcome = node.reconcileOwn(ACME)
            assertAll(
                { assertEquals(ReconcileOwnOutcome.TwoLiveEdges(listOf(NEW, third.id)), outcome) },
                { assertEquals(before, node.ledger.value, "nothing was written") },
            )
        }

    /** A two-peer cluster where peer 1, cut off, reserved across the old edge before peer 0 reweighted it; healed. */
    private class LateReservation(val h: StaticCluster, val scope: CoroutineScope, val id: ReservationId)

    private suspend fun TestScope.lateReservationAcrossTheRetire(): LateReservation {
        val scope = childScope()
        val h = staticCluster(ROOT, peers = 2, mint = mapOf(0 to 100L, 1 to 100L), topology = TOPOLOGY, nodeScope = scope)
        spend(h.peers[0].node, 5L)
        h.pump(1_000L)
        h.partition(1)
        val late = assertIs<ReserveOutcome.Reserved>(grantAndReserve(h.peers[1].node, LATE_COST), "rig: a reservation across the old edge")
        val reweighter = h.peers[0].node
        check(reweighter.prepare(NEW_RECORD) && reweighter.close(OLD) && reweighter.retire(OLD) && reweighter.activate(NEW)) {
            "rig: peer 0 reweights acme without seeing peer 1's grant"
        }
        h.pump()
        h.heal(1)
        h.pump(1_000L)
        return LateReservation(h, scope, late.id)
    }

    /** Apply each step to the ledger in turn, failing loud on a refusal. */
    private fun EntitlementLedger.steps(vararg step: (EntitlementLedger) -> Patch<EntitlementLedger>?): EntitlementLedger =
        step.fold(this) { l, s -> l.piece(checkNotNull(s(l)) { "rig: a ledger step refused" }) }

    /** [node] takes a grant of [amount] through `acme` and spends all of it at the lane. */
    private fun spend(node: HeddleNode, amount: Long) {
        val reserved = assertIs<ReserveOutcome.Reserved>(grantAndReserve(node, amount))
        node.complete(reserved.id, amount)
    }

    /** [node] takes a grant of [amount] through `acme` and reserves it at the lane, leaving the demand cleared. */
    private fun grantAndReserve(node: HeddleNode, amount: Long): ReserveOutcome {
        node.advertise(OLD, Demand(amount, amount))
        node.advertise(LANE_EDGE, Demand(amount, amount))
        check(node.schedule(ROOT) is ScheduleOutcome.Delegated) { "rig: root grants acme" }
        check(node.schedule(ACME) is ScheduleOutcome.Delegated) { "rig: acme grants the lane" }
        node.advertise(OLD, Demand.NONE)
        node.advertise(LANE_EDGE, Demand.NONE)
        return node.reserve(LANE, amount)
    }

    /**
     * A node with the cluster's second peer's identity, booted alone from [ledger]. A fresh fabric's
     * second seam carries the same identity, `peer-2`.
     */
    private suspend fun TestScope.bootSecondPeer(ledger: EntitlementLedger): HeddleNode {
        val loom = InMemoryLoom()
        loom.host(Pattern("reconcile-own-restart"))
        val seam = loom.join(InMemoryTag("peer-2"))
        return HeddleNode(
            scope = backgroundScope, seam = seam, self = ReplicaId(seam.selfId.value), initialLedger = ledger,
            clock = { Instant.fromEpochMilliseconds(testScheduler.currentTime) },
            config = heddleTestConfig(seed = 11), epoch = 1L,
        )
    }

    private fun TestScope.childScope(): CoroutineScope =
        CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))

    private companion object {
        val ROOT = GroupId("root")
        val ACME = GroupId("acme")
        val LANE = GroupId("lane")
        val OTHER = GroupId("other")
        val GROUPS = listOf(ROOT, ACME, LANE, OTHER)
        val OLD = AttachmentId("root/acme@1")
        val NEW = AttachmentId("root/acme@2")
        val LANE_EDGE = AttachmentId("acme/lane@1")
        val NEW_RECORD = AttachmentRecord(NEW, ROOT, ACME, Weight.of(2L))
        val UNDER_OTHER = AttachmentRecord(AttachmentId("other/acme@1"), OTHER, ACME, Weight.ONE)
        const val LATE_COST = 6L
        val TOPOLOGY = listOf(
            AttachmentRecord(OLD, ROOT, ACME, Weight.ONE),
            AttachmentRecord(LANE_EDGE, ACME, LANE, Weight.ONE),
            AttachmentRecord(AttachmentId("root/other@1"), ROOT, OTHER, Weight.ONE),
        )
    }
}
