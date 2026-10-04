package us.tractat.kuilt.core.fabric

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import us.tractat.kuilt.core.DeliveryPolicy
import us.tractat.kuilt.core.Spool
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
 * pump coroutine that republishes frames through a bounded [Spool]. Every reader
 * — the preamble read via [firstFrame] and the subsequent read loop — draws from that
 * one spool, so the upstream is never collected twice. Both `handshaking` (2-peer)
 * and `meshSeam` (N-peer) wrap each conn with this before reading.
 *
 * **A read error is surfaced, not folded into a clean end (#2898).** When the delegate's
 * [Connection.incoming] throws — a stream cut inside a frame, an oversize length prefix — the
 * wrapper's [Connection.incoming] delivers every frame that arrived first and then throws the same
 * exception. Only a normal completion of the delegate completes it normally. A reader can therefore
 * tell a cut stream from a clean close, which the TCP wire contract requires (`docs/tcp-wire.md` § 5).
 *
 * @param dispatcher Scopes the pump coroutine, so the preamble drain shares the seam's
 *   (and tests') clock. Production callers pass the seam's scheduling dispatcher; test
 *   callers pass a dispatcher derived from the test scheduler.
 * @param policy Bounds the republish buffer (default [DeliveryPolicy.Reliable]).
 */
internal fun Connection.singleCollection(
    dispatcher: CoroutineContext,
    policy: DeliveryPolicy = DeliveryPolicy.Reliable,
): Connection = SingleCollectionConnection(this, dispatcher, policy)

/**
 * One pump coroutine collects [delegate].incoming exactly once on [dispatcher] and
 * republishes frames through a bounded [Spool]. [firstFrame] takes the first
 * frame off that spool; [incoming] exposes the remainder from the same spool.
 * No consumer ever collects [delegate].incoming directly.
 */
private class SingleCollectionConnection(
    private val delegate: Connection,
    dispatcher: CoroutineContext,
    policy: DeliveryPolicy,
) : Connection {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val inbox = Spool<ByteArray>(policy)

    // Written by the pump BEFORE it closes the spool, read by `incoming` only AFTER the spool has
    // drained, so a reader that sees the spool complete also sees the failure that ended it.
    private val readFailure = atomic<Throwable?>(null)

    init {
        scope.launch {
            // A wire close completes the delegate normally; a cut or refused stream throws. Record a
            // throw so `incoming` can re-raise it after the frames that preceded it (#2898). A
            // cancellation — our own close cancels this pump — propagates and records nothing. The
            // spool is closed in `finally` so `incoming` ends on every exit path.
            try {
                delegate.incoming.collect { inbox.deliver(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                readFailure.value = e
            } finally {
                inbox.close()
            }
        }
    }

    override suspend fun send(frame: ByteArray) = delegate.send(frame)

    // Adds nothing to a frame — only to how `incoming` is consumed — so the delegate's ceiling
    // passes through unchanged.
    override val maxFrameBytes: Int? get() = delegate.maxFrameBytes

    override val incoming: Flow<ByteArray> = flow {
        emitAll(inbox.incoming)
        readFailure.value?.let { throw it }
    }

    // Best-effort teardown: cancel the pump, then close the delegate. close() is idempotent
    // and must not propagate a delegate-close failure on an already-cancelled link.
    override suspend fun close() {
        scope.cancel()
        runCatchingCancellable { delegate.close() }
    }
}
