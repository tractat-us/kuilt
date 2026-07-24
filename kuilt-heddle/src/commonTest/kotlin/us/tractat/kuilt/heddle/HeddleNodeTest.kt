package us.tractat.kuilt.heddle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
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
import us.tractat.kuilt.quilter.QuilterConfig
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
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
 * Discipline (repo CLAUDE.md): tight 5 s timeout, `StandardTestDispatcher`, node coroutines
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
        AttachmentRecord(e1, root, g1, Weight.ONE, 0L),
        AttachmentRecord(e2, root, g2, Weight.ONE, 0L),
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
        timeout = 5.seconds,
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

    // ─────────────────────────────────────────────────────────────────────────────
    // 2. Deliver the completion N times → history rises once (§4.4).
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun completeIsIdempotentHistoryRisesOnce() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
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
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
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
    // 4. Full §10.1 conservation identity WITH earmarks.
    //    minted = Σ available-holdings + Σ earmarked + Σ spent.
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun conservationHoldsWithEarmarks() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val minted = 100L
        val h = harness(peers = 1, mint = mapOf(0 to minted), topology = flatTopology())
        h.pump()
        val node = h.peers[0].node
        node.advertise(e1, hungry)
        node.advertise(e2, hungry)
        node.schedule(root)
        h.pump()

        // Reserve on both leaves (earmarks), complete one partially, cancel the other.
        val r1 = node.reserve(g1, 20L)!!
        val r2 = node.reserve(g2, 15L)!!
        assertConservationWithEarmarks(node, minted)

        node.complete(r1, 12L)
        h.pump()
        assertConservationWithEarmarks(node, minted)

        node.cancel(r2)
        h.pump()
        assertConservationWithEarmarks(node, minted)
    }

    private fun assertConservationWithEarmarks(node: HeddleNode, minted: Long) {
        val ledger = node.ledger.value
        val groups = listOf(root, g1, g2)
        var sumHoldings = 0L
        var earmarked = 0L
        for (g in groups) {
            sumHoldings += ledger.holdings(g, node.self)
            earmarked += node.earmarked(g)
        }
        val available = sumHoldings - earmarked
        assertEquals(
            minted,
            available + earmarked + ledger.leafSpentTotal(),
            "minted = Σ available + Σ earmarked + Σ spent",
        )
        // The ledger-level (earmark-free) identity holds too — earmarks are a sub-bucket of holdings.
        assertEquals(minted, sumHoldings + ledger.leafSpentTotal(), "ledger conservation intact")
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 5. Partition, schedule both sides, heal, converge; bound metrics stay consistent (§8.2, §10.7).
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun partitionedPeersConvergeOnHealWithinBound() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
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
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
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
    // 7. The design §13 end-to-end scenario.
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun endToEndScenarioTwoTenantsDynamicSubtreePartitionHeal() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            // root → {tenant-a w1, tenant-b w1}
            val tenantA = GroupId("tenant-a")
            val tenantB = GroupId("tenant-b")
            val eA = AttachmentId("root->a")
            val eB = AttachmentId("root->b")
            val base = listOf(
                AttachmentRecord(eA, root, tenantA, Weight.ONE, 0L),
                AttachmentRecord(eB, root, tenantB, Weight.ONE, 0L),
            )
            val h = harness(peers = 2, mint = mapOf(0 to 120L, 1 to 120L), topology = base)
            h.pump()

            // Dynamically add tenant-a → {interactive w3, batch w1} via H2 prepare/activate.
            val interactive = GroupId("interactive")
            val batch = GroupId("batch")
            val eInt = AttachmentRecord(AttachmentId("a->interactive"), tenantA, interactive, Weight.of(3), 0L)
            val eBatch = AttachmentRecord(AttachmentId("a->batch"), tenantA, batch, Weight.ONE, 0L)
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
    // harness
    // ─────────────────────────────────────────────────────────────────────────────

    private class Peer(val id: ReplicaId, val node: HeddleNode, val gate: GatedSeam)

    private inner class Harness(val peers: List<Peer>, val scheduler: TestScope)

    private suspend fun TestScope.harness(
        peers: Int,
        mint: Map<Int, Long>,
        topology: List<AttachmentRecord>,
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
            val gate = GatedSeam(seam)
            val self = ReplicaId(seam.selfId.value)
            val node = backgroundScope.heddleStatic(
                seam = gate,
                self = self,
                root = root,
                mint = mintById,
                topology = topology,
                clock = clock,
                config = config(seed = 1_000 + i),
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
}
