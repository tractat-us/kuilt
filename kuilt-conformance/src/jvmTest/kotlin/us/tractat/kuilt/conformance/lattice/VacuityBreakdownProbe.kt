package us.tractat.kuilt.conformance.lattice

import us.tractat.kuilt.crdt.Quilted
import kotlin.math.round
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.fail

/**
 * The measurement surface behind every number in `VacuityFloors`' and `LatticeLawHarness`' KDoc —
 * off by default, run with `-Plattice.vacuity.breakdown=true`.
 *
 * [VacuityReport] answers *what* a generator searched. This answers *where the waste came from*,
 * which is the question #2145 and #2158 were argued over and which no shipped surface can express:
 * it splits a binding's no-op steps by the state they were taken from (the lattice bottom, or not)
 * and by [OpKind], and splits its retirements into the constructed ones and the explored ones.
 *
 * **It is a re-implementation of [LatticeLawHarness]' private pool builder, and it proves itself.**
 * Every arm that matches the shipped builder is cross-checked against that binding's own
 * [LatticeLawHarness.measureVacuity] — steps, no-ops, effective retires, pairs, ancestry and
 * concurrency, all six exact — and the probe fails rather than reports if any of them differs. That
 * is what lets it also model builders the harness is *not* running: [Bootstrap.NONE] reproduces the
 * pre-#2145 trajectory on today's sources, which is how this PR's before-table was measured without
 * checking the fix out again.
 *
 * **Gated because it is a measuring instrument, not a check.** It asserts nothing about any binding
 * and would tell CI nothing; what pins the behaviour it measures is the per-binding
 * [VacuityFloors.maxNoOpSteps] the retiring bindings now declare.
 *
 * ```
 * ./gradlew :kuilt-conformance:jvmTest --tests "*VacuityBreakdownProbe*" \
 *     -Plattice.vacuity.breakdown=true --rerun-tasks
 * ```
 */
internal class VacuityBreakdownProbe {

    private companion object {
        /** Mirrors `LatticeLawHarness.POOL_LIMIT`. */
        const val POOL_LIMIT = 14

        /** Mirrors `LatticeLawHarness.GOSSIP_ONE_IN`. */
        const val GOSSIP_ONE_IN = 4

        /** Which arm the shipped pool builder implements — the one the cross-check applies to. */
        val SHIPPED = Bootstrap.EVERY_REPLICA

        const val GATE = "lattice.vacuity.breakdown"
    }

    /** Which pool builder to model. */
    private enum class Bootstrap {
        /** Pre-#2145: the critical shape leads replica 0 and nothing leads the others. */
        NONE,

        /** Post-#2145: every replica takes one drawn asserting op — see `leadEveryReplicaWithAnAssert`. */
        EVERY_REPLICA,
    }

    /** Whether a binding's retiring ops are its own, or crippled the way #2158's receipt cripples them. */
    private enum class Retirement {
        AS_BOUND,

        /** #2158's title case: effective on replica 0 only, so retirement is dead on 2 of 3 replicas. */
        DEAD_OFF_REPLICA_ZERO,
    }

    private class Counts {
        var steps = 0
        var noOps = 0
        var noOpsFromBottom = 0
        var noOpsFromBottomRetire = 0
        var effectiveRetires = 0
        var leadSteps = 0
        var leadEffectiveRetires = 0
        var explorationSteps = 0
        var explorationNoOps = 0
        var explorationEffectiveRetires = 0
        var equalPairs = 0L
        var pairs = 0L
        var strictAncestor = 0L
        var concurrent = 0L

        val noOpRate: Double get() = rate(noOps, steps)
        val explorationNoOpRate: Double get() = rate(explorationNoOps, explorationSteps)
        val retireRate: Double get() = rate(effectiveRetires, steps)
        val explorationRetireRate: Double get() = rate(explorationEffectiveRetires, steps)

        private fun rate(n: Int, d: Int) = if (d == 0) 0.0 else n.toDouble() / d
    }

    private fun <S> pick(alphabet: List<LatticeOp<S>>, random: Random): LatticeOp<S> =
        if (alphabet.size == 1) alphabet[0] else alphabet[random.nextInt(alphabet.size)]

    @Suppress("CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth")
    private fun <S : Quilted<S>> breakdown(h: LatticeLawHarness<S>, seeds: LongRange, bootstrap: Bootstrap): Counts {
        val c = Counts()
        for (seed in seeds) {
            val random = Random(seed)
            val latest = MutableList(h.replicaCount) { h.initial }
            val words = MutableList(h.replicaCount) { 0 }
            val pool = mutableListOf(h.initial)

            fun record(lead: Boolean, kind: OpKind, changed: Boolean, fromBottom: Boolean) {
                c.steps++
                if (lead) c.leadSteps++ else c.explorationSteps++
                if (!changed) {
                    c.noOps++
                    if (!lead) c.explorationNoOps++
                    if (fromBottom) {
                        c.noOpsFromBottom++
                        if (kind == OpKind.RETIRE) c.noOpsFromBottomRetire++
                    }
                }
                if (kind == OpKind.RETIRE && changed) {
                    c.effectiveRetires++
                    if (lead) c.leadEffectiveRetires++ else c.explorationEffectiveRetires++
                }
            }

            for (shape in h.criticalShapes) {
                for (opName in shape) {
                    val op = h.alphabet.first { it.name == opName }
                    val before = latest[0]
                    val after = op.apply(before, 0, random)
                    latest[0] = after
                    words[0]++
                    record(lead = true, kind = op.kind, changed = after != before, fromBottom = before == h.initial)
                    pool += after
                }
            }
            if (bootstrap == Bootstrap.EVERY_REPLICA && h.alphabet.any { it.kind == OpKind.RETIRE }) {
                val asserts = h.alphabet.filter { it.kind == OpKind.ASSERT }
                if (asserts.isNotEmpty()) {
                    for (r in 0 until h.replicaCount) {
                        if (words[r] != 0) continue
                        val op = if (asserts.size == 1) asserts[0] else asserts[random.nextInt(asserts.size)]
                        val before = latest[r]
                        val after = op.apply(before, r, random)
                        latest[r] = after
                        words[r]++
                        record(lead = true, kind = op.kind, changed = after != before, fromBottom = true)
                        pool += after
                    }
                }
            }
            outer@ for (op in 0 until h.opsPerReplica) {
                for (r in 0 until h.replicaCount) {
                    if (random.nextInt(GOSSIP_ONE_IN) == 0) {
                        val peer = random.nextInt(h.replicaCount)
                        latest[r] = latest[r].piece(latest[peer])
                        pool += latest[r]
                    }
                    val chosen = pick(h.alphabet, random)
                    val before = latest[r]
                    latest[r] = chosen.apply(before, r, random)
                    record(
                        lead = false,
                        kind = chosen.kind,
                        changed = latest[r] != before,
                        fromBottom = before == h.initial,
                    )
                    pool += latest[r]
                    if (pool.size >= POOL_LIMIT) break@outer
                }
            }
            for (i in pool.indices) {
                for (j in pool.indices) {
                    if (i == j) continue
                    c.pairs++
                    val a = pool[i]
                    val b = pool[j]
                    val aBelowB = a.piece(b) == b
                    val bBelowA = b.piece(a) == a
                    if (aBelowB && !bBelowA) c.strictAncestor++
                    if (!aBelowB && !bBelowA) c.concurrent++
                    if (a == b) c.equalPairs++
                }
            }
        }
        return c
    }

    /**
     * [harness] with every [OpKind.RETIRE] op effective on replica 0 only — #2158's receipt,
     * generalised off `ORSet` so it can be run against any binding.
     *
     * The critical shape is untouched by construction: shapes run on replica 0, so a shape's retiring
     * step still lands and the harness's own no-op check on it still passes.
     */
    private fun <S : Quilted<S>> crippled(harness: LatticeLawHarness<S>): LatticeLawHarness<S> =
        LatticeLawHarness(
            initial = harness.initial,
            alphabet = harness.alphabet.map { op ->
                if (op.kind != OpKind.RETIRE) {
                    op
                } else {
                    LatticeOp(op.name, op.kind) { state, replicaIndex, random ->
                        if (replicaIndex == 0) op.apply(state, replicaIndex, random) else state
                    }
                }
            },
            serializer = harness.serializer,
            criticalShapes = harness.criticalShapes,
            floors = harness.floors,
            replicaCount = harness.replicaCount,
            opsPerReplica = harness.opsPerReplica,
        )

    private fun pct(rate: Double): String = "${round(rate * 1000.0) / 10.0}"

    private fun bindings(): List<Pair<String, LatticeLawSuite<*>>> = listOf(
        "BoundedCounter" to BoundedCounterConvergenceTest(),
        "CausalDotMap" to CausalDotMapConvergenceTest(),
        "CausalDotSet" to CausalDotSetConvergenceTest(),
        "DotContext" to DotContextConvergenceTest(),
        "EphemeralMap" to EphemeralMapConvergenceTest(),
        "Fugue" to FugueConvergenceTest(),
        "GCounter" to GCounterConvergenceTest(),
        "GSet" to GSetConvergenceTest(),
        "IntMax" to IntMaxConvergenceTest(),
        "JsonCrdt" to JsonCrdtConvergenceTest(),
        "LWWMap" to LWWMapConvergenceTest(),
        "LWWRegister" to LWWRegisterConvergenceTest(),
        "MVRegister" to MVRegisterConvergenceTest(),
        "MovableTree" to MovableTreeConvergenceTest(),
        "ORMap" to ORMapConvergenceTest(),
        "ORSet" to ORSetConvergenceTest(),
        "PNCounter" to PNCounterConvergenceTest(),
        "Rga" to RgaConvergenceTest(),
        "TwoPhaseSet" to TwoPhaseSetConvergenceTest(),
    )

    @Suppress("UNCHECKED_CAST", "LongParameterList")
    private fun <S : Quilted<S>> emit(
        name: String,
        suite: LatticeLawSuite<*>,
        seeds: LongRange,
        bootstrap: Bootstrap,
        retirement: Retirement,
        mismatches: MutableList<String>,
    ) {
        val real = (suite as LatticeLawSuite<S>).newHarness()
        val h = if (retirement == Retirement.AS_BOUND) real else crippled(real)
        val c = breakdown(h, seeds, bootstrap)
        if (bootstrap == SHIPPED) {
            val official = h.measureVacuity(seeds)
            val same = c.steps == official.steps &&
                c.noOps == official.noOpSteps &&
                c.effectiveRetires == official.effectiveRetireSteps &&
                c.pairs == official.pairs &&
                c.strictAncestor == official.strictAncestorPairs &&
                c.concurrent == official.concurrentPairs
            if (!same) {
                mismatches += "$name/$retirement: this probe models a pool builder the harness is " +
                    "not running — probe(steps=${c.steps}, noOp=${c.noOps}, " +
                    "retire=${c.effectiveRetires}, pairs=${c.pairs}) vs harness(steps=${official.steps}, " +
                    "noOp=${official.noOpSteps}, retire=${official.effectiveRetireSteps}, " +
                    "pairs=${official.pairs})"
            }
        }
        val window = "s${seeds.first}-${seeds.last}"
        println(
            "ROW|$window|$bootstrap|$retirement|$name|${c.noOps}|${c.steps}|${pct(c.noOpRate)}|" +
                "${c.noOpsFromBottom}|${c.noOpsFromBottomRetire}|" +
                "${c.explorationNoOps}|${c.explorationSteps}|${pct(c.explorationNoOpRate)}|" +
                "${c.effectiveRetires}|${pct(c.retireRate)}|${c.leadEffectiveRetires}|" +
                "${c.explorationEffectiveRetires}|${pct(c.explorationRetireRate)}|" +
                "${pct(c.strictAncestor.toDouble() / c.pairs)}|${pct(c.concurrent.toDouble() / c.pairs)}|" +
                "${c.pairs}|${pct(c.equalPairs.toDouble() / c.pairs)}",
        )
    }

    @Test
    fun breakdown() {
        if (System.getProperty(GATE) != "true") {
            println("VacuityBreakdownProbe skipped — run with -P$GATE=true")
            return
        }
        val mismatches = mutableListOf<String>()
        println(
            "ROW|window|bootstrap|retirement|binding|noOp|steps|noOp%|fromBottom|fromBottomRETIRE|" +
                "explNoOp|explSteps|explNoOp%|effRetire|retire%|leadRetire|explRetire|explRetire%|" +
                "anc%|conc%|pairs|equal%",
        )
        for (seeds in listOf(0L..15L, 0L..63L)) {
            for (bootstrap in Bootstrap.entries) {
                for (retirement in Retirement.entries) {
                    for ((name, suite) in bindings()) {
                        emit<Nothing>(name, suite, seeds, bootstrap, retirement, mismatches)
                    }
                }
            }
        }
        if (mismatches.isNotEmpty()) fail("probe diverged from the harness:\n${mismatches.joinToString("\n")}")
    }
}
