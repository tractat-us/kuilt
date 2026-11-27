package us.tractat.kuilt.heddle

import us.tractat.kuilt.math.Int128
import us.tractat.kuilt.math.timesWide
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What [ShareLine.had] and [ShareLine.forgiven] rest on (#3757): at one parent, a competing child's
 * position after the step a pick takes there is `p_s = c_s · G + F_s · G`, where `c_s` is its base
 * committed service on its current edge, `baseIssued − returned`, and `F_s · G` is what the
 * scheduler forgave it, its seat jump plus its wake offset.
 *
 * The histories are this file's own: random trees of depth up to three with fractional weights,
 * leases acquired, extended, completed under, at and over what they reserved, and cancelled, with
 * the waiting set moving so children idle, wake and return, and a reload now and then that
 * reweights one group, so an edge can carry relocation credit. Every seeded draw walks a `List` this
 * file built, never a `Set` or a map view.
 */
class ShareLineHadTest {

    /**
     * The decomposition itself, read on the step's own outputs: the seated ledger [FairShareAllocator.settle]
     * returns, for the seat jump `ticks − folded · G` and the base counters, and the wake memory the
     * step leaves, for the offset. Each part is counted where it is not zero, so a generator that
     * stopped seating, clamping or relocating reds.
     */
    @Test
    fun aPositionIsCommittedServicePlusWhatWasForgiven() {
        var rows = 0
        var seatJumps = 0
        var wakeOffsets = 0
        var relocated = 0
        var refused = 0
        forEachLine { h, ledger, view, parents ->
            for (parent in parents) {
                val placed = h.allocator.place(parent, ledger, view) as? FairShareAllocator.Placement.Placed
                if (placed == null) {
                    refused++
                    continue
                }
                val step = h.allocator.fork()
                val seated = step.settle(parent, ledger, view)
                val offsets = step.memory.wakeOffsets
                for (summary in seated.activeChildren(parent)) {
                    val edge = summary.attachment
                    val committed = seated.baseIssuance(edge) - summary.returned
                    val jump = seated.gauge(edge)?.let { it.ticks - it.folded.timesWide(TICKS_PER_UNIT) } ?: Int128.ZERO
                    val offset = offsets[edge] ?: Int128.ZERO
                    val expected = committed.timesWide(TICKS_PER_UNIT) + jump + offset
                    assertAll(
                        { assertEquals(expected, placed.positions[edge], "${edge.value} beneath ${parent.value}") },
                        { assertTrue(jump >= Int128.ZERO, "${edge.value}'s seat jump is $jump") },
                        { assertTrue(offset >= Int128.ZERO, "${edge.value}'s wake offset is $offset") },
                    )
                    rows++
                    if (jump > Int128.ZERO) seatJumps++
                    if (offset > Int128.ZERO) wakeOffsets++
                    if (summary.committedService != committed) relocated++
                }
            }
        }
        println("SHARELINE-HAD decomposition rows=$rows seatJumps=$seatJumps wakeOffsets=$wakeOffsets relocated=$relocated refused=$refused")
        assertAll(
            { assertTrue(rows >= 2_000, "rows checked: $rows") },
            { assertTrue(seatJumps >= 200, "rows a seat jump forgave: $seatJumps") },
            { assertTrue(wakeOffsets >= 200, "rows a wake offset forgave: $wakeOffsets") },
            { assertTrue(relocated >= 20, "rows whose effective committed service is not the base: $relocated") },
        )
    }

    // ── Fixtures ────────────────────────────────────────────────────────────────────────────────

    /**
     * Each of [SEEDS] histories, with a line asked every [CHECK_EVERY] steps over a drawn waiting
     * set: [check] gets the engine, its ledger, the waiting view a pick's step reads, and the
     * parents a line over that set places, sorted.
     */
    private fun forEachLine(check: (LocalHeddle, EntitlementLedger, (AttachmentId) -> Demand, List<GroupId>) -> Unit) {
        for (seed in 0 until SEEDS) {
            val rng = Random(seed + 3_757)
            history(seed, rng) { h, leaves ->
                val waiting = leaves.filter { rng.nextInt(3) != 0 }.toSet()
                val ledger = h.snapshot().ledger
                val competing = ledger.reachable(waiting, h.root, Leasable.ByTopology(h.persist().lastApplied))
                val parents = groupsOf(ledger).filter { g -> ledger.activeChildren(g).any { ledger.record(it.attachment)?.child in competing } }
                check(h, ledger, LocalHeddle.waitingOver(ledger, competing), parents)
            }
        }
    }

    /** One group or lane of a generated tree: a lane when it has no [children]. */
    private class Spec(val name: String, val weight: Weight, val children: List<Spec>) {
        fun node(): Topology.Node =
            if (children.isEmpty()) Topology.Lane(name, weight) else Topology.Group(name, weight, children.map { it.node() })

        /** Every path from here down, this one first, in the order written. */
        fun paths(prefix: List<String>): List<List<String>> =
            listOf(prefix + name) + children.flatMap { it.paths(prefix + name) }

        /** This node with the one at [path] (relative to here) given [weight]. */
        fun reweighted(path: List<String>, weight: Weight): Spec = when {
            path.size == 1 -> Spec(name, weight, children)
            else -> Spec(name, this.weight, children.map { if (it.name == path[1]) it.reweighted(path.drop(1), weight) else it })
        }
    }

    private fun weight(rng: Random): Weight = Weight.of(1L + rng.nextInt(4), 1L + rng.nextInt(3))

    private fun specs(rng: Random, depth: Int): List<Spec> = List(2 + rng.nextInt(2)) { i ->
        val children = if (depth < 3 && rng.nextInt(2) == 0) specs(rng, depth + 1) else emptyList()
        Spec("n$i", weight(rng), children)
    }

    private fun topologyOf(specs: List<Spec>): Topology = Topology("root", specs.map { it.node() })

    /**
     * A random history on an engine over a generated tree, calling [observe] every [CHECK_EVERY]
     * steps with the engine and its leaves. One seed in three runs with a sleeper credit, so a
     * clamp can leave a waker short of the front.
     */
    private fun history(seed: Int, rng: Random, observe: (LocalHeddle, List<GroupId>) -> Unit) {
        var specs = specs(rng, depth = 1)
        val start = topologyOf(specs)
        val policy = LocalPolicy(defaultReservation = 1L, sleeperCredit = if (seed % 3 == 0) 1L + rng.nextInt(3) else 0L)
        val h = LocalHeddle.bootWhole(start, policy, epoch = 0L)
        h.reconcileCapacity(1L + rng.nextInt(4))
        val paths = specs.flatMap { it.paths(emptyList()) }
        val leaves = paths.filter { path -> paths.none { it.size == path.size + 1 && it.take(path.size) == path } }
            .map { start.groupId(*it.toTypedArray()) }
        var generation = 0L
        repeat(STEPS) { step ->
            val open = h.snapshot().leases
            val waiting = leaves.filter { rng.nextBoolean() }.toSet()
            when {
                rng.nextInt(12) == 0 -> {
                    val path = paths[rng.nextInt(paths.size)]
                    specs = specs.map { if (it.name == path[0]) it.reweighted(path, weight(rng)) else it }
                    h.apply(topologyOf(specs), generation = ++generation)
                }
                open.size < 2 || rng.nextInt(3) == 0 ->
                    h.acquire(waiting, waiting.filter { rng.nextInt(3) != 0 }.toSet(), step.toLong(), 1L + rng.nextInt(4))
                rng.nextInt(5) == 0 -> h.extend(open[rng.nextInt(open.size)].id, 1L + rng.nextInt(3), waiting)
                rng.nextInt(6) == 0 -> h.cancel(open[rng.nextInt(open.size)].id)
                else -> h.complete(open[rng.nextInt(open.size)].id, rng.nextInt(7).toLong(), waiting)
            }
            if (step % CHECK_EVERY == CHECK_EVERY - 1) observe(h, leaves)
        }
    }

    private companion object {
        const val SEEDS = 60
        const val STEPS = 80
        const val CHECK_EVERY = 5
    }
}
