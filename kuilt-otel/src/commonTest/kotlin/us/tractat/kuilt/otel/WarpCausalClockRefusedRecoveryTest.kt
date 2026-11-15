package us.tractat.kuilt.otel

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.InMemoryDurableStore
import us.tractat.kuilt.store.StoreKey
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins that a **refused** recovery read never lets a later write overwrite the clock's durable
 * high-water mark, so no dot is minted twice (#3390, the uniqueness #1053 protects).
 *
 * Before the fix a refused read left the clock at `seq=0`: the next [WarpCausalClock.tick] re-minted
 * `seq=1`, the next [WarpCausalClock.persist] wrote it over the durable `seq`, and every restart after
 * that re-minted again. The rule now is fail closed: an unrecovered clock mints nothing and persists
 * nothing, a later successful read re-arms it, and a *decode* failure still starts fresh.
 *
 * Every arm asserts its rig fired, so a store that never refused cannot pass it by absence.
 */
class WarpCausalClockRefusedRecoveryTest {

    private val replica = ReplicaId("a")
    private val clockKey = WarpCausalClock.STORE_KEY

    private fun span(id: Byte) = SpanRecord(
        ByteString(ByteArray(16) { 1 }), ByteString(ByteArray(8) { id }), null,
        "span-$id", SpanKind.INTERNAL, id.toLong(), id.toLong() + 1,
    )

    /** A clock that minted `seq=1` and `seq=2` and persisted them, as a previous process did. */
    private suspend fun durableClockAtTwo(store: DurableStore): List<Dot> {
        val previous = WarpCausalClock(replica).armed()
        val used = listOf(previous.mint().dot, previous.mint().dot)
        previous.persist(store)
        return used
    }

    /** What the next healthy restart mints first: the durable high-water mark plus one. */
    private suspend fun nextDotAfterRestart(store: DurableStore): Dot =
        WarpCausalClock(replica).also { it.recover(store) }.mint().dot

    /** The dot [WarpCausalClock.tick] minted, or `null` when it answered unrecovered. */
    private fun WarpCausalClock.mintedDot(): Dot? = when (val tick = tick()) {
        is ClockTick.Minted -> tick.stamp.dot
        ClockTick.Unrecovered -> null
    }

    // ---- The clock on its own ----

    @Test
    fun aRefusedRecoveryReadNeverLetsTheClockReMintADurableDot() = runTest {
        val backing = InMemoryDurableStore()
        val used = durableClockAtTwo(backing).toMutableList()
        val store = ReadRefusingStore(backing, clockKey)

        val restarted = WarpCausalClock(replica)
        val recovery = restarted.recover(store)
        val refusedReads = store.refusals
        restarted.mintedDot()?.let { used += it }
        val persisted = restarted.persist(store)
        used += nextDotAfterRestart(backing)

        assertAll(
            { assertEquals(1, refusedReads, "the rig did not refuse the recovery read") },
            { assertIs<ClockRecovery.Refused>(recovery, "the refused read was reported as $recovery") },
            { assertEquals(used.size, used.toSet().size, "a dot was minted twice: $used") },
            { assertEquals(ClockPersist.Unrecovered, persisted, "an unrecovered clock persisted over the durable high-water mark") },
            { assertEquals(3L, used.last().seq, "the next healthy restart did not resume after seq=2") },
        )
    }

    /**
     * The round-2 review's case: a clock that ticks before any read lands does not know the durable
     * `seq` either. Minting there took it to `Armed(1)`, which a refused read then left alone, and the
     * next persist wrote 1 over the durable 2.
     */
    @Test
    fun aClockThatTicksBeforeAnyReadMintsAndPersistsNothing() = runTest {
        val backing = InMemoryDurableStore()
        val used = durableClockAtTwo(backing).toMutableList()
        val store = ReadRefusingStore(backing, clockKey)

        val fresh = WarpCausalClock(replica)
        val early = fresh.tick()
        (early as? ClockTick.Minted)?.let { used += it.stamp.dot }
        val earlyPersist = fresh.persist(store)
        val recovery = fresh.recover(store)
        val persisted = fresh.persist(store)
        val next = nextDotAfterRestart(backing)
        used += next

        assertAll(
            { assertEquals(1, store.refusals, "the rig did not refuse the recovery read") },
            { assertIs<ClockRecovery.Refused>(recovery, "the refused read was reported as $recovery") },
            { assertEquals(ClockTick.Unrecovered, early, "a clock with no landed read minted") },
            { assertEquals(ClockPersist.Unrecovered, earlyPersist, "a clock that never read persisted") },
            { assertEquals(ClockPersist.Unrecovered, persisted, "a clock with no landed read persisted") },
            { assertEquals(used.size, used.toSet().size, "a dot was minted twice: $used") },
            { assertTrue(next.seq >= 3L, "the next restart did not resume after seq=2: $next") },
        )
    }

    @Test
    fun aLaterSuccessfulRecoveryReArmsTheClockWithWhatItObservedMeanwhile() = runTest {
        val backing = InMemoryDurableStore()
        val durable = durableClockAtTwo(backing)
        val store = ReadRefusingStore(backing, clockKey)
        val remote = Dot(ReplicaId("b"), 7L)

        val clock = WarpCausalClock(replica)
        clock.recover(store)
        val refusedReads = store.refusals
        clock.observe(setOf(remote))
        store.heal()
        clock.recover(store)
        val stamp = clock.mint()

        assertAll(
            { assertEquals(1, refusedReads, "the rig did not refuse the first recovery read") },
            { assertEquals(Dot(replica, 3L), stamp.dot, "the re-armed clock did not resume after the durable seq") },
            { assertEquals(setOf(durable.last(), remote), stamp.predecessors, "the re-armed frontier lost a dot") },
        )
    }

    /**
     * The re-arm is a join, not a replace: the span exporter's retry can race a second retry, and a
     * read older than what the clock has already minted must not wind it back.
     */
    @Test
    fun aReadOlderThanWhatTheClockMintedDoesNotWindItBack() = runTest {
        val store = InMemoryDurableStore()
        val durable = durableClockAtTwo(store)
        val clock = WarpCausalClock(replica)

        clock.recover(store)
        val minted = listOf(clock.mint().dot, clock.mint().dot)
        clock.recover(store)
        val next = clock.mint().dot
        val used = durable + minted + next

        assertAll(
            { assertEquals(listOf(3L, 4L), minted.map { it.seq }, "the rig: the clock did not mint past the entry") },
            { assertEquals(used.size, used.toSet().size, "a second read wound the clock back: $used") },
        )
    }

    @Test
    fun aCorruptEntryStillStartsTheClockFresh() = runTest {
        val store = InMemoryDurableStore()
        store.write(clockKey, byteArrayOf(0x7f, 0x00, 0x13))

        val clock = WarpCausalClock(replica)
        val recovery = clock.recover(store)
        val first = clock.mintedDot()
        val persisted = clock.persist(store)

        assertAll(
            { assertIs<ClockRecovery.Undecodable>(recovery, "the rig: the corrupt entry was reported as $recovery") },
            { assertEquals(Dot(replica, 1L), first, "a clock over a corrupt entry is dead instead of fresh") },
            { assertEquals(ClockPersist.Persisted, persisted, "a clock over a corrupt entry cannot repair it") },
        )
    }

    // ---- The clock as the span exporter stamps from it ----

    @Test
    fun aSpanExportedAfterARefusedClockReadReusesNoDurableDot() = runTest {
        val backing = InMemoryDurableStore()
        WarpTelemetry(replica, backing).also { it.recover() }.spans.apply {
            export(span(1))
            export(span(2))
        }
        val store = ReadRefusingStore(backing, clockKey)

        val restarted = WarpTelemetry(replica, store).also { it.recover() }
        val refusedRecovery = store.refusals
        val result = restarted.spans.export(span(3))
        val unstamped = restarted.spans.snapshot().elements.single { it.spanId == span(3).spanId }.causalStamp
        store.heal()
        WarpTelemetry(replica, backing).also { it.recover() }.spans.export(span(4))
        val durable = WarpTelemetry(replica, backing).also { it.recover() }.spans.snapshot().elements
        val dots = durable.mapNotNull { it.causalStamp?.dot }

        assertAll(
            { assertEquals(1, refusedRecovery, "the rig did not refuse the clock's recovery read") },
            { assertTrue(store.refusals > refusedRecovery, "the export did not retry the refused read") },
            { assertEquals(ExportResult.Success, result, "an unrecovered clock stopped the span itself being saved") },
            { assertNull(unstamped, "an unrecovered clock stamped a span") },
            { assertEquals(4, durable.size, "a durable span was lost: ${durable.map { it.name }}") },
            { assertEquals(dots.size, dots.toSet().size, "two durable spans share a dot: $dots") },
        )
    }

    /**
     * An export that stamps nothing still persists the clock. A clock that was never recovered must
     * not write then, or that turn puts `seq=0` over the durable mark.
     */
    @Test
    fun anExportThatDoesNotTickNeverWritesANeverRecoveredClock() = runTest {
        val store = InMemoryDurableStore()
        durableClockAtTwo(store)
        val exporter = WarpSpanExporter(replica, store, causalClock = WarpCausalClock(replica))
        val stamped = span(1).copy(causalStamp = CausalStamp(Dot(ReplicaId("b"), 1L), emptySet()))

        val result = exporter.export(stamped)
        val next = nextDotAfterRestart(store)

        assertAll(
            { assertEquals(ExportResult.Success, result) },
            { assertEquals(stamped.causalStamp, exporter.snapshot().elements.single().causalStamp, "the rig: the export restamped") },
            { assertEquals(Dot(replica, 3L), next, "the export wrote over the durable seq") },
        )
    }

    /** The same for a clear, whose write section persists the clock without ticking it. */
    @Test
    fun aClearReadsANeverRecoveredClockBeforeWritingIt() = runTest {
        val store = InMemoryDurableStore()
        durableClockAtTwo(store)
        val exporter = WarpSpanExporter(replica, store, causalClock = WarpCausalClock(replica))

        val result = exporter.clear()
        val next = nextDotAfterRestart(store)

        assertAll(
            { assertEquals(ExportResult.Success, result) },
            { assertEquals(Dot(replica, 3L), next, "the clear wrote over the durable seq") },
        )
    }

    @Test
    fun theNextExportRetriesARefusedClockReadAndStampsAfterTheDurableSeq() = runTest {
        val backing = InMemoryDurableStore()
        WarpTelemetry(replica, backing).also { it.recover() }.spans.apply {
            export(span(1))
            export(span(2))
        }
        val store = CancellationScriptedStore(backing)
        store.arm(StoreCall.READ, StoreAct.MINT) { it == clockKey }

        val restarted = WarpTelemetry(replica, store).also { it.recover() }
        val result = restarted.spans.export(span(3))
        val stamp = restarted.spans.snapshot().elements.single { it.spanId == span(3).spanId }.causalStamp

        assertAll(
            { assertEquals(1, store.minted, "the rig never minted on the clock's recovery read") },
            { assertTrue(restarted.spans.health.value.recoveryFailed, "the refused read was not reported") },
            { assertEquals(ExportResult.Success, result) },
            { assertEquals(Dot(replica, 3L), stamp?.dot, "the export did not re-arm the clock from the durable seq") },
        )
    }

    @Test
    fun aClearAfterARefusedClockReadKeepsTheDurableHighWaterMark() = runTest {
        val backing = InMemoryDurableStore()
        durableClockAtTwo(backing)
        val store = ReadRefusingStore(backing, clockKey)
        val clock = WarpCausalClock(replica)
        val exporter = WarpSpanExporter(replica, store, causalClock = clock)

        clock.recover(store)
        val refusedReads = store.refusals
        val cleared = exporter.clear()
        store.heal()
        val next = nextDotAfterRestart(backing)

        assertAll(
            { assertEquals(1, refusedReads, "the rig did not refuse the clock's recovery read") },
            { assertEquals(ExportResult.Success, cleared, "the span half of the clear did not land") },
            { assertEquals(Dot(replica, 3L), next, "the clear overwrote the durable seq") },
        )
    }

    /** The clear retries the read too, so once it lands the clear's empty frontier is what persists. */
    @Test
    fun aClearRetriesARefusedClockReadAndPersistsItsEmptyFrontier() = runTest {
        val backing = InMemoryDurableStore()
        durableClockAtTwo(backing)
        val store = CancellationScriptedStore(backing)
        store.arm(StoreCall.READ, StoreAct.MINT) { it == clockKey }
        val clock = WarpCausalClock(replica)
        val exporter = WarpSpanExporter(replica, store, causalClock = clock)

        clock.recover(store)
        val cleared = exporter.clear()
        val persisted = WarpCausalClock(replica).also { it.recover(backing) }

        assertAll(
            { assertEquals(1, store.minted, "the rig never minted on the clock's recovery read") },
            { assertEquals(ExportResult.Success, cleared) },
            { assertEquals(emptySet(), persisted.frontier(), "the clear left the pre-clear frontier durable") },
            { assertEquals(Dot(replica, 3L), persisted.mint().dot, "the clear overwrote the durable seq") },
        )
    }

    // ---- A refusal never demotes a clock that has already read or minted ----

    /**
     * The interleaving from the #3399 review. B's export persists the clock at 1 and parks on its
     * span write. A ticks 2 outside the write mutex and queues behind it. A concurrent `recover` is
     * refused. If that refusal demoted the clock, A's turn would skip the clock write while its
     * span snapshot carried dot 2, so a restart would re-mint 2.
     */
    @Test
    fun aRefusedSecondRecoveryNeverDemotesAClockMidExport() = runTest {
        val backing = InMemoryDurableStore()
        val gated = GatedWriteStore(backing, StoreKey("otel.spans"))
        val store = ReadRefusingStore(gated, clockKey, refusingAtStart = false)
        val clock = WarpCausalClock(replica)
        val exporter = WarpSpanExporter(replica, store, causalClock = clock)
        clock.recover(store)

        val b = launch { exporter.export(span(1)) }
        runCurrent()
        val parkedOnB = gated.parked
        val a = launch { exporter.export(span(2)) }
        runCurrent()
        store.refuse()
        clock.recover(store)
        val refusedReads = store.refusals
        store.heal()
        gated.release()
        b.join()
        a.join()

        val durable = WarpSpanExporter(replica, backing).also { it.recover() }.snapshot().elements
        val used = durable.mapNotNull { it.causalStamp?.dot } + nextDotAfterRestart(backing)
        assertAll(
            { assertEquals(1, parkedOnB, "the rig never parked B's span write") },
            { assertEquals(1, refusedReads, "the rig did not refuse the concurrent recovery read") },
            { assertEquals(2, durable.size, "a span write did not land") },
            { assertEquals(used.size, used.toSet().size, "a restart re-minted a durable dot: $used") },
        )
    }

    // ---- A clear while the read is refused ----

    /** The clear dropped the frontier, so the read that re-arms the clock must not bring it back. */
    @Test
    fun aClearMadeWhileTheReadIsRefusedIsNotUndoneByTheReArm() = runTest {
        val backing = InMemoryDurableStore()
        durableClockAtTwo(backing)
        val store = ReadRefusingStore(backing, clockKey)
        val clock = WarpCausalClock(replica)
        val exporter = WarpSpanExporter(replica, store, causalClock = clock)

        clock.recover(store)
        val cleared = exporter.clear()
        val refusedReads = store.refusals
        store.heal()
        exporter.export(span(1))
        val stamp = exporter.snapshot().elements.single().causalStamp

        assertAll(
            { assertEquals(2, refusedReads, "the rig did not refuse both the recovery and the clear's retry") },
            { assertEquals(ExportResult.Success, cleared) },
            { assertEquals(Dot(replica, 3L), stamp?.dot, "the export did not re-arm the clock") },
            { assertEquals(emptySet(), stamp?.predecessors, "the re-arm restored the frontier the clear dropped") },
        )
    }

    // ---- What a turn with an unrecovered clock acknowledges ----

    /**
     * A turn whose clock write was skipped needed only the span key, so when it fails, a later
     * span-only write that lands resolves it. Counted against the clock key too, it would stay
     * outstanding for good: an unrecovered clock writes nothing that could resolve it.
     *
     * The converse the round-1 review asked for, a skipped turn resolving an earlier clock-plus-span
     * failure, is now unreachable: a clock-plus-span turn needs an armed clock, and an armed clock is
     * never demoted, so no skipped turn can follow one.
     */
    @Test
    fun aFailedTurnThatSkippedTheClockWriteIsResolvedByASpanOnlyWrite() = runTest {
        val backing = InMemoryDurableStore()
        val store = CancellationScriptedStore(ReadRefusingStore(backing, clockKey))
        val clock = WarpCausalClock(replica)
        val exporter = WarpSpanExporter(replica, store, causalClock = clock)
        val remote = WarpSpanExporter(ReplicaId("b"), InMemoryDurableStore()).also { it.export(span(2)) }.snapshot()

        store.arm(StoreCall.WRITE, StoreAct.MINT) { it == StoreKey("otel.spans") }
        val failedTurn = exporter.export(span(1))
        val outstandingAfterFailure = exporter.health.value.outstandingFailures
        val merged = exporter.merge(remote)

        assertAll(
            { assertEquals(1, store.minted, "the rig never refused the export's span write") },
            { assertIs<ExportResult.Failure>(failedTurn, "the rig: the export did not fail") },
            { assertTrue(clock.frontier().isEmpty() && exporter.snapshot().elements.none { it.causalStamp != null }, "the rig: the clock stamped") },
            { assertEquals(1L, outstandingAfterFailure, "the rig: the failed turn was not outstanding") },
            { assertEquals(ExportResult.Success, merged) },
            { assertEquals(0L, exporter.health.value.outstandingFailures, "a span-only write left a span-only failure outstanding") },
        )
    }
}

/**
 * Delegates to [backing], but parks the first write of [gatedKey] until [release], so a test can
 * hold one export inside its write section while another queues behind it.
 */
private class GatedWriteStore(private val backing: DurableStore, private val gatedKey: StoreKey) : DurableStore {
    private val lock = reentrantLock()
    private val gate = CompletableDeferred<Unit>()
    private var armed = true
    private var parkedCount = 0

    val parked: Int get() = lock.withLock { parkedCount }

    fun release() {
        gate.complete(Unit)
    }

    override suspend fun read(key: StoreKey): ByteArray? = backing.read(key)

    override suspend fun write(key: StoreKey, bytes: ByteArray) {
        val park = lock.withLock { (armed && key == gatedKey).also { if (it) { armed = false; parkedCount++ } } }
        if (park) gate.await()
        backing.write(key, bytes)
    }

    override suspend fun delete(key: StoreKey): Unit = backing.delete(key)
}

/**
 * Delegates to [backing], but refuses every read of [refused] until [heal] — a store whose read
 * keeps failing for a while, as a locked or quota-bound one does. Counts its [refusals] so an arm
 * can assert its rig fired.
 */
private class ReadRefusingStore(
    private val backing: DurableStore,
    private val refused: StoreKey,
    refusingAtStart: Boolean = true,
) : DurableStore {
    private val lock = reentrantLock()
    private var refusing = refusingAtStart
    private var refusalCount = 0

    val refusals: Int get() = lock.withLock { refusalCount }

    fun heal(): Unit = lock.withLock { refusing = false }

    fun refuse(): Unit = lock.withLock { refusing = true }

    override suspend fun read(key: StoreKey): ByteArray? {
        val refuse = lock.withLock { (refusing && key == refused).also { if (it) refusalCount++ } }
        if (refuse) throw IllegalStateException("simulated refused read of ${key.name}")
        return backing.read(key)
    }

    override suspend fun write(key: StoreKey, bytes: ByteArray): Unit = backing.write(key, bytes)

    override suspend fun delete(key: StoreKey): Unit = backing.delete(key)
}
