package us.tractat.kuilt.heddle

import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Returned service folds into the gauge of every edge a return crosses, so it no longer climbs
 * toward the position ceiling (#3360).
 *
 * An edge's gross position used to keep `returned · G`, service reserved and handed back unused.
 * The rebase (#3329) lowers gross and raw alike, so it could never take that part down, and a lane
 * that reserved [LocalHeddle.MAX_RESERVATION] and charged one unit reached its ceiling after a few
 * thousand leases. Each return now raises the gauge's fold and its returns marker by the returns
 * not yet folded: gross falls by exactly those returns, and the raw position a pick reads does not
 * move.
 *
 * Each arm runs a twin with the fold switched off ([LocalHeddle.foldReturnsForTest]). The twin is
 * what proves an arm reaches the state the fold exists for: it must refuse at its ceiling inside
 * the run, and every pick it makes before that must be the folding engine's.
 *
 * The generators walk only [List]s they built, in declaration order, so a seed takes the same
 * trajectory on every target.
 */
class LocalHeddleReturnsFoldTest {

    /**
     * The issue's own arm: lanes `p = 1`, `q = 3/7`, `r = 2`, all of them waiting every step, every
     * lease at [LocalHeddle.MAX_RESERVATION] and charged one unit. Without the fold, `q`'s gross
     * gains `(MAX_RESERVATION − 1) · G` ticks a lease and reaches the ceiling at its
     * [expectedTwinLeasesAtQ]th lease, about pick 37,450. With it, [ISSUE_PICKS] picks run, and
     * no position climbs past a quarter of its ceiling.
     */
    @Test
    fun aLaneReservingTheMaximumAndChargingOneUnitRunsAHundredThousandPicks() {
        val topology = draft("rf") { ISSUE_LANES.forEach { (name, weight) -> lane(name, weight) } }
        val lanes = ISSUE_LANES.map { topology.groupId(it.first) }.toSet()
        val q = topology.groupId("q")
        val engine = boot(topology)
        val twin = boot(topology).apply { foldReturnsForTest = false }

        var picks = 0
        var refusal: String? = null
        var twinRefusedAt = -1
        var twinLeasesAtQ = 0
        val divergences = ArrayList<String>()
        var highestShare = 0.0

        for (step in 0 until ISSUE_PICKS) {
            val outcome = engine.acquire(lanes, lanes, startedAt = step.toLong(), reservation = LocalHeddle.MAX_RESERVATION)
            if (outcome !is AcquireOutcome.Acquired) {
                refusal = "pick ${step + 1}: $outcome"
                break
            }
            engine.complete(outcome.lease.id, actualCost = 1L)
            picks++
            if (twinRefusedAt < 0) {
                when (val twinOutcome = twin.acquire(lanes, lanes, startedAt = step.toLong(), reservation = LocalHeddle.MAX_RESERVATION)) {
                    is AcquireOutcome.Acquired -> {
                        twin.complete(twinOutcome.lease.id, actualCost = 1L)
                        if (twinOutcome.lease.leaf == q) twinLeasesAtQ++
                        if (twinOutcome.lease.leaf != outcome.lease.leaf && divergences.size < MAX_REPORTED) {
                            divergences += "pick ${step + 1}: ${outcome.lease.leaf.value} against the twin's ${twinOutcome.lease.leaf.value}"
                        }
                    }
                    is AcquireOutcome.PositionCeiling -> twinRefusedAt = step
                    else -> error("pick ${step + 1}: the twin answered $twinOutcome")
                }
            }
            if (step % SAMPLE_EVERY == 0) highestShare = maxOf(highestShare, highestCeilingShare(engine))
        }
        highestShare = maxOf(highestShare, highestCeilingShare(engine))
        val qEdge = edgeInto(engine, q)
        val ledger = engine.snapshot().ledger

        assertAll(
            { assertEquals(null, refusal, "the folding engine never refused") },
            { assertEquals(ISSUE_PICKS, picks, "every step made one pick") },
            // The rig: without the fold the same run reaches the ceiling, at the lease the bound names.
            { assertTrue(twinRefusedAt >= 0, "the twin, with no fold, refused at its ceiling within $ISSUE_PICKS picks") },
            { assertEquals(expectedTwinLeasesAtQ(), twinLeasesAtQ, "the twin refused once q had taken the leases its ceiling allows") },
            { assertEquals(emptyList<String>(), divergences, "every pick matched the twin's until it refused") },
            { assertEquals(ledger.edge(qEdge)?.returned, ledger.gauge(qEdge)?.returnsFolded, "every return q made is folded") },
            { assertTrue(highestShare < MAX_SHARE, "the highest position reached ${highestShare * 100}% of its ceiling") },
        )
    }

    /**
     * Returns of every size over a nested group, so the edge into the group folds as well as the
     * lanes' own: one lease in three cancelled, one charged half its reservation, one charged all
     * but a unit. After every return each edge of the lease's path has folded all of its returns,
     * and its raw position came down by exactly what it was handed back: the fold moved gross and
     * nothing a pick reads. Halfway through, the engine is persisted, encoded in CBOR, decoded and
     * restored, and carries on. A twin with no fold makes the same picks until it refuses at its
     * ceiling, which it must, inside the run.
     */
    @Test
    fun everyReturnFoldsOnEveryPathEdgeAndLeavesTheRawPositionWhereTheReturnPutIt() {
        val topology = draft("mx") {
            MIXED_LANES.forEach { (name, weight) -> lane(name, weight) }
            group("n", Weight.of(2)) { NESTED.forEach { (name, weight) -> lane(name, weight) } }
        }
        val leaves = MIXED_LANES.map { topology.groupId(it.first) } + NESTED.map { topology.groupId("n", it.first) }
        var engine = boot(topology)
        val twin = boot(topology).apply { foldReturnsForTest = false }
        val random = Random(3360)

        var picks = 0
        var restored = 0
        var twinRefusedAt = -1
        var foldedPathEdges = 0
        var nestedFolds = 0
        val divergences = ArrayList<String>()
        val violations = ArrayList<String>()
        val nEdge = edgeInto(engine, topology.groupId("n"))

        for (step in 0 until MIXED_PICKS) {
            val waiting = leaves.filter { random.nextBoolean() }.ifEmpty { listOf(leaves[random.nextInt(leaves.size)]) }.toSet()
            val kind = step % 3
            val outcome = engine.acquire(waiting, waiting, startedAt = step.toLong(), reservation = LocalHeddle.MAX_RESERVATION)
            check(outcome is AcquireOutcome.Acquired) { "pick ${step + 1}: expected a lease, got $outcome" }
            picks++
            val path = checkNotNull(engine.capturedPath(outcome.lease.id)) { "the lease has a path" }
            val handedBack = when (kind) {
                0 -> LocalHeddle.MAX_RESERVATION
                1 -> LocalHeddle.MAX_RESERVATION / 2
                else -> 1L
            }
            val before = path.associateWith { rawTicks(engine, it) }
            settle(engine, outcome.lease.id, kind)
            val ledger = engine.snapshot().ledger
            for (edge in path) {
                val gauge = checkNotNull(ledger.gauge(edge)) { "${edge.value} is seated" }
                val returned = ledger.edge(edge)?.returned ?: 0L
                if (gauge.returnsFolded != returned && violations.size < MAX_REPORTED) {
                    violations += "pick ${step + 1}: ${edge.value} folded ${gauge.returnsFolded} of $returned returned"
                }
                val expected = before.getValue(edge) - handedBack * TICKS_PER_UNIT
                val after = rawTicks(engine, edge)
                if (after != expected && violations.size < MAX_REPORTED) {
                    violations += "pick ${step + 1}: ${edge.value} reads $after ticks, the return put it at $expected"
                }
                if (gauge.returnsFolded > 0L) foldedPathEdges++
                if (edge == nEdge && gauge.returnsFolded > 0L) nestedFolds++
            }

            if (twinRefusedAt < 0) {
                when (val twinOutcome = twin.acquire(waiting, waiting, startedAt = step.toLong(), reservation = LocalHeddle.MAX_RESERVATION)) {
                    is AcquireOutcome.Acquired -> {
                        settle(twin, twinOutcome.lease.id, kind)
                        if (twinOutcome.lease.leaf != outcome.lease.leaf && divergences.size < MAX_REPORTED) {
                            divergences += "pick ${step + 1}: ${outcome.lease.leaf.value} against the twin's ${twinOutcome.lease.leaf.value}"
                        }
                    }
                    is AcquireOutcome.PositionCeiling -> twinRefusedAt = step
                    else -> error("pick ${step + 1}: the twin answered $twinOutcome")
                }
            }

            if (step == MIXED_PICKS / 2) {
                val bytes = Cbor.encodeToByteArray(LocalHeddleSnapshot.serializer(), engine.persist())
                val decoded = LocalHeddle.decode(bytes, Cbor)
                check(decoded is RestoreResult.Restored) { "the snapshot did not decode: $decoded" }
                engine = LocalHeddle.restore(decoded.snapshot, LocalPolicy(defaultReservation = 1L), epoch = 2L)
                restored++
            }
        }

        assertAll(
            { assertEquals(MIXED_PICKS, picks, "every step made one pick") },
            { assertEquals(1, restored, "the run went through one persist and restore") },
            { assertEquals(emptyList<String>(), violations, "every path edge folded its returns and kept its raw position") },
            // The rig: folds happened, on the group's edge too, and the run is one the twin cannot finish.
            { assertTrue(foldedPathEdges > 0, "the rig: path edges folded") },
            { assertTrue(nestedFolds > 0, "the rig: the edge into the nested group folded") },
            { assertTrue(twinRefusedAt >= 0, "the twin, with no fold, refused at its ceiling within $MIXED_PICKS picks") },
            { assertEquals(emptyList<String>(), divergences, "every pick matched the twin's until it refused") },
        )
    }

    /**
     * A parent charged only through [LocalHeddle.admit] is never picked at, so never rebased, but
     * the fold is a return's and not a pick's, so the returns under it fold all the same. The twin
     * with no fold reaches the ceiling, where a charge still lands; the folding engine stays far
     * below it.
     */
    @Test
    fun returnsUnderAParentChargedOnlyThroughAdmitFoldToo() {
        val topology = draft("ad") { ISSUE_LANES.forEach { (name, weight) -> lane(name, weight) } }
        val q = topology.groupId("q")
        val engine = boot(topology)
        val twin = boot(topology).apply { foldReturnsForTest = false }
        val leases = expectedTwinLeasesAtQ() + ADMIT_MARGIN
        for (e in listOf(engine, twin)) {
            repeat(leases) {
                val admitted = e.admit(q, startedAt = it.toLong(), reservation = LocalHeddle.MAX_RESERVATION)
                check(admitted is AcquireOutcome.Acquired) { "admit ${it + 1}: $admitted" }
                e.complete(admitted.lease.id, actualCost = 1L)
            }
        }
        val edge = edgeInto(engine, q)
        val ledger = engine.snapshot().ledger
        assertAll(
            { assertEquals(0, engine.rebasesForTest, "the rig: the parent was never rebased") },
            { assertTrue(twin.snapshot().ledger.isAtPositionCeiling(edge), "the rig: without the fold, q reaches its ceiling") },
            { assertTrue(!ledger.isAtPositionCeiling(edge), "with it, q does not") },
            { assertEquals(ledger.edge(edge)?.returned, ledger.gauge(edge)?.returnsFolded, "every return is folded") },
            { assertTrue(highestCeilingShare(engine) < MAX_SHARE, "q's gross stays far below its ceiling") },
        )
    }

    private fun boot(topology: Topology): LocalHeddle =
        LocalHeddle.fromTopology(topology, LocalPolicy(defaultReservation = 1L), epoch = 1L).apply { reconcileCapacity(1L) }

    /** Close [lease] the way step kind [kind] does: cancelled, charged half, or charged all but a unit. */
    private fun settle(engine: LocalHeddle, lease: LeaseId, kind: Int) {
        val outcome = when (kind) {
            0 -> engine.cancel(lease)
            1 -> engine.complete(lease, actualCost = LocalHeddle.MAX_RESERVATION / 2)
            else -> engine.complete(lease, actualCost = LocalHeddle.MAX_RESERVATION - 1L)
        }
        check(outcome is LeaseOutcome.Done) { "settling $lease: $outcome" }
    }

    /** The one edge into [group] in [engine]'s ledger. */
    private fun edgeInto(engine: LocalHeddle, group: GroupId): AttachmentId =
        checkNotNull(engine.snapshot().ledger.lineageOf(group)).last()

    /** [edge]'s raw position in [engine], in ticks: the virtual service a pick reads, on the edge's grid. */
    private fun rawTicks(engine: LocalHeddle, edge: AttachmentId): Long {
        val ledger = engine.snapshot().ledger
        val weight = checkNotNull(ledger.record(edge)).weight
        return ticksAt(checkNotNull(ledger.virtualService(edge)), weight)
    }

    /**
     * The q lease at which a twin with no fold reaches its ceiling: each lease adds
     * `(MAX_RESERVATION − 1) · G` ticks of returns and `G` of charge to q's gross, and the pick
     * that would order it refuses once that passes `positionCeiling(3/7)`.
     */
    private fun expectedTwinLeasesAtQ(): Int {
        val perLease = LocalHeddle.MAX_RESERVATION * TICKS_PER_UNIT
        val ceiling = positionCeiling(Weight.of(3, 7))
        return ((ceiling + perLease - 1) / perLease).toInt()
    }

    /**
     * The largest share of its own ceiling any edge's position reads at in [engine]: its gross
     * position, or its effective one, the raw position plus the wake offset a pick reads.
     */
    private fun highestCeilingShare(engine: LocalHeddle): Double {
        val state = engine.snapshot()
        val ledger = state.ledger
        return ledger.allEdges().maxOf { edge ->
            val weight = checkNotNull(ledger.record(edge)).weight
            val gross = ledger.gauge(edge).grossTicksAt(ledger.baseIssuance(edge), weight)
            val raw = ticksAt(checkNotNull(ledger.virtualService(edge)), weight)
            val offset = state.allocatorMemory.wakeOffsets[edge]?.let { ticksAt(it, weight) } ?: 0L
            maxOf(gross, raw + offset).toDouble() / positionCeiling(weight).toDouble()
        }
    }

    private companion object {
        const val ISSUE_PICKS = 100_000
        const val MIXED_PICKS = 20_000
        const val SAMPLE_EVERY = 100
        const val MAX_REPORTED = 5

        /** Admits past the twin's ceiling, so the twin is there with room to spare. */
        const val ADMIT_MARGIN = 10

        /** A rebase fires at a sixteenth of the ceiling; a quarter leaves room for the spread above it. */
        const val MAX_SHARE = 0.25

        val ISSUE_LANES = listOf(
            "p" to Weight.ONE,
            "q" to Weight.of(3, 7),
            "r" to Weight.of(2),
        )
        /** `q`'s denominator is large so its ceiling is low: the twin with no fold reaches it within the run. */
        val MIXED_LANES = listOf(
            "p" to Weight.ONE,
            "q" to Weight.of(31, 73),
        )
        val NESTED = listOf(
            "x" to Weight.ONE,
            "y" to Weight.of(3, 2),
        )
    }
}
