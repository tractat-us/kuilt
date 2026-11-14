package us.tractat.kuilt.heddle

import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `admit(leaf, waiting, …)` observes the waiting set as the pick of `acquire(waiting, {leaf}, …)`
 * does, and charges as `admit` always has (#3320). Every seeded draw walks a `List` the test built,
 * never a `Set` or a map view, so a seed walks the same trajectory on every target.
 */
class LocalHeddleAdmitWaitingTest {

    private val root = GroupId("root")
    private val p = GroupId("p")
    private val q = GroupId("q")
    private val leaves = listOf(GroupId("p0"), GroupId("p1"), GroupId("q0"), GroupId("q1"))

    /** Prepared only, and activated part-way through a run, so a later observation seats a newborn. */
    private val late = GroupId("q2")
    private val lateEdge = edgeOf(q, late, 1)
    private val everyLeaf = leaves + late

    /** `root → p:2 → {p0:3, p1:1}`, `root → q:1 → {q0:1, q1:2}`, and `q → q2:1` prepared. */
    private val ledger = shapedLedger(
        root,
        listOf(
            edgeOf(root, p, 2), edgeOf(root, q, 1),
            edgeOf(p, leaves[0], 3), edgeOf(p, leaves[1], 1),
            edgeOf(q, leaves[2], 1), edgeOf(q, leaves[3], 2),
        ),
        prepared = listOf(lateEdge),
    )

    private fun subset(rng: Random, of: List<GroupId>): List<GroupId> = of.filter { rng.nextInt(3) != 0 }

    private fun LocalHeddle.activateLate() = reshapeForTest { l -> l.piece(checkNotNull(l.activate(lateEdge.id)) { "activate q2" }.delta) }

    /**
     * Two engines run the same random history, except that where one admits `leaf` with a waiting
     * set, the other acquires with that set and `eligible = {leaf}`. Supply is ample and no capacity
     * is reconciled, so neither mints, and after every step the two engines hold the same state:
     * ledger, open leases and wake memory alike. A seed stops at its first divergence, since the
     * engines no longer run the same history after it.
     */
    @Test
    fun anAdmitWithAWaitingSetLeavesWhatTheSingleEligibleAcquireLeaves() {
        val diverged = ArrayList<String>()
        var admitted = 0
        var refused = 0
        var clamped = 0
        var seated = 0
        for (seed in 0 until SEEDS) {
            val rng = Random(seed)
            val admits = engineOver(ledger, root, supply = SUPPLY)
            val acquires = engineOver(ledger, root, supply = SUPPLY)
            for (step in 0 until STEPS) {
                if (step == ACTIVATE_AT) {
                    admits.activateLate()
                    acquires.activateLate()
                }
                val open = admits.snapshot().leases
                val kind = rng.nextInt(6)
                when {
                    kind == 0 || (kind < 4 && open.isEmpty()) -> {
                        val waiting = subset(rng, everyLeaf).toSet()
                        val eligible = waiting.filter { rng.nextBoolean() }.toSet()
                        val r = 1L + rng.nextInt(5)
                        admits.acquire(waiting, eligible, startedAt = step.toLong(), reservation = r)
                        acquires.acquire(waiting, eligible, startedAt = step.toLong(), reservation = r)
                    }
                    kind == 1 -> {
                        val lease = open[rng.nextInt(open.size)].id
                        val amount = 1L + rng.nextInt(4)
                        admits.extend(lease, amount)
                        acquires.extend(lease, amount)
                    }
                    kind == 2 -> {
                        val lease = open[rng.nextInt(open.size)].id
                        val cost = rng.nextInt(9).toLong()
                        admits.complete(lease, cost)
                        acquires.complete(lease, cost)
                    }
                    kind == 3 -> {
                        val lease = open[rng.nextInt(open.size)].id
                        admits.cancel(lease)
                        acquires.cancel(lease)
                    }
                    else -> {
                        val leaf = everyLeaf[rng.nextInt(everyLeaf.size)]
                        val waiting = (subset(rng, everyLeaf) + leaf).toSet()
                        val r = 1L + rng.nextInt(5)
                        val before = admits.snapshot()
                        val byAdmit = admits.admit(leaf, waiting, startedAt = step.toLong(), reservation = r)
                        val byAcquire = acquires.acquire(waiting, setOf(leaf), startedAt = step.toLong(), reservation = r)
                        // A refused acquire names the group its pick stopped at, a refused admit its leaf.
                        val sameOutcome = if (byAdmit is AcquireOutcome.Acquired) {
                            byAdmit == byAcquire
                        } else {
                            byAdmit is AcquireOutcome.NoLiveLineage && byAcquire is AcquireOutcome.NoLiveLineage
                        }
                        if (!sameOutcome) {
                            diverged += "seed $seed step $step: admit $byAdmit, acquire $byAcquire"
                            break
                        }
                        val after = admits.snapshot()
                        when (byAdmit) {
                            is AcquireOutcome.Acquired -> admitted++
                            else -> refused++
                        }
                        if (before.allocatorMemory.wakeOffsets != after.allocatorMemory.wakeOffsets) clamped++
                        if (before.ledger.gauge(lateEdge.id) == null && after.ledger.gauge(lateEdge.id) != null) seated++
                    }
                }
                val a = admits.snapshot()
                val b = acquires.snapshot()
                if (a != b) {
                    diverged += "seed $seed step $step: " + when {
                        a.allocatorMemory != b.allocatorMemory ->
                            "wake memory ${a.allocatorMemory.wakeOffsets} ${a.allocatorMemory.demandingObserved} != " +
                                "${b.allocatorMemory.wakeOffsets} ${b.allocatorMemory.demandingObserved}"
                        a.ledger != b.ledger -> "ledgers differ"
                        else -> "leases differ"
                    }
                    break
                }
            }
        }
        assertAll(
            { assertEquals(emptyList(), diverged, "${diverged.size} of $SEEDS seeds diverged") },
            // The rig: the admissions charged, some were refused, some clamped a waker and one seated a newborn.
            { assertTrue(admitted > SEEDS * 5, "admissions that charged: $admitted") },
            { assertTrue(refused > 0, "admissions refused for no live lineage: $refused") },
            { assertTrue(clamped > SEEDS / 4, "admissions that clamped a waker: $clamped") },
            { assertTrue(seated > 0, "admissions that seated the late leaf: $seated") },
        )
    }

    /**
     * A consumer whose work is always started elsewhere: it only ever admits. A sibling observed
     * idle that comes back is clamped to the front it rejoins, as an `acquire` would clamp it.
     */
    @Test
    fun anIdleSiblingThatReturnsIsClampedByAnAdmit() {
        val a = GroupId("a")
        val s = GroupId("s")
        val sEdge = edgeOf(root, s).id
        val h = engineOver(shapedLedger(root, listOf(edgeOf(root, a), edgeOf(root, s))), root, supply = 1_000)
        repeat(3) { step ->
            val lease = assertIs<AcquireOutcome.Acquired>(h.admit(a, setOf(a), startedAt = step.toLong(), reservation = 2L)).lease
            h.complete(lease.id, actualCost = 2L)
        }
        val before = h.snapshot().allocatorMemory
        val returned = h.admit(a, setOf(a, s), startedAt = 3L, reservation = 2L)
        val after = h.snapshot().allocatorMemory
        assertAll(
            { assertIs<AcquireOutcome.Acquired>(returned) },
            { assertEquals(false, before.demandingObserved[sEdge], "the rig: s was observed idle before it returned") },
            // a has been charged 6 units at weight 1, so the front s rejoins is 6, and s sits at 0.
            { assertEquals(Rational.of(6L), after.wakeOffsets[sEdge], "s was clamped up to the front") },
            { assertEquals(true, after.demandingObserved[sEdge], "s is observed waiting") },
        )
    }

    @Test
    fun anAdmitWhoseLeafIsNotWaitingThrows() {
        val h = engineOver(ledger, root, supply = 100)
        val before = h.snapshot()
        assertAll(
            { assertFailsWith<IllegalArgumentException> { h.admit(leaves[0], setOf(leaves[1]), startedAt = 0L) } },
            { assertEquals(before, h.snapshot(), "and changed nothing") },
        )
    }

    private companion object {
        const val SEEDS = 40
        const val STEPS = 60
        const val ACTIVATE_AT = 25
        const val SUPPLY = 100_000L
    }
}
