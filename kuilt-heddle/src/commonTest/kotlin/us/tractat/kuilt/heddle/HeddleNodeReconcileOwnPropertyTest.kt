package us.tractat.kuilt.heddle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * #3649's decision gate: on a static cluster, each peer re-homing **only its own row** of an internal
 * reweight's strand ([HeddleNode.reconcileOwn]) joins cleanly with every other peer's, whatever the
 * order and across a partition.
 *
 * Three peers spend through `acme`, one of them reweights it (prepare the new root→acme edge, close
 * and retire the old one, activate the new one), and each then repairs. A seed draws each peer's
 * spend, who reweights, which peer is partitioned, whether the partition cuts it off before or after
 * the retire reaches it, the repair order, and the time between repairs. After the heal every peer
 * holds one ledger, `validate()` is empty on it, and every unit minted is held or spent across all
 * four groups.
 *
 * The property counts the shapes it is meant to reach and asserts the counts: a repair made while
 * partitioned, and a peer that had not yet seen the retire and so moved nothing until the heal.
 * Every draw is from a `List` this file built, so a seed walks the same trajectory on every target.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HeddleNodeReconcileOwnPropertyTest {

    @Test
    fun ownRowRepairsJoinCleanInAnyOrderAcrossAPartition() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val tally = Tally()
            for (seed in 0 until SEEDS) trial(Random(seed), seed, tally)
            println("RECONCILE-OWN-PROPERTY ${tally.trials} ${tally.repairedWhilePartitioned} ${tally.notYetSeen} ${tally.lateWhileCut} ${tally.lateBeforeRepair} ${tally.lateAfterRepair}")
            assertAll(
                { assertEquals(SEEDS, tally.trials, "every seed ran") },
                { assertEquals(SEEDS * PEERS, tally.repaired, "every peer repaired once in every trial") },
                { assertTrue(tally.repairedWhilePartitioned >= SEEDS / 4, "repairs made while partitioned: ${tally.repairedWhilePartitioned}") },
                { assertTrue(tally.notYetSeen >= SEEDS / 8, "repairs that had not seen the retire yet: ${tally.notYetSeen}") },
                { assertTrue(tally.lateWhileCut >= 3, "late work completed while cut off: ${tally.lateWhileCut}") },
                { assertTrue(tally.lateBeforeRepair >= 3, "late work completed after the heal, before its repair: ${tally.lateBeforeRepair}") },
                { assertTrue(tally.lateAfterRepair >= 3, "late work completed after its repair: ${tally.lateAfterRepair}") },
            )
        }

    /**
     * The control: the same reweight with one peer's repair skipped. Its own row stays stranded, so
     * `validate()` still names it, and only it, while the other two rows are clean.
     */
    @Test
    fun aPeerThatSkipsItsRepairKeepsItsNegativeHoldings() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val scope = childScope()
            val h = boot(scope)
            spendThroughAcme(h, listOf(listOf(4L, 6L), listOf(10L), listOf(3L)))
            reweight(h, by = 0)
            h.pump(1_000L)
            val skipped = h.peers[1].id
            for (i in listOf(0, 2)) assertIs<ReconcileOwnOutcome.Repaired>(h.peers[i].node.reconcileOwn(ACME))
            h.pump(1_000L)
            val ledgers = h.peers.map { it.node.ledger.value }
            scope.cancel()
            assertAll(
                { assertTrue(ledgers.all { it == ledgers[0] }, "the three ledgers converged") },
                {
                    assertEquals(
                        listOf(LedgerConflict.PersistentNegativeHoldings(ACME, skipped)),
                        ledgers[0].validate().filterIsInstance<LedgerConflict.PersistentNegativeHoldings>(),
                        "the skipped peer's row, and only it, is still negative",
                    )
                },
                { assertTrue(ledgers[0].holdings(ACME, skipped) < 0L, "skipped: ${ledgers[0].holdings(ACME, skipped)}") },
                { assertEquals(0L, ledgers[0].holdings(ACME, h.peers[0].id)) },
                { assertEquals(0L, ledgers[0].holdings(ACME, h.peers[2].id)) },
            )
        }

    private suspend fun TestScope.trial(r: Random, seed: Int, tally: Tally) {
        val scope = childScope()
        val h = boot(scope)
        val spends = List(PEERS) { List(1 + r.nextInt(3)) { 1L + r.nextInt(QUANTUM.toInt()) } }
        spendThroughAcme(h, spends)

        val reweighter = r.nextInt(PEERS)
        val cut = r.nextInt(PEERS)
        val cutBeforeTheRetire = r.nextBoolean()
        if (cutBeforeTheRetire) h.partition(cut)
        // Late work: the cut peer, not yet told of the retire, takes a fresh grant and reserves it,
        // and completes it while partitioned, after the heal but before its repair, or after it.
        val late = if (cutBeforeTheRetire && cut != reweighter && r.nextBoolean()) lateReservation(h.peers[cut].node, 1L + r.nextInt(QUANTUM.toInt())) else null
        val lateAt = r.nextInt(3)
        if (late != null && lateAt == LATE_WHILE_CUT) {
            h.peers[cut].node.complete(late.id, late.cost)
            tally.lateWhileCut++
        }
        reweight(h, by = reweighter)
        h.pump()
        if (!cutBeforeTheRetire) h.partition(cut)

        val outcomes = HashMap<Int, ReconcileOwnOutcome>()
        val waiting = ArrayList<Int>()
        for (i in (0 until PEERS).toList().shuffled(r)) {
            val outcome = h.peers[i].node.reconcileOwn(ACME)
            when (outcome) {
                is ReconcileOwnOutcome.Repaired -> {
                    outcomes[i] = outcome
                    if (i == cut) tally.repairedWhilePartitioned++
                }
                ReconcileOwnOutcome.NoRetiredEdge -> {
                    waiting += i
                    tally.notYetSeen++
                }
                else -> error("seed $seed: peer $i answered $outcome before the heal")
            }
            h.pump(r.nextLong(0L, 300L))
        }
        h.heal(cut)
        h.pump(1_000L)
        if (late != null && lateAt == LATE_BEFORE_REPAIR) {
            h.peers[cut].node.complete(late.id, late.cost)
            tally.lateBeforeRepair++
        }
        for (i in waiting) {
            outcomes[i] = assertIs<ReconcileOwnOutcome.Repaired>(h.peers[i].node.reconcileOwn(ACME), "seed $seed: peer $i after the heal")
        }
        h.pump(r.nextLong(0L, 300L))
        if (late != null && lateAt == LATE_AFTER_REPAIR) {
            // Its repair already read its finals, so this charge must re-home off the retired edge.
            h.peers[cut].node.complete(late.id, late.cost)
            tally.lateAfterRepair++
        }
        h.pump(1_000L)
        val again = h.peers.map { it.node.reconcileOwn(ACME) }
        h.pump()

        val replicas = h.peers.map { it.id }
        val ledgers = h.peers.map { it.node.ledger.value }
        scope.cancel()
        val where = "seed $seed (reweighter $reweighter, cut $cut before=$cutBeforeTheRetire, spends $spends, late ${late?.cost}@$lateAt)"
        assertAll(
            { assertEquals(PEERS, outcomes.size, "$where: every peer repaired") },
            { assertTrue(again.all { it == ReconcileOwnOutcome.NothingToRepair }, "$where: a second repair moves nothing: $again") },
            { assertTrue(ledgers.all { it == ledgers[0] }, "$where: the three ledgers converged") },
            {
                for ((i, l) in ledgers.withIndex()) {
                    assertEquals(emptyList(), l.validate(), "$where: peer $i validates clean")
                    assertEquals(null, l.conservationShortfallOver(GROUPS, replicas), "$where: conservation on peer $i")
                    for (rep in replicas) assertEquals(0L, l.holdings(ACME, rep), "$where: acme holds nothing for $rep on peer $i")
                }
            },
        )
        tally.repaired += outcomes.size
        tally.trials++
    }

    /** Each peer `i` spends every amount in `spends[i]` at the lane, through `acme`, and the cluster converges. */
    private fun spendThroughAcme(h: StaticCluster, spends: List<List<Long>>) {
        for ((i, amounts) in spends.withIndex()) {
            val node = h.peers[i].node
            for (amount in amounts) {
                val s = node.ledger.value
                node.advertise(OLD, Demand(checkNotNull(s.edge(OLD)).outstanding + amount, amount))
                node.advertise(LANE_EDGE, Demand(checkNotNull(s.edge(LANE_EDGE)).outstanding + amount, amount))
                assertIs<ScheduleOutcome.Delegated>(node.schedule(ROOT), "rig: root grants acme $amount")
                assertIs<ScheduleOutcome.Delegated>(node.schedule(ACME), "rig: acme grants the lane $amount")
                val held = node.ledger.value.holdings(LANE, node.self)
                val reserved = assertIs<ReserveOutcome.Reserved>(node.reserve(LANE, held), "rig: the lane holds $held")
                node.complete(reserved.id, held)
            }
            node.advertise(OLD, Demand.NONE)
            node.advertise(LANE_EDGE, Demand.NONE)
            h.pump()
        }
        h.pump(1_000L)
        val s = h.peers[0].node.ledger.value
        for (p in h.peers) check(p.node.ledger.value == s) { "rig: the spends converged" }
        check(checkNotNull(s.edge(OLD)).outstanding == 0L) { "rig: the old edge drained, ${s.edge(OLD)}" }
        for (p in h.peers) check(s.storedSlot(CounterFamily.ROLLUP_SPENT, OLD, p.id) > 0L) { "rig: ${p.id} spent through acme" }
    }

    private class Late(val id: ReservationId, val cost: Long)

    /** [node] takes a fresh grant of [amount] through `acme` and reserves it at the lane, without completing. */
    private fun lateReservation(node: HeddleNode, amount: Long): Late {
        val s = node.ledger.value
        node.advertise(OLD, Demand(checkNotNull(s.edge(OLD)).outstanding + amount, amount))
        node.advertise(LANE_EDGE, Demand(checkNotNull(s.edge(LANE_EDGE)).outstanding + amount, amount))
        assertIs<ScheduleOutcome.Delegated>(node.schedule(ROOT), "rig: late grant to acme")
        assertIs<ScheduleOutcome.Delegated>(node.schedule(ACME), "rig: late grant to the lane")
        node.advertise(OLD, Demand.NONE)
        node.advertise(LANE_EDGE, Demand.NONE)
        val held = node.ledger.value.holdings(LANE, node.self)
        val reserved = assertIs<ReserveOutcome.Reserved>(node.reserve(LANE, held), "rig: late reservation of $held")
        return Late(reserved.id, held)
    }

    /** The design §5.3 reweight of `acme`, on peer [by]: prepare the new edge, close and retire the old, activate the new. */
    private fun reweight(h: StaticCluster, by: Int) {
        val node = h.peers[by].node
        check(node.prepare(NEW_RECORD)) { "rig: prepare" }
        check(node.close(OLD)) { "rig: close" }
        check(node.retire(OLD)) { "rig: retire" }
        check(node.activate(NEW)) { "rig: activate" }
    }

    private suspend fun TestScope.boot(scope: CoroutineScope): StaticCluster =
        staticCluster(ROOT, PEERS, mint = (0 until PEERS).associateWith { 1_000L }, topology = TOPOLOGY, quantum = QUANTUM, nodeScope = scope)

    private fun TestScope.childScope(): CoroutineScope =
        CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))

    private class Tally {
        var trials = 0
        var repaired = 0
        var repairedWhilePartitioned = 0
        var notYetSeen = 0
        var lateWhileCut = 0
        var lateBeforeRepair = 0
        var lateAfterRepair = 0
    }

    private companion object {
        const val SEEDS = 96
        const val PEERS = 3
        const val QUANTUM = 10L
        const val LATE_WHILE_CUT = 0
        const val LATE_BEFORE_REPAIR = 1
        const val LATE_AFTER_REPAIR = 2
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
