package us.tractat.kuilt.core

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * The one sanctioned per-receiver inbound buffer for in-process delivery.
 *
 * A `Spool` holds incoming frames for a single receiver, the way a spool of thread feeds a
 * loom — and, like the print-spool sense of the word, it is a bounded producer/consumer queue.
 * Each arriving frame is handed to [deliver], and the receiver collects [incoming] exactly once
 * (single-collection FIFO).
 *
 * It is generic in the frame type [T] because kuilt delivers at two layers, both with the same
 * unbounded-growth risk: the multi-peer **Seam** layer (`Spool<Swatch>`) and the point-to-point
 * **[us.tractat.kuilt.core.fabric.Connection]** transport SPI (`Spool<ByteArray>`). Both — and any
 * third-party fabric implementor — route delivery through one `Spool` rather than re-deriving a
 * bounded channel.
 *
 * It is **always bounded** — its capacity and overflow behaviour come from the injected
 * [DeliveryPolicy] (default [DeliveryPolicy.Reliable], capacity [DeliveryPolicy.DEFAULT_CAPACITY]).
 * There is no unbounded `Spool`: that footgun (an inbound queue growing without backpressure until
 * it exhausts the heap) is structurally unrepresentable.
 *
 * Overflow behaviour follows the policy:
 *  - `SUSPEND` suspends the delivering caller until the receiver drains (backpressure).
 *  - `DROP_OLDEST` / `DROP_LATEST` never suspend — the channel discards per the policy.
 *  - `FAIL` throws [FrameOverflow] when the buffer is genuinely full.
 *
 * A receiver that closes concurrently (left the mesh) is treated as a drop, not an error — matching
 * best-effort fabric delivery: the frame is simply discarded rather than surfaced to the broadcaster.
 */
public class Spool<T>(private val policy: DeliveryPolicy) {
    private val channel: Channel<T> =
        if (policy.overflow == Overflow.FAIL) {
            Channel(capacity = policy.capacity, onBufferOverflow = BufferOverflow.SUSPEND)
        } else {
            Channel(capacity = policy.capacity, onBufferOverflow = policy.overflow.toBufferOverflow())
        }

    /** The single-collection FIFO stream of delivered frames. Collect once per `Spool`. */
    public val incoming: Flow<T> = channel.receiveAsFlow()

    /** Deliver one frame to the receiver, applying the [DeliveryPolicy]'s overflow behaviour. */
    public suspend fun deliver(frame: T) {
        when (policy.overflow) {
            Overflow.FAIL -> {
                val result = channel.trySend(frame)
                // A closed channel means the receiver went away (left the mesh) — a drop, like
                // any departed peer. Only a genuinely full buffer is the FAIL signal.
                if (result.isFailure && !result.isClosed) {
                    throw FrameOverflow("delivery buffer full (capacity=${policy.capacity})")
                }
            }
            // SUSPEND suspends; DROP_* never suspend (the channel handles overflow). A receiver
            // that closes concurrently races the send — it is gone, so the frame is dropped (as
            // the pre-bounded `trySend` did) rather than surfaced to the broadcasting caller.
            else -> try {
                channel.send(frame)
            } catch (e: ClosedSendChannelException) {
                // receiver closed concurrently — drop
            }
        }
    }

    /** Close the spool; [incoming] completes. */
    public fun close() {
        channel.close()
    }
}

private fun Overflow.toBufferOverflow(): BufferOverflow = when (this) {
    Overflow.SUSPEND -> BufferOverflow.SUSPEND
    Overflow.DROP_OLDEST -> BufferOverflow.DROP_OLDEST
    Overflow.DROP_LATEST -> BufferOverflow.DROP_LATEST
    Overflow.FAIL -> error("FAIL is enforced in deliver(); toBufferOverflow() must not be called for it")
}
