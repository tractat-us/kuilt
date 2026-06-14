package us.tractat.kuilt.core.fabric

import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import kotlin.coroutines.CoroutineContext

/**
 * A 2-peer [Seam] for transports that do NOT carry identity out of band.
 * Sends a [Hello] preamble as the first frame, awaits the peer's preamble,
 * then delegates to [identified] over the **same single collection** of
 * [Conn.incoming]. Suspends until the peer's preamble arrives.
 *
 * **Single-collection safe.** [Conn.incoming] is collected exactly once: the conn is
 * wrapped with [singleCollection], which starts one pump coroutine that drains the
 * delegate's `incoming` into an internal channel. The preamble is read from that
 * channel, and the post-preamble frames are handed to [identified] from the *same*
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
    val single = conn.singleCollection(dispatcher)
    val remoteId = Hello.decode(single.firstFrame())
    return identified(single, selfId, remoteId, dispatcher)
}
