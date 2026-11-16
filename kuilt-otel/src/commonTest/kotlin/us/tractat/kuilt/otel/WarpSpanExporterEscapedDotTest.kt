package us.tractat.kuilt.otel

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.ORSet
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.store.StoreKey
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins that a span is never visible to a peer before its turn's writes land, so a restart never
 * mints one of its dots again for a different span (#3517), the span twin of #3452.
 *
 * Before the fix an export stamped its span and added it to the set ahead of the turn's writes, so
 * [WarpSpanExporter.snapshot] showed it while the store still lacked it. If a peer gossiped that
 * snapshot and the process died before the writes landed, the restart minted the escaped span's
 * dots again. **There are two dots.** The span's [CausalStamp] dot comes from the [WarpCausalClock],
 * and two spans sharing it conflate their causal links. The `ORSet` mints a second dot of its own for
 * every add, from the set's causal context, and two adds sharing it make a merge drop **both** spans:
 * each side's context has seen the dot, and each side lacks the other's element under it. The third
 * arm has no clock at all, which is why a claim written ahead on the clock's key could not close this.
 *
 * The rig models the crash: the store lets every write through until the first write of its key made
 * while span 100's export is in flight, takes the snapshot a peer would gossip at that moment, and
 * refuses the write, as the process dies there. Each arm then restarts over what the store holds,
 * exports once more and merges the peer's snapshot back.
 *
 * One arm per place an export can die with its dots minted: at the clock write of a turn on an
 * exporter whose read had landed, at the clock write of the turn whose read lands, and, with no
 * clock, at the span write.
 */
class WarpSpanExporterEscapedDotTest {

    private val replica = ReplicaId("a")
    private val clockKey = WarpCausalClock.STORE_KEY
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
     * Delegates to [backing]. Once [peer] is set, the next write of [crashKey] takes the snapshot it
     * answers, kept in [gossiped], and is refused, as the process dies there.
     */
    private class CrashAtWriteStore(private val backing: DurableStore, private val crashKey: StoreKey) : DurableStore {
        var peer: (() -> ORSet<SpanRecord>)? = null
        var gossiped: ORSet<SpanRecord>? = null

        override suspend fun read(key: StoreKey): ByteArray? = backing.read(key)

        override suspend fun write(key: StoreKey, bytes: ByteArray) {
            if (key == crashKey) {
                peer?.let { take ->
                    gossiped = take()
                    peer = null
                    throw IllegalStateException("the process died writing ${key.name}")
                }
            }
            backing.write(key, bytes)
        }

        override suspend fun delete(key: StoreKey): Unit = backing.delete(key)
    }

    /** A process over [store]: its clock recovered, as [WarpTelemetry.recover] does, and its spans read when [readSpans]. */
    private suspend fun start(store: DurableStore, clocked: Boolean = true, readSpans: Boolean = true): WarpSpanExporter {
        val clock = if (clocked) WarpCausalClock(replica) else null
        val exporter = WarpSpanExporter(replica, store, causalClock = clock)
        clock?.let { exporter.clockRecovered(it.recover(store)) }
        if (readSpans) exporter.recover()
        return exporter
    }

    /**
     * Export span 100 through [writer] with [store] armed to die at its key's next write, and return
     * what the turn reported with the snapshot a peer took as it died.
     */
    private suspend fun dieExporting(store: CrashAtWriteStore, writer: WarpSpanExporter): Died {
        store.peer = { writer.snapshot() }
        val result = writer.export(span(100))
        return Died(result, assertNotNull(store.gossiped, "the rig: the turn never made the armed write"))
    }

    private class Died(val result: ExportResult, val gossiped: ORSet<SpanRecord>)

    /** Every own dot that two different spans of [spans] carry, each with the span ids holding it. */
    private fun sharedDots(spans: Collection<SpanRecord>): Map<Dot, List<ByteString>> = spans
        .mapNotNull { span -> span.causalStamp?.dot?.takeIf { it.replica == replica }?.let { it to span.spanId } }
        .distinct()
        .groupBy({ it.first }, { it.second })
        .filterValues { it.size > 1 }

    /**
     * Restart over [backing], export one more span, and merge back what the peer took from the dead
     * process: the outcome every arm asserts on.
     */
    private suspend fun restartAndMergeBack(backing: DurableStore, gossiped: ORSet<SpanRecord>, clocked: Boolean): AfterRestart {
        val restarted = start(backing, clocked)
        val heldAfterRestart = restarted.snapshot().elements.map { it.spanId }.toSet()
        val next = restarted.export(span(200))
        val both = gossiped.elements + restarted.snapshot().elements
        val merged = restarted.snapshot().piece(gossiped)
        return AfterRestart(heldAfterRestart, next, both, merged.elements.map { it.spanId }.toSet())
    }

    /**
     * [both] is every span either side holds, read before any merge: two spans on one dot are both
     * here even where a merge would drop them.
     */
    private class AfterRestart(
        val heldAfterRestart: Set<ByteString>,
        val next: ExportResult,
        val both: Set<SpanRecord>,
        val merged: Set<ByteString>,
    )

    /** What every arm asserts once the process has died exporting span 100 and restarted. */
    private fun assertNoDotEscaped(died: Died, after: AfterRestart) {
        val gossipedIds = died.gossiped.elements.map { it.spanId }.toSet()
        assertAll(
            // The rig: the turn died at the armed write, the peer took a snapshot of five spans, and
            // the store does not hold span 100.
            { assertTrue(died.result is ExportResult.Failure, "the export that died was reported ${died.result}") },
            { assertTrue((0 until 5).all { span(it).spanId in gossipedIds }, "the rig: the peer's snapshot lacks a written span") },
            { assertTrue(span(100).spanId !in after.heldAfterRestart, "the rig: the store holds span 100") },
            { assertEquals(ExportResult.Success, after.next, "the export after the restart") },
            // The property.
            { assertTrue(span(100).spanId !in gossipedIds, "span 100 was visible before its writes landed") },
            { assertEquals(emptyMap(), sharedDots(after.both), "two spans share a dot") },
            { assertEquals(gossipedIds + span(200).spanId, after.merged, "the merge dropped a span") },
        )
    }

    @Test
    fun aSpanWhoseClockWriteNeverLandedSharesNoDotAfterARestart() = runTest {
        val backing = InMemoryDurableStore()
        val store = CrashAtWriteStore(backing, clockKey)
        val writer = start(store)
        repeat(5) { assertEquals(ExportResult.Success, writer.export(span(it)), "the rig: export $it") }
        val died = dieExporting(store, writer)
        assertNoDotEscaped(died, restartAndMergeBack(backing, died.gossiped, clocked = true))
    }

    /**
     * A process whose spans were never read: its first export reads them inside the write section, and
     * builds its add there.
     */
    @Test
    fun aSpanStampedOnTheTurnWhoseReadLandsSharesNoDotAfterARestart() = runTest {
        val backing = InMemoryDurableStore()
        val previous = start(backing)
        repeat(5) { assertEquals(ExportResult.Success, previous.export(span(it)), "the rig: export $it") }
        val store = CrashAtWriteStore(backing, clockKey)
        val writer = start(store, readSpans = false)
        val heldBeforeTheTurn = writer.snapshot().elements.size
        val died = dieExporting(store, writer)
        assertAll(
            { assertEquals(0, heldBeforeTheTurn, "the rig: the exporter had read its spans before the turn") },
            { assertNoDotEscaped(died, restartAndMergeBack(backing, died.gossiped, clocked = true)) },
        )
    }

    /** No clock: the span is unstamped, so only the set's own add dot can escape. */
    @Test
    fun anUnstampedSpanWhoseWriteNeverLandedSurvivesAMergeAfterARestart() = runTest {
        val backing = InMemoryDurableStore()
        val store = CrashAtWriteStore(backing, spansKey)
        val writer = start(store, clocked = false)
        repeat(5) { assertEquals(ExportResult.Success, writer.export(span(it)), "the rig: export $it") }
        val died = dieExporting(store, writer)
        assertNoDotEscaped(died, restartAndMergeBack(backing, died.gossiped, clocked = false))
    }
}
