package us.tractat.kuilt.core.fabric

import us.tractat.kuilt.core.DeliveryPolicy
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * A 2-peer [Seam] for transports that do NOT carry identity out of band.
 * Sends a [Hello] preamble as the first frame, awaits the peer's preamble,
 * then delegates to [identified] over the **same single collection** of
 * [Connection.incoming]. Suspends until the peer's preamble arrives.
 *
 * **The preamble is a versioned [Hello] body:** the `kuil` magic, a version, reserved flags, then
 * the [PeerId] as length-prefixed UTF-8. [Hello] carries the byte-level layout.
 *
 * **Refusals.** A remote whose first frame is not a well-formed v1 body (a non-kuilt peer, a pre-v1
 * peer sending its bare UTF-8 id, an unsupported version) makes this function throw a named
 * [HelloFormatException], as does a link that ends before any frame ([HelloAbsentException]). A
 * remote that claims this peer's own [selfId] throws [HelloSelfConnectionException]. **Every exit
 * that does not return a seam closes [conn]** — a refusal, a failed send, and cancellation alike —
 * so neither this caller nor the remote is left holding a half-open link, and nothing further is
 * sent on it. The same shape as the mesh's per-link handshake (#2587).
 *
 * **Single-collection safe.** [Connection.incoming] is collected exactly once: the conn is
 * wrapped with [singleCollection], which starts one pump coroutine that drains the
 * delegate's `incoming` into an internal channel. The preamble is read from that
 * channel, and the post-preamble frames are handed to [identified] from the *same*
 * channel — there is never a second `delegate.incoming.collect`. This makes
 * `handshaking` correct over a cold, single-collection [Connection] (the shape a
 * stream fabric's `framed()` produces) as well as over a hot channel-backed one
 * ([connectionPair][us.tractat.kuilt.test.fabric.connectionPair]). Stream fabrics no longer
 * need a hot-reader pump of their own.
 *
 * @param dispatcher Scopes both the single-collection pump and [identified]'s
 *   read/write loops, so the preamble drain shares the seam's (and tests') clock.
 *   Production callers pass `Dispatchers.Default.limitedParallelism(1)`; test
 *   callers pass a dispatcher derived from the test scheduler.
 * @param policy Governs the returned seam's inbox [us.tractat.kuilt.core.Spool] — handed
 *   straight to [identified]. Defaults to [DeliveryPolicy.Reliable] (bounded, backpressured,
 *   lossless), which is what this function used to hard-wire: it took no policy and called
 *   [identified] without one, so **no stream fabric could expose the knob at all** (#2323) —
 *   `:kuilt-tcp` and every third-party fabric built per `docs/extending-fabrics.md` reach
 *   [identified] only through here.
 *
 *   It governs the **seam inbox**, not the preamble pump: the [singleCollection] wrapper keeps
 *   its own [DeliveryPolicy.Reliable] republish buffer, exactly as `meshSeam`'s per-link
 *   handshake does. So a lossy policy chosen here bounds what the *seam* holds, while frames
 *   still queue up to [DeliveryPolicy.DEFAULT_CAPACITY] deep on the hop below it.
 */
public suspend fun handshaking(
    conn: Connection,
    selfId: PeerId,
    dispatcher: CoroutineContext,
    policy: DeliveryPolicy = DeliveryPolicy.Reliable,
): Seam {
    val single = conn.singleCollection(dispatcher)
    try {
        single.send(Hello.encode(selfId))
        val remoteId = Hello.decode(single.incoming.firstOrNull() ?: throw HelloAbsentException())
        // Self-connection guard (#1488): a peer that dials its own advertised endpoint handshakes a
        // preamble claiming its own id. A 2-peer seam whose "remote" is itself would echo its own frames;
        // refuse it fast rather than weave a degenerate self-seam. Mirrors the mesh/NwSeam self-drop.
        if (remoteId == selfId) throw HelloSelfConnectionException(selfId)
        return identified(single, selfId, remoteId, dispatcher, policy)
    } catch (failure: Throwable) {
        // Shielded so this runs when `failure` IS the cancellation; a plain catch inside, because in
        // here a CancellationException can only be one the conn's own close minted (#1803/#1824).
        // Closing `single` cancels its pump and then closes `conn`, so one close discharges both.
        withContext(NonCancellable) {
            try {
                single.close()
            } catch (_: Throwable) {
                // Best-effort: the conn refusing to close must not replace the failure being reported.
            }
        }
        throw failure
    }
}
