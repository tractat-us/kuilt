package us.tractat.kuilt.heddle

import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Random lease sequences on [LocalHeddle] (#3173, plan Task L1). Every seeded draw walks a `List`
 * the test built, never a `Set` or a map view, so a seed walks the same trajectory on every target.
 */
class LocalHeddlePropertyTest {

    private val root = GroupId("root")
    private val p = GroupId("p")
    private val q = GroupId("q")
    private val leaves = listOf(GroupId("a/b"), GroupId("#3"), GroupId("c-7f3a"), GroupId("é#%@"))

    /** `root → p:2 → {a/b:3, #3:1}`, `root → q:1 → {c-7f3a:1, é#%@:2}`. */
    private val ledger = shapedLedger(
        root,
        listOf(
            edgeOf(root, p, 2), edgeOf(root, q, 1),
            edgeOf(p, leaves[0], 3), edgeOf(p, leaves[1], 1),
            edgeOf(q, leaves[2], 1), edgeOf(q, leaves[3], 2),
        ),
    )

    private fun subset(rng: Random, of: List<GroupId>): List<GroupId> = of.filter { rng.nextInt(3) != 0 }

    @Test
    fun conservationHoldsUnderRandomLeaseSequences() {
        randomLeaseSequences(engine = { engineOver(ledger, root, supply = 200) })
    }

    /**
     * The same sequences with no supply up front and capacity reconciled once, so every unit is
     * minted by a top-up inside an `acquire` or `extend`, or forced by an overrun or an admission.
     */
    @Test
    fun conservationHoldsWithSupplyToppedUp() {
        var toppedUp = 0
        var funded = 0
        fun slots(seed: Int): Long = 1L + seed % 4
        randomLeaseSequences(
            engine = { seed ->
                engineOver(ledger, root, supply = 0, policy = LocalPolicy(defaultReservation = 3L)).also { it.reconcileCapacity(slots(seed)) }
            },
            after = { h -> if (h.snapshot().ledger.mintedTotal() > 0L) toppedUp++ },
            onOp = { h, seed, out ->
                val at = "seed $seed"
                if (out == AcquireOutcome.SupplyExhausted || out == LeaseOutcome.SupplyExhausted) fail("refused for supply with a capacity reconciled, $at")
                // After an acquire or extend that went through, the root holds at least the low watermark: one round.
                if (out is AcquireOutcome.Acquired || out == LeaseOutcome.Done(0L, 0L)) {
                    funded++
                    val held = h.snapshot().ledger.holdings(root, LOCAL)
                    if (held < slots(seed) * 3L) fail("the root holds $held after a funded operation, under the watermark ${slots(seed) * 3L}, $at")
                }
            },
        )
        assertAll(
            { assertEquals(SEEDS, toppedUp, "every seed minted its own supply") },
            { assertTrue(funded > SEEDS * STEPS / 4, "the rig: $funded funded acquires and extends") },
        )
    }

    /**
     * [onOp] sees each acquire's and each extend's outcome, after the operation. A cancel's outcome is
     * also `Done(0, 0)`, so it is not passed.
     */
    private fun randomLeaseSequences(
        engine: (seed: Int) -> LocalHeddle,
        after: (LocalHeddle) -> Unit = {},
        onOp: (LocalHeddle, seed: Int, outcome: Any) -> Unit = { _, _, _ -> },
    ) {
        var operations = 0
        val kinds = IntArray(5)
        for (seed in 0 until SEEDS) {
            val rng = Random(seed)
            val h = engine(seed)
            repeat(STEPS) { step ->
                val open = h.snapshot().leases
                val kind = rng.nextInt(5)
                when {
                    kind == 0 || open.isEmpty() -> {
                        val waiting = subset(rng, leaves)
                        val eligible = waiting.filter { rng.nextBoolean() }
                        onOp(h, seed, h.acquire(waiting.toSet(), eligible.toSet(), startedAt = step.toLong(), reservation = 1L + rng.nextInt(5)))
                        kinds[0]++
                    }
                    kind == 1 -> { onOp(h, seed, h.extend(open[rng.nextInt(open.size)].id, 1L + rng.nextInt(4))); kinds[1]++ }
                    kind == 2 -> { h.complete(open[rng.nextInt(open.size)].id, rng.nextInt(9).toLong()); kinds[2]++ }
                    kind == 3 -> { h.cancel(open[rng.nextInt(open.size)].id); kinds[3]++ }
                    else -> { h.admit(leaves[rng.nextInt(leaves.size)], startedAt = step.toLong(), reservation = 1L + rng.nextInt(5)); kinds[4]++ }
                }
                operations++
                assertConserved(h, "seed $seed step $step")
            }
            after(h)
        }
        assertAll(
            { assertEquals(SEEDS * STEPS, operations, "every step ran and was checked") },
            { assertTrue(kinds.all { it > 0 }, "every operation kind ran: ${kinds.toList()}") },
        )
    }

    private fun assertConserved(h: LocalHeddle, at: String) {
        val s = h.snapshot()
        val l = s.ledger
        val held = groupsOf(l).sumOf { l.holdings(it, LOCAL) }
        val leafHeld = leaves.sumOf { l.holdings(it, LOCAL) }
        val reserved = s.leases.sumOf { it.reserved }
        if (l.mintedTotal() != held + l.leafSpentTotal()) fail("Σ holdings + spent != minted at $at")
        if (reserved != leafHeld) fail("leaf holdings $leafHeld != open reservations $reserved at $at")
        if (l.validate().isNotEmpty()) fail("validate() at $at: ${l.validate()}")
    }

    /**
     * `previewPicks` equals the acquires it previews, wakers included, and changes nothing. Each
     * seed runs a random history first, with leaves dropping out of the waiting set, so the
     * allocator has observed some of them idle; the preview then has them waiting again.
     */
    @Test
    fun previewPicksEqualTheAcquiresTheyPreview() {
        var previews = 0
        var picked = 0
        var withAWaker = 0
        for (seed in 0 until PREVIEW_SEEDS) {
            val rng = Random(seed)
            val h = engineOver(ledger, root, supply = 10_000)
            repeat(30) { step ->
                val open = h.snapshot().leases
                if (open.size < 3) {
                    val waiting = subset(rng, leaves)
                    h.acquire(waiting.toSet(), waiting.filter { rng.nextBoolean() }.toSet(), step.toLong(), 1L + rng.nextInt(4))
                } else {
                    h.complete(open[rng.nextInt(open.size)].id, rng.nextInt(6).toLong())
                }
            }
            val queued = leaves.associateWith { rng.nextInt(4) }
            val eligible = leaves.filter { rng.nextInt(4) != 0 }.toSet()
            val reservations = List(1 + rng.nextInt(8)) { 1L + rng.nextInt(5) }

            val before = h.snapshot()
            val observed = before.allocatorMemory.demandingObserved
            if (leaves.any { queued.getValue(it) > 0 && it in eligible && observed[AttachmentId("${parentOf(it).value}>${it.value}")] == false }) withAWaker++
            val preview = h.previewPicks(queued, eligible, reservations)
            val after = h.snapshot()

            val remaining = HashMap(queued)
            val real = ArrayList<GroupId>()
            for (r in reservations) {
                val waiting = leaves.filter { remaining.getValue(it) > 0 }
                val out = h.acquire(waiting.toSet(), waiting.filter { it in eligible }.toSet(), startedAt = 0L, reservation = r)
                if (out !is AcquireOutcome.Acquired) break
                real += out.lease.leaf
                remaining[out.lease.leaf] = remaining.getValue(out.lease.leaf) - 1
            }
            assertAll(
                { assertEquals(real, preview, "seed $seed: the preview is what the acquires did") },
                { assertSame(before.ledger, after.ledger, "seed $seed: the preview left the ledger alone") },
                { assertEquals(before, after, "seed $seed: and the leases and the wake memory") },
            )
            previews++
            picked += preview.size
        }
        assertAll(
            { assertEquals(PREVIEW_SEEDS, previews) },
            { assertTrue(picked > PREVIEW_SEEDS, "previews picked something: $picked") },
            { assertTrue(withAWaker > 0, "some preview had a leaf the allocator last saw idle: $withAWaker") },
        )
    }

    private fun parentOf(leaf: GroupId): GroupId = if (leaf == leaves[0] || leaf == leaves[1]) p else q

    /**
     * Random lease traffic over a tree with siblings, interleaved with reshapes that close edges
     * (often on an open lease's path) and activate prepared ones (so a leaf becomes a lane, and a lane
     * whose last active child closed becomes a leaf). After every step:
     * - `acquire` returns [AcquireOutcome.NoLiveLineage] only when no eligible leaf is reachable from
     *   the root through ACTIVE edges, by an independent top-down walk; and then at the root, with the
     *   ledger the very same value;
     * - no operation throws: `extend` across a closing edge is [LeaseOutcome.PathDraining], and an
     *   overrun across one still settles;
     * - `minted == Σ holdings + spent`, every unit held below the root belongs to an open lease, and
     *   `validate()` is empty.
     *
     * Reparenting is outside the generator: two live inbound edges quarantine a group, which no walk
     * from the root can see, and which is not this property's subject.
     */
    @Test
    fun reshapesNeverStarveASiblingNorThrow() {
        reshapeSequences("supplied") { engineOver(reshapeLedger, root, supply = 100_000) }
    }

    /**
     * The same reshapes with no supply up front and a capacity reconciled, so every all-or-nothing
     * check above runs on the top-up path: a refused acquire and a draining extend leave no mint
     * behind, and supply never runs out.
     */
    @Test
    fun reshapesNeverStarveASiblingNorThrowWithSupplyToppedUp() {
        reshapeSequences("topped-up") { seed ->
            engineOver(reshapeLedger, root, supply = 0, policy = LocalPolicy(defaultReservation = 2L)).also { it.reconcileCapacity(1L + seed % 3) }
        }
    }

    private fun reshapeSequences(label: String, engine: (seed: Int) -> LocalHeddle) {
        var steps = 0
        val seen = IntArray(RIG.size)
        for (seed in 0 until SEEDS) {
            val rng = Random(seed)
            val h = engine(seed)
            repeat(RESHAPE_STEPS) { step ->
                val before = h.snapshot()
                val open = before.leases
                val l = before.ledger
                val draw = rng.nextInt(10)
                when {
                    draw <= 3 || open.isEmpty() && draw <= 6 -> {
                        val waiting = reshapeGroups.filter { rng.nextInt(3) != 0 }
                        val eligible = waiting.filter { rng.nextInt(3) != 0 }.toSet()
                        val reached = reachableEligibleLeaves(l, eligible)
                        val reachable = reached.isNotEmpty()
                        if (eligible.isNotEmpty() && !reachable) seen[NONE_REACHABLE]++
                        // The starvation shape: beside a reachable leaf, an eligible group the old live lift
                        // wanted but no descent can end at, a leaf behind a closing edge or a group with an
                        // active child.
                        if (reachable && eligible.any { it !in reached && isADeadEnd(l, it) }) seen[DEAD_END_BESIDE_REACHABLE]++
                        val out = h.acquire(waiting.toSet(), eligible, startedAt = step.toLong(), reservation = 1L + rng.nextInt(5))
                        val at = "seed $seed step $step"
                        when (out) {
                            is AcquireOutcome.NoLiveLineage -> {
                                if (reachable) fail("NoLiveLineage(${out.at.value}) with an eligible leaf reachable at $at")
                                if (out.at != root) fail("NoLiveLineage below the root, at ${out.at.value}, at $at")
                                if (h.snapshot().ledger !== l) fail("a refused acquire changed the ledger at $at")
                            }
                            is AcquireOutcome.Acquired -> {
                                val leaf = out.lease.leaf
                                val lineage = l.lineageOf(leaf).orEmpty()
                                if (leaf !in eligible || !l.isLeaf(leaf) || lineage.isEmpty() || lineage.any { l.lifecycle(it) != Lifecycle.ACTIVE }) {
                                    fail("leased ${leaf.value}, not an eligible leaf behind ACTIVE edges, at $at")
                                }
                            }
                            AcquireOutcome.NothingEligible -> if (eligible.isNotEmpty()) fail("NothingEligible with $eligible at $at")
                            AcquireOutcome.SupplyExhausted -> fail("the supply is sized never to run out, at $at")
                            is AcquireOutcome.PositionCeiling -> fail("no position nears its ceiling here, at $at")
                        }
                    }
                    open.isEmpty() -> reshape(h, rng, seen)
                    draw == 4 -> {
                        val lease = open[rng.nextInt(open.size)]
                        val out = h.extend(lease.id, 1L + rng.nextInt(4))
                        if (out is LeaseOutcome.PathDraining) {
                            seen[DRAINING_EXTEND]++
                            if (h.snapshot().ledger !== l) fail("a draining extend changed the ledger, seed $seed step $step")
                        }
                    }
                    draw == 5 -> {
                        val lease = open[rng.nextInt(open.size)]
                        val actual = rng.nextInt(10).toLong()
                        val path = l.lineageOf(lease.leaf).orEmpty()
                        // How many edges from the root are still ACTIVE: the overrun lands on the last of them.
                        val reach = path.indexOfFirst { l.lifecycle(it) != Lifecycle.ACTIVE }
                        val overrunDraining = reach >= 0 && actual > lease.reserved
                        if (overrunDraining) seen[OVERRUN_DRAINING]++
                        if (overrunDraining && reach >= 2) seen[OVERRUN_DEEP_REACH]++
                        val out = h.complete(lease.id, actual)
                        if (out != LeaseOutcome.Done(actual, maxOf(0L, actual - lease.reserved))) fail("complete gave $out, seed $seed step $step")
                        if (overrunDraining) {
                            val after = h.snapshot().ledger
                            fun grew(e: AttachmentId): Long = checkNotNull(after.edge(e)).spent - checkNotNull(l.edge(e)).spent
                            val at = "seed $seed step $step, path ${path.map { it.value }}"
                            if (grew(path[reach]) != lease.reserved) fail("the first draining edge was charged ${grew(path[reach])}, not the reservation, at $at")
                            if (reach >= 1 && grew(path[reach - 1]) != actual) {
                                fail("the deepest ACTIVE edge was charged ${grew(path[reach - 1])}, not the whole $actual, at $at")
                            }
                        }
                    }
                    draw == 6 -> h.cancel(open[rng.nextInt(open.size)].id)
                    draw == 7 -> h.admit(reshapeGroups[rng.nextInt(reshapeGroups.size)], step.toLong(), 1L + rng.nextInt(5))
                    else -> reshape(h, rng, seen)
                }
                steps++
                assertConservedEverywhere(h, "seed $seed step $step")
            }
        }
        println("LEASE-RESHAPE $label steps=$steps " + RIG.indices.joinToString(" ") { "${RIG[it]}=${seen[it]}" })
        assertAll(
            { assertEquals(SEEDS * RESHAPE_STEPS, steps, "every step ran and was checked") },
            { assertTrue(seen.all { it > 0 }, "every rig fired: ${RIG.indices.joinToString { "${RIG[it]}=${seen[it]}" }}") },
        )
    }

    /** A group with a live lineage that a descent cannot end at: behind a closing edge, or with an active child. */
    private fun isADeadEnd(l: EntitlementLedger, g: GroupId): Boolean {
        val lineage = l.lineageOf(g) ?: return false
        return lineage.isNotEmpty() && (!l.isLeaf(g) || lineage.any { l.lifecycle(it) == Lifecycle.CLOSING })
    }

    /** Close a random ACTIVE edge (two times in three) or activate a random PREPARED one. */
    private fun reshape(h: LocalHeddle, rng: Random, seen: IntArray) {
        val l = h.snapshot().ledger
        val edges = l.allEdges().sorted()
        val activating = rng.nextInt(3) == 0
        val from = if (activating) Lifecycle.PREPARED else Lifecycle.ACTIVE
        val pool = edges.filter { l.lifecycle(it) == from }
        if (pool.isEmpty()) return
        val edge = pool[rng.nextInt(pool.size)]
        if (activating) {
            h.reshapeForTest { it.piece(checkNotNull(it.activate(edge)).delta) }
            seen[ACTIVATED]++
        } else {
            val onAnOpenPath = h.snapshot().leases.any { edge in l.lineageOf(it.leaf).orEmpty() }
            h.reshapeForTest { it.piece(checkNotNull(it.close(edge)).delta) }
            if (onAnOpenPath) seen[CLOSED_UNDER_A_LEASE]++
        }
    }

    /** The groups of [eligible] that are leaves a top-down walk of ACTIVE edges from the root reaches. */
    private fun reachableEligibleLeaves(l: EntitlementLedger, eligible: Set<GroupId>): Set<GroupId> {
        val stack = ArrayDeque(listOf(root))
        val visited = HashSet<GroupId>()
        val found = HashSet<GroupId>()
        while (stack.isNotEmpty()) {
            val g = stack.removeLast()
            if (!visited.add(g)) continue
            for (child in l.activeChildren(g).map { checkNotNull(l.record(it.attachment)).child }) {
                if (child in eligible && l.isLeaf(child)) found += child
                stack.addLast(child)
            }
        }
        return found
    }

    /** Conservation over every group the ledger names, so a reshaped tree is covered too. */
    private fun assertConservedEverywhere(h: LocalHeddle, at: String) {
        val s = h.snapshot()
        val l = s.ledger
        val groups = groupsOf(l)
        val held = groups.sumOf { l.holdings(it, LOCAL) }
        val belowRoot = groups.filter { it != root }.sumOf { l.holdings(it, LOCAL) }
        val reserved = s.leases.sumOf { it.reserved }
        if (l.mintedTotal() != held + l.leafSpentTotal()) fail("Σ holdings + spent != minted at $at")
        if (reserved != belowRoot) fail("holdings below the root $belowRoot != open reservations $reserved at $at")
        if (l.validate().isNotEmpty()) fail("validate() at $at: ${l.validate()}")
    }

    private val d = GroupId("d")
    private val r = GroupId("r")

    /**
     * The fixed tree's shape with a direct leaf `d` beside the lanes, two of `q`'s children made
     * lanes over a leaf each (`c/w`, `é/z`, so some paths have three edges and an overrun can land
     * two edges down), and prepared edges waiting to activate: under a leaf (`a/b → a/b/x`, `d → d/y`), under a lane (`q → f`), and under the root
     * (`root → r`).
     */
    private val reshapeLedger = shapedLedger(
        root,
        active = listOf(
            edgeOf(root, p, 2), edgeOf(root, q, 1), edgeOf(root, d, 1),
            edgeOf(p, leaves[0], 3), edgeOf(p, leaves[1], 1),
            edgeOf(q, leaves[2], 1), edgeOf(q, leaves[3], 2),
            edgeOf(leaves[2], GroupId("c/w"), 1), edgeOf(leaves[3], GroupId("é/z"), 1),
        ),
        prepared = listOf(
            edgeOf(leaves[0], GroupId("a/b/x")), edgeOf(d, GroupId("d/y")), edgeOf(q, GroupId("f")), edgeOf(root, r, 2),
        ),
    )

    /** Every non-root group, lanes included, since a lane whose last active child closes is a leaf. */
    private val reshapeGroups = listOf(p, q, d, r, GroupId("a/b/x"), GroupId("d/y"), GroupId("f"), GroupId("c/w"), GroupId("é/z")) + leaves

    private companion object {
        const val SEEDS = 200
        const val STEPS = 60
        const val PREVIEW_SEEDS = 100
        const val RESHAPE_STEPS = 80

        val RIG = listOf(
            "noneReachable", "deadEndBesideReachable", "drainingExtend", "overrunDraining", "activated", "closedUnderALease",
            "overrunDeepReach",
        )
        const val NONE_REACHABLE = 0
        const val DEAD_END_BESIDE_REACHABLE = 1
        const val DRAINING_EXTEND = 2
        const val OVERRUN_DRAINING = 3
        const val ACTIVATED = 4
        const val CLOSED_UNDER_A_LEASE = 5
        const val OVERRUN_DEEP_REACH = 6
    }
}
