package us.tractat.kuilt.otel

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A run of records must cost one write turn, not one per record (#2194).
 *
 * Capturing one log line used to cost a Theta(N) CRDT append, a CBOR encode of the
 * active segment and **two** durable file writes — measured at a ~9 ms floor on an
 * iPhone XS that never amortised, plus a growing Theta(N) term. These pin that a
 * batched export is observationally identical to the per-record loop while paying
 * the fixed cost once.
 */
class WarpLogRecordExporterBatchTest {

    /** Counts what reaches the store — the direct measurement #2194 is about. */
    private class CountingStore(private val delegate: DurableStore = InMemoryDurableStore()) : DurableStore {
        var writes: Int = 0
            private set

        override suspend fun read(key: StoreKey): ByteArray? = delegate.read(key)

        override suspend fun write(key: StoreKey, bytes: ByteArray) {
            writes++
            delegate.write(key, bytes)
        }

        override suspend fun delete(key: StoreKey): Unit = delegate.delete(key)
    }

    /**
     * Refuses the **first** write and accepts every later one — the shape a quota-bound
     * store presents transiently, and the discriminator for whether a failed turn
     * abandons the rest of its batch.
     */
    private class FailOnceStore(private val delegate: DurableStore = InMemoryDurableStore()) : DurableStore {
        private var refused = false

        override suspend fun read(key: StoreKey): ByteArray? = delegate.read(key)

        override suspend fun write(key: StoreKey, bytes: ByteArray) {
            if (!refused) {
                refused = true
                error("store refused the write")
            }
            delegate.write(key, bytes)
        }

        override suspend fun delete(key: StoreKey): Unit = delegate.delete(key)
    }

    private fun record(n: Int) = LogRecord(
        recordId = ByteString(ByteArray(RECORD_ID_BYTES) { n.toByte() }),
        severityNumber = 9,
        severityText = "INFO",
        body = "event $n",
        attributes = emptyMap(),
        timestampEpochNanos = n.toLong(),
        observedEpochNanos = n.toLong(),
    )

    private fun records(count: Int) = List(count) { record(it) }

    @Test
    fun aBatchIsObservationallyIdenticalToExportingOneAtATime() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val looped = WarpLogRecordExporter(ReplicaId("device-1"), InMemoryDurableStore())
        records(BATCH).forEach { looped.export(it) }

        val batched = WarpLogRecordExporter(ReplicaId("device-1"), InMemoryDurableStore())
        batched.export(records(BATCH))

        assertAll(
            { assertEquals(records(BATCH).map { it.body }, batched.snapshot().toList().map { it.body }) },
            { assertEquals(looped.snapshot().toList().map { it.body }, batched.snapshot().toList().map { it.body }) },
            { assertEquals(looped.snapshot().opCount, batched.snapshot().opCount) },
            { assertEquals(looped.health.value.accepted, batched.health.value.accepted) },
        )
    }

    /**
     * The headline: writes must not scale with records. The per-record path pays two
     * writes each; a batch pays a segment write plus at most one index write.
     */
    @Test
    fun aBatchPaysWritesPerTurnRatherThanPerRecord() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val loopedStore = CountingStore()
        val looped = WarpLogRecordExporter(ReplicaId("device-1"), loopedStore, segmentOps = SEGMENT_OPS)
        records(BATCH).forEach { looped.export(it) }

        val batchedStore = CountingStore()
        val batched = WarpLogRecordExporter(ReplicaId("device-1"), batchedStore, segmentOps = SEGMENT_OPS)
        batched.export(records(BATCH))

        assertAll(
            {
                assertTrue(
                    batchedStore.writes * MIN_AMORTISATION <= loopedStore.writes,
                    "a batch of $BATCH must cost at least ${MIN_AMORTISATION}x fewer writes than the loop; " +
                        "batched=${batchedStore.writes} looped=${loopedStore.writes}",
                )
            },
            // Not just "fewer" — the loop's own cost is the two-writes-per-record shape.
            { assertTrue(loopedStore.writes >= BATCH, "the per-record path pays at least one write per record") },
        )
    }

    /**
     * `accepted` documents itself as "records durably taken", so a batch of k must
     * move it by k — not by one, and not by the number of calls.
     */
    @Test
    fun healthCountsRecordsTakenNotCallsMade() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val exporter = WarpLogRecordExporter(ReplicaId("device-1"), InMemoryDurableStore())
        exporter.export(records(BATCH))

        assertEquals(BATCH.toLong(), exporter.health.value.accepted)
    }

    /**
     * A merge takes no records through admission, so it must not move `accepted` — while
     * a successful merge write is still evidence the store is up, so it must clear the
     * failure streak. Nothing else catches a regression to counting the merge write.
     */
    @Test
    fun aSuccessfulMergeClearsTheFailureStreakWithoutCountingRecords() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val exporter = WarpLogRecordExporter(ReplicaId("device-1"), InMemoryDurableStore())
        val remote = WarpLogRecordExporter(ReplicaId("device-2"), InMemoryDurableStore())
        remote.export(records(3))

        exporter.merge(remote.snapshot())

        assertAll(
            { assertEquals(0L, exporter.health.value.accepted, "a merge takes no records through admission") },
            { assertEquals(3, exporter.snapshot().toList().size, "…but its records are in the log") },
            { assertEquals(0, exporter.health.value.consecutiveFailures) },
        )
    }

    /**
     * Dedup and the buffer cap are per-record decisions and stay that way inside a
     * batch: a repeat of an already-exported id is skipped, and neither a skip nor a
     * refusal counts towards `accepted`.
     */
    @Test
    fun aBatchDedupesWithinItselfAndAgainstWhatWasAlreadyExported() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val exporter = WarpLogRecordExporter(ReplicaId("device-1"), InMemoryDurableStore())
        exporter.export(record(0))
        exporter.export(listOf(record(0), record(1), record(1), record(2)))

        assertAll(
            { assertEquals(listOf("event 0", "event 1", "event 2"), exporter.snapshot().toList().map { it.body }) },
            { assertEquals(3L, exporter.health.value.accepted, "the two duplicates must not count as taken") },
        )
    }

    /**
     * A turn that admits nothing must write nothing.
     *
     * The single-record path returns early on a dedup hit, so a re-export costs zero
     * store writes — `export()`'s KDoc says so outright. A batched turn that ran
     * `pendingWrites` unconditionally would instead rewrite the whole active segment
     * (~123 KB at the production `segmentOps`) for a pure no-op, which is what an
     * anti-entropy caller re-exporting an already-exported page does on every pass.
     */
    @Test
    fun anAllDuplicateBatchWritesNothingAtAll() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val store = CountingStore()
        val exporter = WarpLogRecordExporter(ReplicaId("device-1"), store, segmentOps = SEGMENT_OPS)
        exporter.export(records(BATCH))
        val writesAfterFirstPass = store.writes

        exporter.export(records(BATCH))

        assertAll(
            { assertEquals(writesAfterFirstPass, store.writes, "a re-export of the same records must write nothing") },
            { assertEquals(BATCH.toLong(), exporter.health.value.accepted) },
        )
    }

    /**
     * Same property on the refusal path, and the one that bites hardest: a
     * `DROP_NEWEST` exporter at a full buffer accepts nothing ever again, so an
     * unconditional segment write would burn flash on every drain cycle for the life of
     * the process.
     */
    @Test
    fun aFullDropNewestBufferWritesNothingWhenItRefusesEverything() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val store = CountingStore()
        val exporter = WarpLogRecordExporter(
            ReplicaId("device-1"),
            store,
            maxRecords = CAP,
            bufferPolicy = BufferPolicy.DROP_NEWEST,
        )
        exporter.export(records(CAP))
        val writesWhileFilling = store.writes

        exporter.export(records(CAP + EXTRA).drop(CAP))

        assertEquals(writesWhileFilling, store.writes, "a wholly-refused turn must not rewrite the segment")
    }

    /**
     * A refused write must not cost the records the turn never got to.
     *
     * A failing turn keeps its own records — they are in `log` and `activeSegment`
     * before the write is attempted, so the next successful segment write carries them.
     * Abandoning the rest of the batch would lose records that looping the
     * single-record overload keeps, and a quota-bound store that refuses the large
     * segment write while accepting small ones holds that condition indefinitely.
     */
    @Test
    fun aFailedTurnDoesNotDiscardTheRestOfTheBatch() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val store = FailOnceStore()
        val exporter = WarpLogRecordExporter(ReplicaId("device-1"), store, segmentOps = SEGMENT_OPS)

        val result = exporter.export(records(BATCH))

        assertAll(
            { assertTrue(result is ExportResult.Failure, "the refused write must be reported") },
            {
                assertEquals(
                    records(BATCH).map { it.body },
                    exporter.snapshot().toList().map { it.body },
                    "every record in the batch must still be in the log, awaiting the next write",
                )
            },
        )
    }

    @Test
    fun aBatchLargerThanTheBufferEvictsTheOldestAndKeepsTheCap() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val exporter = WarpLogRecordExporter(ReplicaId("device-1"), InMemoryDurableStore(), maxRecords = CAP)
        exporter.export(records(CAP))
        exporter.export(records(CAP + EXTRA).drop(CAP))

        assertAll(
            { assertEquals(CAP, exporter.snapshot().toList().size) },
            {
                assertEquals(
                    (CAP until CAP + EXTRA).map { "event $it" }.takeLast(CAP),
                    exporter.snapshot().toList().map { it.body }.takeLast(minOf(CAP, EXTRA)),
                )
            },
        )
    }

    @Test
    fun aBatchUnderDropNewestRefusesTheOverflowAndInsertsNoOpForIt() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val exporter = WarpLogRecordExporter(
            ReplicaId("device-1"),
            InMemoryDurableStore(),
            maxRecords = CAP,
            bufferPolicy = BufferPolicy.DROP_NEWEST,
        )
        exporter.export(records(CAP + EXTRA))

        assertAll(
            { assertEquals(records(CAP).map { it.body }, exporter.snapshot().toList().map { it.body }) },
            { assertEquals(CAP.toLong(), exporter.health.value.accepted) },
            // DROP_NEWEST never authors a Remove — the op-log stays a downward-closed
            // prefix of this replica's own inserts (#2127).
            { assertEquals(0, exporter.snapshot().tombstones.size) },
        )
    }

    /**
     * A turn may not overflow the active segment, because `segmentOps` is documented
     * as the ceiling on how many bytes one export rewrites. A batch bigger than the
     * segment is split across turns rather than growing one segment past its budget.
     */
    @Test
    fun aBatchBiggerThanASegmentIsSplitAcrossTurnsRatherThanOverfillingOne() =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val store = InMemoryDurableStore()
            val exporter = WarpLogRecordExporter(ReplicaId("device-1"), store, segmentOps = SEGMENT_OPS)
            exporter.export(records(SEGMENT_OPS * 2))

            val recovered = WarpLogRecordExporter(ReplicaId("device-1"), store, segmentOps = SEGMENT_OPS)
            recovered.recover()

            assertAll(
                { assertEquals(SEGMENT_OPS * 2, exporter.snapshot().toList().size) },
                {
                    assertEquals(
                        exporter.snapshot().toList().map { it.body },
                        recovered.snapshot().toList().map { it.body },
                        "everything a split batch wrote must survive a restart",
                    )
                },
            )
        }

    @Test
    fun anEmptyBatchIsSuccessAndWritesNothing() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val store = CountingStore()
        val exporter = WarpLogRecordExporter(ReplicaId("device-1"), store)

        // Hoisted out of assertAll: its lambdas are `() -> Unit`, not suspending.
        val result = exporter.export(emptyList())

        assertAll(
            { assertEquals(ExportResult.Success, result) },
            { assertEquals(0, store.writes) },
            { assertEquals(0L, exporter.health.value.accepted) },
        )
    }

    private companion object {
        private const val RECORD_ID_BYTES = 8

        /** Small enough for wasmJs (#2183 — never thousands of exports in this module). */
        private const val BATCH = 40

        /** Two segments' worth at [SEGMENT_OPS], so the split path runs. */
        private const val SEGMENT_OPS = 16

        /** A buffer small enough that one batch overruns it. */
        private const val CAP = 8

        private const val EXTRA = 5

        /**
         * The floor the batched path must beat. Deliberately far below the ~128x a
         * production `segmentOps` gives: this asserts the *shape* (per-turn, not
         * per-record) on a deliberately tiny segment, not a tuning number.
         */
        private const val MIN_AMORTISATION = 4
    }
}
