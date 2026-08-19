package us.tractat.kuilt.conformance.lattice

import us.tractat.kuilt.crdt.Quilted
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.fail

/**
 * THROWAWAY probe for #2145 / #2158. Deleted before this branch is marked ready.
 *
 * Replicates `LatticeLawHarness.causalPool` step-for-step from the harness's PUBLIC surface, so the
 * "before" table can be measured against unmodified sources and the "after" table previewed before
 * the harness changes at all. Faithfulness is not assumed — the arm that matches the shipped harness
 * is cross-checked against its own `measureVacuity`, and the probe fails loudly on any mismatch.
 */
internal class Probe2145 {

    private companion object {
        const val POOL_LIMIT = 14
        const val GOSSIP_ONE_IN = 4

        /** Which arm the shipped harness currently implements — flip once the fix lands. */
        val SHIPPED = Bootstrap.NONE
    }

    private enum class Bootstrap { NONE, FIRST_ASSERT, RANDOM_ASSERT }

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
    }

    private fun <S> pick(alphabet: List<LatticeOp<S>>, random: Random): LatticeOp<S> =
        if (alphabet.size == 1) alphabet[0] else alphabet[random.nextInt(alphabet.size)]

    @Suppress("CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth")
    private fun <S : Quilted<S>> breakdown(h: LatticeLawHarness<S>, seeds: LongRange, bootstrap: Bootstrap): Counts {
        val c = Counts()
        for (seed in seeds) {
            val random = Random(seed)
            val latest = MutableList(h.replicaCount) { h.initial }
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
                    record(lead = true, kind = op.kind, changed = true, fromBottom = before == h.initial)
                    pool += after
                }
            }
            if (bootstrap != Bootstrap.NONE) {
                val asserts = h.alphabet.filter { it.kind == OpKind.ASSERT }
                if (asserts.isNotEmpty()) {
                    for (r in 0 until h.replicaCount) {
                        if (latest[r] != h.initial) continue
                        val op = when {
                            asserts.size == 1 -> asserts[0]
                            bootstrap == Bootstrap.FIRST_ASSERT -> asserts[0]
                            else -> asserts[random.nextInt(asserts.size)]
                        }
                        val before = latest[r]
                        val after = op.apply(before, r, random)
                        latest[r] = after
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

    private fun pct(n: Int, d: Int): String =
        if (d == 0) "n/a" else (kotlin.math.round(n * 1000.0 / d) / 10.0).toString()

    private fun pctL(n: Long, d: Long): String =
        if (d == 0L) "n/a" else (kotlin.math.round(n * 1000.0 / d) / 10.0).toString()

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
    private fun <S : Quilted<S>> run(
        tag: String,
        name: String,
        suite: LatticeLawSuite<*>,
        seeds: LongRange,
        bootstrap: Bootstrap,
        mismatches: MutableList<String>,
    ) {
        val h = (suite as LatticeLawSuite<S>).newHarness()
        val c = breakdown(h, seeds, bootstrap)
        if (bootstrap == SHIPPED) {
            val official = h.measureVacuity(seeds)
            if (
                c.steps != official.steps ||
                c.noOps != official.noOpSteps ||
                c.effectiveRetires != official.effectiveRetireSteps ||
                c.pairs != official.pairs ||
                c.strictAncestor != official.strictAncestorPairs ||
                c.concurrent != official.concurrentPairs
            ) {
                mismatches += "$name: probe(steps=${c.steps},noOp=${c.noOps},retire=${c.effectiveRetires}," +
                    "pairs=${c.pairs},anc=${c.strictAncestor},conc=${c.concurrent}) != " +
                    "harness(steps=${official.steps},noOp=${official.noOpSteps}," +
                    "retire=${official.effectiveRetireSteps},pairs=${official.pairs}," +
                    "anc=${official.strictAncestorPairs},conc=${official.concurrentPairs})"
            }
        }
        println(
            "ROW|$tag|$name|${c.noOps}|${c.steps}|${pct(c.noOps, c.steps)}|" +
                "${c.noOpsFromBottom}|${c.noOpsFromBottomRetire}|" +
                "${c.explorationNoOps}|${c.explorationSteps}|${pct(c.explorationNoOps, c.explorationSteps)}|" +
                "${c.effectiveRetires}|${pct(c.effectiveRetires, c.steps)}|" +
                "${c.leadEffectiveRetires}|${c.explorationEffectiveRetires}|" +
                "${pct(c.explorationEffectiveRetires, c.steps)}|" +
                "${pctL(c.strictAncestor, c.pairs)}|${pctL(c.concurrent, c.pairs)}|" +
                "${pctL(c.equalPairs, c.pairs)}",
        )
    }

    @Test
    fun table() {
        val mismatches = mutableListOf<String>()
        println(
            "ROW|arm|binding|noOp|steps|noOp%|fromBottom|fromBottomRETIRE|explNoOp|explSteps|explNoOp%|" +
                "effRetire|retire%|leadRetire|explRetire|explRetire%|anc%|conc%|equal%",
        )
        for (seeds in listOf(0L..15L, 0L..63L)) {
            val window = if (seeds.last == 15L) "s0-15" else "s0-63"
            for (bootstrap in Bootstrap.entries) {
                for ((name, suite) in bindings()) {
                    run<Nothing>("$window/$bootstrap", name, suite, seeds, bootstrap, mismatches)
                }
            }
        }
        if (mismatches.isNotEmpty()) fail("probe diverged from the harness:\n${mismatches.joinToString("\n")}")
    }
}
