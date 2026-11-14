package us.tractat.kuilt.otel

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
        val previous = WarpCausalClock(replica)
        val used = listOf(previous.tick().dot, previous.tick().dot)
        previous.persist(store)
        return used
    }

    /** What the next healthy restart mints first: the durable high-water mark plus one. */
    private suspend fun nextDotAfterRestart(store: DurableStore): Dot =
        WarpCausalClock(replica).also { it.recover(store) }.tick().dot

    private fun WarpCausalClock.tickUnlessRefused(): Dot? = try {
        tick().dot
    } catch (_: IllegalStateException) { // ALLOW-ise: nothing in the try can suspend — tick() is not `suspend`
        null
    }

    private suspend fun WarpCausalClock.persistUnlessRefused(store: DurableStore): Boolean = try {
        persist(store)
        true
    } catch (_: IllegalStateException) {
        currentCoroutineContext().ensureActive()
        false
    }

    // ---- The clock on its own ----

    @Test
    fun aRefusedRecoveryReadNeverLetsTheClockReMintADurableDot() = runTest {
        val backing = InMemoryDurableStore()
        val used = durableClockAtTwo(backing).toMutableList()
        val store = ReadRefusingStore(backing, clockKey)

        val restarted = WarpCausalClock(replica)
        restarted.recover(store)
        val refusedReads = store.refusals
        restarted.tickUnlessRefused()?.let { used += it }
        val persisted = restarted.persistUnlessRefused(store)
        used += nextDotAfterRestart(backing)

        assertAll(
            { assertEquals(1, refusedReads, "the rig did not refuse the recovery read") },
            { assertEquals(used.size, used.toSet().size, "a dot was minted twice: $used") },
            { assertTrue(!persisted, "an unrecovered clock persisted over the durable high-water mark") },
            { assertEquals(3L, used.last().seq, "the next healthy restart did not resume after seq=2") },
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
        val stamp = clock.tick()

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
        val minted = listOf(clock.tick().dot, clock.tick().dot)
        clock.recover(store)
        val next = clock.tick().dot
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
        val failures = mutableListOf<Throwable>()

        val clock = WarpCausalClock(replica)
        clock.recover(store) { failures += it }
        val first = clock.tickUnlessRefused()
        val persisted = clock.persistUnlessRefused(store)

        assertAll(
            { assertEquals(1, failures.size, "the rig: the corrupt entry was not reported as a failed recovery") },
            { assertEquals(Dot(replica, 1L), first, "a clock over a corrupt entry is dead instead of fresh") },
            { assertTrue(persisted, "a clock over a corrupt entry cannot repair it") },
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
            { assertEquals(Dot(replica, 3L), persisted.tick().dot, "the clear overwrote the durable seq") },
        )
    }
}

/**
 * Delegates to [backing], but refuses every read of [refused] until [heal] — a store whose read
 * keeps failing for a while, as a locked or quota-bound one does. Counts its [refusals] so an arm
 * can assert its rig fired.
 */
private class ReadRefusingStore(private val backing: DurableStore, private val refused: StoreKey) : DurableStore {
    private val lock = reentrantLock()
    private var refusing = true
    private var refusalCount = 0

    val refusals: Int get() = lock.withLock { refusalCount }

    fun heal(): Unit = lock.withLock { refusing = false }

    override suspend fun read(key: StoreKey): ByteArray? {
        val refuse = lock.withLock { (refusing && key == refused).also { if (it) refusalCount++ } }
        if (refuse) throw IllegalStateException("simulated refused read of ${key.name}")
        return backing.read(key)
    }

    override suspend fun write(key: StoreKey, bytes: ByteArray): Unit = backing.write(key, bytes)

    override suspend fun delete(key: StoreKey): Unit = backing.delete(key)
}
