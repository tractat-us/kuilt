package us.tractat.kuilt.otel.bench

import kotlinx.coroutines.runBlocking
import us.tractat.kuilt.otel.otlp.OtlpWireFormat
import kotlin.test.Test

/**
 * The measured runs behind #3283's baseline rows. Excluded from the default build; run with
 * `-Pkuilt.benchmark.tests=true` (see this module's build file). Each class prints one table and
 * writes it to `build/reports/otel-bench/`.
 *
 * Plans are sized so a round is a few hundred milliseconds at every series count: a fixed op count
 * would spend a second at 1 series and a minute at 10k. Each arm checks its own rig and iteration
 * count before it returns a row, so a row in the table is a row that ran.
 */
private val SERIES = listOf(1, 100, 1_000, 10_000)

private fun inMemoryPlan(series: Int): Plan = when (series) {
    1 -> Plan(opsPerRound = 20_000, rounds = 5, warmUpOps = 40_000)
    100 -> Plan(opsPerRound = 5_000, rounds = 5, warmUpOps = 10_000)
    1_000 -> Plan(opsPerRound = 500, rounds = 5, warmUpOps = 1_000)
    else -> Plan(opsPerRound = 50, rounds = 5, warmUpOps = 100)
}

private fun filePlan(series: Int): Plan = when (series) {
    1, 100 -> Plan(opsPerRound = 500, rounds = 5, warmUpOps = 500)
    1_000 -> Plan(opsPerRound = 200, rounds = 5, warmUpOps = 200)
    else -> Plan(opsPerRound = 50, rounds = 5, warmUpOps = 50)
}

class RecordingCostBenchmark {
    @Test
    fun recordingCostBySeriesCount() = runBlocking {
        val rows = RecordingOp.entries.flatMap { op ->
            SERIES.map { n ->
                withStore(BenchStore.IN_MEMORY) { recordingArm(op, n, inMemoryPlan(n), "in-memory", it) }
            }
        }
        recordingTable("recording", rows)
    }
}

class PersistenceCostBenchmark {
    @Test
    fun durableWriteCostBySeriesCount() = runBlocking {
        val rows = SERIES.map { n ->
            withStore(BenchStore.FILE) { recordingArm(RecordingOp.INCREMENT_SUM, n, filePlan(n), "file", it) }
        }
        recordingTable("persistence", rows)
    }
}

class BridgeExportBenchmark {
    /**
     * At 10k series one export is 10k whole-map writes — about a minute on a quiet laptop — so it
     * gets one timed export and no warm-up of its own; the smaller rows before it warm the path.
     */
    @Test
    fun sdkExportBySeriesCount() = runBlocking {
        val rows = listOf(
            bridgeExportArm(series = 100, rounds = 5, warmUpExports = 5),
            bridgeExportArm(series = 1_000, rounds = 3, warmUpExports = 1),
            bridgeExportArm(series = 10_000, rounds = 1, warmUpExports = 0),
        )
        bridgeTable(rows)
    }
}

class EdgeDrainBenchmark {
    @Test
    fun drainByChangedFraction() = runBlocking {
        val rows = OtlpWireFormat.entries.flatMap { wire ->
            listOf(1_000, 10_000).flatMap { n ->
                listOf(0.01, 0.1, 1.0).map { fraction ->
                    edgeDrainArm(n, changed = (n * fraction).toInt(), wire = wire, rounds = 5, warmUpRounds = 2)
                }
            }
        }
        drainTable(rows)
    }
}
