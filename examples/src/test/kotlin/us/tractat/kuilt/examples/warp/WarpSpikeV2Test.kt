/**
 * Warp spike v2 tests — SPECULATIVE / EXPERIMENTAL.
 *
 * Head-to-head comparison of three claim strategies under realistic gossip propagation:
 *   A. Optimistic-dedup   — zero pre-claim coordination, dedup via Results ORMap.
 *   B. Intent-register    — one extra gossip round per claim, LWWMap<TaskId,PeerId>.
 *   C. Consensus-model    — zero dups, quorum round-trip modelled per task.
 *
 * Every run ends with a hard convergence assertion: all peers' Results agree and no
 * task is lost. The assertion is in [runWarpComparisonV2] — it fires before the result
 * is returned, so a failing assertion fails the test, not just a soft bound.
 *
 * All runs are seeded and deterministic. No coroutines — pure CRDT state machine.
 */
package us.tractat.kuilt.examples.warp

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

class WarpSpikeV2Test {

    private val seed = 680L

    // ---------------------------------------------------------------------------
    // Core tradeoff sweep — the headline result
    // ---------------------------------------------------------------------------

    @Test
    fun `v2 headline — peer count sweep with realistic gossip (fanout=3, 2-hop)`() {
        val gossip = GossipConfig(fanout = 3, propagationHops = 2)
        val results = listOf(2, 4, 8, 16).map { peers ->
            runWarpComparisonV2(
                taskCount = 40,
                peerCount = peers,
                rounds = 30,
                gossipConfig = gossip,
                rng = Random(seed + peers),
            )
        }

        printComparisonTable("v2 Headline — peer-count sweep (fanout=3, 2-hop, no loss)", results)
        printTradeoffStatement(results)

        // Convergence is asserted inside the simulation — if we got here, it passed.
        // Also verify: intent-register should have fewer dups than optimistic in at least some configs.
        val intentBetterThanOptimistic = results.count {
            it.intentRegister.duplicateRate < it.optimistic.duplicateRate
        }
        assertTrue(
            intentBetterThanOptimistic >= 1,
            "Expected intent-register to reduce dups vs optimistic in at least 1 config",
        )

        // Consensus should always be zero dups.
        results.forEach { r ->
            assertTrue(
                r.consensus.duplicateRate == 0.0,
                "Consensus model must have 0% dup rate, got ${r.consensus.duplicateRate}",
            )
        }
    }

    @Test
    fun `v2 gossip quality sweep — effect of fanout and propagation hops on dup rate`() {
        data class GossipVariant(val label: String, val config: GossipConfig)
        val variants = listOf(
            GossipVariant("fanout=2,hops=1", GossipConfig(fanout = 2, propagationHops = 1)),
            GossipVariant("fanout=3,hops=1", GossipConfig(fanout = 3, propagationHops = 1)),
            GossipVariant("fanout=3,hops=2", GossipConfig(fanout = 3, propagationHops = 2)),
            GossipVariant("fanout=5,hops=3", GossipConfig(fanout = 5, propagationHops = 3)),
        )

        println("\n=== v2 Gossip-Quality Sweep (4 peers, 40 tasks, 30 rounds) ===")
        println(
            "%-20s %-12s %-12s %-12s %-14s %-14s %-14s".format(
                "gossip", "opt-dup%", "intent-dup%", "cons-dup%",
                "opt-coord/task", "intent-coord/task", "cons-coord/task",
            ),
        )
        println("-".repeat(100))

        variants.forEach { (label, cfg) ->
            val r = runWarpComparisonV2(
                taskCount = 40,
                peerCount = 4,
                rounds = 30,
                gossipConfig = cfg,
                rng = Random(seed),
            )
            println(
                "%-20s %-12s %-12s %-12s %-14s %-14s %-14s".format(
                    label,
                    pct(r.optimistic.duplicateRate),
                    pct(r.intentRegister.duplicateRate),
                    pct(r.consensus.duplicateRate),
                    "%.2f".format(r.optimistic.coordMessagesPerTask),
                    "%.2f".format(r.intentRegister.coordMessagesPerTask),
                    "%.2f".format(r.consensus.coordMessagesPerTask),
                ),
            )
        }

        // Convergence passes if we reach here (assertConvergence throws on failure).
    }

    @Test
    fun `v2 loss and partition sweep — degraded network`() {
        data class NetworkVariant(val label: String, val loss: Double, val partition: Double)
        val variants = listOf(
            NetworkVariant("loss=0,part=0", 0.0, 0.0),
            NetworkVariant("loss=25%,part=0", 0.25, 0.0),
            NetworkVariant("loss=50%,part=0", 0.50, 0.0),
            NetworkVariant("loss=0,part=20%", 0.0, 0.20),
            NetworkVariant("loss=25%,part=20%", 0.25, 0.20),
        )

        println("\n=== v2 Network-Degradation Sweep (4 peers, fanout=3, 2-hop) ===")
        println(
            "%-24s %-12s %-12s %-14s %-14s".format(
                "network", "opt-dup%", "intent-dup%", "opt-coord/task", "intent-coord/task",
            ),
        )
        println("-".repeat(82))

        variants.forEach { (label, loss, partition) ->
            val r = runWarpComparisonV2(
                taskCount = 40,
                peerCount = 4,
                rounds = 35,
                gossipConfig = GossipConfig(fanout = 3, propagationHops = 2, partitionRate = partition, messageLossRate = loss),
                rng = Random(seed),
            )
            println(
                "%-24s %-12s %-12s %-14s %-14s".format(
                    label,
                    pct(r.optimistic.duplicateRate),
                    pct(r.intentRegister.duplicateRate),
                    "%.2f".format(r.optimistic.coordMessagesPerTask),
                    "%.2f".format(r.intentRegister.coordMessagesPerTask),
                ),
            )
        }
    }

    @Test
    fun `v2 comprehensive sweep — headline table for docs`() {
        data class Config(val peers: Int, val loss: Double, val partition: Double, val fanout: Int, val hops: Int)

        val configs = listOf(
            Config(2, 0.0, 0.0, 3, 2),
            Config(4, 0.0, 0.0, 3, 2),
            Config(8, 0.0, 0.0, 3, 2),
            Config(16, 0.0, 0.0, 3, 2),
            Config(4, 0.25, 0.0, 3, 2),
            Config(4, 0.50, 0.0, 3, 2),
            Config(4, 0.0, 0.20, 3, 2),
            Config(8, 0.25, 0.20, 3, 2),
            Config(4, 0.0, 0.0, 2, 1),   // low-quality gossip
            Config(4, 0.0, 0.0, 5, 3),   // high-quality gossip
        )

        println("\n=== WARP SPIKE v2 — COMPREHENSIVE COMPARISON TABLE ===")
        println("SPECULATIVE/EXPERIMENTAL | 40 tasks, 30 rounds, seed=${seed}L")
        println()
        println(
            "%-4s %-6s %-6s %-6s %-3s | OPT dup%%  IR dup%%  CONS dup%% | OPT msg/t  IR msg/t  CONS msg/t".format(
                "prs", "loss", "part", "fanout", "hops",
            ),
        )
        println("-".repeat(100))

        val allResults = configs.map { (peers, loss, partition, fanout, hops) ->
            val r = runWarpComparisonV2(
                taskCount = 40,
                peerCount = peers,
                rounds = 30,
                gossipConfig = GossipConfig(fanout, hops, partition, loss),
                rng = Random(seed + peers),
            )
            val row = "%-4d %-6s %-6s %-6d %-3d | %-9s %-9s %-9s | %-10s %-10s %-10s".format(
                peers,
                pct(loss),
                pct(partition),
                fanout,
                hops,
                pct(r.optimistic.duplicateRate),
                pct(r.intentRegister.duplicateRate),
                pct(r.consensus.duplicateRate),
                "%.1f".format(r.optimistic.coordMessagesPerTask),
                "%.1f".format(r.intentRegister.coordMessagesPerTask),
                "%.1f".format(r.consensus.coordMessagesPerTask),
            )
            println(row)
            r
        }

        println()
        println("=== TRADEOFF STATEMENT ===")
        printTradeoffStatement(allResults)

        // Hard assertions — convergence already asserted inside simulation.
        // Consensus model always zero dups.
        allResults.forEach { assertTrue(it.consensus.duplicateRate == 0.0) }
        // Intent-register always costs MORE coordination messages than optimistic (which is zero).
        allResults.forEach {
            assertTrue(
                it.intentRegister.coordMessagesPerTask >= it.optimistic.coordMessagesPerTask,
                "Intent-register must have >= coord cost vs optimistic at ${it.peerCount} peers",
            )
        }
        // Intent-register always has fewer or equal dups than optimistic (intent helps).
        allResults.forEach {
            assertTrue(
                it.intentRegister.duplicateRate <= it.optimistic.duplicateRate,
                "Intent-register dup-rate (${it.intentRegister.duplicateRate}) must be <= optimistic " +
                    "(${it.optimistic.duplicateRate}) at ${it.peerCount} peers",
            )
        }
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun printComparisonTable(header: String, results: List<V2ComparisonResult>) {
        println("\n--- $header ---")
        println(
            "%-4s | %-12s %-12s %-12s | %-14s %-14s %-14s".format(
                "prs",
                "opt-dup%", "IR-dup%", "cons-dup%",
                "opt-coord/task", "IR-coord/task", "cons-coord/task",
            ),
        )
        println("-".repeat(90))
        for (r in results) {
            println(
                "%-4d | %-12s %-12s %-12s | %-14s %-14s %-14s".format(
                    r.peerCount,
                    pct(r.optimistic.duplicateRate),
                    pct(r.intentRegister.duplicateRate),
                    pct(r.consensus.duplicateRate),
                    "%.2f".format(r.optimistic.coordMessagesPerTask),
                    "%.2f".format(r.intentRegister.coordMessagesPerTask),
                    "%.2f".format(r.consensus.coordMessagesPerTask),
                ),
            )
        }
        println()
    }

    private fun printTradeoffStatement(results: List<V2ComparisonResult>) {
        val typicalResult = results.firstOrNull { it.peerCount == 4 } ?: results.firstOrNull() ?: return
        val opt = typicalResult.optimistic
        val ir = typicalResult.intentRegister
        val cons = typicalResult.consensus

        val dupReduction = opt.duplicateRate - ir.duplicateRate
        val irVsOptCoordCost = ir.coordMessagesPerTask - opt.coordMessagesPerTask
        println(
            "HEADLINE (${typicalResult.peerCount} peers, fanout=${typicalResult.gossipConfig.fanout}):\n" +
                "  Optimistic-dedup:  ${pct(opt.duplicateRate)} dups, ${opt.coordMessagesPerTask.let { "%.1f".format(it) }} coord-msgs/task\n" +
                "  Intent-register:   ${pct(ir.duplicateRate)} dups (${pct(dupReduction)} fewer), " +
                "${ir.coordMessagesPerTask.let { "%.1f".format(it) }} coord-msgs/task " +
                "(+${"%.1f".format(irVsOptCoordCost)}/task overhead)\n" +
                "  Consensus-model:   ${pct(cons.duplicateRate)} dups, ${cons.coordMessagesPerTask.let { "%.1f".format(it) }} coord-msgs/task",
        )
        println()
    }

    private fun pct(d: Double): String = "%.1f%%".format(d * 100)
}
