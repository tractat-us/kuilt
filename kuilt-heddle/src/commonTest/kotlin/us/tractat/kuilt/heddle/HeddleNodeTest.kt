package us.tractat.kuilt.heddle

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.liveness.PartitionEvent
import us.tractat.kuilt.math.Int128
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
import kotlin.time.Duration.Companion.seconds

/**
 * H4 acceptance suite (design §15 Phase 4): multi-peer convergence over [InMemoryLoom]
 * under partition/heal/loss/dup/reorder (§10.7), deliver-N-times idempotence (§4.4),
 * stale-demand safety (§6), the §8.2 bound metrics, stranded crashed-peer earmarks,
 * the full §10.1 conservation identity **with** earmarks, and the §13 end-to-end scenario.
 *
 * Discipline (`.claude/rules/consensus.md`): a generous `TEST_WEDGE_BACKSTOP` wedge ceiling (never a tight
 * real-time cap, #1739), `StandardTestDispatcher`, node coroutines
 * on `backgroundScope`, seeded RNG, bounded `advanceTimeBy` only — never `advanceUntilIdle`
 * (the replicator/liveness timers re-arm forever).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HeddleNodeTest {

    private val root = GroupId("root")

    // ── fixed topology used across tests: root → {g1 w1 (leaf), g2 w1 (leaf)} ──────────
    private val g1 = GroupId("g1")
    private val g2 = GroupId("g2")
    private val e1 = AttachmentId("e1")
    private val e2 = AttachmentId("e2")
    private fun flatTopology(): List<AttachmentRecord> = listOf(
        AttachmentRecord(e1, root, g1, Weight.ONE),
        AttachmentRecord(e2, root, g2, Weight.ONE),
    )

    // A big demand so a demanding leaf keeps pulling entitlement.
    private val hungry = Demand(targetOutstanding = 1_000L, maximumUsefulGrant = 1_000L)

    // ─────────────────────────────────────────────────────────────────────────────
    // 1. Multi-peer convergence over InMemoryLoom (no partition).
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun twoPeersConvergeAfterIndependentScheduling() = runTest(
        StandardTestDispatcher(),
        timeout = TEST_WEDGE_BACKSTOP,
    ) {
        val h = harness(peers = 2, mint = mapOf(0 to 100L, 1 to 100L), topology = flatTopology())
        h.pump()

        // Both peers advertise appetite for both leaves and schedule from their root holdings.
        for (p in h.peers) {
            p.node.advertise(e1, hungry)
            p.node.advertise(e2, hungry)
        }
        h.pump()
        for (p in h.peers) p.node.schedule(root)
        h.pump(600)

        // Ledgers converge to one agreed value.
        assertEquals(h.peers[0].node.ledger.value, h.peers[1].node.ledger.value, "ledgers must converge")
        // All 200 minted units were delegated down (100 by each peer).
        val issued = h.peers[0].node.ledger.value.let { it.edge(e1)!!.issued + it.edge(e2)!!.issued }
        assertEquals(200L, issued, "every minted unit delegated once, converged")
    }

    @Test
    fun convergesUnderDuplicatedDelivery() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        // Every frame is delivered twice in and out on both peers — the lattice must absorb it (§10.7).
        val h = harness(
            peers = 2,
            mint = mapOf(0 to 100L, 1 to 100L),
            topology = flatTopology(),
            wrap = { DuplicatingSeam(it) },
        )
        h.pump()
        for (p in h.peers) {
            p.node.advertise(e1, hungry)
            p.node.advertise(e2, hungry)
        }
        h.pump()
        for (p in h.peers) p.node.schedule(root)
        h.pump(600)

        assertEquals(h.peers[0].node.ledger.value, h.peers[1].node.ledger.value, "converge despite duplication")
        // Duplication did not double-count: exactly the 200 minted units were delegated.
        val issued = h.peers[0].node.ledger.value.let { it.edge(e1)!!.issued + it.edge(e2)!!.issued }
        assertEquals(200L, issued, "duplicate delivery is idempotent — no phantom entitlement")
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 2. Deliver the completion N times → history rises once (§4.4).
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun completeIsIdempotentHistoryRisesOnce() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val h = harness(peers = 1, mint = mapOf(0 to 100L), topology = flatTopology())
        h.pump()
        val node = h.peers[0].node
        node.advertise(e1, hungry)
        node.schedule(root) // delegate root → g1/g2 so the peer holds spendable units at the leaves
        h.pump()

        val spentBefore = node.ledger.value.edge(e1)!!.spent
        val id = assertIs<ReserveOutcome.Reserved>(node.reserve(g1, 10L), "reservation must succeed against delegated holdings").id

        node.complete(id, 7L)
        node.complete(id, 7L) // duplicate delivery
        node.complete(id, 7L) // and again
        h.pump()

        val spentAfter = node.ledger.value.edge(e1)!!.spent
        assertEquals(spentBefore + 7L, spentAfter, "history rises exactly once despite N completions")
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 3. Stale/expired demand can misplace but never authorize a spend (§6).
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun expiredDemandStopsSchedulingButNeverAuthorizesSpend() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val h = harness(peers = 1, mint = mapOf(0 to 100L), topology = flatTopology())
            h.pump()
            val node = h.peers[0].node

            node.advertise(e1, hungry) // only g1 wants service
            node.schedule(root)
            h.pump()
            val issuedWhileLive = node.ledger.value.edge(e1)!!.issued
            assertTrue(issuedWhileLive > 0L, "live demand steers entitlement to g1")

            // Let demand age past the TTL without refreshing, then schedule again.
            advanceTimeBy(31.seconds.inWholeMilliseconds)
            runCurrent()
            val holdingsBefore = node.ledger.value.holdings(g1, node.self)
            node.schedule(root)
            h.pump()
            assertEquals(
                issuedWhileLive,
                node.ledger.value.edge(e1)!!.issued,
                "expired demand no longer schedules new delegation",
            )
            // Demand is advisory: it never moved spendable authority — holdings unchanged by expiry.
            assertEquals(holdingsBefore, node.ledger.value.holdings(g1, node.self))
        }

    // ─────────────────────────────────────────────────────────────────────────────
    // 4. Full §10.1 conservation identity WITH a reservation in flight, across a merge.
    //    On the CONVERGED ledger: Σ holdings(g,r) over ALL replicas + leafSpentTotal == minted.
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun globalConservationHoldsWithReservationInFlightAcrossMerge() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val minted = 200L
            val h = harness(peers = 2, mint = mapOf(0 to 100L, 1 to 100L), topology = flatTopology())
            h.pump()
            for (p in h.peers) {
                p.node.advertise(e1, hungry)
                p.node.advertise(e2, hungry)
            }
            h.pump()
            for (p in h.peers) p.node.schedule(root)
            h.pump(600)

            // A reservation is outstanding on peer0 while global conservation is checked.
            val id = assertIs<ReserveOutcome.Reserved>(h.peers[0].node.reserve(g1, 20L), "peer0 reserves against its delegated holdings").id
            assertGlobalConservation(h, minted)

            // Complete part of it, converge across the merge, re-check.
            h.peers[0].node.complete(id, 15L)
            h.pump(600)
            assertGlobalConservation(h, minted)
            assertEquals(0L, h.peers[0].node.earmarked(g1), "earmark released after completion")
        }

    private fun assertGlobalConservation(h: StaticCluster, minted: Long) {
        val ledger = h.peers[0].node.ledger.value
        assertEquals(ledger, h.peers[1].node.ledger.value, "ledgers converged for the conservation check")
        // The Σ-identity balances THROUGH a negative-holdings overspend (−50 cancels +50), so it
        // cannot detect that class alone; validate() catches a PersistentNegativeHoldings overspend.
        assertTrue(ledger.validate().isEmpty(), "no integrity conflict (overspend) on the converged ledger: ${ledger.validate()}")
        val groups = listOf(root, g1, g2)
        val replicas = h.peers.map { it.id }
        var sumHoldings = 0L
        for (g in groups) for (r in replicas) sumHoldings += ledger.holdings(g, r)
        assertEquals(
            minted,
            sumHoldings + ledger.leafSpentTotal(),
            "global §10.1: Σ holdings over ALL replicas + Σ spent == minted, reservation in flight",
        )
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 4b. complete() must charge the CAPTURED path even when the leaf is quarantined or
    //     gains a child concurrently — never swallow, never throw (§4.4 / §10.4).
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun completeChargesCapturedPathWhenLeafQuarantinedConcurrently() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val h = harness(peers = 1, mint = mapOf(0 to 100L), topology = flatTopology())
            h.pump()
            val node = h.peers[0].node
            node.advertise(e1, hungry)
            node.schedule(root)
            h.pump()
            val id = assertIs<ReserveOutcome.Reserved>(node.reserve(g1, 10L)).id

            // A second inbound generation into g1 → DualActiveInbound quarantines g1's lineage.
            val e1b = AttachmentRecord(AttachmentId("e1b"), root, g1, Weight.ONE)
            assertTrue(node.prepare(e1b) && node.activate(e1b.id))
            h.pump()
            assertEquals(0L, node.ledger.value.holdings(g1, node.self), "g1 quarantined: holdings collapse")

            // Work finished — complete must still charge the captured historical path e1.
            node.complete(id, 7L)
            h.pump()
            assertEquals(7L, node.ledger.value.edge(e1)!!.spent, "captured-path charge lands despite quarantine")
            assertEquals(0L, node.earmarked(g1), "earmark released exactly once")
        }

    @Test
    fun completeChargesWhenLeafGainsChildConcurrently() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val h = harness(peers = 1, mint = mapOf(0 to 100L), topology = flatTopology())
            h.pump()
            val node = h.peers[0].node
            node.advertise(e1, hungry)
            node.schedule(root)
            h.pump()
            val id = assertIs<ReserveOutcome.Reserved>(node.reserve(g1, 10L)).id

            // g1 gains an ACTIVE child concurrently → isLeaf(g1) becomes false. A prepared child
            // alone would not do it (#3191), and the rig would then prove nothing.
            val child = AttachmentRecord(AttachmentId("g1-child"), g1, GroupId("gc"), Weight.ONE)
            assertTrue(node.prepare(child) && node.activate(child.id))
            h.pump()
            assertFalse(node.ledger.value.isLeaf(g1), "rig: g1 is no longer a leaf")

            // complete must NOT throw; it charges the captured path.
            node.complete(id, 6L)
            h.pump()
            assertEquals(6L, node.ledger.value.edge(e1)!!.spent, "charge lands though the leaf gained a child")
        }

    @Test
    fun reserveRejectsNonLeafGroup() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val h = harness(peers = 1, mint = mapOf(0 to 100L), topology = flatTopology())
        h.pump()
        // root has children → not a leaf → reserve must refuse (else every complete throws).
        assertEquals(ReserveOutcome.NoHoldings, h.peers[0].node.reserve(root, 10L), "reserve refuses a non-leaf group")
    }

    /**
     * #3198: the static node inherits the ledger's retire of an unused PREPARED edge. A replacement
     * for g1 that nothing crossed retires without a close, so g1 keeps one live inbound edge on every
     * peer, and the retire replicates.
     */
    @Test
    fun aStaticNodeRetiresAnUnusedPreparedEdgeWithoutClosingIt() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val h = harness(peers = 2, mint = mapOf(0 to 100L), topology = flatTopology())
        h.pump()
        val p = AttachmentId("p")
        val node = h.peers[0].node
        assertTrue(node.prepare(AttachmentRecord(p, root, g1, Weight.of(2))), "rig: p prepares")
        h.pump()
        val before = h.peers[1].node.ledger.value
        val retired = node.retire(p)
        h.pump()
        val other = h.peers[1].node.ledger.value
        assertAll(
            { assertEquals(Lifecycle.PREPARED, before.lifecycle(p), "rig: the other peer saw p PREPARED") },
            { assertTrue(retired, "an unused prepared edge retires") },
            { assertEquals(Lifecycle.RETIRED, other.lifecycle(p), "the retire replicated") },
            { assertEquals(listOf(e1), other.liveInboundEdges(g1), "g1 keeps one live inbound edge") },
            { assertEquals(emptyList(), other.validate()) },
        )
    }

    /**
     * #3191: a group whose only child closes is a lane at once, and stays one through the drain and
     * after the retire, so it can be reserved and charged throughout. The reserve gate and the
     * allocator read one predicate, so at every step of the reshape exactly one of them can use
     * g1's holdings: `schedule(g1)` hands them down, or `reserve(g1)` earmarks them.
     */
    @Test
    fun aGroupWhoseChildClosesAndRetiresIsReservableAndTheAllocatorAgrees() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val ex = AttachmentId("ex")
            val h = harness(
                peers = 1,
                mint = mapOf(0 to 100L),
                topology = flatTopology() + AttachmentRecord(ex, g1, GroupId("x"), Weight.ONE),
            )
            h.pump()
            val node = h.peers[0].node
            node.advertise(e1, hungry)
            node.schedule(root)
            h.pump()
            assertTrue(node.ledger.value.holdings(g1, node.self) > 0L, "rig: g1 holds entitlement")

            // ex is active: g1 is a group. The allocator serves ex, and reserve refuses g1.
            assertEquals(ReserveOutcome.NoHoldings, node.reserve(g1, 10L), "a group is not reservable")
            node.advertise(ex, Demand(targetOutstanding = 5L, maximumUsefulGrant = 5L))
            h.pump()
            assertIs<ScheduleOutcome.Delegated>(node.schedule(g1), "the allocator hands a group's holdings down")
            h.pump()

            // The reload turns g1 into a lane: ex closes. Nothing more goes down it.
            assertTrue(node.close(ex), "rig: ex closes")
            h.pump()
            assertEquals(ScheduleOutcome.NothingToDelegate, node.schedule(g1), "the allocator hands nothing down a closing edge")
            val reserved = assertIs<ReserveOutcome.Reserved>(node.reserve(g1, 10L), "g1 is a lane once ex closes")
            node.complete(reserved.id, 6L)
            h.pump()
            val draining = node.ledger.value
            assertAll(
                { assertEquals(Lifecycle.CLOSING, draining.lifecycle(ex), "rig: ex is still draining") },
                { assertEquals(5L, draining.edge(ex)?.outstanding, "rig: ex still holds its grant") },
                { assertEquals(6L, draining.edge(e1)?.spent, "the charge landed on g1's inbound edge") },
                { assertEquals(emptyList(), draining.validate(), "validate is clean mid-drain") },
            )

            // x spends its whole grant, which drains ex, and ex retires.
            val atX = assertIs<ReserveOutcome.Reserved>(node.reserve(GroupId("x"), 5L), "x still spends across a closing edge")
            node.complete(atX.id, 5L)
            h.pump()
            assertTrue(node.retire(ex), "rig: ex drained and retires")
            h.pump()
            assertEquals(Lifecycle.RETIRED, node.ledger.value.lifecycle(ex), "rig: ex is retired")

            // g1 is still a lane: the allocator hands nothing down, and reserve takes g1.
            assertEquals(ScheduleOutcome.NothingToDelegate, node.schedule(g1), "the allocator hands nothing down a retired edge")
            val again = assertIs<ReserveOutcome.Reserved>(node.reserve(g1, 10L), "g1 is a lane once ex retires")
            node.complete(again.id, 4L)
            h.pump()
            val retired = node.ledger.value
            assertAll(
                // g1's 6 and 4 as leaf spend, plus x's 5 rolled up through e1.
                { assertEquals(15L, retired.edge(e1)?.spent, "every charge reached g1's inbound edge") },
                { assertEquals(10L, retired.storedSlot(CounterFamily.LEAF_SPENT, e1, node.self), "g1's own charges") },
                { assertEquals(0L, retired.edge(ex)?.outstanding, "ex drained") },
                { assertEquals(emptyList(), retired.validate(), "validate stays clean") },
            )
        }

    /** #3191: a reverted add leaves a PREPARED child nobody will activate; the lane stays reservable. */
    @Test
    fun aLaneWithAStrayPreparedChildIsStillReservable() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val h = harness(peers = 1, mint = mapOf(0 to 100L), topology = flatTopology())
            h.pump()
            val node = h.peers[0].node
            node.advertise(e1, hungry)
            node.schedule(root)
            h.pump()
            val stray = AttachmentRecord(AttachmentId("g1-stray"), g1, GroupId("gs"), Weight.ONE)
            assertTrue(node.prepare(stray), "rig: the stray child is prepared")
            h.pump()
            assertEquals(Lifecycle.PREPARED, node.ledger.value.lifecycle(stray.id), "rig: and never activated")

            assertEquals(ScheduleOutcome.NothingToDelegate, node.schedule(g1), "the allocator hands nothing down a prepared edge")
            val reserved = assertIs<ReserveOutcome.Reserved>(node.reserve(g1, 10L), "g1 is still a lane")
            node.complete(reserved.id, 4L)
            h.pump()
            assertAll(
                { assertEquals(4L, node.ledger.value.edge(e1)?.spent, "the charge landed") },
                { assertEquals(emptyList(), node.ledger.value.validate(), "validate stays clean") },
            )
        }

    // ─────────────────────────────────────────────────────────────────────────────
    // 4c. An invalid actualCost is rejected WITHOUT corrupting the earmark or losing the
    //     reservation (§4.4 — a validation failure must not corrupt state).
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun invalidActualCostDoesNotLeakEarmarkOrLoseReservation() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val h = harness(peers = 1, mint = mapOf(0 to 100L), topology = flatTopology())
            h.pump()
            val node = h.peers[0].node
            node.advertise(e1, hungry)
            node.schedule(root)
            h.pump()
            val id = assertIs<ReserveOutcome.Reserved>(node.reserve(g1, 10L)).id
            assertEquals(10L, node.earmarked(g1))

            // Over-max completion is rejected — earmark intact, reservation still present.
            assertFailsWith<IllegalArgumentException> { node.complete(id, 11L) }
            assertEquals(10L, node.earmarked(g1), "earmark not leaked by a rejected completion")

            // The reservation survived; a valid completion still charges.
            node.complete(id, 8L)
            h.pump()
            assertEquals(0L, node.earmarked(g1), "earmark released by the valid completion")
            assertEquals(8L, node.ledger.value.edge(e1)!!.spent, "the retried completion charges")
        }

    // ─────────────────────────────────────────────────────────────────────────────
    // 4b'. schedule() must not delegate away EARMARKED units — else a later complete()
    //      overspends (holdings go negative). The scheduling path must be earmark-aware.
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun scheduleNeverDelegatesEarmarkedUnits() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val minted = 100L
        val h = harness(peers = 1, mint = mapOf(0 to minted), topology = flatTopology())
        h.pump()
        val node = h.peers[0].node

        // 1. Delegate the whole supply down e1 to leaf g1.
        node.advertise(e1, hungry)
        node.schedule(root)
        h.pump()
        assertTrue(node.ledger.value.holdings(g1, node.self) >= 50L, "g1 holds the delegated units")

        // 2. Reserve 50 at g1 (captured path [e1]).
        val id = assertIs<ReserveOutcome.Reserved>(node.reserve(g1, 50L)).id

        // 3. g1 gains an ACTIVE child e3 — the exact reshape captured-path charging supports.
        val g3 = GroupId("g3")
        val e3 = AttachmentRecord(AttachmentId("e3"), g1, g3, Weight.ONE)
        assertTrue(node.prepare(e3) && node.activate(e3.id))
        h.pump()

        // 4. Schedule g1's new child — must leave the earmarked 50 unspendable.
        node.advertise(e3.id, hungry)
        node.schedule(g1)
        h.pump()

        // 5. Complete the reservation for the full 50.
        node.complete(id, 50L)
        h.pump()

        val ledger = node.ledger.value
        assertTrue(ledger.holdings(g1, node.self) >= 0L, "no negative holdings at g1: ${ledger.holdings(g1, node.self)}")
        assertTrue(ledger.validate().isEmpty(), "no overspend conflict: ${ledger.validate()}")
        var sum = 0L
        for (g in listOf(root, g1, g3)) sum += ledger.holdings(g, node.self)
        assertEquals(minted, sum + ledger.leafSpentTotal(), "extractable == minted (no phantom 50 units)")
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 4d. §8.2 bound is consistent under ASYMMETRIC demand — a fair scheduler serving only
    //     the demanding child is not a fairness error.
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun boundMetricsConsistentUnderAsymmetricDemand() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val h = harness(peers = 1, mint = mapOf(0 to 100L), topology = flatTopology())
            h.pump()
            val node = h.peers[0].node
            // Only g1 wants service; g2 is idle. Serving only g1 is fair, not an error.
            node.advertise(e1, hungry)
            node.schedule(root)
            h.pump()
            val m = node.boundMetrics(root)
            assertTrue(m.isConsistent, "asymmetric demand must not read as a bound violation: $m")
            assertEquals(0L, m.observedDeviation, "no demanding-sibling imbalance → zero observed deviation")
        }

    // ─────────────────────────────────────────────────────────────────────────────
    // 5. Partition, schedule both sides, heal, converge; bound metrics stay consistent (§8.2, §10.7).
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun partitionedPeersConvergeOnHealWithinBound() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val h = harness(peers = 2, mint = mapOf(0 to 100L, 1 to 100L), topology = flatTopology())
            h.pump()
            for (p in h.peers) {
                p.node.advertise(e1, hungry)
                p.node.advertise(e2, hungry)
            }
            h.pump()

            // Partition both peers, then schedule divergently on each side.
            h.partition(0)
            h.partition(1)
            h.peers[0].node.schedule(root)
            h.peers[1].node.schedule(root)
            h.pump(300)

            // The partition is real: each side saw only its own delegations, so the ledgers diverged.
            assertTrue(
                h.peers[0].node.ledger.value != h.peers[1].node.ledger.value,
                "partitioned peers must diverge before heal",
            )

            // While partitioned the two ledgers may differ, but each side stays within its bound.
            for (p in h.peers) {
                val m = p.node.boundMetrics(root)
                assertTrue(m.isConsistent, "bound pieces ordered: $m")
                assertTrue(
                    m.observedDeviation <= m.currentBound,
                    "observed deviation ${m.observedDeviation} within current bound ${m.currentBound}",
                )
            }

            // Heal and let anti-entropy reconcile.
            h.heal(0)
            h.heal(1)
            h.pump(800)
            assertEquals(
                h.peers[0].node.ledger.value,
                h.peers[1].node.ledger.value,
                "post-heal convergence to one agreed ledger",
            )
        }

    /**
     * Two peers that saw different departures still converge on one ledger (#3376). What a peer
     * remembers about a child that stopped asking and came back is scheduler-local, beside its wake
     * offsets, so it can reorder that peer's grants and never reaches the ledger.
     *
     * Peer 0 runs bursts alone while partitioned, so it sees `e2` stop asking and ask again. Peer 1,
     * on its own side, sees both children asking throughout. The witness that the sightings really
     * differed is that the two allocators' memories differ after the heal, while the ledgers agree.
     */
    @Test
    fun peersThatSawDifferentDeparturesConvergeOnHeal() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val h = harness(peers = 2, mint = mapOf(0 to 100L, 1 to 100L), topology = flatTopology())
            h.pump()
            h.partition(0)
            h.partition(1)
            val (zero, one) = h.peers.map { it.node }
            fun round(node: HeddleNode, e1Target: Long, e2Target: Long) {
                node.advertise(e1, Demand(targetOutstanding = e1Target, maximumUsefulGrant = 10L))
                node.advertise(e2, if (e2Target == 0L) Demand.NONE else Demand(targetOutstanding = e2Target, maximumUsefulGrant = 10L))
                h.pump(50)
                node.schedule(root)
                h.pump(50)
            }
            // Peer 0: both ask, then e2 stops while e1 runs on, then both ask for everything left.
            round(zero, e1Target = 20L, e2Target = 10L)
            round(zero, e1Target = 50L, e2Target = 0L)
            round(zero, e1Target = 1_000L, e2Target = 1_000L)
            // Peer 1: both ask throughout.
            round(one, e1Target = 1_000L, e2Target = 1_000L)
            val before = zero.ledger.value
            assertAll(
                { assertEquals(100L, before.edge(e1)!!.issued + before.edge(e2)!!.issued, "the rig: peer 0 delegated its 100") },
                { assertTrue(before.edge(e2)!!.issued > 10L, "the rig: e2 came back and was served on peer 0") },
                { assertEquals(100L, one.ledger.value.edge(e1)!!.issued + one.ledger.value.edge(e2)!!.issued, "peer 1 delegated its 100") },
            )

            h.heal(0)
            h.heal(1)
            h.pump(800)
            val merged = zero.ledger.value
            assertAll(
                { assertEquals(merged, one.ledger.value, "the ledgers converge") },
                { assertEquals(200L, merged.edge(e1)!!.issued + merged.edge(e2)!!.issued, "every minted unit delegated once") },
                { assertTrue(zero.allocatorMemoryForTest != one.allocatorMemoryForTest, "the rig: the two peers' sightings differ") },
            )
        }

    // ─────────────────────────────────────────────────────────────────────────────
    // 6. A crashed peer's earmarks stay stranded; no other peer's holdings change (§8.1).
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun crashedPeerEarmarksStrandedNoOtherHoldingsChange() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val h = harness(peers = 2, mint = mapOf(0 to 100L, 1 to 100L), topology = flatTopology())
            h.pump()
            val alive = h.peers[0].node
            val crashing = h.peers[1].node

            // The peer that will crash delegates to a leaf and reserves an earmark there.
            crashing.advertise(e1, hungry)
            crashing.schedule(root)
            h.pump(300)
            val earmark = assertIs<ReserveOutcome.Reserved>(crashing.reserve(g1, 10L), "crashing peer reserves before it goes silent").id

            // Record the alive peer's holdings and the crashing peer's ledger holdings, then crash.
            val aliveHoldingsBefore = h.peers.map { alive.ledger.value.holdings(root, it.id) }
            val crashedHoldingsBefore = alive.ledger.value.holdings(g1, crashing.self)
            h.partition(1) // isolate peer 1 permanently — a crash, no heal

            // The alive peer keeps working; time passes well past the reconnect window.
            alive.advertise(e2, hungry)
            alive.schedule(root)
            advanceTimeBy(2.seconds.inWholeMilliseconds)
            runCurrent()

            // No reclamation: the crashed peer's holdings/earmark are untouched in the alive ledger,
            // and no *other* peer's holdings were disturbed by the crash (only by the alive peer's own work).
            assertEquals(
                crashedHoldingsBefore,
                alive.ledger.value.holdings(g1, crashing.self),
                "crashed peer's leaf holdings stranded, never reclaimed",
            )
            assertEquals(
                aliveHoldingsBefore[1],
                alive.ledger.value.holdings(root, crashing.self),
                "crashed peer's root holdings unchanged by the crash",
            )
            assertTrue(crashing.earmarked(g1) > 0L, "the crashed peer's earmark is still held, stranded")
        }

    // ─────────────────────────────────────────────────────────────────────────────
    // 6b. The liveness surface fires: a silent peer surfaces on `partitionEvents` and in
    //     `unreachable`, and the §8.2 bound keeps counting it (never silently forgotten).
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun silentPeerSurfacesOnPartitionEventsAndUnreachable() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val h = harness(peers = 2, mint = mapOf(0 to 100L, 1 to 100L), topology = flatTopology())
            h.pump()
            val observer = h.peers[0].node
            val silent = h.peers[1].id

            // Record every liveness signal the observer emits, before anything goes wrong.
            val events = mutableListOf<PartitionEvent>()
            backgroundScope.launch { observer.partitionEvents.collect { events += it } }
            h.pump()

            // Peer 1 goes silent (a crash): its heartbeats stop reaching peer 0, but it stays in
            // the roster — so peer 0's detector must time it out and report it, never silently drop it.
            h.partition(1)

            // Past the 15 s unresponsive timeout: a PeerUnresponsive must surface for the silent peer.
            h.pump(20.seconds.inWholeMilliseconds)
            assertTrue(
                events.any { it is PartitionEvent.PeerUnresponsive && ReplicaId(it.peerId.value) == silent },
                "a silent peer must surface a PeerUnresponsive on partitionEvents, got $events",
            )
            // The durable `unreachable` set reflects it as soon as it is flagged.
            assertTrue(silent in observer.unreachable.value, "the silent peer is marked unreachable")

            // Past the further 60 s reconnect window: it escalates to PeerLost (still stranded, never reclaimed).
            h.pump(70.seconds.inWholeMilliseconds)
            assertTrue(
                events.any { it is PartitionEvent.PeerLost && ReplicaId(it.peerId.value) == silent },
                "past the reconnect window the silent peer must surface a PeerLost, got $events",
            )

            // The §8.2 bound still folds the unreachable peer into the roster — the metrics stay
            // consistent, they don't silently exclude a partitioned peer.
            assertTrue(
                silent in observer.unreachable.value,
                "the lost peer stays unreachable — reappearing on the seam alone never re-monitors it",
            )
            assertTrue(observer.boundMetrics(root).isConsistent, "boundMetrics stays consistent with a peer unreachable")
        }

    // ─────────────────────────────────────────────────────────────────────────────
    // 6c. #1652 — a lost-then-rejoined peer IS re-monitored, and enrollment is what does it.
    //     A PeerLost detector is terminal (channel closed, heartbeat loop returned), so without
    //     this the peer stays in `unreachable` forever and is never watched again.
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun enrollmentReMonitorsALostPeerAndSeamReappearanceAloneDoesNot() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val h = harness(peers = 2, mint = mapOf(0 to 100L, 1 to 100L), topology = flatTopology())
            h.pump()
            val observer = h.peers[0].node
            val rejoiner = h.peers[1].id

            val events = mutableListOf<PartitionEvent>()
            backgroundScope.launch { observer.partitionEvents.collect { events += it } }
            h.pump()

            // Drive peer 1 all the way to Lost, then heal its link.
            h.partition(1)
            h.pump(90.seconds.inWholeMilliseconds)
            assertTrue(
                events.any { it is PartitionEvent.PeerLost && ReplicaId(it.peerId.value) == rejoiner },
                "setup: the silent peer must reach PeerLost, got $events",
            )
            h.heal(1)
            h.pump(30.seconds.inWholeMilliseconds)
            assertTrue(
                rejoiner in observer.unreachable.value,
                "coming back on the seam is not membership — it must not clear `unreachable` on its own",
            )

            // The committed enrollment is the event that re-monitors it (here: the node-side effect
            // the control plane's ControlMembershipSink fires — see HeddleRosterTest for the wiring).
            observer.remonitorOnEnrollment(rejoiner)
            h.pump()
            assertFalse(rejoiner in observer.unreachable.value, "enrollment must clear the rejoined peer")

            // Prove the detector is genuinely live, not just the flag cleared: silence it again and a
            // SECOND unresponsive must surface. A terminal detector could never report anything.
            val before = events.count { it is PartitionEvent.PeerUnresponsive && ReplicaId(it.peerId.value) == rejoiner }
            h.partition(1)
            h.pump(30.seconds.inWholeMilliseconds)
            val after = events.count { it is PartitionEvent.PeerUnresponsive && ReplicaId(it.peerId.value) == rejoiner }
            assertAll(
                { assertTrue(after > before, "the re-attached detector must report again, got $events") },
                { assertTrue(rejoiner in observer.unreachable.value, "and flag it unreachable again") },
                { assertTrue(observer.boundMetrics(root).isConsistent, "boundMetrics stays consistent throughout") },
            )
        }

    // ─────────────────────────────────────────────────────────────────────────────
    // 7. The design §13 end-to-end scenario.
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun endToEndScenarioTwoTenantsDynamicSubtreePartitionHeal() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            // root → {tenant-a w1, tenant-b w1}
            val tenantA = GroupId("tenant-a")
            val tenantB = GroupId("tenant-b")
            val eA = AttachmentId("root->a")
            val eB = AttachmentId("root->b")
            val base = listOf(
                AttachmentRecord(eA, root, tenantA, Weight.ONE),
                AttachmentRecord(eB, root, tenantB, Weight.ONE),
            )
            val h = harness(peers = 2, mint = mapOf(0 to 120L, 1 to 120L), topology = base)
            h.pump()

            // Dynamically add tenant-a → {interactive w3, batch w1} via H2 prepare/activate.
            val interactive = GroupId("interactive")
            val batch = GroupId("batch")
            val eInt = AttachmentRecord(AttachmentId("a->interactive"), tenantA, interactive, Weight.of(3))
            val eBatch = AttachmentRecord(AttachmentId("a->batch"), tenantA, batch, Weight.ONE)
            for (p in h.peers) {
                assertTrue(p.node.prepare(eInt) && p.node.activate(eInt.id))
                assertTrue(p.node.prepare(eBatch) && p.node.activate(eBatch.id))
            }
            h.pump(300)

            // Everyone advertises appetite down the whole tree, then partition the two peers.
            for (p in h.peers) {
                p.node.advertise(eA, hungry)
                p.node.advertise(eB, hungry)
                p.node.advertise(eInt.id, hungry)
                p.node.advertise(eBatch.id, hungry)
            }
            h.pump(200)
            h.partition(0)
            h.partition(1)

            // Schedule both sides independently, all the way down.
            repeat(3) {
                for (p in h.peers) {
                    p.node.schedule(root)
                    p.node.schedule(tenantA)
                }
                h.pump(150)
            }

            // Heal and converge.
            h.heal(0)
            h.heal(1)
            h.pump(1000)
            val a = h.peers[0].node
            val b = h.peers[1].node
            assertEquals(a.ledger.value, b.ledger.value, "§13: converge after heal")
            assertTrue(a.ledger.value.validate().isEmpty(), "§13: no integrity conflicts on the converged ledger")

            // Multi-level scheduling really flowed entitlement down to the dynamically-added leaves.
            val converged = a.ledger.value
            assertTrue(converged.edge(eInt.id)!!.issued > 0L, "§13: interactive leaf received entitlement")
            assertTrue(converged.edge(eBatch.id)!!.issued > 0L, "§13: batch leaf received entitlement")

            // Report the measured bound at tenant-a: interactive:batch should trend toward 3:1.
            val metrics = a.boundMetrics(tenantA)
            assertTrue(metrics.isConsistent, "§13 bound metrics consistent: $metrics")
            assertTrue(
                metrics.observedDeviation <= metrics.currentBound,
                "§13 measured deviation ${metrics.observedDeviation} ≤ bound ${metrics.currentBound}",
            )
        }

    // ─────────────────────────────────────────────────────────────────────────────
    // 8. The §10.6 wake clamp, end to end through the node (#1695).
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * A child that stops asking while a sibling runs does **not** get a catch-up burst when it
     * wakes: the node detects the idle→demand edge, clamps the waker to the front of the set it
     * is rejoining, and it takes its fair half of what is left (design §7.2, §10.6).
     *
     * This is the production half of the invariant. [HeddlePolicy.wakeOffset] has always been
     * correct, but until #1695 nothing called it — every [PolicyEdge] was built with the default
     * `virtualOffset = ZERO`, so the shipped node let a waker bank the whole idle interval. The
     * numbers here are exact and the contrast is the point: with the clamp g2 alternates with g1
     * for 15 grants apiece; without it, g2 wins the first 20 outright (walking its virtual
     * service up from 0 to 200) and ends on 250 against g1's 250.
     */
    @Test
    fun wakingChildIsClampedToTheFrontInsteadOfBursting() = runTest(
        StandardTestDispatcher(),
        timeout = TEST_WEDGE_BACKSTOP,
    ) {
        val h = harness(peers = 1, mint = mapOf(0 to 500L), topology = flatTopology())
        h.pump()
        val node = h.peers[0].node

        // Phase 1 — g1 runs alone to a bounded appetite; g2 never asks.
        node.advertise(e1, Demand(targetOutstanding = 200L, maximumUsefulGrant = 10L))
        node.schedule(root)
        h.pump()
        assertAll(
            { assertEquals(200L, node.ledger.value.edge(e1)!!.issued, "g1 runs alone to its target") },
            { assertEquals(0L, node.ledger.value.edge(e2)!!.issued, "g2 never demanded") },
            // The front is where the scheduler is: the one competing child's virtual service.
            { assertEquals(Rational.of(200L), node.parentVirtualTime(root)) },
        )

        // Phase 2 — g2 wakes; both now want everything that is left (300 units, 30 quanta).
        val hungrier = Demand(targetOutstanding = 10_000L, maximumUsefulGrant = 10L)
        node.advertise(e1, hungrier)
        node.advertise(e2, hungrier)
        node.schedule(root)
        h.pump()

        val ledger = node.ledger.value
        assertAll(
            { assertEquals(150L, ledger.edge(e2)!!.issued, "the waker takes its fair half, not a burst") },
            { assertEquals(350L, ledger.edge(e1)!!.issued, "the incumbent keeps its half of what is left") },
        )
    }

    /**
     * **A newborn and a waker in the same round are one joiner set, measured against one front**
     * (issue #1752). Both read from further back than the competing set — the newborn from its own
     * origin, having no [Gauge] yet; the waker from the stale virtual service it slept on, this
     * round's clamp not yet computed. So each must be excluded from the front the *other* is
     * measured against, or each drags it down toward itself and both bank the difference.
     *
     * The scenario is the smallest one that separates the two designs. `e1` runs alone to `200`
     * while `e2` sleeps at `0`; then in a single [HeddleNode.schedule] call `e2` wakes and newborn
     * `e3` arrives. The only child that is neither joiner is `e1`, so the one true front is `200`,
     * and all three belong there.
     *
     * Seating and clamping in two passes over two fronts — which is what a `seatUnseated` followed
     * by a separate `refreshWakeClamps` does — gets **both** wrong, in the same direction:
     *
     * | | two passes | one joiner set |
     * |---|---|---|
     * | `e3` seat | `mean(e1 200, e2 0)` = **100** | 200 |
     * | `e2` clamp | `mean(e1 200, e3 100)` = **150** | 200 |
     *
     * `e3`'s share of that is permanent — a [Gauge] position the join only ratchets up — so the
     * assertion that matters is its seat. The behavioural tell is that both joiners then take
     * grants ahead of the incumbent instead of level with it.
     *
     * The joining round holds everyone **competing but unservable** (`maximumUsefulGrant = 0`,
     * this file's idiom from [aReturnerCountsWhereItLandsAndKeepsItsEarlierClamp]) so the seat can be read
     * back as the seat: [EntitlementLedger.delegate] writes its own checkpoint into the same gauge,
     * so a grant landing in the same round would leave the floor advanced past what was seated and
     * the assertion would be reading the wrong number.
     */
    @Test
    fun aNewbornAndAWakerInOneRoundAreSeatedOnTheSameFront() = runTest(
        StandardTestDispatcher(),
        timeout = TEST_WEDGE_BACKSTOP,
    ) {
        val h = harness(peers = 1, mint = mapOf(0 to 500L), topology = flatTopology())
        h.pump()
        val node = h.peers[0].node

        // Phase 1 — e1 runs alone to 200; e2 is observed idle, so its next demand is a wake.
        node.advertise(e1, Demand(targetOutstanding = 200L, maximumUsefulGrant = 10L))
        node.schedule(root)
        h.pump()
        assertEquals(Rational.of(200L), node.parentVirtualTime(root), "e1 alone sets the front at 200")

        // Phase 2 — in ONE round: e3 is born, and e2 wakes. Nothing is servable, so the round
        // seats and clamps without granting, and the gauge reads back as the seat itself.
        val newborn = AttachmentRecord(AttachmentId("e3"), root, GroupId("g3"), Weight.ONE)
        assertTrue(node.prepare(newborn) && node.activate(newborn.id))
        val competingUnservable = Demand(targetOutstanding = 10_000L, maximumUsefulGrant = 0L)
        node.advertise(e1, competingUnservable)
        node.advertise(e2, competingUnservable)
        node.advertise(newborn.id, competingUnservable)
        node.schedule(root)
        h.pump()
        assertNull(node.ledger.value.gauge(newborn.id), "a round with nothing to grant writes no seat (#3353)")

        // Phase 3 — all three now servable. e2 kept its clamp from phase 2, so it is no longer
        // waking, and the newborn, still unseated, is seated with the first grant at the front over
        // e1 and e2, both at 200. They split what is left (300 units, 30 quanta) three ways.
        val hungrier = Demand(targetOutstanding = 10_000L, maximumUsefulGrant = 10L)
        node.advertise(e1, hungrier)
        node.advertise(e2, hungrier)
        node.advertise(newborn.id, hungrier)
        node.schedule(root)
        h.pump()

        val ledger = node.ledger.value
        val issued = "e1=${ledger.edge(e1)!!.issued}, e2=${ledger.edge(e2)!!.issued}, " +
            "e3=${ledger.edge(newborn.id)!!.issued}"
        // Each checkpoint writes the gross position at the new issuance with that issuance as its
        // fold, so `ticks − folded · 16` is the seat the first grant landed with, in ticks.
        val gauge = assertNotNull(ledger.gauge(newborn.id))
        val seat = gauge.ticksLow - gauge.folded * 16L
        assertAll(
            { assertEquals(0L, gauge.ticksHigh, "the rig: a position this small has no high word") },
            {
                assertEquals(
                    200L * 16L,
                    seat,
                    "the newborn is seated at the front over the set it is joining, never one a waker pulled down",
                )
            },
            {
                assertEquals(
                    100L,
                    ledger.edge(newborn.id)!!.issued,
                    "seated level, the newborn takes a third of what is left — not a catch-up burst ($issued)",
                )
            },
            {
                assertEquals(
                    100L,
                    ledger.edge(e2)!!.issued,
                    "and so does the waker — clamped to the same front, not to one the newborn pulled down ($issued)",
                )
            },
            {
                assertEquals(
                    300L,
                    ledger.edge(e1)!!.issued,
                    "and the incumbent keeps its third rather than being overtaken by two joiners ($issued)",
                )
            },
        )
    }

    /**
     * The restart rule through a whole node (design §7.2): a seated edge this peer has never
     * observed, and finds demanding, is a waker, clamped to the front like any other. A first
     * sighting carries no evidence of how long the child idled, so leaving it its raw position
     * would let it keep, as credit, a gap that built up under another peer's scheduling or before a
     * restart. It loses its lag instead, once per peer that first sees it: the bounded side
     * (§10.6, #3472).
     *
     * **Two peers are load-bearing here, not scenery** (issue #1752). Since the seat moved into
     * the replicated [Gauge], a *locally* first-sighted edge is seated by this peer's own bump at
     * the front it is joining, so it can never be behind — the gap under test is only reachable
     * when the seat was written **elsewhere**, at a time when the front was lower. So peer B seats
     * `e3` at the origin and then runs `e1` to 100 and `e2` to 60 on its own holdings; peer A
     * merges that and meets all three for the first time, `e3` already 100 behind. On A, `e1` and
     * `e3` are both first seen demanding, so both are joiners; with no other child demanding, the
     * front is the furthest-ahead child, `e1` at 100, and `e3` is clamped level with it. A's 100
     * units then split between them, where an unclamped `e3` would have taken all of them. `e2`
     * at 60 keeps the second-furthest-ahead child in a different place, so a front taken one off
     * the top would clamp `e3` to 60 and give it 70.
     *
     * Demand is replicated here, so B's advertisement of `e2` is live on A. `targetOutstanding = 60`
     * lets B fill it exactly, so on A it is active but not competing, and the front A reads before
     * its call is the two competing edges' mean (the rig). The core twin is
     * `FairShareAllocatorTest.aFirstObservationOfASeatedDemandingChildIsAWake`.
     */
    @Test
    fun aFirstObservationOfASeatedDemandingEdgeIsAWake() = runTest(
        StandardTestDispatcher(),
        timeout = TEST_WEDGE_BACKSTOP,
    ) {
        val h = harness(peers = 2, mint = mapOf(0 to 100L, 1 to 160L), topology = flatTopology())
        h.pump()
        val a = h.peers[0].node
        val b = h.peers[1].node
        val hungrier = Demand(targetOutstanding = 10_000L, maximumUsefulGrant = 10L)

        // B introduces e3 and schedules: the bump seats e1, e2 and e3 together at the origin (no
        // front yet), and B then spends its own 160 on e1 and e2, 100 and 60. e2's target is exactly
        // the 60 it gets, so it stops competing. A never sees this round.
        val behind = AttachmentRecord(AttachmentId("e3"), root, GroupId("g3"), Weight.ONE)
        assertTrue(b.prepare(behind) && b.activate(behind.id))
        b.advertise(e1, hungrier)
        b.advertise(e2, Demand(targetOutstanding = 60L, maximumUsefulGrant = 10L))
        b.schedule(root)
        h.pump()
        assertAll(
            { assertEquals(100L, a.ledger.value.edge(e1)!!.issued, "A merged e1's run") },
            {
                assertEquals(
                    60L,
                    a.ledger.value.edge(e2)!!.issued,
                    "…and e2's, filled at 60: the rig, a second-furthest-ahead child below e1",
                )
            },
            {
                assertEquals(
                    Gauge(folded = 0L),
                    assertNotNull(a.ledger.value.gauge(behind.id)),
                    "…and merged e3's seat at the origin, written when B's front was still there",
                )
            },
        )

        // A's first sight of e3, 100 behind. Unclamped, every one of A's 100 units would go to it.
        a.advertise(e1, hungrier)
        a.advertise(behind.id, hungrier)
        // The rig's other half: e2 is filled, so on A only e1 (100) and e3 (0) compete, and before
        // A's call, with no wake memory, they read 100 apart.
        assertEquals(
            Rational.of(50L),
            a.parentVirtualTime(root),
            "only e1 and e3 compete on A — e2 is active but filled",
        )
        a.schedule(root)
        h.pump()

        val ledger = a.ledger.value
        assertAll(
            {
                assertEquals(
                    50L,
                    ledger.edge(behind.id)!!.issued,
                    "e3, first seen demanding, is clamped level with e1 and takes half of A's units",
                )
            },
            {
                assertEquals(
                    150L,
                    ledger.edge(e1)!!.issued,
                    "…and e1 takes the other half",
                )
            },
        )
    }

    /**
     * A returner counts in the front a waker is clamped to at where it lands (#3446), and a second
     * wake keeps the first one's clamp (issue #1714), through a whole node.
     *
     * The scenario, exactly: e1 runs alone to `200` and is seen leaving at the grant that filled
     * it. e2 wakes beside e1 returning and e3 parked at `0`, so its front is `mean(200, 0) = 100`
     * and it carries a `+100` clamp; it then runs `20` of its own, reaching an effective virtual
     * service of `120`, and sleeps again, seen leaving. Its re-wake is a return by its parent's
     * clock, which nobody moved, so its shift is zero and it stays at `120`. Left out of e2's first
     * front, e1 would leave it clamped to e3 alone, at `0`; a return that replaced the offset
     * rather than adding to it would drop e2 to `20`. Before #3376 the re-wake was a second clamp,
     * into a front of e3 alone, and a `max` join with the stored offset held it at `120`; that join
     * is gone, since a clamped child's next wake is always a return (#3416 review).
     *
     * e1 and e3 are held **competing but unservable** via `maximumUsefulGrant = 0`, which trims
     * every quantum to nothing while leaving [HeddlePolicy.isDemanding] true — that is exactly the
     * starved sibling of #1714, and it is what pins the front where the test needs it without
     * letting a grant move anyone's raw virtual service.
     */
    @Test
    fun aReturnerCountsWhereItLandsAndKeepsItsEarlierClamp() = runTest(
        StandardTestDispatcher(),
        timeout = TEST_WEDGE_BACKSTOP,
    ) {
        // 220 = e1's 200 plus e2's 20; holdings are exhausted from phase 2 on, so no later grant
        // can move a raw virtual service and mask the clamp.
        val h = harness(peers = 1, mint = mapOf(0 to 220L), topology = flatTopology())
        h.pump()
        val node = h.peers[0].node

        // A third child that competes from its first observation, unseated, so it is seated as a
        // newborn and never carries a clamp, but can never absorb a grant, so it stays parked at 0.
        val starved = AttachmentRecord(AttachmentId("e3"), root, GroupId("g3"), Weight.ONE)
        assertTrue(node.prepare(starved) && node.activate(starved.id))
        val competingUnservable = Demand(targetOutstanding = 10_000L, maximumUsefulGrant = 0L)

        // Phase 1 — e1 runs alone to 200. e2 is observed idle; e3 competes but is never served.
        node.advertise(e1, Demand(targetOutstanding = 200L, maximumUsefulGrant = 10L))
        node.advertise(starved.id, competingUnservable)
        node.schedule(root)
        h.pump()
        assertEquals(200L, node.ledger.value.edge(e1)!!.issued, "e1 runs alone to its target")

        // Phase 2 — e2 wakes into a front of mean(e1 landed at 200, e3 at 0) = 100, then runs its 20.
        node.advertise(e1, competingUnservable)
        node.advertise(e2, Demand(targetOutstanding = 20L, maximumUsefulGrant = 10L))
        node.advertise(starved.id, competingUnservable)
        node.schedule(root)
        h.pump()
        assertAll(
            { assertEquals(20L, node.ledger.value.edge(e2)!!.issued, "the waker takes the last 20") },
            {
                assertEquals(
                    Rational.of(120L),
                    node.probeEffectiveVirtualService(e2, others = listOf(e1, starved.id)),
                    "clamped forward by 100, then ran 20",
                )
            },
        )

        // Phase 3 — e2 sleeps again. Nothing is servable, so nobody's virtual service moves.
        node.advertise(e1, competingUnservable)
        node.advertise(e2, Demand.NONE)
        node.advertise(starved.id, competingUnservable)
        node.schedule(root)
        h.pump()

        // Phase 4 — e1 stops competing too, and e2 returns by a clock nobody moved, carrying its
        // clamp of 100.
        node.advertise(e1, Demand.NONE)
        node.advertise(e2, Demand(targetOutstanding = 10_000L, maximumUsefulGrant = 10L))
        node.advertise(starved.id, competingUnservable)
        node.schedule(root)
        h.pump()

        assertEquals(
            Rational.of(120L),
            node.probeEffectiveVirtualService(e2, others = listOf(e1, starved.id)),
            "a return adds its shift to the clamp e2 carries (replacing it gives 20)",
        )
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 9. A parent whose children cannot be ordered says why (#3353).
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Put `e1` at its ceiling under [root] (#3353). One call in which the node sees both children
     * asking and grants them its supply; then a merged write that puts `e1` at its ceiling and mints
     * [topUp] more at the root, so the next pick has something to grant. `e2`, demanding and seen
     * demanding, is far behind. Nothing rebases (#3472), so `e1` stays there.
     */
    private suspend fun TestScope.holdAtTheCeiling(h: StaticCluster, topUp: Long = 100L) {
        val peer = h.peers[0]
        val node = peer.node
        node.advertise(e1, hungry)
        node.advertise(e2, hungry)
        check(node.schedule(root) is ScheduleOutcome.Delegated) { "the rig: the node granted, and saw both asking" }
        h.pump()
        val seen = node.ledger.value
        val e1Ticks = Int128.of(CEILING_AT_WEIGHT_ONE)
        val e1Issued = checkNotNull(seen.edge(e1)) { "the rig: e1 is active" }.issued
        node.asControlSink().publish(
            Patch(
                EntitlementLedger.of(
                    gauges = mapOf(e1 to Gauge(folded = e1Issued, ticksHigh = e1Ticks.high, ticksLow = e1Ticks.low)),
                    minted = mapOf(MintId("top-up") to MintRecord(root, peer.id, topUp)),
                ),
            ),
        )
        h.pump()
        val held = node.ledger.value
        check(held.virtualService(e1) == Rational.of(CEILING_AT_WEIGHT_ONE, 16L)) { "the rig: e1 sits at its ceiling" }
        check(checkNotNull(held.virtualService(e2)) < Rational.of(CEILING_AT_WEIGHT_ONE, 32L)) { "the rig: e2 is far behind" }
    }

    /** What the node has delegated down `e1` and `e2` together. */
    private fun HeddleNode.issuedToBoth(): Long =
        listOf(e1, e2).sumOf { checkNotNull(ledger.value.edge(it)) { "${it.value} is active" }.issued }

    /**
     * A child whose position reached its ceiling no longer stops the picks at its parent: a pick
     * orders the full 128-bit position, so the node delegates as it would anywhere. It used to answer
     * [ScheduleOutcome.PositionCeiling] and delegate nothing. The ceiling here arrives the way any
     * gauge does, merged into the replicated ledger ([holdAtTheCeiling]).
     */
    @Test
    fun aParentWithAChildAtItsCeilingDelegates() = runTest(
        StandardTestDispatcher(),
        timeout = TEST_WEDGE_BACKSTOP,
    ) {
        val h = harness(peers = 1, mint = mapOf(0 to 100L), topology = flatTopology())
        h.pump()
        val node = h.peers[0].node
        holdAtTheCeiling(h)
        val issuedBefore = node.issuedToBoth()
        val outcome = node.schedule(root)
        h.pump()
        assertAll(
            { assertIs<ScheduleOutcome.Delegated>(outcome, "the node granted: $outcome") },
            { assertTrue(node.issuedToBoth() > issuedBefore, "and delegated down the parent's children") },
        )
    }

    /**
     * A child that a merge carried to its ceiling is no reason to refuse (#3353, #3472). `e1` sits at
     * its ceiling and `e2`, demanding, at `2R`. The pick over `e1` used to refuse, so the parent
     * refused for good; then a rebase before the pick repaired it. Now a pick orders the wide
     * position, so both calls simply grant.
     */
    @Test
    fun aChildAtItsCeilingIsNoReasonToRefuse() = runTest(
        StandardTestDispatcher(),
        timeout = TEST_WEDGE_BACKSTOP,
    ) {
        val h = harness(peers = 1, mint = mapOf(0 to 100L), topology = flatTopology())
        h.pump()
        val node = h.peers[0].node
        val atCeiling = Gauge(folded = 0L, ticksLow = CEILING_AT_WEIGHT_ONE)
        val twoR = CEILING_AT_WEIGHT_ONE / 16L * 2L
        node.asControlSink().publish(Patch(EntitlementLedger.of(gauges = mapOf(e1 to atCeiling, e2 to Gauge(folded = 0L, ticksLow = twoR)))))
        node.advertise(e1, hungry)
        node.advertise(e2, hungry)
        h.pump()
        val gauge = node.ledger.value.gauge(e1)
        val first = node.schedule(root)
        val second = node.schedule(root)
        h.pump()
        assertAll(
            { assertEquals(atCeiling, gauge, "the rig: e1 sits at its ceiling") },
            { assertTrue(first is ScheduleOutcome.Delegated, "the first call grants: $first") },
            { assertTrue(second !is ScheduleOutcome.LifetimeExhausted, "and so does the next: $second") },
        )
    }

    /**
     * The cookbook's reading of [ScheduleOutcome], run: idle, then a grant, then a grant again beside
     * a child at its ceiling, which used to read as stuck.
     */
    @Test
    fun theScheduleOutcomesSampleReadsIdleDelegatedAndDelegatedPastTheCeiling() = runTest(
        StandardTestDispatcher(),
        timeout = TEST_WEDGE_BACKSTOP,
    ) {
        val h = harness(peers = 1, mint = mapOf(0 to 100L), topology = flatTopology())
        h.pump()
        val node = h.peers[0].node
        val idle = node.sampleScheduleOutcomes(root)
        node.advertise(e2, Demand(targetOutstanding = 10L, maximumUsefulGrant = 10L))
        val delegated = node.sampleScheduleOutcomes(root)
        holdAtTheCeiling(h)
        val past = node.sampleScheduleOutcomes(root)
        assertAll(
            { assertEquals("idle", idle) },
            { assertEquals("delegated 1", delegated) },
            { assertTrue(past.startsWith("delegated "), "beside a child at its ceiling it still delegates: $past") },
        )
    }

    /**
     * A schedule beside a child at its ceiling seats its newborn and grants, in one patch (#3353).
     * It used to be refused there and leave the newborn unseated; a refused schedule writing
     * nothing is now only the weight budget's, which
     * [anOverBudgetSetMergedOnHealAnswersWeightBudgetAndWritesNothing] pins.
     */
    @Test
    fun aScheduleBesideAChildAtItsCeilingSeatsItsNewborn() = runTest(
        StandardTestDispatcher(),
        timeout = TEST_WEDGE_BACKSTOP,
    ) {
        val h = harness(peers = 1, mint = mapOf(0 to 100L), topology = flatTopology())
        h.pump()
        val node = h.peers[0].node
        holdAtTheCeiling(h)
        val newborn = AttachmentRecord(AttachmentId("e3"), root, GroupId("g3"), Weight.ONE)
        assertTrue(node.prepare(newborn) && node.activate(newborn.id))
        node.advertise(newborn.id, hungry)
        h.pump()
        val before = node.ledger.value
        val outcome = node.schedule(root)
        h.pump()
        assertAll(
            { assertEquals(null, before.gauge(newborn.id), "the rig: the newborn is unseated") },
            { assertIs<ScheduleOutcome.Delegated>(outcome, "the pick granted: $outcome") },
            { assertTrue(node.ledger.value.gauge(newborn.id) != null, "and the newborn was seated") },
        )
    }

    /**
     * Two static peers, cut off from each other, each activate a child that fits beside the two it
     * sees. Merged on heal, the four weights put a pick's sums out of `Long`
     * (`S = 2⁶³ + 385` over `L = 192`), so every pick at the root throws. The node used to throw on
     * every [HeddleNode.schedule]; it now answers [ScheduleOutcome.WeightBudget] and writes nothing.
     */
    @Test
    fun anOverBudgetSetMergedOnHealAnswersWeightBudgetAndWritesNothing() = runTest(
        StandardTestDispatcher(),
        timeout = TEST_WEDGE_BACKSTOP,
    ) {
        val h = harness(peers = 2, mint = mapOf(0 to 100L, 1 to 100L), topology = flatTopology())
        h.pump()
        val a = h.peers[0].node
        val b = h.peers[1].node
        val heavy = AttachmentRecord(AttachmentId("heavy"), root, GroupId("g-heavy"), Weight.of(1L shl 57, 3L))
        val light = AttachmentRecord(AttachmentId("light"), root, GroupId("g-light"), Weight.of(1L, 192L))

        h.partition(1)
        val aAdded = a.prepare(heavy) && a.activate(heavy.id)
        val bAdded = b.prepare(light) && b.activate(light.id)
        h.pump()
        val aAloneSees = a.ledger.value.activeChildren(root).size
        h.heal(1)
        h.pump(600)

        for (edge in listOf(e1, e2, heavy.id, light.id)) a.advertise(edge, hungry)
        val before = a.ledger.value
        val outcome = a.schedule(root)
        val again = a.schedule(root)
        h.pump()
        assertAll(
            { assertTrue(aAdded && bAdded, "the rig: each peer's own view accepted its child") },
            { assertEquals(3, aAloneSees, "the rig: A did not see B's child before the heal") },
            { assertEquals(4, before.activeChildren(root).size, "the rig: after the heal A sees all four") },
            { assertEquals(ScheduleOutcome.WeightBudget(root), outcome, "the node says why it stopped") },
            { assertEquals(ScheduleOutcome.WeightBudget(root), again, "and says it again on the next call") },
            { assertEquals(before, a.ledger.value, "and wrote nothing: no seat, no grant") },
            { assertEquals(1, a.weightBudgetWarningsForTest, "one warning for the transition, not one per call") },
            { assertNull(a.parentVirtualTime(root), "the diagnostic front has nothing it can compute, and does not throw") },
        )
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // A busy parent runs past its old ceiling, with nothing rebasing it (#3353, #3472)
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * A long run at one busy parent keeps granting past the position ceiling (#3353, #3472): mixed
     * weights, grants of [LocalHeddle.MAX_RESERVATION], every lease charged below its reservation,
     * and a waiting set drawn afresh each call.
     *
     * Before the origin rebase this exact run refused with [ScheduleOutcome.PositionCeiling] after
     * [GRANTS_BEFORE_THE_CEILING] grants (measured on the slice 2–4 base, JVM); with it, the rebase
     * held the peak below the ceiling. Nothing rebases now. The run goes ten times as far, counts
     * its own grants, never meets a refusal, and the peak effective position
     * [HeddleNode.boundMetrics] reports passes the ceiling, the regime the run exists for.
     */
    @Test
    fun aLongMixedWeightRunKeepsGrantingPastThePositionCeiling() = runTest(
        StandardTestDispatcher(),
        timeout = TEST_WEDGE_BACKSTOP,
    ) {
        val h = harness(peers = 1, mint = mapOf(0 to LONG_RUN_MINT), topology = mixedLanes, quantum = MAX_GRANT)
        h.pump()
        val peer = h.peers[0]
        val run = MixedWeightRun(root, mixedLanes, MAX_GRANT, Random(3353))
        val scheduling = peer.node.asSchedulingPeer()
        val target = 10 * GRANTS_BEFORE_THE_CEILING
        var peak = 0.0
        while (run.grants < target && run.calls < LONG_RUN_CALL_LIMIT) {
            run.step(scheduling)
            if (run.calls % 64 == 0) {
                h.pump(1L)
                peak = maxOf(peak, peer.node.boundMetrics(root).peakPositionFraction)
            }
        }
        // In the results XML, so a run shows what it reached.
        println("long run: calls=${run.calls} grants=${run.grants} peak=$peak")
        assertAll(
            { assertTrue(run.grants >= target, "the run made its own ${run.grants} grants") },
            { assertEquals(0, run.refusals, "no call was refused") },
            { assertTrue(run.chargedBelowReservation > 0, "the rig: leases were charged below their reservation") },
            { assertTrue(peak >= 1.0, "the rig: the peak effective position passed the ceiling: $peak") },
        )
    }

    /**
     * A wide weight spread keeps granting too (#3353 review, item A; #3472): lanes at 60/30/10/1,
     * the shape #3313 was reported with. The light lane runs alone for [WIDE_RUN_ALONE] calls, then
     * a waiting set is drawn afresh each call. A heavy lane clamped to the light lane's front on its
     * wake lands about 60 times as far up its own grid, which once refused every call after. The run
     * counts its own grants and never meets a refusal.
     */
    @Test
    fun aWideSpreadRunKeepsGranting() = runTest(
        StandardTestDispatcher(),
        timeout = TEST_WEDGE_BACKSTOP,
    ) {
        val h = harness(peers = 1, mint = mapOf(0 to LONG_RUN_MINT), topology = wideLanes, quantum = MAX_GRANT)
        h.pump()
        val peer = h.peers[0]
        val run = MixedWeightRun(root, wideLanes, MAX_GRANT, Random(3313)) { call -> if (call <= WIDE_RUN_ALONE) listOf(wideLanes[3]) else null }
        val scheduling = peer.node.asSchedulingPeer()
        var peak = 0.0
        while (run.grants < WIDE_RUN_GRANTS && run.calls < LONG_RUN_CALL_LIMIT) {
            run.step(scheduling)
            peak = maxOf(peak, peer.node.boundMetrics(root).peakPositionFraction)
            if (run.calls % 64 == 0) h.pump(1L)
        }
        println("wide run: calls=${run.calls} grants=${run.grants} peak=$peak")
        assertAll(
            { assertTrue(run.grants >= WIDE_RUN_GRANTS, "the run made its own ${run.grants} grants") },
            { assertEquals(0, run.refusals, "no call was refused") },
            { assertTrue(peak >= 1.0, "the rig: the run passed the old ceiling: $peak") },
        )
    }

    /**
     * A grant whose delegation carries its child past its ceiling is made, as `LocalHeddle` makes
     * one: a pick orders the full 128-bit position. One lease of [MAX_GRANT] puts a `1/30,719` child
     * 93.75% of the way up its own grid, so the second passes it, and nothing brings it back down
     * (#3472). The call used to stop
     * after the first lease, and the next one to answer [ScheduleOutcome.PositionCeiling] and write
     * nothing.
     */
    @Test
    fun aGrantThatCarriesAChildPastItsCeilingIsMade() = runTest(
        StandardTestDispatcher(),
        timeout = TEST_WEDGE_BACKSTOP,
    ) {
        val fine = AttachmentRecord(AttachmentId("fine"), root, GroupId("g-fine"), Weight.of(1L, 30_719L))
        val h = harness(peers = 1, mint = mapOf(0 to LONG_RUN_MINT), topology = listOf(fine), quantum = MAX_GRANT)
        h.pump()
        val node = h.peers[0].node
        node.advertise(fine.id, Demand(4L * MAX_GRANT, MAX_GRANT))
        val outcome = node.schedule(root)
        val peak = node.boundMetrics(root).peakPositionFraction
        assertAll(
            { assertEquals(ScheduleOutcome.Delegated(4), outcome, "all four leases the child asked for, in one call") },
            { assertEquals(4L * MAX_GRANT, node.ledger.value.edge(fine.id)?.issued, "every one went down the edge") },
            // One lease is 93.75% of the way up, so the second crossed the ceiling and the rest ran on past it.
            { assertTrue(peak > 3.0, "the rig: the child ended well past its ceiling: $peak") },
        )
    }

    /**
     * [HeddleNode.parentVirtualTime] over a busy child past its ceiling reads it at the ceiling, and
     * does not throw (#3485). Two `1/30,000` children: `busy` is granted the root's three leases of
     * [MAX_GRANT], while `starved` demands and is never served, so it stays at its start and `busy`
     * climbs past its ceiling, since nothing rebases (#3472). The front is the mean of the two
     * public reads.
     * It used to throw `PolicyEdge offsetTicks must be non-negative`: the read held `busy` by
     * lowering its wake offset below zero.
     */
    @Test
    fun theFrontOverABusyChildPastItsCeilingReadsItAtTheCeiling() = runTest(
        StandardTestDispatcher(),
        timeout = TEST_WEDGE_BACKSTOP,
    ) {
        val fine = Weight.of(1L, 30_000L)
        val busy = AttachmentRecord(AttachmentId("busy"), root, GroupId("g-busy"), fine)
        val starved = AttachmentRecord(AttachmentId("starved"), root, GroupId("g-starved"), fine)
        val h = harness(peers = 1, mint = mapOf(0 to 3L * MAX_GRANT), topology = listOf(busy, starved), quantum = MAX_GRANT)
        h.pump()
        val node = h.peers[0].node
        // Asks for more than the root holds, so it is still demanding when the front is read.
        node.advertise(busy.id, Demand(4L * MAX_GRANT, MAX_GRANT))
        node.advertise(starved.id, Demand(targetOutstanding = 1L, maximumUsefulGrant = 0L))
        val outcome = node.schedule(root)
        val ledger = node.ledger.value
        // A `1/30,000` child's ceiling, `C / (G · w)` with `C = ⌊CEILING_AT_WEIGHT_ONE / 30,000⌋`.
        val ceiling = Rational.of(CEILING_AT_WEIGHT_ONE / 30_000L * 30_000L, 16L)
        val busyRead = assertNotNull(ledger.virtualService(busy.id))
        val starvedRead = assertNotNull(ledger.virtualService(starved.id))
        assertAll(
            { assertEquals(ScheduleOutcome.Delegated(3), outcome, "the rig: the root's three leases, all to busy") },
            { assertTrue(node.boundMetrics(root).peakPositionFraction > 1.0, "the rig: busy is past its ceiling") },
            { assertEquals(ceiling, busyRead, "the rig: busy's public read is held at its ceiling") },
            { assertEquals(Rational.ZERO, starvedRead, "the rig: starved stands at its start, never served") },
            { assertEquals((busyRead + starvedRead) / Rational.of(2L), node.parentVirtualTime(root)) },
        )
    }

    /**
     * A grant is never larger than [LocalHeddle.MAX_RESERVATION], whatever the configured quantum
     * (#3353), so the lease P1′ sizes the weight budget for is the largest a node can make. A
     * `1/1024` child fits the budget. With a quantum of `2⁵⁰` its first grant alone would carry it
     * past its ceiling, and before the cap every call answered [ScheduleOutcome.PositionCeiling].
     */
    @Test
    fun aGrantIsCappedAtTheLargestLeaseWhateverTheQuantum() = runTest(
        StandardTestDispatcher(),
        timeout = TEST_WEDGE_BACKSTOP,
    ) {
        val coarse = AttachmentRecord(AttachmentId("coarse"), root, GroupId("g-coarse"), Weight.of(1L, 1024L))
        val h = harness(peers = 1, mint = mapOf(0 to LONG_RUN_MINT), topology = listOf(coarse), quantum = HUGE_QUANTUM)
        h.pump()
        val node = h.peers[0].node
        node.advertise(coarse.id, Demand(4L * HUGE_QUANTUM, HUGE_QUANTUM))
        val outcomes = List(4) { node.schedule(root) }
        assertAll(
            { assertTrue(weightFitsTheBudget(coarse.weight), "rig: the weight fits the budget") },
            { assertTrue(outcomes.none { it is ScheduleOutcome.LifetimeExhausted }, "no call met the ceiling: $outcomes") },
            { assertIs<ScheduleOutcome.Delegated>(outcomes.first(), "the first call granted") },
            { assertEquals(0L, (node.ledger.value.edge(coarse.id)?.issued ?: 0L) % MAX_GRANT, "every grant was one largest lease") },
            { assertTrue((node.ledger.value.edge(coarse.id)?.issued ?: 0L) > 0L, "and something was granted") },
        )
    }

    /**
     * The review's F1 case (#3353): a waker idle for a long time gets no extra service. `b`, at
     * weight 15, idles while `a`, at weight 1, runs on until `a`'s service, read on `b`'s grid, is
     * past `Long`: about 2⁶⁴ ticks. When `b` wakes, its clamp is taken wide and it comes back level
     * with `a`, where exact arithmetic would put it. A clamp narrowed to `Long` would leave it some
     * 2⁶³ ticks behind, and it would take every pick until it caught up. It was written for a waker
     * a rebase had carried below zero; nothing rebases now (#3472), and the clamp is as large.
     */
    @Test
    fun aWakerFarBehindComesBackLevel() = runTest(
        StandardTestDispatcher(),
        timeout = TEST_WEDGE_BACKSTOP,
    ) {
        val lanes = listOf(
            AttachmentRecord(AttachmentId("a"), root, GroupId("a"), Weight.ONE),
            AttachmentRecord(AttachmentId("b"), root, GroupId("b"), Weight.of(15L)),
        )
        val (a, b) = lanes.map { it.id }
        val h = harness(peers = 1, mint = mapOf(0 to LONG_RUN_MINT), topology = lanes, quantum = MAX_GRANT)
        h.pump()
        val node = h.peers[0].node
        // b is seen waiting once, then idles, so its next demand is a wake.
        node.advertise(b, Demand(targetOutstanding = 1L, maximumUsefulGrant = 1L))
        node.advertise(a, Demand.NONE)
        node.schedule(root)
        node.advertise(b, Demand.NONE)
        repeat(F1_CALLS) {
            val outstanding = node.ledger.value.edge(a)?.outstanding ?: 0L
            node.advertise(a, Demand(targetOutstanding = outstanding + 64L * MAX_GRANT, maximumUsefulGrant = MAX_GRANT))
            node.schedule(root)
            if (it % 16 == 0) h.pump(1L)
        }
        h.pump()
        // a's service on b's grid: what b's clamp has to cover.
        val aOnBsGrid = (node.ledger.value.edge(a)?.issued ?: 0L).toDouble() * 15.0 * 16.0
        // Every position as a share of its own ceiling, read wide: a's alone, since b has had one unit.
        val peakBefore = node.boundMetrics(root).peakPositionFraction
        // a stops asking, so the front b wakes to is the furthest-ahead child, a itself (§7.2).
        node.advertise(a, Demand.NONE)
        node.advertise(b, Demand(targetOutstanding = (node.ledger.value.edge(b)?.outstanding ?: 0L) + 4L, maximumUsefulGrant = 1L))
        val woke = node.schedule(root)
        h.pump()
        // Read wide: the public virtual-time reads hold both far below where they stand. a and b share
        // a ceiling, since both denominators are 1, so level at weight 15 is fifteen times a's share.
        val peakAfter = node.boundMetrics(root).peakPositionFraction
        val ratio = peakAfter / peakBefore
        assertAll(
            { assertTrue(aOnBsGrid > Long.MAX_VALUE.toDouble(), "the rig: a's service on b's grid is past Long ($aOnBsGrid ticks)") },
            { assertEquals(ScheduleOutcome.Delegated(4), woke, "the rig: b woke and was served") },
            { assertTrue(peakAfter > 1.0, "the rig: b came back past its ceiling, where only a wide clamp can take it: $peakAfter") },
            // Level within the four units b was granted at its wake, far under a part in a million.
            { assertTrue(kotlin.math.abs(ratio - 15.0) < 1e-6 * 15.0, "b came back level with a: $ratio times a's share, of 15") },
        )
    }

    private val mixedLanes = listOf(
        AttachmentRecord(AttachmentId("m1"), root, GroupId("m1"), Weight.of(1L, 200L)),
        AttachmentRecord(AttachmentId("m2"), root, GroupId("m2"), Weight.of(3L, 700L)),
        AttachmentRecord(AttachmentId("m3"), root, GroupId("m3"), Weight.of(1L, 100L)),
        AttachmentRecord(AttachmentId("m4"), root, GroupId("m4"), Weight.of(2L, 300L)),
    )

    private val wideLanes = listOf(
        AttachmentRecord(AttachmentId("w60"), root, GroupId("w60"), Weight.of(60L)),
        AttachmentRecord(AttachmentId("w30"), root, GroupId("w30"), Weight.of(30L)),
        AttachmentRecord(AttachmentId("w10"), root, GroupId("w10"), Weight.of(10L)),
        AttachmentRecord(AttachmentId("w1"), root, GroupId("w1"), Weight.ONE),
    )

    // ─────────────────────────────────────────────────────────────────────────────
    // harness
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * One edge's **effective** virtual service (raw plus its wake clamp), read without reaching
     * into the node's internals: with [edge] the only child competing, [HeddleNode.parentVirtualTime]
     * — the weighted mean over the demanding set — *is* that edge's effective virtual service.
     *
     * The probe is invisible to the clamp state machine: demand is advisory, and only
     * [HeddleNode.schedule] samples demanding-state. It does leave [edge] demanding and [others]
     * idle, so re-advertise before the next `schedule()`.
     */
    private fun HeddleNode.probeEffectiveVirtualService(
        edge: AttachmentId,
        others: List<AttachmentId>,
    ): Rational {
        for (other in others) advertise(other, Demand.NONE)
        advertise(edge, Demand(targetOutstanding = 10_000L, maximumUsefulGrant = 10L))
        return assertNotNull(parentVirtualTime(root), "root has active children, so it has a front")
    }

    /** The static multi-peer rig, lifted into `HeddleClusterTestSupport` and rooted at [root]. */
    /**
     * The position limit on [HeddleNode.schedule], where no `apply` asks early (design §7.1). `light`
     * at weight 1 stands at `2⁹⁴` ticks, planted through the control sink as a merge could land it,
     * and `heavy`, a newborn at `2⁴⁰`, would be seated level with it at `2¹³⁴` ticks. The call
     * answers [ScheduleOutcome.LifetimeExhausted] for the root, publishes nothing, and throws nothing.
     */
    @Test
    fun scheduleRefusesANewbornWhoseSeatWouldPassThePositionLimit() = runTest(
        StandardTestDispatcher(),
        timeout = TEST_WEDGE_BACKSTOP,
    ) {
        val light = AttachmentRecord(AttachmentId("light"), root, GroupId("g-light"), Weight.ONE)
        val heavy = AttachmentRecord(AttachmentId("heavy"), root, GroupId("g-heavy"), Weight.of(1L shl 40))
        val h = harness(peers = 1, mint = mapOf(0 to MAX_GRANT), topology = listOf(light, heavy), quantum = MAX_GRANT)
        h.pump()
        val node = h.peers[0].node
        node.asControlSink().publish(us.tractat.kuilt.crdt.Patch(EntitlementLedger.of(gauges = mapOf(light.id to Gauge(folded = 0L, ticksHigh = 1L shl 30, ticksLow = 0L)))))
        for (edge in listOf(light, heavy)) node.advertise(edge.id, Demand(4L * MAX_GRANT, MAX_GRANT))
        val before = node.ledger.value
        val outcome = node.schedule(root)
        assertAll(
            { assertEquals(null, before.gauge(heavy.id), "the rig: heavy is a newborn") },
            { assertEquals(ScheduleOutcome.LifetimeExhausted(root), outcome, "the seat would pass the limit") },
            { assertEquals(before, node.ledger.value, "and nothing was published") },
        )
    }

    private suspend fun TestScope.harness(
        peers: Int,
        mint: Map<Int, Long>,
        topology: List<AttachmentRecord>,
        wrap: (Seam) -> Seam = { it },
        quantum: Long = 10L,
    ): StaticCluster = staticCluster(root, peers, mint, topology, wrap, quantum)

    private companion object {
        /** A weight-1 edge's position ceiling in 1/16-unit ticks: `Long.MAX_VALUE` less one largest deadline. */
        const val CEILING_AT_WEIGHT_ONE: Long = Long.MAX_VALUE - LocalHeddle.MAX_RESERVATION * 16L

        /** The largest grant a long run makes: [LocalHeddle.MAX_RESERVATION], the deadline headroom a position keeps. */
        const val MAX_GRANT: Long = LocalHeddle.MAX_RESERVATION

        /** A quantum far past [MAX_GRANT]: one grant of it would carry a `1/1024` child past its ceiling. */
        const val HUGE_QUANTUM: Long = 1L shl 50

        /** Enough root supply for every long run here, and two peers' fit a `Long`: 2⁶¹, about 130,000 grants of [MAX_GRANT]. */
        const val LONG_RUN_MINT: Long = 1L shl 61

        /** The most calls a long run here makes: far past its grant target, so a run that stalls reds instead of hanging. */
        const val LONG_RUN_CALL_LIMIT: Int = 20_000

        /** How many calls the twin and the rebasing node run alike after the twin passes its ceiling. */
        const val TWIN_CALLS_PAST: Int = 64

        /** The lagging-peer scenario's grant, and how many a call makes: 12 calls of 256 pass a weight-1 lane's threshold. */
        const val LAG_GRANT: Long = LocalHeddle.MAX_RESERVATION
        const val LAG_GRANTS_PER_CALL: Long = 256L
        const val LAG_CALLS: Int = 12

        /** The grants the 60/30/10/1 run makes, and the calls its light lane runs alone first. */
        const val WIDE_RUN_GRANTS: Int = 4_000
        const val WIDE_RUN_ALONE: Int = 1_500

        /** Calls of 64 grants the F1 waker's sibling makes: past two rebases of a weight-1 lane. */
        const val F1_CALLS: Int = 72

        /** The newborn the lagging peer seats. */
        val LAG_NEWBORN = AttachmentRecord(AttachmentId("l3"), GroupId("root"), GroupId("l3"), Weight.ONE)
    }
}
