package us.tractat.kuilt.core.fabric

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.runCatchingCancellable
import kotlin.coroutines.CoroutineContext

/**
 * A 2-peer [Seam] for transports that do NOT carry identity out of band.
 * Sends a [Hello] preamble as the first frame, awaits the peer's preamble,
 * then delegates to [identified] over the **same single collection** of
 * [Conn.incoming]. Suspends until the peer's preamble arrives.
 *
 * **Single-collection safe.** [Conn.incoming] is collected exactly once: a
 * [SingleCollectionConn] starts one pump coroutine that drains the delegate's
 * `incoming` into an internal channel. The preamble is read from that channel,
 * and the post-preamble frames are handed to [identified] from the *same*
 * channel — there is never a second `delegate.incoming.collect`. This makes
 * `handshaking` correct over a cold, single-collection [Conn] (the shape a
 * stream fabric's `framed()` produces) as well as over a hot channel-backed one
 * ([connPair][us.tractat.kuilt.test.fabric.connPair]). Stream fabrics no longer
 * need a hot-reader pump of their own.
 *
 * @param dispatcher Scopes both the single-collection pump and [identified]'s
 *   read/write loops, so the preamble drain shares the seam's (and tests') clock.
 *   Production callers pass `Dispatchers.Default.limitedParallelism(1)`; test
 *   callers pass a dispatcher derived from the test scheduler.
 */
public suspend fun handshaking(
    conn: Conn,
    selfId: PeerId,
    dispatcher: CoroutineContext,
): Seam {
    conn.send(Hello.encode(selfId))
    val single = SingleCollectionConn(conn, dispatcher)
    val remoteId = Hello.decode(single.firstFrame())
    return identified(single, selfId, remoteId, dispatcher)
}

/**
 * Adapts a [Conn] so its [incoming] can be consumed by both the preamble read and
 * [identified]'s read loop over **one** upstream collection.
 *
 * One pump coroutine collects [delegate].incoming exactly once on [dispatcher] and
 * republishes frames through an unbounded [Channel]. [firstFrame] takes the first
 * frame off that channel; [incoming] exposes the remainder from the same channel.
 * No consumer ever collects [delegate].incoming directly.
 */
private class SingleCollectionConn(
    private val delegate: Conn,
    dispatcher: CoroutineContext,
) : Conn {
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
