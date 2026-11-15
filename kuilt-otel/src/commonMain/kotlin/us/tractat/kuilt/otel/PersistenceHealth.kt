package us.tractat.kuilt.otel

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether an exporter is managing to save its data locally.
 *
 * Counts complete persistence turns, not records, series, bytes, or individual store calls.
 * A turn is acknowledged only after all its writes return; a partial write is one failed turn.
 *
 * **For metrics a turn is one kind's write.** [WarpMetricExporter] persists each of its five kinds
 * under its own key, so a [WarpMetricExporter.record] batch counts **one turn per kind it touched**:
 * five updates over three kinds is three turns, not one and not five. A batch the store partly
 * refuses therefore moves both counters — [acknowledged] for each kind that landed, [failed] for
 * each that did not — and a cancelled batch keeps the turns of the kinds already written. A
 * single-point mutator is a one-kind batch, so it is still one turn.
 *
 * **For spans a turn's verdict is per work key too, once the causal clock's work fails.** A
 * [WarpSpanExporter.export] or [WarpSpanExporter.clear] whose writes all run is one turn over the span
 * buffer and its clock together. One that skips the clock's write, because the clock's read has not
 * landed, or an export that wanted a dot from a clock with none left ([ClockTick.Exhausted]) and saved
 * its span unstamped, counts its span work by the span write and its clock work as one [failed] turn
 * under the clock's key (#3426). So a clock refused forever stays in [outstandingFailures] however
 * many spans land, until a turn lands the clock's write. An exhausted clock counts once per export
 * that wanted a dot; a turn that wanted none (a clear, a span with its own stamp) is not counted, and
 * its clock write resolves those failures. A refused clock *write* fails the whole turn, since the
 * span write after it is never made.
 * Metric [WarpMetricExporter.clear] counts its entire delete sequence as a separate turn.
 * Reads, validation failures before persistence, and success without store IO do not count.
 * These counters live only for this exporter instance; they do not measure backend delivery.
 *
 * @property acknowledged Successfully completed persistence turns, cumulative.
 * @property failed Failed persistence turns, cumulative. A span write turn that returns
 *   [ExportResult.Failure] without writing, because the store still refuses the read of the stored
 *   spans ([WarpSpanExporter.recover]), is a failed turn too, and the first span write that lands
 *   resolves it.
 * @property outstandingFailures Failed turns whose persistence work has not subsequently
 *   succeeded. A metric write clears failures only for its own persisted kind (integer and
 *   double sums are separate); a metric clear clears only failed clears. A span-only merge
 *   cannot clear a failed turn that also required the causal clock, nor a failed clock turn; only a
 *   turn that lands the clock's write does. This is not a global
 *   consecutive-failure streak: unrelated success leaves these failures outstanding.
 * @property recoveryFailed Whether a read or decode of persisted state failed: at `recover()`, or
 *   on a later retry of a read that had been refused, which a span write turn or a causal clock's
 *   stamp makes. Sticky, even after the read and writes succeed. The failed read does not itself
 *   increment [failed] or [outstandingFailures]; a write turn it stops does, as [failed] says.
 * @property lastFailure Most recent persistence or recovery cause, retained after success.
 *
 * @sample us.tractat.kuilt.otel.samplePersistenceHealth
 */
public data class PersistenceHealth(
    public val acknowledged: Long = 0L,
    public val failed: Long = 0L,
    public val outstandingFailures: Long = 0L,
    public val recoveryFailed: Boolean = false,
    public val lastFailure: Throwable? = null,
)

/**
 * Local bookkeeping, independent of the reporting latches. The lock keeps the pending work and
 * its published counters in one transition, even when callers use different threads.
 * A key names persistence work, not a metric series; the number of key sets is bounded by callers.
 */
internal class PersistenceHealthTracker<K> {
    private val lock = reentrantLock()
    private val pending = mutableMapOf<Set<K>, Long>()
    private val state = MutableStateFlow(PersistenceHealth())
    internal val health: StateFlow<PersistenceHealth> = state.asStateFlow()

    internal fun acknowledged(keys: Set<K>): Unit = lock.withLock {
        val resolved = pending.keys.filter { keys.containsAll(it) }
        resolved.forEach { pending.remove(it) }
        state.value = state.value.copy(
            acknowledged = state.value.acknowledged + 1,
            outstandingFailures = pending.values.sum(),
        )
    }

    internal fun failed(keys: Set<K>, cause: Throwable): Unit = lock.withLock {
        pending[keys] = (pending[keys] ?: 0L) + 1
        state.value = state.value.copy(
            failed = state.value.failed + 1,
            outstandingFailures = pending.values.sum(),
            lastFailure = cause,
        )
    }

    internal fun recoveryFailed(cause: Throwable): Unit = lock.withLock {
        state.value = state.value.copy(recoveryFailed = true, lastFailure = cause)
    }
}
