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
        val windowAfterTwoRefusals = 3
        val backing = InMemoryDurableStore()
        val store = ReadRefusingStore(backing, clockKey)
        val clock = WarpCausalClock(replica)
        val exporter = WarpSpanExporter(replica, store, causalClock = clock)

        // Refused, skipped inside that refusal's window, refused again (#3436): a window of three.
        val refused = (1..turns).map { exporter.export(span(it.toByte())) }
        val whileRefused = exporter.health.value
        store.heal()
        exporter.skipClockWindow(windowAfterTwoRefusals, span(8))
        val healed = exporter.export(span(9))
        val afterHeal = exporter.health.value
        val clockEntry = backing.read(clockKey)

        assertAll(
            { assertEquals(2, store.refusals, "the rig: the clock's read was not refused on the first and third turns") },
            { assertEquals(List(turns) { ExportResult.Success }, refused, "the rig: a refused clock read refused the span turn") },
            { assertTrue(exporter.snapshot().elements.filter { it.spanId != span(9).spanId }.all { it.causalStamp == null }, "the rig: an unrecovered clock stamped") },
            { assertEquals(turns.toLong(), whileRefused.acknowledged, "the span work that landed was not acknowledged") },
            { assertEquals(turns.toLong(), whileRefused.failed, "a skipped clock write was not counted as a failed turn") },
            { assertEquals(turns.toLong(), whileRefused.outstandingFailures, "the clock's refusals were not outstanding: $whileRefused") },
            { assertEquals(ExportResult.Success, healed) },
            { assertNotNull(clockEntry, "the rig: the healed turn did not write the clock") },
            { assertEquals(0L, afterHeal.outstandingFailures, "the clock write that landed did not resolve its failures") },
            { assertEquals(turns.toLong() + windowAfterTwoRefusals, afterHeal.failed, "failed is cumulative") },
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
        // The refused read opened a window of one turn (#3436): this clear skips the clock's read.
        val skipped = exporter.clear()
        val clockAfterSkip = backing.read(clockKey)
        val healed = exporter.clear()

        assertAll(
            { assertEquals(1, store.refusals, "the rig: the clear made no refused clock read") },
            { assertEquals(ExportResult.Success, cleared) },
            { assertNotNull(spanEntry, "the rig: the clear did not write the spans") },
            { assertEquals(1L, whileRefused.acknowledged, "the clear's span work was not acknowledged") },
            { assertEquals(1L, whileRefused.outstandingFailures, "the clear's skipped clock write was not outstanding") },
            { assertEquals(ExportResult.Success, skipped) },
            { assertNull(clockAfterSkip, "the rig: the clear inside the window read and wrote the clock") },
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

    /** A span stamped by hand, so its turn wants no dot from the clock. */
    private fun explicitlyStamped(id: Byte) = span(id).copy(causalStamp = CausalStamp(Dot(ReplicaId("z"), id.toLong()), emptySet()))

    /**
     * A clock at `Long.MAX_VALUE` can mint no more (#3432), so each export that wanted a dot saves its
     * span unstamped and counts one failed clock turn. A turn that wanted none, a span with its own
     * stamp, is not counted: its clock write lands, so it resolves the exhaustion failures outstanding
     * before it, and the next attempt that wants a dot counts again.
     */
    @Test
    fun anExhaustedClockCountsEachAutoStampAttemptOnce() = runTest {
        val clock = WarpCausalClock(replica).armed().also { it.raiseFloor(Long.MAX_VALUE) }
        val exporter = WarpSpanExporter(replica, InMemoryDurableStore(), causalClock = clock)

        val tick = clock.tick()
        val attempts = listOf(exporter.export(span(1)), exporter.export(span(2)))
        val afterAttempts = exporter.health.value
        val explicit = exporter.export(explicitlyStamped(3))
        val afterExplicit = exporter.health.value
        val again = exporter.export(span(4))
        val afterAgain = exporter.health.value
        val unstamped = exporter.snapshot().elements.filter { it.spanId != span(3).spanId }

        assertAll(
            { assertEquals(ClockTick.Exhausted, tick, "the rig: the clock still mints") },
            { assertEquals(List(4) { ExportResult.Success }, attempts + explicit + again) },
            { assertTrue(unstamped.size == 3 && unstamped.all { it.causalStamp == null }, "the rig: an exhausted clock stamped") },
            { assertEquals(2L, afterAttempts.failed, "each exhausted attempt was not counted exactly once: $afterAttempts") },
            { assertEquals(2L, afterAttempts.outstandingFailures, "the exhausted attempts were not outstanding: $afterAttempts") },
            { assertEquals(2L, afterAttempts.acknowledged, "the attempts' span work was not acknowledged: $afterAttempts") },
            { assertEquals(2L, afterExplicit.failed, "a turn that wanted no dot counted an exhaustion: $afterExplicit") },
            { assertEquals(0L, afterExplicit.outstandingFailures, "the explicit turn's clock write did not resolve them: $afterExplicit") },
            { assertEquals(3L, afterAgain.failed, "the next attempt after it was not counted: $afterAgain") },
            { assertEquals(1L, afterAgain.outstandingFailures, "the next attempt after it was not outstanding: $afterAgain") },
            { assertTrue(afterAgain.lastFailure?.message.orEmpty().contains("Long.MAX_VALUE"), "lastFailure does not name the exhaustion: $afterAgain") },
        )
    }

    /**
     * The turn that mints the last dot, `Long.MAX_VALUE` itself, got its dot and saved a stamped span,
     * so it is not counted. Only the attempt after it, which gets none, is.
     */
    @Test
    fun theTurnThatMintsTheLastDotIsNotCounted() = runTest {
        val clock = WarpCausalClock(replica).armed().also { it.raiseFloor(Long.MAX_VALUE - 1) }
        val exporter = WarpSpanExporter(replica, InMemoryDurableStore(), causalClock = clock)

        val last = exporter.export(span(1))
        val lastStamp = exporter.snapshot().elements.single().causalStamp
        val afterLast = exporter.health.value
        val next = exporter.export(span(2))
        val afterNext = exporter.health.value

        assertAll(
            { assertEquals(ExportResult.Success, last) },
            { assertEquals(Long.MAX_VALUE, lastStamp?.dot?.seq, "the rig: the turn did not mint the last dot") },
            { assertEquals(0L, afterLast.failed, "the turn that got the last dot was counted failed: $afterLast") },
            { assertEquals(1L, afterLast.acknowledged, "the turn that got the last dot was not acknowledged: $afterLast") },
            { assertEquals(ExportResult.Success, next) },
            { assertEquals(1L, afterNext.failed, "the attempt after the last dot was not counted: $afterNext") },
        )
    }

    /**
     * A clear wants no dot, and its clock write lands, so on an exhausted clock it is not counted, and
     * it resolves the exhaustion an earlier export counted.
     */
    @Test
    fun aClearOnAnExhaustedClockIsNotCounted() = runTest {
        val backing = InMemoryDurableStore()
        val clock = WarpCausalClock(replica).armed().also { it.raiseFloor(Long.MAX_VALUE) }
        val exporter = WarpSpanExporter(replica, backing, causalClock = clock)

        val exported = exporter.export(span(1))
        val afterExport = exporter.health.value
        val cleared = exporter.clear()
        val afterClear = exporter.health.value
        val clockEntry = backing.read(clockKey)

        assertAll(
            { assertEquals(ExportResult.Success, exported) },
            { assertEquals(1L, afterExport.failed, "the rig: the exhausted export was not counted: $afterExport") },
            { assertEquals(ExportResult.Success, cleared) },
            { assertNotNull(clockEntry, "the rig: the clear did not write the clock") },
            { assertEquals(1L, afterClear.failed, "a clear on an exhausted clock was counted: $afterClear") },
            { assertEquals(2L, afterClear.acknowledged, "the clear was not acknowledged: $afterClear") },
            { assertEquals(0L, afterClear.outstandingFailures, "the clear's clock write did not resolve the exhaustion: $afterClear") },
        )
    }
}
