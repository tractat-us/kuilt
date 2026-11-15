package us.tractat.kuilt.otel

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.store.StoreKey
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins that a causal clock whose work a span turn skips is visible in [WarpSpanExporter.health]
 * (#3426). A clock whose read the store refuses forever used to leave `failed = 0` and
 * `outstandingFailures = 0`: the span turn landed, skipped the clock's entry, and was acknowledged
 * as span-only work, so the clock reached health only through the sticky `recoveryFailed`.
 *
 * The rule now: a turn's verdict is per work key. The span work is acknowledged or failed by its own
 * write; clock work the turn skipped is a failed turn under the clock's key, so only a turn that
 * lands the clock's write resolves it. A span-only [WarpSpanExporter.merge] never does.
 *
 * Every arm asserts its rig fired, so a store that never refused cannot pass it by absence.
 */
class WarpSpanExporterClockHealthTest {

    private val replica = ReplicaId("a")
    private val clockKey = WarpCausalClock.STORE_KEY
    private val spanKey = StoreKey("otel.spans")

    private fun span(id: Byte) = SpanRecord(
        ByteString(ByteArray(16) { 1 }), ByteString(ByteArray(8) { id }), null,
        "span-$id", SpanKind.INTERNAL, id.toLong(), id.toLong() + 1,
    )

    private fun remoteSet() = WarpSpanExporter(ReplicaId("b"), InMemoryDurableStore())

    /**
     * N turns land their spans unstamped while the clock's read is refused. Each is one acknowledged
     * span turn and one failed clock turn, and the N clock failures stay outstanding until the clock's
     * write lands, then all resolve at once.
     */
    @Test
    fun aClockWhoseReadIsRefusedStaysOutstandingUntilItsWriteLands() = runTest {
        val turns = 3
        val backing = InMemoryDurableStore()
        val store = ReadRefusingStore(backing, clockKey)
        val clock = WarpCausalClock(replica)
        val exporter = WarpSpanExporter(replica, store, causalClock = clock)

        val refused = (1..turns).map { exporter.export(span(it.toByte())) }
        val whileRefused = exporter.health.value
        store.heal()
        val healed = exporter.export(span(9))
        val afterHeal = exporter.health.value
        val clockEntry = backing.read(clockKey)

        assertAll(
            { assertEquals(turns, store.refusals, "the rig: the clock's read was not refused once per turn") },
            { assertEquals(List(turns) { ExportResult.Success }, refused, "the rig: a refused clock read refused the span turn") },
            { assertTrue(exporter.snapshot().elements.filter { it.spanId != span(9).spanId }.all { it.causalStamp == null }, "the rig: an unrecovered clock stamped") },
            { assertEquals(turns.toLong(), whileRefused.acknowledged, "the span work that landed was not acknowledged") },
            { assertEquals(turns.toLong(), whileRefused.failed, "a skipped clock write was not counted as a failed turn") },
            { assertEquals(turns.toLong(), whileRefused.outstandingFailures, "the clock's refusals were not outstanding: $whileRefused") },
            { assertEquals(ExportResult.Success, healed) },
            { assertNotNull(clockEntry, "the rig: the healed turn did not write the clock") },
            { assertEquals(0L, afterHeal.outstandingFailures, "the clock write that landed did not resolve its failures") },
            { assertEquals(turns.toLong(), afterHeal.failed, "failed is cumulative") },
        )
    }

    /**
     * A span with its own stamp needs no dot, but its turn still writes the configured clock, so it
     * makes the clock's read too. Otherwise a run of explicitly stamped spans after a transient
     * refusal at recovery would never arm the clock, and each turn would count a clock failure the
     * store had already healed, with [PersistenceHealth.lastFailure] naming no real cause.
     */
    @Test
    fun anExplicitlyStampedTurnRetriesTheClockReadAndResolvesItsFailure() = runTest {
        val backing = InMemoryDurableStore()
        val store = ReadRefusingStore(backing, clockKey)
        val clock = WarpCausalClock(replica)
        val exporter = WarpSpanExporter(replica, store, causalClock = clock)
        val explicit = span(2).copy(causalStamp = CausalStamp(Dot(ReplicaId("z"), 5L), emptySet()))

        val recovery = clock.recover(store).also(exporter::clockRecovered)
        exporter.recover()
        val autoStamped = exporter.export(span(1))
        val outstandingWhileRefused = exporter.health.value.outstandingFailures
        store.heal()
        val healed = exporter.export(explicit)
        val afterHeal = exporter.health.value
        val clockEntry = backing.read(clockKey)

        assertAll(
            { assertIs<ClockRecovery.Refused>(recovery, "the rig: the clock's recovery read was not refused") },
            { assertEquals(ExportResult.Success, autoStamped) },
            { assertEquals(1L, outstandingWhileRefused, "the rig: the refused turn's clock work was not outstanding") },
            { assertEquals(ExportResult.Success, healed) },
            { assertTrue(clock.isArmed, "the explicitly stamped turn did not retry the clock's read") },
            { assertNotNull(clockEntry, "the explicitly stamped turn did not write the clock") },
            { assertEquals(0L, afterHeal.outstandingFailures, "the clock write that landed did not resolve its failure: $afterHeal") },
            { assertEquals(1L, afterHeal.failed, "the healed turn counted a clock failure: $afterHeal") },
            {
                assertEquals(
                    "simulated refused read of ${clockKey.name}",
                    afterHeal.lastFailure?.message,
                    "lastFailure lost the store's real cause",
                )
            },
        )
    }

    /** A span-only merge lands no clock work, so it resolves none of the clock's failures. */
    @Test
    fun aSpanOnlyMergeDoesNotResolveASkippedClockWrite() = runTest {
        val store = ReadRefusingStore(InMemoryDurableStore(), clockKey)
        val exporter = WarpSpanExporter(replica, store, causalClock = WarpCausalClock(replica))
        val remote = remoteSet().also { it.export(span(7)) }.snapshot()

        val exported = exporter.export(span(1))
        val merged = exporter.merge(remote)

        assertAll(
            { assertEquals(1, store.refusals, "the rig: the clock's read was not refused") },
            { assertEquals(ExportResult.Success, exported) },
            { assertEquals(ExportResult.Success, merged, "the rig: the merge did not land") },
            { assertEquals(1L, exporter.health.value.outstandingFailures, "a span-only write resolved the clock's failure") },
        )
    }

    /** A clear whose clock read is still refused writes the spans and skips the clock: the same verdict. */
    @Test
    fun aClearThatSkipsTheClockWriteCountsTheClockAsFailed() = runTest {
        val backing = InMemoryDurableStore()
        val store = ReadRefusingStore(backing, clockKey)
        val exporter = WarpSpanExporter(replica, store, causalClock = WarpCausalClock(replica))

        val cleared = exporter.clear()
        val whileRefused = exporter.health.value
        val spanEntry = backing.read(spanKey)
        store.heal()
        val healed = exporter.clear()

        assertAll(
            { assertEquals(1, store.refusals, "the rig: the clear made no refused clock read") },
            { assertEquals(ExportResult.Success, cleared) },
            { assertNotNull(spanEntry, "the rig: the clear did not write the spans") },
            { assertEquals(1L, whileRefused.acknowledged, "the clear's span work was not acknowledged") },
            { assertEquals(1L, whileRefused.outstandingFailures, "the clear's skipped clock write was not outstanding") },
            { assertEquals(ExportResult.Success, healed) },
            { assertEquals(0L, exporter.health.value.outstandingFailures, "the healed clear's clock write did not resolve it") },
        )
    }

    /**
     * A refused clock *write* fails the whole turn: the clock is written before the spans (#1053),
     * so the span write is never made and the turn's work is both keys at once. A span-only merge
     * cannot resolve it, and the turn that lands both writes does.
     */
    @Test
    fun aRefusedClockWriteStaysOutstandingUntilAClockWriteLands() = runTest {
        val backing = InMemoryDurableStore()
        val store = CancellationScriptedStore(backing)
        val exporter = WarpSpanExporter(replica, store, causalClock = WarpCausalClock(replica).armed())
        val remote = remoteSet().also { it.export(span(7)) }.snapshot()

        store.arm(StoreCall.WRITE, StoreAct.MINT) { it == clockKey }
        val refused = exporter.export(span(1))
        val spansAfterRefusal = backing.read(spanKey)
        val outstandingAfterRefusal = exporter.health.value.outstandingFailures
        val merged = exporter.merge(remote)
        val outstandingAfterMerge = exporter.health.value.outstandingFailures
        val healed = exporter.export(span(2))

        assertAll(
            { assertEquals(1, store.minted, "the rig never refused the clock's write") },
            { assertIs<ExportResult.Failure>(refused) },
            { assertNull(spansAfterRefusal, "a span write followed a refused clock write") },
            { assertEquals(1L, outstandingAfterRefusal, "the refused clock write was not outstanding") },
            { assertEquals(ExportResult.Success, merged, "the rig: the merge did not land") },
            { assertEquals(1L, outstandingAfterMerge, "a span-only write resolved the refused clock write") },
            { assertEquals(ExportResult.Success, healed) },
            { assertEquals(0L, exporter.health.value.outstandingFailures, "the clock write that landed did not resolve it") },
        )
    }

    /**
     * A clock at `Long.MAX_VALUE` can mint no more (#3432), so every later span is saved unstamped,
     * and nothing the clock writes changes that: `seq` never moves down, a clear included, and a
     * restart reads the same `seq` back. So every turn counts its clock work as failed, and a clear
     * that writes the exhausted clock's entry resolves none of it.
     */
    @Test
    fun anExhaustedClockStaysOutstandingWhateverItWrites() = runTest {
        val backing = InMemoryDurableStore()
        val clock = WarpCausalClock(replica).armed().also { it.raiseFloor(Long.MAX_VALUE) }
        val exporter = WarpSpanExporter(replica, backing, causalClock = clock)

        val tick = clock.tick()
        val exported = exporter.export(span(1))
        val saved = exporter.snapshot().elements.single()
        val afterExport = exporter.health.value
        val cleared = exporter.clear()
        val clockEntry = backing.read(clockKey)

        assertAll(
            { assertEquals(ClockTick.Exhausted, tick, "the rig: the clock still mints") },
            { assertEquals(ExportResult.Success, exported) },
            { assertNull(saved.causalStamp, "the rig: an exhausted clock stamped") },
            { assertEquals(1L, afterExport.acknowledged, "the export's span work was not acknowledged") },
            { assertEquals(1L, afterExport.outstandingFailures, "an exhausted clock was not outstanding") },
            { assertEquals(ExportResult.Success, cleared) },
            { assertNotNull(clockEntry, "the rig: the clear did not write the clock") },
            { assertEquals(2L, exporter.health.value.outstandingFailures, "writing an exhausted clock resolved it") },
        )
    }
}
