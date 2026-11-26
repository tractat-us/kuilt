package us.tractat.kuilt.heddle

import us.tractat.kuilt.heddle.Topology.ChildTemplate
import us.tractat.kuilt.heddle.Topology.Group
import us.tractat.kuilt.heddle.Topology.Lane
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * In a `LocalHeddle`, an edge's outstanding is what the open leases beneath it reserve (#3721).
 *
 * The wake step clamps a waker past the front by its edge's [EdgeSummary.outstanding] (#3238),
 * standing for the work it has in flight. Until #3721 a `LocalHeddle` threaded that sum through the
 * step instead, summed from its open leases; the clamp reads the ledger now, so this is the
 * property it rests on. In `LocalHeddle` every delegation is one lease's reservation, so the
 * outstanding on every edge, at every step, should be the reservations of the open leases whose
 * paths cross it. This sums them here, from the leases [LocalHeddle.persist] keeps, and asserts the
 * sum against the ledger after every operation, on every edge the ledger knows, including one no
 * lease crosses, where both sides must read `0`.
 *
 * The tree is `root → {ci: 1 ≤3 {manual, template(2 → 1)}, other: 3 ≤2, spare}`: two attached
 * levels under `ci`, repository → branch, and a ceiling on `ci` and on `other`. The operations
 * are acquire, complete (under, exact and overrunning), cancel, extend, shrink (part of a
 * reservation, or all of it), admit, a reweight that
 * moves the open leases onto new edges, a reload that removes `spare` and one that brings it
 * back, attach, detach, and restore with and without the wake memory. A detach or a removal with
 * a lease open beneath leaves a CLOSING edge that drains when the lease settles.
 *
 * Exact and cheap (#3724): a fixed set of seeds, and the count of each operation the generator
 * reached is pinned, measured identical on JVM, macosArm64 and wasmJs, so a generator that stops
 * reaching one reds. Every draw walks a `List` this file built, or the leases
 * [LocalHeddle.snapshot] returns in [LeaseId] order, and the edges are sorted before they are
 * compared, so a seed walks the same trajectory on every target.
 *
 * The positive control is a shrink that hands back across the leaf's edge only
 * ([LocalHeddle.shrinkAtTheLeafOnlyForTest]): it must red at every shrink of a lease whose path
 * has an edge above its leaf's, the edges where the lease's reservation no longer matches.
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
        for (seed in 0 until SEEDS) walk(seed, Shrink.WHOLE_PATH, tally, failures)
        assertAll(
            { assertEquals(emptyList(), failures.take(10), "${failures.size} mismatches") },
            { assertEquals(SEEDS * STEPS, tally.steps, "every step was checked") },
            { assertEquals(EXPECTED, tally.counts(), "the operations the generator reached") },
        )
    }

    @Test
    fun aShrinkAtTheLeafOnlyIsToldApart() {
        val missed = ArrayList<String>()
        var redded = 0
        var unseenOnOneEdge = 0
        for (seed in 0 until SEEDS) {
            val failures = ArrayList<String>()
            val shrunk = walk(seed, Shrink.LEAF_ONLY, Tally(), failures) ?: continue
            when {
                failures.isNotEmpty() -> redded++
                shrunk.size > 1 -> missed += "seed $seed: a shrink across ${shrunk.size} edges went unseen"
                else -> unseenOnOneEdge++
            }
        }
        assertAll(
            { assertEquals(emptyList(), missed) },
            { assertEquals(CONTROL_REDS, redded, "seeds whose first shrink crossed an edge above its leaf's, and redded") },
            { assertEquals(CONTROL_ONE_EDGE, unseenOnOneEdge, "seeds whose first shrink was on a one-edge path, where leaf-only is the whole path") },
        )
    }

    /** How the walk's shrinks hand back: as [LocalHeddle.shrink] does, or across the leaf's edge only. */
    private enum class Shrink { WHOLE_PATH, LEAF_ONLY }

    /**
     * One seed's walk of [STEPS] operations, checked after each into [failures]. Under
     * [Shrink.LEAF_ONLY] it stops at the first shrink, which leaves a ledger later calls need not
     * settle, and returns the path that shrink crossed; otherwise it returns `null`.
     */
    private fun walk(seed: Int, shrinking: Shrink, tally: Tally, failures: MutableList<String>): List<AttachmentId>? {
        val rng = Random(seed)
        var h = LocalHeddle.bootWhole(t0, policy, epoch = 0L).also { it.reconcileCapacity(SLOTS) }
        // Branches start attached, so the first acquires reach two levels.
        for (names in attachable) h.attach(pathOf(names), generation = 0L)
        for (step in 0 until STEPS) {
            val open = h.snapshot().leases
            val at = "seed $seed step $step"
            val op = rng.nextInt(14)
            var shrunkOn: List<AttachmentId>? = null
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
                op == 13 -> {
                    val reserving = open.filter { it.reserved > 0L }
                    if (reserving.isNotEmpty()) {
                        val lease = reserving[rng.nextInt(reserving.size)]
                        val amount = 1L + rng.nextLong(lease.reserved)
                        val out = when (shrinking) {
                            Shrink.WHOLE_PATH -> h.shrink(lease.id, amount)
                            Shrink.LEAF_ONLY -> h.shrinkAtTheLeafOnlyForTest(lease.id, amount)
                        }
                        if (out is LeaseOutcome.Done) {
                            if (amount == lease.reserved) tally.shrunkWhole++ else tally.shrunk++
                            shrunkOn = h.capturedPath(lease.id)
                        }
                    }
                }
                else -> {
                    val keep = op == 11
                    h = restored(h, keep)
                    if (keep) tally.restoredKept++ else tally.restoredDropped++
                }
            }
            tally.checks += check(h, at, failures, tally)
            if (shrinking == Shrink.LEAF_ONLY && shrunkOn != null) return shrunkOn
        }
        return null
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
     * What the open leases reserve on each edge their paths cross, summed: the reference the
     * ledger's outstanding is checked against. An edge no lease crosses is absent.
     */
    private fun reservedOnPaths(open: List<PersistedLease>): Map<AttachmentId, Long> {
        val sums = LinkedHashMap<AttachmentId, Long>()
        for (lease in open) for (edge in lease.path) sums[edge] = (sums[edge] ?: 0L) + lease.lease.reserved
        return sums
    }

    /**
     * The open leases' reservations against each edge's outstanding, on every edge the ledger
     * knows, sorted. Returns how many edges it compared.
     */
    private fun check(h: LocalHeddle, at: String, failures: MutableList<String>, tally: Tally): Int {
        tally.steps++
        val ledger = h.snapshot().ledger
        val reserved = reservedOnPaths(h.persist().open)
        val edges = ledger.allEdges().sortedBy { it.value }
        for (key in reserved.keys.sortedBy { it.value }) {
            if (key !in ledger.allEdges()) failures += "$at: a lease reserves on ${key.value}, which the ledger does not know"
        }
        var drainingHere = false
        for (edge in edges) {
            val outstanding = checkNotNull(ledger.edge(edge)) { "$at: ${edge.value} has no summary" }.outstanding
            val sum = reserved[edge] ?: 0L
            if (sum != outstanding) {
                failures += "$at: ${edge.value} (${ledger.lifecycle(edge)}) outstanding $outstanding, open leases reserve $sum"
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
        var shrunk = 0
        var shrunkWhole = 0
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
            "shrunk in part" to shrunk,
            "shrunk whole" to shrunkWhole,
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

        /** The control's seeds whose first shrink crossed an edge above its leaf's: measured. */
        const val CONTROL_REDS = 0

        /** The control's seeds whose first shrink was on a one-edge path: measured. */
        const val CONTROL_ONE_EDGE = 0

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
