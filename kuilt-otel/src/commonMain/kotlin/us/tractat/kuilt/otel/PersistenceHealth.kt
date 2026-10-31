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
 * Metric [WarpMetricExporter.clear] counts its entire delete sequence as a separate turn.
 * Reads, validation failures before persistence, and success without store IO do not count.
 * These counters live only for this exporter instance; they do not measure backend delivery.
 *
 * @property acknowledged Successfully completed persistence turns, cumulative.
 * @property failed Failed persistence turns, cumulative.
 * @property outstandingFailures Failed turns whose persistence work has not subsequently
 *   succeeded. A metric write clears failures only for its own persisted kind (integer and
 *   double sums are separate); a metric clear clears only failed clears. A span-only merge
 *   cannot clear a failed turn that also required the causal clock. This is not a global
 *   consecutive-failure streak: unrelated success leaves these failures outstanding.
 * @property recoveryFailed Whether a startup read or decode failed. Sticky, even after writes
 *   succeed; a failed recovery does not increment [failed] or [outstandingFailures].
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
