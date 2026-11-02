package us.tractat.kuilt.multipeer.bridge

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PumpVerdict
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.core.pumpIn

private val log = KotlinLogging.logger("us.tractat.kuilt.multipeer.bridge.BridgeSessionState")

/**
 * Stands between a pump and the JVM callback it calls, with one lock held for the length of each call. So
 * swapping the callback, or removing it, waits for a call in flight, and no call can start on a removed one.
 *
 * The lock being reentrant is what makes this usable from inside the callback: on that thread the swap
 * goes straight through, where a join on the pump would wait for itself. A callback that hands the call to
 * another thread and blocks on it is not told apart, and hangs.
 */
private class CallbackGate<T> {
    private val lock = reentrantLock()
    private var callback: ((T) -> Unit)? = null

    fun deliver(item: T) = lock.withLock { callback?.invoke(item) }

    /** [deliver], but only while [to] is still the callback, for a pump that belongs to that one. */
    fun deliver(item: T, to: (T) -> Unit) = lock.withLock { if (callback === to) to(item) }

    fun replace(next: ((T) -> Unit)?) = lock.withLock { callback = next }
}

/** One peer arriving at or leaving a session, as one call into the JVM. */
internal class PeerChange(val peer: PeerId, val connected: Boolean)

/**
 * What lives behind a session-handle pointer in the JVM ↔ macOS MC bridge.
 *
 * The base session handle just needs to wrap a [Seam] (so `broadcast`
 * and `close` work). Once the JVM side wires data + peer-state callbacks
 * (`mc_session_set_data_callback`, `mc_session_set_peer_state_callback`),
 * the K/N coroutines that pump those events live here too, each behind a [CallbackGate].
 *
 * A collector is inside the JVM for as long as its callback runs, which cancellation cannot interrupt, so
 * ending a callback means waiting for it. These calls do, on the JNA caller's thread, with no bound: a bound
 * would return with a callback possibly still running, and the caller may then release that callback's JNA
 * trampoline. A callback that never returns therefore hangs the call.
 *
 * This is not the join `mc_browser_stop` uses, because `BridgePeerLink` can close a session from inside its
 * own peer-state callback: a consumer's unconfined collector closing a seam it sees go `Torn` runs inside
 * that callback, and a pump cannot join itself. `link.incoming` is single-collection by the `Seam` contract,
 * so the data callback is replaced behind one pump rather than by starting a second collector.
 *
 * The gate is passed once per call into the JVM. One roster update can name several peers, so the pump works
 * out what changed and delivers each change on its own: a close from inside the first ends the rest.
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
    private val data = CallbackGate<Swatch>()
    private val peerState = CallbackGate<PeerChange>()
    private var dataPump: Job? = null
    private var peerStatePump: Job? = null

    /** Attaches the data callback. See `mc_session_set_data_callback` for what the caller is promised. */
    fun replaceDataCallback(callback: (Swatch) -> Unit) {
        data.replace(callback)
        lock.withLock {
            if (dataPump == null) dataPump = pump("mc-session-data", link.incoming) { frame -> data.deliver(frame) }
        }
    }

    /**
     * Attaches the peer-state callback, and restarts its pump so a new callback hears the current roster:
     * `link.peers` holds a state and replays it to a new collector, where an unchanged pump would say nothing
     * until the roster next moved. An old pump that outlives its cancel delivers nothing: what it has left to
     * say, the new pump says too.
     */
    fun replacePeerStateCallback(callback: (PeerChange) -> Unit) {
        peerState.replace(callback)
        lock.withLock {
            peerStatePump?.cancel()
            var seen = setOf(link.selfId)
            peerStatePump = pump("mc-session-peer-state", link.peers) { current ->
                for (peer in current - seen - link.selfId) peerState.deliver(PeerChange(peer, connected = true), callback)
                for (peer in seen - current - link.selfId) peerState.deliver(PeerChange(peer, connected = false), callback)
                seen = current
            }
        }
    }

    /**
     * Cancels the pumps and removes both callbacks, blocking until neither is running and neither can run
     * again. Called from inside one of them, it waits only for the other. That callback's own frames are
     * still running when this returns, and it is gone once they do.
     */
    fun endCallbacks() {
        scope.cancel()
        data.replace(null)
        peerState.replace(null)
    }

    private fun <T> pump(name: String, items: Flow<T>, body: (T) -> Unit): Job =
        items.pumpIn(
            scope = scope,
            onFailure = { phase, failure ->
                log.error(failure) { "mc.session.pump.failed pump=$name phase=$phase" }
                PumpVerdict.CONTINUE
            },
            name = name,
        ) { item -> body(item) }
}
