package us.tractat.kuilt.core.fabric

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import us.tractat.kuilt.core.runCatchingCancellable
import kotlin.coroutines.CoroutineContext

/**
 * Adapt a [Connection] so its [incoming] can be consumed by *several* readers over a
 * single upstream collection.
 *
 * A raw fabric [Connection] is single-collection ([Connection.incoming] may only be collected
 * once). Several call sites need two collections of the same conn: a preamble read
 * (`firstFrame`) followed by a read loop. Over a *hot* channel-backed conn the two
 * collections happen to coexist; over a *cold* single-collection conn (the shape a
 * stream fabric's `framed()` produces) the second collection hangs — or, if the conn
 * defends itself, throws.
 *
 * [singleCollection] resolves this by collecting [Connection.incoming] **exactly once** in a
 * pump coroutine that republishes frames through an unbounded [Channel]. Every reader
 * — the preamble read via [firstFrame] and the subsequent read loop — draws from that
 * one channel, so the upstream is never collected twice. Both `handshaking` (2-peer)
 * and `meshSeam` (N-peer) wrap each conn with this before reading.
 *
 * @param dispatcher Scopes the pump coroutine, so the preamble drain shares the seam's
 *   (and tests') clock. Production callers pass the seam's scheduling dispatcher; test
 *   callers pass a dispatcher derived from the test scheduler.
 */
internal fun Connection.singleCollection(dispatcher: CoroutineContext): Connection =
    SingleCollectionConnection(this, dispatcher)

/**
 * One pump coroutine collects [delegate].incoming exactly once on [dispatcher] and
 * republishes frames through an unbounded [Channel]. [firstFrame] takes the first
 * frame off that channel; [incoming] exposes the remainder from the same channel.
 * No consumer ever collects [delegate].incoming directly.
 */
private class SingleCollectionConnection(
    private val delegate: Connection,
    dispatcher: CoroutineContext,
) : Connection {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val inbox = Channel<ByteArray>(Channel.UNLIMITED)

    init {
        scope.launch {
            // A wire close (peer disconnect EOFs the read; local close cancels it) surfaces
            // as a delegate completion or exception — treat end-of-stream as normal completion
            // of incoming, but let CancellationException propagate.
            runCatchingCancellable { delegate.incoming.collect { inbox.send(it) } }
            inbox.close()
        }
    }

    override suspend fun send(frame: ByteArray) = delegate.send(frame)

    override val incoming: Flow<ByteArray> = inbox.receiveAsFlow()

    // Best-effort teardown: cancel the pump, then close the delegate. close() is idempotent
    // and must not propagate a delegate-close failure on an already-cancelled link.
    override suspend fun close() {
        scope.cancel()
        runCatchingCancellable { delegate.close() }
    }
}
