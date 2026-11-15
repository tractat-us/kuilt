package us.tractat.kuilt.heddle

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.math.Int128
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The acceptance run for #3353: a governed cluster under sustained load runs far past the point
 * where a scheduler without the origin rebase stops, and a scheduler that does stop says so.
 *
 * Every run here is a governed cluster of three peers on `MultiNodeRaftSim`, each with a real data
 * plane, built by [governedCluster]. The workload is [MixedWeightRun]: four lanes of mixed weight
 * at one parent, grants of [LocalHeddle.MAX_RESERVATION], a waiting set drawn afresh each call,
 * and every lease charged a little over half its reservation.
 *
 * - With one scheduling peer, a converged view, one level and one grant per call, the cluster
 *   picks exactly as [LocalHeddle] does, call for call, across rebases on both sides.
 * - With two schedulers at once, a partition between them, or a restarted scheduler, the picks are
 *   allowed to differ. Those runs check instead that the cluster converges, that every minted unit
 *   is accounted for, and that the §8.2 bound pieces stay ordered.
 *
 * Discipline (`.claude/rules/consensus.md`): the canonical sim, `StandardTestDispatcher`,
 * `TEST_WEDGE_BACKSTOP`, seeded RNG walking [List]s the test built, and bounded pumps only.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HeddleAcceptanceTest {

    private val root = ACCEPTANCE_ROOT
    private val topology = draft("root") {
        lane("m1", Weight.of(1L, 200L))
        lane("m2", Weight.of(3L, 700L))
        lane("m3", Weight.of(1L, 100L))
        lane("m4", Weight.of(2L, 300L))
    }

    /** The same lanes [LocalHeddle] boots from [topology], with its attachment ids, for the governed log. */
    private val lanes: List<AttachmentRecord> = topology.lower()

    /** The consumer's shape from #3313: four children weighted 60, 30, 10 and 1, a spread far wider than 16 to 1. */
    private val spreadLanes: List<AttachmentRecord> = draft("root") {
        lane("w60", Weight.of(60L))
        lane("w30", Weight.of(30L))
        lane("w10", Weight.of(10L))
        lane("w1", Weight.ONE)
    }.lower()

    /**
     * The rig, proved: a governed cluster with the rebase switched off makes the same picks as one
     * with it on, call for call, until it answers [ScheduleOutcome.PositionCeiling]; it answers it
     * again on the next call, while every lane still waits, so the stop is typed, not silent. The
     * rebasing cluster then runs on to ten times the twin's grants without one refusal, and the
     * other two peers converge on its ledger.
     */
    @Test
    fun aGovernedClusterRunsTenTimesPastWhereANoRebaseTwinRefuses() = runTest(
        StandardTestDispatcher(),
        timeout = TEST_WEDGE_BACKSTOP,
    ) {
        val rebasing = bootedCluster(mint = mapOf(0 to SUPPLY))
        val twin = bootedCluster(mint = mapOf(0 to SUPPLY))
        twin.peers[0].node.rebaseForTest = false
        val node = rebasing.peers[0].node.asSchedulingPeer()
        val twinNode = twin.peers[0].node.asSchedulingPeer()
        val a = MixedWeightRun(root, lanes, MAX_GRANT, Random(3353))
        val b = MixedWeightRun(root, lanes, MAX_GRANT, Random(3353))

        var twinRefusal: ScheduleOutcome? = null
        while (twinRefusal == null && a.calls < TWIN_CALL_LIMIT) {
            a.step(node)
            when (val outcome = b.step(twinNode)) {
                is ScheduleOutcome.PositionCeiling -> twinRefusal = outcome
                else -> assertEquals(b.issuedByLane(twinNode), a.issuedByLane(node), "call ${a.calls}: the two made different picks")
            }
        }
        val twinGrants = b.grants
        // Every lane waits, and the twin answers the same refusal again rather than going quiet.
        b.advertise(twinNode, lanes)
        val again = b.schedule(twinNode)

        var peak = 0.0
        while (a.grants < 10 * twinGrants) {
            val outcome = a.step(node)
            assertTrue(outcome !is ScheduleOutcome.PositionCeiling, "refused at the ceiling after ${a.grants} grants")
            if (a.calls % PUMP_EVERY == 0) {
                rebasing.pump(1L)
                peak = maxOf(peak, node.boundMetrics(root).peakPositionFraction)
            }
        }
        rebasing.pump()
        rebasing.pump()
        val merged = rebasing.peers[0].node.ledger.value
        println(
            "acceptance: twin refused at call ${b.calls} after $twinGrants grants; the rebasing cluster made " +
                "${a.grants} grants in ${a.calls} calls with ${node.rebases()} rebases, peak $peak",
        )
        assertAll(
            { assertEquals(ScheduleOutcome.PositionCeiling(root), twinRefusal, "the rig: the twin reached its ceiling, and said so") },
            { assertTrue(twinGrants > 0, "the rig: the twin granted before it refused ($twinGrants)") },
            { assertEquals(ScheduleOutcome.PositionCeiling(root), again, "the twin's stop is typed on every call, not a silent stall") },
            { assertEquals(0, twinNode.rebases(), "the rig: the twin never rebased") },
            { assertTrue(a.grants >= 10 * twinGrants, "the rebasing cluster made its own ${a.grants} grants") },
            { assertTrue(node.rebases() > 0, "the rig: the rebasing cluster rebased (${node.rebases()} times)") },
            { assertTrue(merged.origin(root) > Int128.ZERO, "the origin rose, to ${merged.origin(root)}") },
            { assertTrue(a.chargedBelowReservation > 0, "the rig: leases were charged below their reservation") },
            { assertTrue(peak > 0.0 && peak < 1.0, "the peak effective position stayed below the ceiling: $peak") },
            { assertConverged(rebasing) },
            { assertNull(merged.conservationShortfall(root, rebasing.everyReplica), "every minted unit is accounted for") },
        )
    }

    /**
     * Where the design says the two engines agree, they do: one scheduling peer, a converged view,
     * one level and one grant per call. Each call mints exactly one grant through the log, so the
     * scheduler can make exactly one; [LocalHeddle] gets the same waiting set and picks once. The
     * run passes many rebases on both sides, so the agreement spans them.
     *
     * The lease is charged in full on the [LocalHeddle] side and below its reservation on the
     * governed side. The difference is deliberate: [LocalHeddle] returns an unspent reservation to
     * its edge, which lowers that edge's position, while a governed node returns nothing to the gauge
     * on an under-charge (nothing in this module calls `release`), so a full charge is the [LocalHeddle]
     * twin of a governed under-charge.
     */
    @Test
    fun oneGovernedSchedulerPicksAsLocalHeddleCallForCall() = runTest(
        StandardTestDispatcher(),
        timeout = TEST_WEDGE_BACKSTOP,
    ) {
        val cluster = bootedCluster(mint = emptyMap())
        val peer = cluster.peers[0]
        val node = peer.node.asSchedulingPeer()
        val local = LocalHeddle.fromTopology(topology, LocalPolicy(defaultReservation = 1L), epoch = 1L).apply { reconcileCapacity(1L) }
        val run = MixedWeightRun(root, lanes, MAX_GRANT, Random(3329))
        val divergences = ArrayList<String>()
        var fundedOneGrant = 0

        repeat(MATCH_CALLS) { call ->
            cluster.applied("mint one grant") { peer.node.mint(peer.replica, MAX_GRANT) }
            if (node.ledger.value.holdings(root, peer.replica) == MAX_GRANT) fundedOneGrant++
            val waiting = run.drawWaiting()
            val before = run.issuedByLane(node)
            run.advertise(node, waiting)
            val outcome = run.schedule(node)
            run.chargeBelowReservation(node)
            val picked = lanes.indices.filter { run.issuedByLane(node)[it] > before[it] }.map { lanes[it].child }

            val waitingGroups = waiting.mapTo(LinkedHashSet()) { it.child }
            val lease = assertIs<AcquireOutcome.Acquired>(
                local.acquire(waitingGroups, waitingGroups, startedAt = call.toLong(), reservation = MAX_GRANT),
                "call ${call + 1}: LocalHeddle picks",
            ).lease
            assertIs<LeaseOutcome.Done>(local.complete(lease.id, actualCost = MAX_GRANT))

            if (outcome != ScheduleOutcome.Delegated(1) || picked != listOf(lease.leaf)) {
                if (divergences.size < MAX_REPORTED) divergences += "call ${call + 1}: $outcome to $picked, LocalHeddle ${lease.leaf.value}"
            }
        }
        cluster.pump()
        println("match: $MATCH_CALLS calls, ${node.rebases()} governed rebases, origins ${node.ledger.value.origin(root)} and ${local.snapshot().ledger.origin(root)}")
        assertAll(
            { assertEquals(MATCH_CALLS, run.calls, "the run made every call") },
            { assertEquals(MATCH_CALLS, fundedOneGrant, "the rig: each call's root held exactly one grant") },
            { assertTrue(node.rebases() > 0, "the rig: the governed scheduler rebased (${node.rebases()} times)") },
            { assertTrue(local.snapshot().ledger.origin(root) > Int128.ZERO, "the rig: LocalHeddle rebased too") },
            { assertEquals(emptyList<String>(), divergences, "every call picked what LocalHeddle picked") },
            { assertConverged(cluster) },
        )
    }

    /**
     * A parent whose weights spread far wider than sixteen to one, 60/30/10/1, the consumer's shape
     * from #3313. The light child runs alone in bursts while the heavy ones idle, then every child
     * competes again. One unit of virtual time is sixty times as many ticks on the heaviest child's
     * grid as on the lightest's, so a rebase timed by the light child alone lets the heavy children
     * wake past their ceiling. The rebase must keep every child clear of its ceiling.
     */
    @Test
    fun aParentWeightedSixtyThirtyTenOneRunsPastTheOldCeiling() = runTest(
        StandardTestDispatcher(),
        timeout = TEST_WEDGE_BACKSTOP,
    ) {
        val cluster = bootedCluster(mint = mapOf(0 to SUPPLY), lanes = spreadLanes)
        val node = cluster.peers[0].node.asSchedulingPeer()
        val light = spreadLanes.single { it.weight == Weight.ONE }
        val burst = MixedWeightRun(root, spreadLanes, MAX_GRANT, Random(3313), drawFrom = listOf(light), grantsPerCall = BURST_GRANTS)
        val mix = MixedWeightRun(root, spreadLanes, MAX_GRANT, Random(3314))
        val refusals = ArrayList<String>()
        var peak = 0.0
        repeat(SPREAD_ROUNDS) { round ->
            for ((run, calls) in listOf(burst to BURST_CALLS, mix to MIX_CALLS)) {
                repeat(calls) {
                    if (run.step(node) is ScheduleOutcome.PositionCeiling) refusals += "round ${round + 1}, call ${run.calls}"
                    if (run.calls % PUMP_EVERY == 0) {
                        cluster.pump(1L)
                        peak = maxOf(peak, node.boundMetrics(root).peakPositionFraction)
                    }
                }
            }
        }
        cluster.pump()
        val grants = burst.grants + mix.grants
        println("spread: $grants grants (${burst.grants} light alone), ${node.rebases()} rebases, peak $peak, ${refusals.size} refusals")
        assertAll(
            { assertEquals(SPREAD_ROUNDS * (BURST_CALLS + MIX_CALLS), burst.calls + mix.calls, "the run made every call") },
            { assertEquals(emptyList<String>(), refusals.take(MAX_REPORTED), "no call met the ceiling (${refusals.size} did)") },
            { assertTrue(grants >= LONG_RUN_GRANTS, "the run made its own $grants grants") },
            { assertTrue(node.rebases() > 0, "the rig: the parent rebased (${node.rebases()} times)") },
            { assertTrue(peak < 1.0, "the peak effective position stayed below the ceiling: $peak") },
            { assertConverged(cluster) },
            { assertNull(node.ledger.value.conservationShortfall(root, cluster.everyReplica), "every minted unit is accounted for") },
        )
    }

    /**
     * A heavy newborn, weight 60, joins a parent whose only other child, weight 1, has been running
     * alone and busy for a long time: long enough that the runner's service, counted on the
     * newborn's grid, is past a `Long`, but not so long that it passed a sixteenth of its own
     * ceiling. The newborn is seated at the front and the two then both take a grant on every call,
     * with neither meeting the ceiling.
     */
    @Test
    fun aHeavyNewbornJoiningABusyLightRunnerNeverMeetsTheCeiling() = runTest(
        StandardTestDispatcher(),
        timeout = TEST_WEDGE_BACKSTOP,
    ) {
        val light = spreadLanes.single { it.weight == Weight.ONE }
        val heavy = spreadLanes.single { it.weight == Weight.of(60L) }
        val cluster = bootedCluster(mint = mapOf(0 to SUPPLY), lanes = listOf(light))
        val peer = cluster.peers[0]
        val node = peer.node.asSchedulingPeer()
        val runnerAlone = MixedWeightRun(root, listOf(light), MAX_GRANT, Random(1), grantsPerCall = RUNNER_GRANTS)
        repeat(RUNNER_CALLS) {
            runnerAlone.step(node)
            if (runnerAlone.calls % PUMP_EVERY == 0) cluster.pump(1L)
        }
        val rebasesAlone = node.rebases()
        val lightAlone = runnerAlone.issuedByLane(node).single()
        cluster.applied("prepare the heavy newborn") { peer.node.prepare(heavy) }
        cluster.applied("activate the heavy newborn") { peer.node.activate(heavy.id) }

        val both = MixedWeightRun(root, listOf(light, heavy), MAX_GRANT, Random(2))
        val refusals = ArrayList<String>()
        repeat(JOINED_CALLS) {
            both.advertise(node, listOf(light, heavy))
            if (both.schedule(node) is ScheduleOutcome.PositionCeiling) refusals += "call ${both.calls}"
            both.chargeBelowReservation(node)
            if (both.calls % PUMP_EVERY == 0) cluster.pump(1L)
        }
        cluster.pump()
        val ledger = node.ledger.value
        val heavyIssued = ledger.edge(heavy.id)?.issued ?: 0L
        val lightSince = (ledger.edge(light.id)?.issued ?: 0L) - lightAlone
        println("newborn: runner made ${runnerAlone.grants} grants alone, $rebasesAlone rebases; then heavy $heavyIssued, light $lightSince")
        assertAll(
            { assertEquals(RUNNER_CALLS, runnerAlone.calls, "the rig: the runner ran alone for every call") },
            {
                val onTheNewbornsGrid = runnerAlone.grants.toDouble() * MAX_GRANT * 60.0 * 16.0
                assertTrue(onTheNewbornsGrid > Long.MAX_VALUE.toDouble(), "the rig: the runner's service on the newborn's grid is past a Long")
            },
            { assertEquals(JOINED_CALLS, both.calls, "the two ran together for every call") },
            { assertEquals(emptyList<String>(), refusals.take(MAX_REPORTED), "no call met the ceiling (${refusals.size} did)") },
            { assertEquals(JOINED_CALLS * MAX_GRANT, heavyIssued, "the newborn was granted on every call") },
            { assertEquals(JOINED_CALLS * MAX_GRANT, lightSince, "and so was the runner") },
            { assertConverged(cluster) },
        )
    }

    /**
     * Two schedulers at once, cut off from each other for a stretch, then healed. Their picks may
     * differ from any one engine's, so this run checks what replication promises instead: the §8.2
     * bound pieces stay ordered on both sides while they are apart, the three peers converge after,
     * every minted unit is accounted for, and neither scheduler meets the ceiling.
     */
    @Test
    fun twoSchedulersAcrossAPartitionConvergeAndConserve() = runTest(
        StandardTestDispatcher(),
        timeout = TEST_WEDGE_BACKSTOP,
    ) {
        val cluster = bootedCluster(mint = mapOf(0 to SUPPLY, 1 to SUPPLY))
        val nodes = cluster.peers.take(2).map { it.node.asSchedulingPeer() }
        val runs = listOf(MixedWeightRun(root, lanes, MAX_GRANT, Random(11)), MixedWeightRun(root, lanes, MAX_GRANT, Random(12)))
        val refusals = ArrayList<String>()
        val boundBreaches = ArrayList<String>()
        var deviationWhileApart = 0L

        fun phase(calls: Int, apart: Boolean) = repeat(calls) { call ->
            for (i in nodes.indices) {
                val outcome = runs[i].step(nodes[i])
                if (outcome is ScheduleOutcome.PositionCeiling) refusals += "scheduler $i at call ${runs[i].calls}"
            }
            if (call % PUMP_EVERY == 0) cluster.pump(1L)
            if (apart) {
                for (i in nodes.indices) {
                    val m = nodes[i].boundMetrics(root)
                    if (!m.isConsistent) boundBreaches += "scheduler $i at call ${runs[i].calls}: $m"
                    deviationWhileApart = maxOf(deviationWhileApart, m.observedDeviation)
                }
            }
        }

        phase(TOGETHER_CALLS, apart = false)
        cluster.pump()
        cluster.partitionDataPlane(1)
        phase(APART_CALLS, apart = true)
        val apartLedgersDiffer = nodes[0].ledger.value != nodes[1].ledger.value
        val originsApart = nodes.map { it.ledger.value.origin(root) }
        cluster.healDataPlane(1)
        cluster.pump(800)
        cluster.pump(800)
        phase(TOGETHER_CALLS, apart = false)
        cluster.pump(800)
        cluster.pump(800)

        val merged = nodes[0].ledger.value
        val totalGrants = runs.sumOf { it.grants }
        println("two schedulers: ${runs.map { it.grants }} grants, ${nodes.map { it.rebases() }} rebases, origin ${merged.origin(root)}")
        assertAll(
            { assertEquals(List(2) { 2 * TOGETHER_CALLS + APART_CALLS }, runs.map { it.calls }, "each scheduler made every call") },
            { assertTrue(totalGrants >= LONG_RUN_GRANTS, "the run went far past the old ceiling: $totalGrants grants") },
            { assertTrue(nodes.all { it.rebases() > 0 }, "the rig: both schedulers rebased (${nodes.map { it.rebases() }})") },
            { assertTrue(apartLedgersDiffer, "the rig: the partition was real, the two views diverged") },
            { assertTrue(deviationWhileApart > 0L, "the rig: there was a deviation for the bound to bound") },
            { assertEquals(emptyList<String>(), boundBreaches, "the §8.2 pieces stayed ordered on both sides while apart") },
            { assertEquals(emptyList<String>(), refusals, "neither scheduler met the ceiling") },
            { assertTrue(originsApart.all { merged.origin(root) >= it }, "the merged origin is at least each side's: $originsApart") },
            { assertConverged(cluster) },
            { assertNull(merged.conservationShortfall(root, cluster.everyReplica), "every minted unit is accounted for") },
            { assertTrue(nodes.all { it.boundMetrics(root).isConsistent }, "and the §8.2 pieces are ordered after the heal") },
        )
    }

    /**
     * A scheduler crashes mid-run and restarts with no wake memory. Before the crash only its
     * lightest child, weight 1, ran, in bursts, past a rebase; the heaviest, weight 60, idled
     * throughout, so it sits far behind the origin on its own grid, and it is demanding from the
     * restarted scheduler's first call. A fresh allocator sees that as a
     * first observation, not a wake, so nothing clamps the child to the front: the parent must still
     * not wedge at the ceiling. The run then carries on past the old ceiling, and the cluster
     * converges and conserves across the restart.
     */
    @Test
    fun aRestartedSchedulerWithALongIdleChildDemandingAtBootDoesNotWedge() = runTest(
        StandardTestDispatcher(),
        timeout = TEST_WEDGE_BACKSTOP,
    ) {
        val cluster = bootedCluster(mint = mapOf(1 to SUPPLY), lanes = spreadLanes)
        val idle = spreadLanes.single { it.weight == Weight.of(60L) }
        val light = spreadLanes.single { it.weight == Weight.ONE }
        val before = MixedWeightRun(root, spreadLanes, MAX_GRANT, Random(21), drawFrom = listOf(light), grantsPerCall = BURST_GRANTS)
        val first = cluster.peers[1].node.asSchedulingPeer()
        val refusedBefore = ArrayList<String>()
        repeat(BEFORE_RESTART_CALLS) { call ->
            if (before.step(first) is ScheduleOutcome.PositionCeiling) refusedBefore += "call ${call + 1}"
            if (call % PUMP_EVERY == 0) cluster.pump(1L)
        }
        // Its demand goes with it: the restarted process advertises its own.
        for (lane in spreadLanes) first.advertise(lane.id, Demand.NONE)
        cluster.pump()
        val originBefore = cluster.peers[0].node.ledger.value.origin(root)
        val rebasesBefore = first.rebases()
        val idleIssuedBefore = first.ledger.value.edge(idle.id)?.issued ?: 0L

        cluster.restart(1)
        val restarted = cluster.peers[1]
        cluster.applied("mint to the restarted scheduler") { restarted.node.mint(restarted.replica, SUPPLY) }
        var catchUpPumps = 0
        while (restarted.node.ledger.value.origin(root) < originBefore && catchUpPumps < CATCH_UP_PUMPS) {
            cluster.pump()
            catchUpPumps++
        }
        val originAtRestart = restarted.node.ledger.value.origin(root)

        val after = MixedWeightRun(root, spreadLanes, MAX_GRANT, Random(22))
        val second = restarted.node.asSchedulingPeer()
        // The first call after the boot: every lane waits, the long-idle one included.
        after.advertise(second, spreadLanes)
        val atBoot = after.schedule(second)
        after.chargeBelowReservation(second)
        val refusals = ArrayList<String>()
        repeat(AFTER_RESTART_CALLS) { call ->
            if (after.step(second) is ScheduleOutcome.PositionCeiling) refusals += "call ${call + 2}"
            if (call % PUMP_EVERY == 0) cluster.pump(1L)
        }
        cluster.pump(800)
        cluster.pump(800)
        val merged = cluster.peers[0].node.ledger.value
        println("restart: ${before.grants} grants before, ${after.grants} after, rebases $rebasesBefore then ${second.rebases()}, at boot $atBoot")
        assertAll(
            { assertEquals(BEFORE_RESTART_CALLS, before.calls, "the run before the restart made every call") },
            { assertEquals(emptyList<String>(), refusedBefore.take(MAX_REPORTED), "no call before the restart met the ceiling") },
            { assertEquals(AFTER_RESTART_CALLS + 1, after.calls, "and the run after it made every call") },
            { assertTrue(rebasesBefore > 0, "the rig: the scheduler rebased before it crashed") },
            { assertEquals(0L, idleIssuedBefore, "the rig: the heavy child idled through the whole run before the crash") },
            { assertTrue(restarted.replica != first.self, "the rig: the restarted process is a fresh replica") },
            { assertEquals(originBefore, originAtRestart, "the rig: the restarted scheduler caught up on the origin before it scheduled") },
            { assertIs<ScheduleOutcome.Delegated>(atBoot, "the boot call, with the long-idle child demanding, delegates") },
            { assertEquals(emptyList<String>(), refusals.take(MAX_REPORTED), "no call after the restart met the ceiling (${refusals.size} did)") },
            { assertTrue(second.rebases() > 0, "the restarted scheduler rebased in its turn") },
            { assertTrue(before.grants + after.grants >= LONG_RUN_GRANTS, "the run went far past the old ceiling") },
            { assertConverged(cluster) },
            { assertNull(merged.conservationShortfall(root, cluster.everyReplica), "every minted unit is accounted for") },
        )
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // harness
    // ─────────────────────────────────────────────────────────────────────────────

    /** A three-peer governed cluster with [lanes] active under [root] and peer `i` minted `mint[i]`. */
    private suspend fun TestScope.bootedCluster(
        mint: Map<Int, Long>,
        lanes: List<AttachmentRecord> = this@HeddleAcceptanceTest.lanes,
    ): GovernedCluster {
        check(topology.groupId() == root) { "the topology's root is the cluster's" }
        val cluster = governedCluster(size = 3, quantum = MAX_GRANT, cap = SUPPLY)
        cluster.bootstrap(lanes, mint)
        return cluster
    }

    private fun assertConverged(cluster: GovernedCluster) {
        val ledgers = cluster.peers.map { it.node.ledger.value }
        assertEquals(1, ledgers.distinct().size, "every peer holds the same ledger")
    }

    private companion object {
        /** The largest grant: [LocalHeddle.MAX_RESERVATION], the deadline headroom a position keeps. */
        const val MAX_GRANT: Long = LocalHeddle.MAX_RESERVATION

        /** One scheduler's supply, and the §8.2 cap `E`: 2⁵⁶, 4,096 grants of [MAX_GRANT]. Four peers' caps fit a `Long`. */
        const val SUPPLY: Long = 1L shl 56

        /** How far a run goes: ten times the 145 grants after which a static node used to refuse (`HeddleNodeTest`). */
        const val LONG_RUN_GRANTS: Int = 1_450

        /** The twin refuses well inside this many calls; the bound only stops a broken rig running forever. */
        const val TWIN_CALL_LIMIT: Int = 2_000

        /** Calls the match run makes, one grant each: past the old ceiling and many rebases. */
        const val MATCH_CALLS: Int = 250

        /** Calls each scheduler makes before the partition and again after it heals. */
        const val TOGETHER_CALLS: Int = 200

        /**
         * Calls each scheduler makes while the two are apart. The heal costs grow with the calls made
         * apart: the first pump after it took 15 ms after none, 0.8 s after 16 and 1.6 s after 32 on
         * the JVM, about ten times that on Kotlin/Native, and 250 exhausted a 512 MB test JVM.
         */
        const val APART_CALLS: Int = 16

        /** Calls the restart run makes before the crash, light bursts only, and after the boot call. */
        const val BEFORE_RESTART_CALLS: Int = 300
        const val AFTER_RESTART_CALLS: Int = 500

        /**
         * The light runner's calls alone, at [RUNNER_GRANTS] each: 1,600 grants, past a `Long` on the
         * newborn's grid and short of a sixteenth of the runner's own ceiling. Then its calls beside
         * the newborn.
         */
        const val RUNNER_CALLS: Int = 400
        const val RUNNER_GRANTS: Int = 4
        const val JOINED_CALLS: Int = 400

        /**
         * The spread run's rounds: a burst of [BURST_CALLS] calls of [BURST_GRANTS] grants each to
         * the light child alone, then [MIX_CALLS] calls with every child drawn at random.
         */
        const val SPREAD_ROUNDS: Int = 3
        const val BURST_CALLS: Int = 100
        const val BURST_GRANTS: Int = 8
        const val MIX_CALLS: Int = 100

        /** Every so many calls, a run lets gossip move. */
        const val PUMP_EVERY: Int = 16

        /** Pumps a restarted peer gets to catch up on the origin. */
        const val CATCH_UP_PUMPS: Int = 20

        const val MAX_REPORTED: Int = 8
    }
}
