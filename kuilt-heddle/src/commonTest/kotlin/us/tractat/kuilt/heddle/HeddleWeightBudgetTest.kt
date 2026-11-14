package us.tractat.kuilt.heddle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.NamedMux
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.quilter.QuiltMessage
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.raft.RaftRole
import us.tractat.kuilt.raft.test.FakeRaftNode
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * A replicated topology act cannot hand a parent a weight set its picks cannot do arithmetic on
 * (#3353). `LocalHeddle.apply` already refuses one (#3352); these are the two paths into a
 * replicated ledger: the control log, which decides against its log-pure projection, and the
 * ungoverned node's own mutators, which decide on the local view.
 *
 * Two weight sets leave the budget. A lone numerator of `2⁵⁹+1` puts `16 · S` out of `Long`, so
 * the seat step throws on the first pick. `2⁵⁷/3` beside `1/192` is two weights that each fit
 * alone and whose sum `S = 2⁶³ + 1` does not. Each refused arm has a control beside it: the same
 * rig at a weight that fits delegates, so a refusal cannot pass by the rig never reaching a grant.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HeddleWeightBudgetTest {

    @Test
    fun aGovernedPrepareOfAWeightThatLeavesTheBudgetIsRefused() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val governed = bootGoverned()
            val before = governed.ledger.value
            val prepared = governed.prepare(AttachmentRecord(E1, ROOT, C1, OVER_ALONE))
            val activated = governed.activate(E1)
            governed.advertise(E1, HUNGRY)

            assertAll(
                { assertFalse(weightFitsTheBudget(OVER_ALONE), "rig: the weight fails the budget alone") },
                { assertEquals(ControlConflict.WeightBudget(ROOT, E1), assertIs<ControlOutcome.Conflict>(prepared, "prepare").conflict) },
                { assertIs<ControlOutcome.Conflict>(activated, "activate of an edge never prepared") },
                { assertEquals(before, governed.ledger.value, "the ledger is unchanged") },
                { assertEquals(ScheduleOutcome.NothingToDelegate, governed.schedule(ROOT), "schedule does not throw") },
            )
        }

    @Test
    fun aGovernedActivateThatPutsTheSiblingsOutOfTheBudgetIsRefused() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val governed = bootGoverned()
            assertIs<ControlOutcome.Applied>(governed.prepare(AttachmentRecord(E1, ROOT, C1, BIG_SIBLING)), "rig: fits alone")
            assertIs<ControlOutcome.Applied>(governed.prepare(AttachmentRecord(E2, ROOT, C2, SMALL_SIBLING)), "rig: fits alone")
            assertIs<ControlOutcome.Applied>(governed.activate(E1), "rig: one sibling fits")
            assertAll(
                { assertTrue(weightFitsTheBudget(BIG_SIBLING) && weightFitsTheBudget(SMALL_SIBLING), "rig: each fits alone") },
                { assertFalse(governed.ledger.value.activationFitsTheBudget(E2), "rig: the pair does not") },
            )
            val before = governed.ledger.value
            val activated = governed.activate(E2)
            governed.advertise(E1, HUNGRY)
            governed.advertise(E2, HUNGRY)

            assertAll(
                { assertEquals(ControlConflict.WeightBudget(ROOT, E2), assertIs<ControlOutcome.Conflict>(activated, "activate").conflict) },
                { assertEquals(before, governed.ledger.value, "the ledger is unchanged") },
                { assertEquals(Lifecycle.PREPARED, governed.ledger.value.lifecycle(E2), "the edge stays prepared") },
                { assertIs<ScheduleOutcome.Delegated>(governed.schedule(ROOT), "the sibling that fits is served, and nothing throws") },
            )
        }

    /** The control for both governed arms: the same rig, at weights that fit, delegates. */
    @Test
    fun aGovernedTopologyThatFitsTheBudgetIsAppliedAndServed() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val governed = bootGoverned()
            val acts = listOf(
                governed.prepare(AttachmentRecord(E1, ROOT, C1, BIG_SIBLING)),
                governed.prepare(AttachmentRecord(E2, ROOT, C2, Weight.ONE)),
                governed.activate(E1),
                governed.activate(E2),
            )
            assertTrue(acts.all { it is ControlOutcome.Applied }, "every act applied: $acts")
            governed.advertise(E1, HUNGRY)
            governed.advertise(E2, HUNGRY)
            assertIs<ScheduleOutcome.Delegated>(governed.schedule(ROOT))
        }

    @Test
    fun anUngovernedPrepareOfAWeightThatLeavesTheBudgetIsRefused() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val node = bootStatic()
            val before = node.ledger.value
            val prepared = node.prepare(AttachmentRecord(E1, ROOT, C1, OVER_ALONE))
            val activated = node.activate(E1)
            node.advertise(E1, HUNGRY)

            assertAll(
                { assertFalse(prepared, "prepare") },
                { assertFalse(activated, "activate of an edge never prepared") },
                { assertEquals(before, node.ledger.value, "the ledger is unchanged") },
                { assertEquals(0, node.schedule(ROOT), "schedule does not throw") },
            )
        }

    @Test
    fun anUngovernedActivateThatPutsTheSiblingsOutOfTheBudgetIsRefused() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val node = bootStatic()
            assertTrue(node.prepare(AttachmentRecord(E1, ROOT, C1, BIG_SIBLING)), "rig: fits alone")
            assertTrue(node.prepare(AttachmentRecord(E2, ROOT, C2, SMALL_SIBLING)), "rig: fits alone")
            assertTrue(node.activate(E1), "rig: one sibling fits")
            val before = node.ledger.value
            val activated = node.activate(E2)
            node.advertise(E1, HUNGRY)
            node.advertise(E2, HUNGRY)

            assertAll(
                { assertFalse(activated, "activate") },
                { assertEquals(before, node.ledger.value, "the ledger is unchanged") },
                { assertTrue(node.schedule(ROOT) > 0, "the sibling that fits is served, and nothing throws") },
            )
        }

    /** The control for both ungoverned arms. */
    @Test
    fun anUngovernedTopologyThatFitsTheBudgetIsAppliedAndServed() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val node = bootStatic()
            assertAll(
                { assertTrue(node.prepare(AttachmentRecord(E1, ROOT, C1, BIG_SIBLING))) },
                { assertTrue(node.prepare(AttachmentRecord(E2, ROOT, C2, Weight.ONE))) },
                { assertTrue(node.activate(E1)) },
                { assertTrue(node.activate(E2)) },
            )
            node.advertise(E1, HUNGRY)
            node.advertise(E2, HUNGRY)
            assertTrue(node.schedule(ROOT) > 0)
        }

    /**
     * An edge already active is not counted twice: re-activating it checks its siblings beside it,
     * not beside a second copy of itself. `2⁵⁸` fits alone, and two of it do not.
     */
    @Test
    fun aGovernedReactivationIsNotCountedBesideItself() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val governed = bootGoverned()
            assertIs<ControlOutcome.Applied>(governed.prepare(AttachmentRecord(E1, ROOT, C1, HALF_THE_ROOM)), "rig: prepared")
            val first = governed.activate(E1)
            val second = governed.activate(E1)
            assertAll(
                { assertFalse(weightsFitTogether(HALF_THE_ROOM, HALF_THE_ROOM), "rig: two of it would not fit") },
                { assertIs<ControlOutcome.Applied>(first, "first activate") },
                { assertIs<ControlOutcome.Applied>(second, "second activate") },
            )
        }

    @Test
    fun anUngovernedReactivationIsNotCountedBesideItself() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val node = bootStatic()
            assertTrue(node.prepare(AttachmentRecord(E1, ROOT, C1, HALF_THE_ROOM)), "rig: prepared")
            val first = node.activate(E1)
            val second = node.activate(E1)
            assertAll(
                { assertFalse(weightsFitTogether(HALF_THE_ROOM, HALF_THE_ROOM), "rig: two of it would not fit") },
                { assertTrue(first, "first activate") },
                { assertTrue(second, "second activate") },
            )
        }

    /**
     * The log decides on its own projection, not on the gossip-merged ledger. Here the data plane
     * is ahead of the log: another peer's `Close` of [E1] has reached it by gossip, and the log has
     * not applied one. In log order [E1] is still active, so activating [E2] beside it fails the
     * budget, which is what every peer applying this log decides. A plane reading the merged
     * ledger would see [E1] closing and let [E2] in, on this peer only.
     */
    @Test
    fun aGovernedActivateDecidesOnTheLogNotOnTheGossipedLedger() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val loom = InMemoryLoom()
            val governed = bootGoverned(loom)
            assertIs<ControlOutcome.Applied>(governed.prepare(AttachmentRecord(E1, ROOT, C1, BIG_SIBLING)), "rig: prepared")
            assertIs<ControlOutcome.Applied>(governed.prepare(AttachmentRecord(E2, ROOT, C2, SMALL_SIBLING)), "rig: prepared")
            assertIs<ControlOutcome.Applied>(governed.activate(E1), "rig: one sibling active")

            val ahead = loom.join(InMemoryTag("ahead"))
            val ledgerChannel = NamedMux(ahead, backgroundScope).channel(HeddleNode.LEDGER_CHANNEL)
            backgroundScope.launch { ledgerChannel.incoming.collect { } }
            advanceTimeBy(200L)
            runCurrent()
            val close = checkNotNull(governed.ledger.value.close(E1)) { "rig: E1 closes" }
            val messages = QuiltMessage.serializer(EntitlementLedger.serializer())
            val frame = Cbor.encodeToByteArray(messages, QuiltMessage.Delta(ReplicaId("ahead"), 1L, close.delta))
            for (peer in ahead.peers.value - ahead.selfId) ledgerChannel.sendTo(peer, frame)
            advanceTimeBy(200L)
            runCurrent()

            val gossiped = governed.ledger.value
            val activated = governed.activate(E2)
            assertAll(
                { assertEquals(Lifecycle.CLOSING, gossiped.lifecycle(E1), "rig: the data plane holds the Close") },
                { assertTrue(gossiped.activationFitsTheBudget(E2), "rig: on the gossiped ledger E2 would fit") },
                { assertEquals(ControlConflict.WeightBudget(ROOT, E2), assertIs<ControlOutcome.Conflict>(activated, "activate").conflict) },
                { assertEquals(Lifecycle.PREPARED, governed.ledger.value.lifecycle(E2), "the edge stays prepared") },
            )
        }

    /** A closed edge is refused for being closed: the budget is not asked about an act that cannot apply. */
    @Test
    fun aGovernedActivateOfAClosingEdgeIsRefusedForItsLifecycleFirst() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val governed = bootGoverned()
            assertIs<ControlOutcome.Applied>(governed.prepare(AttachmentRecord(E1, ROOT, C1, BIG_SIBLING)), "rig: prepared")
            assertIs<ControlOutcome.Applied>(governed.prepare(AttachmentRecord(E2, ROOT, C2, SMALL_SIBLING)), "rig: prepared")
            assertIs<ControlOutcome.Applied>(governed.activate(E1), "rig: activated")
            assertIs<ControlOutcome.Applied>(governed.close(E1), "rig: closed")
            assertIs<ControlOutcome.Applied>(governed.activate(E2), "rig: its sibling activates once it is closed")
            val activated = governed.activate(E1)
            assertAll(
                { assertFalse(governed.ledger.value.activationFitsTheBudget(E1), "rig: it would fail the budget too") },
                { assertIs<ControlConflict.Refused>(assertIs<ControlOutcome.Conflict>(activated, "activate").conflict) },
            )
        }

    /** First-wins answers a second record under a bound id, whatever that record's weight. */
    @Test
    fun aGovernedPrepareUnderABoundIdIsRefusedAsBoundFirst() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val governed = bootGoverned()
            assertIs<ControlOutcome.Applied>(governed.prepare(AttachmentRecord(E1, ROOT, C1, BIG_SIBLING)), "rig: bound")
            val prepared = governed.prepare(AttachmentRecord(E1, ROOT, C1, OVER_ALONE))
            assertAll(
                { assertFalse(weightFitsTheBudget(OVER_ALONE), "rig: the second record fails the budget too") },
                { assertIs<ControlConflict.Refused>(assertIs<ControlOutcome.Conflict>(prepared, "prepare").conflict) },
            )
        }

    /** A static node's starting tree is held to the budget too, and a refused record refuses the boot. */
    @Test
    fun heddleStaticRefusesATopologyThatLeavesTheBudget() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val alone = listOf(AttachmentRecord(E1, ROOT, C1, OVER_ALONE))
            val pair = listOf(AttachmentRecord(E1, ROOT, C1, BIG_SIBLING), AttachmentRecord(E2, ROOT, C2, SMALL_SIBLING))
            val fits = listOf(AttachmentRecord(E1, ROOT, C1, BIG_SIBLING), AttachmentRecord(E2, ROOT, C2, Weight.ONE))
            val aloneSeam = InMemoryLoom().host(Pattern("topology-alone"))
            val pairSeam = InMemoryLoom().host(Pattern("topology-pair"))
            val fitsSeam = InMemoryLoom().host(Pattern("topology-fits"))
            assertAll(
                { assertFailsWith<IllegalArgumentException>("alone") { staticOn(aloneSeam, alone) } },
                { assertFailsWith<IllegalArgumentException>("the pair") { staticOn(pairSeam, pair) } },
                { assertEquals(SUPPLY, staticOn(fitsSeam, fits).ledger.value.mintedTotal(), "the control: a topology that fits boots") },
            )
        }

    private fun TestScope.staticOn(seam: Seam, topology: List<AttachmentRecord>): HeddleNode {
        val self = ReplicaId(seam.selfId.value)
        return backgroundScope.heddleStatic(
            seam = seam,
            self = self,
            root = ROOT,
            mint = mapOf(self to SUPPLY),
            topology = topology,
            clock = { Instant.fromEpochMilliseconds(testScheduler.currentTime) },
            config = config(),
            epoch = 0L,
        )
    }

    /** Whether [b] activates beside an active [a], on the ledger alone: the rig's own arithmetic check. */
    private fun weightsFitTogether(a: Weight, b: Weight): Boolean {
        var ledger = EntitlementLedger.bootstrap(ROOT, emptyMap(), nonce = "weights-fit-together")
        ledger = ledger.piece(checkNotNull(ledger.prepare(AttachmentRecord(E1, ROOT, C1, a))).delta)
        ledger = ledger.piece(checkNotNull(ledger.activate(E1)).delta)
        ledger = ledger.piece(checkNotNull(ledger.prepare(AttachmentRecord(E2, ROOT, C2, b))).delta)
        return ledger.activationFitsTheBudget(E2)
    }

    private suspend fun TestScope.bootGoverned(loom: InMemoryLoom = InMemoryLoom()): GovernedHeddleNode {
        val governed = backgroundScope.governedOn(this, FakeRaftNode(initialRole = RaftRole.Leader), loom)
        assertIs<ControlOutcome.Applied>(governed.enroll(SELF), "rig: enrolled")
        assertIs<ControlOutcome.Applied>(governed.mint(SELF, SUPPLY), "rig: minted")
        assertTrue(governed.isWritable, "rig: the gate is open")
        return governed
    }

    private suspend fun CoroutineScope.governedOn(test: TestScope, raft: FakeRaftNode, loom: InMemoryLoom): GovernedHeddleNode =
        heddleGoverned(
            seam = loom.host(Pattern("heddle-weight-budget")),
            self = SELF,
            raft = raft,
            root = ROOT,
            clock = { Instant.fromEpochMilliseconds(test.testScheduler.currentTime) },
            config = config(),
            incarnation = "weight-budget",
            epoch = 0L,
        )

    private suspend fun TestScope.bootStatic(): HeddleNode {
        val seam = InMemoryLoom().host(Pattern("heddle-weight-budget-static"))
        val self = ReplicaId(seam.selfId.value)
        val node = backgroundScope.heddleStatic(
            seam = seam,
            self = self,
            root = ROOT,
            mint = mapOf(self to SUPPLY),
            clock = { Instant.fromEpochMilliseconds(testScheduler.currentTime) },
            config = config(),
            epoch = 0L,
        )
        assertEquals(SUPPLY, node.ledger.value.mintedTotal(), "rig: minted")
        return node
    }

    private fun config() = HeddleConfig(
        policy = PolicyConfig(quantum = 10L),
        maxHoldingsPerPeer = 100_000L,
        demandTtl = 30.seconds,
        quilter = QuilterConfig(antiEntropyInterval = 100.milliseconds, fullStateRetryLimit = 0, expectVirtualTime = true),
        heartbeat = HeartbeatConfig(),
        random = Random(41),
    )

    private companion object {
        val ROOT = GroupId("root")
        val C1 = GroupId("c1")
        val C2 = GroupId("c2")
        val E1 = AttachmentId("e1")
        val E2 = AttachmentId("e2")
        val SELF = ReplicaId("self")
        const val SUPPLY = 100L
        val HUNGRY = Demand(targetOutstanding = 1_000L, maximumUsefulGrant = 1_000L)

        /** `2⁵⁹+1`: alone under a parent, `16 · S` leaves `Long`. */
        val OVER_ALONE: Weight = Weight.of((1L shl 59) + 1L)

        /** `2⁵⁷/3`, which fits alone; beside [SMALL_SIBLING] the sum over `L = 192` is `2⁶³ + 1`. */
        val BIG_SIBLING: Weight = Weight.of(1L shl 57, 3L)

        /** `2⁵⁸`, which fits alone and not twice: `16 · 2⁵⁹` leaves `Long`. */
        val HALF_THE_ROOM: Weight = Weight.of(1L shl 58)

        /** `1/192`, which fits alone. */
        val SMALL_SIBLING: Weight = Weight.of(1L, 192L)
    }
}
