package us.tractat.kuilt.heddle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.raft.RaftRole
import us.tractat.kuilt.raft.test.FakeRaftNode
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
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
                { assertIs<ControlOutcome.Conflict>(prepared, "prepare") },
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
            val before = governed.ledger.value
            val activated = governed.activate(E2)
            governed.advertise(E1, HUNGRY)
            governed.advertise(E2, HUNGRY)

            assertAll(
                { assertIs<ControlOutcome.Conflict>(activated, "activate") },
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
            assertAll(
                { assertIs<ControlOutcome.Applied>(governed.prepare(AttachmentRecord(E1, ROOT, C1, BIG_SIBLING))) },
                { assertIs<ControlOutcome.Applied>(governed.prepare(AttachmentRecord(E2, ROOT, C2, Weight.ONE))) },
                { assertIs<ControlOutcome.Applied>(governed.activate(E1)) },
                { assertIs<ControlOutcome.Applied>(governed.activate(E2)) },
            )
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

    private suspend fun TestScope.bootGoverned(): GovernedHeddleNode {
        val governed = backgroundScope.governedOn(this, FakeRaftNode(initialRole = RaftRole.Leader))
        assertIs<ControlOutcome.Applied>(governed.enroll(SELF), "rig: enrolled")
        assertIs<ControlOutcome.Applied>(governed.mint(SELF, SUPPLY), "rig: minted")
        assertTrue(governed.isWritable, "rig: the gate is open")
        return governed
    }

    private suspend fun CoroutineScope.governedOn(test: TestScope, raft: FakeRaftNode): GovernedHeddleNode =
        heddleGoverned(
            seam = InMemoryLoom().host(Pattern("heddle-weight-budget")),
            self = SELF,
            raft = raft,
            root = ROOT,
            clock = { Instant.fromEpochMilliseconds(test.testScheduler.currentTime) },
            config = config(),
            incarnation = "weight-budget",
            epoch = 0L,
        )

    private fun TestScope.bootStatic(): HeddleNode {
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

        /** `1/192`, which fits alone. */
        val SMALL_SIBLING: Weight = Weight.of(1L, 192L)
    }
}
