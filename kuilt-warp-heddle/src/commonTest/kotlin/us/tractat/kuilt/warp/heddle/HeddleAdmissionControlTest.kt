@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    kotlinx.serialization.ExperimentalSerializationApi::class,
)

package us.tractat.kuilt.warp.heddle

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.heddle.AttachmentId
import us.tractat.kuilt.heddle.AttachmentRecord
import us.tractat.kuilt.heddle.Demand
import us.tractat.kuilt.heddle.GroupId
import us.tractat.kuilt.heddle.ControlOutcome
import us.tractat.kuilt.heddle.HeddleConfig
import us.tractat.kuilt.heddle.HeddleNode
import us.tractat.kuilt.heddle.PolicyConfig
import us.tractat.kuilt.heddle.Weight
import us.tractat.kuilt.heddle.heddleGoverned
import us.tractat.kuilt.heddle.heddleStatic
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftRole
import us.tractat.kuilt.raft.test.FakeRaftNode
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.warp.AdmissionControl
import us.tractat.kuilt.warp.ClaimStrategy
import us.tractat.kuilt.warp.Lane
import us.tractat.kuilt.warp.Op
import us.tractat.kuilt.warp.OpId
import us.tractat.kuilt.warp.OpRegistry
import us.tractat.kuilt.warp.TaskDescriptor
import us.tractat.kuilt.warp.TaskId
import us.tractat.kuilt.warp.WarpNode
import us.tractat.kuilt.test.drainAntiEntropy
import kotlin.random.Random
import kotlin.test.Test
import us.tractat.kuilt.test.assertAll
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * H6 acceptance suite (design §15 Phase 6): the `:kuilt-warp-heddle` satellite binds warp's
 * opaque [Lane] tags to `:kuilt-heddle` fair-share leaves and gates warp's **free** execution
 * path on entitlement — without warp core learning a single fair-share type.
 *
 * Discipline (repo CLAUDE.md): [StandardTestDispatcher], node coroutines on
 * [TestScope.backgroundScope], seeded RNG, bounded time advance via [drainAntiEntropy] — never
 * `advanceUntilIdle` (the Quilter anti-entropy loops re-arm forever). The wall-clock ceiling is
 * [WEDGE_BACKSTOP], a wedge detector rather than a tight budget: these trajectories are purely
 * virtual, so a tight real-time bound measures the host and nothing else (see its KDoc; #1891).
 */
class HeddleAdmissionControlTest {

    private val root = GroupId("root")
    private val laneA = GroupId("laneA") // weight 3
    private val laneB = GroupId("laneB") // weight 1
    private val eA = AttachmentId("root->laneA")
    private val eB = AttachmentId("root->laneB")
    private val echoOp = OpId("echo")
    private val hungry = Demand(targetOutstanding = 1_000L, maximumUsefulGrant = 1_000L)

    // WarpNode mux channel tags (mirrors its private companion constants).
    private val CHANNEL_QUEUE: Byte = 0x01
    private val CHANNEL_COORD_QUEUE: Byte = 0x05

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
        quilter = QuilterConfig(
            antiEntropyInterval = 100.milliseconds,
            fullStateRetryLimit = 0,
            expectVirtualTime = true,
        ),
        heartbeat = us.tractat.kuilt.liveness.HeartbeatConfig(),
        random = Random(seed),
    )

    /** root → {laneA weight 3 (leaf), laneB weight 1 (leaf)}. */
    private fun threeToOneTopology() = listOf(
        AttachmentRecord(eA, root, laneA, Weight.of(3), 0L),
        AttachmentRecord(eB, root, laneB, Weight.of(1), 0L),
    )

    /** Thread-safe tally of completed task ids (an Op may complete off the test thread). */
    private class Recorder {
        private val lock = reentrantLock()
        private val ids = mutableListOf<TaskId>()
        fun add(id: TaskId) { lock.withLock { ids.add(id) } }
        fun snapshot(): List<TaskId> = lock.withLock { ids.toList() }
        fun size(): Int = lock.withLock { ids.size }
    }

    /** A registry whose one op records every task id it completes into [recorder]. */
    private fun recordingRegistry(recorder: Recorder) =
        OpRegistry().also { r ->
            r.register(echoOp, Op { args ->
                recorder.add(TaskId(args.decodeToString()))
                args
            })
        }

    private fun TaskId.descriptor() = TaskDescriptor(op = echoOp, args = value.encodeToByteArray())

    /** Bootstrap a single-peer heddle node with the 3:1 topology and a mint, fully delegated. */
    private fun CoroutineScope.fairShareNode(
        seam: Seam,
        clock: () -> Instant,
        mint: Long,
        seed: Int,
    ): HeddleNode {
        val self = ReplicaId(seam.selfId.value)
        val node = heddleStatic(
            seam = seam,
            self = self,
            root = root,
            mint = mapOf(self to mint),
            topology = threeToOneTopology(),
            clock = clock,
            config = heddleConfig(seed),
            epoch = 0L,
        )
        node.advertise(eA, hungry)
        node.advertise(eB, hungry)
        node.schedule(root) // delegate root supply down 3:1 into the two leaves
        return node
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 1. Two lanes weighted 3:1 converge to the ratio in COMPLETED warp tasks.
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun twoLanes3To1ConvergeInCompletedTasks() = runTest(StandardTestDispatcher(), timeout = WEDGE_BACKSTOP) {
        val mint = 40L // 3:1 → 30 to laneA, 10 to laneB
        val heddleSeam = InMemoryLoom().host(Pattern("h6-heddle-3to1"))
        val clock = schedulerClock(testScheduler)
        val heddle = backgroundScope.fairShareNode(heddleSeam, clock, mint = mint, seed = 7)

        val holdingsA = heddle.ledger.value.holdings(laneA, heddle.self)
        val holdingsB = heddle.ledger.value.holdings(laneB, heddle.self)
        assertTrue(holdingsA > 0 && holdingsB > 0, "both lanes received entitlement: A=$holdingsA B=$holdingsB")

        // Single warp peer gated on the fair-share node.
        val warpSeam = InMemoryLoom().host(Pattern("h6-warp-3to1"))
        val roster = MutableStateFlow(setOf(warpSeam.selfId))
        val recorder = Recorder()
        val node = WarpNode(
            selfId = warpSeam.selfId,
            seam = warpSeam,
            rosterFlow = roster,
            scope = backgroundScope,
            quilterConfig = warpQuilterConfig,
            clock = clock,
            strategy = ClaimStrategy.Ring,
            registry = recordingRegistry(recorder),
            admissionControl = HeddleAdmissionControl(heddle),
            epoch = 0L,
        )

        // Enqueue more tasks than either lane can afford — the surplus must DEFER, not run.
        val perLane = 80
        repeat(perLane) { i -> node.enqueue(TaskId("laneA-$i"), TaskId("laneA-$i").descriptor().inLane("laneA")) }
        repeat(perLane) { i -> node.enqueue(TaskId("laneB-$i"), TaskId("laneB-$i").descriptor().inLane("laneB")) }
        drainAntiEntropy(warpQuilterConfig.antiEntropyInterval, rounds = 8, settleWindow = 0.milliseconds)

        val done = recorder.snapshot()
        val doneA = done.count { it.value.startsWith("laneA-") }
        val doneB = done.count { it.value.startsWith("laneB-") }

        val ratio = doneA.toDouble() / doneB.toDouble()
        assertAll(
            { assertEquals(holdingsA, doneA.toLong(), "laneA completes exactly its delegated entitlement") },
            { assertEquals(holdingsB, doneB.toLong(), "laneB completes exactly its delegated entitlement") },
            { assertTrue(ratio in 2.6..3.4, "completed-task ratio converges to 3:1 (was $doneA:$doneB = $ratio)") },
            { assertTrue(doneA + doneB < 2 * perLane, "surplus tasks DEFERRED, not dropped (ran ${doneA + doneB} of ${2 * perLane})") },
        )
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 2. An UNTAGGED workload reproduces today's behavior bit-for-bit.
    //    (a) wire: the default lane is omitted from the CBOR envelope entirely.
    //    (b) behavior: an untagged run gated by the heddle == the same run with no gate.
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun untaggedDefaultLaneIsBitForBitOnTheWire() {
        val untagged = TaskDescriptor(op = OpId("x"), args = "hi".encodeToByteArray())
        val tagged = untagged.inLane("L")
        val untaggedBytes = Cbor.encodeToByteArray(TaskDescriptor.serializer(), untagged)
        val taggedBytes = Cbor.encodeToByteArray(TaskDescriptor.serializer(), tagged)
        val roundTrip = Cbor.decodeFromByteArray(TaskDescriptor.serializer(), untaggedBytes)

        assertAll(
            { assertEquals(Lane.ROOT, untagged.lane, "the default lane is ROOT (no lane)") },
            { assertEquals(untagged, TaskDescriptor(op = OpId("x"), args = "hi".encodeToByteArray()), "equals unaffected by the added field") },
            // encodeDefaults=false ⇒ a default-lane field is omitted; a tagged one adds bytes.
            { assertTrue(taggedBytes.size > untaggedBytes.size, "the lane field appears on the wire only when set") },
            { assertEquals(Lane.ROOT, roundTrip.lane, "decoding a pre-lane (untagged) envelope yields the ROOT default") },
            { assertEquals(untagged, roundTrip, "untagged descriptor round-trips unchanged") },
        )
    }

    @Test
    fun untaggedWorkloadBehavesIdenticallyWithAndWithoutTheHeddle() =
        runTest(StandardTestDispatcher(), timeout = WEDGE_BACKSTOP) {
            val clock = schedulerClock(testScheduler)
            val ids = (0 until 12).map { TaskId("plain-$it") }

            // Arm A: gated by a fair-share node. Untagged tasks must bypass it entirely.
            val heddleSeam = InMemoryLoom().host(Pattern("h6-untagged-heddle"))
            val heddle = backgroundScope.fairShareNode(heddleSeam, clock, mint = 40L, seed = 11)
            val ledgerBefore = heddle.ledger.value
            val gatedDone = runUntaggedWorkload(clock, ids, HeddleAdmissionControl(heddle))
            val ledgerAfter = heddle.ledger.value

            // Arm B: no gate at all — today's warp.
            val ungatedDone = runUntaggedWorkload(clock, ids, AdmissionControl.OPEN)

            assertAll(
                { assertEquals(ids.toSet(), gatedDone, "every untagged task completed under the heddle-gated node") },
                { assertEquals(ungatedDone, gatedDone, "untagged workload identical with and without the heddle") },
                { assertEquals(ledgerBefore, ledgerAfter, "untagged tasks charge NOTHING — the ledger is untouched") },
            )
        }

    private suspend fun TestScope.runUntaggedWorkload(
        clock: () -> Instant,
        ids: List<TaskId>,
        admission: AdmissionControl,
    ): Set<TaskId> {
        val warpSeam = InMemoryLoom().host(Pattern("h6-untagged-warp-${admission.hashCode()}"))
        val roster = MutableStateFlow(setOf(warpSeam.selfId))
        val recorder = Recorder()
        val node = WarpNode(
            selfId = warpSeam.selfId,
            seam = warpSeam,
            rosterFlow = roster,
            scope = backgroundScope,
            quilterConfig = warpQuilterConfig,
            clock = clock,
            strategy = ClaimStrategy.Ring,
            registry = recordingRegistry(recorder),
            admissionControl = admission,
            epoch = 0L,
        )
        ids.forEach { node.enqueue(it, it.descriptor()) } // no .inLane → Lane.ROOT
        drainAntiEntropy(warpQuilterConfig.antiEntropyInterval, rounds = 5, settleWindow = 0.milliseconds)
        return recorder.snapshot().toSet()
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 3. Ring placement is unchanged: the owner of every task is identical with and
    //    without a lane tag — placement (the consistent-hash ring) never consults the lane.
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun ringPlacementIdenticalWithAndWithoutLane() {
        // WarpNode's placement is `descriptor.pinnedOwner ?: ring.owner(taskId)` — the lane is
        // not in that expression, so a tagged and an untagged descriptor for the same id land on
        // the same owner. Prove it against the same TaskRing WarpNode builds from a roster.
        val roster = (0 until 5).map { PeerId("peer-$it") }.toSet()
        val ring = us.tractat.kuilt.warp.RosterSnapshot(roster).toTaskRing()

        var mismatches = 0
        val ids = (0 until 500).map { TaskId("task-$it") }
        for (id in ids) {
            val untagged = id.descriptor()
            val tagged = untagged.inLane("acme/interactive")
            val ownerUntagged = untagged.pinnedOwner ?: ring.owner(id)
            val ownerTagged = tagged.pinnedOwner ?: ring.owner(id)
            if (ownerUntagged != ownerTagged) mismatches++
        }
        assertAll(
            { assertEquals(0, mismatches, "no task's ring owner changed when a lane tag was added") },
            { assertTrue(ids.map { ring.owner(it) }.toSet().size > 1, "the ring genuinely spreads tasks across peers") },
        )
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 4. Consensus spend on the free path is ZERO (message accounting): a tagged
    //    free-path workload issues no frame on warp's coordinated/consensus channel,
    //    and there is no Raft node at all.
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun freePathIssuesZeroConsensusMessages() = runTest(StandardTestDispatcher(), timeout = WEDGE_BACKSTOP) {
        val clock = schedulerClock(testScheduler)
        val heddleSeam = InMemoryLoom().host(Pattern("h6-zeroconsensus-heddle"))
        val heddle = backgroundScope.fairShareNode(heddleSeam, clock, mint = 40L, seed = 3)

        val warpSeam = InMemoryLoom().host(Pattern("h6-zeroconsensus-warp"))
        val counting = CountingSeam(warpSeam)
        val roster = MutableStateFlow(setOf(warpSeam.selfId))
        val recorder = Recorder()
        val node = WarpNode(
            selfId = warpSeam.selfId,
            seam = counting,
            rosterFlow = roster,
            scope = backgroundScope,
            quilterConfig = warpQuilterConfig,
            clock = clock,
            strategy = ClaimStrategy.Ring,
            registry = recordingRegistry(recorder),
            admissionControl = HeddleAdmissionControl(heddle),
            raftNode = null, // free path only — no consensus engine exists
            epoch = 0L,
        )

        repeat(30) { i -> node.enqueue(TaskId("t-$i"), TaskId("t-$i").descriptor().inLane("laneA")) }
        drainAntiEntropy(warpQuilterConfig.antiEntropyInterval, rounds = 6, settleWindow = 0.milliseconds)

        val done = recorder.size()
        // Warp's mux channel tags (WarpNode's private companion): 0x01 = free queue, 0x05 =
        // coordinated (consensus-bound) queue. Zero frames on 0x05 ⇒ no task ever entered the
        // consensus path; with raftNode = null there is no Raft engine to spend on regardless.
        val coordFrames = counting.framesOn(CHANNEL_COORD_QUEUE)
        val freeFrames = counting.framesOn(CHANNEL_QUEUE)
        assertAll(
            { assertTrue(done > 0, "the free path actually ran tasks ($done completed)") },
            { assertEquals(0, coordFrames, "zero frames on the coordinated/consensus channel") },
            { assertTrue(freeFrames > 0, "the free path did produce (coordination-free) traffic") },
        )
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 5. An H5 GOVERNED node composes into HeddleAdmissionControl exactly like the
    //    static node — the "heddleStatic OR governed" surface the KDoc promises. The
    //    adapter takes a data-plane FairShareExecution, so a governed node's tagged
    //    tasks are admitted (reserve) and settled (complete) off its replicated ledger,
    //    while its consensus front door is untouched by the free execution path.
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun governedNodeComposesIntoAdmissionControl() = runTest(StandardTestDispatcher(), timeout = WEDGE_BACKSTOP) {
        val clock = schedulerClock(testScheduler)
        val heddleSeam = InMemoryLoom().host(Pattern("h6-governed-heddle"))
        val self = ReplicaId(heddleSeam.selfId.value)

        // A Raft-governed node: supply and topology arrive through the consensus log, not a
        // pre-partitioned genesis. A leader FakeRaftNode commits every proposal immediately.
        val raft = FakeRaftNode(selfId = NodeId(self.value), initialRole = RaftRole.Leader)
        val governed = backgroundScope.heddleGoverned(
            seam = heddleSeam,
            self = self,
            raft = raft,
            root = root,
            clock = clock,
            config = heddleConfig(seed = 5),
            incarnation = "boot-governed-compose",
            epoch = 0L,
        )

        // Enrolling self is what opens this node's write gate (#1693): until it applies, `reserve`
        // returns null and `schedule` delegates nothing, so an unenrolled peer can never author
        // entitlement no barrier is waiting for (§13.2). Every governed consumer must do this on
        // every boot — omitting it makes the node silently schedule nothing.
        assertIs<ControlOutcome.Applied>(governed.enroll(self))

        // Mint + build the 3:1 tree through the governed control plane, then delegate down.
        assertIs<ControlOutcome.Applied>(governed.mint(self, 40L))
        assertIs<ControlOutcome.Applied>(governed.prepare(AttachmentRecord(eA, root, laneA, Weight.of(3), 0L)))
        assertIs<ControlOutcome.Applied>(governed.prepare(AttachmentRecord(eB, root, laneB, Weight.of(1), 0L)))
        assertIs<ControlOutcome.Applied>(governed.activate(eA))
        assertIs<ControlOutcome.Applied>(governed.activate(eB))
        governed.advertise(eA, hungry)
        governed.advertise(eB, hungry)
        governed.schedule(root)

        val holdingsA = governed.ledger.value.holdings(laneA, self)
        val holdingsB = governed.ledger.value.holdings(laneB, self)
        assertTrue(holdingsA > 0 && holdingsB > 0, "both lanes received governed entitlement: A=$holdingsA B=$holdingsB")

        // The bug this test pins: HeddleAdmissionControl(governed) must COMPILE and gate on the
        // governed node's data plane. Before #1664 GovernedHeddleNode was not a HeddleNode and had
        // no shared interface, so this line failed to compile.
        val warpSeam = InMemoryLoom().host(Pattern("h6-governed-warp"))
        val roster = MutableStateFlow(setOf(warpSeam.selfId))
        val recorder = Recorder()
        val node = WarpNode(
            selfId = warpSeam.selfId,
            seam = warpSeam,
            rosterFlow = roster,
            scope = backgroundScope,
            quilterConfig = warpQuilterConfig,
            clock = clock,
            strategy = ClaimStrategy.Ring,
            registry = recordingRegistry(recorder),
            admissionControl = HeddleAdmissionControl(governed),
            epoch = 0L,
        )

        val perLane = 80
        repeat(perLane) { i -> node.enqueue(TaskId("laneA-$i"), TaskId("laneA-$i").descriptor().inLane("laneA")) }
        repeat(perLane) { i -> node.enqueue(TaskId("laneB-$i"), TaskId("laneB-$i").descriptor().inLane("laneB")) }
        drainAntiEntropy(warpQuilterConfig.antiEntropyInterval, rounds = 8, settleWindow = 0.milliseconds)

        val done = recorder.snapshot()
        val doneA = done.count { it.value.startsWith("laneA-") }
        val doneB = done.count { it.value.startsWith("laneB-") }
        assertAll(
            { assertEquals(holdingsA, doneA.toLong(), "laneA completes exactly its governed entitlement") },
            { assertEquals(holdingsB, doneB.toLong(), "laneB completes exactly its governed entitlement") },
            { assertTrue(doneA + doneB < 2 * perLane, "surplus tagged tasks DEFERRED, not dropped (ran ${doneA + doneB} of ${2 * perLane})") },
        )
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Test seams
    // ─────────────────────────────────────────────────────────────────────────────

    /** Tallies outbound frames by their leading MuxSeam channel tag (the first byte). */
    private class CountingSeam(private val delegate: Seam) : Seam {
        private val lock = reentrantLock()
        private val counts = HashMap<Byte, Int>()
        override val selfId: PeerId get() = delegate.selfId
        override val peers: StateFlow<Set<PeerId>> get() = delegate.peers
        override val state: StateFlow<SeamState> get() = delegate.state
        override val incoming: Flow<Swatch> get() = delegate.incoming
        override suspend fun broadcast(payload: ByteArray) {
            if (payload.isNotEmpty()) lock.withLock { counts[payload[0]] = (counts[payload[0]] ?: 0) + 1 }
            delegate.broadcast(payload)
        }
        override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
            if (payload.isNotEmpty()) lock.withLock { counts[payload[0]] = (counts[payload[0]] ?: 0) + 1 }
            delegate.sendTo(peer, payload)
        }
        override suspend fun close(reason: CloseReason): Unit = delegate.close(reason)
        fun framesOn(tag: Byte): Int = lock.withLock { counts[tag] ?: 0 }
    }
}
