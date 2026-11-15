package us.tractat.kuilt.otel

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.StoreKey

/**
 * Delegates to [backing], but refuses every read of [refused] until [heal] — a store whose read
 * keeps failing for a while, as a locked or quota-bound one does. Counts its [refusals] so an arm
 * can assert its rig fired.
 */
internal class ReadRefusingStore(
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

/**
 * Spend [turns] turns of this exporter's refused-read backoff window (#3436) with exports of
 * [filler], a span the calling arm never asserts on, each checked to skip. A turn that read instead
 * would succeed on a healed store, or add to [refusals] on a refusing one, so both are checked.
 *
 * This is the step an arm takes where it means a turn that **reads**: after `k` refused reads in a
 * row the next `2^k − 1` turns skip, so the turn it measures must come after them.
 */
internal suspend fun WarpSpanExporter.skipReadWindow(turns: Int, filler: SpanRecord, refusals: () -> Int = { 0 }) {
    repeat(turns) { turn ->
        val before = refusals()
        val skipped = export(filler)
        check(skipped is ExportResult.Failure) { "the rig: turn $turn of the read window ran: $skipped" }
        check(refusals() == before) { "the rig: turn $turn of the read window read the spans" }
    }
}
