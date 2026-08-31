package us.tractat.kuilt.otel

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins that [WarpLogRecordExporter]'s two **retry** paths report a failure once per thing that
 * failed, not once per attempt (#2237).
 *
 * Both paths re-attempt on every turn, for good reasons documented where they are:
 * `retireSupersededSegments` re-sweeps **every** outstanding ledger entry so a delete that failed
 * once is retried without waiting for a restart, and a refused durable write is simply retried by
 * the next export. A store that stays broken therefore turns "log the failure" into "log the
 * failure Θ(exports × ledger) times" — and each of those lines carried a throwable, so each one
 * materialised a stack trace.
 *
 * That is a production pathology first: a device whose store is refusing writes (a quota-bound
 * `IndexedDbDurableStore` is the shape the exporter's own KDoc names) emits unbounded log volume
 * for one unchanging condition. It is also what made this module's tests slow enough to be
 * load-sensitive — on Apple targets a stack trace costs real milliseconds, so the four
 * `WarpLogRecordExporterRetirementTest` cases that drive a failing store spent ~25 s of wall
 * clock symbolicating traces inside a `runTest` body that does no real I/O at all.
 *
 * **The assertions here are on the count of lines emitted, which is the outcome, not an
 * instrument.** Log volume *is* the quantity under test, and this module cannot capture its own
 * logger output — see [WarpLogRecordExporter.dropSummariesEmitted], whose KDoc argues the same
 * point for the drop summary and which these two counters follow.
 */
class WarpLogRecordExporterRetryLoggingTest {

    private fun recordId(id: Int): ByteString =
        ByteString(ByteArray(8) { i -> (id shr (8 * i)).toByte() })

    private fun record(id: Int) = LogRecord(
        recordId = recordId(id),
        body = "body-$id",
        observedEpochNanos = 1_700_000_000_000_000_000L,
    )

    private fun exporterFor(store: DurableStore) = WarpLogRecordExporter(
        replica = ReplicaId("A"),
        store = store,
        maxRecords = 10,
        bufferPolicy = BufferPolicy.DROP_OLDEST,
        segmentOps = 8,
    )

    /**
     * The sweep path. The reference is built from the **fake**, not from the counter under test:
     * the store knows exactly which distinct keys it refused, and that set is what a
     * once-per-segment report has to match.
     */
    @Test
    fun aDeleteThatKeepsFailingIsReportedOncePerSegmentNotOncePerAttempt() = runTest {
        val store = FailDeleteStore(RecordingStore())
        val exporter = exporterFor(store)

        repeat(300) { i -> exporter.export(record(i)) }

        val targets = store.deleteTargets()
        assertAll(
            {
                assertTrue(
                    targets.isNotEmpty(),
                    "precondition: the rig never fired — no retirement reached a delete",
                )
            },
            {
                // Without this the claim below is satisfiable by a run that never retried, in
                // which case "once per segment" and "once per attempt" are the same number and
                // the test proves nothing. Three attempts per key is well inside what the
                // re-sweep produces here and well outside one attempt each.
                assertTrue(
                    store.deleteAttempts > targets.size * 3,
                    "precondition: the retry path did not re-attempt — ${store.deleteAttempts} " +
                        "attempt(s) over ${targets.size} key(s)",
                )
            },
            {
                assertEquals(
                    targets.size,
                    exporter.sweepFailuresReported,
                    "a failing delete was reported once per attempt (${store.deleteAttempts}) " +
                        "rather than once per segment (${targets.size})",
                )
            },
        )
    }

    /**
     * The durable-write path, with the control arm that separates "report a state change" from
     * "report once ever": a store that comes back and then breaks again is news a second time.
     */
    @Test
    fun aStoreRefusingEveryWriteIsReportedOncePerOutageNotOncePerExport() = runTest {
        val store = WriteRefusingStore(RecordingStore())
        val exporter = exporterFor(store)
        exporter.export(record(0))

        store.refuseWrites()
        repeat(OUTAGE_EXPORTS) { i -> exporter.export(record(1 + i)) }
        val duringFirstOutage = exporter.turnFailuresReported

        store.allowWrites()
        val recoveredResult = exporter.export(record(1_000))
        store.refuseWrites()
        exporter.export(record(2_000))

        assertAll(
            {
                assertEquals(
                    OUTAGE_EXPORTS + 1,
                    exporter.health.value.failed,
                    "precondition: not every armed export actually failed, so the counts below " +
                        "are not over the population this claims to be about",
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
                    "one unchanging outage was reported once per export ($OUTAGE_EXPORTS of them)",
                )
            },
            {
                assertEquals(
                    2,
                    exporter.turnFailuresReported,
                    "a fresh outage after the store recovered was swallowed as a repeat",
                )
            },
        )
    }

    private companion object {
        /**
         * Exports made while the store is refusing. Only has to be comfortably more than one:
         * the claim is that the count of reports does not track it.
         */
        const val OUTAGE_EXPORTS = 50
    }
}
