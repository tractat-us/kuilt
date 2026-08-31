package us.tractat.kuilt.otel

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import org.slf4j.LoggerFactory
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A store that stays broken is **one** piece of news, and [WarpLogRecordExporter] must report it
 * that way (#2237, #2185).
 *
 * Both of its failure paths retry, for reasons documented where they are: `retireSupersededSegments`
 * re-sweeps *every* outstanding ledger entry so a refused delete is retried without waiting for a
 * restart, and a refused durable write is simply retried by the next export. Reporting per attempt
 * therefore turns one unchanging condition into Θ(passes × ledger) log lines — each of which, before
 * this fix, carried a throwable and so materialised a stack trace.
 *
 * That is a production pathology first: an `IndexedDbDurableStore` past its quota — the shape the
 * exporter's own KDoc names — emits unbounded log volume, and unbounded stack traces, for a
 * condition that never changes. Two measured consequences it also had, both of which read as test
 * infrastructure problems rather than as this defect:
 *
 * - `WarpLogRecordExporterRetirementTest`'s crash-sweep case burned **11.09 s of wall clock** on
 *   `macosArm64` inside a `runTest` body that performs no real I/O at all — against the bare
 *   `runTest` 60 s default, a 5× margin that a contended box crosses (#2237). Dropping the
 *   throwable from the single sweep line took that case to **0.0 s** with nothing else changed.
 * - On `wasmJsBrowserTest` the same class emitted 841 of these lines into one teamcity service
 *   message. Past 1 MB the harness drops the class's results **and exits 0**, so a failure in the
 *   segment-retirement guards — the code path that deletes a user's persisted telemetry — would go
 *   unnoticed (#2185, and #2183 for its loud form).
 *
 * ## Why this class is `jvmTest` rather than `commonTest`
 *
 * The claim is about **emitted log lines**, so it is asserted against emitted log lines: logback is
 * already on this module's JVM test runtime classpath, and `:kuilt-otel-tap`'s `JoinCodeNotLoggedTest`
 * establishes the pattern. The alternative considered and rejected was an `internal` counter on the
 * exporter that production never reads — an instrument, not an outcome, and one that keeps reporting
 * "1" after somebody deletes the log call it is supposed to witness. Nothing here is JVM-specific:
 * the code under test is `commonMain`, so a green here is a green everywhere.
 */
class WarpLogRecordExporterFailureReportingTest {

    private fun recordId(id: Int): ByteString =
        ByteString(ByteArray(8) { i -> (id shr (8 * i)).toByte() })

    private fun record(id: Int) = LogRecord(
        recordId = recordId(id),
        body = "body-$id",
        observedEpochNanos = 1_700_000_000_000_000_000L,
    )

    /**
     * The same shape [WarpLogRecordExporterRetirementTest] uses: a window small enough that a pass
     * runs every [MAX_RECORDS] exports, and a segment small enough that the passes actually retire
     * something. Both knobs exist to make retirement *reachable*; neither switches off the retry
     * this class is about, which is why the preconditions below measure the retries rather than
     * assume them.
     */
    private fun exporterFor(store: DurableStore) = WarpLogRecordExporter(
        replica = ReplicaId("A"),
        store = store,
        maxRecords = MAX_RECORDS,
        bufferPolicy = BufferPolicy.DROP_OLDEST,
        segmentOps = SEGMENT_OPS,
    )

    /**
     * Run [block] with every event the exporter's logger emits captured, at every level.
     *
     * Level `ALL` deliberately: the property is about the *volume* a broken store provokes, so a
     * line demoted to `debug` still counts against it. Restoring the level and detaching the
     * appender in `finally` keeps the swap from leaking into whatever runs next in this JVM.
     */
    private suspend fun <T> capturingExporterLogs(block: suspend (List<ILoggingEvent>) -> T): T {
        val logger = LoggerFactory.getLogger(EXPORTER_LOGGER) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val previousLevel = logger.level
        logger.level = Level.TRACE
        logger.addAppender(appender)
        try {
            return block(appender.list)
        } finally {
            logger.detachAppender(appender)
            appender.stop()
            logger.level = previousLevel
        }
    }

    private fun List<ILoggingEvent>.naming(fragment: String): List<ILoggingEvent> =
        filter { fragment in it.formattedMessage }

    @Test
    fun aDeleteThatKeepsFailingIsReportedOncePerSegmentNotOncePerAttempt() = runTest {
        val store = FailDeleteStore(RecordingStore())
        lateinit var targets: Set<String>
        lateinit var lines: List<ILoggingEvent>
        var attempts = 0

        capturingExporterLogs { captured ->
            val exporter = exporterFor(store)
            repeat(EXPORTS) { i -> exporter.export(record(i)) }
            targets = store.deleteTargets().map { it.name }.toSet()
            attempts = store.deleteAttempts()
            lines = captured.naming(SWEEP_FRAGMENT).toList()
        }

        assertAll(
            {
                assertTrue(
                    targets.isNotEmpty(),
                    "precondition: the rig never fired — no retirement reached a delete",
                )
            },
            {
                // Without this the claim below is satisfiable by a run that never retried, where
                // "once per segment" and "once per attempt" are the same number and the assertion
                // proves nothing. The re-sweep produces far more than 3 attempts per key here.
                assertTrue(
                    attempts > targets.size * 3,
                    "precondition: the retry path did not re-attempt — $attempts attempt(s) " +
                        "over ${targets.size} key(s), so this test cannot tell the two claims apart",
                )
            },
            {
                assertEquals(
                    targets.size,
                    lines.size,
                    "a failing delete was reported once per attempt ($attempts) rather than once " +
                        "per segment (${targets.size})",
                )
            },
            {
                // The half that cost the 11 s. `sourceInfoType=libbacktrace` prices a Kotlin/Native
                // trace at ~10 ms, so the trace — not the line — is what a retry path cannot afford.
                // The cause's type and message survive in the message itself.
                val traced = lines.mapNotNull { it.throwableProxy?.className }
                assertTrue(
                    traced.isEmpty(),
                    "${traced.size} of ${lines.size} routine, retried delete failures still attach " +
                        "a stack trace (${traced.distinct()})",
                )
            },
            {
                assertTrue(
                    lines.all { "simulated delete failure" in it.formattedMessage },
                    "dropping the throwable also dropped the diagnosis: " +
                        lines.map { it.formattedMessage }.distinct().take(3),
                )
            },
        )
    }

    /**
     * The control arm that separates "report a state change" from "report once, ever". A store that
     * comes back and then breaks again is news a second time — without this, a fix that simply
     * remembered "already reported" forever would pass the test above and silence a real second
     * outage.
     */
    @Test
    fun aDeleteThatFailsAgainAfterRecoveringIsReportedAgain() = runTest {
        val store = FailDeleteStore(RecordingStore())
        var duringFirstOutage = 0
        var afterRecovery = 0
        var afterSecondOutage = 0
        var sweptBetween = 0

        capturingExporterLogs { captured ->
            val exporter = exporterFor(store)
            repeat(EXPORTS) { i -> exporter.export(record(i)) }
            duringFirstOutage = captured.naming(SWEEP_FRAGMENT).size

            // The store comes back. The next pass sweeps the backlog successfully, which is what
            // has to re-arm the report.
            store.allowDeletes()
            repeat(EXPORTS) { i -> exporter.export(record(1_000 + i)) }
            sweptBetween = store.deleteTargets().size
            afterRecovery = captured.naming(SWEEP_FRAGMENT).size

            store.refuseDeletes()
            repeat(EXPORTS) { i -> exporter.export(record(2_000 + i)) }
            afterSecondOutage = captured.naming(SWEEP_FRAGMENT).size
        }

        assertAll(
            {
                assertTrue(
                    duringFirstOutage > 0,
                    "precondition: the first outage was never reported, so the arms below compare nothing",
                )
            },
            {
                assertEquals(
                    duringFirstOutage,
                    afterRecovery,
                    "a store that came back kept being reported as broken",
                )
            },
            {
                assertTrue(
                    sweptBetween > 0,
                    "precondition: nothing was ever swept successfully, so the recovery arm is vacuous",
                )
            },
            {
                assertTrue(
                    afterSecondOutage > afterRecovery,
                    "a fresh outage after the store recovered was swallowed as a repeat " +
                        "($afterRecovery lines before it, $afterSecondOutage after)",
                )
            },
        )
    }

    /**
     * The durable-write twin. Health already carries the streak this dedups on
     * ([ExporterHealth.consecutiveFailures]), so the state the report reads is production state on
     * a public surface — not a counter kept alongside it for a test's benefit.
     */
    @Test
    fun aStoreRefusingEveryWriteIsReportedOncePerOutageNotOncePerExport() = runTest {
        val store = WriteRefusingStore(RecordingStore())
        var duringFirstOutage = 0
        var afterSecondOutage = 0
        var refused = 0
        var recoveredResult: ExportResult = ExportResult.Success
        var failedExports = 0L
        lateinit var lines: List<ILoggingEvent>

        capturingExporterLogs { captured ->
            val exporter = exporterFor(store)
            exporter.export(record(0))

            store.refuseWrites()
            repeat(EXPORTS) { i -> exporter.export(record(1 + i)) }
            duringFirstOutage = captured.naming(WRITE_FRAGMENT).size
            refused = store.refusedWrites()

            store.allowWrites()
            recoveredResult = exporter.export(record(1_000))

            store.refuseWrites()
            exporter.export(record(2_000))
            afterSecondOutage = captured.naming(WRITE_FRAGMENT).size
            failedExports = exporter.health.value.failed
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
                    EXPORTS + 1L,
                    failedExports,
                    "precondition: not every armed export actually failed, so the counts below are " +
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
                // Kept, unlike the sweep's: a refused durable write is the store rejecting the
                // app's own data, and now that it is reported once per outage the trace is
                // affordable. Health's `lastFailure` holds the same cause for a caller.
                assertTrue(
                    lines.all { it.throwableProxy != null },
                    "the durable-write failure lost the trace that is its whole diagnosis",
                )
            },
        )
    }

    private companion object {
        /** The exporter's logger name, spelled out because the production constant is private. */
        const val EXPORTER_LOGGER = "us.tractat.kuilt.otel.WarpLogRecordExporter"

        /** Distinguishing fragment of the sweep-failure line. */
        const val SWEEP_FRAGMENT = "could not be deleted"

        /** Distinguishing fragment of the durable-write-failure line. */
        const val WRITE_FRAGMENT = "durable write failed"

        /**
         * Exports per phase. Only has to be comfortably more than one window's worth: the claim is
         * that the report count does **not** track it, so a larger number only makes a regression
         * louder — and past ~1,200 it is what overruns the wasm harness's message ceiling.
         */
        const val EXPORTS = 300

        /** Records retained. Small, so a window pass — and therefore a retirement — runs often. */
        const val MAX_RECORDS = 10

        /** Ops per segment. Small, so segments actually roll and become retirable. */
        const val SEGMENT_OPS = 8
    }
}
