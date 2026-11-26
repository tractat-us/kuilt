package us.tractat.kuilt.heddle

import us.tractat.kuilt.heddle.Topology.ChildTemplate
import us.tractat.kuilt.heddle.Topology.Group
import us.tractat.kuilt.heddle.Topology.Lane
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a clamped waker is counted past the front by (#3238) is the edge's own outstanding (#3721).
 *
 * `LocalHeddle` threads one per-edge sum of open reservations through the wake step: the engine's
 * [LocalHeddle.reservedBeneath], which [LocalHeddle.view] hands a [LocalHeddleView] as its field,
 * and the sum [LocalHeddleSnapshot.view] forms from the persisted leases. In `LocalHeddle` every
 * delegation is one lease's reservation, so each should equal [EdgeSummary.outstanding] on every
 * edge, at every step. This asserts both sums against it after every operation, on every edge the
 * ledger knows, including one no lease crosses, where both sides must read `0`.
 *
 * The tree is `root → {ci: 1 ≤3 {manual, template(2 → 1)}, other: 3 ≤2, spare}`: two attached
 * levels under `ci`, repository → branch, and a ceiling on `ci` and on `other`. The operations
 * are acquire, complete (under, exact and overrunning), cancel, extend, admit, a reweight that
 * moves the open leases onto new edges, a reload that removes `spare` and one that brings it
 * back, attach, detach, and restore with and without the wake memory. A detach or a removal with
 * a lease open beneath leaves a CLOSING edge that drains when the lease settles.
 *
 * Exact and cheap (#3724): a fixed set of seeds, and the count of each operation the generator
 * reached is pinned, measured identical on JVM, macosArm64 and wasmJs, so a generator that stops
 * reaching one reds. Every draw walks a `List` this file built, or the leases
 * [LocalHeddle.snapshot] returns in [LeaseId] order, and the edges are sorted before they are
 * compared, so a seed walks the same trajectory on every target.
 */
class OpenReservationOutstandingPropertyTest {

    private val policy = LocalPolicy(defaultReservation = 2L)
    private val template = ChildTemplate(Weight.of(2), template = ChildTemplate(Weight.ONE))

    private fun tree(ci: Long, other: Long, spare: Boolean): Topology = Topology(
        "root",
        listOfNotNull(
            Group("ci", Weight.of(ci), listOf(Lane("manual")), template = template, maxConcurrent = CI_CEILING),
            Lane("other", Weight.of(other), maxConcurrent = OTHER_CEILING),
            Lane("spare").takeIf { spare },
        ),
    )

    private val t0 = tree(ci = 1L, other = 3L, spare = true)

    /** The reweight: every lane and group a new weight, so the reload moves each open lease onto a new edge. */
    private val reweighted = tree(ci = 2L, other = 1L, spare = true)

    /** The removal: `spare` leaves, and drains if a lease is open on it. */
    private val removed = tree(ci = 1L, other = 3L, spare = false)
    private val trees = listOf(t0, reweighted, removed)

    private val pool = listOf("a", "b")

    /** Every attached path: two repositories, and two branches under each. */
    private val attachable: List<List<String>> = pool.map { listOf(it) } + pool.flatMap { r -> pool.map { listOf(r, it) } }

    private fun pathOf(names: List<String>): GroupPath =
        names.drop(1).fold(GroupPath.Attached(listOf("ci"), names.first()) as GroupPath) { parent, name -> GroupPath.Nested(parent, name) }

    private fun idOf(names: List<String>): GroupId = checkNotNull(t0.findGroupId(pathOf(names))) { "the tree reaches $names" }

    private val ci = t0.groupId("ci")
    private val other = t0.groupId("other")

    /** Every leaf a lease can be opened on, in a fixed order: the declared lanes, then the branches. */
    private val leaves: List<GroupId> =
        listOf(t0.groupId("ci", "manual"), other, t0.groupId("spare")) + attachable.filter { it.size == 2 }.map(::idOf)

    private val branches: List<GroupId> = leaves.drop(3)

    private fun subset(rng: Random): List<GroupId> = leaves.filter { rng.nextInt(3) != 0 }

    @Test
    fun theOpenReservationsBeneathAnEdgeAreItsOutstanding() {
        val failures = ArrayList<String>()
        val tally = Tally()
        for (seed in 0 until SEEDS) {
            val rng = Random(seed)
            var h = LocalHeddle.bootWhole(t0, policy, epoch = 0L).also { it.reconcileCapacity(SLOTS) }
            // Branches start attached, so the first acquires reach two levels.
            for (names in attachable) h.attach(pathOf(names), generation = 0L)
            for (step in 0 until STEPS) {
                val open = h.snapshot().leases
                val at = "seed $seed step $step"
                val op = rng.nextInt(13)
                when {
                    op <= 2 || open.isEmpty() && op <= 6 -> {
                        val waiting = subset(rng)
                        val eligible = waiting.filter { rng.nextBoolean() }
                        val atCeiling = h.openBeneath().let { (it[ci] ?: 0) >= CI_CEILING.toInt() || (it[other] ?: 0) >= OTHER_CEILING.toInt() }
                        val out = h.acquire(waiting.toSet(), eligible.toSet(), startedAt = step.toLong(), reservation = 1L + rng.nextInt(5))
                        if (out is AcquireOutcome.Acquired) {
                            tally.acquired++
                            if (atCeiling) tally.acquiredBesideACeiling++
                            if (out.lease.leaf in branches) tally.acquiredThreeDown++
                        }
                    }
                    op == 3 -> {
                        val lease = open[rng.nextInt(open.size)]
                        if (h.extend(lease.id, 1L + rng.nextInt(4), waiting = emptySet()) is LeaseOutcome.Done) tally.extended++
                    }
                    op == 4 || op == 5 -> {
                        val lease = open[rng.nextInt(open.size)]
                        val draining = closingOn(h, lease.id)
                        val cost = rng.nextInt(9).toLong()
                        if (h.complete(lease.id, cost, waiting = emptySet()) is LeaseOutcome.Done) {
                            if (cost > lease.reserved) tally.overran++ else tally.completed++
                            if (draining) tally.drained++
                        }
                    }
                    op == 6 -> {
                        val lease = open[rng.nextInt(open.size)]
                        val draining = closingOn(h, lease.id)
                        if (h.cancel(lease.id) is LeaseOutcome.Done) {
                            tally.cancelled++
                            if (draining) tally.drained++
                        }
                    }
                    op == 7 -> {
                        val leaf = leaves[rng.nextInt(leaves.size)]
                        if (h.admit(leaf, setOf(leaf), startedAt = step.toLong(), reservation = 1L + rng.nextInt(5)) is AcquireOutcome.Acquired) tally.admitted++
                    }
                    op == 8 -> {
                        val next = trees[rng.nextInt(trees.size)]
                        val paths = open.associate { it.id to h.capturedPath(it.id) }
                        val result = h.apply(next, generation = 1_000L + step)
                        if (result.applied.isNotEmpty()) {
                            when {
                                open.any { h.capturedPath(it.id) != paths[it.id] } -> tally.reweightsThatMovedALease++
                                next === removed && open.any { it.leaf == t0.groupId("spare") } -> tally.removalsOverALease++
                                else -> tally.reloads++
                            }
                        }
                    }
                    op == 9 -> {
                        if (h.attach(pathOf(attachable[rng.nextInt(attachable.size)]), generation = 2_000L + step).applied.isNotEmpty()) tally.attached++
                    }
                    op == 10 -> {
                        val result = h.detach(pathOf(attachable[rng.nextInt(attachable.size)]))
                        if (result.refusals.any { it is StepRefusal.Draining }) tally.detachesOverALease++ else if (result.applied.isNotEmpty()) tally.detached++
                    }
                    else -> {
                        val keep = op == 11
                        h = restored(h, keep)
                        if (keep) tally.restoredKept++ else tally.restoredDropped++
                    }
                }
                tally.checks += check(h, at, failures, tally)
            }
        }
        assertAll(
            { assertEquals(emptyList(), failures.take(10), "${failures.size} mismatches") },
            { assertEquals(SEEDS * STEPS, tally.steps, "every step was checked") },
            { assertEquals(EXPECTED, tally.counts(), "the operations the generator reached") },
        )
    }

    /** Whether [lease]'s path crosses a CLOSING edge, so settling it drains one. */
    private fun closingOn(h: LocalHeddle, lease: LeaseId): Boolean {
        val ledger = h.snapshot().ledger
        return h.capturedPath(lease).orEmpty().any { ledger.lifecycle(it) == Lifecycle.CLOSING }
    }

    private fun restored(h: LocalHeddle, keepMemory: Boolean): LocalHeddle {
        val s = h.persist()
        val snapshot = if (keepMemory) s else LocalHeddleSnapshot(s.epoch, s.ledger, AllocatorMemory.EMPTY, s.totalSlots, s.open, s.lastApplied)
        return LocalHeddle.restore(snapshot, policy, epoch = s.epoch + 1)
    }

    /**
     * Both threaded sums against each edge's outstanding, on every edge the ledger knows, sorted.
     * Returns how many edges it compared.
     */
    private fun check(h: LocalHeddle, at: String, failures: MutableList<String>, tally: Tally): Int {
        tally.steps++
        val ledger = h.snapshot().ledger
        val engine = h.reservedBeneath()
        val persisted = reservedOnPaths(h.persist().open.asSequence().map { it.path to it.lease.reserved }, emptyMap())
        val edges = ledger.allEdges().sortedBy { it.value }
        for (key in (engine.keys + persisted.keys).sortedBy { it.value }) {
            if (key !in ledger.allEdges()) failures += "$at: a lease reserves on ${key.value}, which the ledger does not know"
        }
        var drainingHere = false
        for (edge in edges) {
            val outstanding = checkNotNull(ledger.edge(edge)) { "$at: ${edge.value} has no summary" }.outstanding
            val mine = engine[edge] ?: 0L
            val theirs = persisted[edge] ?: 0L
            if (mine != outstanding || theirs != outstanding) {
                failures += "$at: ${edge.value} (${ledger.lifecycle(edge)}) outstanding $outstanding, engine sum $mine, snapshot sum $theirs"
            }
            if (outstanding != 0L) tally.nonZero++
            if (outstanding != 0L && ledger.lifecycle(edge) == Lifecycle.CLOSING) drainingHere = true
        }
        if (drainingHere) tally.stepsWithADrainingEdge++
        return edges.size
    }

    private class Tally {
        var steps = 0
        var checks = 0
        var nonZero = 0
        var stepsWithADrainingEdge = 0
        var acquired = 0
        var acquiredBesideACeiling = 0
        var acquiredThreeDown = 0
        var extended = 0
        var completed = 0
        var overran = 0
        var cancelled = 0
        var drained = 0
        var admitted = 0
        var reweightsThatMovedALease = 0
        var removalsOverALease = 0
        var reloads = 0
        var attached = 0
        var detached = 0
        var detachesOverALease = 0
        var restoredKept = 0
        var restoredDropped = 0

        fun counts(): Map<String, Int> = linkedMapOf(
            "edges compared" to checks,
            "of which outstanding" to nonZero,
            "steps with a draining CLOSING edge" to stepsWithADrainingEdge,
            "acquired" to acquired,
            "acquired with a ceiling full" to acquiredBesideACeiling,
            "acquired on a branch, three edges down" to acquiredThreeDown,
            "extended" to extended,
            "completed within the reservation" to completed,
            "completed overrunning it" to overran,
            "cancelled" to cancelled,
            "settled across a CLOSING edge" to drained,
            "admitted" to admitted,
            "reweights that moved a lease" to reweightsThatMovedALease,
            "removals over an open lease" to removalsOverALease,
            "other reloads" to reloads,
            "attached" to attached,
            "detached" to detached,
            "detaches over an open lease" to detachesOverALease,
            "restored with memory" to restoredKept,
            "restored without memory" to restoredDropped,
        )
    }

    private companion object {
        const val SEEDS = 6
        const val STEPS = 150
        const val SLOTS = 8L
        const val CI_CEILING = 3L
        const val OTHER_CEILING = 2L

        /** Measured on JVM, macosArm64 and wasmJs, identical on all three. */
        val EXPECTED: Map<String, Int> = linkedMapOf(
            "edges compared" to 8591,
            "of which outstanding" to 3834,
            "steps with a draining CLOSING edge" to 286,
            "acquired" to 151,
            "acquired with a ceiling full" to 41,
            "acquired on a branch, three edges down" to 48,
            "extended" to 54,
            "completed within the reservation" to 54,
            "completed overrunning it" to 59,
            "cancelled" to 61,
            "settled across a CLOSING edge" to 22,
            "admitted" to 45,
            "reweights that moved a lease" to 19,
            "removals over an open lease" to 5,
            "other reloads" to 20,
            "attached" to 32,
            "detached" to 24,
            "detaches over an open lease" to 33,
            "restored with memory" to 70,
            "restored without memory" to 66,
        )
    }
}
