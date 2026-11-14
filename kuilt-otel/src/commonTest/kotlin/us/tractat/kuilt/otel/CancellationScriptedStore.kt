package us.tractat.kuilt.otel

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.StoreKey
import kotlin.time.Duration.Companion.milliseconds

/**
 * The fixture behind the store-minted-cancellation suites of [WarpSpanExporter] and
 * [WarpMetricExporter] (#3388), the same rig #3384 pinned [WarpLogRecordExporter] with.
 *
 * A [DurableStore] may run its own `withTimeout`. When that expires it throws a
 * `TimeoutCancellationException` at a caller whose job is still alive, and only
 * `currentCoroutineContext().ensureActive()` tells that one apart from the caller's own
 * cancellation. So every arm needs both halves: [StoreAct.MINT] for the cancellation the store
 * makes, and [StoreAct.PARK] for the control in which the caller really is cancelled.
 */
internal enum class StoreCall { READ, WRITE, DELETE }

/** What a [CancellationScriptedStore] does to the one call its armed rule matches. */
internal enum class StoreAct {
    /** Throw a `TimeoutCancellationException` from the store's own `withTimeout`. */
    MINT,

    /** Suspend until the caller is cancelled, so the cancellation lands inside the call. */
    PARK,
}

/**
 * Delegates to [backing], except for the **next** call matching the armed rule, which it mints a
 * cancellation on or parks. One-shot, so every call after it sees a healthy store.
 *
 * It mints with a real `withTimeout`, so the exception is the one a store would throw. It counts
 * every firing: [minted], [parked] and [cancelled] are precondition guards that make an arm whose
 * rig never fired red rather than green by absence. They are never the assertion under test.
 */
internal class CancellationScriptedStore(private val backing: DurableStore) : DurableStore {
    private class Rule(val call: StoreCall, val act: StoreAct, val matches: (StoreKey) -> Boolean)

    private val lock = reentrantLock()
    private var rule: Rule? = null
    private var mintedCount = 0
    private var parkedCount = 0
    private var cancelledCount = 0

    val minted: Int get() = lock.withLock { mintedCount }
    val parked: Int get() = lock.withLock { parkedCount }
    val cancelled: Int get() = lock.withLock { cancelledCount }

    fun arm(call: StoreCall, act: StoreAct, matches: (StoreKey) -> Boolean): Unit =
        lock.withLock { rule = Rule(call, act, matches) }

    private suspend fun intercept(call: StoreCall, key: StoreKey) {
        val act = lock.withLock {
            val armed = rule ?: return@withLock null
            if (armed.call != call || !armed.matches(key)) return@withLock null
            rule = null
            armed.act
        } ?: return
        when (act) {
            StoreAct.MINT -> {
                lock.withLock { mintedCount++ }
                // The caller's job stays alive: only this inner scope is cancelled, and
                // `withTimeout` throws its TimeoutCancellationException at the caller.
                withTimeout(1.milliseconds) { awaitCancellation() }
            }
            StoreAct.PARK -> {
                lock.withLock { parkedCount++ }
                try {
                    awaitCancellation()
                } finally {
                    // `awaitCancellation` leaves only by cancellation, so this counts exactly the
                    // cancellations that reached the parked call.
                    lock.withLock { cancelledCount++ }
                }
            }
        }
    }

    override suspend fun read(key: StoreKey): ByteArray? {
        intercept(StoreCall.READ, key)
        return backing.read(key)
    }

    override suspend fun write(key: StoreKey, bytes: ByteArray) {
        intercept(StoreCall.WRITE, key)
        backing.write(key, bytes)
    }

    override suspend fun delete(key: StoreKey) {
        intercept(StoreCall.DELETE, key)
        backing.delete(key)
    }
}

/** What [escapeOf] saw: the value [block] returned, or the cancellation that escaped it. */
internal class Escape<T>(val returned: T?, val escaped: CancellationException?)

/** Run [block], turning a cancellation that escapes it while this job is alive into a value. */
internal suspend fun <T> escapeOf(block: suspend () -> T): Escape<T> = try {
    Escape(block(), null)
} catch (escaped: CancellationException) {
    // Our own job is alive, so this falls through and records what escaped.
    currentCoroutineContext().ensureActive()
    Escape(null, escaped)
}

/**
 * Run [block] in a child job, cancel that job once [store] has parked a call, and return what
 * [block] returned: `null` when it was cancelled, as it must be.
 */
internal suspend fun <T : Any> TestScope.cancelInside(store: CancellationScriptedStore, block: suspend () -> T): T? {
    val parkedBefore = store.parked
    var returned: T? = null
    val job: Job = launch { returned = block() }
    runCurrent()
    if (store.parked > parkedBefore) job.cancelAndJoin() else job.join()
    return returned
}
