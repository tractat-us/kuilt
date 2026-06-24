/**
 * Warp spike #680 — SPECULATIVE / EXPERIMENTAL.
 *
 * Measures the exclusive-claim duplicate-execution rate across peer count,
 * message latency, and partition rate sweeps. See WarpSimulation.kt for the model.
 *
 * The go/no-go question: is optimistic dedup cheap enough to avoid per-task
 * consensus, or does wasted work dominate?
 */
package us.tractat.kuilt.examples.warp

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

class WarpSpikeTest {

    // Fixed seed for reproducibility.
    private val seed = 42L

    @Test
    fun `sweep peer count — 2 to 8 peers, low latency, no partition`() {
        val results = (2..8 step 2).map { peers ->
            runWarpSimulation(
                taskCount = 40,
                peerCount = peers,
                rounds = 20,
                partitionRate = 0.0,
                messageLossRate = 0.0,
                rng = Random(seed),
            )
        }
        printTable("Peer-count sweep (loss=0, partition=0)", results)

        // Sanity: duplicate rate should stay below 60% even at max peers (no partition).
        results.forEach { r ->
            assertTrue(
                r.duplicateRate < 0.60,
                "Expected dup rate < 60% at ${r.peerCount} peers, got ${r.duplicateRate}",
            )
        }
    }

    @Test
    fun `sweep message loss rate — 2 peers, 0 to 90 percent loss`() {
        val lossRates = listOf(0.0, 0.1, 0.25, 0.5, 0.75, 0.9)
        val results = lossRates.map { loss ->
            runWarpSimulation(
                taskCount = 20,
                peerCount = 2,
                rounds = 30,
                partitionRate = 0.0,
                messageLossRate = loss,
                rng = Random(seed),
            )
        }
        printTable("Message-loss sweep (peers=2, partition=0)", results)

        // At 0% loss: near-zero duplicates (peers see each other's claims quickly).
        val noLossResult = results.first()
        assertTrue(
            noLossResult.duplicateRate < 0.20,
            "Expected dup rate < 20% at 0% loss, got ${noLossResult.duplicateRate}",
        )
    }

    @Test
    fun `sweep partition rate — 4 peers, 0 to 50 percent partition`() {
        val partitionRates = listOf(0.0, 0.1, 0.2, 0.3, 0.5)
        val results = partitionRates.map { partition ->
            runWarpSimulation(
                taskCount = 30,
                peerCount = 4,
                rounds = 25,
                partitionRate = partition,
                messageLossRate = 0.0,
                rng = Random(seed),
            )
        }
        printTable("Partition-rate sweep (peers=4, loss=0)", results)

        // At 50% partition, tasks may not complete — but we should not assert a hard dup bound.
        // Just verify we get a result at all.
        results.forEach { r ->
            assertTrue(r.totalExecutions >= 0)
        }
    }

    @Test
    fun `comprehensive sweep — headline numbers for the report`() {
        data class Config(val peers: Int, val loss: Double, val partition: Double)

        val configs = listOf(
            Config(2, 0.0, 0.0),
            Config(4, 0.0, 0.0),
            Config(8, 0.0, 0.0),
            Config(4, 0.25, 0.0),
            Config(4, 0.5, 0.0),
            Config(4, 0.0, 0.2),
            Config(4, 0.0, 0.5),
            Config(8, 0.25, 0.2),
        )

        val results = configs.map { (peers, loss, partition) ->
            runWarpSimulation(
                taskCount = 40,
                peerCount = peers,
                rounds = 30,
                partitionRate = partition,
                messageLossRate = loss,
                rng = Random(seed),
            )
        }

        println("\n=== WARP SPIKE #680 — HEADLINE RESULTS ===")
        println("TaskQueue=ORSet, Results=ORMap, TaskScheduler=BoundedCounter, seed=$seed")
        println()
        printTable("Comprehensive sweep (40 tasks, 30 rounds)", results)

        // Capture headline: best-case dup rate (2 peers, no loss, no partition)
        val bestCase = results.first()
        println("Best-case duplicate rate (2 peers, 0/0): ${pct(bestCase.duplicateRate)}")
        println("Worst-case scanned: ${pct(results.maxOf { it.duplicateRate })}")
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun printTable(header: String, results: List<SimulationResult>) {
        println("\n--- $header ---")
        println(
            "%-6s %-8s %-10s %-8s %-12s %-12s %-10s %-9s"
                .format("peers", "loss%", "partition%", "rounds", "executions", "duplicates", "dup-rate", "completed"),
        )
        println("-".repeat(80))
        for (r in results) {
            println(
                "%-6d %-8s %-10s %-8d %-12d %-12d %-10s %-9d".format(
                    r.peerCount,
                    pct(r.messageLossRate),
                    pct(r.partitionRate),
                    r.rounds,
                    r.totalExecutions,
                    r.duplicateExecutions,
                    pct(r.duplicateRate),
                    r.tasksCompleted,
                ),
            )
        }
        println()
    }

    private fun pct(d: Double): String = "%.1f%%".format(d * 100)
}
