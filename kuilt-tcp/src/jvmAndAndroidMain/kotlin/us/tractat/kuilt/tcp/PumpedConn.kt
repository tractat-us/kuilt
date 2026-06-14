package us.tractat.kuilt.tcp

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.core.fabric.Conn

/**
 * Wrap a *cold, single-reader* [Conn] (e.g. one produced by `:kuilt-stream`'s
 * `framed()`) so its `incoming` flow is **hot and re-collectable**.
 *
 * A `framed()` [Conn] reads bytes with blocking pull reads and must be collected
 * exactly once. But its consumers collect `incoming` twice in sequence —
 * `handshaking` reads the identity preamble with `firstFrame()`, then `identified`
 * installs its own read loop. This wrapper bridges that gap: one pump coroutine
 * collects the cold flow once on [ioDispatcher] and re-publishes frames through an
 * unbounded [Channel], over which [incoming] is a `receiveAsFlow()`. The blocking
 * reads stay on [ioDispatcher], never on the seam's confinement dispatcher.
 *
 * This is the single piece of glue every stream RPC needs to satisfy `handshaking`'s
 * hot-[Conn] contract.
 */
internal fun Conn.pumped(ioDispatcher: CoroutineDispatcher): Conn = PumpedConn(this, ioDispatcher)

private class PumpedConn(
    private val delegate: Conn,
    ioDispatcher: CoroutineDispatcher,
) : Conn {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val inbox = Channel<ByteArray>(Channel.UNLIMITED)

    init {
        scope.launch {
            // A wire close (local close cancels the read; a peer disconnect EOFs it)
            // surfaces as an IOException from the blocking read. Treat any end-of-stream
            // as normal completion of incoming — but let CancellationException propagate.
            runCatchingCancellable { delegate.incoming.collect { inbox.send(it) } }
            inbox.close()
        }
    }

    override suspend fun send(frame: ByteArray) = delegate.send(frame)

    override val incoming: Flow<ByteArray> = inbox.receiveAsFlow()

    // Best-effort teardown: closing a source/sink backed by an already-cancelled
    // channel can throw. close() is idempotent and must not propagate that.
    override suspend fun close() {
        scope.cancel()
        runCatchingCancellable { delegate.close() }
    }
}
