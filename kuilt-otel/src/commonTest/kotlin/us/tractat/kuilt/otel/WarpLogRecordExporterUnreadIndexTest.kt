package us.tractat.kuilt.otel

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.RgaId
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.store.StoreKey
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Pins that [WarpLogRecordExporter] **fails closed** while the store refuses the read that tells it
 * where its records are (#3384, ruling on PR #3385).
 *
 * The index names every segment, and the store has no way to list keys, so an exporter that has
 * not read it does not know what is on disk. Until this fix a refused read started the exporter
 * fresh: the next export wrote an index naming only segment 0 and overwrote segment 0, and every
 * other segment was orphaned. Measured on this fixture, 30 records went in and 1 came back.
 *
 * The rule now: a refused read (an ordinary throw or a cancellation the store mints) is retried by
 * the write turns, on a window that backs off by counted turns (#3404, pinned by
 * [WarpLogRecordExporterRefusedReadBackoffTest]). Until it succeeds, a turn returns
 * [ExportResult.Failure] and writes and deletes nothing, whether it read or skipped the read. A *decode* failure is bad data rather than a refusal, so it still starts fresh
 * and the exporter is never dead on it (#1860).
 *
 * [RefusingStore] counts every refusal, and each arm asserts the rig fired exactly as often as the
 * arm needs: a rig that never fired is green by absence.
 */
class WarpLogRecordExporterUnreadIndexTest {

    private val replica = ReplicaId("A")

    private fun recordId(id: Int): ByteString = ByteString(ByteArray(8) { i -> (id shr (8 * i)).toByte() })

    private fun record(id: Int) = LogRecord(
        recordId = recordId(id),
        body = "body-$id",
        observedEpochNanos = 1_700_000_000_000_000_000L,
    )

    /** Room for every record, and a segment small enough that 30 records span several keys. */
    private fun exporterFor(store: DurableStore) = WarpLogRecordExporter(
        replica = replica,
        store = store,
        maxRecords = 100,
        bufferPolicy = BufferPolicy.DROP_OLDEST,
        segmentOps = 8,
    )

    private suspend fun bodiesAfterRestart(store: DurableStore): List<String?> =
        exporterFor(store).also { it.recover() }.snapshot().toList().map { it.body }

    /** Every store mutation [store] has taken, so an arm can say "nothing was written or deleted". */
    private fun mutations(store: RecordingStore): Int = store.operations().count { it.kind != StoreOpKind.READ }

    /** How a refused call fails: the two shapes a real store produces. */
    enum class Refusal {
        /** An ordinary throw: a quota refusal, an I/O error. The exporter does not look at the type. */
        THROW,

        /** A `TimeoutCancellationException` from the store's own `withTimeout`, at a live caller. */
        MINT,
    }

    private enum class Call { READ, WRITE }

    /**
     * Delegates to [backing], but refuses the next [times] calls of [call] whose key [matches], in
     * the shape [how]. [refused] counts them: a precondition guard, never the assertion under test.
     */
    private class RefusingStore(
        private val backing: DurableStore,
        private val call: Call,
        private val matches: (StoreKey) -> Boolean,
        times: Int,
        private val how: Refusal,
    ) : DurableStore {
        private val lock = reentrantLock()
        private var remaining = times
        private var refusedCount = 0

        val refused: Int get() = lock.withLock { refusedCount }

        private suspend fun intercept(call: Call, key: StoreKey) {
            val refuse = lock.withLock {
                val refuse = call == this.call && matches(key) && remaining > 0
                if (refuse) {
                    remaining--
                    refusedCount++
                }
                refuse
            }
            if (!refuse) return
            when (how) {
                Refusal.THROW -> throw IllegalStateException("store refused ${call.name.lowercase()} of ${key.name}")
                Refusal.MINT -> withTimeout(1.milliseconds) { awaitCancellation() }
            }
        }

        override suspend fun read(key: StoreKey): ByteArray? {
            intercept(Call.READ, key)
            return backing.read(key)
        }

        override suspend fun write(key: StoreKey, bytes: ByteArray) {
            intercept(Call.WRITE, key)
            backing.write(key, bytes)
        }

        override suspend fun delete(key: StoreKey): Unit = backing.delete(key)
    }

    private val isIndex: (StoreKey) -> Boolean = { it == INDEX_KEY_FOR_TEST }
    private val legacyKey = StoreKey(LOG_KEY_PREFIX_FOR_TEST)
    private val isLegacy: (StoreKey) -> Boolean = { it == legacyKey }

    /** A store holding 30 records across several segments, and those records' bodies. */
    private suspend fun storeWithThirtyRecords(): Pair<RecordingStore, List<String?>> {
        val backing = RecordingStore()
        val writer = exporterFor(backing)
        repeat(30) { writer.export(record(it)) }
        return backing to writer.snapshot().toList().map { it.body }
    }

    /** A store holding only the pre-#1860 single blob, as a first start on the segmented format finds it. */
    private fun storeWithALegacyBlob(ids: IntRange): Pair<RecordingStore, List<String?>> {
        var rga = Rga.empty<LogRecord>()
        var tail = RgaId.HEAD
        for (id in ids) {
            val (next, op) = rga.insertAfter(replica = replica, after = tail, value = record(id))
            rga = next
            tail = op.id
        }
        val backing = RecordingStore()
        backing.putRaw(legacyKey, Cbor { alwaysUseByteString = true }.encodeToByteArray(Rga.wireSerializer(LogRecord.serializer()), rga))
        return backing to rga.toList().map { it.body }
    }

    // ---- The index read ----

    @Test
    fun aRefusedIndexReadFailsEveryTurnClosedUntilItHealsAndCostsNoRecords() = runTest {
        val checks = Refusal.entries.flatMap { how ->
            val (backing, persisted) = storeWithThirtyRecords()
            val store = RefusingStore(backing, Call.READ, isIndex, times = 2, how = how)
            val exporter = exporterFor(store)

            exporter.recover() // refused 1: the next turn skips the read (#3404)
            val mutationsBefore = mutations(backing)
            // A skipped turn, then a turn whose read is refused again: a window of 3 more skips.
            val whileUnread = listOf(exporter.export(record(100)), exporter.export(record(101))) +
                List(3) { exporter.export(record(110 + it)) }
            val mutationsWhileUnread = mutations(backing) - mutationsBefore
            val healed = exporter.export(record(102))
            val live = exporter.snapshot().toList().map { it.body }
            val afterRestart = bodiesAfterRestart(backing)

            listOf<() -> Unit>(
                { assertEquals(30, persisted.size, "precondition [$how]: the fixture did not persist 30 records") },
                { assertEquals(2, store.refused, "[$how] the index read was refused ${store.refused} times, not 2") },
                { assertTrue(whileUnread.all { it is ExportResult.Failure }, "[$how] a turn ran over an unread index: $whileUnread") },
                { assertEquals(0, mutationsWhileUnread, "[$how] a turn wrote or deleted over an unread index") },
                { assertEquals(5L, exporter.health.value.failed, "[$how] the refused turns were not counted as failed") },
                { assertTrue(exporter.health.value.recoveryFailed, "[$how] the refused read was not reported") },
                { assertEquals(ExportResult.Success, healed, "[$how] the turn after the read healed failed") },
                { assertEquals(persisted + "body-102", live, "[$how] the healed read did not install the persisted log") },
                { assertEquals(persisted + "body-102", afterRestart, "[$how] records were lost to the unread index") },
            )
        }
        assertAll(*checks.toTypedArray())
    }

    @Test
    fun aMergeAndAClearOverAnUnreadIndexWriteAndDeleteNothing() = runTest {
        val checks = Refusal.entries.flatMap { how ->
            val (backing, persisted) = storeWithThirtyRecords()
            val store = RefusingStore(backing, Call.READ, isIndex, times = 3, how = how)
            val exporter = exporterFor(store)
            val remote = WarpLogRecordExporter(replica = ReplicaId("B"), store = InMemoryDurableStore())
                .also { it.export(record(500)) }
                .snapshot()

            exporter.recover() // refused 1: the next turn skips the read (#3404)
            val mutationsBefore = mutations(backing)
            // Each of merge and clear once inside a window and once reading: skip, refused 2 and
            // a window of 3, three skips, refused 3.
            val merged = listOf(exporter.merge(remote), exporter.merge(remote))
            val cleared = List(3) { exporter.clear() } + exporter.clear()
            val mutationsWhileUnread = mutations(backing) - mutationsBefore
            val afterRestart = bodiesAfterRestart(backing)

            listOf<() -> Unit>(
                { assertEquals(3, store.refused, "[$how] the index read was refused ${store.refused} times, not 3") },
                { assertTrue(merged.all { it is ExportResult.Failure }, "[$how] a merge ran over an unread index: $merged") },
                { assertTrue(cleared.all { it is ExportResult.Failure }, "[$how] a clear ran over an unread index: $cleared") },
                { assertEquals(0, mutationsWhileUnread, "[$how] a merge or clear wrote or deleted over an unread index") },
                { assertEquals(persisted, afterRestart, "[$how] records were lost to the unread index") },
            )
        }
        assertAll(*checks.toTypedArray())
    }

    @Test
    fun anUndecodableIndexStillStartsFreshAtOnce() = runTest {
        // The control: bad bytes are not a refusal, so the exporter must not fail closed on them
        // forever. It starts fresh on the first turn and never reads the index again.
        val backing = RecordingStore()
        backing.putRaw(INDEX_KEY_FOR_TEST, byteArrayOf(0x7f, 0x00, 0x13))
        val exporter = exporterFor(backing)

        exporter.recover()
        val readsAfterRecover = backing.operations().count { it.kind == StoreOpKind.READ && it.key == INDEX_KEY_FOR_TEST }
        val exported = exporter.export(record(1))
        val indexReads = backing.operations().count { it.kind == StoreOpKind.READ && it.key == INDEX_KEY_FOR_TEST }
        val afterRestart = bodiesAfterRestart(backing)

        assertAll(
            { assertEquals(1, readsAfterRecover, "precondition: recover never read the index") },
            { assertTrue(exporter.health.value.recoveryFailed, "the undecodable index was not reported") },
            { assertEquals(ExportResult.Success, exported, "an undecodable index left the exporter dead") },
            { assertEquals(1, indexReads, "an undecodable index was retried as if the store had refused it") },
            { assertEquals(listOf("body-1"), afterRestart) },
        )
    }

    // ---- The active segment: never write over what was not read ----

    /** The active segment's key in [store], from the index it holds. */
    private suspend fun activeKeyOf(store: RecordingStore): StoreKey =
        StoreKey(segmentKeyForTest(decodeIndexForTest(requireNotNull(store.read(INDEX_KEY_FOR_TEST))).active))

    @Test
    fun aRefusedActiveSegmentReadFailsEveryTurnClosedUntilItHeals() = runTest {
        // The active segment is the key the next export writes, so an unread one cannot be
        // installed as empty: that export wrote over it, and a probe lost 6 of 30. Nor can it be
        // sealed and a new one opened, because the ids minted next would reuse the ones it holds.
        // So it fails closed, as the index does, and is read before anything else: a refused read
        // deletes nothing and costs a turn two reads, the index and the active segment.
        val checks = Refusal.entries.flatMap { how ->
            val (backing, persisted) = storeWithThirtyRecords()
            val activeKey = activeKeyOf(backing)
            val activeBefore = requireNotNull(backing.read(activeKey)) { "precondition: the active segment was never written" }
            val store = RefusingStore(backing, Call.READ, { it == activeKey }, times = 2, how = how)
            val exporter = exporterFor(store)

            val mutationsBefore = mutations(backing)
            exporter.recover() // refused 1: the next turn skips the read (#3404)
            val readsBefore = backing.reads()
            // A skipped turn, then a turn whose read is refused again: a window of 3 more skips.
            val whileRefused = listOf(exporter.export(record(100)), exporter.export(record(101)))
            // The skipped turn reads nothing, and the refused active read never reaches `backing`,
            // so the refused turn shows one read: the index.
            val readsWhileRefused = backing.reads() - readsBefore
            val windowed = List(3) { exporter.export(record(110 + it)) }
            val mutationsWhileRefused = mutations(backing) - mutationsBefore
            val failedWhileRefused = exporter.health.value.failed
            val activeWhileRefused = backing.read(activeKey)
            val healed = exporter.export(record(102))
            val afterRestart = bodiesAfterRestart(backing)

            listOf<() -> Unit>(
                { assertEquals(2, store.refused, "[$how] the active segment read was refused ${store.refused} times, not 2") },
                { assertTrue((whileRefused + windowed).all { it is ExportResult.Failure }, "[$how] a turn ran over an unread active segment: $whileRefused $windowed") },
                { assertEquals(0, mutationsWhileRefused, "[$how] recovery or a turn wrote or deleted over an unread active segment") },
                { assertEquals(1, readsWhileRefused, "[$how] a refused turn read more than the index and the active segment") },
                { assertEquals(5L, failedWhileRefused, "[$how] the refused turns were not counted as failed") },
                { assertTrue(activeBefore.contentEquals(activeWhileRefused), "[$how] the unread active segment was written over") },
                { assertEquals(ExportResult.Success, healed, "[$how] the turn after the read healed failed") },
                { assertEquals(persisted + "body-102", afterRestart, "[$how] records were lost to the unread active segment") },
            )
        }
        assertAll(*checks.toTypedArray())
    }

    @Test
    fun anUndecodableActiveSegmentIsWrittenOverAndCostsOnlyItsOwnRecords() = runTest {
        // The control: bad bytes are not a refusal. Nothing in them is readable, so writing over
        // them loses nothing more, and the exporter keeps working at once.
        val (backing, persisted) = storeWithThirtyRecords()
        val activeKey = activeKeyOf(backing)
        val activeBodies = decodeSegmentForTest(requireNotNull(backing.read(activeKey))).toList().map { it.body }
        backing.putRaw(activeKey, byteArrayOf(0x7f, 0x00, 0x13))
        val exporter = exporterFor(backing)

        exporter.recover()
        val exported = exporter.export(record(100))
        val afterRestart = bodiesAfterRestart(backing)

        assertAll(
            { assertTrue(activeBodies.isNotEmpty(), "precondition: the active segment held no records") },
            { assertEquals(ExportResult.Success, exported, "an undecodable active segment stopped the export") },
            { assertEquals(persisted - activeBodies.toSet() + "body-100", afterRestart) },
        )
    }


    // ---- The legacy blob: never delete what was not read ----

    @Test
    fun aRefusedLegacyReadFailsClosedAndNeverDeletesTheBlob() = runTest {
        val checks = Refusal.entries.flatMap { how ->
            val (backing, legacy) = storeWithALegacyBlob(0 until 12)
            val store = RefusingStore(backing, Call.READ, isLegacy, times = 2, how = how)
            val exporter = exporterFor(store)

            exporter.recover() // refused 1: the next turn skips the read (#3404)
            // A skipped turn, then a turn whose read is refused again: a window of 3 more skips.
            val whileUnread = listOf(exporter.export(record(100)), exporter.export(record(102))) +
                List(3) { exporter.export(record(110 + it)) }
            val legacyKeptWhileUnread = legacyKey.name in backing.keys()
            val healed = exporter.export(record(101))
            val afterRestart = bodiesAfterRestart(backing)

            listOf<() -> Unit>(
                { assertEquals(12, legacy.size, "precondition [$how]: the fixture holds no legacy records") },
                { assertEquals(2, store.refused, "[$how] the legacy read was refused ${store.refused} times, not 2") },
                { assertTrue(whileUnread.all { it is ExportResult.Failure }, "[$how] a turn ran over an unread legacy blob: $whileUnread") },
                { assertTrue(legacyKeptWhileUnread, "[$how] the unread legacy blob was deleted") },
                { assertEquals(ExportResult.Success, healed) },
                { assertEquals(legacy + "body-101", afterRestart, "[$how] the legacy records were lost to the unread blob") },
            )
        }
        assertAll(*checks.toTypedArray())
    }

    @Test
    fun aRefusedMigrationWriteFailsClosedAndTheNextTurnMigrates() = runTest {
        // The migration copies the blob to segment 0 and then writes the index. A start-fresh after
        // a refused index write had the next export write its own index naming segment 0, over the
        // copy, and the start after that swept the legacy key as superseded.
        val checks = Refusal.entries.flatMap { how ->
            val (backing, legacy) = storeWithALegacyBlob(0 until 12)
            val store = RefusingStore(backing, Call.WRITE, isIndex, times = 1, how = how)
            val exporter = exporterFor(store)

            exporter.recover() // refused 1: the next turn skips the read (#3404)
            val skipped = exporter.export(record(99))
            val healed = exporter.export(record(100))
            val afterRestart = bodiesAfterRestart(backing)

            listOf<() -> Unit>(
                { assertEquals(1, store.refused, "[$how] the migration's index write was refused ${store.refused} times, not 1") },
                { assertTrue(skipped is ExportResult.Failure, "[$how] the turn inside the window ran: $skipped") },
                { assertEquals(ExportResult.Success, healed) },
                { assertEquals(legacy + "body-100", afterRestart, "[$how] the legacy records were lost to a refused migration") },
            )
        }
        assertAll(*checks.toTypedArray())
    }

    /**
     * Delegates to [backing], but refuses the next [times] deletes of the legacy key, every one by
     * default: a store whose delete keeps failing.
     */
    private class KeepsLegacyStore(
        private val backing: DurableStore,
        private val legacy: StoreKey,
        times: Int = Int.MAX_VALUE,
    ) : DurableStore {
        private val lock = reentrantLock()
        private var remaining = times
        private var refusedCount = 0

        val refusedDeletes: Int get() = lock.withLock { refusedCount }

        override suspend fun read(key: StoreKey): ByteArray? = backing.read(key)

        override suspend fun write(key: StoreKey, bytes: ByteArray): Unit = backing.write(key, bytes)

        override suspend fun delete(key: StoreKey) {
            val refuse = lock.withLock {
                val refuse = key == legacy && remaining > 0
                if (refuse) {
                    remaining--
                    refusedCount++
                }
                refuse
            }
            if (refuse) throw IllegalStateException("store refused delete of ${key.name}")
            backing.delete(key)
        }
    }

    /**
     * A store whose migration copied a 12-record legacy blob and wrote an index but could not
     * delete the blob, and whose index has since stopped decoding. Returns the store and the
     * legacy records' bodies.
     */
    private suspend fun storeWithAKeptLegacyBlobBehindAnUndecodableIndex(): Pair<RecordingStore, List<String?>> {
        val (backing, legacy) = storeWithALegacyBlob(0 until 12)
        exporterFor(KeepsLegacyStore(backing, legacyKey)).recover()
        check(legacyKey.name in backing.keys()) { "precondition: the migration deleted the legacy key" }
        check(INDEX_KEY_FOR_TEST.name in backing.keys()) { "precondition: the migration wrote no index" }
        backing.putRaw(INDEX_KEY_FOR_TEST, byteArrayOf(0x7f, 0x00, 0x13))
        return backing to legacy
    }

    @Test
    fun anUndecodableIndexFallsBackToALegacyBlobItsMigrationCouldNotDelete() = runTest {
        // The migration copied the blob to segment 0 and wrote the index, and the delete of the
        // legacy key was refused, so the blob outlived it. With the index now undecodable, a start
        // fresh wrote its own segment 0 and index over the copy, and the blob was never read again.
        val (backing, legacy) = storeWithALegacyBlob(0 until 12)
        val store = KeepsLegacyStore(backing, legacyKey)
        exporterFor(store).recover()
        val legacyOutlivedMigration = legacyKey.name in backing.keys()
        val migrated = INDEX_KEY_FOR_TEST.name in backing.keys()
        backing.putRaw(INDEX_KEY_FOR_TEST, byteArrayOf(0x7f, 0x00, 0x13))

        val exporter = exporterFor(store)
        exporter.recover()
        val exported = exporter.export(record(100))
        val afterRestart = bodiesAfterRestart(backing)

        assertAll(
            { assertEquals(12, legacy.size, "precondition: the fixture holds no legacy records") },
            { assertTrue(migrated, "precondition: the migration wrote no index") },
            { assertTrue(legacyOutlivedMigration, "precondition: the migration deleted the legacy key") },
            { assertTrue(store.refusedDeletes >= 1, "precondition: the rig refused no delete") },
            { assertTrue(exporter.health.value.recoveryFailed, "the undecodable index was not reported") },
            { assertEquals(ExportResult.Success, exported) },
            { assertEquals(legacy + "body-100", afterRestart, "the legacy records were lost behind an undecodable index") },
        )
    }

    @Test
    fun anUndecodableIndexOverARefusedLegacyReadFailsClosed() = runTest {
        // Starting fresh here wrote segment 0 and a decodable index, and the next start, finding
        // that index, swept a legacy blob no run had read. So a refused legacy read behind an
        // undecodable index fails closed, as it does behind no index at all.
        val checks = Refusal.entries.flatMap { how ->
            val (backing, legacy) = storeWithAKeptLegacyBlobBehindAnUndecodableIndex()
            val store = RefusingStore(backing, Call.READ, isLegacy, times = 2, how = how)
            val exporter = exporterFor(store)

            val mutationsBefore = mutations(backing)
            exporter.recover() // refused 1: the next turn skips the read (#3404)
            // A skipped turn, then a turn whose read is refused again: a window of 3 more skips.
            val whileUnread = listOf(exporter.export(record(100)), exporter.export(record(102))) +
                List(3) { exporter.export(record(110 + it)) }
            val mutationsWhileUnread = mutations(backing) - mutationsBefore
            val legacyKeptWhileUnread = legacyKey.name in backing.keys()
            val healed = exporter.export(record(101))
            val afterRestart = bodiesAfterRestart(backing)

            listOf<() -> Unit>(
                { assertEquals(12, legacy.size, "precondition [$how]: the fixture holds no legacy records") },
                { assertEquals(2, store.refused, "[$how] the legacy read was refused ${store.refused} times, not 2") },
                { assertTrue(whileUnread.all { it is ExportResult.Failure }, "[$how] a turn ran over an unread legacy blob: $whileUnread") },
                { assertEquals(0, mutationsWhileUnread, "[$how] a turn wrote or deleted over an unread legacy blob") },
                { assertTrue(legacyKeptWhileUnread, "[$how] the unread legacy blob was deleted") },
                { assertEquals(ExportResult.Success, healed, "[$how] the turn after the read healed failed") },
                { assertEquals(legacy + "body-101", afterRestart, "[$how] the legacy records were lost to the unread blob") },
            )
        }
        assertAll(*checks.toTypedArray())
    }

    @Test
    fun anUndecodableIndexOverAnUndecodableLegacyBlobStartsFresh() = runTest {
        // The control: bad bytes in both keys are bad data, not a refusal, so the exporter starts
        // fresh at once rather than failing closed on them forever.
        val (backing, _) = storeWithAKeptLegacyBlobBehindAnUndecodableIndex()
        backing.putRaw(legacyKey, byteArrayOf(0x7f, 0x00, 0x13))
        val exporter = exporterFor(backing)

        exporter.recover()
        val exported = exporter.export(record(100))
        val afterRestart = bodiesAfterRestart(backing)

        assertAll(
            { assertTrue(exporter.health.value.recoveryFailed, "the undecodable index was not reported") },
            { assertEquals(ExportResult.Success, exported, "an undecodable legacy blob left the exporter dead") },
            { assertEquals(listOf("body-100"), afterRestart) },
        )
    }

    @Test
    fun aClearDeletesTheLegacyBlobSoAnUndecodableIndexCannotResurrectIt() = runTest {
        // The fallback loads the legacy blob verbatim, so a blob that outlived its migration would
        // bring back, behind a later undecodable index, records this replica was told to forget.
        // So a clear deletes it too, and fails while the store refuses that delete.
        val (backing, legacy) = storeWithALegacyBlob(0 until 12)
        // One refused delete for the migration's sweep, one for the first clear.
        val store = KeepsLegacyStore(backing, legacyKey, times = 2)
        val exporter = exporterFor(store)
        exporter.recover()
        val legacyOutlivedMigration = legacyKey.name in backing.keys()
        val recovered = exporter.snapshot().toList().map { it.body }

        val refusedClear = exporter.clear()
        val legacyKeptByRefusedClear = legacyKey.name in backing.keys()
        val retriedClear = exporter.clear()
        val legacyAfterClear = legacyKey.name in backing.keys()
        backing.putRaw(INDEX_KEY_FOR_TEST, byteArrayOf(0x7f, 0x00, 0x13))
        val afterRestart = bodiesAfterRestart(backing)

        assertAll(
            { assertEquals(legacy, recovered, "precondition: the migration did not recover the legacy records") },
            { assertTrue(legacyOutlivedMigration, "precondition: the migration deleted the legacy key") },
            { assertEquals(2, store.refusedDeletes, "the legacy delete was refused ${store.refusedDeletes} times, not 2") },
            { assertTrue(refusedClear is ExportResult.Failure, "a clear whose legacy delete was refused reported $refusedClear") },
            { assertTrue(legacyKeptByRefusedClear, "precondition: the refused clear deleted the legacy key") },
            { assertEquals(ExportResult.Success, retriedClear, "the retried clear failed") },
            { assertTrue(!legacyAfterClear, "the clear left the legacy blob in the store") },
            { assertEquals(emptyList(), afterRestart, "cleared records came back behind an undecodable index") },
        )
    }

    @Test
    fun theCallersOwnCancellationInEachMigrationCallStillPropagates() = runTest {
        // The control for the two legacy arms: without `ensureActive` each guard would read the
        // caller's own cancellation as a refusal, and recover would return. The index read's
        // control is in WarpLogRecordExporterStoreMintedCancellationTest.
        val rules = listOf(
            "legacy read" to Pair(StoreCall.READ, isLegacy),
            "segment copy" to Pair(StoreCall.WRITE) { key: StoreKey -> key.name == segmentKeyForTest(0) },
            "index write" to Pair(StoreCall.WRITE, isIndex),
        )
        val outcomes = rules.map { (name, rule) ->
            val (backing, _) = storeWithALegacyBlob(0 until 3)
            val store = CancellationScriptedStore(backing)
            store.arm(rule.first, StoreAct.PARK, rule.second)
            val returned = cancelInside(store) { exporterFor(store).recover() }
            Triple(name, store, returned)
        }

        assertAll(
            *outcomes.flatMap { (name, store, returned) ->
                listOf(
                    { assertEquals(1, store.parked, "the rig never parked the $name") },
                    { assertEquals(1, store.cancelled, "the cancellation never landed inside the $name") },
                    { assertEquals(null, returned, "recover cancelled in its $name returned instead of being cancelled") },
                )
            }.toTypedArray(),
        )
    }
}
