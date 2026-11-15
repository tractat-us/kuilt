package us.tractat.kuilt.heddle

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.math.Int128
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
     * A governed cluster with the rebase switched off passes its position ceiling and still makes
     * the same picks as one with it on, call for call, through the whole supply: a pick orders the
     * full 128-bit position, and a rebase moves every child alike, so neither changes the order. The
     * twin used to answer [ScheduleOutcome.PositionCeiling] part-way through, and the rebasing
     * cluster then ran on alone to ten times its grants; now neither refuses, the twin's peak
     * effective position passes its ceiling while the rebasing cluster's stays far below, and the
     * other two peers converge on the rebasing cluster's ledger.
     */
    @Test
    fun aNoRebaseTwinPassesItsCeilingAndPicksAsTheRebasingClusterCallForCall() = runTest(
        StandardTestDispatcher(),
        timeout = TEST_WEDGE_BACKSTOP,
    ) {
        val started = TimeSource.Monotonic.markNow()
        val rebasing = bootedCluster(mint = mapOf(0 to SUPPLY))
        val twin = bootedCluster(mint = mapOf(0 to SUPPLY))
        twin.peers[0].node.rebaseForTest = false
        val node = rebasing.peers[0].node.asSchedulingPeer()
        val twinNode = twin.peers[0].node.asSchedulingPeer()
        val a = MixedWeightRun(root, lanes, MAX_GRANT, Random(3353))
        val b = MixedWeightRun(root, lanes, MAX_GRANT, Random(3353))

        var peak = 0.0
        var twinPeak = 0.0
        var twinPassedAtGrant = -1
        while (a.calls < TWIN_CALL_LIMIT) {
            val outcome = a.step(node)
            val twinOutcome = b.step(twinNode)
            assertTrue(outcome !is ScheduleOutcome.PositionCeiling, "refused at the ceiling after ${a.grants} grants")
            assertTrue(twinOutcome !is ScheduleOutcome.PositionCeiling, "the twin refused at the ceiling after ${b.grants} grants")
            assertEquals(b.issuedByLane(twinNode), a.issuedByLane(node), "call ${a.calls}: the two made different picks")
            twinPeak = maxOf(twinPeak, twinNode.boundMetrics(root).peakPositionFraction)
            if (twinPassedAtGrant < 0 && twinPeak >= 1.0) twinPassedAtGrant = b.grants
            if (a.calls % PUMP_EVERY == 0) {
                rebasing.pump(1L)
                peak = maxOf(peak, node.boundMetrics(root).peakPositionFraction)
            }
        }
        rebasing.pump()
        rebasing.pump()
        val merged = rebasing.peers[0].node.ledger.value
        println(
            "[${started.elapsedNow()}] acceptance: the twin passed its ceiling at grant $twinPassedAtGrant, peak $twinPeak; both made " +
                "${a.grants} grants in ${a.calls} calls, the rebasing cluster with ${node.rebases()} rebases, peak $peak",
        )
        assertAll(
            { assertTrue(twinPassedAtGrant in 1 until a.grants, "the rig: the twin passed its ceiling with grants still to come ($twinPassedAtGrant of ${a.grants})") },
            { assertEquals(a.grants, b.grants, "the twin granted every grant the rebasing cluster did") },
            { assertEquals(SUPPLY / MAX_GRANT, a.grants.toLong(), "the rig: the whole supply was granted") },
            { assertEquals(0, twinNode.rebases(), "the rig: the twin never rebased") },
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
        println("[${started.elapsedNow()}] match: $MATCH_CALLS calls, ${node.rebases()} governed rebases, origins ${node.ledger.value.origin(root)} and ${local.snapshot().ledger.origin(root)}")
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
        println("[${started.elapsedNow()}] spread: $grants grants (${burst.grants} light alone), ${node.rebases()} rebases, peak $peak")
        assertAll(
            { assertEquals(SPREAD_ROUNDS * (BURST_CALLS + MIX_CALLS), burst.calls + mix.calls, "the run made every call") },
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
     * with the peak effective position, read after every call, below the ceiling.
     */
    @Test
    fun aHeavyNewbornJoiningABusyLightRunnerNeverMeetsTheCeiling() = runTest(
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
        val rebasesAlone = node.rebases()
        val lightAlone = runnerAlone.issuedByLane(node).single()
        cluster.applied("prepare the heavy newborn") { peer.node.prepare(heavy) }
        cluster.applied("activate the heavy newborn") { peer.node.activate(heavy.id) }

        val both = MixedWeightRun(root, listOf(light, heavy), MAX_GRANT, Random(2))
        var peak = 0.0
        var heavyLevel = Rational.ZERO
        var lightLevel = Rational.ZERO
        repeat(JOINED_CALLS) {
            both.advertise(node, listOf(light, heavy))
            both.schedule(node)
            peak = maxOf(peak, node.boundMetrics(root).peakPositionFraction)
            both.chargeBelowReservation(node)
            // Right after the seat: later calls give both one grant each, which the light lane's
            // virtual time feels sixty times as much, so only the first call says where the seat was.
            if (both.calls == 1) {
                heavyLevel = peer.node.effectiveVirtualService(heavy, listOf(light, heavy))
                lightLevel = peer.node.effectiveVirtualService(light, listOf(light, heavy))
            }
            if (both.calls % PUMP_EVERY == 0) cluster.pump(1L)
        }
        cluster.pump()
        val ledger = node.ledger.value
        val heavyIssued = ledger.edge(heavy.id)?.issued ?: 0L
        val lightSince = (ledger.edge(light.id)?.issued ?: 0L) - lightAlone
        println("[${started.elapsedNow()}] newborn: runner made ${runnerAlone.grants} grants alone, $rebasesAlone rebases; then heavy $heavyIssued, light $lightSince, peak $peak")
        assertAll(
            { assertEquals(RUNNER_CALLS, runnerAlone.calls, "the rig: the runner ran alone for every call") },
            { assertEquals(0, rebasesAlone, "the rig: the runner alone stayed short of a rebase, so only the newborn's arrival moves the origin") },
            {
                val onTheNewbornsGrid = runnerAlone.grants.toDouble() * MAX_GRANT * 60.0 * 16.0
                assertTrue(onTheNewbornsGrid > Long.MAX_VALUE.toDouble(), "the rig: the runner's service on the newborn's grid is past a Long")
            },
            { assertEquals(JOINED_CALLS, both.calls, "the two ran together for every call") },
            { assertTrue(peak > 0.0 && peak < 1.0, "the peak effective position stayed below the ceiling: $peak") },
            { assertEquals(JOINED_CALLS * MAX_GRANT, heavyIssued, "the newborn was granted on every call") },
            { assertEquals(JOINED_CALLS * MAX_GRANT, lightSince, "and so was the runner") },
            { assertLevel(heavyLevel, lightLevel, "the newborn was seated level with the runner, not behind it") },
            { assertConverged(cluster) },
        )
    }

    /**
     * A heavy newborn, weight 65,534, joins two light runners that drifted leases apart (#3353, the
     * window rule), on the governed node, whose first round raises before its seat step
     * (`preSeatRaise ?: prePickRaise`, not `LocalHeddle`'s path). A guard, not a red: `schedule` fills
     * each runner's demand every call, so when the newborn joins they ask again as returners, and
     * both rules aim that raise at the front the returners land at. What this pins is that the raise,
     * now bounded by each returner's room, still seats the newborn short of its ceiling, read as the
     * peak effective position after every call, and every call after grants. `LocalHeddleWindowRuleTest` holds the red rows, where the runners stay asking.
     */
    @Test
    fun aHeavyNewbornJoiningTwoLightsALeaseApartNeverMeetsTheCeiling() = runTest(
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
        val rebasesBefore = node.rebases()
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
            { assertTrue(node.rebases() > rebasesBefore, "the rig: the newborn's arrival rebased the parent") },
            { assertTrue(peak > 0.0 && peak < 1.0, "the peak effective position stayed below the ceiling: $peak") },
            { assertTrue((node.ledger.value.edge(heavy.id)?.issued ?: 0L) > 0L, "the newborn was granted") },
            { assertConverged(cluster) },
        )
    }

    /**
     * Two schedulers at once, cut off from each other for a stretch, then healed. Their picks may
     * differ from any one engine's, so this run checks what replication promises instead: the §8.2
     * bound pieces stay ordered on both sides while they are apart, the three peers converge after,
     * every minted unit is accounted for, and neither scheduler's peak effective position reaches
     * the ceiling.
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
        val rebasesBeforeApart = nodes.map { it.rebases() }
        phase(APART_CALLS, apart = true)
        val rebasesWhileApart = nodes.mapIndexed { i, n -> n.rebases() - rebasesBeforeApart[i] }
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
        println("[${started.elapsedNow()}] two schedulers: ${runs.map { it.grants }} grants, ${nodes.map { it.rebases() }} rebases, origin ${merged.origin(root)}")
        assertAll(
            { assertEquals(List(2) { 2 * TOGETHER_CALLS + APART_CALLS }, runs.map { it.calls }, "each scheduler made every call") },
            { assertTrue(totalGrants >= LONG_RUN_GRANTS, "the run went far past the old ceiling: $totalGrants grants") },
            { assertTrue(nodes.all { it.rebases() > 0 }, "the rig: both schedulers rebased (${nodes.map { it.rebases() }})") },
            { assertTrue(apartLedgersDiffer, "the rig: the partition was real, the two views diverged") },
            { assertTrue(rebasesWhileApart.all { it > 0 }, "the rig: both sides raised the origin while apart ($rebasesWhileApart)") },
            { assertTrue(originsApart[0] != originsApart[1], "the rig: the two raises differed, so the heal joins them: $originsApart") },
            { assertTrue(deviationWhileApart > 0L, "the rig: there was a deviation for the bound to bound") },
            { assertEquals(emptyList<String>(), boundBreaches, "the §8.2 pieces stayed ordered on both sides while apart") },
            { assertTrue(peaks.all { it > 0.0 && it < 1.0 }, "neither scheduler's peak effective position reached the ceiling: ${peaks.toList()}") },
            { assertTrue(originsApart.all { merged.origin(root) >= it }, "the merged origin is at least each side's: $originsApart") },
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
     * The rebase is off on every peer, so no child ever stands below its parent's origin. The
     * below-zero arm of the old first-sighting rule cannot fire here, and only the restart rule can
     * level the idle child: the rig asserts both, and that the idle child read far behind the runner
     * before the boot call, which is what makes "level" mean something. The runs are shorter than
     * the rebasing row's, so every position stays inside the window the public reads hold it in.
     * [aRestartedSchedulerWithALongIdleChildBelowZeroComesBackLevel] is the same run with the rebase on.
     */
    @Test
    fun aRestartedSchedulerWithALongIdleChildDemandingAtBootComesBackLevel() = runTest(
        StandardTestDispatcher(),
        timeout = TEST_WEDGE_BACKSTOP,
    ) {
        val run = restartRun(rebase = false, callsBefore = UNREBASED_CALLS_BEFORE_RESTART, callsAfter = UNREBASED_CALLS_AFTER_RESTART)
        assertAll(
            *run.levelAndConserved(),
            { assertEquals(0, run.rebasesBefore, "the rig: nothing rebased before the crash") },
            { assertEquals(0, run.rebasesAfter, "…nor after it") },
            {
                assertTrue(
                    run.idleAtRestart >= Rational.ZERO,
                    "the rig: the idle child never stood below zero, so only the restart rule moves it: ${run.idleAtRestart}",
                )
            },
        )
    }

    /**
     * [aRestartedSchedulerWithALongIdleChildDemandingAtBootComesBackLevel] with the rebase on: the
     * light child's run raises the origin past the idle child, which therefore stands below zero
     * when the scheduler restarts. The restart rule clamps it level all the same, as the old
     * below-zero arm did, which the rule takes in. Retired with the rebase.
     */
    @Test
    fun aRestartedSchedulerWithALongIdleChildBelowZeroComesBackLevel() = runTest(
        StandardTestDispatcher(),
        timeout = TEST_WEDGE_BACKSTOP,
    ) {
        val run = restartRun(rebase = true, callsBefore = BEFORE_RESTART_CALLS, callsAfter = AFTER_RESTART_CALLS)
        assertAll(
            *run.levelAndConserved(),
            { assertTrue(run.rebasesBefore > 0, "the rig: the scheduler rebased before it crashed") },
            {
                assertTrue(
                    run.idleAtRestart < Rational.ZERO,
                    "the rig: the idle child stood below zero when the scheduler restarted: ${run.idleAtRestart}",
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
        val rebasesBefore: Int,
        val rebasesAfter: Int,
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
        /** The assertions both restart rows make: the rig ran, and the idle child came back level. */
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
     * The restart run, with [rebase] on or off on every peer: light bursts on one scheduler, which
     * then crashes with its demand withdrawn; the restarted process mints, catches up on the ledger,
     * and makes its boot call with every lane waiting, the long-idle one included; then a run of
     * random calls, and gossip to settle.
     */
    private suspend fun TestScope.restartRun(rebase: Boolean, callsBefore: Int, callsAfter: Int): RestartRun {
        val started = TimeSource.Monotonic.markNow()
        val cluster = bootedCluster(mint = mapOf(1 to SUPPLY), lanes = spreadLanes)
        for (peer in cluster.peers) peer.node.rebaseForTest = rebase
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
        val rebasesBefore = first.rebases()
        val idleIssuedBefore = first.ledger.value.edge(idle.id)?.issued ?: 0L

        cluster.restart(1)
        val restarted = cluster.peers[1]
        restarted.node.rebaseForTest = rebase
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
            "[${started.elapsedNow()}] restart, rebase $rebase: ${before.grants} grants before, ${after.grants} after, " +
                "rebases $rebasesBefore then ${second.rebases()}, idle at $idleAtRestart against $lightAtRestart, then $idleLevel against $lightLevel",
        )
        return RestartRun(
            plannedBefore = callsBefore,
            plannedAfter = callsAfter,
            callsBefore = before.calls,
            callsAfter = after.calls,
            idleIssuedBefore = idleIssuedBefore,
            freshReplica = restarted.replica != first.self,
            rebasesBefore = rebasesBefore,
            rebasesAfter = second.rebases(),
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

    /** Whether this ledger holds [settled]'s origin at [root] and [light]'s whole run. */
    private fun EntitlementLedger.caughtUpWith(settled: EntitlementLedger, light: AttachmentRecord): Boolean =
        origin(root) >= settled.origin(root) && (edge(light.id)?.issued ?: 0L) >= (settled.edge(light.id)?.issued ?: 0L)

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

        /** The most calls the rebasing long run makes, so a run that stalls reds instead of hanging. */
        const val LONG_RUN_CALL_LIMIT: Int = 20_000

        /** The twin refuses well inside this many calls; the bound only stops a broken rig running forever. */
        const val TWIN_CALL_LIMIT: Int = 2_000

        /** Calls the match run makes, one grant each: past the old ceiling and many rebases. */
        const val MATCH_CALLS: Int = 180

        /** Calls each scheduler makes before the partition and again after it heals. */
        const val TOGETHER_CALLS: Int = 140

        /**
         * Calls each scheduler makes while the two are apart. The heal costs grow with the calls made
         * apart: the first pump after it took 15 ms after none, 0.25 s after 8, 0.8 s after 16 and 1.6 s after
         * 32 on the JVM, about ten times that on Kotlin/Native, and 250 exhausted a 512 MB test JVM (#3424).
         * Eight is enough for each side to raise the origin on its own, which the arm asserts.
         */
        const val APART_CALLS: Int = 8

        /**
         * Calls the restart run makes before the crash, light bursts only, and after the boot call.
         * The run before is long enough to rebase when the rebase is on, so the idle child stands
         * below zero at the restart in that row; the run after carries the restarted scheduler on
         * far enough for convergence and conservation to mean something.
         */
        const val BEFORE_RESTART_CALLS: Int = 300
        const val AFTER_RESTART_CALLS: Int = 1_200

        /**
         * The same with the rebase off. With no origin to pull the runner back, its 256 grants put it
         * about `2⁵²` units of virtual time ahead, under the `2⁶³ / (60 · 16)` ticks the heavy child's
         * public read is held at, so the level the boot call clamps it to reads exactly.
         */
        const val UNREBASED_CALLS_BEFORE_RESTART: Int = 32
        const val UNREBASED_CALLS_AFTER_RESTART: Int = 100

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

        /** How far apart two children may read and still be level: two of the largest grants of virtual time. */
        const val LEVEL_SLACK: Long = 2L * MAX_GRANT

        /** Every so many calls, a run lets gossip move. */
        const val PUMP_EVERY: Int = 16

        /** Pumps a restarted peer gets to catch up on the origin. */
        const val CATCH_UP_PUMPS: Int = 20

        const val MAX_REPORTED: Int = 8
    }
}
