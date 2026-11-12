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
        var operations = 0
        val kinds = IntArray(5)
        for (seed in 0 until SEEDS) {
            val rng = Random(seed)
            val h = engineOver(ledger, root, supply = 200)
            repeat(STEPS) { step ->
                val open = h.snapshot().leases
                val kind = rng.nextInt(5)
                when {
                    kind == 0 || open.isEmpty() -> {
                        val waiting = subset(rng, leaves)
                        val eligible = waiting.filter { rng.nextBoolean() }
                        h.acquire(waiting.toSet(), eligible.toSet(), startedAt = step.toLong(), reservation = 1L + rng.nextInt(5))
                        kinds[0]++
                    }
                    kind == 1 -> { h.extend(open[rng.nextInt(open.size)].id, 1L + rng.nextInt(4)); kinds[1]++ }
                    kind == 2 -> { h.complete(open[rng.nextInt(open.size)].id, rng.nextInt(9).toLong()); kinds[2]++ }
                    kind == 3 -> { h.cancel(open[rng.nextInt(open.size)].id); kinds[3]++ }
                    else -> { h.admit(leaves[rng.nextInt(leaves.size)], startedAt = step.toLong(), reservation = 1L + rng.nextInt(5)); kinds[4]++ }
                }
                operations++
                assertConserved(h, "seed $seed step $step")
            }
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

    private companion object {
        const val SEEDS = 200
        const val STEPS = 60
        const val PREVIEW_SEEDS = 100
    }
}
