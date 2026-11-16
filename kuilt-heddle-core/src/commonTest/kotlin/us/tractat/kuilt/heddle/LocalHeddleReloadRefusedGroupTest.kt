package us.tractat.kuilt.heddle

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A reload whose activation of one group the weight budget refuses (#3466). A **new** group takes
 * its new lanes out with it, each reported in [ApplyResult.refusals], so no lane activates below a
 * group with no inbound edge. A refused reweight of an **existing** group keeps its old edge, so a
 * new lane under it still lands.
 */
class LocalHeddleReloadRefusedGroupTest {

    private val policy = LocalPolicy(defaultReservation = 1_000L)

    /** `pool → {small}`, with `huge → {own, other}` beside it at [hugeWeight] when [withHuge]. */
    private fun pool(withHuge: Boolean, hugeWeight: Weight = Weight.of(1L shl 62)) = draft("pool") {
        lane("small", Weight.ONE)
        if (withHuge) group("huge", hugeWeight) {
            lane("own", Weight.ONE)
            lane("other", Weight.ONE)
        }
    }

    private val shape = pool(withHuge = true)
    private val huge = shape.groupId("huge")
    private val own = shape.groupId("huge", "own")
    private val other = shape.groupId("huge", "other")

    /** Every group of [groups] with an ACTIVE child edge but no ACTIVE inbound edge of its own. */
    private fun LocalHeddle.orphanParents(groups: List<GroupId>): List<GroupId> {
        val ledger = snapshot().ledger
        return groups.filter { g ->
            ledger.activeChildren(g).isNotEmpty() && ledger.liveInboundEdges(g).none { ledger.lifecycle(it) == Lifecycle.ACTIVE }
        }
    }

    @OptIn(HeddleInternalApi::class)
    @Test
    fun aRefusedNewGroupLeavesOutItsLanesAndAFixedReloadActivatesThem() {
        val engine = LocalHeddle.bootWhole(pool(withHuge = false), policy, epoch = 1L).apply { reconcileCapacity(4_000L) }
        val refused = engine.apply(shape, generation = 1L)
        val orphansAfterRefusal = engine.orphanParents(listOf(huge))
        val ownAfterRefusal = engine.acquire(setOf(own), setOf(own), startedAt = 0L)
        val fixed = engine.apply(pool(withHuge = true, hugeWeight = Weight.ONE), generation = 2L)
        val ownAfterFix = engine.acquire(setOf(own), setOf(own), startedAt = 0L)
        assertAll(
            { assertEquals(listOf(huge, other, own), refused.refusals.map { it.child }, "huge and both its lanes are refused") },
            { assertTrue(refused.refusals.all { it is StepRefusal.Refused }, "each one as Refused: ${refused.refusals}") },
            { assertTrue(refused.applied.none { it.child in setOf(huge, own, other) }, "no step of theirs lands: ${refused.applied}") },
            { assertEquals(emptyList(), orphansAfterRefusal, "no ACTIVE edge hangs below a group with no inbound edge") },
            { assertIs<AcquireOutcome.NoLiveLineage>(ownAfterRefusal, "own is not there to lease: $ownAfterRefusal") },
            { assertEquals(emptyList(), fixed.refusals, "the fixed reload lands whole") },
            { assertEquals(setOf(huge, own, other), fixed.applied.filterIsInstance<TopologyStep.Activate>().map { it.child }.toSet(), "and activates all three") },
            { assertIs<AcquireOutcome.Acquired>(ownAfterFix, "own leases from the root: $ownAfterFix") },
            { assertEquals(emptyList(), engine.orphanParents(listOf(huge)), "still no orphan") },
        )
    }

    /**
     * `team` was running, so a refused reweight leaves its old edge ACTIVE and a new lane beneath it
     * reachable from the root: only `team` is refused, and the new lane leases.
     */
    @OptIn(HeddleInternalApi::class)
    @Test
    fun aRefusedReweightOfAnExistingGroupKeepsItsNewLane() {
        val before = draft("pool") {
            lane("small", Weight.ONE)
            group("team", Weight.ONE) { lane("x", Weight.ONE) }
        }
        val after = draft("pool") {
            lane("small", Weight.ONE)
            group("team", Weight.of(1L shl 62)) {
                lane("x", Weight.ONE)
                lane("y", Weight.ONE)
            }
        }
        val team = after.groupId("team")
        val y = after.groupId("team", "y")
        val engine = LocalHeddle.bootWhole(before, policy, epoch = 1L).apply { reconcileCapacity(4_000L) }
        val result = engine.apply(after, generation = 1L)
        val leased = engine.acquire(setOf(y), setOf(y), startedAt = 0L)
        assertAll(
            { assertEquals(listOf(team), result.refusals.map { it.child }, "only team's reweight is refused: ${result.refusals}") },
            { assertTrue(result.applied.any { it is TopologyStep.Activate && it.child == y }, "y's edge activates: ${result.applied}") },
            { assertIs<AcquireOutcome.Acquired>(leased, "y leases from the root through team's old edge: $leased") },
            { assertEquals(emptyList(), engine.orphanParents(listOf(team)), "no orphan") },
        )
    }
}
