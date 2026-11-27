package us.tractat.kuilt.heddle

import us.tractat.kuilt.math.Int128
import us.tractat.kuilt.math.timesWide
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What [ShareLine.had], [ShareLine.committed] and [ShareLine.forgiven] rest on (#3757, #3794): at
 * one parent, a competing child's position after the step a pick takes there is
 * `p_s = c_s · G + F_s · G`, where `c_s` is its base committed service on its current edge,
 * `baseIssued − returned`, and `F_s · G` is what the scheduler forgave it, its seat jump plus its
 * wake offset.
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
     * stopped seating, clamping or relocating reds. The placement's own forgiven, which
     * [ShareLine.forgiven] reads, is held to the same two parts.
     */
    @Test
    fun aPositionIsCommittedServicePlusWhatWasForgiven() {
        var rows = 0
        var seatJumps = 0
        var wakeOffsets = 0
        var relocated = 0
        var refused = 0
        forEachLine { asked ->
            val (h, ledger, view) = Triple(asked.h, asked.ledger, asked.view)
            for (parent in asked.parents) {
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
                        { assertEquals(jump + offset, placed.forgiven[edge], "${edge.value}'s forgiven beneath ${parent.value}") },
                        { assertEquals(committed, placed.committed[edge], "${edge.value}'s committed beneath ${parent.value}") },
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

    /**
     * The identity the ruling asks to be pinned, exactly, and from the line's public reads alone, as
     * a host would check it: at every competing parent, for each competing child `s`,
     * `lag = share_ℓ · (N + F) − c_s − F_s`, with `share_ℓ = share(s) / share(parent)`, `c_s` the
     * line's [ShareLine.committed], `N = Σc` summed from it over the siblings, and `F_s` and
     * `F = ΣF` the line's [ShareLine.forgiven]. Beside it, `had = c_s / N` and the `had`s add up to
     * `1` wherever `N` is not `0`.
     *
     * The one read that is not the line's is the oracle for `c_s` itself: the base committed
     * service this test reads off the ledger, `baseIssued − returned`, so a line that read the
     * effective figure, or any other `c` the identity happened to close over, still reds.
     *
     * Counted: rows where something was forgiven, rows where the forgiven term moves the lag
     * (`share_ℓ · F ≠ F_s`, so dropping it would red), rows whose effective committed service is
     * not the base (so reading the effective figure would red), rows where `had` is not the weight's
     * part, and parents where the siblings have had nothing.
     */
    @Test
    fun lagIsTheShareOfWhatWasHadAndForgivenLessItsOwn() {
        var rows = 0
        var forgivenRows = 0
        var forgivenMoves = 0
        var relocated = 0
        var offShare = 0
        var nothingHad = 0
        forEachLine { asked ->
            val line = asked.line
            for (parent in asked.parents) {
                val records = asked.competingBeneath(parent)
                val kids = records.map { it.child }
                if (kids.isEmpty() || line.lag(kids.first()) == null) continue
                val committed = kids.map { checkNotNull(line.committed(it)) { "${it.value} reads a lag" } }
                val n = sum(committed)
                val forgiven = kids.map { checkNotNull(line.forgiven(it)) { "${it.value} reads a lag" } }
                val f = sum(forgiven)
                val had = kids.map { checkNotNull(line.had(it)) { "${it.value} reads a lag" } }
                for ((i, kid) in kids.withIndex()) {
                    val share = line.share(kid) / line.share(parent)
                    val expected = share * (n + f) - committed[i] - forgiven[i]
                    val oracle = baseCommitted(asked.ledger, records[i].id)
                    assertAll(
                        { assertEquals(expected, line.lag(kid), "${kid.value} beneath ${parent.value}: the identity") },
                        { assertEquals(if (n == Rational.ZERO) Rational.ZERO else committed[i] / n, had[i], "${kid.value}'s had") },
                        { assertEquals(Rational.of(oracle), committed[i], "${kid.value}'s committed against the ledger's base") },
                        { assertTrue(forgiven[i] >= Rational.ZERO, "${kid.value}'s forgiven is ${forgiven[i]}") },
                    )
                    rows++
                    if (forgiven[i] > Rational.ZERO) forgivenRows++
                    if (share * f != forgiven[i]) forgivenMoves++
                    if (checkNotNull(asked.ledger.edge(records[i].id)).committedService != oracle) relocated++
                    if (n != Rational.ZERO && had[i] != share) offShare++
                }
                if (n == Rational.ZERO) nothingHad++ else assertEquals(Rational.ONE, sum(had), "the hads beneath ${parent.value}")
            }
        }
        println("SHARELINE-HAD identity rows=$rows forgiven=$forgivenRows forgivenMoves=$forgivenMoves relocated=$relocated offShare=$offShare nothingHad=$nothingHad")
        assertAll(
            { assertTrue(rows >= 2_000, "rows checked: $rows") },
            { assertTrue(forgivenRows >= 500, "rows with something forgiven: $forgivenRows") },
            { assertTrue(forgivenMoves >= 500, "rows the forgiven term moves: $forgivenMoves") },
            { assertTrue(relocated >= 20, "rows whose effective committed service is not the base: $relocated") },
            { assertTrue(offShare >= 500, "rows whose had is not the weight's part: $offShare") },
            { assertTrue(nothingHad >= 1, "parents whose siblings had nothing: $nothingHad") },
        )
    }

    /**
     * `had`, `committed` and `forgiven` are `null` on exactly the rows `lag` is, over every group a
     * ledger names and the root: so a row reads all four or none.
     */
    @Test
    fun hadCommittedAndForgivenAreNullExactlyWhereLagIs() {
        var reading = 0
        var silent = 0
        forEachLine { asked ->
            for (g in groupsOf(asked.ledger) + asked.h.root) {
                val lag = asked.line.lag(g)
                assertAll(
                    { assertEquals(lag == null, asked.line.had(g) == null, "${g.value}'s had") },
                    { assertEquals(lag == null, asked.line.committed(g) == null, "${g.value}'s committed") },
                    { assertEquals(lag == null, asked.line.forgiven(g) == null, "${g.value}'s forgiven") },
                )
                if (lag == null) silent++ else reading++
            }
        }
        assertAll(
            { assertTrue(reading >= 2_000, "rows that read: $reading") },
            { assertTrue(silent >= 500, "rows that do not: $silent") },
        )
    }

    /** [ShareLine.fractionOf] and [ShareLine.unitsOf] against plain [Rational] arithmetic, past `Long` where the answer still fits. */
    @Test
    fun theReadsAreExactAndThrowOnlyWhenTheAnswerDoesNotFit() {
        val rng = Random(3757)
        repeat(2_000) {
            val part = rng.nextLong(0L, 1L shl 40)
            val whole = part + rng.nextLong(0L, 1L shl 40)
            assertEquals(if (whole == 0L) Rational.ZERO else Rational.of(part, whole), ShareLine.fractionOf(part, Int128.of(whole)))
            val ticks = rng.nextLong(0L, 1L shl 50)
            assertEquals(Rational.of(ticks, TICKS_PER_UNIT), ShareLine.unitsOf(Int128.of(ticks)))
        }
        val big = Int128.of(Long.MAX_VALUE) + Int128.of(Long.MAX_VALUE)
        assertAll(
            { assertEquals(Rational.of(1L, 2L), ShareLine.fractionOf(Long.MAX_VALUE, big), "a whole past Long that reduces") },
            { assertFailsWith<ArithmeticException> { ShareLine.fractionOf(1L, big) } },
            { assertEquals(Rational.of(Long.MAX_VALUE, 8L), ShareLine.unitsOf(big), "ticks past Long that reduce") },
            { assertFailsWith<ArithmeticException> { ShareLine.unitsOf(big + Int128.of(1L)) } },
        )
    }

    // ── Fixtures ────────────────────────────────────────────────────────────────────────────────

    /**
     * One line asked of a history: the engine, its [ledger], the [waiting] set drawn, the [view] a
     * pick's step reads over it, the [parents] the line places, sorted, and the [line] itself.
     */
    private class Asked(
        val h: LocalHeddle,
        val ledger: EntitlementLedger,
        val competing: Set<GroupId>,
        val view: (AttachmentId) -> Demand,
        val parents: List<GroupId>,
        val line: ShareLine,
    ) {
        /** [parent]'s active children that compete, by their records, in id order. */
        fun competingBeneath(parent: GroupId): List<AttachmentRecord> =
            ledger.activeChildren(parent).mapNotNull { ledger.record(it.attachment) }.filter { it.child in competing }
    }

    /** Each of [SEEDS] histories, with a line asked every [CHECK_EVERY] steps over a drawn waiting set. */
    private fun forEachLine(check: (Asked) -> Unit) {
        for (seed in 0 until SEEDS) {
            val rng = Random(seed + 3_757)
            history(seed, rng) { h, leaves ->
                val waiting = leaves.filter { rng.nextInt(3) != 0 }.toSet()
                val ledger = h.snapshot().ledger
                val competing = ledger.reachable(waiting, h.root, Leasable.ByTopology(h.persist().lastApplied))
                val parents = groupsOf(ledger).filter { g -> ledger.activeChildren(g).any { ledger.record(it.attachment)?.child in competing } }
                check(Asked(h, ledger, competing, LocalHeddle.waitingOver(ledger, competing), parents, h.shareLine(waiting)))
            }
        }
    }

    /** [edge]'s base committed service in [ledger], `baseIssued − returned`, read without the line. */
    private fun baseCommitted(ledger: EntitlementLedger, edge: AttachmentId): Long =
        ledger.baseIssuance(edge) - checkNotNull(ledger.edge(edge)).returned

    private fun sum(values: List<Rational>): Rational = values.fold(Rational.ZERO, Rational::plus)

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
