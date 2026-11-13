package us.tractat.kuilt.heddle

import kotlinx.serialization.json.Json
import us.tractat.kuilt.heddle.Topology.Group
import us.tractat.kuilt.heddle.Topology.Lane
import us.tractat.kuilt.heddle.Topology.Node
import us.tractat.kuilt.heddle.Topology.RuntimeChildren
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Task 4's diff properties run through the real interpreter, [LocalHeddle.apply], with leases open
 * across random leaves before each reload (#3176, plan Task L3 Step 2), and `explain` checked against
 * `acquire` across those reloads (Amendment 9).
 *
 * After every reload, with leases still open, [checkInvariants] holds: the ledger validates clean,
 * every open lease's captured path is its leaf's one live lineage (so no reload left a lease on a
 * retired edge, two live inbound edges, a divergent record or a cycle), conservation holds, no group's
 * holdings are negative, and every holding reads the same on a decoded copy of the ledger, whose
 * index is built from scratch rather than carried. Once every lease completes, applying the target
 * again converges on booting it from scratch.
 *
 * Trees are depth up to 3 with up to 3 children per group, names from a short list with escaped and
 * colliding ones, weights 1 to 4, half the groups marked for runtime children. Every draw is from a
 * `List` this file built. Each property counts the shapes it is meant to reach and asserts the
 * counts, so a generator that drifted to trivial histories reds rather than passing.
 */
class LocalHeddleApplyPropertyTest {

    private val seeds = 200
    private val names = listOf("a", "a1", "b/c", "é", "default")
    private val runtimeNames = listOf("x", "c-7f3a", "#3", "a")

    @Test
    fun applyingTheDiffThroughTheEngineMatchesBootingTheTarget() {
        val tally = Tally()
        var loops = 0
        for (seed in 0 until seeds) {
            val r = Random(seed)
            val from = randomTree(r)
            val run = Run(LocalHeddle.fromTopology(from, POLICY, epoch = 0L).also { it.reconcileCapacity(3L) }, from, tally)
            run.churn(r, tally)
            run.traffic(r, steps = 6)
            run.openLeases(r, 1 + r.nextInt(3))
            val to = mutate(from, r)
            run.reload(to, generation = r.nextLong(0, 4), tally, "seed $seed")
            run.settleAll(r)
            run.reload(to, generation = 0L, tally, "seed $seed again")
            run.checkConverged("seed $seed")
            loops++
        }
        println("APPLY-PROPERTY matches $tally")
        assertAll(
            { assertEquals(seeds, loops) },
            // Floors sit below the counts measured on every target (202, 43, 36, 16, 139, 26).
            { assertTrue(tally.moved >= 140, "leases moved by a reweight ${tally.moved}") },
            { assertTrue(tally.drained >= 30, "removals that drained ${tally.drained}") },
            { assertTrue(tally.runtimeMoved >= 20, "runtime-child leases moved ${tally.runtimeMoved}") },
            { assertTrue(tally.reconcilesThatMoved >= 10, "reconciles that moved spend ${tally.reconcilesThatMoved}") },
            { assertTrue(tally.replacements >= 100, "replacements the liveness oracle checked ${tally.replacements}") },
            { assertTrue(tally.undeclaredLeaf >= 15, "leaves explained NoLiveLineage only for being undeclared ${tally.undeclaredLeaf}") },
        )
    }

    @Test
    fun withTrafficTheEngineStillEndsClean() {
        val tally = Tally()
        var loops = 0
        var reloads = 0
        for (seed in 0 until seeds) {
            val r = Random(seed)
            var target = randomTree(r)
            val run = Run(LocalHeddle.fromTopology(target, POLICY, epoch = 0L).also { it.reconcileCapacity(1L + r.nextInt(4)) }, target, tally)
            repeat(4) { round ->
                run.churn(r, tally)
                run.traffic(r, steps = 4 + r.nextInt(8))
                run.openLeases(r, r.nextInt(4))
                target = mutate(target, r)
                run.reload(target, generation = r.nextLong(0, 3), tally, "seed $seed round $round")
                reloads++
                // Some leases finish between reloads, so a later reload finds a drain half done.
                run.settleSome(r)
            }
            run.settleAll(r)
            run.reload(target, generation = 0L, tally, "seed $seed last")
            run.checkConverged("seed $seed")
            loops++
        }
        println("APPLY-PROPERTY traffic $tally")
        assertAll(
            { assertEquals(seeds, loops) },
            { assertEquals(seeds * 4, reloads) },
            // Floors sit below the counts measured on every target (626, 215, 19, 30, 1, 92, 154, 527, 13010, 59).
            // A former lane's re-home is rare here; `rehomeKeepsAFormerLanesOpenReservationsEarmarked` pins it.
            { assertTrue(tally.moved >= 450, "leases moved by a reweight ${tally.moved}") },
            { assertTrue(tally.drained >= 150, "removals that drained ${tally.drained}") },
            { assertTrue(tally.removalWon >= 12, "reweights that waited on a removal below ${tally.removalWon}") },
            { assertTrue(tally.readdMidDrain >= 20, "children put back while still draining ${tally.readdMidDrain}") },
            { assertTrue(tally.formerLaneMoved >= 1, "leases at a former lane moved by a reweight ${tally.formerLaneMoved}") },
            { assertTrue(tally.runtimeMoved >= 60, "runtime-child leases moved ${tally.runtimeMoved}") },
            { assertTrue(tally.reconcilesThatMoved >= 100, "reconciles that moved spend ${tally.reconcilesThatMoved}") },
            { assertTrue(tally.replacements >= 400, "replacements the liveness oracle checked ${tally.replacements}") },
            { assertTrue(tally.undeclaredLeaf >= 40, "leaves explained NoLiveLineage only for being undeclared ${tally.undeclaredLeaf}") },
            { assertTrue(tally.explained >= 9_000, "explanations checked against an acquire ${tally.explained}") },
        )
    }

    // ── one engine's history ────────────────────────────────────────────────────────────────────

    /** An engine and what the test knows about it: its topology and the runtime children attached. */
    private inner class Run(val h: LocalHeddle, var topology: Topology, val tally: Tally) {
        val attached = ArrayList<Pair<List<String>, String>>()
        private var step = 0L

        /** Every group a lease may end at, by the test's own reading of the topology: lanes and attached runtime children. */
        fun declared(): List<GroupId> =
            lanePaths(topology.children, emptyList()).map { topology.groupId(*it.toTypedArray()) } +
                attached.map { (g, n) -> topology.runtimeChildId(g, n) }

        /** Every group of the tree, declared or not, and every runtime child attached: what `explain` is asked about. */
        fun everyGroup(): List<GroupId> =
            (nodePaths(topology.children, emptyList()).map { topology.groupId(*it.toTypedArray()) } + declared()).distinct().sorted()

        /** One to three attaches or detaches under the marked groups, through the engine. */
        fun churn(r: Random, tally: Tally) {
            val marked = groupPaths(topology.children, emptyList()).filter { markerAt(topology, it) != null }
            if (marked.isEmpty()) return
            repeat(1 + r.nextInt(3)) {
                if (attached.isNotEmpty() && r.nextInt(3) == 0) {
                    val (g, n) = attached.removeAt(r.nextInt(attached.size))
                    h.detachRuntimeChild(g, n)
                    // Half the time it comes straight back: with a lease open there, mid-drain.
                    if (r.nextBoolean()) attach(g to n, r)
                } else {
                    attach(marked[r.nextInt(marked.size)] to runtimeNames[r.nextInt(runtimeNames.size)], r)
                }
                checkInvariants("churn")
            }
        }

        private fun attach(child: Pair<List<String>, String>, r: Random) {
            val before = h.snapshot().ledger
            val result = h.attachRuntimeChild(child.first, child.second, generation = r.nextLong(0, 3))
            countReaddsMidDrain(before, result)
            if (child !in attached) attached += child
        }

        /** A retire that landed on an edge still carrying work: only a re-home of a child put back mid-drain does that. */
        private fun countReaddsMidDrain(before: EntitlementLedger, result: ApplyResult) {
            tally.readdMidDrain += result.applied.count { it is TopologyStep.Retire && (before.edge(it.edge)?.outstanding ?: 0L) > 0L && before.lifecycle(it.edge) == Lifecycle.CLOSING }
        }

        /** [steps] random acquires, explained first, and completes, cancels and extends. */
        fun traffic(r: Random, steps: Int) = repeat(steps) {
            val open = h.snapshot().leases
            when {
                open.isEmpty() || r.nextInt(3) == 0 -> explainThenAcquire(r)
                r.nextInt(4) == 0 -> h.extend(open[r.nextInt(open.size)].id, 1L + r.nextInt(3))
                r.nextInt(3) == 0 -> h.cancel(open[r.nextInt(open.size)].id)
                else -> h.complete(open[r.nextInt(open.size)].id, r.nextInt(6).toLong())
            }
            checkInvariants("traffic")
        }

        /** Open up to [n] leases at random declared leaves, one acquire each. */
        fun openLeases(r: Random, n: Int) = repeat(n) {
            val runtime = attached.map { (g, name) -> topology.runtimeChildId(g, name) }
            // Half the leases go to a runtime child when one is attached, so its re-homes are reached.
            val leaves = if (runtime.isNotEmpty() && r.nextBoolean()) runtime else declared()
            // A tree of marked groups with nothing attached yet declares no leaf at all.
            if (leaves.isEmpty()) return@repeat
            val leaf = leaves[r.nextInt(leaves.size)]
            h.acquire(setOf(leaf), setOf(leaf), startedAt = step++, reservation = 1L + r.nextInt(4))
        }

        fun settleSome(r: Random) {
            for (lease in h.snapshot().leases) if (r.nextBoolean()) check(h.complete(lease.id, r.nextInt(6).toLong()) is LeaseOutcome.Done)
            checkInvariants("settle")
        }

        fun settleAll(r: Random) {
            for (lease in h.snapshot().leases) {
                val out = h.complete(lease.id, r.nextInt(6).toLong())
                if (out !is LeaseOutcome.Done) fail("an open lease did not complete: $out")
            }
            checkInvariants("settled")
        }

        fun reload(to: Topology, generation: Long, tally: Tally, at: String) {
            val before = h.snapshot()
            val paths = before.leases.associate { it.id to h.capturedPath(it.id) }
            val formerLanes = before.leases.filter { !before.ledger.isLeaf(it.leaf) }.map { it.id }.toSet()
            val expectedToWait = replacementsThatMustWait(Topology.diff(to, before.ledger, generation), before)
            val result = h.apply(to, generation)
            topology = to
            attached.retainAll { (g, _) -> markerAt(to, g) != null }
            val after = h.snapshot()
            for (lease in after.leases) {
                if (h.capturedPath(lease.id) == paths[lease.id]) continue
                tally.moved++
                if (pathOf(lease.leaf)?.runtime == true) tally.runtimeMoved++
                if (lease.id in formerLanes) tally.formerLaneMoved++
            }
            if (after.leases.map { it.id } != before.leases.map { it.id }) fail("a reload opened or closed a lease at $at")
            tally.drained += result.refusals.count { it is StepRefusal.Draining && it.blockingLeases.isNotEmpty() }
            val activated = result.applied.filterIsInstance<TopologyStep.Activate>().map { it.child }.toSet()
            tally.removalWon += result.refusals.count { refusal ->
                refusal is StepRefusal.Draining && refusal.child !in activated && after.ledger.lifecycle(refusal.edge) == Lifecycle.ACTIVE
            }
            // A reconcile that moved spend onto the new edge: nothing else charges an edge during a reload.
            tally.reconcilesThatMoved += result.applied.count { step ->
                step is TopologyStep.Reconcile && (after.ledger.lineageOf(step.child)?.lastOrNull()?.let { after.ledger.edge(it)?.spent } ?: 0L) > 0L
            }
            // Liveness: a replacement waits exactly when a lease across its old edge cannot move.
            val waited = expectedToWait.second.filter { child -> result.refusals.any { it.child == child } }.toSet()
            if (waited != expectedToWait.first) fail("replacements that waited $waited, expected ${expectedToWait.first}, at $at: ${result.refusals}")
            tally.replacements += expectedToWait.second.size
            countReaddsMidDrain(before.ledger, result)
            if (result.refusals.any { it is StepRefusal.Refused }) fail("a reload refused outright at $at: ${result.refusals}")
            checkInvariants(at)
        }

        /**
         * The replacements [steps] holds (a child whose list retires an edge and activates another),
         * and those that must wait: an open lease crossing its old edge also crosses an edge the list
         * closes for a child that gets no new edge, or one already not ACTIVE. Read from the diff and
         * the snapshot alone, not from the engine.
         */
        private fun replacementsThatMustWait(steps: List<TopologyStep>, before: LocalHeddleState): Pair<Set<GroupId>, Set<GroupId>> {
            val activated = steps.filterIsInstance<TopologyStep.Activate>().map { it.child }.toSet()
            val retiredBy = steps.filterIsInstance<TopologyStep.Retire>().filter { it.child in activated }.groupBy({ it.child }, { it.edge })
            val replacedEdges = retiredBy.values.flatten().toSet()
            val closed = steps.filterIsInstance<TopologyStep.Close>().map { it.edge }.toSet()
            val paths = before.leases.map { checkNotNull(h.capturedPath(it.id)) }
            val mustWait = retiredBy.filter { (_, edges) ->
                paths.any { path ->
                    path.any { it in edges } && path.any { e -> e !in replacedEdges && (e in closed || before.ledger.lifecycle(e) != Lifecycle.ACTIVE) }
                }
            }.keys
            return mustWait to retiredBy.keys
        }

        fun explainThenAcquire(r: Random) {
            val groups = everyGroup()
            val waiting = groups.filter { r.nextInt(3) != 0 }.toSet()
            val eligible = waiting.filter { r.nextInt(3) != 0 }.toSet()
            val reservation = 1L + r.nextInt(4)
            val l = h.snapshot().ledger
            val declared = declared().toSet()
            val reasons = groups.associateWith { h.explain(it, waiting, eligible, reservation) }
            val out = h.acquire(waiting, eligible, startedAt = step++, reservation = reservation)
            val picked = (out as? AcquireOutcome.Acquired)?.lease?.leaf
            for (g in groups) {
                // The test's own oracle: declared, a leaf, and reached through ACTIVE edges only.
                val lineage = l.lineageOf(g)
                val pickable = g in declared && l.isLeaf(g) && lineage != null && lineage.isNotEmpty() &&
                    lineage.all { l.lifecycle(it) == Lifecycle.ACTIVE }
                val reason = reasons.getValue(g)
                val reachable = l.isLeaf(g) && lineage != null && lineage.isNotEmpty() && lineage.all { l.lifecycle(it) == Lifecycle.ACTIVE }
                if (g in eligible && reachable && g !in declared) tally.undeclaredLeaf++
                val where = "${g.value} told $reason, acquire gave $out"
                when {
                    g !in waiting -> if (reason != WaitExplanation.NotWaiting) fail(where)
                    g !in eligible -> if (reason != WaitExplanation.NotEligible) fail(where)
                    !pickable -> if (reason != WaitExplanation.NoLiveLineage(g)) fail(where)
                    reason == WaitExplanation.WouldBePickedNext -> if (picked != g) fail("told it goes next: $where")
                    reason is WaitExplanation.BehindAtLevel -> if (picked == g || picked == null) fail("told it is behind: $where")
                    reason != WaitExplanation.SupplyExhausted -> fail("unexpected: $where")
                }
                tally.explained++
            }
            if (picked != null && picked !in declared) fail("leased ${picked.value}, which the topology does not declare leasable")
        }

        /** The invariants after any operation, leases open or not. */
        fun checkInvariants(at: String) {
            val s = h.snapshot()
            val l = s.ledger
            val conflicts = l.validate()
            if (conflicts.isNotEmpty()) fail("conflicts at $at: $conflicts")
            for (lease in s.leases) {
                val path = h.capturedPath(lease.id)
                if (path != l.lineageOf(lease.leaf)) fail("lease ${lease.id} charges $path, not ${lease.leaf.value}'s lineage ${l.lineageOf(lease.leaf)} at $at")
                if (path.orEmpty().any { l.lifecycle(it) == Lifecycle.RETIRED }) fail("lease ${lease.id} crosses a retired edge at $at")
            }
            val groups = groupsOf(l)
            val decoded = Json.decodeFromString(EntitlementLedger.serializer(), Json.encodeToString(EntitlementLedger.serializer(), l))
            for (g in groups) {
                val held = l.holdings(g, LOCAL)
                if (held < 0L) fail("${g.value} holds $held at $at")
                if (held != decoded.holdings(g, LOCAL)) fail("${g.value} holds $held, but ${decoded.holdings(g, LOCAL)} on a decoded copy, at $at")
            }
            val root = topology.groupId()
            val held = groups.sumOf { l.holdings(it, LOCAL) }
            if (l.mintedTotal() != held + l.leafSpentTotal()) fail("minted ${l.mintedTotal()} != Σ holdings $held + spent ${l.leafSpentTotal()} at $at")
            val belowRoot = groups.filter { it != root }.sumOf { l.holdings(it, LOCAL) }
            if (belowRoot != s.leases.sumOf { it.reserved }) fail("holdings below the root $belowRoot != open reservations at $at")
        }

        fun checkConverged(at: String) {
            val l = h.snapshot().ledger
            val runtime = attached.map { (g, n) ->
                ActiveTriple(topology.groupId(*g.toTypedArray()), topology.runtimeChildId(g, n), checkNotNull(markerAt(topology, g)).weight)
            }
            val expected = (bootFrom(topology).activeTriples() + runtime).sortedWith(compareBy<ActiveTriple> { it.child }.thenBy { it.parent })
            if (l.activeTriples() != expected) fail("active ${l.activeTriples()} != $expected at $at")
            val closing = l.allEdges().sorted().filter { l.lifecycle(it) == Lifecycle.CLOSING }
            if (closing.isNotEmpty()) fail("left closing $closing at $at")
            if (Topology.diff(topology, l, generation = 0L).isNotEmpty()) fail("a diff is still owed at $at")
        }
    }

    private class Tally {
        var moved = 0
        var runtimeMoved = 0
        var formerLaneMoved = 0
        var drained = 0
        var removalWon = 0
        var readdMidDrain = 0
        var reconcilesThatMoved = 0
        var replacements = 0
        var explained = 0
        var undeclaredLeaf = 0

        override fun toString(): String =
            "moved=$moved runtimeMoved=$runtimeMoved formerLaneMoved=$formerLaneMoved drained=$drained removalWon=$removalWon " +
                "readdMidDrain=$readdMidDrain reconcilesThatMoved=$reconcilesThatMoved replacements=$replacements " +
                "explained=$explained undeclaredLeaf=$undeclaredLeaf"
    }

    // ── generators ──────────────────────────────────────────────────────────────────────────────

    private fun randomTree(r: Random): Topology = Topology("root", randomChildren(r, depth = 1))

    private fun randomChildren(r: Random, depth: Int): List<Node> = names.shuffled(r).take(1 + r.nextInt(3)).map { randomNode(r, it, depth) }

    private fun randomNode(r: Random, name: String, depth: Int): Node {
        val weight = Weight.of(1L + r.nextInt(4))
        return if (depth < 3 && r.nextInt(3) == 0) {
            // A quarter of the groups are marked and declare nothing: a leaf the ledger cannot tell
            // from a lane until a runtime child attaches, which is what the leasable predicate decides.
            if (r.nextInt(4) == 0) return Group(name, weight, emptyList(), runtimeChildren = RuntimeChildren(Weight.of(1L + r.nextInt(3))))
            Group(name, weight, randomChildren(r, depth + 1), runtimeChildren = if (r.nextBoolean()) RuntimeChildren(Weight.of(1L + r.nextInt(3))) else null)
        } else {
            Lane(name, weight)
        }
    }

    /** One to three edits, reweights weighted up since they are what moves leases. */
    private fun mutate(t: Topology, r: Random): Topology {
        var current = t
        repeat(1 + r.nextInt(3)) { current = edit(current, r) }
        return current
    }

    private fun edit(t: Topology, r: Random): Topology {
        val paths = nodePaths(t.children, emptyList())
        val path = paths[r.nextInt(paths.size)]
        val node = nodeAt(t.children, path)
        return when (r.nextInt(8)) {
            0, 1, 2 -> t.update(path) { it.withWeight(Weight.of(it.weight.numerator % 4 + 1)) }
            // A marked group may lose its last declared child; any other group keeps one.
            3 -> if (siblingsOf(t, path).size > 1 || (path.size > 1 && markerAt(t, path.dropLast(1)) != null)) t.update(path) { null } else t
            4 -> {
                val parent = if (node is Group) path else path.dropLast(1)
                val taken = (if (parent.isEmpty()) t.children else (nodeAt(t.children, parent) as Group).children).map { it.name }
                val free = names.filter { it !in taken }
                if (free.isEmpty()) {
                    t
                } else {
                    val added = randomNode(r, free[r.nextInt(free.size)], parent.size + 1)
                    if (parent.isEmpty()) Topology(t.root, t.children + added) else t.update(parent) { (it as Group).copy(children = it.children + added) }
                }
            }
            5 -> if (node is Lane && path.size < 3) t.update(path) { Group(it.name, it.weight, listOf(Lane(names[r.nextInt(names.size)]))) } else t
            6 -> if (node is Group) t.update(path) { Lane(it.name, it.weight) } else t
            else -> if (node is Group) {
                // Unmarking a group that declares nothing would leave it empty, so it stays marked.
                t.update(path) { (it as Group).copy(runtimeChildren = if (it.runtimeChildren == null || it.children.isEmpty()) RuntimeChildren(Weight.ONE) else null) }
            } else {
                t
            }
        }
    }

    private fun Node.withWeight(weight: Weight): Node = when (this) {
        is Group -> copy(weight = weight)
        is Lane -> copy(weight = weight)
    }

    private fun nodePaths(nodes: List<Node>, prefix: List<String>): List<List<String>> =
        nodes.flatMap { node -> listOf(prefix + node.name) + if (node is Group) nodePaths(node.children, prefix + node.name) else emptyList() }

    private fun lanePaths(nodes: List<Node>, prefix: List<String>): List<List<String>> =
        nodes.flatMap { node -> if (node is Group) lanePaths(node.children, prefix + node.name) else listOf(prefix + node.name) }

    private fun groupPaths(nodes: List<Node>, prefix: List<String>): List<List<String>> =
        nodes.filterIsInstance<Group>().flatMap { g -> listOf(prefix + g.name) + groupPaths(g.children, prefix + g.name) }

    private fun nodeAt(nodes: List<Node>, path: List<String>): Node {
        val node = nodes.single { it.name == path.first() }
        return if (path.size == 1) node else nodeAt((node as Group).children, path.drop(1))
    }

    private fun siblingsOf(t: Topology, path: List<String>): List<Node> =
        if (path.size == 1) t.children else (nodeAt(t.children, path.dropLast(1)) as Group).children

    private fun markerAt(t: Topology, path: List<String>): RuntimeChildren? {
        var nodes = t.children
        var node: Node? = null
        for (name in path) {
            node = nodes.firstOrNull { it.name == name } ?: return null
            nodes = (node as? Group)?.children.orEmpty()
        }
        return (node as? Group)?.runtimeChildren
    }

    private fun Topology.update(path: List<String>, f: (Node) -> Node?): Topology = Topology(root, children.update(path, f))

    private fun List<Node>.update(path: List<String>, f: (Node) -> Node?): List<Node> = mapNotNull { node ->
        when {
            node.name != path.first() -> node
            path.size == 1 -> f(node)
            else -> (node as Group).copy(children = node.children.update(path.drop(1), f))
        }
    }

    private companion object {
        val POLICY = LocalPolicy(defaultReservation = 2L)
    }
}
