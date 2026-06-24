/**
 * Warp spike D tests — consistent-hashing assignment under membership churn.
 * SPECULATIVE / EXPERIMENTAL.
 *
 * Primary sweep: churn rate (join/leave/partition %) at 4 and 8 peers.
 * Strategy D (gossip-roster ring + strong-membership ring) vs OPT/IR/CONS baselines.
 *
 * All runs are seeded and deterministic. Hard convergence assertion in every run.
 */
package us.tractat.kuilt.examples.warp

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

class WarpSpikeDChurnTest {

    private val seed = 793L

    // ---------------------------------------------------------------------------
    // Primary sweep: churn rate — the headline result
    // ---------------------------------------------------------------------------

    @Test
    fun `D churn sweep at 4 peers — headline`() {
        val results = churnSweep(peerCount = 4)
        printChurnTable("Strategy D — 4 peers, churn sweep (fanout=3, 2-hop)", results)
        assertDConvergenceInvariants(results)
    }

    @Test
    fun `D churn sweep at 8 peers`() {
        val results = churnSweep(peerCount = 8)
        printChurnTable("Strategy D — 8 peers, churn sweep (fanout=3, 2-hop)", results)
        assertDConvergenceInvariants(results)
    }

    @Test
    fun `D vs OPT-IR-CONS at zero churn — apples-to-apples baseline`() {
        val gossip = GossipConfig(fanout = 3, propagationHops = 2)
        val peerCounts = listOf(2, 4, 8)
        val allResults = peerCounts.map { peers ->
            runStrategyDWithBaseline(
                taskCount = 40,
                peerCount = peers,
                rounds = 30,
                churnConfig = ChurnConfig(vnodeCount = 64),
                gossipConfig = gossip,
                rng = Random(seed + peers),
            )
        }

        println("\n=== WARP SPIKE D vs v2 BASELINE — zero-churn (fanout=3, 2-hop, 40 tasks, 30 rounds) ===")
        println("SPECULATIVE/EXPERIMENTAL | seed=${seed}L")
        println()
        println(
            "%-4s | %-9s %-9s | %-9s %-9s %-9s | %-12s %-12s %-12s".format(
                "prs",
                "D-G dup%", "D-S dup%",
                "OPT dup%", "IR dup%", "CONS dup%",
                "D-S msg/t", "IR msg/t", "CONS msg/t",
            ),
        )
        println("-".repeat(105))

        allResults.forEach { r ->
            println(
                "%-4d | %-9s %-9s | %-9s %-9s %-9s | %-12s %-12s %-12s".format(
                    r.d.peerCount,
                    pct(r.d.gossip.duplicateRate),
                    pct(r.d.strong.duplicateRate),
                    pct(r.v2.optimistic.duplicateRate),
                    pct(r.v2.intentRegister.duplicateRate),
                    pct(r.v2.consensus.duplicateRate),
                    "%.1f".format(r.d.strong.coordMessagesPerTask),
                    "%.1f".format(r.v2.intentRegister.coordMessagesPerTask),
                    "%.1f".format(r.v2.consensus.coordMessagesPerTask),
                ),
            )
        }
        println()
        println("D-G = gossip-roster ring (eventual), D-S = strong-membership ring (agreed)")
        println("D-S coord cost = membership changes × 2×quorum, amortised over tasks in epoch")
        println("At zero churn: D-G dup-rate should approximate OPT (same local info), D-S ≈ CONS.")

        // At zero churn, D-STRONG should produce zero dups (same agreed ring for everyone).
        allResults.forEach { r ->
            assertTrue(
                r.d.strong.duplicateRate == 0.0,
                "D-STRONG with zero churn must have 0% dup rate at ${r.d.peerCount} peers, " +
                    "got ${pct(r.d.strong.duplicateRate)}",
            )
        }
    }

    @Test
    fun `D failover window — partition sweep at 4 peers`() {
        // Shows the duplicate cost of the failover ambiguity window under partition-only churn.
        val gossip = GossipConfig(fanout = 3, propagationHops = 2)
        val partitionRates = listOf(0, 5, 10, 20, 40)

        println("\n=== WARP SPIKE D — Failover/Partition sweep (4 peers, join=0, leave=0) ===")
        println("SPECULATIVE/EXPERIMENTAL | 40 tasks, 30 rounds, seed=${seed}L, fanout=3, 2-hop")
        println()
        println(
            "%-12s | %-10s %-10s | %-12s".format(
                "partition%", "D-G dup%", "D-S dup%", "D-S msg/t",
            ),
        )
        println("-".repeat(52))

        partitionRates.forEach { rate ->
            val result = runStrategyD(
                taskCount = 40,
                peerCount = 4,
                rounds = 30,
                churnConfig = ChurnConfig(partitionRatePercent = rate, vnodeCount = 64),
                gossipConfig = gossip,
                rng = Random(seed),
            )
            println(
                "%-12s | %-10s %-10s | %-12s".format(
                    "$rate%",
                    pct(result.gossip.duplicateRate),
                    pct(result.strong.duplicateRate),
                    "%.1f".format(result.strong.coordMessagesPerTask),
                ),
            )
        }
    }

    @Test
    fun `D comprehensive churn sweep — full table for docs`() {
        val gossip = GossipConfig(fanout = 3, propagationHops = 2)

        // churn-rate configs: total churn = join% + leave% + partition% combined
        data class ChurnVariant(val label: String, val config: ChurnConfig)
        val churnVariants = listOf(
            ChurnVariant("0%", ChurnConfig(0, 0, 0, 64)),
            ChurnVariant("1%", ChurnConfig(1, 1, 0, 64)),
            ChurnVariant("5%", ChurnConfig(3, 2, 1, 64)),
            ChurnVariant("10%", ChurnConfig(5, 3, 2, 64)),
            ChurnVariant("20%", ChurnConfig(10, 5, 5, 64)),
        )

        println("\n=== WARP SPIKE D — COMPREHENSIVE CHURN SWEEP ===")
        println("SPECULATIVE/EXPERIMENTAL | 40 tasks, 30 rounds, seed=${seed}L, fanout=3, 2-hop")
        println()
        println(
            "%-6s %-4s | %-9s %-9s %-9s %-9s %-9s | %-10s %-10s %-10s".format(
                "churn", "prs",
                "D-G dup%", "D-S dup%", "OPT dup%", "IR dup%", "CONS dup%",
                "D-S msg/t", "IR msg/t", "CONS msg/t",
            ),
        )
        println("-".repeat(108))

        val allRows = mutableListOf<DWithBaselineResult>()
        for (peerCount in listOf(4, 8)) {
            for (variant in churnVariants) {
                val result = runStrategyDWithBaseline(
                    taskCount = 40,
                    peerCount = peerCount,
                    rounds = 30,
                    churnConfig = variant.config,
                    gossipConfig = gossip,
                    rng = Random(seed + peerCount),
                )
                allRows.add(result)
                println(
                    "%-6s %-4d | %-9s %-9s %-9s %-9s %-9s | %-10s %-10s %-10s".format(
                        variant.label,
                        peerCount,
                        pct(result.d.gossip.duplicateRate),
                        pct(result.d.strong.duplicateRate),
                        pct(result.v2.optimistic.duplicateRate),
                        pct(result.v2.intentRegister.duplicateRate),
                        pct(result.v2.consensus.duplicateRate),
                        "%.1f".format(result.d.strong.coordMessagesPerTask),
                        "%.1f".format(result.v2.intentRegister.coordMessagesPerTask),
                        "%.1f".format(result.v2.consensus.coordMessagesPerTask),
                    ),
                )
            }
        }
        println()
        println("D-G = gossip-roster ring | D-S = strong-membership ring")
        println("D-S coord-cost is membership-change amortised — rises with churn rate, not task count")
        println()
        printCrossoverVerdict(allRows)

        // Hard assertions.
        // D-STRONG at 0% churn should be zero dups for both peer counts.
        val zeroChurnRows = allRows.filter { it.d.churnConfig.joinRatePercent == 0 && it.d.churnConfig.leaveRatePercent == 0 && it.d.churnConfig.partitionRatePercent == 0 }
        zeroChurnRows.forEach { r ->
            assertTrue(
                r.d.strong.duplicateRate == 0.0,
                "D-STRONG zero-churn must have 0 dups at ${r.d.peerCount} peers",
            )
        }
        // Duplicate-rate is live-peer-only, so always in [0, 1].
        allRows.forEach { r ->
            assertTrue(r.d.gossip.duplicateRate in 0.0..1.0)
            assertTrue(r.d.strong.duplicateRate in 0.0..1.0)
        }
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun churnSweep(peerCount: Int): List<StrategyDResult> {
        val gossip = GossipConfig(fanout = 3, propagationHops = 2)
        return listOf(
            ChurnConfig(0, 0, 0, 64),
            ChurnConfig(1, 1, 0, 64),
            ChurnConfig(3, 2, 1, 64),
            ChurnConfig(5, 3, 2, 64),
            ChurnConfig(10, 5, 5, 64),
        ).map { churn ->
            runStrategyD(
                taskCount = 40,
                peerCount = peerCount,
                rounds = 30,
                churnConfig = churn,
                gossipConfig = gossip,
                rng = Random(seed + peerCount),
            )
        }
    }

    private fun printChurnTable(header: String, results: List<StrategyDResult>) {
        println("\n--- $header ---")
        println("%-22s | %-10s %-10s | %-12s %-10s".format(
            "churn (join/leave/part%)", "D-G dup%", "D-S dup%", "D-S msg/t", "tasks-lost",
        ))
        println("-".repeat(75))
        results.forEach { r ->
            val c = r.churnConfig
            val label = "${c.joinRatePercent}/${c.leaveRatePercent}/${c.partitionRatePercent}"
            println(
                "%-22s | %-10s %-10s | %-12s %-10d".format(
                    label,
                    pct(r.gossip.duplicateRate),
                    pct(r.strong.duplicateRate),
                    "%.1f".format(r.strong.coordMessagesPerTask),
                    r.gossip.tasksLost,
                ),
            )
        }
        println()
    }

    private fun assertDConvergenceInvariants(results: List<StrategyDResult>) {
        // Convergence is asserted inside the simulation — if we got here, it passed.
        // Duplicate-rate is computed over live-peer executions only, so it is always in [0, 1].
        results.forEach { r ->
            assertTrue(r.gossip.duplicateRate in 0.0..1.0, "D-GOSSIP dup-rate out of range: ${r.gossip.duplicateRate}")
            assertTrue(r.strong.duplicateRate in 0.0..1.0, "D-STRONG dup-rate out of range: ${r.strong.duplicateRate}")
            // Tasks-lost should be non-negative and <= total tasks.
            assertTrue(r.gossip.tasksLost in 0..r.gossip.totalTasks)
        }
        // At zero churn, D-STRONG must be zero dups.
        val zeroChurn = results.firstOrNull { r ->
            r.churnConfig.joinRatePercent == 0 && r.churnConfig.leaveRatePercent == 0 && r.churnConfig.partitionRatePercent == 0
        }
        if (zeroChurn != null) {
            assertTrue(
                zeroChurn.strong.duplicateRate == 0.0,
                "D-STRONG zero-churn must be 0 dups at ${zeroChurn.peerCount} peers",
            )
        }
    }

    private fun printCrossoverVerdict(rows: List<DWithBaselineResult>) {
        println("=== CROSSOVER VERDICT ===")
        val fourPeerRows = rows.filter { it.d.peerCount == 4 }
        val crossoverPoint = fourPeerRows.firstOrNull { r ->
            r.d.gossip.duplicateRate > r.v2.consensus.duplicateRate ||
                r.d.strong.coordMessagesPerTask > r.v2.consensus.coordMessagesPerTask
        }
        if (crossoverPoint != null) {
            val c = crossoverPoint.d.churnConfig
            println(
                "At 4 peers: consistent-hashing advantage breaks at churn ~${c.joinRatePercent}/${c.leaveRatePercent}/${c.partitionRatePercent}%",
            )
            println("Above that rate, D-GOSSIP dup-rate approaches or exceeds OPT/IR, and D-STRONG's amortised cost rises toward CONS.")
        } else {
            println("At 4 peers: D-STRONG maintains advantage across all measured churn rates (stable peer regime).")
        }
        println()
    }

    private fun pct(d: Double): String = "%.1f%%".format(d * 100)
}
