package us.tractat.kuilt.heddle

import us.tractat.kuilt.heddle.Topology.Group
import us.tractat.kuilt.heddle.Topology.Lane
import us.tractat.kuilt.heddle.Topology.RuntimeChildren
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A ceiling is enforced (#3324, design §11.2): a pick drops every leaf beneath a node whose open
 * leases have reached its `maxConcurrent` from both the waiting and the eligible set, so the node
 * counts as idle at its parent and wakes clamped. `admit`, `extend` and `complete` are never refused.
 */
class LocalHeddleCeilingTest {

    private val policy = LocalPolicy(defaultReservation = 1L)

    private fun engine(topology: Topology): LocalHeddle =
        LocalHeddle.fromTopology(topology, policy, epoch = 0L).also { it.reconcileCapacity(4L) }

    private fun LocalHeddle.pick(waiting: Set<GroupId>, eligible: Set<GroupId> = waiting): GroupId =
        assertIs<AcquireOutcome.Acquired>(acquire(waiting, eligible, startedAt = 0L), "expected a lease among $eligible").lease.leaf

    /** `root → {a: 10, b: 1}`, with [cap] on `a`: unbounded, `a` wins about ten picks for each of `b`'s. */
    private fun heavy(cap: Long?) = Topology("root", listOf(Lane("a", Weight.of(10), maxConcurrent = cap), Lane("b")))
    private val a = heavy(null).groupId("a")
    private val b = heavy(null).groupId("b")
    private val ab = setOf(a, b)

    @Test
    fun aThirdAcquireAtACeilingOfTwoPicksTheSibling() {
        val capped = engine(heavy(cap = 2L))
        val uncapped = engine(heavy(cap = null))
        val cappedPicks = List(3) { capped.pick(ab) }
        val uncappedPicks = List(3) { uncapped.pick(ab) }
        assertAll(
            { assertEquals(listOf(a, a, a), uncappedPicks, "the control: uncapped, the heavy lane takes all three") },
            { assertEquals(listOf(a, a, b), cappedPicks) },
        )
    }

    @Test
    fun aCeilingOfZeroPausesEverythingBeneathIt() {
        val t = Topology("root", listOf(Group("acme", Weight.of(10), listOf(Lane("x"), Lane("y")), maxConcurrent = 0L), Lane("b")))
        val x = t.groupId("acme", "x")
        val y = t.groupId("acme", "y")
        val other = t.groupId("b")
        val h = engine(t)
        val picks = List(4) { h.pick(setOf(x, y, other)) }
        val before = h.snapshot()
        val onlyPaused = h.acquire(setOf(x, y, other), setOf(x, y), startedAt = 0L)
        assertAll(
            { assertEquals(List(4) { other }, picks) },
            { assertEquals(AcquireOutcome.NothingEligible, onlyPaused, "every eligible leaf is paused") },
            { assertEquals(before, h.snapshot(), "and nothing moved, the wake memory included") },
        )
    }

    /**
     * `a` (cap 2) fills its ceiling, then sits out 20 picks that `b` and `c` share. While capped it
     * is idle at the root, so it banks no credit: once a completion frees it, it rotates in with the
     * other two rather than taking a burst of picks to catch up.
     */
    @Test
    fun aGroupAtItsCeilingWakesClampedWithNoBurst() {
        val t = Topology("root", listOf(Lane("a", maxConcurrent = 2L), Lane("b"), Lane("c")))
        val (la, lb, lc) = listOf("a", "b", "c").map { t.groupId(it) }
        val all = setOf(la, lb, lc)
        val h = engine(t)
        val held = List(2) { assertIs<AcquireOutcome.Acquired>(h.acquire(all, setOf(la), startedAt = 0L)).lease }
        val whileCapped = List(20) {
            val lease = assertIs<AcquireOutcome.Acquired>(h.acquire(all, all, startedAt = 0L)).lease
            check(h.complete(lease.id, 1L) is LeaseOutcome.Done)
            lease.leaf
        }
        check(h.complete(held[0].id, 1L) is LeaseOutcome.Done)
        val afterWake = List(6) {
            val lease = assertIs<AcquireOutcome.Acquired>(h.acquire(all, all, startedAt = 0L)).lease
            check(h.complete(lease.id, 1L) is LeaseOutcome.Done)
            lease.leaf
        }
        assertAll(
            { assertTrue(whileCapped.none { it == la } && whileCapped.count { it == lb } == 10, "the rig: b and c share the capped stretch: $whileCapped") },
            { assertEquals(2, afterWake.count { it == la }, "a takes its third of the picks after waking, not a burst: $afterWake") },
        )
    }

    /** A group's ceiling counts every lease beneath it, across its children, a draining one included. */
    @Test
    fun aGroupCeilingCountsEveryLeaseBeneathItADrainingOneIncluded() {
        fun tree(withX: Boolean) = Topology(
            "root",
            listOf(Group("acme", Weight.of(10), listOfNotNull(Lane("x").takeIf { withX }, Lane("y")), maxConcurrent = 1L), Lane("b")),
        )
        val t = tree(withX = true)
        val x = t.groupId("acme", "x")
        val y = t.groupId("acme", "y")
        val other = t.groupId("b")
        val h = engine(t)
        h.lease(setOf(x))
        val whileOpen = h.pick(setOf(y, other))
        val removal = h.apply(tree(withX = false), generation = 1L)
        val whileDraining = h.pick(setOf(y, other))
        assertAll(
            { assertIs<StepRefusal.Draining>(removal.refusals.single(), "the rig: x drains with its lease open") },
            { assertEquals(other, whileOpen, "acme is at its ceiling across its children") },
            { assertEquals(other, whileDraining, "the draining lease still counts at acme") },
        )
    }

    /** On a runtime marker the ceiling caps each child on its own, not all of them together. */
    @Test
    fun aRuntimeMarkerCapsEachChildOnItsOwn() {
        val t = Topology("root", listOf(Group("tenants", Weight.ONE, emptyList(), RuntimeChildren(Weight.ONE, maxConcurrent = 1L))))
        val h = engine(t)
        check(h.attachRuntimeChild(listOf("tenants"), "t1", generation = 1L).refusals.isEmpty())
        check(h.attachRuntimeChild(listOf("tenants"), "t2", generation = 1L).refusals.isEmpty())
        val t1 = t.runtimeChildId(listOf("tenants"), "t1")
        val t2 = t.runtimeChildId(listOf("tenants"), "t2")
        val first = h.pick(setOf(t1))
        val again = h.acquire(setOf(t1, t2), setOf(t1), startedAt = 0L)
        val sibling = h.pick(setOf(t1, t2))
        assertAll(
            { assertEquals(t1, first) },
            { assertEquals(AcquireOutcome.NothingEligible, again, "t1 is at its own ceiling") },
            { assertEquals(t2, sibling, "t2 is not: the ceiling is per child") },
        )
    }

    /** `admit`, `extend` and `complete` charge work that is already running, so a ceiling refuses none of them. */
    @Test
    fun admitExtendAndCompleteAreNeverRefusedAtACeiling() {
        val h = engine(heavy(cap = 0L))
        val plain = h.admit(a, startedAt = 0L)
        val observing = h.admit(a, ab, startedAt = 0L)
        val lease = assertIs<AcquireOutcome.Acquired>(plain).lease
        assertAll(
            { assertIs<AcquireOutcome.Acquired>(observing) },
            { assertEquals(LeaseOutcome.Done(0L, 0L), h.extend(lease.id, 1L)) },
            { assertEquals(LeaseOutcome.Done(3L, 1L), h.complete(lease.id, 3L)) },
            { assertEquals(b, h.pick(ab), "while the paused lane's admitted work still counts") },
        )
    }

    /**
     * With a sibling at its ceiling, `admit(b, waiting)` still leaves what `acquire(waiting, {b})`
     * leaves (#3320): both observe the capped lane as idle. For a capped leaf itself the two part:
     * the acquire has nothing to pick, and the admit charges anyway.
     */
    @Test
    fun anUncappedAdmitObservesAsTheAcquireDoesBesideACappedSibling() {
        val t = Topology("root", listOf(Lane("a", maxConcurrent = 1L), Lane("b"), Lane("c")))
        val (la, lb, lc) = listOf("a", "b", "c").map { t.groupId(it) }
        val all = setOf(la, lb, lc)
        val admits = engine(t)
        val acquires = engine(t)
        for (h in listOf(admits, acquires)) {
            h.lease(setOf(la))
            repeat(3) { check(h.complete(h.lease(setOf(lb, lc)).id, 1L) is LeaseOutcome.Done) }
        }
        val admitted = admits.admit(lb, all, startedAt = 0L)
        val acquired = acquires.acquire(all, setOf(lb), startedAt = 0L)
        assertAll(
            { assertEquals(acquired, admitted) },
            { assertEquals(acquires.snapshot(), admits.snapshot(), "ledger, leases and wake memory alike") },
            { assertEquals(AcquireOutcome.NothingEligible, acquires.acquire(all, setOf(la), startedAt = 0L)) },
            { assertIs<AcquireOutcome.Acquired>(admits.admit(la, all, startedAt = 0L), "the capped leaf is still admitted") },
        )
    }

    /**
     * A preview counts the leases it opens against a ceiling, and the leases already open: the
     * engine's own, and a view's from the snapshot it was taken from.
     */
    @Test
    fun previewsCountTheLeasesTheyOpenAndTheOnesAlreadyOpen() {
        val h = engine(heavy(cap = 2L))
        val fresh = h.previewPicks(mapOf(a to 5, b to 5), ab, List(4) { 1L })
        val freshQueues = h.previewPicks(mapOf(a to List(5) { 1L }, b to List(5) { 1L }), ab)
        h.lease(setOf(a))
        val oneOpen = h.previewPicks(mapOf(a to 5, b to 1), ab, List(3) { 1L })
        val viewed = h.persist().view(policy).previewPicks(mapOf(a to 5, b to 1), ab, List(3) { 1L })
        assertAll(
            { assertEquals(listOf(a, a, b, b), fresh) },
            { assertEquals(listOf(a, a, b, b, b, b, b), freshQueues, "a queue-form preview, once both of a's slots are taken") },
            { assertEquals(listOf(a, b), oneOpen, "the open lease leaves a one slot, and b's single job ends the list") },
            { assertEquals(oneOpen, viewed, "a view of the snapshot counts the open lease too") },
        )
    }

    /** `explain` agrees with the acquire: a capped leaf is dropped from the eligible set, and the pick goes to its sibling. */
    @Test
    fun explainAgreesWithTheAcquireAtACeiling() {
        val h = engine(heavy(cap = 1L))
        h.lease(setOf(a))
        val ofCapped = h.explain(a, ab, ab)
        val ofSibling = h.explain(b, ab, ab)
        val onlyCapped = h.explain(a, setOf(a), setOf(a))
        val viewed = h.persist().view(policy)
        assertAll(
            { assertEquals(WaitExplanation.NotEligible, ofCapped) },
            { assertEquals(WaitExplanation.WouldBePickedNext, ofSibling) },
            { assertEquals(WaitExplanation.NotEligible, onlyCapped, "every eligible leaf capped, and explain does not throw") },
            { assertEquals(ofSibling, viewed.explain(b, ab, ab)) },
            { assertEquals(b, h.pick(ab), "the acquire it explains") },
        )
    }
}
