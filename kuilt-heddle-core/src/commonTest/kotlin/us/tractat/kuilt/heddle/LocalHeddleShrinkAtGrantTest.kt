package us.tractat.kuilt.heddle

import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A grant sized to its head (#3648, PR 2): `previewPicks(queues, …)`, and the real loop it models,
 * acquire each slot at the slot's reservation `r`, then shrink the lease to a head `h < r` straight
 * after the pick, or extend it to a head above `r`. The shrink leaves every position and the wake
 * memory as a lease opened at the head would, so the picks after it read the head.
 *
 * The oracle builds that engine another way, with no shrink anywhere: the leaf an acquire at `r`
 * would pick is read off a count-based preview of one pick, which makes no change, and the twin
 * then [admits][LocalHeddle.admit] that leaf at `min(h, r)`, observing the same waiting set (#3320),
 * extending to `h` when it is above `r`.
 *
 * Every engine here has capacity reconciled, so a pick tops the root up: [LocalHeddleShrinkTest]'s
 * oracle reconciled none. An acquire at `r` is funded for `r`, so the refund stays at the root as
 * supply, and an engine that shrank can hold, and have minted, more at the root than one that leased
 * at the head. Every holding and net position below the root agrees, and so does what has left the root, minted
 * less held. The gross lifetime counters do not: a `release` bumps `returned` and leaves issuance
 * alone, so on the shrink path effective issuance and `returned` count the reservation `r`, not
 * the head `h`.
 *
 * Exact fingerprints over a few fixed seeds, each walking a `List` the test built, so they run on
 * every target. Each run is built once per test.
 */
class LocalHeddleShrinkAtGrantTest {

    /** `root → p:2 → {p0:3, p1:1}`, `root → q:1 → {q0:1, q1:2}`: every lease crosses two edges. */
    private val tree = draft("root") {
        group("p", Weight.of(2)) {
            lane("p0", Weight.of(3))
            lane("p1")
        }
        group("q") {
            lane("q0")
            lane("q1", Weight.of(2))
        }
    }
    private val root = tree.groupId()
    private val leaves = listOf(tree.groupId("p", "p0"), tree.groupId("p", "p1"), tree.groupId("q", "q0"), tree.groupId("q", "q1"))
    private val policy = LocalPolicy(defaultReservation = R)

    /**
     * An engine with [SLOTS] slots reconciled and a seeded history behind it: picks over drawn
     * waiting sets, so lanes go idle and come back, and completions at costs either side of the
     * reservation. It ends with no lease open. Built the same way twice, it is the same engine.
     */
    private fun warmed(seed: Int): LocalHeddle {
        val h = LocalHeddle.bootWhole(tree, policy, epoch = 0L)
        h.reconcileCapacity(SLOTS)
        val rng = Random(seed)
        repeat(WARM_UP) { step ->
            val waiting = leaves.filter { rng.nextInt(3) != 0 }.toSet().ifEmpty { setOf(leaves[step % leaves.size]) }
            val picked = h.acquire(waiting, waiting, startedAt = step.toLong(), reservation = 1L + rng.nextInt(6))
            check(picked is AcquireOutcome.Acquired) { "seed $seed warm-up step $step: $picked" }
            h.complete(picked.lease.id, rng.nextInt(9).toLong(), waiting)
        }
        return h
    }

    /** Each leaf's queue, head first, with heads drawn either side of [R]: some leaves left empty. */
    private fun queues(rng: Random): Map<GroupId, List<Long>> =
        leaves.filter { rng.nextInt(4) != 0 }.associateWith { List(2 + rng.nextInt(5)) { 1L + rng.nextInt(2 * R.toInt() - 1) } }

    /** How a model sizes the lease a grant opens for [head]: at the head, or at `max(head, r)` as before #3648. */
    private fun interface Sizing {
        fun of(head: Long): Long
    }

    private val atTheHead = Sizing { it }
    private val atLeastTheReservation = Sizing { maxOf(it, R) }

    /** What a [model] run produced: the picks, and how many of its grants were sized below [R]. */
    private class Modelled(val picks: List<GroupId>, val belowReservation: Int)

    /**
     * The picks of `previewPicks(queues, eligible, R)`, built on [h] with no shrink: each pick is a
     * count-based preview of one pick at [R], which changes nothing, and the grant is an [admit] of
     * that leaf at `min(size, R)`, then an [extend][LocalHeddle.extend] by what [sizing] puts above [R].
     */
    private fun model(h: LocalHeddle, queues: Map<GroupId, List<Long>>, eligible: Set<GroupId>, sizing: Sizing): Modelled {
        val remaining = leaves.filter { it in queues }.associateWithTo(LinkedHashMap()) { ArrayDeque(queues.getValue(it)) }
        val picks = ArrayList<GroupId>()
        var below = 0
        while (true) {
            val waiting = remaining.filterValues { it.isNotEmpty() }.keys.toSet()
            val eligibleNow = eligible.filterTo(HashSet()) { it in waiting }
            if (eligibleNow.isEmpty()) break
            val leaf = h.previewPicks(waiting.associateWith { 1 }, eligibleNow, listOf(R)).singleOrNull() ?: break
            val size = sizing.of(remaining.getValue(leaf).removeFirst())
            val admitted = h.admit(leaf, waiting, startedAt = 0L, reservation = minOf(size, R))
            check(admitted is AcquireOutcome.Acquired && admitted.lease.reserved == minOf(size, R)) { "the model's admit of ${leaf.value}: $admitted" }
            if (size < R) below++
            if (size > R) {
                val stillWaiting = remaining.filterValues { it.isNotEmpty() }.keys
                check(h.extend(admitted.lease.id, size - R, stillWaiting) == LeaseOutcome.Done(0L, 0L, 0L)) { "the model's extend of ${leaf.value}" }
            }
            picks += leaf
        }
        return Modelled(picks, below)
    }

    /** One seed's preview, its model at the head, and its model at `max(head, r)`. */
    private class PreviewRun(val preview: List<GroupId>, val unchanged: Boolean, val atHead: Modelled, val atReservation: Modelled)

    private fun previewRun(seed: Int): PreviewRun {
        val rng = Random(seed * 7919 + 1)
        val queues = queues(rng)
        val eligible = queues.keys.filter { rng.nextInt(4) != 0 }.toSet().ifEmpty { queues.keys.toSet() }
        val h = warmed(seed)
        val before = h.persist()
        val preview = h.previewPicks(queues, eligible, R)
        return PreviewRun(
            preview,
            unchanged = before == h.persist(),
            atHead = model(warmed(seed), queues, eligible, atTheHead),
            atReservation = model(warmed(seed), queues, eligible, atLeastTheReservation),
        )
    }

    /**
     * The queues preview is the model at the head, in every seed, and changes nothing. The control:
     * the model at `max(head, r)`, the preview before #3648, lists another order in some seeds, so
     * the comparison sees a grant that skips the shrink.
     */
    @Test
    fun aQueuesPreviewIsThePicksOfGrantsSizedToTheirHeads() {
        val runs = SEEDS.map(::previewRun)
        assertAll(
            { assertEquals(runs.map { it.atHead.picks }, runs.map { it.preview }, "the preview against the model at the head") },
            { assertTrue(runs.all { it.unchanged }, "a preview changes nothing") },
            // The rig: grants below the reservation, in every seed.
            { assertTrue(runs.all { it.atHead.belowReservation >= 2 }, "grants below the reservation per seed: ${runs.map { it.atHead.belowReservation }}") },
            {
                val skipped = runs.count { it.atReservation.picks != it.preview }
                assertTrue(skipped >= 2, "seeds where a grant that skips the shrink lists another order: $skipped of ${runs.size}")
            },
        )
    }

    // ---- The real loop, step by step, against a twin that leased at the head ----

    /** Every edge's raw position: the virtual service a pick reads, net of returns. */
    private fun positions(h: LocalHeddle): Map<AttachmentId, VirtualTime?> {
        val ledger = h.snapshot().ledger
        return ledger.allEdges().sorted().associateWith { ledger.virtualServiceWide(it) }
    }

    /** What every group below the root holds. The root's holdings are the supply, compared through [leftTheRoot]. */
    private fun holdingsBelowTheRoot(h: LocalHeddle): Map<GroupId, Long> {
        val ledger = h.snapshot().ledger
        return groupsOf(ledger).filter { it != root }.associateWith { ledger.holdings(it, LOCAL) }
    }

    private fun minted(h: LocalHeddle): Long = h.snapshot().ledger.mintedSupply(root, LOCAL)

    private fun atRoot(h: LocalHeddle): Long = h.snapshot().ledger.holdings(root, LOCAL)

    /** What has left the root: everything minted, less what the root still holds. */
    private fun leftTheRoot(h: LocalHeddle): Long = minted(h) - atRoot(h)

    private fun nextPicks(h: LocalHeddle): List<GroupId> =
        h.previewPicks(leaves.associateWith { NEXT_PICKS }, leaves.toSet(), List(NEXT_PICKS) { 1L + it % 5 })

    private enum class Aspect { POSITIONS, HOLDINGS_BELOW_ROOT, LEFT_THE_ROOT, WAKE_MEMORY, LEASES, NEXT_PICKS }

    private fun differences(a: LocalHeddle, b: LocalHeddle): Set<Aspect> = buildSet {
        if (positions(a) != positions(b)) add(Aspect.POSITIONS)
        if (holdingsBelowTheRoot(a) != holdingsBelowTheRoot(b)) add(Aspect.HOLDINGS_BELOW_ROOT)
        if (leftTheRoot(a) != leftTheRoot(b)) add(Aspect.LEFT_THE_ROOT)
        if (a.snapshot().allocatorMemory != b.snapshot().allocatorMemory) add(Aspect.WAKE_MEMORY)
        if (a.snapshot().leases != b.snapshot().leases) add(Aspect.LEASES)
        if (nextPicks(a) != nextPicks(b)) add(Aspect.NEXT_PICKS)
    }

    /** One seed's lockstep run, and how often its rigs fired. */
    private class LoopRun(
        val divergence: String?,
        val shrinks: Int,
        val extends: Int,
        val topUps: Int,
        val mintsApart: Int,
    )

    /**
     * Two engines with the same history. At a grant, [shrunk] acquires at [R] among drawn waiting
     * and eligible sets and sizes the lease to a drawn head, as `FairSharePool` grants a slot; its
     * twin admits the leaf that acquire picked at `min(head, R)`, with the same waiting set, and
     * extends past [R] the same way. Between grants both complete a drawn open lease at a drawn
     * cost. The run stops at the first step after which the two differ.
     */
    private fun lockstep(seed: Int, shrinkOf: (LocalHeddle, LeaseId, Long) -> LeaseOutcome): LoopRun {
        val rng = Random(seed * 104_729 + 3)
        val shrunk = warmed(seed)
        val twin = warmed(seed)
        var shrinks = 0
        var extends = 0
        var topUps = 0
        // The twin is built with `admit`, whose funding mints only the shortfall; the totals part for that
        // reason, not because of the shrink (an acquire-at-the-head twin keeps them equal in most seeds).
        var mintsApart = 0
        for (step in 0 until STEPS) {
            val open = shrunk.snapshot().leases
            if (open.size < SLOTS && rng.nextInt(3) != 0 || open.isEmpty()) {
                val waiting = leaves.filter { rng.nextInt(3) != 0 }.toSet().ifEmpty { leaves.toSet() }
                val eligible = waiting.filter { rng.nextInt(4) != 0 }.toSet().ifEmpty { waiting }
                val head = 1L + rng.nextInt(2 * R.toInt())
                val mintedBefore = minted(shrunk)
                val picked = shrunk.acquire(waiting, eligible, startedAt = step.toLong(), reservation = R)
                check(picked is AcquireOutcome.Acquired) { "seed $seed step $step: $picked" }
                if (minted(shrunk) != mintedBefore) topUps++
                val admitted = twin.admit(picked.lease.leaf, waiting, startedAt = step.toLong(), reservation = minOf(head, R))
                check(admitted is AcquireOutcome.Acquired) { "seed $seed step $step: the twin's admit was $admitted" }
                when {
                    head < R -> {
                        check(shrinkOf(shrunk, picked.lease.id, R - head) == LeaseOutcome.Done(0L, 0L, 0L))
                        shrinks++
                    }
                    head > R -> {
                        val stillWaiting = waiting - picked.lease.leaf
                        check(shrunk.extend(picked.lease.id, head - R, stillWaiting) == LeaseOutcome.Done(0L, 0L, 0L))
                        check(twin.extend(admitted.lease.id, head - R, stillWaiting) == LeaseOutcome.Done(0L, 0L, 0L))
                        extends++
                    }
                }
            } else {
                val lease = open[rng.nextInt(open.size)].id
                val cost = rng.nextInt(2 * R.toInt() + 1).toLong()
                val waiting = leaves.filter { rng.nextInt(3) != 0 }.toSet()
                shrunk.complete(lease, cost, waiting)
                twin.complete(lease, cost, waiting)
            }
            if (minted(shrunk) != minted(twin)) mintsApart++
            val aspects = differences(shrunk, twin)
            if (aspects.isNotEmpty()) return LoopRun("seed $seed step $step: $aspects", shrinks, extends, topUps, mintsApart)
        }
        return LoopRun(null, shrinks, extends, topUps, mintsApart)
    }

    /**
     * Grant by grant, with capacity reconciled: positions, everything below the root, what has left
     * the root, the wake memory, the open leases and the next 30 picks agree with the twin that
     * leased at the head, in every seed. The rig: shrinks and extends landed, the root topped up
     * mid-run, and the two engines' mint totals came apart, so supply was live and the comparison
     * is not of two engines that never minted.
     */
    @Test
    fun aGrantShrunkToItsHeadLeavesWhatALeaseOpenedAtTheHeadLeaves() {
        val runs = SEEDS.map { lockstep(it, LocalHeddle::shrink) }
        assertAll(
            { assertEquals(emptyList(), runs.mapNotNull { it.divergence }) },
            { assertTrue(runs.all { it.shrinks >= 5 }, "shrinks per seed: ${runs.map { it.shrinks }}") },
            { assertTrue(runs.all { it.extends >= 1 }, "extends per seed: ${runs.map { it.extends }}") },
            { assertTrue(runs.all { it.topUps >= 1 }, "top-ups per seed: ${runs.map { it.topUps }}") },
            { assertTrue(runs.all { it.mintsApart >= 1 }, "steps with the mint totals apart, per seed (the twin's admit funds only the shortfall and never tops up, so this reflects its funding, not the shrink): ${runs.map { it.mintsApart }}") },
        )
    }

    /**
     * The positive control: a grant that skips the shrink, the lease left at [R], reds the lockstep
     * oracle in every seed at its first grant below the reservation, on positions.
     */
    @Test
    fun aGrantThatSkipsTheShrinkRedsTheOracle() {
        val runs = SEEDS.map { lockstep(it) { _, _, _ -> LeaseOutcome.Done(0L, 0L, 0L) } }
        assertAll(
            { assertTrue(runs.all { it.divergence?.contains("POSITIONS") == true }, "every seed diverged on positions: ${runs.map { it.divergence }}") },
            { assertTrue(runs.all { it.shrinks == 1 }, "each at its first grant below the reservation: ${runs.map { it.shrinks }}") },
        )
    }

    private companion object {
        const val R = 4L
        const val SLOTS = 3L
        val SEEDS = listOf(0, 1, 2, 3, 4, 5)
        const val WARM_UP = 30
        const val STEPS = 60
        const val NEXT_PICKS = 30
    }
}
