package us.tractat.kuilt.heddle

import us.tractat.kuilt.heddle.Topology.Group
import us.tractat.kuilt.heddle.Topology.Lane
import us.tractat.kuilt.heddle.Topology.RuntimeChildren
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A ceiling is enforced (#3324, design §11.2): a pick drops every leaf beneath a node whose open
 * leases have reached its `maxConcurrent` from both the waiting and the eligible set, so the node
 * counts as idle at its parent and wakes clamped. `admit`, `extend` and `complete` are never refused.
 * And it is reported: `explain` names the ceiling a leaf waits on, and `entitlement` owes it nothing.
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

    /**
     * `a` alone waits for two picks, then `b` joins at the front beside it. Uncapped, the heavy lane's
     * earlier deadline takes the third pick too; at a ceiling of two, it goes to `b`.
     */
    @Test
    fun aThirdAcquireAtACeilingOfTwoPicksTheSibling() {
        fun picks(h: LocalHeddle) = List(2) { h.pick(setOf(a)) } + h.pick(ab)
        assertAll(
            { assertEquals(listOf(a, a, a), picks(engine(heavy(cap = null))), "the control: uncapped, the heavy lane takes all three") },
            { assertEquals(listOf(a, a, b), picks(engine(heavy(cap = 2L)))) },
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
            { assertEquals(AcquireOutcome.NothingEligible, h.acquire(ab, setOf(a), startedAt = 0L), "and the lane stays paused") },
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
            { assertEquals<Any>(acquired, admitted) },
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
        val counts = mapOf(a to 5, b to 5)
        val queues = mapOf(a to List(5) { 1L }, b to List(5) { 1L })
        val afterOne = mapOf(a to 5, b to 1)
        fun previews(h: LocalHeddle): List<List<GroupId>> {
            val fresh = listOf(h.previewPicks(counts, ab, List(4) { 1L }), h.previewPicks(queues, ab))
            h.lease(setOf(a))
            val oneOpen = h.previewPicks(afterOne, ab, List(3) { 1L })
            return fresh + listOf(oneOpen, h.persist().view(policy).previewPicks(afterOne, ab, List(3) { 1L }))
        }
        val (fresh, freshQueues, oneOpen, viewed) = previews(engine(heavy(cap = 2L)))
        val uncapped = previews(engine(heavy(cap = null)))
        assertAll(
            { assertEquals(listOf(listOf(a, b, a, a), listOf(a, b, a, a, a, a, b, b, b, b), listOf(a, b, a), listOf(a, b, a)), uncapped, "the control") },
            { assertEquals(listOf(a, b, a, b), fresh, "a's third pick would open a third lease") },
            { assertEquals(listOf(a, b, a, b, b, b, b), freshQueues, "a queue-form preview, once both of a's slots are taken") },
            { assertEquals(listOf(a, b), oneOpen, "the open lease leaves a one slot, and b's single job ends the list") },
            { assertEquals(oneOpen, viewed, "a view of the snapshot counts the open lease too") },
        )
    }

    /**
     * `explain` names the ceiling a capped leaf waits on, and agrees with the acquire, which skips
     * it for its sibling. The answer comes before the supply check, so it holds on an engine with no
     * supply at all.
     */
    @Test
    fun explainNamesTheCeilingTheAcquireSkipsALeafFor() {
        val h = engine(heavy(cap = 1L))
        h.lease(setOf(a))
        val ofCapped = h.explain(a, ab, ab)
        val ofSibling = h.explain(b, ab, ab)
        val onlyCapped = h.explain(a, setOf(a), setOf(a))
        val viewed = h.persist().view(policy)
        val broke = LocalHeddle.fromTopology(heavy(cap = 0L), policy, epoch = 0L)
        assertAll(
            { assertEquals(WaitExplanation.AtCeiling(a, 1L), ofCapped) },
            { assertEquals(WaitExplanation.Kind.AT_CEILING, ofCapped.kind) },
            { assertEquals(WaitExplanation.WouldBePickedNext, ofSibling) },
            { assertEquals(WaitExplanation.AtCeiling(a, 1L), onlyCapped, "every eligible leaf capped, and explain does not throw") },
            { assertEquals(listOf(ofCapped, ofSibling), listOf(viewed.explain(a, ab, ab), viewed.explain(b, ab, ab)), "a view of the snapshot agrees") },
            { assertEquals(WaitExplanation.AtCeiling(a, 0L), broke.explain(a, ab, ab), "a ceiling outranks an empty root") },
            { assertEquals(WaitExplanation.SupplyExhausted, broke.explain(b, ab, ab), "the control: the uncapped sibling is told the supply") },
            { assertEquals(b, h.pick(ab), "the acquire it explains") },
        )
    }

    /**
     * With two groups capped on one lineage, `explain` names the topmost, the one the whole subtree
     * waits on. `acme` holds `x` (ceiling 1); one lease at `x` fills `x`, and fills `acme` too when
     * its ceiling is 1.
     */
    @Test
    fun explainNamesTheTopmostCappedGroupOnTheLineage() {
        fun tree(acmeCap: Long) = Topology(
            "root",
            listOf(Group("acme", Weight.ONE, listOf(Lane("x", maxConcurrent = 1L), Lane("y")), maxConcurrent = acmeCap), Lane("b")),
        )
        val t = tree(1L)
        val acme = t.groupId("acme")
        val x = t.groupId("acme", "x")
        val y = t.groupId("acme", "y")
        val all = setOf(x, y, t.groupId("b"))
        fun reasons(acmeCap: Long): List<WaitExplanation> {
            val h = engine(tree(acmeCap))
            h.lease(setOf(x))
            return listOf(h.explain(x, all, all), h.explain(y, all, all))
        }
        val (xBoth, yBoth) = reasons(acmeCap = 1L)
        val (xLane, yLane) = reasons(acmeCap = 2L)
        assertAll(
            { assertEquals(WaitExplanation.AtCeiling(acme, 1L), xBoth, "both are full: the topmost is named") },
            { assertEquals(WaitExplanation.AtCeiling(acme, 1L), yBoth, "y waits on acme, not on x") },
            { assertEquals(WaitExplanation.AtCeiling(x, 1L), xLane, "the control: only the lane is full") },
            { assertTrue(yLane !is WaitExplanation.AtCeiling, "and y is not capped: $yLane") },
        )
    }

    /**
     * A name no pick can end at is still `NoLiveLineage` beneath a capped group, from `explain` and
     * from `acquire` alike, exactly as it is with no ceiling: a ceiling never hides a misnamed leaf.
     */
    @Test
    fun aGroupNamedAsALeafIsNoLiveLineageBeneathACeilingToo() {
        fun tree(cap: Long?) = Topology(
            "root",
            listOf(Group("acme", Weight.ONE, listOf(Lane("x"), Lane("y")), maxConcurrent = cap), Lane("b")),
        )
        val t = tree(null)
        val root = t.groupId()
        val acme = t.groupId("acme")
        val waiting = setOf(acme, t.groupId("b"))
        fun answers(cap: Long?): List<Any> {
            val h = engine(tree(cap))
            return listOf(h.acquire(waiting, setOf(acme), startedAt = 0L), h.explain(acme, waiting, setOf(acme)))
        }
        assertAll(
            { assertEquals(listOf<Any>(AcquireOutcome.NoLiveLineage(root), WaitExplanation.NoLiveLineage(acme)), answers(null), "the control: no ceiling") },
            { assertEquals(answers(null), answers(0L), "paused") },
            { assertEquals(answers(null), answers(2L), "below its ceiling") },
        )
    }

    /**
     * A capped leaf does not compete, so `entitlement` owes it nothing and its sibling the whole
     * share, from the engine and from a view of its snapshot alike.
     */
    @Test
    fun entitlementOwesACappedLeafNothing() {
        fun shares(cap: Long?): List<Rational> {
            val h = engine(heavy(cap))
            h.lease(setOf(a))
            val view = h.persist().view(policy)
            return listOf(h.entitlement(a, ab, 11L), h.entitlement(b, ab, 11L), view.entitlement(a, ab, 11L), view.entitlement(b, ab, 11L))
        }
        assertAll(
            { assertEquals(listOf(Rational.of(10L), Rational.ONE, Rational.of(10L), Rational.ONE), shares(cap = 2L), "the control: below its ceiling, a competes") },
            { assertEquals(listOf(Rational.ZERO, Rational.of(11L), Rational.ZERO, Rational.of(11L)), shares(cap = 1L)) },
        )
    }

    /**
     * Across random histories under random ceilings, `explain` answers `AtCeiling` exactly for an
     * eligible leaf with a node at its ceiling on its path, naming the topmost, counted here from
     * the open leases rather than by the engine. The acquire that follows never picks such a leaf,
     * picks the one `explain` called `WouldBePickedNext`, and finds nothing eligible only when every
     * eligible leaf is capped. `entitlement` owes a capped leaf nothing and shares every slot among
     * the rest.
     */
    @Test
    fun explainAndEntitlementAgreeWithTheAcquireUnderCeilings() {
        val caps = listOf(null, 0L, 1L, 2L)
        var atCeiling = 0
        var topmostAbove = 0
        var picks = 0
        var allCapped = 0
        for (seed in 0 until 120) {
            val rng = Random(seed)
            val xCap = caps.random(rng)
            val acmeCap = caps.random(rng)
            val bCap = caps.random(rng)
            val t = Topology(
                "root",
                listOf(
                    Group("acme", Weight.of(3), listOf(Lane("x", maxConcurrent = xCap), Lane("y")), maxConcurrent = acmeCap),
                    Lane("b", maxConcurrent = bCap),
                    Lane("c"),
                ),
            )
            val acme = t.groupId("acme")
            val x = t.groupId("acme", "x")
            val y = t.groupId("acme", "y")
            val lb = t.groupId("b")
            val lc = t.groupId("c")
            val leaves = listOf(x, y, lb, lc)
            // Each leaf's capped-or-not nodes, top-down, and the ceilings the topology declares on them.
            val above = mapOf(x to listOf(acme, x), y to listOf(acme), lb to listOf(lb), lc to emptyList())
            val limit = mapOf(acme to acmeCap, x to xCap, lb to bCap)
            val h = engine(t)
            repeat(30) { step ->
                val open = h.snapshot().leases
                if (open.isNotEmpty() && rng.nextInt(3) == 0) {
                    check(h.complete(open[rng.nextInt(open.size)].id, 1L) is LeaseOutcome.Done)
                    return@repeat
                }
                val at = "seed $seed step $step"
                val waiting = leaves.filter { rng.nextInt(4) != 0 }.toSet()
                val eligible = waiting.filter { rng.nextInt(3) != 0 }.toSet()
                val count = HashMap<GroupId, Int>()
                for (lease in open) for (g in above.getValue(lease.leaf)) count[g] = (count[g] ?: 0) + 1
                fun capOf(leaf: GroupId): WaitExplanation.AtCeiling? = above.getValue(leaf).firstNotNullOfOrNull { g ->
                    limit[g]?.takeIf { (count[g] ?: 0) >= it }?.let { WaitExplanation.AtCeiling(g, it) }
                }
                val reasons = leaves.associateWith { h.explain(it, waiting, eligible) }
                for (leaf in eligible) {
                    val expected = capOf(leaf)
                    if (expected != null) {
                        atCeiling++
                        if (expected.group != leaf) topmostAbove++
                        assertEquals(expected, reasons[leaf], "$at: ${leaf.value}")
                    } else {
                        assertTrue(reasons[leaf] !is WaitExplanation.AtCeiling, "$at: ${leaf.value} is not capped, told ${reasons[leaf]}")
                    }
                }
                val competing = waiting.filter { capOf(it) == null }
                val shares = leaves.associateWith { h.entitlement(it, waiting, 12L) }
                assertAll(
                    { assertTrue(waiting.filter { capOf(it) != null }.all { shares[it] == Rational.ZERO }, "$at: a capped leaf is owed nothing: $shares") },
                    {
                        val whole = if (competing.isEmpty()) Rational.ZERO else Rational.of(12L)
                        assertEquals(whole, competing.fold(Rational.ZERO) { sum, it -> sum + shares.getValue(it) }, "$at: the rest share every slot")
                    },
                )
                when (val out = h.acquire(waiting, eligible, startedAt = 0L)) {
                    is AcquireOutcome.Acquired -> {
                        picks++
                        assertEquals(WaitExplanation.WouldBePickedNext, reasons[out.lease.leaf], "$at: the pick")
                    }
                    AcquireOutcome.NothingEligible -> {
                        if (eligible.isNotEmpty()) allCapped++
                        assertTrue(eligible.all { capOf(it) != null }, "$at: nothing eligible, though $eligible is not all capped")
                    }
                    else -> fail("$at: an engine with capacity and four live lanes gave $out")
                }
            }
        }
        println("CEILING-AGREES atCeiling=$atCeiling topmostAbove=$topmostAbove picks=$picks allCapped=$allCapped")
        assertAll(
            { assertTrue(atCeiling > 100,"the rig: capped leaves were explained: $atCeiling") },
            { assertTrue(topmostAbove > 20, "the rig: a group above the leaf was the one named: $topmostAbove") },
            { assertTrue(picks > 300, "the rig: the acquires picked: $picks") },
            { assertTrue(allCapped > 10, "the rig: every eligible leaf was capped: $allCapped") },
        )
    }
}
