@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.otel

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.RgaId
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.StoreKey
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Pins how [WarpLogRecordExporter] hands out segment numbers (#3317).
 *
 * A segment number names a key, `otel.logs.seg.<n>`, so handing one out twice overwrites a live
 * segment. The number used to be an `Int`, and once it wrapped every later roll reused
 * `Int.MAX_VALUE` while every export still returned `Success`. One number is spent per roll and,
 * before this fix, one per [WarpLogRecordExporter.merge] even when the merge changed nothing.
 */
class WarpLogRecordExporterSegmentNumberingTest {

    private val replicaA = ReplicaId("A")
    private val replicaB = ReplicaId("B")

    private fun recordId(id: Int): ByteString =
        ByteString(ByteArray(8) { i -> (id shr (8 * i)).toByte() })

    private fun record(id: Int, body: String = "body-$id") = LogRecord(
        recordId = recordId(id),
        body = body,
        observedEpochNanos = 1_700_000_000_000_000_000L,
    )

    private fun exporterFor(store: DurableStore, segmentOps: Int = 4) = WarpLogRecordExporter(
        replica = replicaA,
        store = store,
        maxRecords = 1_000,
        segmentOps = segmentOps,
    )

    private fun foreignLog(count: Int = 6): Rga<LogRecord> {
        var rga = Rga.empty<LogRecord>()
        var tail = RgaId.HEAD
        repeat(count) { i ->
            val (next, op) = rga.insertAfter(replica = replicaB, after = tail, value = record(500 + i, "foreign$i"))
            rga = next
            tail = op.id
        }
        return rga
    }

    private fun segmentKeys(store: RecordingStore): Set<String> =
        store.keys().filter { it.startsWith(SEGMENT_KEY_PREFIX_FOR_TEST) }.toSet()

    private suspend fun indexOf(store: RecordingStore): LogSegmentIndex =
        decodeIndexForTest(requireNotNull(store.read(INDEX_KEY_FOR_TEST)))

    // ---- The number must not wrap ----

    @Test
    fun segmentNumbersCrossTheIntBoundaryWithoutOverwritingALiveSegment() = runTest {
        // The index is seeded in the shape every build before this fix wrote — `Int` fields —
        // three rolls short of `Int.MAX_VALUE`. Forty one-op exports at four ops per segment roll
        // ten times, so the numbering crosses the old boundary about seven rolls in.
        val store = RecordingStore()
        store.putRaw(
            INDEX_KEY_FOR_TEST,
            legacyCbor.encodeToByteArray(
                IntShapedIndex.serializer(),
                IntShapedIndex(sealedSegments = emptyList(), active = Int.MAX_VALUE - 3, next = Int.MAX_VALUE - 2),
            ),
        )
        val exporter = exporterFor(store).also { it.recover() }
        val results = (0 until 40).map { exporter.export(record(it)) }

        val restarted = exporterFor(store).also { it.recover() }
        val recovered = restarted.snapshot().toList().map { it.body }.toSet()
        val index = indexOf(store)

        assertAll(
            { assertTrue(results.all { it == ExportResult.Success }, "every export must report Success: $results") },
            {
                assertTrue(
                    index.active > Int.MAX_VALUE.toLong(),
                    "the numbering never crossed the old Int boundary, so this test exercised nothing: $index",
                )
            },
            { assertEquals(40, recovered.size, "a restart lost records to an overwritten segment: $index") },
            {
                assertEquals(
                    index.sealedSegments.size,
                    index.sealedSegments.toSet().size,
                    "a segment number was sealed twice: $index",
                )
            },
            { assertTrue(index.next > index.active, "the next number must lie past the active one: $index") },
        )
    }

    // ---- A no-op merge spends nothing ----

    @Test
    fun aMergeThatChangesNothingSpendsNoSegmentNumber() = runTest {
        val store = RecordingStore()
        val exporter = exporterFor(store)
        repeat(3) { exporter.export(record(it)) }
        val remote = foreignLog()

        val firstMerge = exporter.merge(remote)
        val afterFirst = indexOf(store)
        val keysAfterFirst = segmentKeys(store)
        val writesAfterFirst = store.writes().size

        val again = exporter.merge(remote)
        val ownEcho = exporter.merge(exporter.snapshot())
        val afterRepeat = indexOf(store)

        assertAll(
            { assertEquals(ExportResult.Success, firstMerge) },
            // The control: a merge that brings something new does take a number, so the
            // assertions below are not passing on a merge path that never adopts at all.
            { assertTrue(afterFirst.sealedSegments.isNotEmpty(), "the first merge adopted nothing: $afterFirst") },
            { assertEquals(ExportResult.Success, again) },
            { assertEquals(ExportResult.Success, ownEcho) },
            { assertEquals(afterFirst.next, afterRepeat.next, "a no-op merge spent a segment number") },
            { assertEquals(keysAfterFirst, segmentKeys(store), "a no-op merge wrote a new segment key") },
            { assertEquals(writesAfterFirst, store.writes().size, "a no-op merge wrote to the store") },
        )
    }

    @Test
    fun aMergeRetriedAfterItsWriteWasRefusedStillReachesTheStore() = runTest {
        // A refused merge has already joined the remote into the in-memory log, so the retry
        // changes nothing in memory. It must still persist the remote: nothing on disk holds it.
        val backing = RecordingStore()
        val store = WriteRefusingStore(backing) { key -> key.name.startsWith(SEGMENT_KEY_PREFIX_FOR_TEST) }
        val exporter = exporterFor(store)
        repeat(3) { exporter.export(record(it)) }
        val remote = foreignLog()

        store.refuseWrites()
        val refused = exporter.merge(remote)
        val refusedWrites = store.refusedWrites()
        store.allowWrites()
        val retried = exporter.merge(remote)

        val restarted = exporterFor(backing).also { it.recover() }
        val bodies = restarted.snapshot().toList().map { it.body }

        assertAll(
            { assertTrue(refusedWrites > 0, "the rig never refused a write") },
            { assertTrue(refused is ExportResult.Failure, "the refused merge reported $refused") },
            { assertEquals(ExportResult.Success, retried) },
            { assertTrue((0 until 6).all { "foreign$it" in bodies }, "the retried merge never persisted: $bodies") },
        )
    }

    // ---- The index refuses a number it would hand out twice ----

    @Test
    fun theIndexRefusesEveryLayoutThatWouldHandOutANumberTwice() {
        val refusals = mapOf(
            "next equal to active" to { LogSegmentIndex(listOf(1L), active = 2L, next = 2L) },
            "next below active (a wrap)" to { LogSegmentIndex(listOf(1L), active = Long.MAX_VALUE, next = Long.MIN_VALUE) },
            "a segment sealed twice" to { LogSegmentIndex(listOf(1L, 1L), active = 2L, next = 3L) },
            "active also sealed" to { LogSegmentIndex(listOf(1L, 2L), active = 2L, next = 3L) },
            "a sealed number at next" to { LogSegmentIndex(listOf(3L), active = 2L, next = 3L) },
            "a retired number at next" to { LogSegmentIndex(listOf(1L), active = 2L, next = 3L, retired = listOf(3L)) },
        )
        assertAll(
            // The control: a well-formed index constructs, so the refusals are not a constructor
            // that refuses everything.
            { LogSegmentIndex(listOf(1L), active = 2L, next = 3L, retired = listOf(0L)) },
            *refusals.map { (shape, build) ->
                { assertFailsWith<IllegalArgumentException>("the index accepted $shape") { build() } }
            }.toTypedArray(),
        )
    }

    @Test
    fun aRollThatWouldWrapRefusesTheExportInsteadOfOverwritingASegment() = runTest {
        // One roll from the end of `Long`: the fourth record fills the segment, and the roll would
        // open `Long.MAX_VALUE` with nothing past it to hand out next.
        val store = RecordingStore()
        store.putRaw(
            INDEX_KEY_FOR_TEST,
            legacyCbor.encodeToByteArray(
                LogSegmentIndex.serializer(),
                LogSegmentIndex(sealedSegments = emptyList(), active = Long.MAX_VALUE - 1, next = Long.MAX_VALUE),
            ),
        )
        val exporter = exporterFor(store).also { it.recover() }
        val results = (0 until 4).map { exporter.export(record(it)) }
        val index = indexOf(store)

        assertAll(
            // The precondition: the store takes writes, so the refusal below is the roll's own.
            { assertEquals(List(3) { ExportResult.Success }, results.take(3)) },
            { assertTrue(results[3] is ExportResult.Failure, "the wrapping roll reported ${results[3]}") },
            { assertEquals(Long.MAX_VALUE - 1, index.active, "the refused roll still moved the index: $index") },
            {
                assertEquals(
                    setOf(segmentKeyForTest(Long.MAX_VALUE - 1)),
                    segmentKeys(store),
                    "the refused roll wrote a segment",
                )
            },
        )
    }

    // ---- An index an older build wrote still reads ----

    @Test
    fun aWrappedIndexAnOlderBuildWroteIsRepairedOnReadAndLosesNoKey() = runTest {
        // The layout the wrap left behind, as measured on `main` before #3317: `next` wrapped to
        // `Int.MIN_VALUE`, and `Int.MAX_VALUE` both active and sealed over and over.
        val store = RecordingStore()
        val below = Int.MAX_VALUE.toLong() - 1
        val wrapped = Int.MAX_VALUE.toLong()
        store.putRaw(
            INDEX_KEY_FOR_TEST,
            legacyCbor.encodeToByteArray(
                IntShapedIndex.serializer(),
                IntShapedIndex(
                    sealedSegments = listOf(Int.MAX_VALUE - 1, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE),
                    active = Int.MAX_VALUE,
                    next = Int.MIN_VALUE,
                ),
            ),
        )
        store.putRaw(StoreKey(segmentKeyForTest(below)), segmentOf((0 until 4).map { record(it) }))
        store.putRaw(StoreKey(segmentKeyForTest(wrapped)), segmentOf((4 until 6).map { record(it) }))

        val read = indexOf(store)
        val exporter = exporterFor(store).also { it.recover() }
        val results = (6 until 20).map { exporter.export(record(it)) }
        val restarted = exporterFor(store).also { it.recover() }
        val recovered = restarted.snapshot().toList().map { it.body }.toSet()

        assertAll(
            { assertEquals(listOf(below), read.sealedSegments, "the read did not fold the repeated seals: $read") },
            { assertEquals(wrapped, read.active) },
            { assertEquals(wrapped + 1, read.next, "the read did not raise next past every number: $read") },
            { assertTrue(results.all { it == ExportResult.Success }, "an export after the repair failed: $results") },
            { assertEquals((0 until 20).map { "body-$it" }.toSet(), recovered, "a key the old index named was lost") },
        )
    }

    private fun segmentOf(records: List<LogRecord>): ByteArray {
        var rga = Rga.empty<LogRecord>()
        var tail = RgaId.HEAD
        records.forEach { r ->
            val (next, op) = rga.insertAfter(replica = replicaA, after = tail, value = r)
            rga = next
            tail = op.id
        }
        return legacyCbor.encodeToByteArray(Rga.wireSerializer(LogRecord.serializer()), rga)
    }

    /** The index exactly as every build before #3317 wrote it: `Int` fields, same names. */
    @Serializable
    private class IntShapedIndex(
        val sealedSegments: List<Int>,
        val active: Int,
        val next: Int,
        val retired: List<Int> = emptyList(),
    )

    private companion object {
        val legacyCbor = Cbor { alwaysUseByteString = true }
    }
}
