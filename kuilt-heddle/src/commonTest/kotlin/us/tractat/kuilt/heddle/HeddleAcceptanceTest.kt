package us.tractat.kuilt.heddle

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * The acceptance run for #3353 and #3472: a governed cluster under sustained load runs far past the
 * point where a scheduler used to stop at its position ceiling, with nothing rebasing it back down.
 *
 * Every run here is a governed cluster of three peers on `MultiNodeRaftSim`, each with a real data
 * plane, built by [governedCluster]. The workload is [MixedWeightRun]: four lanes of mixed weight
 * at one parent, grants of [LocalHeddle.MAX_RESERVATION], a waiting set drawn afresh each call,
 * and every lease charged a little over half its reservation.
 *
 * - With one scheduling peer, a converged view, one level and one grant per call, the cluster
 *   picks exactly as [LocalHeddle] does, call for call, past the old ceiling on both sides.
 * - A parent weighted 60/30/10/1, a heavy newborn joining a busy light runner, and a restarted
 *   scheduler whose heaviest child idled the whole run keep granting, and the newborn and the
 *   idle child come back level with the runner rather than behind it.
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
     * Where the design says the two engines agree, they do: one scheduling peer, a converged view,
     * one level and one grant per call. Each call mints exactly one grant through the log, so the
     * scheduler can make exactly one; [LocalHeddle] gets the same waiting set and picks once. The
     * run passes the old ceiling on both sides, so the agreement spans positions past it.
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
        val started = TimeSource.Monotonic.markNow()
        val cluster = bootedCluster(mint = emptyMap())
        val peer = cluster.peers[0]
        val node = peer.node.asSchedulingPeer()
        val boot = LocalHeddle.fromTopology(topology, LocalPolicy(defaultReservation = 1L), epoch = 1L)
        check(boot.result.refusals.isEmpty()) { "the boot left out ${boot.result.refusals}" }
        val local = boot.heddle.apply { reconcileCapacity(1L) }
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
            assertIs<LeaseOutcome.Done>(local.complete(lease.id, actualCost = MAX_GRANT, waiting = emptySet()))

            if (outcome != ScheduleOutcome.Delegated(1) || picked != listOf(lease.leaf)) {
                if (divergences.size < MAX_REPORTED) divergences += "call ${call + 1}: $outcome to $picked, LocalHeddle ${lease.leaf.value}"
            }
        }
        cluster.pump()
        val peak = node.boundMetrics(root).peakPositionFraction
        println("[${started.elapsedNow()}] match: $MATCH_CALLS calls, peak $peak")
        assertAll(
            { assertEquals(MATCH_CALLS, run.calls, "the run made every call") },
            { assertEquals(MATCH_CALLS, fundedOneGrant, "the rig: each call's root held exactly one grant") },
            { assertTrue(peak >= 1.0, "the rig: the run passed the old ceiling, $peak") },
            { assertEquals(emptyList<String>(), divergences, "every call picked what LocalHeddle picked") },
            { assertConverged(cluster) },
        )
    }

    /**
     * A parent whose weights spread far wider than sixteen to one, 60/30/10/1, the consumer's shape
     * from #3313. The light child runs alone in bursts while the heavy ones idle, then every child
     * competes again. One unit of virtual time is sixty times as many ticks on the heaviest child's
     * grid as on the lightest's, so the heavy children wake far up their own grids. Nothing rebases
     * them back down (#3472): every call grants, and every minted unit is accounted for.
     */
    @Test
    fun aParentWeightedSixtyThirtyTenOneRunsPastTheOldCeiling() = runTest(
        StandardTestDispatcher(),
        timeout = TEST_WEDGE_BACKSTOP,
    ) {
        val started = TimeSource.Monotonic.markNow()
        val cluster = bootedCluster(mint = mapOf(0 to SUPPLY), lanes = spreadLanes)
        val node = cluster.peers[0].node.asSchedulingPeer()
        val light = spreadLanes.single { it.weight == Weight.ONE }
        val burst = MixedWeightRun(root, spreadLanes, MAX_GRANT, Random(3313), drawFrom = listOf(light), grantsPerCall = BURST_GRANTS)
        val mix = MixedWeightRun(root, spreadLanes, MAX_GRANT, Random(3314))
        var peak = 0.0
        repeat(SPREAD_ROUNDS) {
            for ((run, calls) in listOf(burst to BURST_CALLS, mix to MIX_CALLS)) {
                repeat(calls) {
                    run.step(node)
                    if (run.calls % PUMP_EVERY == 0) {
                        cluster.pump(1L)
                        peak = maxOf(peak, node.boundMetrics(root).peakPositionFraction)
                    }
                }
            }
        }
        cluster.pump()
        val grants = burst.grants + mix.grants
        println("[${started.elapsedNow()}] spread: $grants grants (${burst.grants} light alone), peak $peak")
        assertAll(
            { assertEquals(SPREAD_ROUNDS * (BURST_CALLS + MIX_CALLS), burst.calls + mix.calls, "the run made every call") },
            { assertTrue(grants >= LONG_RUN_GRANTS, "the run made its own $grants grants") },
            { assertTrue(peak >= 1.0, "the rig: the run passed the old ceiling: $peak") },
            { assertConverged(cluster) },
            { assertNull(node.ledger.value.conservationShortfall(root, cluster.everyReplica), "every minted unit is accounted for") },
        )
    }

    /**
     * A heavy newborn, weight 60, joins a parent whose only other child, weight 1, has been running
     * alone and busy for a long time: long enough that the runner's service, counted on the
     * newborn's grid, is past a `Long`. The newborn is seated at the front, past its own ceiling,
     * since nothing rebases the parent (#3472), and the two then both take a grant on every call.
     * The public virtual-time reads hold the newborn at its ceiling, so where it was seated is read
     * wide, off the peak effective position: the two share a ceiling, both denominators being 1.
     */
    @Test
    fun aHeavyNewbornJoiningABusyLightRunnerIsSeatedLevelPastItsCeiling() = runTest(
        StandardTestDispatcher(),
        timeout = TEST_WEDGE_BACKSTOP,
    ) {
        val started = TimeSource.Monotonic.markNow()
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
        val lightAlone = runnerAlone.issuedByLane(node).single()
        cluster.applied("prepare the heavy newborn") { peer.node.prepare(heavy) }
        cluster.applied("activate the heavy newborn") { peer.node.activate(heavy.id) }

        val both = MixedWeightRun(root, listOf(light, heavy), MAX_GRANT, Random(2))
        var peak = 0.0
        var heavyAtSeat = 0.0
        repeat(JOINED_CALLS) {
            both.advertise(node, listOf(light, heavy))
            both.schedule(node)
            peak = maxOf(peak, node.boundMetrics(root).peakPositionFraction)
            both.chargeBelowReservation(node)
            // Right after the seat: later calls give both one grant each, which the light lane's
            // virtual time feels sixty times as much, so only the first call says where the seat was.
            // The newborn is the peak, sixty times the runner on a shared ceiling: its virtual time,
            // less the one grant it took, is the front it was seated at.
            if (both.calls == 1) {
                heavyAtSeat = node.boundMetrics(root).peakPositionFraction * SHARED_CEILING / (16.0 * 60.0) - MAX_GRANT / 60.0
            }
            if (both.calls % PUMP_EVERY == 0) cluster.pump(1L)
        }
        cluster.pump()
        val ledger = node.ledger.value
        val heavyIssued = ledger.edge(heavy.id)?.issued ?: 0L
        val lightSince = (ledger.edge(light.id)?.issued ?: 0L) - lightAlone
        println("[${started.elapsedNow()}] newborn: runner made ${runnerAlone.grants} grants alone; then heavy $heavyIssued, light $lightSince, peak $peak")
        assertAll(
            { assertEquals(RUNNER_CALLS, runnerAlone.calls, "the rig: the runner ran alone for every call") },
            {
                val onTheNewbornsGrid = runnerAlone.grants.toDouble() * MAX_GRANT * 60.0 * 16.0
                assertTrue(onTheNewbornsGrid > Long.MAX_VALUE.toDouble(), "the rig: the runner's service on the newborn's grid is past a Long")
            },
            { assertEquals(JOINED_CALLS, both.calls, "the two ran together for every call") },
            { assertTrue(peak >= 1.0, "the rig: the newborn passed its ceiling: $peak") },
            { assertEquals(JOINED_CALLS * MAX_GRANT, heavyIssued, "the newborn was granted on every call") },
            { assertEquals(JOINED_CALLS * MAX_GRANT, lightSince, "and so was the runner") },
            {
                assertTrue(
                    kotlin.math.abs(heavyAtSeat - lightAlone.toDouble()) <= LEVEL_SLACK.toDouble(),
                    "the newborn was seated level with the runner, not behind it: $heavyAtSeat against $lightAlone",
                )
            },
            { assertConverged(cluster) },
        )
    }

    /**
     * A heavy newborn, weight 65,534, joins two light runners that drifted leases apart (#3353, the
     * window rule), on the governed node, whose first round is not `LocalHeddle`'s path. Written for
     * the raise the window rule bounded, which seated the newborn short of its ceiling. Nothing
     * rebases now (#3472): the newborn is seated at the front on its own grid, far past its ceiling,
     * and is granted all the same, since a pick orders the wide position.
     * `LocalHeddleWindowRuleTest` holds the rows where the runners stay asking.
     */
    @Test
    fun aHeavyNewbornJoiningTwoLightsALeaseApartIsGrantedPastItsCeiling() = runTest(
        StandardTestDispatcher(),
        timeout = TEST_WEDGE_BACKSTOP,
    ) {
        val lights = draft("root") {
            lane("l1", Weight.ONE)
            lane("l2", Weight.ONE)
        }.lower()
        val heavy = AttachmentRecord(AttachmentId("root/h"), root, GroupId("root/h"), Weight.of(65_534L))
        val cluster = bootedCluster(mint = mapOf(0 to SUPPLY), lanes = lights)
        val peer = cluster.peers[0]
        val node = peer.node.asSchedulingPeer()
        val apart = MixedWeightRun(root, lights, MAX_GRANT, Random(3353))
        // Nothing charged: a lease held is service, so the runners stand whole leases apart.
        repeat(LIGHT_CALLS) {
            apart.advertise(node, apart.drawWaiting())
            apart.schedule(node)
            if (apart.calls % PUMP_EVERY == 0) cluster.pump(1L)
        }
        cluster.pump()
        val (l1At, l2At) = lights.map { checkNotNull(node.ledger.value.virtualService(it.id)) }
        val spread = if (l1At > l2At) l1At - l2At else l2At - l1At
        cluster.applied("prepare the heavy newborn") { peer.node.prepare(heavy) }
        cluster.applied("activate the heavy newborn") { peer.node.activate(heavy.id) }
        val all = lights + heavy
        val joined = MixedWeightRun(root, all, MAX_GRANT, Random(3354))
        var peak = 0.0
        repeat(JOINED_LIGHT_CALLS) {
            joined.advertise(node, if (joined.calls == 0) all else joined.drawWaiting())
            joined.schedule(node)
            peak = maxOf(peak, node.boundMetrics(root).peakPositionFraction)
            joined.chargeBelowReservation(node)
            if (joined.calls % PUMP_EVERY == 0) cluster.pump(1L)
        }
        cluster.pump()
        assertAll(
            { assertTrue(spread >= Rational.of(MAX_GRANT), "the rig: the runners stood a lease apart or more, $spread") },
            { assertTrue(peak >= 1.0, "the rig: the newborn passed its ceiling: $peak") },
            { assertTrue((node.ledger.value.edge(heavy.id)?.issued ?: 0L) > 0L, "the newborn was granted") },
            { assertConverged(cluster) },
        )
    }

    /**
     * Two schedulers at once, cut off from each other for a stretch, then healed. Their picks may
     * differ from any one engine's, so this run checks what replication promises instead: the §8.2
     * bound pieces stay ordered on both sides while they are apart, the three peers converge after,
     * and every minted unit is accounted for, with both schedulers' peak effective positions past
     * the old ceiling, where nothing rebases them back (#3472).
     */
    @Test
    fun twoSchedulersAcrossAPartitionConvergeAndConserve() = runTest(
        StandardTestDispatcher(),
        timeout = TEST_WEDGE_BACKSTOP,
    ) {
        val started = TimeSource.Monotonic.markNow()
        val cluster = bootedCluster(mint = mapOf(0 to SUPPLY, 1 to SUPPLY))
        val nodes = cluster.peers.take(2).map { it.node.asSchedulingPeer() }
        val runs = listOf(MixedWeightRun(root, lanes, MAX_GRANT, Random(11)), MixedWeightRun(root, lanes, MAX_GRANT, Random(12)))
        val boundBreaches = ArrayList<String>()
        var deviationWhileApart = 0L
        val peaks = DoubleArray(nodes.size)

        fun phase(calls: Int, apart: Boolean) = repeat(calls) { call ->
            for (i in nodes.indices) {
                runs[i].step(nodes[i])
                peaks[i] = maxOf(peaks[i], nodes[i].boundMetrics(root).peakPositionFraction)
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
        cluster.healDataPlane(1)
        cluster.pump(800)
        cluster.pump(800)
        phase(TOGETHER_CALLS, apart = false)
        cluster.pump(800)
        cluster.pump(800)

        val merged = nodes[0].ledger.value
        val totalGrants = runs.sumOf { it.grants }
        println("[${started.elapsedNow()}] two schedulers: ${runs.map { it.grants }} grants, peaks ${peaks.toList()}")
        assertAll(
            { assertEquals(List(2) { 2 * TOGETHER_CALLS + APART_CALLS }, runs.map { it.calls }, "each scheduler made every call") },
            { assertTrue(totalGrants >= LONG_RUN_GRANTS, "the run went far past the old ceiling: $totalGrants grants") },
            { assertTrue(apartLedgersDiffer, "the rig: the partition was real, the two views diverged") },
            { assertTrue(deviationWhileApart > 0L, "the rig: there was a deviation for the bound to bound") },
            { assertEquals(emptyList<String>(), boundBreaches, "the §8.2 pieces stayed ordered on both sides while apart") },
            { assertTrue(peaks.all { it >= 1.0 }, "the rig: both schedulers ran past the old ceiling: ${peaks.toList()}") },
            { assertConverged(cluster) },
            { assertNull(merged.conservationShortfall(root, cluster.everyReplica), "every minted unit is accounted for") },
            { assertTrue(nodes.all { it.boundMetrics(root).isConsistent }, "and the §8.2 pieces are ordered after the heal") },
        )
    }

    /**
     * A scheduler crashes mid-run and restarts with no wake memory, and the child that idled through
     * the whole run comes back level with the one that ran (design §7.2, the restart rule). Before
     * the crash only the lightest child, weight 1, ran, in bursts; the heaviest, weight 60, idled
     * throughout, so it sits far behind the runner, and it is demanding from the restarted
     * scheduler's first call. The fresh allocator has observed no child, and a seated child it has
     * never observed that it finds demanding is a waker: every child is joining, so each is clamped
     * level with the furthest-ahead one. Without the rule the idle child would keep the whole run as
     * credit and take every slot it is eligible for until it caught up.
     *
     * Nothing rebases, so no child ever stands below zero, and only the restart rule can level the
     * idle child: the rig asserts that, and that the idle child read far behind the runner before
     * the boot call, which is what makes "level" mean something. The runs are short enough that
     * every position stays inside the window the public reads hold it in.
     */
    @Test
    fun aRestartedSchedulerWithALongIdleChildDemandingAtBootComesBackLevel() = runTest(
        StandardTestDispatcher(),
        timeout = TEST_WEDGE_BACKSTOP,
    ) {
        val run = restartRun(callsBefore = RESTART_CALLS_BEFORE, callsAfter = RESTART_CALLS_AFTER)
        assertAll(
            *run.levelAndConserved(),
            {
                assertTrue(
                    run.idleAtRestart >= Rational.ZERO,
                    "the rig: the idle child never stood below zero, so only the restart rule moves it: ${run.idleAtRestart}",
                )
            },
        )
    }

    /** What [restartRun] measured: the rig's witnesses and the level the idle child came back at. */
    private class RestartRun(
        val plannedBefore: Int,
        val plannedAfter: Int,
        val callsBefore: Int,
        val callsAfter: Int,
        val idleIssuedBefore: Long,
        val freshReplica: Boolean,
        /** The idle child's effective virtual service on the restarted scheduler, before its first call. */
        val idleAtRestart: Rational,
        /** The runner's, read the same way. */
        val lightAtRestart: Rational,
        val atBoot: ScheduleOutcome,
        val idleLevel: Rational,
        val lightLevel: Rational,
        val boundAtBoot: BoundMetrics,
        val boundAfter: BoundMetrics,
        val converged: () -> Unit,
        val shortfall: String?,
    ) {
        /** The rig ran, and the idle child came back level. */
        fun levelAndConserved(): Array<() -> Unit> = arrayOf(
            { assertEquals(plannedBefore, callsBefore, "the run before the restart made every call") },
            { assertEquals(plannedAfter + 1, callsAfter, "and the run after it made every call") },
            { assertEquals(0L, idleIssuedBefore, "the rig: the heavy child idled through the whole run before the crash") },
            { assertTrue(freshReplica, "the rig: the restarted process is a fresh replica") },
            {
                assertTrue(
                    lightAtRestart - idleAtRestart > Rational.of(LEVEL_SLACK),
                    "the rig: before the boot call the idle child read far behind the runner: $idleAtRestart against $lightAtRestart",
                )
            },
            { assertIs<ScheduleOutcome.Delegated>(atBoot, "the boot call, with the long-idle child demanding, delegates") },
            { assertLevel(idleLevel, lightLevel, "the long-idle child came back level with the runner, not behind it") },
            { assertTrue(boundAtBoot.isConsistent, "the §8.2 pieces are ordered after the boot call: $boundAtBoot") },
            { assertTrue(boundAfter.isConsistent, "and after the run: $boundAfter") },
            converged,
            { assertNull(shortfall, "every minted unit is accounted for") },
        )
    }

    /**
     * The restart run: light bursts on one scheduler, which
     * then crashes with its demand withdrawn; the restarted process mints, catches up on the ledger,
     * and makes its boot call with every lane waiting, the long-idle one included; then a run of
     * random calls, and gossip to settle.
     */
    private suspend fun TestScope.restartRun(callsBefore: Int, callsAfter: Int): RestartRun {
        val started = TimeSource.Monotonic.markNow()
        val cluster = bootedCluster(mint = mapOf(1 to SUPPLY), lanes = spreadLanes)
        val idle = spreadLanes.single { it.weight == Weight.of(60L) }
        val light = spreadLanes.single { it.weight == Weight.ONE }
        val before = MixedWeightRun(root, spreadLanes, MAX_GRANT, Random(21), drawFrom = listOf(light), grantsPerCall = BURST_GRANTS)
        val first = cluster.peers[1].node.asSchedulingPeer()
        repeat(callsBefore) { call ->
            before.step(first)
            if (call % PUMP_EVERY == 0) cluster.pump(1L)
        }
        // Its demand goes with it: the restarted process advertises its own.
        for (lane in spreadLanes) first.advertise(lane.id, Demand.NONE)
        cluster.pump()
        val settledBefore = cluster.peers[0].node.ledger.value
        val idleIssuedBefore = first.ledger.value.edge(idle.id)?.issued ?: 0L

        cluster.restart(1)
        val restarted = cluster.peers[1]
        cluster.applied("mint to the restarted scheduler") { restarted.node.mint(restarted.replica, SUPPLY) }
        var catchUpPumps = 0
        while (!restarted.node.ledger.value.caughtUpWith(settledBefore, light) && catchUpPumps < CATCH_UP_PUMPS) {
            cluster.pump()
            catchUpPumps++
        }
        check(restarted.node.ledger.value.caughtUpWith(settledBefore, light)) { "the restarted scheduler caught up within $CATCH_UP_PUMPS pumps" }
        // Read before any call, so with no wake memory: each child's raw position.
        val idleAtRestart = restarted.node.effectiveVirtualService(idle, spreadLanes)
        val lightAtRestart = restarted.node.effectiveVirtualService(light, spreadLanes)

        val after = MixedWeightRun(root, spreadLanes, MAX_GRANT, Random(22))
        val second = restarted.node.asSchedulingPeer()
        // The first call after the boot: every lane waits, the long-idle one included.
        after.advertise(second, spreadLanes)
        val atBoot = after.schedule(second)
        after.chargeBelowReservation(second)
        val boundAtBoot = second.boundMetrics(root)
        val idleLevel = restarted.node.effectiveVirtualService(idle, spreadLanes)
        val lightLevel = restarted.node.effectiveVirtualService(light, spreadLanes)
        repeat(callsAfter) { call ->
            after.step(second)
            if (call % PUMP_EVERY == 0) cluster.pump(1L)
        }
        cluster.pump(800)
        cluster.pump(800)
        val merged = cluster.peers[0].node.ledger.value
        println(
            "[${started.elapsedNow()}] restart: ${before.grants} grants before, ${after.grants} after, " +
                "idle at $idleAtRestart against $lightAtRestart, then $idleLevel against $lightLevel",
        )
        return RestartRun(
            plannedBefore = callsBefore,
            plannedAfter = callsAfter,
            callsBefore = before.calls,
            callsAfter = after.calls,
            idleIssuedBefore = idleIssuedBefore,
            freshReplica = restarted.replica != first.self,
            idleAtRestart = idleAtRestart,
            lightAtRestart = lightAtRestart,
            atBoot = atBoot,
            idleLevel = idleLevel,
            lightLevel = lightLevel,
            boundAtBoot = boundAtBoot,
            boundAfter = second.boundMetrics(root),
            converged = { assertConverged(cluster) },
            shortfall = merged.conservationShortfall(root, cluster.everyReplica),
        )
    }

    /** Whether this ledger holds [light]'s whole run from [settled]. */
    private fun EntitlementLedger.caughtUpWith(settled: EntitlementLedger, light: AttachmentRecord): Boolean =
        (edge(light.id)?.issued ?: 0L) >= (settled.edge(light.id)?.issued ?: 0L)

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

    /**
     * [edge]'s effective virtual service, raw plus its wake clamp: with [edge] the only child of
     * [lanes] demanding, the parent's front is exactly that. Demand is advisory and only `schedule`
     * samples it, so the probe moves no wake memory; re-advertise before the next call.
     */
    private fun GovernedHeddleNode.effectiveVirtualService(edge: AttachmentRecord, lanes: List<AttachmentRecord>): Rational {
        for (lane in lanes) advertise(lane.id, if (lane == edge) Demand(Long.MAX_VALUE / 4, MAX_GRANT) else Demand.NONE)
        return checkNotNull(parentVirtualTime(root)) { "the parent has active children, so it has a front" }
    }

    private fun assertConverged(cluster: GovernedCluster) {
        val ledgers = cluster.peers.map { it.node.ledger.value }
        assertEquals(1, ledgers.distinct().size, "every peer holds the same ledger")
    }

    private companion object {
        /** [a] and [b] within [LEVEL_SLACK] of virtual time, either way. */
        fun assertLevel(a: Rational, b: Rational, message: String) {
            val slack = Rational.of(LEVEL_SLACK)
            assertTrue(a - b <= slack && b - a <= slack, "$message: $a against $b")
        }

        /** The largest grant: [LocalHeddle.MAX_RESERVATION], the deadline headroom a position keeps. */
        const val MAX_GRANT: Long = LocalHeddle.MAX_RESERVATION

        /** One scheduler's supply, and the §8.2 cap `E`: 2⁵⁶, 4,096 grants of [MAX_GRANT]. Four peers' caps fit a `Long`. */
        const val SUPPLY: Long = 1L shl 56

        /** How far a run goes: ten times the grants after which a static node used to refuse. */
        const val LONG_RUN_GRANTS: Int = 10 * GRANTS_BEFORE_THE_CEILING

        /** Calls the match run makes, one grant each: past the old ceiling. */
        const val MATCH_CALLS: Int = 180

        /** Calls each scheduler makes before the partition and again after it heals. */
        const val TOGETHER_CALLS: Int = 140

        /**
         * Calls each scheduler makes while the two are apart. The heal costs grow with the calls made
         * apart: the first pump after it took 15 ms after none, 0.25 s after 8, 0.8 s after 16 and 1.6 s after
         * 32 on the JVM, about ten times that on Kotlin/Native, and 250 exhausted a 512 MB test JVM (#3424).
         * Eight is enough for the two views to diverge, which the arm asserts.
         */
        const val APART_CALLS: Int = 8

        /**
         * Calls the restart run makes before the crash, light bursts only, and after the boot call.
         * Its 256 grants put the runner about `2⁵²` units of virtual time ahead, under the
         * `2⁶³ / (60 · 16)` ticks the heavy child's public read is held at, so the level the boot
         * call clamps it to reads exactly.
         */
        const val RESTART_CALLS_BEFORE: Int = 32
        const val RESTART_CALLS_AFTER: Int = 100

        /**
         * The light runner's calls alone, at [RUNNER_GRANTS] each: 1,600 grants, past a `Long` on the
         * newborn's grid and short of a sixteenth of the runner's own ceiling. Then its calls beside
         * the newborn.
         */
        const val RUNNER_CALLS: Int = 400
        const val RUNNER_GRANTS: Int = 4
        const val JOINED_CALLS: Int = 400

        /** The two light runners' calls before the heavy newborn joins, and the calls after. */
        const val LIGHT_CALLS: Int = 64
        const val JOINED_LIGHT_CALLS: Int = 64

        /**
         * The spread run's rounds: a burst of [BURST_CALLS] calls of [BURST_GRANTS] grants each to
         * the light child alone, then [MIX_CALLS] calls with every child drawn at random.
         */
        const val SPREAD_ROUNDS: Int = 3
        const val BURST_CALLS: Int = 100
        const val BURST_GRANTS: Int = 8
        const val MIX_CALLS: Int = 100

        /** The position ceiling of a weight whose denominator is 1, in ticks, as a `Double`. */
        const val SHARED_CEILING: Double = (Long.MAX_VALUE - MAX_GRANT * 16L).toDouble()

        /** How far apart two children may read and still be level: two of the largest grants of virtual time. */
        const val LEVEL_SLACK: Long = 2L * MAX_GRANT

        /** Every so many calls, a run lets gossip move. */
        const val PUMP_EVERY: Int = 16

        /** Pumps a restarted peer gets to catch up on the ledger. */
        const val CATCH_UP_PUMPS: Int = 20

        const val MAX_REPORTED: Int = 8
    }
}
