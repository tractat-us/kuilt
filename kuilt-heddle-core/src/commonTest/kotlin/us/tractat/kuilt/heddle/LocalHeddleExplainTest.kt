package us.tractat.kuilt.heddle

import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * [LocalHeddle.explain] and [LocalHeddle.entitlement] (#3173, plan Task E). The examples pin each
 * arm; [explainAgreesWithAcquire] pins that every arm agrees with the acquire that follows it, over
 * random histories with reshapes and a supply that runs out. Every seeded draw walks a `List` the
 * test built, never a `Set` or a map view.
 */
class LocalHeddleExplainTest {

    private val root = GroupId("root")
    private val p = GroupId("p")
    private val q = GroupId("q")
    private val a = GroupId("a/b")
    private val b = GroupId("#3")
    private val c = GroupId("c-7f3a")

    /** `root → p → {a/b, #3}`, `root → q → c-7f3a`, every weight 1. */
    private val twoLevels = shapedLedger(root, listOf(edgeOf(root, p), edgeOf(root, q), edgeOf(p, a), edgeOf(p, b), edgeOf(q, c)))

    /** Acquire with [waiting] as both sets, complete at the reservation, and return the leaf. */
    private fun cycle(h: LocalHeddle, waiting: Set<GroupId>): GroupId {
        val lease = h.lease(waiting)
        h.complete(lease.id, lease.reserved)
        return lease.leaf
    }

    @Test
    fun explainNamesEachReasonInTheOrderItChecks() {
        val h = engineOver(twoLevels, root, supply = 10)
        val broke = engineOver(twoLevels, root, supply = 0)
        val all = setOf(a, b, c)
        assertAll(
            { assertEquals(WaitExplanation.NotWaiting, h.explain(c, waiting = setOf(a, b), eligible = setOf(a, b))) },
            { assertEquals(WaitExplanation.NotEligible, h.explain(b, waiting = all, eligible = setOf(a))) },
            { assertEquals(WaitExplanation.NoLiveLineage(root), h.explain(root, all + root, all + root)) },
            { assertEquals(WaitExplanation.SupplyExhausted, broke.explain(a, all, all)) },
            { assertEquals(AcquireOutcome.SupplyExhausted, broke.acquire(all, all, startedAt = 0L)) },
            // NotWaiting outranks every other reason: the root is not waiting, so that is what it is told.
            { assertEquals(WaitExplanation.NotWaiting, broke.explain(root, all, all)) },
            {
                assertEquals(
                    listOf("NOT_WAITING", "NOT_ELIGIBLE", "NO_LIVE_LINEAGE", "SUPPLY_EXHAUSTED", "BEHIND_AT_LEVEL", "WOULD_BE_PICKED_NEXT"),
                    WaitExplanation.Kind.entries.map { it.name },
                    "the arms' order is part of the API",
                )
            },
        )
    }

    @Test
    fun explainNamesTheLevelThatIsAhead() {
        val h = engineOver(twoLevels, root, supply = 100)
        val all = setOf(a, b, c)
        // Nothing has run, so every tie breaks by id: the root picks `root>p`, and p picks `p>#3`.
        val fresh = all.associateWith { h.explain(it, all, all) }
        val first = cycle(h, all)
        // #3 ran, so p is a unit ahead of q at the root, which is now the level both of p's leaves are behind at.
        val after = all.associateWith { h.explain(it, all, all) }
        val second = (h.acquire(all, all, startedAt = 1L) as AcquireOutcome.Acquired).lease.leaf
        assertAll(
            { assertEquals(WaitExplanation.WouldBePickedNext, fresh[b]) },
            { assertEquals(WaitExplanation.BehindAtLevel(p, b, Rational.ZERO, Rational.ZERO, Rational.ZERO), fresh[a], "a/b is behind under p, not at the root") },
            { assertEquals(WaitExplanation.BehindAtLevel(root, p, Rational.ZERO, Rational.ZERO, Rational.ZERO), fresh[c]) },
            { assertEquals(b, first, "the acquire took the one it was told") },
            { assertEquals(WaitExplanation.BehindAtLevel(root, q, Rational.ONE, Rational.of(1L, 2L), Rational.of(1L, 2L)), after[a]) },
            { assertEquals(WaitExplanation.BehindAtLevel(root, q, Rational.ONE, Rational.of(1L, 2L), Rational.of(1L, 2L)), after[b], "the first level, not p") },
            { assertEquals(WaitExplanation.WouldBePickedNext, after[c]) },
            { assertEquals(c, second) },
        )
    }

    @Test
    fun explainReadsTheWakeMemoryAndChangesNothing() {
        val x = GroupId("x")
        val y = GroupId("y")
        val h = engineOver(shapedLedger(root, listOf(edgeOf(root, x), edgeOf(root, y))), root, supply = 1_000)
        repeat(50) { cycle(h, setOf(x)) }
        val before = h.snapshot()
        val both = setOf(x, y)
        // y was seen idle while x ran 50 slots, so it wakes clamped level with x, and loses the tie on id.
        val forY = h.explain(y, both, both)
        val forX = h.explain(x, both, both)
        val after = h.snapshot()
        val next = (h.acquire(both, both, startedAt = 0L) as AcquireOutcome.Acquired).lease.leaf
        assertAll(
            { assertEquals(false, before.allocatorMemory.demandingObserved[AttachmentId("root>y")], "the rig: y was observed idle") },
            { assertEquals(WaitExplanation.BehindAtLevel(root, x, Rational.of(50L), Rational.of(50L), Rational.of(50L)), forY) },
            { assertEquals(WaitExplanation.WouldBePickedNext, forX) },
            { assertEquals(x, next) },
            { assertSame(before.ledger, after.ledger, "explain left the ledger alone") },
            { assertEquals(before, after, "and the leases and the wake memory: y is still a waker") },
        )
    }

    @Test
    fun explainNamesNoLiveLineageBehindAClosingEdge() {
        val closed = twoLevels.piece(checkNotNull(twoLevels.close(AttachmentId("root>p"))).delta)
        val h = engineOver(closed, root, supply = 100)
        val all = setOf(a, b, c)
        val forA = h.explain(a, all, all)
        val forC = h.explain(c, all, all)
        val picked = (h.acquire(all, all, startedAt = 0L) as AcquireOutcome.Acquired).lease.leaf
        // With c-7f3a not waiting, nothing is reachable at all; a/b is still told its own reason.
        val alone = h.explain(a, setOf(a, b), setOf(a, b))
        val aloneAcquire = h.acquire(setOf(a, b), setOf(a, b), startedAt = 0L)
        assertAll(
            { assertEquals(WaitExplanation.NoLiveLineage(a), forA) },
            { assertEquals(WaitExplanation.WouldBePickedNext, forC) },
            { assertEquals(c, picked) },
            { assertEquals(WaitExplanation.NoLiveLineage(a), alone) },
            { assertEquals(AcquireOutcome.NoLiveLineage(root), aloneAcquire) },
        )
    }

    @Test
    fun explainNamesNoLiveLineageForAGroupThatGainedAChild() {
        val x = GroupId("a/b/x")
        val shaped = shapedLedger(root, listOf(edgeOf(root, p), edgeOf(p, a), edgeOf(root, q), edgeOf(q, c), edgeOf(a, x)))
        val h = engineOver(shaped, root, supply = 100)
        val waiting = setOf(a, c)
        assertAll(
            { assertEquals(WaitExplanation.NoLiveLineage(a), h.explain(a, waiting, waiting)) },
            { assertEquals(WaitExplanation.NoLiveLineage(p), h.explain(p, setOf(p, c), setOf(p, c)), "a lane is never a leaf") },
            { assertEquals(WaitExplanation.WouldBePickedNext, h.explain(c, waiting, waiting)) },
            { assertEquals(c, (h.acquire(waiting, waiting, startedAt = 0L) as AcquireOutcome.Acquired).lease.leaf) },
        )
    }

    @Test
    fun entitlementIsTheGpsShareOfTheCompetingSet() {
        /** `root → p:2 → {a/b:3, #3:1}`, `root → q:1 → c-7f3a`. */
        val weighted = shapedLedger(root, listOf(edgeOf(root, p, 2), edgeOf(root, q, 1), edgeOf(p, a, 3), edgeOf(p, b, 1), edgeOf(q, c)))
        val h = engineOver(weighted, root, supply = 0)
        val all = setOf(a, b, c)
        assertAll(
            { assertEquals(listOf(6L, 2L, 4L).map(Rational::of), listOf(a, b, c).map { h.entitlement(it, all, totalSlots = 12) }) },
            { assertEquals(listOf(9L, 3L, 0L).map(Rational::of), listOf(a, b, c).map { h.entitlement(it, setOf(a, b), 12) }, "c-7f3a is not waiting") },
            { assertEquals(listOf(8L, 4L).map(Rational::of), listOf(a, c).map { h.entitlement(it, setOf(a, c), 12) }) },
            { assertEquals(Rational.of(1L, 2L), h.entitlement(a, all, 1), "a share, not a count") },
            { assertEquals(Rational.ZERO, h.entitlement(p, setOf(p, a), 12), "a lane is not a leaf") },
            { assertEquals(Rational.ZERO, h.entitlement(root, all + root, 12)) },
        )
    }

    @Test
    fun entitlementCountsOnlyWorkADescentCanReach() {
        val x = GroupId("c-7f3a/x")
        val weighted = shapedLedger(
            root,
            listOf(edgeOf(root, p, 2), edgeOf(root, q, 1), edgeOf(p, a, 3), edgeOf(p, b, 1), edgeOf(q, c)),
            prepared = listOf(edgeOf(c, x)),
        )
        val closing = weighted.piece(checkNotNull(weighted.close(AttachmentId("q>c-7f3a"))).delta)
        val gainedAChild = weighted.piece(checkNotNull(weighted.activate(AttachmentId("c-7f3a>c-7f3a/x"))).delta)
        val all = setOf(a, b, c)
        val shares = listOf(closing, gainedAChild).map { ledger ->
            val h = engineOver(ledger, root, supply = 0)
            listOf(a, b, c).map { h.entitlement(it, all, 12) }
        }
        val owed = listOf(9L, 3L, 0L).map(Rational::of)
        assertAll(
            { assertEquals(owed, shares[0], "behind a closing edge, c-7f3a and so q compete for nothing") },
            { assertEquals(owed, shares[1], "nor as a group with an active child") },
        )
    }

    @Test
    fun explainTakesTheFrontOverEveryoneWaiting() {
        val x = GroupId("x")
        val y = GroupId("y")
        val z = GroupId("z")
        val h = engineOver(shapedLedger(root, listOf(edgeOf(root, x), edgeOf(root, y), edgeOf(root, z))), root, supply = 100)
        val waiting = setOf(x, y, z)
        // z waits through four slots it cannot run, so x and y run two each.
        repeat(4) {
            val lease = (h.acquire(waiting, setOf(x, y), startedAt = 0L) as AcquireOutcome.Acquired).lease
            h.complete(lease.id, lease.reserved)
        }
        // y ties x and loses on id: its side is at 2, not above the eligible front of x and y (2), so
        // it was a candidate. It is above the front of all three waiting (4/3), which says z is owed.
        assertEquals(
            WaitExplanation.BehindAtLevel(
                root, x, leafSideVirtualService = Rational.of(2L), front = Rational.of(4L, 3L), eligibleFront = Rational.of(2L),
            ),
            h.explain(y, waiting, setOf(x, y)),
        )
    }

    @Test
    fun explainSeparatesTheShareLineFromThePicksLine() {
        val x = GroupId("x")
        val y = GroupId("y")
        val z = GroupId("z")
        val h = engineOver(shapedLedger(root, listOf(edgeOf(root, x), edgeOf(root, y, 10), edgeOf(root, z))), root, supply = 100)
        val waiting = setOf(x, y, z)
        // Everyone waits throughout; z runs ten slots, then y ten, so ev(x) = 0, ev(y) = 10/10, ev(z) = 10.
        for (only in listOf(z, y)) repeat(10) {
            val lease = (h.acquire(waiting, setOf(only), startedAt = 0L) as AcquireOutcome.Acquired).lease
            h.complete(lease.id, lease.reserved)
        }
        val why = h.explain(y, waiting, setOf(x, y), reservation = 5L)
        // y's side (1) is below the share line of all three (20/12), yet above the line the pick drew
        // over x and y (10/11): it was not a candidate, so it lost on eligibility, not on its deadline.
        assertAll(
            { assertEquals(WaitExplanation.BehindAtLevel(root, x, Rational.ONE, Rational.of(5L, 3L), Rational.of(10L, 11L)), why) },
            { assertEquals(x, (h.acquire(waiting, setOf(x, y), startedAt = 0L, reservation = 5L) as AcquireOutcome.Acquired).lease.leaf) },
        )
    }

    @Test
    fun entitlementAgreesWithTheLongRunShare() {
        val weighted = shapedLedger(root, listOf(edgeOf(root, p, 2), edgeOf(root, q, 1), edgeOf(p, a, 3), edgeOf(p, b, 1), edgeOf(q, c)))
        val h = engineOver(weighted, root, supply = 1_200)
        val all = setOf(a, b, c)
        val owed = all.associateWith { h.entitlement(it, all, totalSlots = 1_200) }
        val picks = (0 until 1_200).map { cycle(h, all) }
        val checks = listOf(a, b, c).map { leaf ->
            {
                val got = picks.count { it == leaf }.toLong()
                val want = checkNotNull(owed[leaf]).numerator
                assertTrue(got in want - 2..want + 2, "${leaf.value}: $got picks against an entitlement of $want")
            }
        }
        assertAll(
            { assertEquals(listOf(600L, 200L, 400L).map(Rational::of), listOf(a, b, c).map { owed[it] }) },
            *checks.toTypedArray(),
        )
    }

    /**
     * Random histories over a tree with siblings, lanes and prepared edges, interleaved with reshapes
     * that close ACTIVE edges and activate PREPARED ones, on a supply that may run out. Before each
     * acquire every group is explained, and then:
     * - the explains changed nothing, ledger, leases or wake memory;
     * - `WouldBePickedNext` exactly for the leaf the acquire picks, and for no other;
     * - `NotWaiting` and `NotEligible` exactly off the two sets;
     * - for an eligible group, `NoLiveLineage` exactly when an independent top-down walk of ACTIVE
     *   edges from the root does not reach it as a leaf;
     * - `SupplyExhausted` exactly when the acquire is refused for supply and the group was otherwise
     *   pickable;
     * - `BehindAtLevel(parent, nextChild)` only when the acquire picked another leaf, the two
     *   lineages share every edge above [parent], and the picked one goes on to `nextChild` there,
     *   which is not on the group's own lineage.
     */
    @Test
    fun explainAgreesWithAcquire() {
        explainAgreement("supplied", absent = FUNDED_BY_TOP_UP) { rng -> engineOver(reshapeLedger, root, supply = 10L + rng.nextInt(150)) }
    }

    /**
     * The same histories with no supply up front and a capacity reconciled under a non-zero low
     * watermark, so every acquire's supply comes from a top-up. `explain` must see that top-up:
     * it never says `SupplyExhausted`, and an acquire the root alone could not have funded is
     * explained exactly like any other.
     */
    @Test
    fun explainAgreesWithAcquireWithSupplyToppedUp() {
        explainAgreement("topped-up", absent = WaitExplanation.Kind.SUPPLY_EXHAUSTED.ordinal) { rng ->
            engineOver(reshapeLedger, root, supply = 0L, policy = LocalPolicy(defaultReservation = 2L, supply = SupplyPolicy(lowWatermarkSlots = 1L, targetSlots = 2L)))
                .also { it.reconcileCapacity(1L + rng.nextInt(3)) }
        }
    }

    /** The random histories, with every rig firing except [absent], which must never fire. */
    private fun explainAgreement(label: String, absent: Int, engine: (Random) -> LocalHeddle) {
        var acquires = 0
        val seen = IntArray(RIG.size)
        for (seed in 0 until SEEDS) {
            val rng = Random(seed)
            val h = engine(rng)
            repeat(STEPS) { step ->
                val open = h.snapshot().leases
                val draw = rng.nextInt(10)
                when {
                    draw <= 4 || open.isEmpty() && draw <= 6 -> {
                        explainThenAcquire(h, rng, seen, "seed $seed step $step")
                        acquires++
                    }
                    open.isEmpty() -> reshape(h, rng)
                    draw == 5 -> h.complete(open[rng.nextInt(open.size)].id, rng.nextInt(8).toLong())
                    draw == 6 -> h.cancel(open[rng.nextInt(open.size)].id)
                    else -> reshape(h, rng)
                }
            }
        }
        println("EXPLAIN-AGREES $label acquires=$acquires " + RIG.indices.joinToString(" ") { "${RIG[it]}=${seen[it]}" })
        assertAll(
            { assertTrue(acquires > SEEDS * STEPS / 3, "the explain-then-acquire arm ran: $acquires") },
            { assertTrue(seen.indices.all { it == absent || seen[it] > 0 }, "every rig fired: ${RIG.indices.joinToString { "${RIG[it]}=${seen[it]}" }}") },
            { assertEquals(0, seen[absent], "${RIG[absent]} never fires in the $label arm") },
        )
    }

    private fun explainThenAcquire(h: LocalHeddle, rng: Random, seen: IntArray, at: String) {
        val waitingList = explained.filter { rng.nextInt(3) != 0 }
        val waiting = waitingList.toSet()
        val eligible = waitingList.filter { rng.nextInt(3) != 0 }.toSet()
        val reservation = 1L + rng.nextInt(5)
        val before = h.snapshot()
        val l = before.ledger
        val pickable = delegableByWalk(l)
        if (before.allocatorMemory.demandingObserved.any { (edge, demanding) -> !demanding && l.record(edge)?.child in pickable && l.record(edge)?.child in eligible }) {
            seen[WAKER_ELIGIBLE]++
        }

        val reasons = explained.associateWith { h.explain(it, waiting, eligible, reservation) }
        val after = h.snapshot()
        if (after.ledger !== l || after != before) fail("explain changed the engine at $at")

        val out = h.acquire(waiting, eligible, startedAt = 0L, reservation = reservation)
        if (out is AcquireOutcome.Acquired && l.holdings(root, LOCAL) < reservation) seen[FUNDED_BY_TOP_UP]++
        val picked = (out as? AcquireOutcome.Acquired)?.lease?.leaf
        val pickedLineage = picked?.let { checkNotNull(l.lineageOf(it)) }
        for (g in explained) {
            val reason = reasons.getValue(g)
            seen[reason.kind.ordinal]++
            val where = "${g.value} told $reason, acquire gave $out, at $at"
            val expectedBySets = when {
                g !in waiting -> WaitExplanation.NotWaiting
                g !in eligible -> WaitExplanation.NotEligible
                g !in pickable -> WaitExplanation.NoLiveLineage(g)
                out == AcquireOutcome.SupplyExhausted -> WaitExplanation.SupplyExhausted
                else -> null
            }
            if (expectedBySets != null) {
                if (reason != expectedBySets) fail("expected $expectedBySets: $where")
                if (reason is WaitExplanation.NoLiveLineage) {
                    if (l.lineageOf(g)?.any { l.lifecycle(it) == Lifecycle.CLOSING } == true) seen[BEHIND_CLOSING]++
                    if (l.lineageOf(g)?.isNotEmpty() == true && !l.isLeaf(g)) seen[GAINED_A_CHILD]++
                }
                continue
            }
            // Eligible, pickable, funded: the acquire picked a leaf, this one or another.
            val lineage = checkNotNull(l.lineageOf(g))
            if (picked == null || pickedLineage == null) fail("nothing was picked although ${g.value} could be: $where")
            when (reason) {
                WaitExplanation.WouldBePickedNext -> if (picked != g) fail("told it goes next, but did not: $where")
                is WaitExplanation.BehindAtLevel -> {
                    if (picked == g) fail("told it is behind, but it was picked: $where")
                    val level = lineage.indexOfFirst { l.record(it)?.parent == reason.parent }
                    if (level < 0) fail("the level named is not on its lineage: $where")
                    if (lineage.subList(0, level) != pickedLineage.take(level)) fail("the pick left its lineage above the level named: $where")
                    if (lineage[level] == pickedLineage.getOrNull(level)) fail("the pick did not leave its lineage at the level named: $where")
                    if (l.record(pickedLineage[level])?.child != reason.nextChild) fail("the pick went to another child than the one named: $where")
                    if (reason.parent != root) seen[BEHIND_BELOW_ROOT]++
                }
                else -> fail("an eligible, pickable, funded group told $reason: $where")
            }
        }
        if (picked != null && reasons[picked] != WaitExplanation.WouldBePickedNext) fail("picked ${picked.value} but it was told ${reasons[picked]}, at $at")
    }

    /** Close a random ACTIVE edge (two times in three) or activate a random PREPARED one. */
    private fun reshape(h: LocalHeddle, rng: Random) {
        val l = h.snapshot().ledger
        val activating = rng.nextInt(3) == 0
        val from = if (activating) Lifecycle.PREPARED else Lifecycle.ACTIVE
        val pool = l.allEdges().sorted().filter { l.lifecycle(it) == from }
        if (pool.isEmpty()) return
        val edge = pool[rng.nextInt(pool.size)]
        h.reshapeForTest { if (activating) it.piece(checkNotNull(it.activate(edge)).delta) else it.piece(checkNotNull(it.close(edge)).delta) }
    }

    /** Every group a top-down walk of ACTIVE edges from the root reaches and finds a leaf. */
    private fun delegableByWalk(l: EntitlementLedger): Set<GroupId> {
        val stack = ArrayDeque(listOf(root))
        val visited = HashSet<GroupId>()
        val found = HashSet<GroupId>()
        while (stack.isNotEmpty()) {
            val g = stack.removeLast()
            if (!visited.add(g)) continue
            for (child in l.activeChildren(g).map { checkNotNull(l.record(it.attachment)).child }) {
                if (l.isLeaf(child)) found += child
                stack.addLast(child)
            }
        }
        return found
    }

    private val d = GroupId("d")
    private val r = GroupId("r")
    private val e = GroupId("é#%@")

    /**
     * `root → {p:2, q:1, d:1}`, `p → {a/b:3, #3:1}`, `q → {c-7f3a:1, é#%@:2}`, with `c-7f3a` and
     * `é#%@` lanes over a leaf each, so some lineages have three edges; prepared edges under a leaf
     * (`a/b → a/b/x`, `d → d/y`), under a lane (`q → f`) and under the root (`root → r`).
     */
    private val reshapeLedger = shapedLedger(
        root,
        active = listOf(
            edgeOf(root, p, 2), edgeOf(root, q, 1), edgeOf(root, d, 1),
            edgeOf(p, a, 3), edgeOf(p, b, 1),
            edgeOf(q, c, 1), edgeOf(q, e, 2),
            edgeOf(c, GroupId("c/w"), 1), edgeOf(e, GroupId("é/z"), 1),
        ),
        prepared = listOf(edgeOf(a, GroupId("a/b/x")), edgeOf(d, GroupId("d/y")), edgeOf(q, GroupId("f")), edgeOf(root, r, 2)),
    )

    /** Every group, the root and the lanes included: a lane whose last active child closes is a leaf. */
    private val explained = listOf(root, p, q, d, r, a, b, c, e, GroupId("a/b/x"), GroupId("d/y"), GroupId("f"), GroupId("c/w"), GroupId("é/z"))

    private companion object {
        const val SEEDS = 200
        const val STEPS = 60

        /** One counter per [WaitExplanation.Kind], by ordinal, then the shapes that matter most. */
        val RIG = WaitExplanation.Kind.entries.map { it.name } + listOf("behindBelowRoot", "behindClosing", "gainedAChild", "wakerEligible", "fundedByTopUp")
        val BEHIND_BELOW_ROOT = WaitExplanation.Kind.entries.size
        val BEHIND_CLOSING = BEHIND_BELOW_ROOT + 1
        val GAINED_A_CHILD = BEHIND_BELOW_ROOT + 2
        val WAKER_ELIGIBLE = BEHIND_BELOW_ROOT + 3

        /** An acquire that leased with the root holding less than its reservation: only a top-up funds that. */
        val FUNDED_BY_TOP_UP = BEHIND_BELOW_ROOT + 4
    }
}
