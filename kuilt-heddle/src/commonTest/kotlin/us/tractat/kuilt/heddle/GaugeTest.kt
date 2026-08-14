package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.assertAll
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The [Gauge] register itself (issue #1752): its join laws, its wire form, and the
 * [EntitlementLedger] read path over it.
 *
 * The adversarial attacks on the *composition* — F1's stale bump, F2's relocation-receiving edge,
 * the staleness-window scaling, and the patch-atomicity negative control — belong with the write
 * rules that make them expressible and land with those. What is pinned here is everything the
 * write rules will rest on: that the join deflates rather than preserves a stale pair, that a
 * hostile wire form cannot smuggle in a denormalized floor, that the read folds **base** issuance
 * only, and how far the floor's denominator can chain before exact arithmetic runs out of `Long`.
 */
class GaugeTest {

    // ── the register laws: componentwise join, and why lexicographic-by-floor is wrong ──

    /**
     * Componentwise max is idempotent/commutative/associative (a product of two total orders),
     * and — the load-bearing property — it can only *deflate* a stale floor, by pairing it with a
     * better-informed fold.
     *
     * The lexicographic-by-floor alternative keeps the stale writer's pair **whole** and
     * reintroduces the double count, so it is asserted here too: this is the one "simplification"
     * a later reader is most likely to reach for, and the difference is invisible from the join's
     * type alone.
     */
    @Test
    fun componentwiseJoinLawsHoldAndLexicographicWouldNot() {
        val stale = Gauge(Rational.of(281L, 4L), 0L) // a stale bump: front 70¼, fold 0
        val honest = Gauge(Rational.of(70L), 20L) // the honest checkpoint after 20 units
        val seat = Gauge(Rational.of(50L), 0L) // the original seat

        assertAll(
            { assertEquals(stale, stale.join(stale), "idempotent") },
            { assertEquals(stale.join(honest), honest.join(stale), "commutative") },
            {
                assertEquals(
                    stale.join(honest).join(seat),
                    stale.join(honest.join(seat)),
                    "associative",
                )
            },
        )

        // Componentwise keeps (70¼, 20): the stale floor, paired with the honest fold.
        val merged = stale.join(honest)
        assertEquals(Gauge(Rational.of(281L, 4L), 20L), merged)
        val readAt20 = merged.floor + Rational.of(20L - merged.folded, 1L)
        assertEquals(
            Rational.of(281L, 4L),
            readAt20,
            "read = 70¼ — overshoot ¼, the sibling ev-spread at the write, not the service re-counted",
        )

        // Lexicographic-by-floor would keep `stale` whole: 70¼ + 20 = 90¼ — F1's double count.
        val lexKept = if (stale.floor >= honest.floor) stale else honest
        val lexRead = lexKept.floor + Rational.of(20L - lexKept.folded, 1L)
        assertTrue(lexRead > Rational.of(90L), "a lexicographic join re-adds the service: $lexRead")
    }

    /** Neither component may fall under the join — the register is a max on each axis. */
    @Test
    fun joinNeverLowersEitherComponent() {
        val rnd = Random(0x6A063)
        repeat(500) {
            val a = Gauge(Rational.of(rnd.nextLong(-500L, 500L), rnd.nextLong(1L, 9L)), rnd.nextLong(0L, 500L))
            val b = Gauge(Rational.of(rnd.nextLong(-500L, 500L), rnd.nextLong(1L, 9L)), rnd.nextLong(0L, 500L))
            val merged = a.join(b)
            assertAll(
                { assertTrue(merged.floor >= a.floor && merged.floor >= b.floor, "floor fell: $a ⊔ $b = $merged") },
                { assertTrue(merged.folded >= a.folded && merged.folded >= b.folded, "fold fell: $a ⊔ $b = $merged") },
                { assertEquals(merged, merged.join(a), "a ⊑ a ⊔ b") },
            )
        }
    }

    // ── the wire form: the #1647 read-path guard, on both components ─────────────────────

    /**
     * `folded` is an issuance count, so it is non-negative — and the generated deserializer calls
     * the primary constructor, so the `init` check runs on the read path for free (the
     * [MintRecord] shape). A negative fold would read as service the edge has *un*-received and
     * inflate its virtual time without bound, so it must not decode.
     *
     * This is why [Gauge] deliberately does **not** put its invariant in a private-constructor
     * factory the way [Weight]/[Rational] do: that shape is exactly what #1647 showed the plugin
     * bypasses. Keeping the check in `init` makes the bypass unrepresentable rather than guarded.
     */
    @Test
    fun aNegativeFoldCannotArriveOffTheWire() {
        val malformed = """{"floor":{"numerator":70,"denominator":1},"folded":-20}"""
        assertFailsWith<IllegalArgumentException>("a negative fold must not decode") {
            Json.decodeFromString<Gauge>(malformed)
        }
    }

    /**
     * The floor's guard, which cannot live in `init` — [Rational]'s invariant is enforced in its
     * factory, and a generated serializer would write past it. [RationalSerializer] is what
     * [Gauge.floor] routes through, and it **repairs** what has a unique canonical form.
     *
     * Repair rather than reject is [WeightSerializer]'s rule and it matters at least as much
     * here: a `Gauge` rides the same [EntitlementLedger] frames a `Weight` does, and both decode
     * boundaries drop the *entire frame* on a decode failure — so refusing `2/4` would discard
     * every legitimate record travelling with it and anti-entropy would resend it forever.
     */
    @Test
    fun aDenormalizedFloorIsRepairedNotRejected() {
        val unreduced = Json.decodeFromString<Gauge>(
            """{"floor":{"numerator":140,"denominator":4},"folded":20}""",
        )
        val signFlipped = Json.decodeFromString<Gauge>(
            """{"floor":{"numerator":-70,"denominator":-2},"folded":20}""",
        )
        val canonical = Gauge(Rational.of(35L), 20L)
        assertAll(
            { assertEquals(canonical, unreduced, "140/4 must reduce to 35/1") },
            { assertEquals(canonical, signFlipped, "-70/-2 is 35/1 — a repairable encoding") },
            {
                // The point of repairing: a denormalized floor that survived would compare wrong.
                // Rational.compareTo cross-multiplies assuming a positive denominator, so a
                // decoded -70/-2 would order below 0 and seat the edge with lifetime credit.
                assertTrue(unreduced.floor > Rational.ZERO, "a repaired floor orders correctly")
                assertTrue(signFlipped.floor > Rational.ZERO, "…and so does a sign-normalized one")
            },
        )
    }

    /**
     * A zero denominator names no rational, so there is nothing to repair to. Substituting a
     * default would fabricate a virtual-time claim out of hostile input — and a gauge floor is
     * precisely what seats an edge, so that is a fairness lie, not a cosmetic one. It throws at
     * the decode boundary, before any lattice state is touched.
     */
    @Test
    fun aFloorNamingNoRationalIsRefused() {
        assertFailsWith<Exception>("a zero denominator must not decode") {
            Json.decodeFromString<Gauge>("""{"floor":{"numerator":70,"denominator":0},"folded":20}""")
        }
    }

    /** Round-tripping a well-formed gauge is the identity — the guard costs nothing legitimate. */
    @Test
    fun wellFormedGaugesRoundTrip() {
        val rnd = Random(0x0A17E)
        repeat(200) {
            val g = Gauge(Rational.of(rnd.nextLong(-999L, 999L), rnd.nextLong(1L, 17L)), rnd.nextLong(0L, 999L))
            assertEquals(g, Json.decodeFromString<Gauge>(Json.encodeToString(g)))
        }
    }

    // ── the read path over the ledger ────────────────────────────────────────────────────

    /**
     * With no gauge stored the edge reads straight from zero — `baseIssued / w`. That is the
     * unseated read, and it is what makes gauge **absence** a usable seat-bump predicate: an
     * unseated edge is not silently credited with a front it never joined.
     */
    @Test
    fun anEdgeWithNoGaugeReadsFromZero() {
        val w = Weight.of(2L, 3L)
        val s = ledgerWith(weight = w, issued = 10L)
        assertAll(
            { assertNull(s.gauge(EDGE), "no gauge stored") },
            // 10 / (2/3) = 15
            { assertEquals(Rational.of(15L), s.grossVirtualService(EDGE)) },
        )
    }

    /** With a gauge stored the read advances the floor over issuance **since the fold**. */
    @Test
    fun aStoredGaugeAdvancesItsFloorOverIssuanceSinceTheFold() {
        val s = ledgerWith(weight = Weight.ONE, issued = 20L, gauge = Gauge(Rational.of(50L), 5L))
        // 50 + (20 − 5)/1 = 65
        assertEquals(Rational.of(65L), s.grossVirtualService(EDGE))
    }

    /** `virtualService` is the gross read net of returns: `gross − returned / w`. */
    @Test
    fun virtualServiceSubtractsReturnsFromTheGrossRead() {
        val s = ledgerWith(
            weight = Weight.ONE,
            issued = 20L,
            returned = 4L,
            gauge = Gauge(Rational.of(50L), 5L),
        )
        assertAll(
            { assertEquals(Rational.of(65L), s.grossVirtualService(EDGE)) },
            { assertEquals(Rational.of(61L), s.virtualService(EDGE)) },
        )
    }

    /**
     * **F2's structural half.** The fold axis is the *base* `issued` counter, so a relocated
     * magnitude — `issuedRelocIn`, which the control plane writes when `reconcileStranded`
     * re-homes a strand — does not enter the read at all.
     *
     * This is what makes the register arrival-order independent: a reconcile patch touches no
     * term of `grossVirtualService`, so it cannot matter whether it lands before or after the
     * edge is seated. Read at *effective* issuance instead, this edge would appear to have burned
     * its whole 300-unit strand the instant it received it — starvation sized by the strand,
     * which is exactly the coupling to #1665 that F2 named.
     */
    @Test
    fun aRelocatedMagnitudeNeverEntersTheVirtualTimeRead() {
        val seated = Gauge(Rational.of(400L), 0L)
        val withoutReloc = ledgerWith(weight = Weight.ONE, issued = 2L, gauge = seated)
        val withReloc = ledgerWith(weight = Weight.ONE, issued = 2L, gauge = seated, issuedRelocIn = 300L)
        assertAll(
            { assertEquals(Rational.of(402L), withoutReloc.grossVirtualService(EDGE)) },
            {
                assertEquals(
                    Rational.of(402L),
                    withReloc.grossVirtualService(EDGE),
                    "a 300-unit re-home must not advance the edge's virtual clock",
                )
            },
            // The economics DO see it — the relocation is real, it is just not virtual time.
            {
                assertEquals(
                    302L,
                    withReloc.edge(EDGE)?.issued,
                    "…while the effective issuance the economics read does include it",
                )
            },
        )
    }

    /** A divergent record leaves no single weight to divide by, so the reads refuse. */
    @Test
    fun aDivergentRecordHasNoVirtualService() {
        val a = AttachmentRecord(EDGE, PARENT, CHILD, Weight.ONE)
        val b = AttachmentRecord(EDGE, PARENT, GroupId("other"), Weight.ONE)
        val s = EntitlementLedger.of(records = mapOf(EDGE to setOf(a, b)), gauges = mapOf(EDGE to Gauge(Rational.ONE, 0L)))
        assertAll(
            { assertNull(s.grossVirtualService(EDGE)) },
            { assertNull(s.virtualService(EDGE)) },
            { assertEquals(Gauge(Rational.ONE, 0L), s.gauge(EDGE), "the register itself is still readable") },
        )
    }

    // ── the accepted cost, measured: what actually bounds the exact floor ────────────

    /**
     * **The implementer trap the design flagged — measured, and the guess was wrong in a useful
     * direction.** The recorded concern was that "checkpoint denominators chain through the `lcm`
     * of weight denominators, so a **deep** or heterogeneous topology can approach the `Long`
     * ceiling". Depth is not the variable. This test is the measurement.
     *
     * A seat is seeded from the parent's front — a weighted mean over siblings — and then advanced
     * by checkpoints, so the arithmetic that could compound is `Σ w·ev / Σ w` followed by
     * `floor + units / w`. Every [Rational] operation reduces afterwards, and the reduced
     * denominator that combination settles on is a **fixed point of the weight set**: reached
     * within the first level or two, then unchanged however deep the topology goes. Chaining 200
     * levels leaves the denominator exactly where 2 levels did, and tiny — five orders of magnitude
     * below where a product of two such values could threaten `Long`.
     *
     * Note the settled value is *not* simply `lcm(weight numerators, weight denominators)`, which
     * is the shape the concern was phrased in: the mean divides by `Σ w`, and that sum's numerator
     * contributes prime factors belonging to no individual weight (measured: a set whose weight
     * `lcm` is `30030` settles at `9763 = 13 × 751`). The bound is still a function of the weight
     * set alone — just a coarser one than the `lcm`. Either way, depth is not a variable in it.
     *
     * What *does* reach the ceiling is pinned separately by
     * [anExtremeWeightRatioIsWhatReachesTheCeilingAndItThrows].
     */
    @Test
    fun topologyDepthDoesNotCompoundTheFloorDenominator() {
        // Coprime numerators AND assorted denominators — the maximally heterogeneous sibling set,
        // which is the case the concern was about.
        val weights = listOf(
            Weight.of(2L, 1L), Weight.of(3L, 1L), Weight.of(5L, 2L),
            Weight.of(7L, 3L), Weight.of(11L, 5L), Weight.of(13L, 1L),
        )
        val denominatorAtDepth = HashMap<Int, Long>()
        var floors = weights.map { Rational.ZERO }

        for (level in 1..DEEP_TOPOLOGY) {
            // The seat every child at this level is seeded from: its parent's front.
            val front = weightedMean(weights, floors)
            // Each child is then checkpointed once — one unit of service at its own weight.
            floors = weights.map { w -> front + Rational.of(w.denominator, w.numerator) }
            val widest = floors.maxOf { it.denominator }
            if (level in DEPTH_SAMPLES) denominatorAtDepth[level] = widest
            // Never a wrapped value on the way: the floor advances by positive quantities only.
            assertTrue(widest > 0L, "denominator wrapped negative at level $level")
        }

        val sampled = DEPTH_SAMPLES.map { denominatorAtDepth.getValue(it) }
        assertAll(
            {
                assertEquals(
                    1,
                    sampled.distinct().size,
                    "the reduced denominator must be identical at every sampled depth — depth does " +
                        "not compound it. Measured: $denominatorAtDepth",
                )
            },
            {
                // And it settles somewhere tiny — five orders of magnitude below the point where a
                // product of two such denominators could threaten Long. The margin, not just the
                // constancy, is what makes exactness affordable.
                assertTrue(
                    sampled.first() < DENOMINATOR_HEADROOM,
                    "the settled denominator ${sampled.first()} must stay far below the ceiling; " +
                        "measured across depths: $denominatorAtDepth",
                )
            },
        )
    }

    /**
     * What *does* reach the ceiling: not depth, but a **single extreme weight ratio**. Two
     * denominators sharing no factor multiply before [Rational] can reduce — `plus` computes
     * `checkedMul(denominator, other.denominator)` first — so the product of a floor's denominator
     * and a weight numerator coprime to it is the hard constraint. At `~3.04e9` each, that product
     * exceeds `Long.MAX_VALUE` in a single addition.
     *
     * Under [CheckedMath] that **throws**, which is the right failure and the reason this is an
     * accepted cost rather than a defect: a wrapped denominator would invert
     * [Rational.compareTo]'s cross-multiplication and silently reorder siblings cluster-wide,
     * whereas a throw is a loud, local, deterministic refusal that every replica makes alike.
     *
     * Reached through the [EntitlementLedger] read rather than bare arithmetic, so the guarantee is
     * pinned where consumers actually stand.
     */
    @Test
    fun anExtremeWeightRatioIsWhatReachesTheCeilingAndItThrows() {
        // Two primes just over sqrt(Long.MAX_VALUE): coprime, so nothing reduces, and their
        // product overflows.
        val overflowing = ledgerWith(
            weight = Weight.of(3_037_000_507L, 1L),
            issued = 1L,
            gauge = Gauge(Rational.of(1L, 3_037_000_493L), 0L),
        )
        assertFailsWith<ArithmeticException>("must throw, never wrap (§10.12)") {
            overflowing.grossVirtualService(EDGE)
        }

        // The neighbouring case that fits is exact — so the throw is a real ceiling and not an
        // over-eager guard.
        val fits = ledgerWith(
            weight = Weight.of(3_037_000_499L, 1L),
            issued = 1L,
            gauge = Gauge(Rational.of(1L, 3_037_000_493L), 0L),
        )
        assertEquals(
            Rational.of(3_037_000_499L + 3_037_000_493L, 3_037_000_493L * 3_037_000_499L),
            fits.grossVirtualService(EDGE),
            "just under the ceiling the read is still exact",
        )
    }

    /** `Σ w·ev / Σ w` — the front, as [HeddlePolicy] computes it, for the chaining measurement. */
    private fun weightedMean(weights: List<Weight>, values: List<Rational>): Rational {
        var weightedSum = Rational.ZERO
        var weightSum = Rational.ZERO
        for ((w, v) in weights.zip(values)) {
            val wr = Rational.of(w.numerator, w.denominator)
            weightedSum += wr * v
            weightSum += wr
        }
        return weightedSum / weightSum
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────

    private fun ledgerWith(
        weight: Weight,
        issued: Long = 0L,
        returned: Long = 0L,
        issuedRelocIn: Long = 0L,
        gauge: Gauge? = null,
    ): EntitlementLedger = EntitlementLedger.of(
        records = mapOf(EDGE to setOf(AttachmentRecord(EDGE, PARENT, CHILD, weight))),
        issued = counter(issued),
        returned = counter(returned),
        issuedRelocIn = counter(issuedRelocIn),
        gauges = gauge?.let { mapOf(EDGE to it) } ?: emptyMap(),
    )

    private fun counter(value: Long): Map<AttachmentId, GCounter> =
        if (value == 0L) emptyMap() else mapOf(EDGE to GCounter.of(REPLICA to value))

    private companion object {
        val EDGE = AttachmentId("e")
        val PARENT = GroupId("parent")
        val CHILD = GroupId("child")
        val REPLICA = ReplicaId("r")

        /** Deep enough that any depth-compounding would be unmistakable, short enough to be fast. */
        const val DEEP_TOPOLOGY = 200

        /** Depths the denominator is sampled at — every sample must agree. */
        val DEPTH_SAMPLES = listOf(2, 5, 20, 50, 100, 200)

        /**
         * The margin the settled denominator must keep. Two denominators multiply before
         * [Rational] reduces, so the hard ceiling sits near `sqrt(Long.MAX_VALUE)` ≈ `3.04e9`;
         * requiring five orders of magnitude below that is what makes exactness affordable
         * rather than merely correct.
         */
        const val DENOMINATOR_HEADROOM = 100_000L
    }
}
