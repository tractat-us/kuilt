package us.tractat.kuilt.core.fabric

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import kotlin.coroutines.CoroutineContext

/**
 * A 2-peer [Seam] for transports that do NOT carry identity out of band.
 * Sends a [Hello] preamble as the first frame, awaits the peer's preamble,
 * then delegates to [identified] over a [PreambleStrippedConn] that presents
 * only the post-preamble frames to the inner seam. Suspends until the peer's
 * preamble arrives.
 *
 * **Single-collection assumption:** [Conn.incoming] must be collected exactly
 * once. This function calls [Conn.firstFrame] (which collects the first item
 * from [Conn.incoming]) before delegating to [identified], which installs its
 * own collector. Because [connPair] is [kotlinx.coroutines.channels.Channel]-backed
 * (hot/buffered), [firstFrame] consumes the Hello preamble from the underlying
 * channel, leaving application frames for [identified]'s collector. The
 * [PreambleStrippedConn] wrapper therefore presents [delegate.incoming] unchanged —
 * the preamble is already gone. If a future [Conn] implementation uses a cold or
 * replayable [Flow], revisit by buffering the preamble read instead.
 */
public suspend fun handshaking(
    conn: Conn,
    selfId: PeerId,
    dispatcher: CoroutineContext = Dispatchers.Default.limitedParallelism(1),
): Seam {
    conn.send(Hello.encode(selfId))
    val remoteId = Hello.decode(conn.firstFrame())
    return identified(PreambleStrippedConn(conn), selfId, remoteId, dispatcher)
}

/**
 * Wraps a [Conn] to present only the post-preamble [incoming] flow to [identified].
 * For channel-backed [Conn] implementations, the preamble frame is already consumed
 * by [Conn.firstFrame] before this wrapper is used, so [incoming] is forwarded as-is.
 */
private class PreambleStrippedConn(private val delegate: Conn) : Conn {
    override suspend fun send(frame: ByteArray) = delegate.send(frame)
    override val incoming: Flow<ByteArray> = delegate.incoming
    override suspend fun close() = delegate.close()
}
