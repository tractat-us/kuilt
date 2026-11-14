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
 * The rule now: a refused read (an ordinary throw or a cancellation the store mints) is retried on
 * every write turn. Until it succeeds, the turn returns [ExportResult.Failure] and writes and
 * deletes nothing. A *decode* failure is bad data rather than a refusal, so it still starts fresh
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
            val store = RefusingStore(backing, Call.READ, isIndex, times = 3, how = how)
            val exporter = exporterFor(store)

            exporter.recover()
            val mutationsBefore = mutations(backing)
            val whileUnread = listOf(exporter.export(record(100)), exporter.export(record(101)))
            val mutationsWhileUnread = mutations(backing) - mutationsBefore
            val healed = exporter.export(record(102))
            val live = exporter.snapshot().toList().map { it.body }
            val afterRestart = bodiesAfterRestart(backing)

            listOf<() -> Unit>(
                { assertEquals(30, persisted.size, "precondition [$how]: the fixture did not persist 30 records") },
                { assertEquals(3, store.refused, "[$how] the index read was refused ${store.refused} times, not 3") },
                { assertTrue(whileUnread.all { it is ExportResult.Failure }, "[$how] a turn ran over an unread index: $whileUnread") },
                { assertEquals(0, mutationsWhileUnread, "[$how] a turn wrote or deleted over an unread index") },
                { assertEquals(2L, exporter.health.value.failed, "[$how] the refused turns were not counted as failed") },
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

            exporter.recover()
            val mutationsBefore = mutations(backing)
            val merged = exporter.merge(remote)
            val cleared = exporter.clear()
            val mutationsWhileUnread = mutations(backing) - mutationsBefore
            val afterRestart = bodiesAfterRestart(backing)

            listOf<() -> Unit>(
                { assertEquals(3, store.refused, "[$how] the index read was refused ${store.refused} times, not 3") },
                { assertTrue(merged is ExportResult.Failure, "[$how] a merge ran over an unread index: $merged") },
                { assertTrue(cleared is ExportResult.Failure, "[$how] a clear ran over an unread index: $cleared") },
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

    // ---- The legacy blob: never delete what was not read ----

    @Test
    fun aRefusedLegacyReadFailsClosedAndNeverDeletesTheBlob() = runTest {
        val checks = Refusal.entries.flatMap { how ->
            val (backing, legacy) = storeWithALegacyBlob(0 until 12)
            val store = RefusingStore(backing, Call.READ, isLegacy, times = 2, how = how)
            val exporter = exporterFor(store)

            exporter.recover()
            val whileUnread = exporter.export(record(100))
            val legacyKeptWhileUnread = legacyKey.name in backing.keys()
            val healed = exporter.export(record(101))
            val afterRestart = bodiesAfterRestart(backing)

            listOf<() -> Unit>(
                { assertEquals(12, legacy.size, "precondition [$how]: the fixture holds no legacy records") },
                { assertEquals(2, store.refused, "[$how] the legacy read was refused ${store.refused} times, not 2") },
                { assertTrue(whileUnread is ExportResult.Failure, "[$how] a turn ran over an unread legacy blob: $whileUnread") },
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

            exporter.recover()
            val healed = exporter.export(record(100))
            val afterRestart = bodiesAfterRestart(backing)

            listOf<() -> Unit>(
                { assertEquals(1, store.refused, "[$how] the migration's index write was refused ${store.refused} times, not 1") },
                { assertEquals(ExportResult.Success, healed) },
                { assertEquals(legacy + "body-100", afterRestart, "[$how] the legacy records were lost to a refused migration") },
            )
        }
        assertAll(*checks.toTypedArray())
    }
}
