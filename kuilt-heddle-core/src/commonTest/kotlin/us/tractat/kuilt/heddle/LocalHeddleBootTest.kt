package us.tractat.kuilt.heddle

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [LocalHeddle.fromTopology] over a well-formed tree whose weights the budget refuses (#3461).
 *
 * The budget is a property of a sibling set, and `apply` walks a set's activations by child id,
 * so which sibling tips the set over is the one whose id sorts last. A reload that refuses that
 * one leaves the previous tree running, which is what a reload should do. A boot has no previous
 * tree, so it leaves out the set's parent and everything beneath it, the same whichever names the
 * children carry and whatever order they are written in, and reports every group it left out.
 */
class LocalHeddleBootTest {

    private val policy = LocalPolicy(defaultReservation = 1L)

    /** A weight that fits the budget alone and does not fit beside [third]. */
    private val heavy = Weight.of(185_000_000_000_000_000L)

    /** Fits alone; beside [heavy] it triples the heavy lane's grid step past its window. */
    private val third = Weight.of(1, 3)

    /** Too fine for one full lease, beside any siblings or none. */
    private val fine = Weight.of(1, 30_720)

    /** `p → {q → {[first], [second]}, b}`, with `q`'s two lanes written in the order given. */
    private fun underQ(first: Pair<String, Weight>, second: Pair<String, Weight>): Topology = draft("p") {
        group("q") {
            lane(first.first, first.second)
            lane(second.first, second.second)
        }
        lane("b")
    }

    private fun LocalHeddle.Boot.held(topology: Topology): Set<GroupId> {
        val ledger = heddle.snapshot().ledger
        return topology.groupIds().filterTo(HashSet()) { ledger.inboundEdges(it).isNotEmpty() }
    }

    private fun LocalHeddle.Boot.leasable(topology: Topology): Set<GroupId> =
        topology.groupIds().filterTo(HashSet()) { heddle.admit(it, waiting = setOf(it), startedAt = 0L) is AcquireOutcome.Acquired }

    private fun Topology.groupIds(): List<GroupId> {
        val out = ArrayList<GroupId>()
        fun walk(prefix: List<String>, nodes: List<Topology.Node>) {
            for (node in nodes) {
                val path = prefix + node.name
                out += groupId(*path.toTypedArray())
                if (node is Topology.Group) walk(path, node.children)
            }
        }
        walk(emptyList(), children)
        return out
    }

    /**
     * `q`'s lanes are `heavy` and `1/3` under every pairing of names and written order. `apply`
     * would refuse whichever lane is named `z`, so a reload's answer turns on the names; the boot
     * leaves out `q`, `q/a` and `q/z` in all four, reports those three, and runs `b`.
     */
    @Test
    fun aSiblingSetThatDoesNotFitLeavesItsParentOutWhateverTheNamesAndOrder() {
        val trees = listOf(
            underQ("z" to heavy, "a" to third),
            underQ("a" to third, "z" to heavy),
            underQ("a" to heavy, "z" to third),
            underQ("z" to third, "a" to heavy),
        )
        val boots = trees.map { it to LocalHeddle.fromTopology(it, policy, epoch = 0L) }
        val q = trees.first().groupId("q")
        val left = setOf(q, trees.first().groupId("q", "a"), trees.first().groupId("q", "z"))
        val b = trees.first().groupId("b")
        assertAll(
            { assertTrue(weightsFitTheBudget(listOf(heavy)) && weightsFitTheBudget(listOf(third)), "the rig: each fits alone") },
            { assertTrue(!weightsFitTheBudget(listOf(heavy, third)), "the rig: the pair does not") },
            *boots.map { (tree, boot) ->
                {
                    assertAll(
                        { assertEquals(left, boot.result.refusals.map { it.child }.toSet(), "$tree: q and both its lanes") },
                        { assertEquals(3, boot.result.refusals.size, "$tree: one refusal per group left out") },
                        { boot.result.refusals.forEach { assertIs<StepRefusal.Refused>(it, "$tree: refused, not draining") } },
                        { assertEquals(setOf(b), boot.held(tree), "$tree: only b has an edge") },
                        { assertEquals(setOf(b), boot.leasable(tree), "$tree: only b is leased") },
                    )
                }
            }.toTypedArray(),
            {
                val reasons = boots.map { (_, boot) -> boot.result.refusals.map { (it as StepRefusal.Refused).reason }.toSet() }
                assertEquals(1, reasons.toSet().size, "the same answer whatever the names: $reasons")
            },
        )
    }

    /**
     * A lane too fine for any sibling set, at the root, in both written orders: the root's set does
     * not fit, so nothing is booted and every group is reported. The engine still comes back, and
     * an [LocalHeddle.apply] of a tree that fits starts it.
     */
    @Test
    fun aRootSetThatDoesNotFitBootsNothingInEitherOrder() {
        val trees = listOf(
            draft("p") { lane("a"); lane("fine", fine) },
            draft("p") { lane("fine", fine); lane("a") },
        )
        val coarse = draft("p") { lane("a") }
        assertAll(
            { assertTrue(!weightsFitTheBudget(listOf(fine)), "the rig: fine fails alone") },
            *trees.map { tree ->
                {
                    val boot = LocalHeddle.fromTopology(tree, policy, epoch = 0L)
                    val all = tree.groupIds().toSet()
                    assertAll(
                        { assertEquals(all, boot.result.refusals.map { it.child }.toSet(), "$tree: every group") },
                        { assertEquals(emptyList(), boot.result.applied, "$tree: no step landed") },
                        { assertEquals(emptySet(), boot.held(tree), "$tree: no edge") },
                        { assertEquals(emptyList(), boot.heddle.apply(coarse, generation = 1L).refusals, "$tree: a tree that fits applies") },
                        { assertEquals(setOf(tree.groupId("a")), boot.leasable(coarse), "$tree: and runs") },
                    )
                }
            }.toTypedArray(),
        )
    }

    /**
     * A refused group's descendants go with it. `apply` on a running engine refuses a group too
     * fine for its siblings and still activates the lanes beneath it; a boot leaves the unit out
     * whole: `q`, `q/g` and `q/g/x`, with `q/c` beside `g`, and `b` runs.
     */
    @Test
    fun aGroupLeftOutAtBootTakesEveryGroupBeneathIt() {
        val tree = draft("p") {
            group("q") {
                group("g", fine) { lane("x") }
                lane("c")
            }
            lane("b")
        }
        val boot = LocalHeddle.fromTopology(tree, policy, epoch = 0L)
        val left = setOf(tree.groupId("q"), tree.groupId("q", "g"), tree.groupId("q", "g", "x"), tree.groupId("q", "c"))
        assertAll(
            { assertEquals(left, boot.result.refusals.map { it.child }.toSet()) },
            { assertEquals(setOf(tree.groupId("b")), boot.held(tree)) },
            { assertEquals(setOf(tree.groupId("b")), boot.leasable(tree)) },
            { assertEquals(emptyList(), boot.heddle.snapshot().ledger.validate()) },
        )
    }

    /** A tree that fits boots whole: nothing refused, every step of its diff applied. */
    @Test
    fun aTreeThatFitsBootsWhole() {
        val tree = underQ("a" to Weight.ONE, "z" to third)
        val boot = LocalHeddle.fromTopology(tree, policy, epoch = 0L)
        assertAll(
            { assertEquals(emptyList(), boot.result.refusals) },
            { assertEquals(Topology.diff(tree, EntitlementLedger.ZERO, 0L), boot.result.applied) },
            { assertEquals(tree.groupIds().toSet(), boot.held(tree)) },
        )
    }

    /**
     * A reload is not a boot: adding `z = 1/3` beside a running `a = heavy` refuses only `z`, and
     * `q` and `a` keep running, as a refused change should leave the previous tree.
     */
    @Test
    fun aReloadLeavesTheRunningSiblingsRunning() {
        val before = draft("p") { group("q") { lane("a", heavy) }; lane("b") }
        val after = underQ("a" to heavy, "z" to third)
        val engine = LocalHeddle.fromTopology(before, policy, epoch = 0L)
        val result = engine.heddle.apply(after, generation = 1L)
        assertAll(
            { assertEquals(emptyList(), engine.result.refusals, "the rig: the coarse tree booted whole") },
            { assertEquals(listOf(after.groupId("q", "z")), result.refusals.map { it.child }) },
            { assertEquals(setOf(after.groupId("q", "a"), after.groupId("b")), engine.leasable(after)) },
        )
    }
}
