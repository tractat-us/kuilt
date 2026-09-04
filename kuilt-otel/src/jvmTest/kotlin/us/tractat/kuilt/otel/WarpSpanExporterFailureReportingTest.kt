package us.tractat.kuilt.otel

import ch.qos.logback.classic.spi.ILoggingEvent
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.ORSet
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A store that stays broken is **one** piece of news, and [WarpSpanExporter] must report it that way
 * (#2593).
 *
 * All three of its durable-write paths — [WarpSpanExporter.export], [WarpSpanExporter.merge] and
 * [WarpSpanExporter.clear] — write the same key, and a refused write is simply retried by the next
 * call. Reporting per attempt therefore turns one unchanging condition into one line, and one
 * **stack trace**, per export, forever: the shape measured on the sibling at 300 throwable-bearing
 * lines over a 300-export outage (#2237, fixed in #2586).
 *
 * That is a production pathology first — a quota-bound `IndexedDbDurableStore` emits unbounded log
 * volume for a condition that never changes. It also had two measured consequences on the sibling
 * that read as test-infrastructure problems rather than as this defect: on Apple targets each trace
 * is symbolicated, and a `runTest` body performing no real I/O burned ~11 s materialising them; on
 * wasm the volume walked a class's results toward the harness's 1 MB-per-service-message ceiling,
 * past which it drops the class and **exits 0** (#2185, #2183).
 *
 * See [capturingLogsOf] for why this class is `jvmTest` while the code it pins is `commonMain`.
 */
class WarpSpanExporterFailureReportingTest {

    private fun traceId(id: Int): ByteString = ByteString(ByteArray(16) { i -> (id shr (8 * i)).toByte() })

    private fun spanId(id: Int): ByteString = ByteString(ByteArray(8) { i -> (id shr (8 * i)).toByte() })

    private fun span(id: Int) = SpanRecord(
        traceId = traceId(id),
        spanId = spanId(id),
        parentSpanId = null,
        name = "op-$id",
        kind = SpanKind.INTERNAL,
        startEpochNanos = 1_000L + id,
        endEpochNanos = 2_000L + id,
    )

    private fun remote(id: Int): ORSet<SpanRecord> =
        ORSet.empty<SpanRecord>().piece { it.add(ReplicaId("B"), span(id)) }

    /**
     * No `causalClock` and the default buffer cap, deliberately: both are knobs that would switch
     * *off* part of what is under test. A clock makes `export` roll its in-memory add back on
     * failure, and a cap small enough to evict adds a second population of lines to the capture —
     * neither changes the retry this class is about, and both would make the counts below harder to
     * read for no gain.
     */
    private fun exporterFor(store: WriteRefusingStore) = WarpSpanExporter(
        replica = ReplicaId("A"),
        store = store,
    )

    /**
     * One outage is one line, and a store that comes back and breaks again is news a second time.
     *
     * The two halves are one test on purpose. Asserting "reported once" alone is satisfied by a
     * latch that reports once and then **never again** — which would silence every later outage, a
     * strictly worse defect than the noise it replaced. The recovery half is what separates the two,
     * and it is only worth anything if the store really does come back: `recoveredResult` is
     * asserted to be [ExportResult.Success] as a precondition, so the arm cannot go green on a rig
     * where the second failure was unreachable.
     */
    @Test
    fun aStoreRefusingEveryWriteIsReportedOncePerOutageNotOncePerExport() = runTest {
        val store = WriteRefusingStore(RecordingStore())
        var duringFirstOutage = 0
        var afterSecondOutage = 0
        var refused = 0
        var failedExports = 0
        var recoveredResult: ExportResult = ExportResult.Success
        lateinit var lines: List<ILoggingEvent>

        capturingLogsOf(SPAN_EXPORTER_LOGGER) { captured ->
            val exporter = exporterFor(store)
            exporter.export(span(0))

            store.refuseWrites()
            repeat(EXPORTS) { i ->
                if (exporter.export(span(1 + i)) is ExportResult.Failure) failedExports++
            }
            duringFirstOutage = captured.naming(WRITE_FRAGMENT).size
            refused = store.refusedWrites()

            store.allowWrites()
            recoveredResult = exporter.export(span(1_000))

            store.refuseWrites()
            exporter.export(span(2_000))
            afterSecondOutage = captured.naming(WRITE_FRAGMENT).size
            lines = captured.naming(WRITE_FRAGMENT).toList()
        }

        assertAll(
            {
                assertTrue(
                    refused >= EXPORTS,
                    "precondition: the rig refused only $refused write(s) over $EXPORTS armed exports",
                )
            },
            {
                assertEquals(
                    EXPORTS,
                    failedExports,
                    "precondition: not every armed export actually failed, so the count below is " +
                        "not over the population this claims to be about",
                )
            },
            {
                assertEquals(
                    ExportResult.Success,
                    recoveredResult,
                    "precondition: the store never came back, so the control arm below is vacuous",
                )
            },
            {
                assertEquals(
                    1,
                    duringFirstOutage,
                    "one unchanging outage was reported once per export ($EXPORTS of them)",
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
                // Kept, unlike the sibling's sweep line: a refused durable write is the store
                // rejecting the application's own data, and at one line per outage the trace is
                // affordable. A future "optimisation" that drops it reddens here.
                assertTrue(
                    lines.all { it.throwableProxy != null },
                    "the durable-write failure lost the trace that is its whole diagnosis",
                )
            },
        )
    }

    /**
     * The three durable-write paths are **one** population, so whichever reaches the broken store
     * first is the one that reports, and the other two say nothing until it recovers.
     *
     * Opened here by [WarpSpanExporter.merge] rather than by `export`, which is the half the arm
     * above cannot show: a per-path latch would pass that test and fail this one, because it would
     * let the same store outage be announced once by each path. All three write the *same* key —
     * `otel.spans` — so "the store is refusing writes" is a single condition however it is reached.
     *
     * Every precondition here exists because the assertion is a **count of zero further lines**,
     * which is exactly what a rig that never fired also produces.
     */
    @Test
    fun anOutageOpenedByAnyPathSilencesTheOthers() = runTest {
        val store = WriteRefusingStore(RecordingStore())
        var mergeResult: ExportResult = ExportResult.Success
        var exportResult: ExportResult = ExportResult.Success
        var clearResult: ExportResult = ExportResult.Success
        var afterMerge = 0
        var refusedAfterMerge = 0
        var refusedAtEnd = 0
        lateinit var lines: List<ILoggingEvent>

        capturingLogsOf(SPAN_EXPORTER_LOGGER) { captured ->
            val exporter = exporterFor(store)
            exporter.export(span(0))

            store.refuseWrites()
            mergeResult = exporter.merge(remote(1))
            afterMerge = captured.naming(WRITE_FRAGMENT).size
            refusedAfterMerge = store.refusedWrites()

            exportResult = exporter.export(span(2))
            clearResult = exporter.clear()
            refusedAtEnd = store.refusedWrites()
            lines = captured.naming(WRITE_FRAGMENT).toList()
        }

        assertAll(
            {
                assertIs<ExportResult.Failure>(
                    mergeResult,
                    "precondition: the merge succeeded, so no outage was opened by it",
                )
            },
            {
                assertIs<ExportResult.Failure>(
                    exportResult,
                    "precondition: the export succeeded, so its silence below proves nothing",
                )
            },
            {
                assertIs<ExportResult.Failure>(
                    clearResult,
                    "precondition: the clear succeeded, so its silence below proves nothing",
                )
            },
            {
                assertEquals(
                    refusedAfterMerge + 2,
                    refusedAtEnd,
                    "precondition: the export and the clear did not both reach the store, so the " +
                        "assertion below is satisfied by two calls that never attempted a write",
                )
            },
            {
                assertEquals(
                    1,
                    afterMerge,
                    "the merge path did not report the outage it opened",
                )
            },
            {
                assertEquals(
                    1,
                    lines.size,
                    "one store outage was announced ${lines.size} times, once per durable-write " +
                        "path: ${lines.map { it.formattedMessage }}",
                )
            },
            {
                // `firstOrNull`, not `single`: on the red this test exists to produce there are
                // three lines, and `single` throws an IllegalArgumentException that `assertAll`
                // propagates instead of collecting — turning a legible count into a stack trace.
                val first = lines.firstOrNull()?.formattedMessage
                assertTrue(
                    first != null && MERGE_FRAGMENT in first,
                    "the first line reported is not the merge's, so the outage was opened " +
                        "elsewhere: $first",
                )
            },
        )
    }

    private companion object {
        /** The exporter's logger name, spelled out because the production constant is private. */
        const val SPAN_EXPORTER_LOGGER = "us.tractat.kuilt.otel.WarpSpanExporter"

        /**
         * Common to all three durable-write lines (`… for span X` / `… during merge` / `… during
         * clear`), which is the point: they are one population and are counted as one.
         */
        const val WRITE_FRAGMENT = "durable write failed"

        /** Distinguishing tail of the merge path's line. */
        const val MERGE_FRAGMENT = "during merge"

        /**
         * Exports per outage. Only has to be comfortably more than one, since the claim is that the
         * report count does **not** track it — but it is the number the sibling's outage was
         * measured at, and a larger one only makes a regression louder.
         */
        const val EXPORTS = 300
    }
}
