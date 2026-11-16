package us.tractat.kuilt.otel

import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.ORSet
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.store.StoreKey
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins how an export that shows its span only once its writes return (#3517) meets a [merge] and a
 * [clear] that arrive while an earlier turn's write is in flight.
 *
 * A [WarpSpanExporter.clear] holds the write section for its whole turn, so it never runs between an
 * export's build and its publish: an export queued behind a clear builds on the cleared set, and its
 * span survives. Before #3517 the queued export had already added its span, the clear removed it,
 * and the export then wrote a set without it and reported success. A [WarpSpanExporter.merge] joins
 * its remote set before it queues, so it can land between a turn's build and its publish, and the
 * publish must keep it.
 */
class WarpSpanExporterPublishAfterWriteTest {

    private val replica = ReplicaId("a")
    private val peer = ReplicaId("b")
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

    /** Parks every span write while [parking], each until one [release], so a turn can hold the write section. */
    private class ParkingStore(private val backing: DurableStore, private val target: StoreKey) : DurableStore {
        private val permits = Channel<Unit>(Channel.UNLIMITED)
        var parking = true
        var parked = 0

        fun release() {
            permits.trySend(Unit)
        }

        override suspend fun read(key: StoreKey): ByteArray? = backing.read(key)

        override suspend fun write(key: StoreKey, bytes: ByteArray) {
            if (parking && key == target) {
                parked++
                permits.receive()
            }
            backing.write(key, bytes)
        }

        override suspend fun delete(key: StoreKey): Unit = backing.delete(key)
    }

    private suspend fun exporterOver(store: DurableStore): WarpSpanExporter {
        val clock = WarpCausalClock(replica)
        return WarpSpanExporter(replica, store, causalClock = clock).also {
            it.clockRecovered(clock.recover(store))
            it.recover()
        }
    }

    private suspend fun durableIds(backing: DurableStore): Set<ByteString> =
        WarpSpanExporter(replica, backing).apply { recover() }.snapshot().elements.map { it.spanId }.toSet()

    private fun ids(exporter: WarpSpanExporter): Set<ByteString> = exporter.snapshot().elements.map { it.spanId }.toSet()

    @Test
    fun anExportQueuedBehindAClearKeepsItsSpan() = runTest {
        val backing = InMemoryDurableStore()
        val store = ParkingStore(backing, spansKey)
        val exporter = exporterOver(store)
        val holder = async { exporter.export(span(1)) }
        testScheduler.runCurrent()
        val cleared = async { exporter.clear() }
        testScheduler.runCurrent()
        val queued = async { exporter.export(span(2)) }
        testScheduler.runCurrent()
        val parkedWhileQueued = store.parked
        store.parking = false
        store.release()
        val results = listOf(holder.await(), cleared.await(), queued.await())
        val durable = durableIds(backing)

        assertAll(
            // The rig: only the holder reached a span write before the clear and the export queued.
            { assertEquals(1, parkedWhileQueued, "the rig: the holder was not alone in the write section") },
            { assertEquals(List(3) { ExportResult.Success }, results) },
            // The property: the export after the clear holds its span, in memory and in the store.
            { assertEquals(setOf(span(2).spanId), ids(exporter), "the export queued behind the clear lost its span") },
            { assertEquals(setOf(span(2).spanId), durable, "the store lacks the span its export reported saved") },
        )
    }

    @Test
    fun aRemoteSetMergedWhileATurnIsWritingIsKeptWhenTheTurnPublishes() = runTest {
        val backing = InMemoryDurableStore()
        val store = ParkingStore(backing, spansKey)
        val exporter = exporterOver(store)
        val remote = ORSet.empty<SpanRecord>().piece { it.add(peer, span(50)) }
        val holder = async { exporter.export(span(1)) }
        testScheduler.runCurrent()
        val merged = async { exporter.merge(remote) }
        testScheduler.runCurrent()
        val liveWhileWriting = ids(exporter)
        store.parking = false
        store.release()
        val results = listOf(holder.await(), merged.await())
        val durable = durableIds(backing)

        assertAll(
            // The rig: the merge joined while the holder's write was parked, before it published.
            { assertEquals(1, store.parked, "the rig: the holder never parked its write") },
            { assertEquals(setOf(span(50).spanId), liveWhileWriting, "the rig: the merge had not joined, or the holder had published") },
            { assertEquals(List(2) { ExportResult.Success }, results) },
            // The property: publishing joins the candidate into the merged set rather than replacing it.
            { assertEquals(setOf(span(1).spanId, span(50).spanId), ids(exporter), "the publish dropped the merged span") },
            { assertEquals(setOf(span(1).spanId, span(50).spanId), durable, "the store lacks a span") },
        )
    }
}
