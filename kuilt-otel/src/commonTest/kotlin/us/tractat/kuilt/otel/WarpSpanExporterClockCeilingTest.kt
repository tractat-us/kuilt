@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.otel

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.DotMap
import us.tractat.kuilt.crdt.DotSet
import us.tractat.kuilt.crdt.ORSet
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.store.StoreKey
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * A span export the `ORSet` clock ceiling refuses changes nothing (#3441).
 *
 * Since #3429 [ORSet.add] throws an `ArithmeticException` rather than mint a dot past
 * [Long.MAX_VALUE]. A span set reaches the ceiling through [WarpSpanExporter.merge], whose join
 * takes the larger context, so one peer set naming this replica's dot at `MAX` is enough, and a
 * store that recorded such a join carries it into the next process. On a full buffer the export
 * owes an eviction as well as an add, and the eviction used to run first: the export dropped a
 * span in memory and then threw out of a method documented to return [ExportResult.Failure].
 *
 * The set's ceiling is not the causal clock's. The clock counts stamps and is far from `MAX` here,
 * so it mints; the refused add must not leave it advanced for a span nothing holds.
 *
 * Every arm asserts its rig: the set really is at the ceiling for this replica (an add on it
 * throws), the buffer really is full, and the refusal's cause is the ceiling's
 * `ArithmeticException`, not some other failure.
 */
class WarpSpanExporterClockCeilingTest {

    private val local = ReplicaId("local")
    private val spanKey = StoreKey("otel.spans")

    private fun span(id: Int) = SpanRecord(
        traceId = ByteString(ByteArray(16) { 1 }),
        spanId = ByteString(ByteArray(8) { i -> (id shr (8 * i)).toByte() }),
        parentSpanId = null,
        name = "span-$id",
        kind = SpanKind.INTERNAL,
        startEpochNanos = 1_000L + id,
        endEpochNanos = 2_000L + id,
    )

    /** A clock that has minted one stamp per span of a full buffer: far from its own ceiling. */
    private suspend fun clock(): WarpCausalClock = WarpCausalClock(local).armed()

    private val cbor = Cbor { alwaysUseByteString = true }
    private val setSerializer = ORSet.serializer(SpanRecord.serializer())

    private fun peerSetAtTheCeiling(held: ORSet<SpanRecord>): ORSet<SpanRecord> = spanSetAtTheCeiling(held, local)

    private fun ORSet<SpanRecord>.refusesAnAdd(): Boolean =
        assertFailsWith<ArithmeticException> { add(local, span(EXTRA)) }.let { true }

    private fun RecordingStore.mutations(): Int = operations().count { it.kind != StoreOpKind.READ }

    private suspend fun RecordingStore.storedSpans(): ORSet<SpanRecord>? =
        read(spanKey)?.let { cbor.decodeFromByteArray(setSerializer, it) }

    /** Export, catching what escapes it: the defect under test is that something does. */
    private suspend fun WarpSpanExporter.exportCatching(span: SpanRecord): Any = try {
        export(span)
    } catch (escaped: ArithmeticException) {
        escaped
    }

    /**
     * A recovered exporter at the cap whose set a peer's merge brought to the ceiling. Returns the
     * exporter; the caller's [clock] has minted [CAP] stamps.
     */
    private suspend fun fullExporterAtTheCeiling(
        store: RecordingStore,
        clock: WarpCausalClock?,
        policy: BufferPolicy,
    ): WarpSpanExporter {
        val exporter = WarpSpanExporter(local, store, maxSpans = CAP, bufferPolicy = policy, causalClock = clock)
        val filled = (0 until CAP).map { exporter.export(span(it)) }
        val merged = exporter.merge(peerSetAtTheCeiling(exporter.snapshot()))
        assertAll(
            { assertEquals(List(CAP) { ExportResult.Success }, filled, "the rig's spans land") },
            { assertEquals(ExportResult.Success, merged, "the rig's merge lands") },
            { assertEquals(CAP, exporter.snapshot().elements.size, "the rig's buffer is full") },
            { assertEquals(true, exporter.snapshot().refusesAnAdd(), "the rig's set is at the ceiling for this replica") },
        )
        return exporter
    }

    /**
     * The add on a recovered exporter, before the write section: at the ceiling it evicted a span
     * and threw out of [WarpSpanExporter.export].
     */
    @Test
    fun aRecoveredExportRefusedAtTheCeilingMutatesNothing() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val store = RecordingStore()
        val clock = clock()
        val exporter = fullExporterAtTheCeiling(store, clock, BufferPolicy.DROP_OLDEST)
        val before = exporter.snapshot()
        val healthBefore = exporter.health.value
        val frontierBefore = clock.frontier()
        val mutationsBefore = store.mutations()

        val refused = exporter.exportCatching(span(EXTRA))
        val after = exporter.snapshot()
        val healthAfter = exporter.health.value
        val frontierAfter = clock.frontier()
        val mutationsAfter = store.mutations()
        val restarted = WarpSpanExporter(local, store, maxSpans = CAP).also { it.recover() }.snapshot()
        // Last: a tick advances the clock. The CAP spans minted 1..CAP, so a clock the refusal left
        // alone mints CAP + 1 next.
        val nextSeq = clock.mint().dot.seq

        assertAll(
            { assertIs<ArithmeticException>(assertIs<ExportResult.Failure>(refused, "export threw instead of failing").cause, "the export reached the ceiling") },
            { assertEquals(before.elements, after.elements, "no span was evicted or added") },
            { assertEquals(before, after, "the set is unchanged") },
            { assertEquals(mutationsBefore, mutationsAfter, "the refused export wrote nothing") },
            { assertEquals(before.elements, restarted.elements, "a restart reads what memory holds") },
            { assertEquals(frontierBefore, frontierAfter, "the clock's frontier moved for a span nothing holds") },
            { assertEquals(CAP + 1L, nextSeq, "the clock was advanced for a span nothing holds") },
            { assertEquals(healthBefore.acknowledged, healthAfter.acknowledged, "the refusal acknowledged work") },
            { assertEquals(healthBefore.failed + 1, healthAfter.failed, "the refusal is one failed turn") },
            { assertIs<ArithmeticException>(healthAfter.lastFailure, "health names the ceiling") },
        )
    }

    /**
     * The add on a healing turn, inside the write section after the landing read (#3415, #3434):
     * at the ceiling it evicted a span in memory, and the throw was counted as a refused write.
     */
    @Test
    fun aHealingExportRefusedAtTheCeilingMutatesNothing() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val store = RecordingStore()
        val first = fullExporterAtTheCeiling(store, clock(), BufferPolicy.DROP_NEWEST)
        val stored = store.storedSpans()
        // A second process over the same store: its clock resumes from the stored entry, and the
        // exporter has not read its spans, so this export reads them inside the write section.
        val clock = WarpCausalClock(local)
        val clockRecovery = clock.recover(store)
        val exporter = WarpSpanExporter(local, store, maxSpans = CAP, bufferPolicy = BufferPolicy.DROP_NEWEST, causalClock = clock)
        val frontierBefore = clock.frontier()
        val mutationsBefore = store.mutations()

        val refused = exporter.exportCatching(span(EXTRA))
        val after = exporter.snapshot()
        val health = exporter.health.value
        val frontierAfter = clock.frontier()
        val mutationsAfter = store.mutations()
        val nextSeq = clock.mint().dot.seq

        assertAll(
            { assertEquals(ClockRecovery.Recovered, clockRecovery, "the rig's clock resumes from the store") },
            { assertEquals(first.snapshot(), stored, "the rig's store holds the full set at the ceiling") },
            { assertIs<ArithmeticException>(assertIs<ExportResult.Failure>(refused, "export threw instead of failing").cause, "the export reached the ceiling") },
            { assertEquals(stored, after, "the landing read joined the stored set and nothing was evicted or added") },
            { assertEquals(mutationsBefore, mutationsAfter, "the refused export wrote nothing") },
            { assertEquals(frontierBefore, frontierAfter, "the clock's frontier moved for a span nothing holds") },
            { assertEquals(CAP + 1L, nextSeq, "the clock was advanced for a span nothing holds") },
            { assertEquals(0L, health.acknowledged, "the refusal acknowledged work") },
            { assertEquals(1L, health.failed, "the refusal is one failed turn") },
            { assertIs<ArithmeticException>(health.lastFailure, "health names the ceiling") },
        )
    }

    /** With no clock the span is saved as given, and the refusal still changes nothing. */
    @Test
    fun anUnstampedExportRefusedAtTheCeilingMutatesNothing() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val store = RecordingStore()
        val exporter = fullExporterAtTheCeiling(store, clock = null, BufferPolicy.DROP_OLDEST)
        val before = exporter.snapshot()
        val mutationsBefore = store.mutations()

        val refused = exporter.exportCatching(span(EXTRA))

        assertAll(
            { assertIs<ArithmeticException>(assertIs<ExportResult.Failure>(refused, "export threw instead of failing").cause, "the export reached the ceiling") },
            { assertEquals(before, exporter.snapshot(), "no span was evicted or added") },
            { assertEquals(mutationsBefore, store.mutations(), "the refused export wrote nothing") },
        )
    }

    private companion object {
        const val CAP = 3
        const val EXTRA = 99
    }
}

/**
 * A span set's serial shape, with the context written by hand: an [ORSet]'s context is reachable
 * at the ceiling only by decoding, since minting `1..MAX` is not.
 */
@Serializable
private class ContextShape(val vv: Map<ReplicaId, Long>, val cloud: List<Dot>)

@Serializable
private class CausalShape(val store: DotMap<SpanRecord, DotSet>, val context: ContextShape)

@Serializable
private class SetShape(val causal: CausalShape)

private val shapeCbor = Cbor { alwaysUseByteString = true }

/**
 * A peer's copy of [held]: the same spans, under a context that has seen every dot of [replica]'s
 * up to [Long.MAX_VALUE], so the next add on [replica]'s behalf would wrap. It must carry the spans,
 * since a context covering their dots without them would be a peer that removed them.
 */
internal fun spanSetAtTheCeiling(held: ORSet<SpanRecord>, replica: ReplicaId): ORSet<SpanRecord> {
    val setSerializer = ORSet.serializer(SpanRecord.serializer())
    val shape = shapeCbor.decodeFromByteArray(SetShape.serializer(), shapeCbor.encodeToByteArray(setSerializer, held))
    val context = ContextShape(shape.causal.context.vv + (replica to Long.MAX_VALUE), shape.causal.context.cloud)
    val raised = SetShape(CausalShape(shape.causal.store, context))
    return shapeCbor.decodeFromByteArray(setSerializer, shapeCbor.encodeToByteArray(SetShape.serializer(), raised))
}
