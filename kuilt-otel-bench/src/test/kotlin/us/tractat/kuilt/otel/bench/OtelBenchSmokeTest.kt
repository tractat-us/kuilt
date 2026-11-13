package us.tractat.kuilt.otel.bench

import kotlinx.coroutines.runBlocking
import us.tractat.kuilt.otel.otlp.OtlpWireFormat
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs every benchmark arm once, at sizes small enough for the default build, so the arms keep
 * compiling and keep working between the occasions someone needs a baseline.
 *
 * It asserts no timing — a timing assertion in the default build is a flake waiting for a busy box.
 * Each arm already fails if its rig did not fire (series recovered, writes taken, counters
 * totalled); what this adds is the iteration counts, and the one structural property the arms rest
 * on that does not depend on a clock: a durable write carries the WHOLE map, so it grows with the
 * series count. If #3285 shards the persistence, that assertion is the one to revisit.
 */
class OtelBenchSmokeTest {
    private val tiny = Plan(opsPerRound = 3, rounds = 2, warmUpOps = 1)

    @Test
    fun recordingArmRunsAndEachWriteCarriesTheWholeMap() = runBlocking {
        val rows = BenchStore.entries.flatMap { store ->
            RecordingOp.entries.flatMap { op ->
                listOf(1, 10).map { n -> withStore(store) { recordingArm(op, n, tiny, store.name, it) } }
            }
        }
        val byKey = rows.associateBy { Triple(it.store, it.op, it.series) }
        val checks: List<() -> Unit> =
            rows.map { row -> { assertEquals(6L, row.sample.executed, "$row") } } +
                rows.filter { it.series == 10 }.map { big ->
                    {
                        val small = byKey.getValue(Triple(big.store, big.op, 1))
                        assertTrue(
                            big.bytesPerWrite > small.bytesPerWrite * 5,
                            "10 series wrote ${big.bytesPerWrite} B, 1 wrote ${small.bytesPerWrite} B",
                        )
                    }
                }
        assertAll(*checks.toTypedArray())
    }

    @Test
    fun bridgeExportArmRuns() = runBlocking {
        val row = bridgeExportArm(series = 10, rounds = 2, warmUpExports = 1)
        assertAll(
            { assertEquals(2L, row.sample.executed) },
            // Ten points of one kind: one durable write per export, not one per point (#3284).
            { assertEquals(1L, row.writesPerExport) },
        )
    }

    @Test
    fun edgeDrainArmRuns() = runBlocking {
        val rows = OtlpWireFormat.entries.map { wire ->
            edgeDrainArm(series = 20, changed = 2, wire = wire, rounds = 2, warmUpRounds = 1)
        }
        val checks: List<() -> Unit> = rows.flatMap { row ->
            listOf(
                { assertEquals(2L, row.sample.executed, "$row") },
                { assertTrue(row.requestBytesPerDrain > 0L, "$row") },
                { assertTrue(row.edgeBytesWrittenPerDrain > 0L, "$row") },
            )
        }
        assertAll(*checks.toTypedArray())
    }
}
