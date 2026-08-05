package us.tractat.kuilt.otel

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cover for [WarpLogRecordExporter.health] — the out-of-band signal that lets a
 * dead exporter report that it is dead (#1860).
 *
 * `export()` already returned [ExportResult.Failure] on a failed durable write,
 * but every caller on the logging path discards it: `LogCapture.capture()` hands
 * it back to appenders whose framework signatures are `void`. So a component
 * whose entire purpose is post-hoc diagnosis had no way to answer the one
 * question that mattered in the field — *"have I accepted anything since process
 * start?"*
 *
 * [ExporterHealth] carries counters only, deliberately no timestamp: this repo
 * treats time as an injected dependency and the exporter has no `Clock`.
 */
class WarpLogRecordExporterHealthTest {

    private val replica = ReplicaId("A")

    private fun record(id: Byte) = LogRecord(
        recordId = ByteString(ByteArray(8) { id }),
        body = "log message",
        severityNumber = 9,
        observedEpochNanos = 1_000L + id,
    )

    /** A store whose [write] always throws; reads report an empty store. */
    private class WriteFailsStore : DurableStore {
        override suspend fun read(key: StoreKey): ByteArray? = null
        override suspend fun write(key: StoreKey, bytes: ByteArray): Unit =
            throw IllegalStateException("simulated durable-write failure")
        override suspend fun delete(key: StoreKey) = Unit
    }

    /** A store whose [read] throws, modelling the unreadable accumulated entry. */
    private class ReadFailsStore : DurableStore {
        override suspend fun read(key: StoreKey): ByteArray? =
            throw IllegalStateException("simulated unreadable store entry")
        override suspend fun write(key: StoreKey, bytes: ByteArray) = Unit
        override suspend fun delete(key: StoreKey) = Unit
    }

    private class SwitchableStore : DurableStore {
        var failWrites: Boolean = false
        private val entries = mutableMapOf<StoreKey, ByteArray>()

        override suspend fun read(key: StoreKey): ByteArray? = entries[key]

        override suspend fun write(key: StoreKey, bytes: ByteArray) {
            if (failWrites) throw IllegalStateException("simulated durable-write failure")
            entries[key] = bytes
        }

        override suspend fun delete(key: StoreKey) {
            entries.remove(key)
        }
    }

    @Test
    fun healthStartsClean() = runTest {
        val exporter = WarpLogRecordExporter(replica = replica, store = InMemoryDurableStore())
        val health = exporter.health.value
        assertAll(
            { assertEquals(0L, health.accepted) },
            { assertEquals(0L, health.failed) },
            { assertEquals(0, health.consecutiveFailures) },
            { assertNull(health.lastFailure) },
            { assertEquals(false, health.recoveryFailed) },
        )
    }

    @Test
    fun healthCountsDurableWrites() = runTest {
        val exporter = WarpLogRecordExporter(replica = replica, store = InMemoryDurableStore())
        exporter.export(record(1))
        exporter.export(record(2))
        assertAll(
            { assertEquals(2L, exporter.health.value.accepted) },
            { assertEquals(0L, exporter.health.value.failed) },
        )
    }

    /**
     * The load-bearing semantic: [ExporterHealth.accepted] counts **durable
     * writes**, not `Success` returns.
     *
     * A dedup hit returns [ExportResult.Success] without touching the store. If
     * it counted, a pathological all-dedup state would report a healthy,
     * climbing `accepted` while nothing was ever written to disk — reproducing
     * the very false-green this surface exists to prevent.
     */
    @Test
    fun aDedupNoOpDoesNotCountAsADurableWrite() = runTest {
        val exporter = WarpLogRecordExporter(replica = replica, store = InMemoryDurableStore())
        val r = record(1)
        exporter.export(r)
        exporter.export(r)
        exporter.export(r)
        assertEquals(1L, exporter.health.value.accepted, "only the first export wrote")
    }

    @Test
    fun healthReportsAnExporterThatHasAcceptedNothing() = runTest {
        val exporter = WarpLogRecordExporter(replica = replica, store = WriteFailsStore())

        val result = exporter.export(record(1))

        val health = exporter.health.value
        assertAll(
            { assertIs<ExportResult.Failure>(result) },
            // The question the field failure could not answer.
            { assertEquals(0L, health.accepted, "nothing has been accepted since process start") },
            { assertEquals(1L, health.failed) },
            { assertEquals(1, health.consecutiveFailures) },
            { assertIs<IllegalStateException>(health.lastFailure) },
        )
    }

    @Test
    fun consecutiveFailuresAccumulateAndResetOnSuccess() = runTest {
        val store = SwitchableStore()
        val exporter = WarpLogRecordExporter(replica = replica, store = store)

        store.failWrites = true
        exporter.export(record(1))
        exporter.export(record(2))
        assertEquals(2, exporter.health.value.consecutiveFailures)

        store.failWrites = false
        exporter.export(record(3))

        val health = exporter.health.value
        assertAll(
            { assertEquals(0, health.consecutiveFailures, "a success clears the streak") },
            { assertEquals(1L, health.accepted) },
            { assertEquals(2L, health.failed, "the cumulative failure count is retained") },
            // Retained as forensics: recovering does not erase what went wrong.
            { assertIs<IllegalStateException>(health.lastFailure) },
        )
    }

    @Test
    fun healthRecordsARecoveryFailure() = runTest {
        val exporter = WarpLogRecordExporter(replica = replica, store = ReadFailsStore())

        exporter.recover()

        val health = exporter.health.value
        assertAll(
            { assertTrue(health.recoveryFailed, "the unreadable store must be visible") },
            { assertIs<IllegalStateException>(health.lastFailure) },
            // A failed recovery is not a failed export — the counters stay clean.
            { assertEquals(0L, health.failed) },
            { assertEquals(0L, health.accepted) },
        )
    }

    @Test
    fun aCleanRecoveryLeavesHealthClean() = runTest {
        val store = InMemoryDurableStore()
        WarpLogRecordExporter(replica = replica, store = store).export(record(1))

        val exporter = WarpLogRecordExporter(replica = replica, store = store)
        exporter.recover()

        assertAll(
            { assertEquals(false, exporter.health.value.recoveryFailed) },
            { assertNull(exporter.health.value.lastFailure) },
        )
    }

    @Test
    fun healthReportsAMergeFailure() = runTest {
        val source = WarpLogRecordExporter(replica = ReplicaId("B"), store = InMemoryDurableStore())
        source.export(record(1))

        val exporter = WarpLogRecordExporter(replica = replica, store = WriteFailsStore())
        val result = exporter.merge(source.snapshot())

        assertAll(
            { assertIs<ExportResult.Failure>(result) },
            { assertEquals(1L, exporter.health.value.failed) },
            { assertEquals(0L, exporter.health.value.accepted) },
        )
    }

    @Test
    fun healthIsObservableAsAFlow() = runTest {
        val exporter = WarpLogRecordExporter(replica = replica, store = WriteFailsStore())
        // A monitor collects this to alarm on a stalled exporter; the point of the
        // surface is that it is observable without polling export()'s return value.
        assertEquals(0L, exporter.health.value.failed)
        exporter.export(record(1))
        assertEquals(1L, exporter.health.value.failed)
    }
}
