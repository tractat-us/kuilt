@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.warp.heddle

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.heddle.AttachmentId
import us.tractat.kuilt.heddle.AttachmentRecord
import us.tractat.kuilt.heddle.Demand
import us.tractat.kuilt.heddle.GroupId
import us.tractat.kuilt.heddle.HeddleConfig
import us.tractat.kuilt.heddle.HeddleNode
import us.tractat.kuilt.heddle.PolicyConfig
import us.tractat.kuilt.heddle.Weight
import us.tractat.kuilt.heddle.heddleStatic
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.drainAntiEntropy
import us.tractat.kuilt.warp.Affinity
import us.tractat.kuilt.warp.CapSet
import us.tractat.kuilt.warp.ClaimStrategy
import us.tractat.kuilt.warp.Op
import us.tractat.kuilt.warp.OpId
import us.tractat.kuilt.warp.OpRegistry
import us.tractat.kuilt.warp.TaskDescriptor
import us.tractat.kuilt.warp.TaskId
import us.tractat.kuilt.warp.WarpNode
import us.tractat.kuilt.warp.and
import us.tractat.kuilt.warp.where
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * **H8 acceptance — eligibility has ZERO effect on the ledger's invariants (orthogonality).**
 *
 * Location eligibility (design §14.6, Model A) is a *placement-side* overlay: it decides *where*
 * a task may run, never *how much* entitlement it spends. It introduces no conserved quantity, so
 * it cannot touch the `:kuilt-heddle` ledger's conservation. This suite runs a lane-tagged,
 * fair-share-gated warp workload with eligibility **active** (an [Affinity] plus a matching
 * capability advertisement) and proves the entitlement ledger behaves exactly as it does with no
 * affinity at all: the same tasks are charged, conservation holds (`validate()` is empty), and the
 * per-lane spend is unchanged.
 *
 * Same discipline as [HeddleAdmissionControlTest]: [StandardTestDispatcher], node coroutines on
 * [TestScope.backgroundScope], seeded RNG, bounded [drainAntiEntropy], and [WEDGE_BACKSTOP] as the
 * wall-clock ceiling — a wedge detector, deliberately not a tight budget (see its KDoc; #1891).
 */
class EligibilityLedgerOrthogonalityTest {

    private val root = GroupId("root")
    private val laneA = GroupId("laneA") // weight 3
    private val laneB = GroupId("laneB") // weight 1
    private val eA = AttachmentId("root->laneA")
    private val eB = AttachmentId("root->laneB")
    private val echoOp = OpId("echo")
    private val hungry = Demand(targetOutstanding = 1_000L, maximumUsefulGrant = 1_000L)
    private val gpuAffinity = Affinity.has("GPU") and Affinity.attr("region", "us-east")
    private val gpuUsEast = CapSet(tokens = setOf("GPU"), attributes = mapOf("region" to "us-east"))

    private val warpQuilterConfig = QuilterConfig(
        antiEntropyInterval = 100.milliseconds,
        fullStateRetryInterval = 150.milliseconds,
        expectVirtualTime = true,
    )

    private fun schedulerClock(scheduler: TestCoroutineScheduler): () -> Instant =
        { Instant.fromEpochMilliseconds(scheduler.currentTime) }

    private fun heddleConfig(seed: Int) = HeddleConfig(
        policy = PolicyConfig(quantum = 10L),
        maxHoldingsPerPeer = 1_000_000L,
        demandTtl = 30.seconds,
        quilter = QuilterConfig(antiEntropyInterval = 100.milliseconds, fullStateRetryLimit = 0, expectVirtualTime = true),
        heartbeat = us.tractat.kuilt.liveness.HeartbeatConfig(),
        random = Random(seed),
    )

    private fun threeToOneTopology() = listOf(
        AttachmentRecord(eA, root, laneA, Weight.of(3), 0L),
        AttachmentRecord(eB, root, laneB, Weight.of(1), 0L),
    )

    private class Recorder {
        private val lock = reentrantLock()
        private val ids = mutableListOf<TaskId>()
        fun add(id: TaskId) { lock.withLock { ids.add(id) } }
        fun snapshot(): List<TaskId> = lock.withLock { ids.toList() }
    }

    private fun recordingRegistry(recorder: Recorder) =
        OpRegistry().also { r -> r.register(echoOp, Op { args -> recorder.add(TaskId(args.decodeToString())); args }) }

    private fun CoroutineScope.fairShareNode(seam: Seam, clock: () -> Instant, mint: Long, seed: Int): HeddleNode {
        val self = ReplicaId(seam.selfId.value)
        val node = heddleStatic(
            seam = seam, self = self, root = root, mint = mapOf(self to mint),
            topology = threeToOneTopology(), clock = clock, config = heddleConfig(seed), epoch = 0L,
        )
        node.advertise(eA, hungry)
        node.advertise(eB, hungry)
        node.schedule(root)
        return node
    }

    private class Outcome(val doneA: Int, val doneB: Int, val conflicts: Int, val holdingsA: Long, val holdingsB: Long)

    /** Run the 3:1 lane workload once; when [affinity] is not [Affinity.Anywhere] the sole warp
     *  peer advertises a matching capability so the same tasks stay eligible. */
    private suspend fun TestScope.runLaneWorkload(clock: () -> Instant, seed: Int, affinity: Affinity): Outcome {
        val heddleSeam = InMemoryLoom().host(Pattern("h8-orthogonality-heddle-$seed-${affinity.hashCode()}"))
        val heddle = backgroundScope.fairShareNode(heddleSeam, clock, mint = 40L, seed = seed)
        val holdingsA = heddle.ledger.value.holdings(laneA, heddle.self)
        val holdingsB = heddle.ledger.value.holdings(laneB, heddle.self)

        val warpSeam = InMemoryLoom().host(Pattern("h8-orthogonality-warp-$seed-${affinity.hashCode()}"))
        val roster = MutableStateFlow(setOf(warpSeam.selfId))
        val recorder = Recorder()
        val node = WarpNode(
            selfId = warpSeam.selfId, seam = warpSeam, rosterFlow = roster, scope = backgroundScope,
            quilterConfig = warpQuilterConfig, clock = clock, strategy = ClaimStrategy.Ring,
            registry = recordingRegistry(recorder), admissionControl = HeddleAdmissionControl(heddle),
            epoch = 0L,
        )
        if (affinity != Affinity.Anywhere) {
            node.advertiseCapabilities(gpuUsEast)
            drainAntiEntropy(warpQuilterConfig.antiEntropyInterval, rounds = 2, settleWindow = 0.milliseconds)
        }

        val perLane = 80
        repeat(perLane) { i -> node.enqueue(TaskId("laneA-$i"), TaskDescriptor(echoOp, "laneA-$i".encodeToByteArray()).inLane("laneA").where(affinity)) }
        repeat(perLane) { i -> node.enqueue(TaskId("laneB-$i"), TaskDescriptor(echoOp, "laneB-$i".encodeToByteArray()).inLane("laneB").where(affinity)) }
        drainAntiEntropy(warpQuilterConfig.antiEntropyInterval, rounds = 8, settleWindow = 0.milliseconds)

        val done = recorder.snapshot()
        return Outcome(
            doneA = done.count { it.value.startsWith("laneA-") },
            doneB = done.count { it.value.startsWith("laneB-") },
            conflicts = heddle.ledger.value.validate().size,
            holdingsA = holdingsA,
            holdingsB = holdingsB,
        )
    }

    @Test
    fun eligibilityHasZeroEffectOnLedgerInvariants() = runTest(StandardTestDispatcher(), timeout = WEDGE_BACKSTOP) {
        val clock = schedulerClock(testScheduler)

        // Arm A: eligibility ACTIVE — an affinity predicate + a matching capability.
        val withEligibility = runLaneWorkload(clock, seed = 21, affinity = gpuAffinity)
        // Arm B: no eligibility — the identical workload with Affinity.Anywhere (today's placement).
        val noEligibility = runLaneWorkload(clock, seed = 21, affinity = Affinity.Anywhere)

        assertAll(
            // Conservation holds with eligibility active — the whole point of orthogonality.
            { assertEquals(0, withEligibility.conflicts, "ledger conservation intact with eligibility active (validate() empty)") },
            // The ledger charged EXACTLY the delegated entitlement — eligibility changed nothing.
            { assertEquals(withEligibility.holdingsA, withEligibility.doneA.toLong(), "laneA charged exactly its entitlement, unaffected by eligibility") },
            { assertEquals(withEligibility.holdingsB, withEligibility.doneB.toLong(), "laneB charged exactly its entitlement, unaffected by eligibility") },
            // Bit-for-bit identical ledger effect to the no-affinity run.
            { assertEquals(noEligibility.doneA, withEligibility.doneA, "laneA completions identical with and without eligibility") },
            { assertEquals(noEligibility.doneB, withEligibility.doneB, "laneB completions identical with and without eligibility") },
            { assertEquals(0, noEligibility.conflicts, "baseline ledger also conflict-free") },
            { assertTrue(withEligibility.doneA + withEligibility.doneB > 0, "the workload actually ran under eligibility") },
        )
    }
}
