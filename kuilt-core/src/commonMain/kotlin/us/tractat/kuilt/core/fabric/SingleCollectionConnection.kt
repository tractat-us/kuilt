package us.tractat.kuilt.core.fabric

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import us.tractat.kuilt.core.DeliveryPolicy
import us.tractat.kuilt.core.FrameOverflow
import us.tractat.kuilt.core.Overflow
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
 * pump coroutine that republishes frames through a bounded [Channel] governed by [policy].
 * Every reader — the preamble read via [firstFrame] and the subsequent read loop — draws from
 * that one channel, so the upstream is never collected twice. Both `handshaking` (2-peer)
 * and `meshSeam` (N-peer) wrap each conn with this before reading.
 *
 * The [policy] controls the inbound buffer capacity and overflow behaviour — the same semantics
 * as [us.tractat.kuilt.core.Spool] but operating on raw [ByteArray] frames at the [Connection]
 * layer, before peer identity and sequence numbers are stamped on by the [us.tractat.kuilt.core.Seam].
 *
 * @param dispatcher Scopes the pump coroutine, so the preamble drain shares the seam's
 *   (and tests') clock. Production callers pass the seam's scheduling dispatcher; test
 *   callers pass a dispatcher derived from the test scheduler.
 * @param policy Controls the pump's inbound buffer capacity and overflow strategy.
 *   Defaults to [DeliveryPolicy.Reliable] (bounded, backpressured, lossless).
 */
internal fun Connection.singleCollection(
    dispatcher: CoroutineContext,
    policy: DeliveryPolicy = DeliveryPolicy.Reliable,
): Connection = SingleCollectionConnection(this, dispatcher, policy)

/**
 * One pump coroutine collects [delegate].incoming exactly once on [dispatcher] and
 * republishes frames through a bounded [Channel] governed by [policy]. [firstFrame] takes
 * the first frame off that channel; [incoming] exposes the remainder from the same channel.
 * No consumer ever collects [delegate].incoming directly.
 *
 * Delivery follows the same policy semantics as [us.tractat.kuilt.core.Spool]:
 * - `SUSPEND` — the pump suspends until the consumer drains (backpressure).
 * - `DROP_OLDEST` / `DROP_LATEST` — the channel silently discards per the policy; the pump
 *   never suspends.
 * - `FAIL` — throws [FrameOverflow] when the buffer is full and the receiver is not draining.
 */
private class SingleCollectionConnection(
    private val delegate: Connection,
    dispatcher: CoroutineContext,
    policy: DeliveryPolicy,
) : Connection {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val inbox = boundedChannel(policy)

    init {
        scope.launch {
            try {
                // A wire close (peer disconnect EOFs the read; local close cancels it) surfaces
                // as a delegate completion or exception — treat end-of-stream as normal completion
                // of incoming, but let CancellationException and FrameOverflow propagate.
                delegate.incoming.collect { frame -> deliverFrame(inbox, frame, policy) }
            } finally {
                // Always close the inbox so [incoming] completes regardless of how the pump exits
                // (delegate completion, CancellationException, or FrameOverflow on FAIL policy).
                inbox.close()
            }
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

/**
 * Construct a bounded [Channel] with capacity and overflow behaviour from [policy].
 * Mirrors [us.tractat.kuilt.core.Spool]'s channel construction at the [ByteArray] layer.
 */
private fun boundedChannel(policy: DeliveryPolicy): Channel<ByteArray> =
    if (policy.overflow == Overflow.FAIL) {
        // FAIL uses trySend with manual overflow detection; configure the channel as SUSPEND
        // so trySend can distinguish "full" (isFailure && !isClosed) from "closed" (isClosed).
        Channel(capacity = policy.capacity, onBufferOverflow = BufferOverflow.SUSPEND)
    } else {
        Channel(capacity = policy.capacity, onBufferOverflow = policy.overflow.toBufferOverflow())
    }

/**
 * Deliver one frame to [inbox] according to [policy] overflow semantics.
 *
 * - `SUSPEND` and `DROP_*` delegate to [Channel.send] (suspend or discard per the channel's
 *   `onBufferOverflow` setting). A concurrently closed channel (receiver left) is silently dropped.
 * - `FAIL` uses [Channel.trySend]: a genuinely full buffer throws [FrameOverflow]; a closed
 *   channel (receiver gone) is silently dropped.
 */
private suspend fun deliverFrame(inbox: Channel<ByteArray>, frame: ByteArray, policy: DeliveryPolicy) {
    when (policy.overflow) {
        Overflow.FAIL -> {
            val result = inbox.trySend(frame)
            if (result.isFailure && !result.isClosed) {
                throw FrameOverflow("single-collection delivery buffer full (capacity=${policy.capacity})")
            }
        }
        else -> try {
            inbox.send(frame)
        } catch (_: ClosedSendChannelException) {
            // Receiver closed concurrently — drop, matching best-effort fabric semantics.
        }
    }
}

private fun Overflow.toBufferOverflow(): BufferOverflow = when (this) {
    Overflow.SUSPEND -> BufferOverflow.SUSPEND
    Overflow.DROP_OLDEST -> BufferOverflow.DROP_OLDEST
    Overflow.DROP_LATEST -> BufferOverflow.DROP_LATEST
    Overflow.FAIL -> error("FAIL uses trySend; toBufferOverflow() must not be called for it")
}
