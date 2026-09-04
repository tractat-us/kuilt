package us.tractat.kuilt.otel

import ch.qos.logback.classic.spi.ILoggingEvent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A store that stays broken is **one** piece of news, and [WarpMetricExporter] must report it that
 * way (#2593).
 *
 * Every mutating method on this exporter — across all five metric kinds — funnels its durable write
 * through one private `persist`, and a refused write is simply retried by the next call. Reporting
 * per attempt therefore turns one unchanging condition into one line, and one **stack trace**, per
 * metric write, forever. See [WarpSpanExporterFailureReportingTest] for the measured cost of that
 * shape on the sibling this fix follows (#2237 / #2586), and [capturingLogsOf] for why this class is
 * `jvmTest` while the code it pins is `commonMain`.
 */
class WarpMetricExporterFailureReportingTest {

    private fun exporterFor(store: WriteRefusingStore) = WarpMetricExporter(
        replica = ReplicaId("A"),
        store = store,
    )

    private fun sumKey(name: String) = MetricKey(name, MetricKind.SUM, emptyMap())

    /**
     * One outage is one line, and a store that comes back and breaks again is news a second time.
     *
     * The two halves are one test for the reason spelled out on
     * [WarpSpanExporterFailureReportingTest.aStoreRefusingEveryWriteIsReportedOncePerOutageNotOncePerExport]:
     * "reported once" on its own is satisfied by a latch that reports once and then never again,
     * which is a worse defect than the noise it replaced. `recoveredResult` is asserted to be a
     * success as a precondition, so the recovery half cannot go green on a rig where the second
     * failure was unreachable.
     */
    @Test
    fun aStoreRefusingEveryWriteIsReportedOncePerOutageNotOncePerMetricWrite() = runTest {
        val store = WriteRefusingStore(RecordingStore())
        var duringFirstOutage = 0
        var afterSecondOutage = 0
        var refused = 0
        var failedWrites = 0
        var recoveredResult: MetricExportResult = MetricExportResult.Success
        lateinit var lines: List<ILoggingEvent>

        capturingLogsOf(METRIC_EXPORTER_LOGGER) { captured ->
            val exporter = exporterFor(store)
            exporter.incrementSum(sumKey("requests"))

            store.refuseWrites()
            repeat(WRITES) {
                if (exporter.incrementSum(sumKey("requests")) is MetricExportResult.Failure) failedWrites++
            }
            duringFirstOutage = captured.naming(WRITE_FRAGMENT).size
            refused = store.refusedWrites()

            store.allowWrites()
            recoveredResult = exporter.incrementSum(sumKey("requests"))

            store.refuseWrites()
            exporter.incrementSum(sumKey("requests"))
            afterSecondOutage = captured.naming(WRITE_FRAGMENT).size
            lines = captured.naming(WRITE_FRAGMENT).toList()
        }

        assertAll(
            {
                assertTrue(
                    refused >= WRITES,
                    "precondition: the rig refused only $refused write(s) over $WRITES armed writes",
                )
            },
            {
                assertEquals(
                    WRITES,
                    failedWrites,
                    "precondition: not every armed write actually failed, so the count below is " +
                        "not over the population this claims to be about",
                )
            },
            {
                assertEquals(
                    MetricExportResult.Success,
                    recoveredResult,
                    "precondition: the store never came back, so the control arm below is vacuous",
                )
            },
            {
                assertEquals(
                    1,
                    duringFirstOutage,
                    "one unchanging outage was reported once per metric write ($WRITES of them)",
                )
            },
            {
                assertEquals(
                    2,
                    afterSecondOutage,
                    "a fresh outage after the store recovered was swallowed as a repeat",
                )
            },
            {
                // Kept: a refused durable write is the store rejecting the application's own data,
                // and at one line per outage the trace is affordable. Dropping it reddens here.
                assertTrue(
                    lines.all { it.throwableProxy != null },
                    "the durable-write failure lost the trace that is its whole diagnosis",
                )
            },
        )
    }

    /**
     * The five metric kinds are **one** population: they are five keys in one store, and a store
     * refusing writes is one condition however it is reached.
     *
     * A latch per store key — or per kind — would pass the arm above and fail this one, which is
     * why it is here: the arm above only ever touches `SUM`.
     */
    @Test
    fun oneOutageIsOneLineAcrossEveryMetricKind() = runTest {
        val store = WriteRefusingStore(RecordingStore())
        val results = mutableListOf<MetricExportResult>()
        var refusedBefore = 0
        var refusedAfter = 0
        lateinit var lines: List<ILoggingEvent>

        capturingLogsOf(METRIC_EXPORTER_LOGGER) { captured ->
            val exporter = exporterFor(store)
            store.refuseWrites()
            refusedBefore = store.refusedWrites()

            results += exporter.incrementSum(sumKey("requests"))
            results += exporter.incrementSumDouble(MetricKey("latency.total", MetricKind.SUM, emptyMap()), by = 1.5)
            results += exporter.setGauge(MetricKey("cpu", MetricKind.GAUGE, emptyMap()), value = 0.5, timestamp = 1L)
            results += exporter.addCardinality(MetricKey("users", MetricKind.CARDINALITY, emptyMap()), "u1")
            results += exporter.recordHistogram(
                MetricKey("latency", MetricKind.EXPONENTIAL_HISTOGRAM, emptyMap()),
                value = 12.0,
            )

            refusedAfter = store.refusedWrites()
            lines = captured.naming(WRITE_FRAGMENT).toList()
        }

        assertAll(
            {
                assertTrue(
                    results.all { it is MetricExportResult.Failure },
                    "precondition: ${results.count { it is MetricExportResult.Success }} of " +
                        "${results.size} kinds never failed, so their silence proves nothing",
                )
            },
            {
                assertEquals(
                    results.size,
                    refusedAfter - refusedBefore,
                    "precondition: only ${refusedAfter - refusedBefore} of ${results.size} kinds " +
                        "reached the store, so the assertion below is satisfied by calls that " +
                        "never attempted a write",
                )
            },
            {
                assertEquals(
                    1,
                    lines.size,
                    "one store outage was announced once per metric kind: " +
                        lines.map { it.formattedMessage },
                )
            },
        )
    }

    /**
     * **A failure the write path had nothing to do with must not swallow the write path's report.**
     *
     * This is the arm that decides whether the dedup key is sound, and it is the defect #2586 found
     * on the sibling after shipping the obvious version: keyed on anything that moves on *every*
     * failure — a health streak, a shared "last failure" — a member of the wider population opens
     * the key first, and the store's own outage is then reported **zero** times rather than once.
     * That is strictly worse than the per-attempt noise the dedup replaced, because the log then
     * points at the wrong subsystem entirely.
     *
     * [WarpMetricExporter.clear] is that wider population here: it fails on a refused **delete**,
     * reports through its own line, and — unlike a write — proves nothing about whether the store
     * will accept writes. Its failure must therefore neither open the write latch nor close it.
     *
     * The order is the whole test: unrelated failure **first**, store write outage **second**.
     */
    @Test
    fun aRefusedClearDeleteDoesNotSwallowTheDurableWriteReport() = runTest {
        val store = WriteRefusingStore(FailDeleteStore(RecordingStore()))
        var clearResult: MetricExportResult = MetricExportResult.Success
        var writeResult: MetricExportResult = MetricExportResult.Success
        var deleteLines = 0
        var writeLinesAfterClear = 0
        lateinit var writeLines: List<ILoggingEvent>

        capturingLogsOf(METRIC_EXPORTER_LOGGER) { captured ->
            val exporter = exporterFor(store)
            exporter.incrementSum(sumKey("requests"))

            // A failure on this exporter that the durable-write path had no part in, and which
            // reports through a line of its own.
            clearResult = exporter.clear()
            deleteLines = captured.naming(DELETE_FRAGMENT).size
            writeLinesAfterClear = captured.naming(WRITE_FRAGMENT).size

            // Only now does the store stop taking writes. Under a shared key this write sees the
            // failure the clear opened and says nothing.
            store.refuseWrites()
            writeResult = exporter.incrementSum(sumKey("requests"))
            writeLines = captured.naming(WRITE_FRAGMENT).toList()
        }

        assertAll(
            {
                assertIs<MetricExportResult.Failure>(
                    clearResult,
                    "precondition: the clear rig never fired, so nothing pre-empted anything and " +
                        "this test cannot distinguish a sound key from a shared one",
                )
            },
            {
                assertEquals(
                    1,
                    deleteLines,
                    "precondition: the clear did not report through its own delete line, so the " +
                        "failure it opened is not the unrelated one this test is about",
                )
            },
            {
                assertEquals(
                    0,
                    writeLinesAfterClear,
                    "precondition: a durable-write line was already emitted before the store " +
                        "stopped taking writes, so the assertion below would pass on the " +
                        "pre-emption it exists to catch",
                )
            },
            {
                assertIs<MetricExportResult.Failure>(
                    writeResult,
                    "precondition: the armed write did not actually fail, so there was no outage " +
                        "to report",
                )
            },
            {
                assertEquals(
                    1,
                    writeLines.size,
                    "an outage opened behind an unrelated failure went unreported for its whole " +
                        "duration — the dedup key counts failures the line is not about",
                )
            },
        )
    }

    private companion object {
        /** The exporter's logger name, spelled out because the production constant is private. */
        const val METRIC_EXPORTER_LOGGER = "us.tractat.kuilt.otel.WarpMetricExporter"

        /** Distinguishing fragment of the durable-**write**-failure line. */
        const val WRITE_FRAGMENT = "durable write failed for metric"

        /**
         * Distinguishing fragment of `clear`'s durable-**delete**-failure line.
         *
         * Deliberately not a prefix of [WRITE_FRAGMENT]: the point of the pre-emption arm is that
         * the two lines come from different populations, so a test that could not tell them apart
         * would be asserting nothing.
         */
        const val DELETE_FRAGMENT = "durable delete failed during clear"

        /**
         * Metric writes per outage. Only has to be comfortably more than one, since the claim is
         * that the report count does **not** track it; 300 is the length the sibling's outage was
         * measured at.
         */
        const val WRITES = 300
    }
}
