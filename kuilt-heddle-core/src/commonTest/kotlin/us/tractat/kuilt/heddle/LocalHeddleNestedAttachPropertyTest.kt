package us.tractat.kuilt.heddle

import us.tractat.kuilt.heddle.Topology.ChildTemplate
import us.tractat.kuilt.heddle.Topology.Group
import us.tractat.kuilt.heddle.Topology.Lane
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Seeded runs of nested [LocalHeddle.attach] and [LocalHeddle.detach] (#3314) against a model
 * this file keeps by hand: a set of attached paths, where attaching a path adds it and every
 * ancestor, and detaching one removes it and everything below it.
 *
 * The tree is `root → {ci {manual, template(2 → 3/2 → 1)}, other: 3}`: three attached levels
 * under `ci`, repository → author → branch, with only a branch leasable. After every call:
 *
 * - every path in the model has a live lineage, and every other path of the universe has none;
 * - a reload of the same topology changes nothing;
 * - every leaf's share ([LocalHeddle.entitlement]) equals the share of the same leaf in an
 *   **oracle**: the same tree built from **declared** groups, booted fresh, which never touches
 *   the attach path, the `!` grammar or a template. A repository or author with nothing below it
 *   becomes a group holding one idle lane, so it competes for nothing, as an attached group
 *   with no children does;
 * - a repository or an author is owed nothing, even waiting: it is a group, never leasable.
 *
 * No leases are held, so no detach waits on a drain; the drain is
 * [LocalHeddleNestedAttachTest.detachingARepositoryDrainsItsSubtreeAndBringsNoAuthorBack]'s.
 *
 * Every draw is from a `List` this file built in a fixed order, so a seed walks the same trajectory
 * on every target. The cases the laws are meant to exercise are counted and floored.
 */
class LocalHeddleNestedAttachPropertyTest {

    private val seeds = 60
    private val calls = 25
    private val policy = LocalPolicy(defaultReservation = 1L)
    private val pool = listOf("a", "b", "c")
    private val levelWeights = listOf(Weight.of(2), Weight.of(3, 2), Weight.ONE)

    private val template = ChildTemplate(levelWeights[0], template = ChildTemplate(levelWeights[1], template = ChildTemplate(levelWeights[2])))
    private val t = Topology("root", listOf(Group("ci", Weight.ONE, listOf(Lane("manual")), template = template), Lane("other", Weight.of(3))))

    /** Every attached path of up to three names over [pool], shortest first. */
    private val universe: List<List<String>> = buildList {
        var level = listOf(emptyList<String>())
        repeat(3) {
            level = level.flatMap { prefix -> pool.map { prefix + it } }
            addAll(level)
        }
    }

    private fun pathOf(names: List<String>): GroupPath =
        names.drop(1).fold(GroupPath.Attached(listOf("ci"), names.first()) as GroupPath) { parent, name -> GroupPath.Nested(parent, name) }

    private fun idOf(names: List<String>): GroupId = checkNotNull(t.findGroupId(pathOf(names))) { "the topology reaches $names" }

    @Test
    fun nestedAttachAndDetachKeepTheModelAndItsShares() {
        val failures = ArrayList<String>()
        val tally = Tally()
        var loops = 0
        for (seed in 0 until seeds) {
            val r = Random(seed)
            val h = LocalHeddle.bootWhole(t, policy, epoch = 0L).also { it.reconcileCapacity(4L) }
            val model = LinkedHashSet<List<String>>()
            for (call in 0 until calls) {
                val names = universe[r.nextInt(universe.size)]
                val result = if (r.nextInt(5) < 3) {
                    model += names.indices.map { names.subList(0, it + 1) }
                    tally.attaches++
                    if (names.size == 3) tally.deepAttaches++
                    h.attach(pathOf(names), generation = call.toLong())
                } else {
                    val below = model.filter { it.size > names.size && it.subList(0, names.size) == names }
                    if (names in model && below.isNotEmpty()) tally.cascades++
                    model.removeAll { it.size >= names.size && it.subList(0, names.size) == names }
                    tally.detaches++
                    h.detach(pathOf(names))
                }
                val at = "seed $seed call $call (${if (names in model) "attach" else "detach"} $names)"
                if (result.refusals.isNotEmpty()) failures += "$at: refused ${result.refusals}"
                checkModel(h, model, at, failures)
                if (r.nextInt(4) == 0) {
                    val reload = h.apply(t, generation = call.toLong())
                    if (reload.applied.isNotEmpty() || reload.refusals.isNotEmpty()) failures += "$at: a reload moved ${reload.applied} ${reload.refusals}"
                    tally.reloads++
                }
                tally.compared += checkShares(h, model, r, at, failures)
                if (model.any { it.size == 3 }) tally.withBranches++
            }
            loops++
        }
        assertAll(
            { assertEquals(seeds, loops) },
            { assertEquals(emptyList(), failures.take(20), "${failures.size} failures") },
            // Floors set below the counts measured on JVM, macosArm64 and wasmJs, identical on all three
            // (646, 78, 1422, 4271, 399, 583), so a drifting generator reds.
            { assertTrue(tally.deepAttaches >= 550, "attaches three levels down ${tally.deepAttaches}") },
            { assertTrue(tally.cascades >= 60, "detaches that took a subtree with them ${tally.cascades}") },
            { assertTrue(tally.withBranches >= 1_250, "calls ending with a branch attached ${tally.withBranches}") },
            { assertTrue(tally.compared >= 3_800, "shares compared ${tally.compared}") },
            { assertTrue(tally.reloads >= 350, "reloads ${tally.reloads}") },
            { assertTrue(tally.detaches >= 500, "detaches ${tally.detaches}") },
        )
    }

    /**
     * The same calls with leases held across them, so a detach waits on the work beneath it and an
     * attach can land on a repository or author whose detach is still draining, or has drained but
     * not yet retired. After every call:
     *
     * - [EntitlementLedger.validate] reports nothing;
     * - no edge outlives the edge above it: every live edge into an attached child has a live edge
     *   into its parent numbered at or below it, the edge it was attached under or one older. A
     *   parent's edge made after the child's is a re-attach, and this tree has no reweight, the one
     *   change that keeps old children beneath a new edge, so such a child was closed by the
     *   detach that ended its parent's old edge, and must retire before or with it;
     *
     * and once every lease completes, a detach of each path the model no longer holds and an attach
     * of each it does land with nothing refused, after which [checkModel] and the shares hold as in
     * [nestedAttachAndDetachKeepTheModelAndItsShares].
     */
    @Test
    fun nestedAttachAndDetachUnderOpenLeasesSettleToTheModel() {
        val failures = ArrayList<String>()
        val tally = Tally()
        // Two names a level, so a lease, a detach and an attach meet on the same groups often.
        val near = universe.filter { path -> "c" !in path }
        val leaves = near.filter { it.size == 3 }
        for (seed in 0 until seeds) {
            val r = Random(seed)
            val h = LocalHeddle.bootWhole(t, policy, epoch = 0L).also { it.reconcileCapacity(64L) }
            val model = LinkedHashSet<List<String>>()
            val open = ArrayList<Lease>()
            for (call in 0 until calls * 2) {
                val names = near[r.nextInt(near.size)]
                val at = "seed $seed call $call"
                when (r.nextInt(10)) {
                    in 0..3 -> {
                        val before = h.snapshot().ledger
                        fun closingOnly(depth: Int) = before.inboundEdges(idOf(names.subList(0, depth + 1))).let { edges ->
                            edges.any { before.lifecycle(it) == Lifecycle.CLOSING } && edges.none { before.lifecycle(it) == Lifecycle.ACTIVE }
                        }
                        val draining = names.indices.any { depth -> before.inboundEdges(idOf(names.subList(0, depth + 1))).any { before.lifecycle(it) == Lifecycle.CLOSING } }
                        // Two groups of the chain to attach again, both detached: the shape whose walks overlap.
                        if (names.size == 3 && closingOnly(0) && closingOnly(1)) tally.deepReattaches++
                        model += names.indices.map { names.subList(0, it + 1) }
                        val result = h.attach(pathOf(names), generation = 1_000L + call)
                        if (draining) tally.reattachesOverClosing++
                        if (draining && result.refusals.isEmpty()) tally.reattachesLanded++
                        checkNoLedgerRefusal(result, "$at (attach $names)", failures)
                    }
                    in 4..5 -> {
                        model.removeAll { it.size >= names.size && it.subList(0, names.size) == names }
                        val result = h.detach(pathOf(names))
                        if (result.refusals.any { it is StepRefusal.Draining }) tally.drainingDetaches++
                        checkNoLedgerRefusal(result, "$at (detach $names)", failures)
                    }
                    in 6..8 -> {
                        val leaf = idOf(leaves[r.nextInt(leaves.size)])
                        val out = h.acquire(setOf(leaf), setOf(leaf), startedAt = call.toLong())
                        if (out is AcquireOutcome.Acquired) open += out.lease.also { tally.leases++ }
                    }
                    else -> if (open.isNotEmpty()) {
                        val lease = open.removeAt(r.nextInt(open.size))
                        if (h.complete(lease.id, actualCost = 1L, waiting = emptySet()) !is LeaseOutcome.Done) failures += "$at: completing ${lease.id} failed"
                    }
                }
                checkNothingOutlivesItsParent(h.snapshot().ledger, "$at ($names)", failures)
            }
            for (lease in open) if (h.complete(lease.id, actualCost = 1L, waiting = emptySet()) !is LeaseOutcome.Done) failures += "seed $seed: completing ${lease.id} failed"
            val settle = universe.reversed().filter { it !in model }.map { h.detach(pathOf(it)) } +
                model.sortedBy { it.size }.map { h.attach(pathOf(it), generation = 10_000L) }
            for (result in settle) if (result.refusals.isNotEmpty()) failures += "seed $seed settle: refused ${result.refusals}"
            checkNothingOutlivesItsParent(h.snapshot().ledger, "seed $seed settled", failures)
            checkModel(h, model, "seed $seed settled", failures)
            tally.compared += checkShares(h, model, r, "seed $seed settled", failures)
        }
        assertAll(
            { assertEquals(emptyList(), failures.take(20), "${failures.size} failures") },
            // Floors below the counts measured on JVM, macosArm64 and wasmJs, identical on all three
            // (273, 89, 79, 30, 120), so a generator that stopped reaching a drain reds.
            { assertTrue(tally.leases >= 200, "leases ${tally.leases}") },
            { assertTrue(tally.drainingDetaches >= 60, "detaches that waited on a lease ${tally.drainingDetaches}") },
            { assertTrue(tally.reattachesOverClosing >= 55, "attaches over a closing edge ${tally.reattachesOverClosing}") },
            { assertTrue(tally.reattachesLanded >= 20, "of which landed ${tally.reattachesLanded}") },
            { assertTrue(tally.deepReattaches >= 999_999, "attaches over two detached groups of a chain ${tally.deepReattaches}") },
            { assertTrue(tally.compared >= 80, "shares compared ${tally.compared}") },
        )
    }

    /**
     * A step the ledger itself refused. An honest plan never contains one: a step that cannot land
     * yet is refused as [StepRefusal.Draining], or for a reason the engine names, never by the ledger
     * turning down the step it was handed.
     */
    private fun checkNoLedgerRefusal(result: ApplyResult, at: String, failures: MutableList<String>) {
        val refused = result.refusals.filterIsInstance<StepRefusal.Refused>().filter { it.reason == "the ledger refused it" }
        if (refused.isNotEmpty()) failures += "$at: the ledger refused ${refused.map { it.step }}"
    }

    /** See [nestedAttachAndDetachUnderOpenLeasesSettleToTheModel]: validate, and no live edge beneath a retired one. */
    private fun checkNothingOutlivesItsParent(ledger: EntitlementLedger, at: String, failures: MutableList<String>) {
        val conflicts = ledger.validate()
        if (conflicts.isNotEmpty()) failures += "$at: validate $conflicts"
        for (names in universe.filter { it.size >= 2 }) {
            val child = idOf(names)
            val parent = idOf(names.dropLast(1))
            val above = ledger.liveInboundEdges(parent).mapNotNull { generationOf(it) }
            val outlived = ledger.liveInboundEdges(child).filter { edge -> above.none { it <= checkNotNull(generationOf(edge)) } }
            if (outlived.isNotEmpty()) failures += "$at: ${outlived.map { it.value }} live with no live edge above it as old: ${parent.value} has $above"
        }
    }

    /** Every path of the universe is live exactly when the model holds it, and reads back to itself. */
    private fun checkModel(h: LocalHeddle, model: Set<List<String>>, at: String, failures: MutableList<String>) {
        val ledger = h.snapshot().ledger
        for (names in universe) {
            val id = idOf(names)
            // An empty lineage is a group with no edge left at all: the engine forgot the retired ones.
            val live = ledger.lineageOf(id)?.isNotEmpty() == true
            if (live != (names in model)) failures += "$at: ${id.value} live=$live, the model says ${names in model}"
            if (t.findPath(id) != pathOf(names)) failures += "$at: findPath(${id.value}) = ${t.findPath(id)}"
        }
    }

    /**
     * Compares the engine's share of each waiting leaf with the oracle's, and checks every waiting
     * repository and author is owed nothing. Returns how many non-zero shares it compared.
     */
    private fun checkShares(h: LocalHeddle, model: Set<List<String>>, r: Random, at: String, failures: MutableList<String>): Int {
        // Sorted, so the subset drawn is the same on every target.
        val held = model.sortedWith(compareBy<List<String>>({ it.size }, { it.joinToString("/") }))
        val waitingBranches = held.filter { it.size == 3 && r.nextInt(3) != 0 }
        val waitingGroups = held.filter { it.size < 3 && r.nextBoolean() }
        val declared = listOf(listOf("ci", "manual"), listOf("other")).filter { r.nextBoolean() }
        val waiting = (waitingBranches + waitingGroups).map { idOf(it) }.toSet() + declared.map { t.groupId(*it.toTypedArray()) }

        val oracle = oracleOf(held)
        val oracleWaiting = waitingBranches.map { oracle.groupId("ci", *it.toTypedArray()) }.toSet() + declared.map { oracle.groupId(*it.toTypedArray()) }
        val reference = LocalHeddle.bootWhole(oracle, policy, epoch = 0L)

        var compared = 0
        for (names in waitingBranches) {
            val mine = h.entitlement(idOf(names), waiting, totalSlots = 12L)
            val theirs = reference.entitlement(oracle.groupId("ci", *names.toTypedArray()), oracleWaiting, totalSlots = 12L)
            if (mine != theirs) failures += "$at: $names is owed $mine, the declared tree owes it $theirs"
            if (mine != Rational.ZERO) compared++
        }
        for (names in declared) {
            val mine = h.entitlement(t.groupId(*names.toTypedArray()), waiting, totalSlots = 12L)
            val theirs = reference.entitlement(oracle.groupId(*names.toTypedArray()), oracleWaiting, totalSlots = 12L)
            if (mine != theirs) failures += "$at: declared $names is owed $mine, the declared tree owes it $theirs"
        }
        for (names in waitingGroups) {
            val owed = h.entitlement(idOf(names), waiting, totalSlots = 12L)
            if (owed != Rational.ZERO) failures += "$at: the attached group $names is owed $owed; it is never leased"
        }
        return compared
    }

    /** The model's tree built from declared groups alone: the same shape and weights, no template anywhere. */
    private fun oracleOf(held: List<List<String>>): Topology {
        fun level(prefix: List<String>): List<Topology.Node> {
            val depth = prefix.size
            return held.filter { it.size == depth + 1 && it.subList(0, depth) == prefix }.map { names ->
                if (depth + 1 == 3) {
                    Lane(names.last(), levelWeights[depth])
                } else {
                    Group(names.last(), levelWeights[depth], level(names).ifEmpty { listOf(Lane(IDLE)) })
                }
            }
        }
        return Topology("root", listOf(Group("ci", Weight.ONE, listOf(Lane("manual")) + level(emptyList())), Lane("other", Weight.of(3))))
    }

    private class Tally {
        var attaches = 0
        var deepAttaches = 0
        var detaches = 0
        var cascades = 0
        var reloads = 0
        var compared = 0
        var withBranches = 0
        var leases = 0
        var drainingDetaches = 0
        var reattachesOverClosing = 0
        var reattachesLanded = 0
        var deepReattaches = 0
    }

    private companion object {
        /** Never in the pool, so it never collides with an attached name. */
        const val IDLE = "idle"
    }
}
