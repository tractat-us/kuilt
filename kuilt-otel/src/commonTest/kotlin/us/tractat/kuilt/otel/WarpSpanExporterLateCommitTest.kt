package us.tractat.kuilt.otel

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.crdt.ORSet
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.store.StoreKey
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins that a failed export's set dot is not minted again for the next export (#3517 review).
 *
 * `ORSet.add` mints its dot from the set's context. A failed turn publishes nothing, so unless the
 * exporter keeps the dot the turn minted, the next export mints the same one for a different span.
 * That only shows when the failed write committed after all: [DurableStore.write] admits a write
 * that lands after its caller stopped waiting, ordered against nothing written since. Then the store
 * holds the failed span on the dot a peer already has from the next export's span, and merging that
 * peer's snapshot back after a restart drops **both**.
 *
 * The rig refuses the armed span writes to their callers but keeps their bytes, and commits the
 * first of them once the next export has landed: a late commit over newer bytes.
 */
class WarpSpanExporterLateCommitTest {

    private val replica = ReplicaId("a")
    private val spansKey = StoreKey("otel.spans")

    private fun span(id: Int) = SpanRecord(
        traceId = ByteString(ByteArray(16) { 1 }),
        spanId = ByteString(ByteArray(8) { i -> (id shr (8 * i)).toByte() }),
        parentSpanId = null,
        name = "span-$id",
        kind = SpanKind.INTERNAL,
        startEpochNanos = 1_000L + id,
        endEpochNanos = 2_000L + id,
    )

    /**
     * Delegates to [backing]. While [refusing] is above zero, a span write throws to its caller, and
     * while [parking] is, it waits until its caller is cancelled; either way its bytes are kept in
     * [held], and [commitFirstHeld] lands the first of them late.
     */
    private class LateCommitStore(private val backing: DurableStore, private val key: StoreKey) : DurableStore {
        var refusing = 0
        var parking = 0
        val held = mutableListOf<ByteArray>()

        suspend fun commitFirstHeld() {
            backing.write(key, held.first())
        }

        override suspend fun read(key: StoreKey): ByteArray? = backing.read(key)

        override suspend fun write(key: StoreKey, bytes: ByteArray) {
            if (key == this.key && refusing > 0) {
                refusing--
                held += bytes
                throw IllegalStateException("the store timed out; the write is still in flight")
            }
            if (key == this.key && parking > 0) {
                parking--
                held += bytes
                awaitCancellation()
            }
            backing.write(key, bytes)
        }

        override suspend fun delete(key: StoreKey): Unit = backing.delete(key)
    }

    private fun ids(set: ORSet<SpanRecord>): Set<ByteString> = set.elements.map { it.spanId }.toSet()

    /**
     * Five spans written, then an export of each of [failing] whose write fails to its caller, then
     * span 200, which lands and which a peer takes. The first failed write then commits late, the
     * process restarts over what the store holds, and merges the peer's snapshot back.
     */
    private suspend fun failThenLandLate(failing: List<Int>): Outcome {
        val backing = InMemoryDurableStore()
        val store = LateCommitStore(backing, spansKey)
        val writer = WarpSpanExporter(replica, store).apply { recover() }
        repeat(5) { assertEquals(ExportResult.Success, writer.export(span(it)), "the rig: export $it") }
        store.refusing = failing.size
        val failed = failing.map { writer.export(span(it)) }
        val landed = writer.export(span(200))
        val gossiped = writer.snapshot()
        store.commitFirstHeld()

        val restarted = WarpSpanExporter(replica, backing).apply { recover() }
        val heldAfterRestart = ids(restarted.snapshot())
        val merged = restarted.merge(gossiped)
        return Outcome(failed, landed, store.held.size, ids(gossiped), heldAfterRestart, merged, ids(restarted.snapshot()))
    }

    private class Outcome(
        val failed: List<ExportResult>,
        val landed: ExportResult,
        val heldWrites: Int,
        val gossiped: Set<ByteString>,
        val heldAfterRestart: Set<ByteString>,
        val merged: ExportResult,
        val afterMerge: Set<ByteString>,
    )

    private fun assertTheLandedSpanSurvives(outcome: Outcome, failing: List<Int>) {
        assertAll(
            // The rig: every armed write failed to its caller, the next export landed and was taken by
            // the peer, and the late commit put the first failed span in the store in its place.
            { assertTrue(outcome.failed.all { it is ExportResult.Failure }, "the rig: an armed export landed: ${outcome.failed}") },
            { assertEquals(failing.size, outcome.heldWrites, "the rig: the store did not hold every armed write") },
            { assertEquals(ExportResult.Success, outcome.landed, "the rig: span 200 was not saved") },
            { assertTrue(span(200).spanId in outcome.gossiped, "the rig: the peer did not take span 200") },
            { assertTrue(span(failing.first()).spanId in outcome.heldAfterRestart, "the rig: the late commit did not land") },
            { assertTrue(span(200).spanId !in outcome.heldAfterRestart, "the rig: the late commit did not overwrite span 200") },
            { assertEquals(ExportResult.Success, outcome.merged, "the merge after the restart") },
            // The property: span 200 kept a dot of its own, so the merge keeps it.
            { assertTrue(span(200).spanId in outcome.afterMerge, "the merge dropped the span that landed") },
        )
    }

    @Test
    fun aFailedWriteThatCommitsLateSharesNoSetDotWithTheNextSpan() = runTest {
        val failing = listOf(100)
        assertTheLandedSpanSurvives(failThenLandLate(failing), failing)
    }

    /**
     * Two failures in a row: the second turn's dot must sit beside the first's, not replace it, or the
     * next export mints the first one's dot again, since a context mints past its contiguous run only.
     */
    @Test
    fun twoFailedWritesInARowLeaveBothDotsTaken() = runTest {
        val failing = listOf(100, 101)
        assertTheLandedSpanSurvives(failThenLandLate(failing), failing)
    }

    /**
     * The failed export re-exports span 1, a value the set already holds, so its dot is kept from an
     * add of a renamed copy rather than from the add's own delta. The late commit holds span 1 on that
     * dot, and the next export must not mint it again. Span 1 itself does not survive the merge: the
     * late re-add superseded its old dot, and the peer has seen the new one without the element. That
     * is the late-commit residual (#3544), and `main` loses span 1 the same way.
     */
    @Test
    fun aFailedReExportOfAHeldValueThatCommitsLateSharesNoSetDotWithTheNextSpan() = runTest {
        val failing = listOf(1)
        assertTheLandedSpanSurvives(failThenLandLate(failing), failing)
    }

    /**
     * A failed export of a value the set already holds keeps only its own new dot. The add's delta
     * also witnesses the held copy's dot, and kept whole it would act as a removal of that copy when
     * the next turn builds on it.
     */
    @Test
    fun aFailedExportOfAHeldValueLeavesTheHeldCopyInPlace() = runTest {
        val store = LateCommitStore(InMemoryDurableStore(), spansKey)
        val writer = WarpSpanExporter(replica, store).apply { recover() }
        val first = writer.export(span(1))
        store.refusing = 1
        val again = writer.export(span(1))
        val next = writer.export(span(2))
        val held = ids(writer.snapshot())
        assertAll(
            { assertEquals(ExportResult.Success, first, "the rig: the first export was not saved") },
            { assertTrue(again is ExportResult.Failure, "the rig: the repeated export landed: $again") },
            { assertEquals(1, store.held.size, "the rig: the repeated export's write was not refused") },
            { assertEquals(ExportResult.Success, next) },
            { assertEquals(setOf(span(1).spanId, span(2).spanId), held, "the next turn dropped the held copy") },
        )
    }

    /**
     * A store that refuses every write keeps failing turns for as long as it stays broken. What they
     * keep must not grow with their number: each kept dot follows the last, so the kept context folds
     * them into one run rather than holding a dot apiece. Measured as the encoded size of what is kept,
     * after a short outage and after a long one.
     */
    @Test
    fun whatFailedTurnsKeepStaysTheSameSizeHoweverManyFail() = runTest {
        val store = LateCommitStore(InMemoryDurableStore(), spansKey)
        val writer = WarpSpanExporter(replica, store).apply { recover() }
        repeat(5) { assertEquals(ExportResult.Success, writer.export(span(it)), "the rig: export $it") }
        store.refusing = LONG_OUTAGE
        val early = (0 until SHORT_OUTAGE).map { writer.export(span(1_000 + it)) }
        val keptEarly = encodedSize(writer.unpublishedForTest())
        val late = (SHORT_OUTAGE until LONG_OUTAGE).map { writer.export(span(1_000 + it)) }
        val keptLate = encodedSize(writer.unpublishedForTest())
        assertAll(
            { assertTrue((early + late).all { it is ExportResult.Failure }, "the rig: an export landed during the outage") },
            { assertEquals(LONG_OUTAGE, store.held.size, "the rig: the store did not refuse every write") },
            {
                assertTrue(
                    keptLate - keptEarly <= SIZE_SLACK,
                    "what failed turns keep grew from $keptEarly to $keptLate bytes over ${LONG_OUTAGE - SHORT_OUTAGE} more failures",
                )
            },
        )
    }

    /**
     * What a failed turn keeps is the set it started from, so the next turn joins those spans back in.
     * A clear in between must win: the cleared set has seen every span the kept set holds, so the join
     * brings none of them back, in memory or in the store.
     */
    @Test
    fun aClearAfterAFailedTurnStaysClearedWhenTheNextExportLands() = runTest {
        val backing = InMemoryDurableStore()
        val store = LateCommitStore(backing, spansKey)
        val writer = WarpSpanExporter(replica, store).apply { recover() }
        repeat(5) { assertEquals(ExportResult.Success, writer.export(span(it)), "the rig: export $it") }
        store.refusing = 1
        val failed = writer.export(span(100))
        val keptBeforeClear = ids(writer.unpublishedForTest())
        val cleared = writer.clear()
        val next = writer.export(span(200))
        val durable = ids(WarpSpanExporter(replica, backing).apply { recover() }.snapshot())
        assertAll(
            { assertTrue(failed is ExportResult.Failure, "the rig: the armed export landed: $failed") },
            { assertEquals((0 until 5).map { span(it).spanId }.toSet(), keptBeforeClear, "the rig: the failed turn kept no spans to bring back") },
            { assertEquals(ExportResult.Success, cleared) },
            { assertEquals(ExportResult.Success, next) },
            { assertEquals(setOf(span(200).spanId), ids(writer.snapshot()), "the next export brought cleared spans back") },
            { assertEquals(setOf(span(200).spanId), durable, "the store holds cleared spans again") },
        )
    }

    /**
     * At the cap, a failed turn chose a victim it never evicted from memory. What it keeps must not
     * evict it either, or the next turn would drop two spans for its one add.
     */
    @Test
    fun aFailedTurnAtTheCapEvictsNothingFromTheNextOne() = runTest {
        val store = LateCommitStore(InMemoryDurableStore(), spansKey)
        val writer = WarpSpanExporter(replica, store, maxSpans = 3).apply { recover() }
        repeat(3) { assertEquals(ExportResult.Success, writer.export(span(it)), "the rig: export $it") }
        store.refusing = 1
        val failed = writer.export(span(100))
        val heldAfterFailure = ids(writer.snapshot())
        val next = writer.export(span(200))
        assertAll(
            { assertTrue(failed is ExportResult.Failure, "the rig: the armed export landed: $failed") },
            { assertEquals((0 until 3).map { span(it).spanId }.toSet(), heldAfterFailure, "the failed turn evicted a span") },
            { assertEquals(ExportResult.Success, next) },
            { assertEquals(setOf(1, 2, 200).map { span(it).spanId }.toSet(), ids(writer.snapshot()), "the next export evicted more than one span") },
        )
    }

    /** A remote set merged after a failed turn is kept when the next export publishes. */
    @Test
    fun aMergeAfterAFailedTurnIsKeptWhenTheNextExportLands() = runTest {
        val store = LateCommitStore(InMemoryDurableStore(), spansKey)
        val writer = WarpSpanExporter(replica, store).apply { recover() }
        repeat(5) { assertEquals(ExportResult.Success, writer.export(span(it)), "the rig: export $it") }
        store.refusing = 1
        val failed = writer.export(span(100))
        val merged = writer.merge(ORSet.empty<SpanRecord>().piece { it.add(ReplicaId("b"), span(50)) })
        val next = writer.export(span(200))
        assertAll(
            { assertTrue(failed is ExportResult.Failure, "the rig: the armed export landed: $failed") },
            { assertEquals(ExportResult.Success, merged) },
            { assertEquals(ExportResult.Success, next) },
            {
                assertEquals(
                    ((0 until 5) + 50 + 200).map { span(it).spanId }.toSet(),
                    ids(writer.snapshot()),
                    "the next export lost a span",
                )
            },
        )
    }

    private fun encodedSize(set: ORSet<SpanRecord>): Int =
        Cbor { alwaysUseByteString = true }.encodeToByteArray(ORSet.serializer(SpanRecord.serializer()), set).size

    private companion object {
        const val SHORT_OUTAGE = 20
        const val LONG_OUTAGE = 300

        /** A few bytes for a counter that needs a wider CBOR integer after more failures. */
        const val SIZE_SLACK = 8
    }

    /**
     * The caller gives up on the export while its span write is in flight, and the write commits
     * later anyway. A cancelled turn keeps its set dot as a failed one does.
     */
    @Test
    fun aCancelledExportWhoseWriteCommitsLateSharesNoSetDotWithTheNextSpan() = runTest {
        val backing = InMemoryDurableStore()
        val store = LateCommitStore(backing, spansKey)
        val writer = WarpSpanExporter(replica, store).apply { recover() }
        repeat(5) { assertEquals(ExportResult.Success, writer.export(span(it)), "the rig: export $it") }
        store.parking = 1
        val abandoned = withTimeoutOrNull(1_000L) { writer.export(span(100)) }
        val landed = writer.export(span(200))
        val gossiped = writer.snapshot()
        store.commitFirstHeld()

        val restarted = WarpSpanExporter(replica, backing).apply { recover() }
        val heldAfterRestart = ids(restarted.snapshot())
        val merged = restarted.merge(gossiped)
        val afterMerge = ids(restarted.snapshot())
        assertAll(
            { assertNull(abandoned, "the rig: the export returned instead of being abandoned") },
            { assertEquals(1, store.held.size, "the rig: the abandoned write was not held") },
            { assertEquals(ExportResult.Success, landed, "the rig: span 200 was not saved") },
            { assertTrue(span(100).spanId in heldAfterRestart, "the rig: the late commit did not land") },
            { assertTrue(span(200).spanId !in heldAfterRestart, "the rig: the late commit did not overwrite span 200") },
            { assertEquals(ExportResult.Success, merged, "the merge after the restart") },
            { assertTrue(span(200).spanId in afterMerge, "the merge dropped the span that landed") },
        )
    }
}
