package us.tractat.kuilt.heddle

import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [ShareLine.share] (#3550): the top-down product of weight fractions over competing siblings.
 *
 * The oracle is [Model], a split of the weights each generated tree was built with, walked from the
 * tree the test chose and the leases it opened, never from the ledger. The other properties (the
 * children split their parent, the root is 1 or 0, a leaf's share is its entitlement of one slot,
 * the order of `waiting` changes nothing, a snapshot's view draws the engine's line) run over the
 * same cases. Every seeded draw walks a `List` the test built, never a `Set` or a map view.
 */
class ShareLineTest {

    private val root = GroupId("root")
    private val p = GroupId("p")
    private val q = GroupId("q")
    private val r = GroupId("r")
    private val a = GroupId("a")
    private val b = GroupId("b")
    private val c = GroupId("c")
    private val d = GroupId("d")
    private val e = GroupId("e")

    /** `root → p:2 → {a:3, b:1, d:2}`, `root → q:1 → c`, `root → r:1 → e`. */
    private val threeTeams = shapedLedger(
        root,
        listOf(
            edgeOf(root, p, 2), edgeOf(root, q, 1), edgeOf(root, r, 1),
            edgeOf(p, a, 3), edgeOf(p, b, 1), edgeOf(p, d, 2), edgeOf(q, c), edgeOf(r, e),
        ),
    )

    @Test
    fun aShareIsTheProductOfWeightFractionsDownTheLineage() {
        val h = engineOver(threeTeams, root, supply = 0)
        val waiting = setOf(a, b, c)
        val line = h.shareLine(waiting)
        assertAll(
            { assertEquals(Rational.ONE, line.share(root)) },
            { assertEquals(Rational.of(2L, 3L), line.share(p), "r has nothing waiting, so p and q split the root 2 : 1") },
            { assertEquals(Rational.of(1L, 3L), line.share(q)) },
            { assertEquals(Rational.ZERO, line.share(r)) },
            { assertEquals(Rational.of(1L, 2L), line.share(a), "d is not waiting, so a takes 3/4 of p") },
            { assertEquals(Rational.of(1L, 6L), line.share(b)) },
            { assertEquals(Rational.ZERO, line.share(d)) },
            { assertEquals(Rational.of(1L, 3L), line.share(c)) },
            { assertEquals(Rational.ZERO, line.share(GroupId("nowhere"))) },
            { assertEquals(Rational.ZERO, h.shareLine(emptySet()).share(root), "nothing competes, so the root is owed nothing") },
            { assertEquals(Rational.ZERO, h.shareLine(setOf(p, root)).share(root), "a group and the root are not leaves a pick ends at") },
        )
    }

    /**
     * A ceiling is binary: below it a lane keeps its whole weight share, more than the one lease it
     * has room for; at it the lane competes for nothing and its sibling takes the root.
     */
    @Test
    fun aCeilingIsBinaryNotACappedRate() {
        val tree = draft("root") {
            lane("deploys", Weight.of(3), maxConcurrent = 1)
            lane("builds")
        }
        val deploys = tree.groupId("deploys")
        val builds = tree.groupId("builds")
        val h = LocalHeddle.bootWhole(tree, LocalPolicy(defaultReservation = 1L), epoch = 0L).also { it.mintSupply(10L) }
        val both = setOf(deploys, builds)
        val before = h.shareLine(both)
        h.lease(setOf(deploys))
        val after = h.shareLine(both)
        assertAll(
            { assertEquals(Rational.of(3L, 4L), before.share(deploys), "below its ceiling of one lease, deploys is owed 3/4 of the pool") },
            { assertEquals(Rational.ZERO, after.share(deploys), "at its ceiling it competes for nothing") },
            { assertEquals(Rational.ONE, after.share(builds)) },
            { assertEquals(Rational.of(3L, 4L), before.share(deploys), "a line keeps what it read: the lease did not move it") },
        )
    }

    @Test
    fun shareIsTheSplitOfTheWeightsTheTestChose() {
        var interior = 0
        var cappedByALease = 0
        forEachCase { case, waiting, line ->
            val owed = case.model.shares(waiting.toSet())
            for (id in case.model.ids) assertEquals(owed.getValue(id), line.share(id), "${case.label}: ${id.value}")
            assertEquals(Rational.ZERO, line.share(GroupId("nowhere")), case.label)
            interior += case.model.groups.count { owed.getValue(it.id).let { s -> s > Rational.ZERO && s < Rational.ONE } }
            cappedByALease += case.model.lanes.count { it.id in waiting && case.model.cappedByALease(it) }
        }
        assertAll(
            { assertTrue(interior > SEEDS, "interior groups owed part of the root: $interior") },
            { assertTrue(cappedByALease > 0, "a waiting lane held off by an open lease, not by a ceiling of 0: $cappedByALease") },
        )
    }

    @Test
    fun childrenSplitTheirParentsShare() {
        var withAnIdleSibling = 0
        forEachCase { case, waiting, line ->
            for (group in listOf(case.model.root) + case.model.groups) {
                val parent = line.share(group.id)
                val children = group.children.fold(Rational.ZERO) { sum, child -> sum + line.share(child.id) }
                assertEquals(parent, children, "${case.label}: the children of ${group.id.value}")
                if (parent > Rational.ZERO && group.children.any { line.share(it.id) == Rational.ZERO }) withAnIdleSibling++
            }
        }
        assertTrue(withAnIdleSibling > SEEDS, "a competing parent with a child that does not compete: $withAnIdleSibling")
    }

    @Test
    fun theRootIsOneWhenAnythingCompetesAndZeroOtherwise() {
        var one = 0
        var zero = 0
        forEachCase { case, waiting, line ->
            val anyLane = case.model.lanes.any { case.model.competes(it, waiting.toSet()) }
            val expected = if (anyLane) Rational.ONE else Rational.ZERO
            assertEquals(expected, line.share(case.model.root.id), case.label)
            if (anyLane) one++ else zero++
        }
        assertAll(
            { assertTrue(one > SEEDS, "roots owed the pool: $one") },
            { assertTrue(zero > 0, "roots with nothing competing: $zero") },
        )
    }

    @Test
    fun aLeafsShareIsItsEntitlementOfOneSlot() {
        var owedSomething = 0
        forEachCase { case, waiting, line ->
            val asSet = waiting.toSet()
            for (leaf in case.model.leaves) {
                val share = line.share(leaf.id)
                assertEquals(case.heddle.entitlement(leaf.id, asSet, totalSlots = 1L), share, "${case.label}: ${leaf.id.value}")
                if (share > Rational.ZERO) owedSomething++
            }
        }
        assertTrue(owedSomething > SEEDS, "leaves owed a share: $owedSomething")
    }

    @Test
    fun theOrderWaitingIsGivenInChangesNothing() {
        forEachCase { case, waiting, line ->
            val sorted = case.heddle.shareLine(LinkedHashSet(waiting.sorted()))
            val reversed = case.heddle.shareLine(LinkedHashSet(waiting.sortedDescending()))
            for (id in case.model.ids) {
                assertAll(
                    { assertEquals(line.share(id), sorted.share(id), "${case.label}: ${id.value}, sorted") },
                    { assertEquals(line.share(id), reversed.share(id), "${case.label}: ${id.value}, reversed") },
                )
            }
        }
    }

    @Test
    fun aSnapshotViewDrawsTheEngineLine() {
        forEachCase { case, waiting, line ->
            val viewed = case.heddle.persist().view(case.policy).shareLine(LinkedHashSet(waiting))
            for (id in case.model.ids) assertEquals(line.share(id), viewed.share(id), "${case.label}: ${id.value}")
        }
    }

    /**
     * The picks beneath a group track its share over a long run, as each leaf's track its
     * entitlement in [LocalHeddleExplainTest.entitlementAgreesWithTheLongRunShare]. `d` and `e` are
     * never waiting, so `r` competes for nothing and `p`'s share is `2/3`, not the `2/4` a count of
     * every sibling would give it.
     */
    @Test
    fun picksBeneathAGroupTrackItsShare() {
        val h = engineOver(threeTeams, root, supply = RUN)
        val waiting = setOf(a, b, c)
        val line = h.shareLine(waiting)
        val beneath = mapOf(root to setOf(a, b, c), p to setOf(a, b), q to setOf(c), r to emptySet())
        val picks = (0 until RUN).map {
            val lease = h.lease(waiting)
            h.complete(lease.id, lease.reserved, waiting = emptySet())
            lease.leaf
        }
        val checks = listOf(root, p, q, r).map { group ->
            {
                val got = picks.count { it in beneath.getValue(group) }.toLong()
                val owed = line.share(group) * Rational.of(RUN)
                assertEquals(1L, owed.denominator, "${group.value} is owed a whole number of $RUN picks: $owed")
                assertTrue(got in owed.numerator - 2..owed.numerator + 2, "${group.value}: $got picks against a share of $owed")
            }
        }
        assertAll(
            { assertEquals(listOf(1_200L, 800L, 400L, 0L).map(Rational::of), listOf(root, p, q, r).map { line.share(it) * Rational.of(RUN) }) },
            *checks.toTypedArray(),
        )
    }

    /**
     * A leaf whose share fits under a group whose share does not, in a topology `bootWhole` accepts:
     * `share(b)` is `(2³²−1)² / 2⁶⁴`, too fine for `Long`, while `share(c)` multiplies it by
     * `2²⁰ / (2³²−1)` and fits. Only cancelling one level's numerator against another level's
     * denominator gets there, so multiplying level by level would throw on the way. The sum of
     * `c`'s entitlement takes that way and throws, the product-versus-sum difference #3550 names.
     */
    @Test
    fun aLeafFitsUnderAGroupThatDoesNot() {
        val big = (1L shl 32) - 1
        val tree = draft("root") {
            group("a", Weight.of(big)) {
                group("b", Weight.of(big)) {
                    lane("c", Weight.of(1L shl 20))
                    lane("c2", Weight.of(big - (1L shl 20)))
                }
                lane("b2")
            }
            lane("a2")
        }
        val h = LocalHeddle.bootWhole(tree, LocalPolicy(defaultReservation = 1L), epoch = 0L)
        val c = tree.groupId("a", "b", "c")
        val waiting = setOf(c, tree.groupId("a", "b", "c2"), tree.groupId("a", "b2"), tree.groupId("a2"))
        val line = h.shareLine(waiting)
        assertAll(
            { assertEquals(Rational.of(big, 1L shl 44), line.share(c)) },
            { assertFailsWith<ArithmeticException>("b's own share is too fine") { line.share(tree.groupId("a", "b")) } },
            { assertFailsWith<ArithmeticException>("entitlement multiplies level by level") { h.entitlement(c, waiting, totalSlots = 1L) } },
            { assertEquals(Rational.of(big, 1L shl 32), line.share(tree.groupId("a"))) },
        )
    }

    /**
     * Three levels each split evenly between two weights, `3¹³`, `5⁹` and `7⁷`, so each level's
     * fraction is `w / 2w` and the leaf's share is `1/8`. Across levels nothing cancels, since the
     * weights share no factor, so the denominators multiply to `8 · 3¹³ · 5⁹ · 7⁷ > 2⁶³` unless each
     * level's fraction is reduced on its own first.
     */
    @Test
    fun anEvenSplitReducesWithinItsOwnLevel() {
        val tree = draft("root") {
            group("g1", Weight.of(1_594_323L)) {
                group("g2", Weight.of(1_953_125L)) {
                    lane("leaf", Weight.of(823_543L))
                    lane("s3", Weight.of(823_543L))
                }
                lane("s2", Weight.of(1_953_125L))
            }
            lane("s1", Weight.of(1_594_323L))
        }
        val h = LocalHeddle.bootWhole(tree, LocalPolicy(defaultReservation = 1L), epoch = 0L)
        val leaf = tree.groupId("g1", "g2", "leaf")
        val line = h.shareLine(setOf(leaf, tree.groupId("g1", "g2", "s3"), tree.groupId("g1", "s2"), tree.groupId("s1")))
        assertAll(
            { assertEquals(Rational.of(1L, 8L), line.share(leaf)) },
            { assertEquals(Rational.of(1L, 4L), line.share(tree.groupId("g1", "g2"))) },
        )
    }

    /**
     * A chain `g1 → … → g5`, each `gᵢ` of weight 1 beside a leaf `hᵢ` of weight `1/pᵢ` for a prime
     * `pᵢ` near 30,000, so `share(gᵢ) = Π pⱼ / (pⱼ + 1)` and nothing cancels. Four levels fit a
     * `Long` denominator; five do not. The row that does not fit throws, and every other row of the
     * same line still reads.
     */
    @Test
    fun aShareThatDoesNotFitThrowsForItsRowAlone() {
        val primes = listOf(29_917L, 29_921L, 29_927L, 29_947L, 29_959L)
        val g = List(primes.size + 1) { if (it == 0) root else GroupId("g$it") }
        val h = List(primes.size) { GroupId("h${it + 1}") }
        val edges = primes.indices.flatMap { i ->
            listOf(
                edgeOf(g[i], g[i + 1]),
                AttachmentRecord(AttachmentId("${g[i].value}>${h[i].value}"), g[i], h[i], Weight.of(1L, primes[i])),
            )
        }
        val line = engineOver(shapedLedger(root, edges), root, supply = 0).shareLine(h.toSet() + g.last())
        val fourLevels = primes.take(4).fold(Rational.ONE) { share, p -> share * Rational.of(p, p + 1) }
        assertAll(
            { assertEquals(fourLevels, line.share(g[4]), "four levels fit") },
            { assertEquals(Rational.of(1L, primes[0] + 1), line.share(h[0])) },
            { assertFailsWith<ArithmeticException> { line.share(g[5]) } },
            { assertFailsWith<ArithmeticException> { line.share(h[4]) } },
            { assertEquals(Rational.ONE, line.share(root), "and the root still reads after them") },
        )
    }

    // ── Generated cases ─────────────────────────────────────────────────────────────────────────

    /** One tree, booted, with some leases left open, and the [Model] the test built it from. */
    private class Case(val label: String, val heddle: LocalHeddle, val policy: LocalPolicy, val model: Model)

    /** Each case, asked [QUESTIONS] waiting sets drawn over its names, in a drawn order. */
    private fun forEachCase(check: (Case, List<GroupId>, ShareLine) -> Unit) {
        for (seed in 0 until SEEDS) {
            val rng = Random(seed)
            val case = caseOf(seed, rng)
            repeat(QUESTIONS) { round ->
                val waiting = (case.model.ids + GroupId("nowhere")).filter { rng.nextInt(3) != 0 }.shuffled(rng)
                check(Case("seed $seed, question $round", case.heddle, case.policy, case.model), waiting, case.heddle.shareLine(LinkedHashSet(waiting)))
            }
        }
    }

    private fun caseOf(seed: Int, rng: Random): Case {
        val topology = Topology("root", nodes(rng, depth = 0))
        val policy = LocalPolicy(defaultReservation = 1L)
        val h = LocalHeddle.bootWhole(topology, policy, epoch = 0L)
        h.mintSupply(1_000L)
        val attached = HashMap<List<String>, List<String>>()
        fun attachUnder(path: List<String>, nodes: List<Topology.Node>) {
            for (node in nodes) {
                if (node !is Topology.Group) continue
                if (node.template != null) {
                    val names = List(rng.nextInt(3)) { "t$it" }
                    for (name in names) check(h.attach(path + node.name, name, generation = 1L).refusals.isEmpty()) { "attach $name" }
                    attached[path + node.name] = names
                }
                attachUnder(path + node.name, node.children)
            }
        }
        attachUnder(emptyList(), topology.children)
        val model = Model.of(topology, attached)
        // Leave some leases open, so a ceiling above zero can hold.
        repeat(rng.nextInt(5)) {
            val wanted = model.lanes.map { it.id }.filter { rng.nextBoolean() }.toSet()
            val out = h.acquire(wanted, wanted, startedAt = 0L, reservation = 1L)
            if (out is AcquireOutcome.Acquired) model.opened(out.lease.leaf)
        }
        return Case("seed $seed", h, policy, model)
    }

    private fun nodes(rng: Random, depth: Int): List<Topology.Node> = List(1 + rng.nextInt(if (depth == 0) 4 else 3)) { i ->
        val name = "n$i"
        val weight = Weight.of(1L + rng.nextInt(4), 1L + rng.nextInt(3))
        val ceiling = ceilingOf(rng)
        when {
            depth < 3 && rng.nextInt(if (depth == 0) 2 else 3) == 0 -> Topology.Group(name, weight, nodes(rng, depth + 1), maxConcurrent = ceiling)
            depth < 3 && rng.nextInt(6) == 0 ->
                Topology.Group(name, weight, emptyList(), template = Topology.ChildTemplate(Weight.of(1L + rng.nextInt(3)), ceilingOf(rng)), maxConcurrent = ceiling)
            else -> Topology.Lane(name, weight, ceiling)
        }
    }

    private fun ceilingOf(rng: Random): Long? = when (rng.nextInt(8)) {
        0 -> 0L
        1, 2 -> 1L
        else -> null
    }

    /**
     * The tree a case was built from, as the test chose it: each node's weight and ceiling, and the
     * leases the test saw open beneath it. [shares] splits the weights from the root down over the
     * children that compete, by plain [Rational] arithmetic, with no ledger in sight.
     */
    private class Model(val root: Node, private val parentOf: Map<GroupId, Node>) {

        class Node(val id: GroupId, val weight: Rational, val ceiling: Long?, val lane: Boolean, val children: List<Node>)

        private val open = HashMap<GroupId, Int>()

        private val all: List<Node> = buildList {
            fun walk(n: Node) {
                add(n)
                n.children.forEach(::walk)
            }
            root.children.forEach(::walk)
        }

        /** Every node but the root, in the tree's own order, then the root. */
        val ids: List<GroupId> = all.map { it.id } + root.id
        val lanes: List<Node> = all.filter { it.lane }
        val groups: List<Node> = all.filter { !it.lane }

        /** Everything with no children: the lanes, and a template group nothing is attached to. */
        val leaves: List<Node> = all.filter { it.children.isEmpty() }

        /** [lane] and the groups above it, nearest first, the root left out. */
        private fun pathOf(lane: Node): List<Node> = generateSequence(lane) { parentOf[it.id] }.takeWhile { it !== root }.toList()

        fun opened(leaf: GroupId) {
            for (n in pathOf(all.single { it.id == leaf })) open[n.id] = (open[n.id] ?: 0) + 1
        }

        private fun atCeiling(n: Node): Boolean = n.ceiling != null && (open[n.id] ?: 0) >= n.ceiling

        fun competes(n: Node, waiting: Set<GroupId>): Boolean =
            if (n.lane) n.id in waiting && pathOf(n).none(::atCeiling) else n.children.any { competes(it, waiting) }

        /** Whether [lane] is held off by an open lease at a ceiling above zero. */
        fun cappedByALease(lane: Node): Boolean = pathOf(lane).any { atCeiling(it) && it.ceiling != 0L }

        fun shares(waiting: Set<GroupId>): Map<GroupId, Rational> {
            val out = HashMap<GroupId, Rational>()
            fun split(n: Node, share: Rational) {
                out[n.id] = share
                val competing = n.children.filter { competes(it, waiting) }
                val total = competing.fold(Rational.ZERO) { sum, child -> sum + child.weight }
                for (child in n.children) split(child, if (child in competing) share * child.weight / total else Rational.ZERO)
            }
            split(root, if (competes(root, waiting)) Rational.ONE else Rational.ZERO)
            return out
        }

        companion object {
            /** The model of [topology], with [attached] naming the children attached under each template group's path. */
            fun of(topology: Topology, attached: Map<List<String>, List<String>>): Model {
                val parentOf = HashMap<GroupId, Node>()
                fun build(path: List<String>, node: Topology.Node): Node {
                    val id = topology.groupId(*path.toTypedArray())
                    val weight = Rational.of(node.weight.numerator, node.weight.denominator)
                    val built = when (node) {
                        is Topology.Lane -> Node(id, weight, node.maxConcurrent, lane = true, children = emptyList())
                        is Topology.Group -> {
                            val declared = node.children.map { build(path + it.name, it) }
                            val template = node.template
                            val extra = if (template == null) emptyList() else attached[path].orEmpty().map { name ->
                                val w = Rational.of(template.weight.numerator, template.weight.denominator)
                                Node(topology.attachedChildId(path, name), w, template.maxConcurrent, lane = true, children = emptyList())
                            }
                            Node(id, weight, node.maxConcurrent, lane = false, children = declared + extra)
                        }
                    }
                    for (child in built.children) parentOf[child.id] = built
                    return built
                }
                val rootNode = Node(topology.groupId(), Rational.ONE, null, lane = false, children = topology.children.map { build(listOf(it.name), it) })
                for (child in rootNode.children) parentOf[child.id] = rootNode
                return Model(rootNode, parentOf)
            }
        }
    }

    private companion object {
        const val SEEDS = 80
        const val QUESTIONS = 4
        const val RUN = 1_200L
    }
}
