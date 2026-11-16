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
 * Pins that a span which escaped before its turn's clock write landed never shares its dot with a
 * span minted after a restart (#3517), the span twin of #3452.
 *
 * An export stamps its span and adds it to the set before the turn writes the clock, so [snapshot]
 * shows the stamped span while the durable clock is still below its dot. If a peer gossips that
 * snapshot and the process dies before the clock write lands, the restart reads the older clock
 * entry, the span bytes carry no higher mark, and the next export mints the same `(replica, seq)`
 * for a different span.
 *
 * The rig models the crash precisely: the store lets every write through until the first clock
 * write made while the escaping span is visible, takes the peer's snapshot there, and refuses it.
 * Keyed on what the snapshot holds rather than on a write's index, so a claim written ahead of the
 * mint is a write the rig lets through, as a crash after it would.
 *
 * One arm per place an export mints: before its write section, once the exporter's read has landed,
 * and inside the section, on the turn whose read lands.
 */
class WarpSpanExporterEscapedDotTest {

    private val replica = ReplicaId("a")
    private val clockKey = WarpCausalClock.STORE_KEY

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
     * Delegates to [backing]. While [crash] is set, a clock write calls it first: a non-null answer is
     * the snapshot a peer took at that moment, kept in [gossiped], and the write is refused, as the
     * process dies there. A `null` answer lets the write through.
     */
    private class CrashAtClockWriteStore(private val backing: DurableStore, private val clockKey: StoreKey) : DurableStore {
        var crash: (() -> ORSet<SpanRecord>?)? = null
        var gossiped: ORSet<SpanRecord>? = null

        override suspend fun read(key: StoreKey): ByteArray? = backing.read(key)

        override suspend fun write(key: StoreKey, bytes: ByteArray) {
            if (key == clockKey) {
                crash?.invoke()?.let { snapshot ->
                    gossiped = snapshot
                    crash = null
                    throw IllegalStateException("the process died writing ${key.name}")
                }
            }
            backing.write(key, bytes)
        }

        override suspend fun delete(key: StoreKey): Unit = backing.delete(key)
    }

    /** A process over [store]: its clock recovered, as [WarpTelemetry.recover] does, and its spans read when [readSpans]. */
    private suspend fun start(store: DurableStore, readSpans: Boolean = true): WarpSpanExporter {
        val clock = WarpCausalClock(replica)
        val exporter = WarpSpanExporter(replica, store, causalClock = clock)
        exporter.clockRecovered(clock.recover(store))
        if (readSpans) exporter.recover()
        return exporter
    }

    /** Arm [store] to die at the first clock write made while [exporter]'s snapshot shows span [id]. */
    private fun dieOnceSpanIsVisible(store: CrashAtClockWriteStore, exporter: WarpSpanExporter, id: Int) {
        store.crash = { exporter.snapshot().takeIf { set -> set.elements.any { it.spanId == span(id).spanId } } }
    }

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
    private suspend fun restartAndMergeBack(backing: DurableStore, gossiped: ORSet<SpanRecord>): AfterRestart {
        val restarted = start(backing)
        val heldAfterRestart = restarted.snapshot().elements.map { it.spanId }.toSet()
        val next = restarted.export(span(200))
        val merged = restarted.snapshot().piece(gossiped)
        return AfterRestart(heldAfterRestart, next, merged.elements)
    }

    private class AfterRestart(val heldAfterRestart: Set<ByteString>, val next: ExportResult, val merged: Set<SpanRecord>)

    private fun escapedDot(gossiped: ORSet<SpanRecord>?): Dot? =
        gossiped?.elements?.singleOrNull { it.spanId == span(100).spanId }?.causalStamp?.dot

    @Test
    fun aSpanGossipedBeforeItsClockWriteLandedKeepsItsDotAfterARestart() = runTest {
        val backing = InMemoryDurableStore()
        val store = CrashAtClockWriteStore(backing, clockKey)
        val writer = start(store)
        repeat(5) { assertEquals(ExportResult.Success, writer.export(span(it)), "the rig: export $it") }
        dieOnceSpanIsVisible(store, writer, 100)
        val died = writer.export(span(100))
        val gossiped = store.gossiped

        val after = restartAndMergeBack(backing, assertNotNull(gossiped, "the rig: no clock write ran while span 100 was visible"))
        assertAll(
            // The rig: the turn died at its clock write, span 100 had escaped stamped, and no key holds it.
            { assertTrue(died is ExportResult.Failure, "the export that died was reported $died") },
            { assertNotNull(escapedDot(gossiped), "the rig: span 100 escaped unstamped") },
            { assertTrue(span(100).spanId !in after.heldAfterRestart, "the rig: the store holds span 100") },
            { assertEquals(ExportResult.Success, after.next, "the export after the restart") },
            // The property.
            { assertEquals(emptyMap(), sharedDots(after.merged), "two spans share a dot") },
            { assertEquals(7, after.merged.size, "the merged set holds every span once") },
        )
    }

    /**
     * A process whose spans were never read: its first export reads them inside the write section, and
     * stamps and adds there, so the stamp is visible while that turn's clock write is pending too.
     */
    @Test
    fun aSpanStampedOnTheTurnWhoseReadLandsKeepsItsDotAfterARestart() = runTest {
        val backing = InMemoryDurableStore()
        val previous = start(backing)
        repeat(5) { assertEquals(ExportResult.Success, previous.export(span(it)), "the rig: export $it") }
        val store = CrashAtClockWriteStore(backing, clockKey)
        val writer = start(store, readSpans = false)
        val heldBeforeTheTurn = writer.snapshot().elements.size
        dieOnceSpanIsVisible(store, writer, 100)
        val died = writer.export(span(100))
        val gossiped = store.gossiped

        val after = restartAndMergeBack(backing, assertNotNull(gossiped, "the rig: no clock write ran while span 100 was visible"))
        assertAll(
            // The rig: the turn read the stored spans itself, died at its clock write, and span 100 escaped.
            { assertEquals(0, heldBeforeTheTurn, "the rig: the exporter had read its spans before the turn") },
            { assertTrue(died is ExportResult.Failure, "the export that died was reported $died") },
            { assertNotNull(escapedDot(gossiped), "the rig: span 100 escaped unstamped") },
            { assertTrue(span(100).spanId !in after.heldAfterRestart, "the rig: the store holds span 100") },
            { assertEquals(ExportResult.Success, after.next, "the export after the restart") },
            // The property.
            { assertEquals(emptyMap(), sharedDots(after.merged), "two spans share a dot") },
            { assertEquals(7, after.merged.size, "the merged set holds every span once") },
        )
    }
}
