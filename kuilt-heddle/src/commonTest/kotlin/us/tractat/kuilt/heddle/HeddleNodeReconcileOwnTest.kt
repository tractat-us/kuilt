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
 * outcome as the reweight's steps arrive, a restart between the repair and a late completion, and
 * the two refusals. The many-peer property is `HeddleNodeReconcileOwnPropertyTest`.
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
     * A restart forgets the quiesce marks, and the reservations with them (#3649's unverified point).
     * Peer 1 reserves across the old edge while cut off, the retire lands, and it repairs. It then
     * restarts from its ledger: the old reservation no longer exists, so completing it charges
     * nothing, and a fresh reservation captures the new edge. The retired edge never moves again.
     */
    @Test
    fun aRestartForgetsTheMarksAndTheReservationsThatNeededThem() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val scope = childScope()
            val h = staticCluster(ROOT, peers = 2, mint = mapOf(0 to 100L, 1 to 100L), topology = TOPOLOGY, nodeScope = scope)
            spend(h.peers[0].node, 5L)
            h.pump(1_000L)
            h.partition(1)
            val lateNode = h.peers[1].node
            val late = assertIs<ReserveOutcome.Reserved>(grantAndReserve(lateNode, 6L), "rig: a reservation across the old edge")
            val reweighter = h.peers[0].node
            check(reweighter.prepare(NEW_RECORD) && reweighter.close(OLD) && reweighter.retire(OLD) && reweighter.activate(NEW)) {
                "rig: peer 0 reweights acme without seeing peer 1's grant"
            }
            h.pump()
            h.heal(1)
            h.pump(1_000L)
            assertIs<ReconcileOwnOutcome.Repaired>(reweighter.reconcileOwn(ACME))
            val own = assertIs<ReconcileOwnOutcome.Repaired>(lateNode.reconcileOwn(ACME), "peer 1 repairs with its reservation open")
            h.pump(1_000L)
            val saved = lateNode.ledger.value
            val oldEdgeBefore = checkNotNull(saved.edge(OLD))
            scope.cancel()

            val restarted = rebootSecondPeer(saved, epoch = 1L)
            check(restarted.self == lateNode.self) { "rig: the restart keeps its identity, ${restarted.self} vs ${lateNode.self}" }
            restarted.complete(late.id, 6L)
            val afterTheLostCompletion = restarted.ledger.value
            val fresh = assertIs<ReserveOutcome.Reserved>(restarted.reserve(LANE, 6L), "the restarted peer re-reserves its holdings")
            restarted.complete(fresh.id, 6L)
            val l = restarted.ledger.value
            assertAll(
                { assertEquals(6L, own.credit, "rig: the open reservation's grant moved with the repair") },
                { assertEquals(0L, own.spent, "rig: nothing of it was spent before the repair") },
                { assertEquals(saved, afterTheLostCompletion, "the forgotten reservation charges nothing") },
                { assertEquals(ReconcileOwnOutcome.NothingToRepair, restarted.reconcileOwn(ACME), "the repair is in the ledger, not in the marks") },
                { assertEquals(oldEdgeBefore, l.edge(OLD), "the retired edge never moves again") },
                { assertEquals(11L, checkNotNull(l.edge(NEW)).spent, "both peers' spends ride the new edge") },
                { assertEquals(emptyList(), l.validate()) },
                { assertEquals(null, l.conservationShortfallOver(GROUPS, h.peers.map { it.id })) },
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
            var l = buildInitialLedger(ROOT, mapOf(donor to 100L, self to 0L), TOPOLOGY)
            fun step(patch: Patch<EntitlementLedger>?) {
                l = l.piece(checkNotNull(patch) { "rig: a ledger step refused" })
            }
            step(l.delegate(donor, OLD, 10L))
            step(l.transfer(ACME, donor, self, 10L))
            step(l.delegate(self, LANE_EDGE, 10L))
            step(l.spend(self, LANE, 10L))
            step(l.prepare(NEW_RECORD))
            step(l.close(OLD))
            step(l.retire(OLD))
            step(l.activate(NEW))
            val node = HeddleNode(
                scope = backgroundScope, seam = seam, self = self, initialLedger = l,
                clock = { Instant.fromEpochMilliseconds(testScheduler.currentTime) },
                config = heddleTestConfig(seed = 7), epoch = 0L,
            )
            val outcome = node.reconcileOwn(ACME)
            assertAll(
                { assertTrue((outcome as? ReconcileOwnOutcome.Refused)?.reason.orEmpty().contains("cover"), "names the uncovered spend: $outcome") },
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
            val under = AttachmentRecord(AttachmentId("other/acme@1"), OTHER, ACME, Weight.ONE)
            check(node.prepare(under) && node.close(OLD) && node.retire(OLD) && node.activate(under.id)) { "rig: acme moves under other" }
            val before = node.ledger.value
            val outcome = node.reconcileOwn(ACME)
            assertAll(
                { assertTrue((outcome as? ReconcileOwnOutcome.Refused)?.reason.orEmpty().contains("another parent"), "names the parents: $outcome") },
                { assertEquals(before, node.ledger.value, "nothing was written") },
            )
        }

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
     * The cluster's second peer booted again, alone, from [ledger], as a restart from a durable copy
     * would be. A fresh fabric's second seam carries the same identity, `peer-2`.
     */
    private suspend fun TestScope.rebootSecondPeer(ledger: EntitlementLedger, epoch: Long): HeddleNode {
        val loom = InMemoryLoom()
        loom.host(Pattern("reconcile-own-restart"))
        val seam = loom.join(InMemoryTag("peer-2"))
        return HeddleNode(
            scope = backgroundScope, seam = seam, self = ReplicaId(seam.selfId.value), initialLedger = ledger,
            clock = { Instant.fromEpochMilliseconds(testScheduler.currentTime) },
            config = heddleTestConfig(seed = 11), epoch = epoch,
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
        val TOPOLOGY = listOf(
            AttachmentRecord(OLD, ROOT, ACME, Weight.ONE),
            AttachmentRecord(LANE_EDGE, ACME, LANE, Weight.ONE),
            AttachmentRecord(AttachmentId("root/other@1"), ROOT, OTHER, Weight.ONE),
        )
    }
}
