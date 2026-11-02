package us.tractat.kuilt.multipeer.bridge

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PumpVerdict
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.core.pumpIn
import kotlin.native.concurrent.ThreadLocal

private val log = KotlinLogging.logger("us.tractat.kuilt.multipeer.bridge.BridgeSessionState")

/**
 * The pump whose JVM callback this thread is inside, if any. It is what lets a teardown tell a call from
 * inside a callback, which cannot wait for that callback. A thread-local is sound here because a callback
 * is one synchronous call: it is set and cleared with no suspension in between.
 */
@ThreadLocal
private object CallbackFrame {
    var pump: Job? = null
}

/**
 * What lives behind a session-handle pointer in the JVM ↔ macOS MC bridge.
 *
 * The base session handle just needs to wrap a [Seam] (so `broadcast`
 * and `close` work). Once the JVM side wires data + peer-state callbacks
 * (`mc_session_set_data_callback`, `mc_session_set_peer_state_callback`),
 * the K/N coroutines that pump those events live here too, in one [PumpSlot] per callback.
 *
 * A collector is inside the JVM for as long as its callback runs, which cancellation cannot interrupt, so
 * ending a pump means waiting for it. [cancelAndAwaitPumps] and both `replace` calls do, on the JNA caller's
 * thread, with no bound: a bound would return with a callback possibly still running, and the caller may
 * then release that callback's JNA trampoline. A callback that never returns therefore hangs the call.
 *
 * One wait cannot be made. A call from inside a callback is running on that callback's pump, so waiting
 * for it would never return, and `BridgePeerLink` can reach that case: a consumer's unconfined collector
 * closing a seam it sees go `Torn` runs inside the peer-state callback. [awaitFinished] leaves out that one
 * pump, found through [CallbackFrame]. A callback that hands the call to another thread and blocks on it
 * is not told apart, and hangs.
 *
 * Internal-only; the JVM never sees this type.
 *
 * @param dispatcher Dispatcher for the [scope]. Production default is
 *   [Dispatchers.Default]; tests inject [kotlinx.coroutines.test.UnconfinedTestDispatcher].
 */
internal class BridgeSessionState(
    val link: Seam,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    val scope: CoroutineScope = CoroutineScope(dispatcher + SupervisorJob())

    private val lock = reentrantLock()
    private val data = PumpSlot("mc-session-data")
    private val peerState = PumpSlot("mc-session-peer-state")

    /** Replaces the data pump. See `mc_session_set_data_callback` for what the caller is promised. */
    fun replaceDataPump(body: (Swatch) -> Unit) = data.replace(link.incoming, body)

    /** Replaces the peer-state pump. See `mc_session_set_data_callback` for what the caller is promised. */
    fun replacePeerStatePump(body: (Set<PeerId>) -> Unit) = peerState.replace(link.peers, body)

    /** Cancels every pump and blocks until each has finished, leaving out the one making this call, if any. */
    fun cancelAndAwaitPumps() {
        val pumps = scope.coroutineContext.job
        pumps.cancel()
        awaitFinished(pumps.children.toList())
    }

    private fun awaitFinished(pumps: List<Job>) {
        val own = CallbackFrame.pump
        runBlocking { pumps.filter { it !== own }.joinAll() }
    }

    /**
     * One callback's pumps: the live one, and any it replaced that may not have finished.
     *
     * A new pump starts by waiting for all of them, so no two callbacks of one slot can overlap, even when
     * two threads replace at once or the replacement comes from inside the callback being replaced. That
     * wait is cancellable, which is what lets a close from inside the callback finish: the new pump is
     * waiting for that callback, and the close is waiting for the new pump.
     */
    private inner class PumpSlot(private val name: String) {
        private var pumps = emptyList<Job>()

        fun <T> replace(items: Flow<T>, body: (T) -> Unit) {
            val retiring = lock.withLock {
                val unfinished = pumps.filterNot { it.isCompleted }
                pumps = unfinished + pumpAfter(unfinished, items, body)
                unfinished
            }
            retiring.forEach { it.cancel() }
            awaitFinished(retiring)
        }

        private fun <T> pumpAfter(retiring: List<Job>, items: Flow<T>, body: (T) -> Unit): Job =
            flow {
                retiring.joinAll()
                emitAll(items)
            }.pumpIn(
                scope = scope,
                onFailure = { phase, failure ->
                    log.error(failure) { "mc.session.pump.failed pump=$name phase=$phase" }
                    PumpVerdict.CONTINUE
                },
                name = name,
            ) { item ->
                val outer = CallbackFrame.pump
                CallbackFrame.pump = currentCoroutineContext().job
                try {
                    body(item)
                } finally {
                    CallbackFrame.pump = outer
                }
            }
    }
}
