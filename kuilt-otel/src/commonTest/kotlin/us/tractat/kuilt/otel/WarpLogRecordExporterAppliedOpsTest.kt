package us.tractat.kuilt.otel

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.RgaOp
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What [WarpLogRecordExporter] publishes to an [AppliedOpSink], and — just as load-bearing — what
 * it does not.
 *
 * No archive anywhere near this file. The sink is a recorder, so these pin the *exporter's* half of
 * the contract on its own: which paths publish, in what order relative to the durable write, and
 * that a badly behaved sink cannot reach back through it. The end-to-end scenario lives in
 * [GossipedRecordsReachTheArchiveTest].
 */
class WarpLogRecordExporterAppliedOpsTest {

    private val phone = ReplicaId("phone")
    private val server = ReplicaId("server")

    private fun recordId(id: Int): ByteString =
        ByteString(ByteArray(8) { i -> (id.toLong() shr (8 * i)).toByte() })

    private fun record(id: Int) = LogRecord(recordId = recordId(id), body = "record-$id")

    private fun exporter(
        store: DurableStore,
        replica: ReplicaId = phone,
        maxRecords: Int = DEFAULT_MAX_LOG_RECORDS,
        sink: AppliedOpSink = AppliedOpSink.Discarding,
    ) = WarpLogRecordExporter(replica = replica, store = store, maxRecords = maxRecords, appliedOps = sink)

    /** Every op published, flattened, in publication order. */
    private class Recorder : AppliedOpSink {
        val batches = mutableListOf<List<RgaOp<LogRecord>>>()
        val ops: List<RgaOp<LogRecord>> get() = batches.flatten()

        override suspend fun published(ops: List<RgaOp<LogRecord>>) {
            batches += ops
        }
    }

    // ── The local path ────────────────────────────────────────────────────────

    /**
     * An export publishes the operations it applied — the inserts, and the tombstones an eviction
     * minted, in the order they were applied.
     *
     * The eviction half is why `maxRecords` is tiny here. A tombstone is *content*: a consumer
     * replaying the stream has to see the same removal the live replica saw, or its reconstruction
     * shows a record as live that this replica dropped.
     *
     * **Mutation receipt:** dropping the `removes` from `applyTurn`'s return value reds the
     * `RgaOp.Remove` assertion; deleting the `publishApplied(applied)` call reds all of them.
     */
    @Test
    fun anExportPublishesTheInsertsAndTheTombstonesItApplied() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val recorder = Recorder()
        val exporter = exporter(InMemoryDurableStore(), maxRecords = 2, sink = recorder)

        repeat(3) { i -> exporter.export(record(i)) }

        val inserted = recorder.ops.filterIsInstance<RgaOp.Insert<LogRecord>>().map { it.value.body }
        assertAll(
            { assertEquals(listOf("record-0", "record-1", "record-2"), inserted, "every record's insert") },
            {
                assertEquals(
                    1,
                    recorder.ops.count { it is RgaOp.Remove },
                    "and the tombstone the cap minted when the third record evicted the first",
                )
            },
            {
                assertTrue(
                    recorder.ops.none { it is RgaOp.Compact },
                    "never a compaction record — the sink exists so something else can NOT forget",
                )
            },
        )
    }

    // ── The merge path: the one that carries the module ───────────────────────

    /**
     * **A merge publishes the remote replica's whole operation log**, which is the only way a sink
     * ever sees history that arrived by gossip.
     *
     * A merge is a state join; it produces no operation stream to tee. So an exporter that
     * published only on [WarpLogRecordExporter.export] would hand a sink this replica's own
     * telemetry and none of anybody else's — the exact shape that leaves a server-side archive
     * holding zero phone records.
     *
     * **Mutation receipt:** deleting `publishApplied(remote.operations().toList())` from
     * `mergeTurn` reds the first assertion — the server's sink sees only the server's own record.
     * Note the discriminating detail: the server exports one record of its own *first*, so a sink
     * fed by the local path alone is non-empty. A test that merged into a *silent* server would
     * see an empty sink either way only by luck, and would not distinguish "published nothing" from
     * "published the wrong thing".
     */
    @Test
    fun aMergePublishesTheRemoteLogsOperations() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val recorder = Recorder()
        val phoneSide = exporter(InMemoryDurableStore())
        val serverSide = exporter(InMemoryDurableStore(), replica = server, sink = recorder)
        phoneSide.export(record(1))
        phoneSide.export(record(2))
        serverSide.export(record(9))

        serverSide.merge(phoneSide.snapshot())

        val bodies = recorder.ops.filterIsInstance<RgaOp.Insert<LogRecord>>().map { it.value.body }.toSet()
        assertAll(
            { assertTrue(bodies.containsAll(setOf("record-1", "record-2")), "the phone's records reached the sink") },
            { assertTrue("record-9" in bodies, "and the server's own are still there") },
        )
    }

    /**
     * The same remote merged twice publishes twice — the exporter does **not** track what a sink
     * has already seen.
     *
     * Deliberate, and worth pinning so nobody "fixes" it here: the exporter would have to keep a
     * per-sink memory sized by the op-log to do it, on the export hot path, for a decision only the
     * consumer can make correctly. Suppression belongs to the side that knows what it kept — see
     * `BoltDecorator`, which does it with a bounded window.
     */
    @Test
    fun theExporterDoesNotSuppressARePublishedRemoteLog() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val recorder = Recorder()
        val phoneSide = exporter(InMemoryDurableStore())
        val serverSide = exporter(InMemoryDurableStore(), replica = server, sink = recorder)
        phoneSide.export(record(1))

        serverSide.merge(phoneSide.snapshot())
        serverSide.merge(phoneSide.snapshot())

        assertEquals(2, recorder.batches.size, "both rounds published; suppression is the consumer's job")
    }

    // ── Ordering, and the superset it buys ────────────────────────────────────

    /**
     * **Publication precedes the durable write**, so a sink is a *superset* of what the store holds
     * rather than a subset.
     *
     * That direction is the right one for an archive and the wrong one for a mirror, which is why
     * it is a stated property. The refused-write arm is the one that shows it: the export fails,
     * the store holds nothing, and the sink holds the record anyway.
     *
     * **Mutation receipt:** moving `publishApplied(applied)` to after `commit(actions)` reds the
     * first assertion (the sink is empty when the write threw) and the ordering assertion, since
     * `"write"` then precedes `"publish"`.
     */
    @Test
    fun publicationPrecedesTheDurableWriteSoARefusedWriteStillReachesTheSink() =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val order = mutableListOf<String>()
            val recorder = object : AppliedOpSink {
                val ops = mutableListOf<RgaOp<LogRecord>>()
                override suspend fun published(ops: List<RgaOp<LogRecord>>) {
                    order += "publish"
                    this.ops += ops
                }
            }
            val store = RefusingStore(InMemoryDurableStore()) { order += "write" }
            val exporter = exporter(store, sink = recorder)

            val result = exporter.export(record(1))
            val recovered = exporter(InMemoryDurableStore(), sink = AppliedOpSink.Discarding)
            store.refuse = false
            val fresh = WarpLogRecordExporter(replica = phone, store = store.backing)
            fresh.recover()

            assertAll(
                { assertTrue(result is ExportResult.Failure, "the store refused the write") },
                {
                    assertEquals(
                        1,
                        recorder.ops.count { it is RgaOp.Insert },
                        "and the sink holds the record anyway — a superset, which is the point",
                    )
                },
                { assertEquals(listOf("publish", "write"), order, "published BEFORE the write was attempted") },
                { assertEquals(emptyList(), fresh.snapshot().toList(), "while the store recovered nothing") },
                { assertEquals(emptyList(), recovered.snapshot().toList(), "control: an untouched store is empty too") },
            )
        }

    // ── What is deliberately NOT published ────────────────────────────────────

    /**
     * **[WarpLogRecordExporter.clear] publishes nothing, and tells no sink to forget.**
     *
     * This is the question a bolt-decorated exporter forces, and the answer has to be explicit
     * rather than implicit: a clear that reached through to an archive would make the archive a
     * mirror of the buffer, and an archive whose retention is the same as its source's has no
     * reason to exist. The consumer is *supposed* to still hold the records afterwards.
     *
     * `clear` is not a no-op internally — it runs a window pass that raises the compaction floor —
     * so "publishes nothing" is a real assertion, not a tautology.
     *
     * **The first assertion is what keeps it from being one.** "The count did not move" is
     * trivially true against an exporter that never publishes at all, so this asserts the exporter
     * *was* publishing beforehand. Without it the test goes green under a mutation that deletes
     * publication outright — measured, not assumed.
     */
    @Test
    fun clearPublishesNothingAndAsksNoSinkToForget() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val recorder = Recorder()
        val exporter = exporter(InMemoryDurableStore(), sink = recorder)
        repeat(3) { i -> exporter.export(record(i)) }
        val beforeClear = recorder.batches.size

        val cleared = exporter.clear()

        assertAll(
            { assertEquals(3, beforeClear, "the sink was live — otherwise 'nothing more' means nothing") },
            { assertEquals(ExportResult.Success, cleared) },
            { assertEquals(emptyList(), exporter.snapshot().toList(), "the live buffer really did empty") },
            { assertEquals(beforeClear, recorder.batches.size, "and the sink was told nothing about it") },
        )
    }

    /**
     * [WarpLogRecordExporter.recover] publishes nothing.
     *
     * The operations it reads back were published by whichever process first applied them.
     * Publishing here would re-offer the entire persisted log at every process start — which for a
     * consumer without its own suppression is a full duplicate archive per launch.
     *
     * The export *after* the recovery is what keeps "published nothing" from being vacuous: an
     * exporter that never publishes would satisfy the silence trivially, so the sink has to be
     * shown live on the same instance, immediately afterwards.
     */
    @Test
    fun recoverPublishesNothing() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val store = InMemoryDurableStore()
        val first = exporter(store)
        repeat(3) { i -> first.export(record(i)) }
        val recorder = Recorder()

        val recovered = exporter(store, sink = recorder)
        recovered.recover()
        val afterRecovery = recorder.batches.size
        val recoveredCount = recovered.snapshot().size
        recovered.export(record(99))

        assertAll(
            { assertEquals(3, recoveredCount, "the records really were recovered") },
            { assertEquals(0, afterRecovery, "and none of them was re-published") },
            {
                assertEquals(
                    listOf("record-99"),
                    recorder.ops.filterIsInstance<RgaOp.Insert<LogRecord>>().map { it.value.body },
                    "while the very next export publishes normally — so the silence above was a choice",
                )
            },
        )
    }

    /**
     * A sink that throws does not fail the export.
     *
     * It is a side channel. A caller on the logging path cannot handle a thrown exception — it
     * would surface inside an application's own logging call — and the record really was applied,
     * so reporting failure would be a lie about the exporter's own contract.
     *
     * The sink counts its own invocations, and that assertion is load-bearing: a sink that is never
     * called cannot throw, so "the export succeeded" would be green against an exporter that had
     * stopped publishing altogether — which is a mutation this file's other tests are meant to
     * catch, not one this test should quietly agree with.
     */
    @Test
    fun aThrowingSinkDoesNotFailTheExport() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        var invocations = 0
        val exporter = exporter(
            InMemoryDurableStore(),
            sink = {
                invocations++
                error("this sink is broken")
            },
        )

        val result = exporter.export(record(1))

        assertAll(
            { assertEquals(ExportResult.Success, result, "the export is unaffected") },
            { assertEquals(1, exporter.snapshot().size, "and the record was taken") },
            { assertEquals(1, invocations, "and the sink really was called — otherwise it never threw") },
        )
    }

    /** A store that refuses every write while [refuse] holds, noting each attempt through [onWrite]. */
    private class RefusingStore(
        val backing: DurableStore,
        var refuse: Boolean = true,
        private val onWrite: () -> Unit,
    ) : DurableStore {
        override suspend fun read(key: StoreKey): ByteArray? = backing.read(key)

        override suspend fun write(key: StoreKey, bytes: ByteArray) {
            onWrite()
            if (refuse) throw IllegalStateException("this store is refusing writes")
            backing.write(key, bytes)
        }

        override suspend fun delete(key: StoreKey) {
            backing.delete(key)
        }
    }
}
