package us.tractat.kuilt.otel

import ch.qos.logback.classic.spi.ILoggingEvent
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.RgaId
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

    @Test
    fun aDeleteThatKeepsFailingIsReportedOncePerSegmentNotOncePerAttempt() = runTest {
        val store = FailDeleteStore(RecordingStore())
        lateinit var targets: Set<String>
        lateinit var lines: List<ILoggingEvent>
        var attempts = 0

        capturingLogsOf(EXPORTER_LOGGER) { captured ->
            val exporter = exporterFor(store)
            repeat(EXPORTS) { i -> exporter.export(record(i)) }
            // Segment keys only: `sweepLegacyKey` refuses on a different key and reports through a
            // different line, so counting it here would compare 58 refusals against 57 reports.
            targets = store.deleteTargets().map { it.name }
                .filter { it.startsWith(SEGMENT_KEY_PREFIX_FOR_TEST) }
                .toSet()
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
     * The control arm that separates "report once per process" from "report once, ever" — the
     * mistake the fix above is one line away from, and the one that would silence a real outage.
     *
     * **A restart, deliberately, and not "the store came back and broke again".** For a *given*
     * segment number the second failure is unreachable: a number leaves [LogSegmentIndex.retired]
     * the moment its delete succeeds and no number ever re-enters it, so a store that recovers and
     * breaks again always fails on *fresh* numbers — which report anyway, under a correct fix and
     * under a broken one alike. That arm was written first, and a mutation deleting the re-arm
     * entirely passed it. The reachable re-arm is a new exporter over the same store: it starts
     * with an empty memory, and `recover` sweeps the ledger before it reads anything.
     */
    @Test
    fun aStillBrokenStoreIsReportedAgainAfterARestart() = runTest {
        val backing = RecordingStore()
        val store = FailDeleteStore(backing)
        var duringFirstRun = 0
        var afterRestart = 0
        var ledger = emptyList<Int>()

        capturingLogsOf(EXPORTER_LOGGER) { captured ->
            val exporter = exporterFor(store)
            repeat(EXPORTS) { i -> exporter.export(record(i)) }
            duringFirstRun = captured.naming(SWEEP_FRAGMENT).size
            ledger = decodeIndexForTest(requireNotNull(backing.read(INDEX_KEY_FOR_TEST))).retired

            // A second process over the same store, still broken. `loadPersistedState` re-sweeps
            // `retired` before it reads a thing, so every ledger entry is attempted again.
            exporterFor(store).recover()
            afterRestart = captured.naming(SWEEP_FRAGMENT).size
        }

        assertAll(
            {
                assertTrue(
                    ledger.isNotEmpty(),
                    "precondition: the crash left no ledger, so the restart had nothing to re-sweep",
                )
            },
            {
                assertTrue(
                    duringFirstRun > 0,
                    "precondition: the first run reported nothing, so the arm below compares nothing",
                )
            },
            {
                assertEquals(
                    duringFirstRun + ledger.size,
                    afterRestart,
                    "a restart against a store that is still broken re-reported ${afterRestart - duringFirstRun} " +
                        "of its ${ledger.size} outstanding ledger entries — 'once per segment' had " +
                        "become 'once, ever'",
                )
            },
        )
    }

    /**
     * The durable-write twin: one outage is one line, and a store that comes back and breaks again
     * is news a second time.
     *
     * The latch is `WarpLogRecordExporter.durableWriteOutage`, owned by `commit` — see
     * [anUnrelatedBufferUpdateFailureDoesNotSwallowTheDurableWriteReport] for the population
     * argument, and for why the earlier key ([ExporterHealth.consecutiveFailures]) was wrong.
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

        capturingLogsOf(EXPORTER_LOGGER) { captured ->
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

    /**
     * A remote whose CRDT join succeeds and whose **persistence** throws — the buffer-update
     * failure `mergeTurn` is written to survive.
     *
     * `merge` is the realistic vector: the remote arrives over the wire from another device, so
     * `mergeTurn`'s turn-building block is the one that handles a shape this replica did not
     * produce. `loadPersistedState` already treats a throwing `Rga.piece` as reachable ("a segment
     * whose `absorb` threw"), and `adoptRemoteSegment` CBOR-encodes the remote verbatim.
     *
     * The rig is an unchecked cast: an [Rga] carrying a `String` where a [LogRecord] is expected.
     * The element is **tombstoned**, and that is load-bearing rather than incidental —
     * `Rga.entries` filters tombstones, so `rebuildDerivedState` walks past it and leaves this
     * exporter's `log` usable afterwards, while `adoptRemoteSegment`'s encode still serializes the
     * `Insert` op *including its value* through `LogRecord.serializer()` and throws. Poisoning
     * `entries` instead would fail every later export at the same buffer-update step, and the
     * outage the test is about would never be reached.
     */
    @Suppress("UNCHECKED_CAST")
    private fun remoteThatFailsToPersist(): Rga<LogRecord> {
        val (inserted, _) = Rga.empty<String>().insertAfter(ReplicaId("B"), RgaId.HEAD, "not-a-LogRecord")
        val (tombstoned, _) = requireNotNull(inserted.removeAt(0)) { "the rig never inserted anything to tombstone" }
        return tombstoned as Rga<LogRecord>
    }

    /**
     * **A failure the store had nothing to do with must not swallow the store's own report** — the
     * defect this arm exists for (#2237).
     *
     * The line is deduplicated, and what it is deduplicated *against* decides whether it is ever
     * emitted. Keyed on [ExporterHealth.consecutiveFailures] it was not: that counter moves on
     * every failed turn, while only `commit` reports one, so any member of the difference —
     * a buffer-update failure, which logs its own unrelated line — opens the streak first. The
     * store then refuses writes, every later export sees a non-zero streak, and nothing ever
     * resets it because nothing succeeds. The result is **zero** `"durable write failed"` lines
     * for the whole outage, with `health.lastFailure` quietly holding the store's exception and
     * the log pointing at the merge.
     *
     * That is strictly worse than the noise the dedup replaced, which is why it is asserted here
     * rather than left to the arm above: that arm opens its outage from a clean streak, so it is
     * green under both the broken key and the correct one and cannot see this at all.
     *
     * The order matters and is the whole test: unrelated failure **first**, store outage
     * **second**.
     */
    @Test
    fun anUnrelatedBufferUpdateFailureDoesNotSwallowTheDurableWriteReport() = runTest {
        val store = WriteRefusingStore(RecordingStore())
        var mergeResult: ExportResult = ExportResult.Success
        var afterMerge = 0
        var mergeLines = 0
        var failuresAfterMerge = 0L
        var failuresAfterOutage = 0L
        var refused = 0
        lateinit var lines: List<ILoggingEvent>

        capturingLogsOf(EXPORTER_LOGGER) { captured ->
            val exporter = exporterFor(store)
            exporter.export(record(0))

            // An export failure the store had no part in, and which reports through its own line.
            mergeResult = exporter.merge(remoteThatFailsToPersist())
            mergeLines = captured.naming(MERGE_BUFFER_FRAGMENT).size
            afterMerge = captured.naming(WRITE_FRAGMENT).size
            failuresAfterMerge = exporter.health.value.failed

            // Only now does the store break. Under the pre-emptable key this export sees the
            // streak the merge opened and says nothing.
            store.refuseWrites()
            exporter.export(record(1))
            failuresAfterOutage = exporter.health.value.failed
            refused = store.refusedWrites()
            lines = captured.naming(WRITE_FRAGMENT).toList()
        }

        assertAll(
            {
                assertEquals(
                    1L,
                    failuresAfterMerge,
                    "precondition: the merge rig never fired, so nothing pre-empted anything and " +
                        "this test cannot distinguish the two keys",
                )
            },
            {
                assertTrue(
                    mergeResult is ExportResult.Failure,
                    "precondition: the merge succeeded, so the rig fired on some other path: $mergeResult",
                )
            },
            {
                assertEquals(
                    1,
                    mergeLines,
                    "precondition: the merge did not report through its own buffer-update line, so " +
                        "the failure it opened is not the unrelated one this test is about",
                )
            },
            {
                assertEquals(
                    0,
                    afterMerge,
                    "precondition: a durable-write line was already emitted before the store broke, " +
                        "so the assertion below would pass on the pre-emption it exists to catch",
                )
            },
            {
                assertTrue(
                    refused > 0,
                    "precondition: the store refused nothing, so there was no outage to report",
                )
            },
            {
                assertEquals(
                    2L,
                    failuresAfterOutage,
                    "precondition: the armed export did not actually fail, so the count below is " +
                        "not over the population this claims to be about",
                )
            },
            {
                assertEquals(
                    1,
                    lines.size,
                    "an outage opened behind an unrelated failure went unreported for its whole " +
                        "duration — the dedup key counts failures the line is not about",
                )
            },
        )
    }

    private companion object {
        /** The exporter's logger name, spelled out because the production constant is private. */
        const val EXPORTER_LOGGER = "us.tractat.kuilt.otel.WarpLogRecordExporter"

        /**
         * Distinguishing fragment of the **segment** sweep-failure line.
         *
         * Not the more obvious `"could not be deleted"`: `sweepLegacyKey` says that too, and it
         * fires once per `recover()` against a store that refuses deletes — which silently added
         * one to the restart arm's count and read as an off-by-one in the dedup.
         */
        const val SWEEP_FRAGMENT = "retired segment"

        /** Distinguishing fragment of the durable-write-failure line. */
        const val WRITE_FRAGMENT = "durable write failed"

        /**
         * Distinguishing fragment of the **merge** buffer-update line.
         *
         * Deliberately not a prefix of [WRITE_FRAGMENT]: the point of the pre-emption arm is that
         * the two lines come from different populations, so a test that could not tell them apart
         * would be asserting nothing.
         */
        const val MERGE_BUFFER_FRAGMENT = "buffer update failed during merge"

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
