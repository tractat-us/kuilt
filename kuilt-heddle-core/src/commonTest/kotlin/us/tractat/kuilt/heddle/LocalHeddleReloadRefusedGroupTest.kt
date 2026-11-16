package us.tractat.kuilt.heddle

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A reload whose activation of one group the weight budget refuses (#3466). A **new** group takes
 * its new lanes out with it, each reported in [ApplyResult.refusals], so no lane activates below a
 * group the root cannot reach, whether a reload, an `attach` or a boot would add it. A refused
 * reweight of an **existing** group keeps its old edge, so a new lane under it still lands. A
 * group put back while its removal drains holds its new lanes back as `Draining`, on its leases.
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

    /** A refused new group two levels up takes both levels beneath it out, in one reload. */
    @OptIn(HeddleInternalApi::class)
    @Test
    fun aRefusedNewGrandparentLeavesOutEveryLevelBeneathIt() {
        val deep = draft("pool") {
            lane("small", Weight.ONE)
            group("huge", Weight.of(1L shl 62)) { group("mid", Weight.ONE) { lane("leaf", Weight.ONE) } }
        }
        val mid = deep.groupId("huge", "mid")
        val leaf = deep.groupId("huge", "mid", "leaf")
        val engine = LocalHeddle.bootWhole(pool(withHuge = false), policy, epoch = 1L).apply { reconcileCapacity(4_000L) }
        val result = engine.apply(deep, generation = 1L)
        assertAll(
            { assertEquals(listOf(huge, mid, leaf), result.refusals.map { it.child }, "every level is refused: ${result.refusals}") },
            { assertTrue(result.refusals.all { it is StepRefusal.Refused }, "each one as Refused: ${result.refusals}") },
            { assertEquals(emptyList(), engine.orphanParents(listOf(huge, mid)), "no orphan at either level") },
        )
    }

    /** A templated group the reload refused takes an `attach` beneath it out too, later and in another call. */
    @OptIn(HeddleInternalApi::class)
    @Test
    fun anAttachUnderARefusedNewGroupIsRefused() {
        val withFarm = draft("pool") {
            lane("small", Weight.ONE)
            group("farm", Weight.of(1L shl 62)) { template() }
        }
        val farm = withFarm.groupId("farm")
        val worker = withFarm.attachedChildId(listOf("farm"), "w1")
        val engine = LocalHeddle.bootWhole(pool(withHuge = false), policy, epoch = 1L).apply { reconcileCapacity(4_000L) }
        val reloaded = engine.apply(withFarm, generation = 1L)
        val attached = engine.attach(listOf("farm"), "w1", generation = 2L)
        assertAll(
            { assertEquals(listOf(farm), reloaded.refusals.map { it.child }, "the rig: farm is refused") },
            { assertEquals(listOf(worker), attached.refusals.map { it.child }, "the attach is refused: ${attached.refusals}") },
            { assertIs<StepRefusal.Refused>(attached.refusals.singleOrNull(), "as Refused") },
            { assertTrue(attached.applied.none { it.child == worker }, "no step of it lands: ${attached.applied}") },
            { assertEquals(emptyList(), engine.orphanParents(listOf(farm)), "no orphan") },
        )
    }

    /** The same through a boot, which leaves out the sibling set the templated group is in. */
    @OptIn(HeddleInternalApi::class)
    @Test
    fun anAttachUnderAGroupABootLeftOutIsRefused() {
        val tree = draft("pool") {
            lane("small", Weight.ONE)
            group("team", Weight.ONE) {
                lane("own", Weight.ONE)
                group("farm", Weight.of(1L shl 62)) { template() }
            }
        }
        val team = tree.groupId("team")
        val farm = tree.groupId("team", "farm")
        val worker = tree.attachedChildId(listOf("team", "farm"), "w1")
        val (engine, booted) = LocalHeddle.fromTopology(tree, policy, epoch = 1L)
        val attached = engine.attach(listOf("team", "farm"), "w1", generation = 1L)
        assertAll(
            { assertTrue(team in booted.refusals.map { it.child } && farm in booted.refusals.map { it.child }, "the rig: the boot leaves out team and farm: ${booted.refusals}") },
            { assertEquals(listOf(worker), attached.refusals.map { it.child }, "the attach is refused: ${attached.refusals}") },
            { assertIs<StepRefusal.Refused>(attached.refusals.singleOrNull(), "as Refused") },
            { assertEquals(emptyList(), engine.orphanParents(listOf(team, farm)), "no orphan") },
        )
    }

    /**
     * `t` is removed while a lease keeps `t/x` draining, then put back with a new lane `t/z`. `t`
     * waits on that lease, so `t/z` waits with it, as [StepRefusal.Draining] on the same lease:
     * waiting cures it, which a [StepRefusal.Refused] would deny. Once the lease completes, the same
     * reload lands whole and `t/z` leases.
     */
    @OptIn(HeddleInternalApi::class)
    @Test
    fun aLaneUnderAGroupPutBackWhileItDrainsWaitsOnTheSameLease() {
        val withX = draft("pool") {
            lane("small", Weight.ONE)
            group("t", Weight.ONE) { lane("x", Weight.ONE) }
        }
        val withZ = draft("pool") {
            lane("small", Weight.ONE)
            group("t", Weight.ONE) { lane("z", Weight.ONE) }
        }
        val t = withX.groupId("t")
        val x = withX.groupId("t", "x")
        val z = withZ.groupId("t", "z")
        val engine = LocalHeddle.bootWhole(withX, policy, epoch = 1L).apply { reconcileCapacity(4_000L) }
        val lease = assertIs<AcquireOutcome.Acquired>(engine.acquire(setOf(x), setOf(x), startedAt = 0L)).lease
        val removed = engine.apply(pool(withHuge = false), generation = 1L)
        val putBack = engine.apply(withZ, generation = 2L)
        val zRefusal = putBack.refusals.firstOrNull { it.child == z }
        val orphans = engine.orphanParents(listOf(t))
        engine.complete(lease.id, actualCost = 1L, waiting = emptySet())
        val landed = engine.apply(withZ, generation = 3L)
        val zLeases = engine.acquire(setOf(z), setOf(z), startedAt = 1L)
        assertAll(
            { assertTrue(removed.refusals.all { it is StepRefusal.Draining }, "the rig: the removal drains: ${removed.refusals}") },
            { assertIs<StepRefusal.Draining>(putBack.refusals.firstOrNull { it.child == t }, "t waits on the lease: ${putBack.refusals}") },
            { assertIs<StepRefusal.Draining>(zRefusal, "t/z waits with it: ${putBack.refusals}") },
            { assertEquals(listOf(lease), (zRefusal as? StepRefusal.Draining)?.blockingLeases, "on the same lease") },
            { assertTrue(putBack.applied.none { it.child == z }, "no step of t/z lands: ${putBack.applied}") },
            { assertEquals(emptyList(), orphans, "no orphan") },
            { assertEquals(emptyList(), landed.refusals, "after the lease, the reload lands whole") },
            { assertIs<AcquireOutcome.Acquired>(zLeases, "and t/z leases: $zLeases") },
        )
    }

    /**
     * `t` is removed while a lease keeps `t/x` draining, then put back, and a later, separate
     * `attach` lands a worker under it. No refusal of this call says `t` waits, yet it does, on the
     * lease: the attach is [StepRefusal.Draining] on that lease, and lands once it completes (#3508).
     */
    @OptIn(HeddleInternalApi::class)
    @Test
    fun anAttachUnderAGroupStillDrainingFromAnEarlierCallWaitsOnTheSameLease() {
        val withX = draft("pool") {
            lane("small", Weight.ONE)
            group("t", Weight.ONE) {
                lane("x", Weight.ONE)
                template()
            }
        }
        val putBack = draft("pool") {
            lane("small", Weight.ONE)
            group("t", Weight.ONE) { template() }
        }
        val t = withX.groupId("t")
        val x = withX.groupId("t", "x")
        val worker = putBack.attachedChildId(listOf("t"), "w1")
        val engine = LocalHeddle.bootWhole(withX, policy, epoch = 1L).apply { reconcileCapacity(4_000L) }
        val lease = assertIs<AcquireOutcome.Acquired>(engine.acquire(setOf(x), setOf(x), startedAt = 0L)).lease
        engine.apply(pool(withHuge = false), generation = 1L)
        val reloaded = engine.apply(putBack, generation = 2L)
        val attached = engine.attach(listOf("t"), "w1", generation = 3L)
        val wait = attached.refusals.singleOrNull()
        val orphans = engine.orphanParents(listOf(t))
        engine.complete(lease.id, actualCost = 1L, waiting = emptySet())
        val landed = engine.apply(putBack, generation = 4L)
        val again = engine.attach(listOf("t"), "w1", generation = 5L)
        val leased = engine.acquire(setOf(worker), setOf(worker), startedAt = 1L)
        assertAll(
            { assertIs<StepRefusal.Draining>(reloaded.refusals.firstOrNull { it.child == t }, "the rig: t is put back while it drains: ${reloaded.refusals}") },
            { assertEquals(worker, wait?.child, "the attach is held back: ${attached.refusals}") },
            { assertIs<StepRefusal.Draining>(wait, "as Draining: ${attached.refusals}") },
            { assertEquals(listOf(lease), (wait as? StepRefusal.Draining)?.blockingLeases, "on the same lease") },
            { assertTrue(attached.applied.none { it.child == worker }, "no step of it lands: ${attached.applied}") },
            { assertEquals(emptyList(), orphans, "no orphan") },
            { assertEquals(emptyList(), landed.refusals, "after the lease, the put-back lands") },
            { assertEquals(emptyList(), again.refusals, "and so does the attach") },
            { assertIs<AcquireOutcome.Acquired>(leased, "and the worker leases: $leased") },
        )
    }
}
