package us.tractat.kuilt.heddle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.liveness.PartitionEvent
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * H4 acceptance suite (design §15 Phase 4): multi-peer convergence over [InMemoryLoom]
 * under partition/heal/loss/dup/reorder (§10.7), deliver-N-times idempotence (§4.4),
 * stale-demand safety (§6), the §8.2 bound metrics, stranded crashed-peer earmarks,
 * the full §10.1 conservation identity **with** earmarks, and the §13 end-to-end scenario.
 *
 * Discipline (repo CLAUDE.md): a generous `TEST_WEDGE_BACKSTOP` wedge ceiling (never a tight
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

    private fun config(seed: Int, quantum: Long = 10L, cap: Long = 1_000L) = HeddleConfig(
        policy = PolicyConfig(quantum = quantum),
        maxHoldingsPerPeer = cap,
        demandTtl = 30.seconds,
        quilter = QuilterConfig(
            antiEntropyInterval = 100.milliseconds,
            fullStateRetryLimit = 0,
            expectVirtualTime = true,
        ),
        heartbeat = us.tractat.kuilt.liveness.HeartbeatConfig(),
        random = Random(seed),
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
        val id = node.reserve(g1, 10L)
        assertNotNull(id, "reservation must succeed against delegated holdings")

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
            val id = h.peers[0].node.reserve(g1, 20L)
            assertNotNull(id, "peer0 reserves against its delegated holdings")
            assertGlobalConservation(h, minted)

            // Complete part of it, converge across the merge, re-check.
            h.peers[0].node.complete(id, 15L)
            h.pump(600)
            assertGlobalConservation(h, minted)
            assertEquals(0L, h.peers[0].node.earmarked(g1), "earmark released after completion")
        }

    private fun assertGlobalConservation(h: Harness, minted: Long) {
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
            val id = node.reserve(g1, 10L)
            assertNotNull(id)

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
            val id = node.reserve(g1, 10L)
            assertNotNull(id)

            // g1 gains a PREPARED child concurrently → isLeaf(g1) becomes false.
            val child = AttachmentRecord(AttachmentId("g1-child"), g1, GroupId("gc"), Weight.ONE)
            assertTrue(node.prepare(child))
            h.pump()

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
        assertNull(h.peers[0].node.reserve(root, 10L), "reserve refuses a non-leaf group")
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
            val id = node.reserve(g1, 10L)!!
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
        val id = node.reserve(g1, 50L)
        assertNotNull(id)

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
            val earmark = crashing.reserve(g1, 10L)
            assertNotNull(earmark, "crashing peer reserves before it goes silent")

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
     * The clamp fires on an observed idle→demand **transition**, never on the first sight of an
     * edge: a first observation carries no evidence the child was ever idle, and forfeiting a
     * real deficit accrued under some *other* peer's scheduling would be a penalty this peer has
     * no standing to impose. Seating a genuinely new generation is the creation rule's job
     * ([AttachmentRecord.neutral], #1688), not the clamp's.
     */
    @Test
    fun firstObservationOfADemandingEdgeIsNotTreatedAsAWake() = runTest(
        StandardTestDispatcher(),
        timeout = TEST_WEDGE_BACKSTOP,
    ) {
        val h = harness(peers = 1, mint = mapOf(0 to 200L), topology = flatTopology())
        h.pump()
        val node = h.peers[0].node

        // g1 is handed a deficit out of band — it is behind, and has never been observed idle.
        val behind = AttachmentRecord(AttachmentId("e3"), root, GroupId("g3"), Weight.ONE)
        assertTrue(node.prepare(behind) && node.activate(behind.id))
        val hungrier = Demand(targetOutstanding = 10_000L, maximumUsefulGrant = 10L)
        node.advertise(e1, hungrier)
        node.advertise(behind.id, hungrier)
        node.schedule(root)
        h.pump()

        // e1 starts at 0 and e3 at 100, so e1 is eligible first and stays ahead on grants; the
        // clamp must not have levelled them.
        val ledger = node.ledger.value
        assertTrue(
            ledger.edge(e1)!!.issued > ledger.edge(behind.id)!!.issued,
            "an unclamped first observation must keep e1's real advantage " +
                "(e1=${ledger.edge(e1)!!.issued}, e3=${ledger.edge(behind.id)!!.issued})",
        )
    }

    /**
     * A **second** wake can never hand back credit an earlier one forfeited (issue #1714). The
     * clamp is documented as a *forward* offset — it "can never advance a child's turn, only give
     * one up" — but the front it is measured against is the weighted mean over whoever is
     * competing *now*, and that mean is not monotone across wake cycles. Storing the offset by
     * replacement therefore lets a later, lower front refund the earlier clamp; the offset has to
     * be joined with `max`.
     *
     * The scenario, exactly: e2 sleeps through e1's run and wakes into a front of `100`, so it
     * carries a `+100` clamp; it then runs `20` of its own, reaching an effective virtual service
     * of `120`, and sleeps again. When it re-wakes the only child still competing is e3 — parked
     * at `0` and permanently unservable — so the freshly computed offset is `max(0, 0 − 20) = 0`.
     * Replacement drops e2 from `120` back to `20`, level with a starved sibling despite 120 units
     * of effective history; a monotone join holds it at `120`.
     *
     * e1 and e3 are held **competing but unservable** via `maximumUsefulGrant = 0`, which trims
     * every quantum to nothing while leaving [HeddlePolicy.isDemanding] true — that is exactly the
     * starved sibling of #1714, and it is what pins the front where the test needs it without
     * letting a grant move anyone's raw virtual service.
     */
    @Test
    fun aRewakeCannotLowerAnEffectiveVirtualService() = runTest(
        StandardTestDispatcher(),
        timeout = TEST_WEDGE_BACKSTOP,
    ) {
        // 220 = e1's 200 plus e2's 20; holdings are exhausted from phase 2 on, so no later grant
        // can move a raw virtual service and mask the clamp.
        val h = harness(peers = 1, mint = mapOf(0 to 220L), topology = flatTopology())
        h.pump()
        val node = h.peers[0].node

        // A third child that competes from its first observation — so it is never a waker and
        // never carries a clamp — but can never absorb a grant, so it stays parked at 0.
        val starved = AttachmentRecord(AttachmentId("e3"), root, GroupId("g3"), Weight.ONE)
        assertTrue(node.prepare(starved) && node.activate(starved.id))
        val competingUnservable = Demand(targetOutstanding = 10_000L, maximumUsefulGrant = 0L)

        // Phase 1 — e1 runs alone to 200. e2 is observed idle; e3 competes but is never served.
        node.advertise(e1, Demand(targetOutstanding = 200L, maximumUsefulGrant = 10L))
        node.advertise(starved.id, competingUnservable)
        node.schedule(root)
        h.pump()
        assertEquals(200L, node.ledger.value.edge(e1)!!.issued, "e1 runs alone to its target")

        // Phase 2 — e2 wakes into a front of mean(e1 at 200, e3 at 0) = 100, then runs its 20.
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

        // Phase 4 — e1 stops competing too, so e2 re-wakes into a front of just e3, at 0 —
        // 100 units *behind* the clamp e2 is already carrying.
        node.advertise(e1, Demand.NONE)
        node.advertise(e2, Demand(targetOutstanding = 10_000L, maximumUsefulGrant = 10L))
        node.advertise(starved.id, competingUnservable)
        node.schedule(root)
        h.pump()

        assertEquals(
            Rational.of(120L),
            node.probeEffectiveVirtualService(e2, others = listOf(e1, starved.id)),
            "a re-wake into a lower front must not refund the earlier clamp (replacement gives 20)",
        )
    }

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

    private class Peer(val id: ReplicaId, val node: HeddleNode, val gate: GatedSeam)

    private inner class Harness(val peers: List<Peer>, val scheduler: TestScope)

    private suspend fun TestScope.harness(
        peers: Int,
        mint: Map<Int, Long>,
        topology: List<AttachmentRecord>,
        wrap: (Seam) -> Seam = { it },
    ): Harness {
        val loom = InMemoryLoom()
        val clock: () -> Instant = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }
        val seams: List<Seam> = (0 until peers).map { i ->
            if (i == 0) loom.host(Pattern("heddle-e2e")) else loom.join(InMemoryTag("peer-$i"))
        }
        val mintById: Map<ReplicaId, Long> = seams.mapIndexed { i, s ->
            ReplicaId(s.selfId.value) to (mint[i] ?: 0L)
        }.toMap()

        val nodes = seams.mapIndexed { i, seam ->
            val gate = GatedSeam(wrap(seam))
            val self = ReplicaId(seam.selfId.value)
            val node = backgroundScope.heddleStatic(
                seam = gate,
                self = self,
                root = root,
                mint = mintById,
                topology = topology,
                clock = clock,
                config = config(seed = 1_000 + i),
                epoch = 0L,
            )
            Peer(self, node, gate)
        }
        return Harness(nodes, this)
    }

    /** Partition peer [i] from everyone (drops its traffic both ways). */
    private fun Harness.partition(i: Int) { peers[i].gate.connected.value = false }

    /** Heal peer [i]'s link. */
    private fun Harness.heal(i: Int) { peers[i].gate.connected.value = true }

    /** Advance bounded virtual time and flush — never `advanceUntilIdle`. */
    private fun Harness.pump(ms: Long = 400L) {
        scheduler.advanceTimeBy(ms)
        scheduler.runCurrent()
    }

    /** A togglable seam wrapper: when disconnected, incoming is filtered out and sends are dropped. */
    private class GatedSeam(private val delegate: Seam) : Seam {
        val connected = MutableStateFlow(true)
        override val selfId: PeerId get() = delegate.selfId
        override val peers: StateFlow<Set<PeerId>> get() = delegate.peers
        override val state: StateFlow<SeamState> get() = delegate.state
        override val incoming: Flow<Swatch> get() = delegate.incoming.filter { connected.value }
        override suspend fun broadcast(payload: ByteArray) {
            if (connected.value) delegate.broadcast(payload)
        }
        override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
            if (connected.value) delegate.sendTo(peer, payload)
        }
        override suspend fun close(reason: CloseReason): Unit = delegate.close(reason)
    }

    /** A seam wrapper that delivers every frame twice, in and out — a duplicating fabric (§10.7). */
    private class DuplicatingSeam(private val delegate: Seam) : Seam {
        override val selfId: PeerId get() = delegate.selfId
        override val peers: StateFlow<Set<PeerId>> get() = delegate.peers
        override val state: StateFlow<SeamState> get() = delegate.state
        override val incoming: Flow<Swatch>
            get() = delegate.incoming.transform { emit(it); emit(it) }
        override suspend fun broadcast(payload: ByteArray) {
            delegate.broadcast(payload)
            delegate.broadcast(payload)
        }
        override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
            delegate.sendTo(peer, payload)
            delegate.sendTo(peer, payload)
        }
        override suspend fun close(reason: CloseReason): Unit = delegate.close(reason)
    }
}
